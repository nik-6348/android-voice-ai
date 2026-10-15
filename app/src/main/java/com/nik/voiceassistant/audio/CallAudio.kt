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
class CallAudio(
    context: Context,
    // Another app took audio focus for good or for a while (an incoming phone call, a voice note):
    // the conversation should pause / end instead of talking over it. Called on the main thread
    private val onFocusLost: () -> Unit = {}
) {

    companion object {
        private const val TAG = "CallAudio"
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var savedMode = AudioManager.MODE_NORMAL
    private var focus: AudioFocusRequest? = null
    @Volatile private var active = false

    /**
     * Idempotent. Returns false if audio focus was refused, e.g. a phone call is on; the phone's
     * call audio is left alone then. allowInCall allows entering for call agent sessions where a phone call is expected.
     */
    @Synchronized
    fun enter(allowInCall: Boolean = false): Boolean {
        val am = audio ?: return false
        if (active) return true
        try {
            // A phone call owns the audio mode and routing: don't take them over unless explicitly intended for CallAgentSession
            if (!allowInCall && (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_RINGTONE)) {
                Log.w(TAG, "Phone call in progress: not switching to call audio")
                return false
            }
            active = true
            savedMode = am.mode
            if (!allowInCall) {
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
            routeToSpeakerUnlessHeadset(am)
            // Do NOT force max volume: respect the user's chosen volume level on their device!
            // Pause / duck other audio while we listen and talk (released when the conversation ends)
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener({ change ->
                    // Ducking requests (navigation, notifications) don't count: only a real takeover
                    // When allowInCall is true, the telephony stack owns call audio focus so we do not abort on focus changes
                    Log.w(TAG, "Audio focus changed: $change (allowInCall=$allowInCall)")
                    if (!allowInCall && (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)) {
                        if (active) onFocusLost()
                    }
                }, main)
                .build()
            focus = request
            val granted = am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!granted && !allowInCall) {
                Log.w(TAG, "Audio focus refused")
                return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't set up call audio: ${e.message}")
        }
        return true
    }

    /** Idempotent: gives back focus, routing and the audio mode from before enter(). */
    @Synchronized
    fun exit() {
        val am = audio ?: return
        if (!active) return
        active = false
        try {
            focus?.let { am.abandonAudioFocusRequest(it) }
            focus = null
            // Only undo our own mode: if a phone call has switched it meanwhile, leave the call's audio alone
            val ours = am.mode == AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else if (ours) {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            if (ours) am.mode = savedMode
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
