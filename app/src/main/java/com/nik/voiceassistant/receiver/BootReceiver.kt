package com.nik.voiceassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nik.voiceassistant.service.PaymentGuard
import com.nik.voiceassistant.service.PhoneAgentService
import com.nik.voiceassistant.service.VoiceAssistantService

/**
 * Automatically restores the VoiceAssistantService after device reboot or package update
 * if the user has enabled Wake Word detection in settings.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON"
        )
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        // Exported for the system's boot broadcasts: anything else (another app's explicit intent) is ignored
        if (action !in BOOT_ACTIONS) return
        Log.i(TAG, "Received boot/update event: $action")

        // Screen control switched off for a payment app before the reboot comes back on
        PaymentGuard.restore(context)
        // AlarmManager clears every armed alarm on reboot: re-arm every Scheduled-trigger macro
        com.nik.voiceassistant.service.ScheduledTriggerMonitor.rescheduleAll(context)

        val prefs = context.getSharedPreferences("voice_assistant_settings", Context.MODE_PRIVATE)
        val isWakeWordEnabled = prefs.getBoolean("wake_word", false)

        if (isWakeWordEnabled) {
            Log.i(TAG, "Wake word is enabled, restoring background listening service...")
            if (PhoneAgentService.isEnabled(context) && PhoneAgentService.instance != null) {
                PhoneAgentService.instance?.bringUpListening()
            } else {
                VoiceAssistantService.startListening(context)
            }
        }
    }
}
