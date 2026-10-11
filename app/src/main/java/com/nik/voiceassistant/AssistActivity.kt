package com.nik.voiceassistant

import android.app.Activity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = VoiceSession.get(this)
        if (!session.isActive) {
            WakeWordManager.playWakeChime()
            session.start()
        }
        // Stay just long enough for the service to come up in the foreground with the mic
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 700)
    }
}
