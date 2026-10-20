package com.nik.voiceassistant.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Headless SMS dispatcher using Android's native Telephony SmsManager API.
 *
 * ZERO-UI / Pure Background:
 * Sends SMS directly via the carrier cellular radio. Works with screen off, device locked,
 * and without opening the Messages/SMS application.
 */
object DirectSmsManager {
    private const val TAG = "DirectSmsManager"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Pure validation logic (no Android runtime required, unit-testable).
     */
    fun validate(phoneNumber: String, message: String): String? {
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        if (cleanNumber.isBlank()) return "Invalid phone number: '$phoneNumber'"
        if (message.trim().isEmpty()) return "SMS message text cannot be empty"
        return null
    }

    fun sendSms(context: Context, phoneNumber: String, message: String): Map<String, Any?> {
        val validationError = validate(phoneNumber, message)
        if (validationError != null) {
            return mapOf("success" to false, "error" to validationError)
        }
        val cleanNumber = phoneNumber.filter { it.isDigit() || it == '+' }
        val cleanMsg = message.trim()
        if (!hasPermission(context)) {
            return mapOf(
                "success" to false,
                "error" to "SMS permission (SEND_SMS) is not granted on this phone. Please grant SMS permission in Settings."
            )
        }

        return try {
            val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
                    ?: @Suppress("DEPRECATION") SmsManager.getDefault()
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(cleanMsg)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(cleanNumber, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(cleanNumber, null, cleanMsg, null, null)
            }

            Log.i(TAG, "Headless SMS dispatched to $cleanNumber (${parts.size} part(s))")
            mapOf(
                "success" to true,
                "to" to cleanNumber,
                "parts" to parts.size,
                "method" to "headless_sms",
                "message" to "SMS sent successfully to $cleanNumber"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send SMS to $cleanNumber: ${e.message}", e)
            mapOf(
                "success" to false,
                "error" to "Failed to send SMS: ${e.message}"
            )
        }
    }
}
