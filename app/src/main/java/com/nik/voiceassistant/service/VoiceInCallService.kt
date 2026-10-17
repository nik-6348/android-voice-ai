package com.nik.voiceassistant.service

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log

/**
 * Telecom InCallService implementation for managing cellular phone calls when the app
 * is configured as the system Default Phone / Dialer app.
 *
 * Provides system-level call answering, hanging up, and audio routing (speakerphone/earpiece)
 * directly via Android's Telecom framework.
 */
class VoiceInCallService : InCallService() {

    companion object {
        private const val TAG = "VoiceInCallService"

        @Volatile var currentService: VoiceInCallService? = null
        @Volatile var activeCall: Call? = null

        var onCallAddedListener: ((Call) -> Unit)? = null
        var onCallRemovedListener: ((Call) -> Unit)? = null

        fun answerActiveCall(): Boolean {
            val call = activeCall ?: return false
            Log.w(TAG, "Answering active call via InCallService: $call")
            call.answer(VideoProfile.STATE_AUDIO_ONLY)
            return true
        }

        fun hangUpActiveCall(): Boolean {
            val call = activeCall ?: return false
            Log.w(TAG, "Disconnecting active call via InCallService: $call")
            call.disconnect()
            return true
        }

        fun setSpeakerphone(on: Boolean) {
            val svc = currentService ?: return
            val route = if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE
            Log.w(TAG, "Setting audio route via InCallService to: $route (on=$on)")
            svc.setAudioRoute(route)
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            Log.w(TAG, "InCallService Call state changed: ${callStateToString(state)} for $call")
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            Log.w(TAG, "InCallService Call details changed: handle=${details.handle}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.w(TAG, "VoiceInCallService created")
        currentService = this
    }

    override fun onDestroy() {
        Log.w(TAG, "VoiceInCallService destroyed")
        currentService = null
        activeCall = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.w(TAG, "onCallAdded: $call (state=${callStateToString(call.state)}, handle=${call.details.handle})")
        activeCall = call
        call.registerCallback(callCallback)
        onCallAddedListener?.invoke(call)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        Log.w(TAG, "onCallRemoved: $call")
        call.unregisterCallback(callCallback)
        if (activeCall == call) {
            activeCall = null
        }
        onCallRemovedListener?.invoke(call)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        Log.w(TAG, "onCallAudioStateChanged: route=${audioState.route}, isMuted=${audioState.isMuted}")
    }

    private fun callStateToString(state: Int): String = when (state) {
        Call.STATE_NEW -> "NEW"
        Call.STATE_RINGING -> "RINGING"
        Call.STATE_DIALING -> "DIALING"
        Call.STATE_ACTIVE -> "ACTIVE"
        Call.STATE_HOLDING -> "HOLDING"
        Call.STATE_DISCONNECTED -> "DISCONNECTED"
        Call.STATE_CONNECTING -> "CONNECTING"
        Call.STATE_DISCONNECTING -> "DISCONNECTING"
        Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
        Call.STATE_SIMULATED_RINGING -> "SIMULATED_RINGING"
        Call.STATE_AUDIO_PROCESSING -> "AUDIO_PROCESSING"
        else -> "UNKNOWN($state)"
    }
}
