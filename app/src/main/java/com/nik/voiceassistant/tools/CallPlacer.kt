package com.nik.voiceassistant.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.nik.voiceassistant.service.PhoneAgentService

/**
 * Shared helper for placing phone calls (used by both the existing `make_call` tool and `CallAgentSession`).
 * Handles ACTION_CALL with ACTION_DIAL fallback when permission is missing, and turns on speakerphone.
 */
object CallPlacer {

    data class Result(
        val success: Boolean,
        val calling: String? = null,
        val speaker: Boolean = false,
        val error: String? = null
    )

    fun place(context: Context, number: String?, speaker: Boolean): Result {
        if (number.isNullOrBlank()) {
            return Result(success = false, error = "Give a phone number (use find_contact for a name)")
        }
        val tel = Uri.fromParts("tel", number.replace(Regex("[\\s()-]"), ""), null)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // Without the permission the dialer can only be opened with the number filled in
            context.startActivity(Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return Result(
                success = false,
                error = "Phone call permission is off, so only the dialer opened; tap its Call button, and tell the user to allow Phone calls for Nik's voice"
            )
        }

        context.startActivity(Intent(Intent.ACTION_CALL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var onSpeaker = false
        if (speaker) {
            Thread.sleep(2500) // let the call screen come up
            // The in-call screen's own Speaker button is what the dialer respects; the audio route is the fallback
            onSpeaker = PhoneAgentService.instance?.switchOn(listOf("Speaker", "Speakerphone", "स्पीकर")) == true
            if (!onSpeaker) {
                val audio = context.getSystemService(AudioManager::class.java)
                @Suppress("DEPRECATION")
                runCatching { audio?.isSpeakerphoneOn = true; onSpeaker = audio?.isSpeakerphoneOn == true }
            }
        }
        return Result(success = true, calling = number, speaker = onSpeaker)
    }
}
