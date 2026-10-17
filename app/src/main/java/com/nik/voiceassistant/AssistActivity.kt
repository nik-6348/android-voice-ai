package com.nik.voiceassistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.nik.voiceassistant.audio.WakeWordManager

/**
 * Opened by the phone's assistant gesture (long-press power, swipe up from a bottom corner) once
 * Nik's voice is the default digital assistant app. Starts a conversation and gets out of the way;
 * the conversation carries on in the background service with the overlay pill on top.
 *
 * The mic only opens here, like Google Assistant, instead of listening all the time for a wake word.
 * Being briefly on screen is also what lets the app start using the mic from the background.
 */
class AssistActivity : Activity() {

    companion object {
        /**
         * Opened (invisibly) by the accessibility service when "Hi Nik" must start from the background
         * (after a reboot, an update, or Android stopping the app): the mic is only granted to a
         * foreground service started while something of the app is on screen.
         */
        const val ACTION_KEEP_LISTENING = "com.nik.voiceassistant.action.KEEP_LISTENING"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent?.action
        if (action == ACTION_KEEP_LISTENING) {
            // Only meaningful while "Hi Nik" is on (any app can open this exported activity)
            if (VoiceSession.get(this).isWakeWordEnabled.value) com.nik.voiceassistant.service.VoiceAssistantService.refreshListening(this)
            Handler(Looper.getMainLooper()).postDelayed({ finish() }, 500)
            return
        }
        // Exported for the system's assistant gesture: only that request opens the mic
        if (action != Intent.ACTION_ASSIST && action != Intent.ACTION_VOICE_COMMAND) {
            finish()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // The app asks for it
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val session = VoiceSession.get(this)
        if (!session.isActive) {
            WakeWordManager.playWakeChime()
            session.start()
        }
        // Stay just long enough for the service to come up in the foreground with the mic
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 700)
    }
}
