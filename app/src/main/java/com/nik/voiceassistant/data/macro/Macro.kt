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
}

/** Extra conditions, all of which must hold for the trigger to actually run the macro. */
sealed class Constraint {
    abstract fun describe(): String

    data class TimeWindow(val fromHour: Int, val fromMinute: Int, val toHour: Int, val toMinute: Int) : Constraint() {
        override fun describe() = String.format("Between %02d:%02d and %02d:%02d", fromHour, fromMinute, toHour, toMinute)
    }
    data class Days(val days: Set<Int>) : Constraint() {
        override fun describe(): String {
            val names = listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            return "On " + days.sorted().joinToString(", ") { names[it] }
        }
    }
    data class BatteryAtLeast(val percent: Int) : Constraint() { override fun describe() = "Battery at least $percent%" }
    data class BatteryAtMost(val percent: Int) : Constraint() { override fun describe() = "Battery at most $percent%" }
    object WifiConnected : Constraint() { override fun describe() = "Wi-Fi connected" }
    object ScreenOff : Constraint() { override fun describe() = "Screen is off" }
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

    /**
     * Answers (or places) a phone call and lets the AI talk, acoustically (speakerphone + mic): see
     * CallAgentSession. `greeting` is said first, always disclosing it's an AI unless the user turned
     * that off for this macro. `goal` is what the AI is there to do or find out.
     */
    data class HandleCall(val goal: String, val discloseAi: Boolean = true, val maxDurationMs: Long = 5 * 60_000L) : Action()
}

/** One step as the AI actually performed it: a tool name, its arguments, and what it was for. */
data class RecordedStep(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val args: Map<String, Any?> = emptyMap(),
    val description: String = "",
    val delayMs: Long = 600L
)
