package com.nik.voiceassistant.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Puts the phone in "call" audio for the length of a conversation, like a WhatsApp / ChatGPT voice
 * call: the reply plays on the voice-communication path, so the phone's hardware echo canceller
 * removes it from the mic. That lets the mic stay open while the assistant talks, so the user can
 * cut in by voice without the assistant hearing itself.
 *
 * Also, for the conversation only:
 *  - the loudspeaker (unless a headset / Bluetooth device is connected),
 *  - call volume at max (call audio is quieter than media; restored afterwards),
 *  - audio focus, so music / videos pause or duck and the mic hears the user, not the phone.
 */
class CallAudio(context: Context) {

    companion object {
        private const val TAG = "CallAudio"
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private var savedMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    @Volatile private var active = false

    fun enter() {
        val am = audio ?: return
        if (active) return
        active = true
        try {
            savedMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            routeToSpeakerUnlessHeadset(am)
            // Do NOT force max volume: respect the user's chosen volume level on their device!
            // Pause / duck other audio while we listen and talk (released when the conversation ends)
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
                .also { am.requestAudioFocus(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't set up call audio: ${e.message}")
        }
    }

    fun exit() {
        val am = audio ?: return
        if (!active) return
        active = false
        try {
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = savedMode
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't restore audio: ${e.message}")
        }
    }

    private fun routeToSpeakerUnlessHeadset(am: AudioManager) {
        val personal = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            val pick = devices.firstOrNull { it.type in personal } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            pick?.let { am.setCommunicationDevice(it) }
        } else {
            val hasHeadset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in personal }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !hasHeadset
        }
    }
}
