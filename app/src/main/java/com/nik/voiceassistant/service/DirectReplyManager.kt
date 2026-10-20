package com.nik.voiceassistant.service

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless inline reply manager for messaging apps (WhatsApp, Telegram, Signal, Google Messages, Slack, etc.)
 * using Android's native Notification and RemoteInput architecture.
 *
 * ZERO-UI / Pure Background:
 * When a message arrives, its reply action is cached in memory. Replies are dispatched directly
 * through the app's PendingIntent. No app is launched, the screen is never turned on, and no
 * accessibility gestures or screen interaction are required.
 */
object DirectReplyManager {
    private const val TAG = "DirectReplyManager"
    private const val MAX_SESSIONS = 50
    private const val SESSION_TTL_MS = 30 * 60_000L // 30 minutes

    data class ReplySession(
        val notificationKey: String,
        val packageName: String,
        val appLabel: String,
        val sender: String,
        val snippet: String,
        val replyAction: NotificationCompat.Action,
        val remoteInput: RemoteInput,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<String, ReplySession>()

    /**
     * Extracts an inline reply action (RemoteInput) from an incoming notification and registers it.
     */
    fun register(context: Context, sbn: StatusBarNotification, appLabel: String, sender: String, snippet: String) {
        val notification = sbn.notification ?: return
        val replyPair = findReplyAction(notification) ?: return
        val (action, remoteInput) = replyPair

        // Prune stale sessions if nearing capacity
        if (sessions.size >= MAX_SESSIONS) {
            val now = System.currentTimeMillis()
            sessions.entries.removeIf { now - it.value.timestamp > SESSION_TTL_MS }
        }

        val session = ReplySession(
            notificationKey = sbn.key,
            packageName = sbn.packageName,
            appLabel = appLabel,
            sender = sender,
            snippet = snippet,
            replyAction = action,
            remoteInput = remoteInput
        )
        sessions[sbn.key] = session
        Log.d(TAG, "Registered direct reply action: app='$appLabel' pkg='${sbn.packageName}' sender='$sender'")
    }

    /**
     * Removes an active session when its notification is dismissed or cancelled.
     */
    fun unregister(key: String?) {
        if (!key.isNullOrBlank()) {
            sessions.remove(key)
        }
    }

    /**
     * Finds the reply action from standard Notification actions, WearableExtender actions,
     * or native platform Notification.actions.
     */
    fun findReplyAction(notification: Notification): Pair<NotificationCompat.Action, RemoteInput>? {
        // 1. Direct NotificationCompat actions
        val count = NotificationCompat.getActionCount(notification)
        for (i in 0 until count) {
            val action = NotificationCompat.getAction(notification, i) ?: continue
            if (action.actionIntent == null) continue
            val remoteInputs = action.remoteInputs ?: continue
            for (ri in remoteInputs) {
                if (ri.allowFreeFormInput && ri.resultKey != null) {
                    return Pair(action, ri)
                }
            }
        }

        // 2. WearableExtender actions (WhatsApp, Signal and others frequently put wearable actions with RemoteInput)
        val wearableActions = NotificationCompat.WearableExtender(notification).actions
        for (action in wearableActions) {
            if (action.actionIntent == null) continue
            val remoteInputs = action.remoteInputs ?: continue
            for (ri in remoteInputs) {
                if (ri.allowFreeFormInput && ri.resultKey != null) {
                    return Pair(action, ri)
                }
            }
        }

        // 3. Native platform Notification.actions on API 24+ fallback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && notification.actions != null) {
            for (act in notification.actions) {
                if (act.actionIntent == null) continue
                val inputs = act.remoteInputs ?: continue
                for (ri in inputs) {
                    if (ri.allowFreeFormInput && ri.resultKey != null) {
                        val compatRi = RemoteInput.Builder(ri.resultKey)
                            .setLabel(ri.label)
                            .setChoices(ri.choices)
                            .setAllowFreeFormInput(ri.allowFreeFormInput)
                            .build()
                        val compatAction = NotificationCompat.Action.Builder(
                            act.icon, act.title, act.actionIntent
                        ).addRemoteInput(compatRi).build()
                        return Pair(compatAction, compatRi)
                    }
                }
            }
        }

        return null
    }

    /**
     * Pure validation logic (no Android runtime required, unit-testable).
     */
    fun validate(replyText: String): String? {
        if (replyText.trim().isEmpty()) return "Reply message cannot be empty"
        return null
    }

    /**
     * Sends a reply to an incoming conversation headlessly without opening any UI.
     * [packageName]: optional app filter (e.g. "com.whatsapp" or "whatsapp" or "Messages")
     * [senderQuery]: optional contact/sender name filter (e.g. "Papa" or "Rahul")
     * [replyText]: the text to reply
     */
    fun reply(context: Context, packageName: String?, senderQuery: String?, replyText: String): Map<String, Any?> {
        val validationError = validate(replyText)
        if (validationError != null) {
            return mapOf("success" to false, "error" to validationError)
        }
        val cleanReply = replyText.trim()

        val now = System.currentTimeMillis()
        val activeList = sessions.values
            .filter { now - it.timestamp <= SESSION_TTL_MS }
            .sortedByDescending { it.timestamp }

        if (activeList.isEmpty()) {
            return mapOf(
                "success" to false,
                "error" to "No active incoming messages available to reply to. The notification might have been cleared or expired."
            )
        }

        val target = activeList.firstOrNull { session ->
            val matchPkg = packageName.isNullOrBlank() ||
                session.packageName.contains(packageName, ignoreCase = true) ||
                session.appLabel.contains(packageName, ignoreCase = true)
            val matchSender = senderQuery.isNullOrBlank() ||
                session.sender.contains(senderQuery, ignoreCase = true)
            matchPkg && matchSender
        } ?: return mapOf(
            "success" to false,
            "error" to "No matching incoming message found for ${listOfNotNull(packageName, senderQuery).joinToString(", ")}. Available senders: ${activeList.map { it.sender }.distinct().joinToString()}"
        )

        val pendingIntent = target.replyAction.actionIntent ?: return mapOf(
            "success" to false,
            "error" to "Reply action does not have a valid PendingIntent"
        )

        return try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(target.remoteInput.resultKey, cleanReply)
            RemoteInput.addResultsToIntent(arrayOf(target.remoteInput), intent, bundle)

            pendingIntent.send(context, 0, intent)
            Log.i(TAG, "Direct reply dispatched headlessly to '${target.sender}' on ${target.appLabel}")
            mapOf(
                "success" to true,
                "app" to target.appLabel,
                "sender" to target.sender,
                "method" to "headless_remote_input",
                "message" to "Reply sent headlessly to ${target.sender} on ${target.appLabel}: \"$cleanReply\""
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send direct reply to ${target.sender}: ${e.message}", e)
            mapOf(
                "success" to false,
                "error" to "Failed to send reply to ${target.sender}: ${e.message}"
            )
        }
    }

    /**
     * Lists active messages that can currently be replied to headlessly.
     */
    fun listActiveReplyable(): List<Map<String, String>> {
        val now = System.currentTimeMillis()
        return sessions.values
            .filter { now - it.timestamp <= SESSION_TTL_MS }
            .sortedByDescending { it.timestamp }
            .map {
                mapOf(
                    "sender" to it.sender,
                    "app" to it.appLabel,
                    "package" to it.packageName,
                    "snippet" to it.snippet.take(100),
                    "received_seconds_ago" to ((now - it.timestamp) / 1000).toString()
                )
            }
    }
}
