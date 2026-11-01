package com.nik.voiceassistant.data.macro

import java.util.UUID

/**
 * A saved automation: one or more triggers that, when any fires and every constraint holds, run an
 * action. Nothing here is specific to a particular task (no "alarm macro", "youtube macro"): what a
 * macro does is just the tool calls the AI actually made the first time it ran, or an open-ended goal
 * it carries out fresh each run. See MacroEngine for how macros are matched and run, and
 * MacroRecorder for how a first live run becomes a saved one.
 */
data class Macro(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val description: String = "",
    val triggers: List<Trigger>,
    val constraints: List<Constraint> = emptyList(),
    // What the user originally asked for, in their words; kept so a failed replay can fall back to
    // asking the AI to do it again (and re-record a fresh version from that).
    val instruction: String,
    val action: Action,
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long? = null,
    var runCount: Int = 0,
    var lastRunOk: Boolean? = null,
    var isFavorite: Boolean = false
) {
    /** One line for a list row: what starts it. */
    fun triggerSummary(): String = triggers.firstOrNull()?.describe() ?: "Manual"
}

/** What starts a macro. A macro with several triggers runs on any one of them. */
sealed class Trigger {
    abstract fun describe(): String

    /** Said or typed, matched like a saved automation always has been (startsWith, give or take a word or two). */
    data class Phrase(val phrase: String) : Trigger() {
        override fun describe() = "\"$phrase\""
    }

    /** The Run button only; no automatic trigger. */
    object Manual : Trigger() {
        override fun describe() = "Manual run"
    }

    data class Scheduled(val hour: Int, val minute: Int, val days: Set<Int> /* Calendar.SUNDAY..SATURDAY, empty = every day */) : Trigger() {
        override fun describe(): String {
            val time = String.format("%02d:%02d", hour, minute)
            if (days.isEmpty() || days.size == 7) return "Every day at $time"
            val names = listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            return days.sorted().joinToString(", ") { names[it] } + " at $time"
        }
    }

    data class AppOpened(val packageName: String, val appLabel: String) : Trigger() {
        override fun describe() = "$appLabel opens"
    }

    /**
     * A notification arrives matching the filter (any field blank = don't filter on it). The text is
     * only ever compared on the device, inside the matching check; it is never itself stored unredacted.
     */
    data class NotificationReceived(val packageName: String?, val appLabel: String?, val titleContains: String?, val textContains: String?) : Trigger() {
        override fun describe() = "Notification" + (appLabel?.let { " from $it" } ?: "") + (titleContains ?: textContains)?.let { ": \"$it\"" }.orEmpty()
    }

    /** contactFilter: null/blank = any caller; a phone number; or "unknown" for numbers not in contacts. */
    data class IncomingCall(val contactFilter: String?, val contactLabel: String?) : Trigger() {
        override fun describe() = "Incoming call" + (contactLabel?.let { " from $it" } ?: "")
    }

    data class MissedCall(val contactFilter: String?, val contactLabel: String?) : Trigger() {
        override fun describe() = "Missed call" + (contactLabel?.let { " from $it" } ?: "")
    }

    object ChargerConnected : Trigger() { override fun describe() = "Charger connected" }
    object ChargerDisconnected : Trigger() { override fun describe() = "Charger disconnected" }
    data class WifiConnected(val ssidContains: String?) : Trigger() {
        override fun describe() = "Wi-Fi connects" + (ssidContains?.let { " ($it)" } ?: "")
    }
    object BootCompleted : Trigger() { override fun describe() = "Phone restarts" }

    /**
     * An HTTP POST (or GET) to `/webhook/<path>` on the phone's local server (port 7474).
     * Reachable from the same Wi-Fi / Tailscale network, not from the open internet.
     * If `secret` is set, the caller must include it as the `X-Webhook-Token` header or `?token=…`
     * query parameter.
     */
    data class Webhook(val path: String, val secret: String? = null, val label: String = "") : Trigger() {
        override fun describe() = "Webhook /${path.trimStart('/')}" + if (label.isNotBlank()) " ($label)" else ""
    }

    /**
     * Any Android broadcast intent. GenericBroadcastMonitor registers dynamically for every unique
     * action found across all macros — no new monitor code needed for any new event.
     * [extras]: key-value pairs that must ALL match the intent's extras (blank value = just key present).
     * [label]: human-readable description shown in the UI.
     */
    data class BroadcastEvent(
        val action: String,
        val extras: Map<String, String> = emptyMap(),
        val label: String = ""
    ) : Trigger() {
        override fun describe() = label.ifBlank { "System event: ${action.substringAfterLast('.')}" }
    }

    /**
     * The AI periodically wakes up, evaluates [checkInstruction] using its tools (screen, apps, web),
     * and calls report_condition(met=true/false). If met=true the macro's action instruction runs.
     * [intervalMinutes]: how often to check. [stopOnMet]: disable this trigger after first fire.
     */
    data class Condition(
        val checkInstruction: String,
        val intervalMinutes: Int = 10,
        val stopOnMet: Boolean = true,
        val label: String = ""
    ) : Trigger() {
        override fun describe() = label.ifBlank { "When: ${checkInstruction.take(60)}" }
    }
}

/** Extra conditions, all of which must hold for the trigger to actually run the macro. */
sealed class Constraint {
    abstract fun describe(): String
    abstract fun isMet(context: android.content.Context): Boolean

    data class TimeWindow(val fromHour: Int, val fromMinute: Int, val toHour: Int, val toMinute: Int) : Constraint() {
        override fun describe() = String.format("Between %02d:%02d and %02d:%02d", fromHour, fromMinute, toHour, toMinute)
        override fun isMet(context: android.content.Context): Boolean {
            val cal = java.util.Calendar.getInstance()
            val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
            val fromMinutes = fromHour * 60 + fromMinute
            val toMinutes = toHour * 60 + toMinute
            return if (fromMinutes <= toMinutes) {
                currentMinutes in fromMinutes..toMinutes
            } else {
                currentMinutes >= fromMinutes || currentMinutes <= toMinutes
            }
        }
    }

    data class Days(val days: Set<Int>) : Constraint() {
        override fun describe(): String {
            val names = listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            return "On " + days.sorted().joinToString(", ") { names[it] }
        }
        override fun isMet(context: android.content.Context): Boolean {
            val currentDay = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
            return days.isEmpty() || currentDay in days
        }
    }

    data class BatteryAtLeast(val percent: Int) : Constraint() {
        override fun describe() = "Battery at least $percent%"
        override fun isMet(context: android.content.Context): Boolean {
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            return level >= percent
        }
    }

    data class BatteryAtMost(val percent: Int) : Constraint() {
        override fun describe() = "Battery at most $percent%"
        override fun isMet(context: android.content.Context): Boolean {
            val bm = context.getSystemService(android.os.BatteryManager::class.java)
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            return level <= percent
        }
    }

    object WifiConnected : Constraint() {
        override fun describe() = "Wi-Fi connected"
        override fun isMet(context: android.content.Context): Boolean {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    object ScreenOff : Constraint() {
        override fun describe() = "Screen is off"
        override fun isMet(context: android.content.Context): Boolean {
            val pm = context.getSystemService(android.os.PowerManager::class.java)
            return pm?.isInteractive == false
        }
    }
}

/** What a macro does once triggered. */
sealed class Action {
    /**
     * The exact tool calls recorded the first time the AI did this live. Replayed literally (fast, no
     * AI call); if a step fails (element not found, an error back from the tool), the run stops and,
     * when `instruction` is non-blank, MacroEngine asks the AI to do it fresh from that instruction and
     * re-records this action from what it actually did.
     */
    data class Replay(val steps: List<RecordedStep>) : Action()

    /** No saved steps (or the user chose it explicitly): the AI is asked to do `instruction` every run. */
    object AlwaysAsk : Action()
}

/** One step as the AI actually performed it: a tool name, its arguments, and what it was for. */
data class RecordedStep(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val args: Map<String, Any?> = emptyMap(),
    val description: String = "",
    val delayMs: Long = 600L
)

/**
 * Review state shown on screen before saving/arming an automation.
 * Represents the 3 pillars: Trigger, Action, Constraints.
 */
data class AutomationReview(
    val title: String,
    val triggerSummary: String,
    val actionSummary: String,
    val constraintsSummary: String,
    val onApprove: () -> Unit,
    val onCancel: () -> Unit
)

