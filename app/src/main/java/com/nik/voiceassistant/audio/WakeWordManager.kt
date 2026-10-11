package com.nik.voiceassistant.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

/**
 * Handles wake-word sound feedback and hotword intent detection.
 */
object WakeWordManager {
    private const val TAG = "WakeWordManager"
    private var toneGenerator: ToneGenerator? = null

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init failed: ${e.message}")
        }
    }

    /**
     * Plays a futuristic sci-fi chime when assistant wakes up or hotword is heard.
     */
    fun playWakeChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play wake chime: ${e.message}")
        }
    }

    /**
     * Checks if a phrase is a wake word greeting like "hi nik", "hey nik", "nik", "jarvis".
     */
    fun isWakeWord(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower.contains("hi nik") ||
                lower.contains("hey nik") ||
                lower.contains("hello nik") ||
                lower.contains("nik jarvis") ||
                lower.contains("hi jarvis") ||
                lower.contains("hey jarvis") ||
                lower == "nik" ||
                lower == "jarvis"
    }
}
