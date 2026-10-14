package com.nik.voiceassistant.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nik.voiceassistant.service.PhoneAgentService
import com.nik.voiceassistant.service.VoiceAssistantService

/**
 * Automatically restores the VoiceAssistantService after device reboot or package update
 * if the user has enabled Wake Word detection in settings.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "Received boot/update event: $action")

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
