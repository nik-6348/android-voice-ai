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
    private const val KEY_AUTO_UNLOCK = "auto_unlock"

    @Volatile private var prefs: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences? {
        prefs?.let { return it }
        return try {
            val key = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            ).also { prefs = it }
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted store unavailable: ${e.message}")
            null
        }
    }

    /** Saves the PIN (or clears it when blank). Returns false if the secure store is unavailable. */
    fun setPin(context: Context, pin: String): Boolean {
        val p = prefs(context) ?: return false
        p.edit().apply { if (pin.isBlank()) remove(KEY_PIN) else putString(KEY_PIN, pin.trim()) }.apply()
        return true
    }

    fun hasPin(context: Context): Boolean = !prefs(context)?.getString(KEY_PIN, null).isNullOrBlank()

    /** The saved PIN, read only by the unlock gesture. */
    fun pin(context: Context): String? = prefs(context)?.getString(KEY_PIN, null)

    fun isAutoUnlockEnabled(context: Context): Boolean = prefs(context)?.getBoolean(KEY_AUTO_UNLOCK, false) == true

    fun setAutoUnlock(context: Context, enabled: Boolean) {
        prefs(context)?.edit()?.putBoolean(KEY_AUTO_UNLOCK, enabled)?.apply()
    }
}
