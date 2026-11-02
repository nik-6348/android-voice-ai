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
        if (action == "com.nik.voiceassistant.action.TEST_HUD") {
            val type = intent.getStringExtra("type") ?: "action"
            val q = intent.getStringExtra("q") ?: "User Question"
            val a = intent.getStringExtra("a") ?: "Agent Response"
            val title = intent.getStringExtra("title") ?: "⚡ Shiv Autonomous HUD"
            val sub = intent.getStringExtra("sub")
            when (type) {
                "listening" -> com.nik.voiceassistant.service.TvHudOverlay.showListening(context)
                "answer" -> com.nik.voiceassistant.service.TvHudOverlay.showAnswer(context, q, a)
                "action" -> com.nik.voiceassistant.service.TvHudOverlay.showAction(context, title, sub)
                "completed" -> com.nik.voiceassistant.service.TvHudOverlay.showCompleted(context, title)
                "stopped" -> com.nik.voiceassistant.service.TvHudOverlay.showStopped(context)
                "hide" -> com.nik.voiceassistant.service.TvHudOverlay.hide()
                "query" -> {
                    val qText = intent.getStringExtra("text") ?: "Hello bro"
                    val session = com.nik.voiceassistant.VoiceSession.get(context)
                    session.sendText(qText, keepMicMuted = true)
                }
                "audio" -> {
                    val session = com.nik.voiceassistant.VoiceSession.get(context)
                    val sampleRate = 24000
                    val numSamples = (sampleRate * 0.8).toInt()
                    val pcm = ByteArray(numSamples * 2)
                    for (i in 0 until numSamples) {
                        val angle = 2.0 * Math.PI * i * 523.25 / sampleRate
                        val sample = (Math.sin(angle) * 16000).toInt().toShort()
                        pcm[i * 2] = (sample.toInt() and 0xFF).toByte()
                        pcm[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
                    }
                    session.audioTrackManager.enqueueAudioChunk(pcm)
                    Log.i(TAG, "Queued 0.8s test tone to AudioTrackManager")
                }
            }
            return
        }
        // Exported for the system's boot broadcasts: anything else (another app's explicit intent) is ignored
        if (action !in BOOT_ACTIONS) return
        Log.i(TAG, "Received boot/update event: $action")

        // Screen control switched off for a payment app before the reboot comes back on
        PaymentGuard.restore(context)
        // Ensure accessibility service is active across reboots
        if (PaymentGuard.canToggle(context)) {
            PaymentGuard.setServiceEnabled(context, true)
            Log.i(TAG, "Ensured PhoneAgentService accessibility is enabled after boot")
        }
        // AlarmManager clears every armed alarm on reboot: re-arm every Scheduled and Condition macro
        com.nik.voiceassistant.service.ScheduledTriggerMonitor.rescheduleAll(context)
        com.nik.voiceassistant.service.ConditionPollingEngine.rescheduleAll(context)

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
