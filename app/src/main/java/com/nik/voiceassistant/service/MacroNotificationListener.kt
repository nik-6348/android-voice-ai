package com.nik.voiceassistant.service

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.data.macro.MacroDispatch
import com.nik.voiceassistant.data.macro.MacroEngine
import com.nik.voiceassistant.data.macro.Trigger
import com.nik.voiceassistant.security.Redactor

/**
 * Lets a macro trigger on a notification ("jab WhatsApp par Papa ka message aaye…"). The user turns
 * this on once in Settings > Notification access, same as any notification-reading app. Title and
 * text are only ever used in memory to decide whether a trigger matches; nothing here writes them to
 * disk or a log unredacted (NotificationReceived.describe(), what the saved trigger shows, never
 * includes the matched text itself — only the filter the user set up).
 */
class MacroNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MacroNotifications"

        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, MacroNotificationListener::class.java).flattenToString()
            return try {
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) &&
                    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
                        .split(':').any { it.equals(me, ignoreCase = true) }
            } catch (e: Exception) {
                false
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            handle(n)
        } catch (e: Exception) {
            // One odd notification (a malformed extras bundle on some OEM app) must not take the listener down
            Log.w(TAG, "Couldn't read a notification: ${e.javaClass.simpleName}")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // never trigger on our own notifications
        val extras = sbn.notification?.extras ?: return
        val title = Redactor.forModel(extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())
        val text = Redactor.forModel(extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
        if (title.isBlank() && text.isBlank()) return
        val appLabel = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            sbn.packageName
        }

        Log.d(TAG, "Notification received: pkg=${sbn.packageName} app='$appLabel' title='$title'")

        val matches = MacroEngine.matching(applicationContext) { trigger ->
            trigger is Trigger.NotificationReceived && triggerMatches(trigger, sbn.packageName, appLabel, title, text)
        }
        Log.d(TAG, "Macro matches for '${sbn.packageName}': ${matches.size}")
        if (matches.isEmpty()) return
        val executor = VoiceSession.get(applicationContext).getToolExecutor()
        val triggerData = buildMap {
            put("app", appLabel)
            if (title.isNotBlank()) put("from", title)
            if (text.isNotBlank()) put("content", text)
        }
        for (macro in matches) {
            Log.i(TAG, "Firing macro '${macro.title}' (id=${macro.id}) for notification from ${sbn.packageName}")
            MacroDispatch.run(applicationContext, macro, executor, triggerData)
        }
    }

    /**
     * A trigger with no app filter would fire on every notification from every app, so it needs at
     * least a package or a visible app name to narrow it down; title/text are optional extra filters.
     */
    private fun triggerMatches(t: Trigger.NotificationReceived, pkg: String, appLabel: String, title: String, text: String): Boolean {
        if (t.packageName.isNullOrBlank() && t.appLabel.isNullOrBlank()) return false
        t.packageName?.takeIf { it.isNotBlank() }?.let { if (!it.equals(pkg, ignoreCase = true)) return false }
        t.appLabel?.takeIf { it.isNotBlank() }?.let { if (!appLabel.contains(it, ignoreCase = true)) return false }
        t.titleContains?.takeIf { it.isNotBlank() }?.let { if (!title.contains(it, ignoreCase = true)) return false }
        t.textContains?.takeIf { it.isNotBlank() }?.let { if (!text.contains(it, ignoreCase = true)) return false }
        return true
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
