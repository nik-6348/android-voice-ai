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
     * Checks if a phrase is a wake word greeting like "hi shiv", "hey shiv", "shiv", "hi nik", "jarvis".
     */
    fun isWakeWord(text: String, assistantName: String = "Shiv"): Boolean {
        val lower = text.lowercase().trim()
        val name = assistantName.lowercase().trim()

        if (name.contains("shiv") || lower.contains("shiv") || lower.contains("shiva")) {
            if (lower.contains("hi shiv") || lower.contains("hey shiv") ||
                lower.contains("hello shiv") || lower.contains("ok shiv") ||
                lower.contains("okay shiv") || lower.contains("hi shiva") ||
                lower.contains("hey shiva") || lower.contains("hello shiva") ||
                lower == "shiv" || lower == "shiva") {
                return true
            }
        }

        if (name.isNotBlank()) {
            if (lower.contains("hi $name") || lower.contains("hey $name") ||
                lower.contains("hello $name") || lower.contains("okay $name") ||
                lower.contains("ok $name") || lower == name) {
                return true
            }
        }

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
