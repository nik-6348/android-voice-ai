package com.nik.voiceassistant.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's safety and privacy choices (Settings > Safety & Privacy). Every check that could get
 * in the agent's way reads its switch from here, so the user decides how much it may do on its own.
 * Defaults keep the agent fully working while stopping the risky cases (prompt injection from the
 * screen, actions nobody asked for).
 */
object AgentPolicy {

    /** How often the agent stops to ask before an action with consequences. */
    enum class ConfirmLevel {
        /** Asks only before payments, wipes and dialing codes (*#…#). */
        RELAXED,
        /** Also asks before calls, sending and deleting, unless the user's own words asked for it. */
        BALANCED,
        /** Asks before every call, send, delete and payment. */
        STRICT
    }

    data class Settings(
        val confirmLevel: ConfirmLevel = ConfirmLevel.BALANCED,
        // OTPs and card numbers on screen are hidden from the AI (it can't read an OTP for you then)
        val maskSensitiveScreen: Boolean = false,
        // Conversations are kept on the phone for the History tab
        val saveHistory: Boolean = true,
        // Older conversations are deleted; 0 = keep forever
        val historyDays: Int = 30,
        // The Activity tab's logs and traces are written to the phone (off = memory only)
        val saveActivityLogs: Boolean = true,
        // Logs keep what the agent read on screen (off = a one-line summary only)
        val logScreenContents: Boolean = false,
        // Older log days are deleted; 0 = keep forever
        val logRetentionDays: Int = 7,
        // Opening the app starts listening right away
        val autoStartOnOpen: Boolean = true
    )

    private const val PREFS = "agent_policy"

    @Volatile private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()

    /** The current settings (defaults until init). */
    val current: Settings get() = _state.value

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            _state.value = Settings(
                confirmLevel = runCatching { ConfirmLevel.valueOf(p.getString("confirm_level", null) ?: d.confirmLevel.name) }.getOrDefault(d.confirmLevel),
                maskSensitiveScreen = p.getBoolean("mask_sensitive_screen", d.maskSensitiveScreen),
                saveHistory = p.getBoolean("save_history", d.saveHistory),
                historyDays = p.getInt("history_days", d.historyDays),
                saveActivityLogs = p.getBoolean("save_activity_logs", d.saveActivityLogs),
                logScreenContents = p.getBoolean("log_screen_contents", d.logScreenContents),
                logRetentionDays = p.getInt("log_retention_days", d.logRetentionDays),
                autoStartOnOpen = p.getBoolean("auto_start_on_open", d.autoStartOnOpen)
            )
            prefs = p
        }
    }

    fun update(context: Context, transform: (Settings) -> Settings) {
        init(context)
        val next = synchronized(this) { transform(_state.value).also { _state.value = it } }
        prefs?.edit()
            ?.putString("confirm_level", next.confirmLevel.name)
            ?.putBoolean("mask_sensitive_screen", next.maskSensitiveScreen)
            ?.putBoolean("save_history", next.saveHistory)
            ?.putInt("history_days", next.historyDays.coerceAtLeast(0))
            ?.putBoolean("save_activity_logs", next.saveActivityLogs)
            ?.putBoolean("log_screen_contents", next.logScreenContents)
            ?.putInt("log_retention_days", next.logRetentionDays.coerceAtLeast(0))
            ?.putBoolean("auto_start_on_open", next.autoStartOnOpen)
            ?.apply()
    }
}
