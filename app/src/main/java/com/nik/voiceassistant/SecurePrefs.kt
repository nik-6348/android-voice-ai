package com.nik.voiceassistant

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * A small encrypted store, for secrets that must never leave the phone (the device PIN). The PIN is
 * used only by the on-device unlock gesture (PhoneAgentService); it is never sent to the server or
 * the model. Backed by the Android Keystore, so even the file on disk is unreadable without the device.
 */
object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val FILE = "secure_voice_assistant"
    private const val KEY_PIN = "device_pin"
    private const val KEY_LOCK_TYPE = "lock_type"
    private const val KEY_PATTERN = "device_pattern"
    private const val KEY_AUTO_UNLOCK = "auto_unlock"
    private const val KEY_UNLOCK_FAILURES = "unlock_failures"
    const val MAX_UNLOCK_FAILURES = 2

    /** Which kind of screen lock the saved credential unlocks. */
    enum class LockType { PIN, PASSWORD, PATTERN }

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: try {
                open(context)
            } catch (e: Exception) {
                // A file the Keystore key can't open any more (restored from another device, or the
                // key was reset) would fail forever: start a fresh store; the user re-enters the PIN / keys
                Log.e(TAG, "Encrypted store unreadable, resetting it: ${e.javaClass.simpleName}")
                context.applicationContext.deleteSharedPreferences(FILE)
                try {
                    open(context)
                } catch (e2: Exception) {
                    Log.e(TAG, "Encrypted store unavailable: ${e2.javaClass.simpleName}")
                    null
                }
            }
        }
    }

    private fun open(context: Context): SharedPreferences {
        val key = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also { prefs = it }
    }

    /** Saves the PIN or password (or clears it when blank). Returns false if the secure store is unavailable. */
    fun setPin(context: Context, pin: String): Boolean {
        val p = prefs(context) ?: return false
        p.edit().apply { if (pin.isBlank()) remove(KEY_PIN) else putString(KEY_PIN, pin.trim()) }.remove(KEY_UNLOCK_FAILURES).apply()
        return true
    }

    fun hasPin(context: Context): Boolean = !prefs(context)?.getString(KEY_PIN, null).isNullOrBlank()

    /** The saved PIN or password, read only by the unlock gesture. */
    fun pin(context: Context): String? = prefs(context)?.getString(KEY_PIN, null)

    fun lockType(context: Context): LockType = when (prefs(context)?.getString(KEY_LOCK_TYPE, null)) {
        "password" -> LockType.PASSWORD
        "pattern" -> LockType.PATTERN
        else -> LockType.PIN
    }

    fun setLockType(context: Context, type: LockType): Boolean {
        val p = prefs(context) ?: return false
        p.edit().putString(KEY_LOCK_TYPE, type.name.lowercase()).remove(KEY_UNLOCK_FAILURES).apply()
        return true
    }

    /** Saves the pattern as an ordered list of 0-8 cell indices (3x3 grid), or clears it when empty. */
    fun setPattern(context: Context, cells: List<Int>): Boolean {
        val p = prefs(context) ?: return false
        p.edit().apply {
            if (cells.isEmpty()) remove(KEY_PATTERN) else putString(KEY_PATTERN, cells.joinToString(","))
        }.remove(KEY_UNLOCK_FAILURES).apply()
        return true
    }

    fun hasPattern(context: Context): Boolean = !prefs(context)?.getString(KEY_PATTERN, null).isNullOrBlank()

    /** The saved pattern as 0-8 cell indices in drawing order, read only by the unlock gesture. */
    fun pattern(context: Context): List<Int>? =
        prefs(context)?.getString(KEY_PATTERN, null)?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.isNotEmpty() }

    // ---- API keys (Gemini Live, LLM gateway): encrypted like the PIN, never kept in plain text ----

    const val GEMINI_API_KEY = "gemini_api_key"
    const val LLM_API_KEY = "llm_api_key"

    /** A saved secret, or null. */
    fun secret(context: Context, name: String): String? = prefs(context)?.getString(name, null)?.takeIf { it.isNotBlank() }

    /** Saves a secret (or clears it when blank). Returns false if the secure store is unavailable: nothing is saved then. */
    fun setSecret(context: Context, name: String, value: String?): Boolean {
        val p = prefs(context) ?: return false
        p.edit().apply { if (value.isNullOrBlank()) remove(name) else putString(name, value.trim()) }.apply()
        return true
    }

    fun isAutoUnlockEnabled(context: Context): Boolean = prefs(context)?.getBoolean(KEY_AUTO_UNLOCK, false) == true

    fun setAutoUnlock(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_AUTO_UNLOCK, enabled)?.remove(KEY_UNLOCK_FAILURES)?.apply()
    }

    /**
     * Counts a wrong PIN entered by auto-unlock. After MAX_UNLOCK_FAILURES in a row auto-unlock turns
     * itself off (a changed device PIN must not lead to lockouts or a wipe). Returns true if it did.
     */
    fun noteUnlockFailure(context: Context): Boolean {
        val p = prefs(context) ?: return false
        val failures = p.getInt(KEY_UNLOCK_FAILURES, 0) + 1
        if (failures >= MAX_UNLOCK_FAILURES) {
            p.edit().putBoolean(KEY_AUTO_UNLOCK, false).remove(KEY_UNLOCK_FAILURES).apply()
            return true
        }
        p.edit().putInt(KEY_UNLOCK_FAILURES, failures).apply()
        return false
    }

    fun noteUnlockSuccess(context: Context) {
        prefs(context)?.edit()?.remove(KEY_UNLOCK_FAILURES)?.apply()
    }
}
