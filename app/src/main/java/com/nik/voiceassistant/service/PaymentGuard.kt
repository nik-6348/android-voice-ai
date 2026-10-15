package com.nik.voiceassistant.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nik.voiceassistant.R
import com.nik.voiceassistant.data.trace.AgentTrace

/**
 * Payment and banking apps (PhonePe, Google Pay, Paytm, bank apps…) refuse to pay while any
 * screen-control (accessibility) service is on, as protection against screen-reading malware.
 * So while one of them is open, the agent switches its own screen control off, and switches it
 * back on once the user has left it. No list is built in: payment apps are the ones that handle
 * UPI payment links (upi://pay) on this phone, plus any apps the user adds in Settings.
 *
 * Fully automatic with two one-time permissions (granted over adb, see Settings): changing its own
 * accessibility setting (WRITE_SECURE_SETTINGS) and seeing which app is open (usage access).
 * Without them it still steps aside (disableSelf), and a notification turns it back on.
 */
object PaymentGuard {

    private const val TAG = "PaymentGuard"
    private const val PREFS = "payment_guard"
    private const val CHANNEL = "payment_guard"
    private const val NOTIFICATION_ID = 4711
    private const val ACTION_RESUME = "com.nik.voiceassistant.action.RESUME_SCREEN_CONTROL"
    private const val POLL_MS = 1500L

    /** The payment app screen control was paused for, or null. */
    @Volatile var pausedFor: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cachedApps: Set<String> = emptySet()
    @Volatile private var cachedAt = 0L

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean("enabled", true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        if (!enabled) resume(context)
    }

    /** Apps the user added by hand (banking apps without UPI, etc.). */
    fun extraApps(context: Context): Set<String> = prefs(context).getStringSet("extra", emptySet()).orEmpty()

    fun setExtraApps(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet("extra", packages).apply()
        cachedAt = 0L
    }

    /** Payment apps on this phone: whatever handles upi://pay links, plus the user's additions. */
    fun protectedApps(context: Context): Set<String> {
        if (System.currentTimeMillis() - cachedAt < 10 * 60_000L && cachedApps.isNotEmpty()) return cachedApps + extraApps(context)
        val pm = context.packageManager
        val upi = Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay"))
        @Suppress("DEPRECATION")
        val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(upi, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else pm.queryIntentActivities(upi, PackageManager.MATCH_ALL)
        cachedApps = handlers.map { it.activityInfo.packageName }.toSet() - context.packageName
        cachedAt = System.currentTimeMillis()
        return cachedApps + extraApps(context)
    }

    /** Names of the protected apps, for Settings. */
    fun protectedAppNames(context: Context): List<String> = protectedApps(context).map { pkg ->
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }.sorted()

    /** Can switch screen control off and on by itself (WRITE_SECURE_SETTINGS, granted over adb). */
    fun canToggle(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Can see which app is open, to switch back on after the payment app (usage access). */
    fun canWatch(context: Context): Boolean {
        val ops = context.getSystemService(android.app.AppOpsManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** An app came to the front (from the screen-control service). */
    fun onForeground(service: PhoneAgentService, packageName: String) {
        if (pausedFor != null || !isEnabled(service) || packageName !in protectedApps(service)) return
        pause(service, packageName)
    }

    private fun pause(service: PhoneAgentService, packageName: String) {
        val context = service.applicationContext
        pausedFor = packageName
        // Kept on disk: if this process dies meanwhile, the next start still knows to switch back on
        prefs(context).edit().putString("paused_for", packageName).apply()
        val name = appName(context, packageName)
        AgentTrace.log(AgentTrace.Kind.WARNING, "Screen control paused: $name is a payment app and blocks payments while it is on")
        Log.i(TAG, "Pausing screen control for $packageName")
        if (canToggle(context)) setServiceEnabled(context, false) else service.disableSelf()
        notify(context, name)
        watch(context)
    }

    /** Switches screen control back on (after the payment app, from the notification, or when the app opens). */
    fun resume(context: Context) {
        val was = pausedFor ?: prefs(context).getString("paused_for", null) ?: return
        pausedFor = null
        prefs(context).edit().remove("paused_for").apply()
        main.removeCallbacksAndMessages(null)
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        if (canToggle(context)) {
            setServiceEnabled(context, true)
            AgentTrace.log(AgentTrace.Kind.NETWORK, "Screen control back on after ${appName(context, was)}")
        } else {
            // Without the permission only the user can switch it back on
            runCatching {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** At app start: a pause left over from before (the process died meanwhile) keeps being watched. */
    fun restore(context: Context) {
        val paused = prefs(context).getString("paused_for", null) ?: return
        pausedFor = paused
        if (foregroundApp(context)?.let { it !in protectedApps(context) } != false) resume(context) else watch(context)
    }

    /** Checks the app in front every POLL_MS; once it is no longer a payment app, switches back on. */
    private fun watch(context: Context) {
        if (!canWatch(context)) return
        main.removeCallbacksAndMessages(null)
        main.postDelayed(object : Runnable {
            override fun run() {
                val paused = pausedFor ?: return
                val front = foregroundApp(context)
                if (front != null && front != paused && front !in protectedApps(context)) resume(context)
                else main.postDelayed(this, POLL_MS)
            }
        }, POLL_MS)
    }

    /** The app most recently brought to the front (usage events of the last minute). */
    private fun foregroundApp(context: Context): String? {
        val usage = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val now = System.currentTimeMillis()
        val events = usage.queryEvents(now - 60_000, now)
        val event = UsageEvents.Event()
        var last: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) last = event.packageName
        }
        return last
    }

    private fun setServiceEnabled(context: Context, enabled: Boolean) {
        val me = ComponentName(context, PhoneAgentService::class.java).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && !it.equals(me, ignoreCase = true) }
        val next = if (enabled) current + me else current
        try {
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, next.joinToString(":"))
            if (enabled) Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't change accessibility settings: ${e.message}")
        }
    }

    private fun appName(context: Context, pkg: String) =
        runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun notify(context: Context, name: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Payment apps", NotificationManager.IMPORTANCE_LOW))
        }
        val resume = PendingIntent.getBroadcast(
            context, 0, Intent(context, ResumeReceiver::class.java).setAction(ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val auto = canToggle(context) && canWatch(context)
        nm.notify(
            NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_nik)
                .setContentTitle("Screen control paused for $name")
                .setContentText(if (auto) "So $name can take payments. It turns back on when you leave $name." else "So $name can take payments. Tap to turn it back on after paying.")
                .setContentIntent(resume)
                .addAction(0, "Turn back on", resume)
                .setOngoing(true)
                .build()
        )
    }

    /** "Turn back on" from the notification. */
    class ResumeReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_RESUME) resume(context.applicationContext)
        }
    }
}
