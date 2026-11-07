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

        fun ensureBlokadaVpnActive(context: Context) {
            Thread {
                try {
                    // 1. Wait for system clock to be synchronized (year >= 2024)
                    // On STB boot without hardware RTC, time starts at 2015.
                    // If Blokada DoH starts before NTP sync, TLS cert check fails and breaks internet!
                    var attempts = 0
                    while (attempts < 20) {
                        val currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                        if (currentYear >= 2024) break
                        Log.i(TAG, "Waiting for STB clock to sync via NTP (current year: $currentYear)...")
                        Thread.sleep(1500)
                        attempts++
                    }

                    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                    val isVpnActive = cm?.allNetworks?.any { network ->
                        cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
                    } == true
                    if (!isVpnActive) {
                        val pm = context.packageManager
                        val blokadaPkg = "org.blokada.origin.alarm"
                        if (pm.getLaunchIntentForPackage(blokadaPkg) != null) {
                            Log.i(TAG, "STB clock verified. Auto-starting Blokada to enforce ad blocking.")
                            val launchIntent = pm.getLaunchIntentForPackage(blokadaPkg)?.apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                            }
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                                Thread.sleep(2000)
                                PhoneAgentService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                            }
                        }
                    } else {
                        Log.i(TAG, "Blokada AdBlocker VPN is active.")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't ensure Blokada VPN active: ${e.message}")
                }
            }.start()
        }
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
                "test_provider" -> {
                    val qText = intent.getStringExtra("q") ?: "Aaj Tak"
                    Thread {
                        try {
                            Log.i(TAG, ">>> Testing JioTV+ provider with query: '$qText'")
                            val uri = android.net.Uri.parse("content://com.jio.media.jiotvplus")
                            val cursor = context.contentResolver.query(uri, null, null, arrayOf(qText), null)
                            if (cursor == null) {
                                Log.e(TAG, ">>> Cursor is NULL from JioTV+ provider")
                            } else {
                                cursor.use { c ->
                                    Log.i(TAG, ">>> Cursor returned! Row count: ${c.count}, Column count: ${c.columnCount}")
                                    val colNames = c.columnNames.joinToString(", ")
                                    Log.i(TAG, ">>> Columns: $colNames")
                                    while (c.moveToNext()) {
                                        val row = (0 until c.columnCount).joinToString(" | ") { idx ->
                                            "${c.getColumnName(idx)}=${c.getString(idx)}"
                                        }
                                        Log.i(TAG, ">>> ROW: $row")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, ">>> Error querying JioTV+ provider", e)
                        }
                    }.start()
                }
                "set_keys" -> {
                    val key = intent.getStringExtra("key") ?: "Shiv+Shakti=Love@143"
                    val session = com.nik.voiceassistant.VoiceSession.get(context)
                    session.saveDirectApiKey(key)
                    session.saveLlmApiKey(key)
                    Log.i(TAG, "Direct mode configured with key: $key for both Voice (Gemini) and LLM")
                }
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

        ensureBlokadaVpnActive(context)
    }
}
