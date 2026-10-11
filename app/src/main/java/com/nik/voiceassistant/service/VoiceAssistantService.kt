package com.nik.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nik.voiceassistant.MainActivity
import com.nik.voiceassistant.R
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.audio.WakeWordDetector
import com.nik.voiceassistant.audio.WakeWordManager

/**
 * Foreground service that owns the microphone in the background, in one of two modes:
 *  - listening: the on-device detector waits for "Hi Nik" (nothing is sent anywhere)
 *  - conversation: a VoiceSession is live; when it ends, back to listening
 *
 * Android only lets an app use the mic in the background from a foreground service started while
 * the app was visible, so the app (or the accessibility service at boot) starts it.
 */
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceAssistantService"
        private const val CHANNEL_ID = "voice_assistant_foreground"
        private const val NOTIFICATION_ID = 4201

        private const val ACTION_LISTEN = "com.nik.voiceassistant.action.LISTEN"
        private const val ACTION_CONVERSATION = "com.nik.voiceassistant.action.CONVERSATION"
        private const val ACTION_STOP = "com.nik.voiceassistant.action.STOP"
        private const val ACTION_REFRESH = "com.nik.voiceassistant.action.REFRESH"

        /** Listening for "Hi Nik" with a mic that works (not silenced by Android). */
        @Volatile var listeningOk = false
            private set

        /** Wait for "Hi Nik" in the background. */
        fun startListening(context: Context) = send(context, ACTION_LISTEN)

        /** From AssistActivity while it is on screen: restart listening so Android grants the mic. */
        fun refreshListening(context: Context) = send(context, ACTION_REFRESH)

        /** A conversation is starting: keep the mic and process alive for it. */
        fun startConversation(context: Context) = send(context, ACTION_CONVERSATION)

        fun stop(context: Context) {
            context.startService(Intent(context, VoiceAssistantService::class.java).setAction(ACTION_STOP))
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, VoiceAssistantService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                // e.g. started from the background on Android 12+: it starts next time the app opens
                Log.w(TAG, "Could not start the voice service: ${e.message}")
            }
        }
    }

    private lateinit var session: VoiceSession
    private lateinit var detector: WakeWordDetector

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        session = VoiceSession.get(this)
        detector = WakeWordDetector(this,
            onListening = { silenced ->
                listeningOk = !silenced
                // Started in the background, Android hands us a muted mic: get it back through the app
                if (silenced) PhoneAgentService.instance?.bringUpListening()
            },
            onWake = { preroll ->
                // "Hi Nik": the detector has let go of the mic; the conversation takes over
                listeningOk = false
                WakeWordManager.playWakeChime()
                session.start(preroll)
            })
        session.onConversationEnded = { listenOrStop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping")
                detector.stop()
                if (session.isActive) session.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONVERSATION -> {
                goForeground("Listening… (conversation active)")
                detector.stop()
                listeningOk = false
            }
            ACTION_REFRESH -> {
                // Called while AssistActivity is visible: going foreground now gets the mic for good
                detector.stop()
                if (!goForeground("Say \"Hi Nik\" to talk")) return START_NOT_STICKY
                if (!session.isActive) detector.start()
            }
            else -> { // ACTION_LISTEN, or a restart by the system
                if (session.isActive) {
                    goForeground("Listening… (conversation active)")
                } else if (goForeground("Say \"Hi Nik\" to talk")) {
                    detector.start()
                }
            }
        }
        return START_STICKY
    }

    private fun listenOrStop() {
        if (session.isWakeWordEnabled.value) {
            goForeground("Say \"Hi Nik\" to talk")
            detector.start()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        detector.stop()
        session.onConversationEnded = null
        super.onDestroy()
    }

    /**
     * Shows the notification and claims the mic. Android refuses a microphone foreground service
     * started from the background; then the accessibility service briefly opens AssistActivity,
     * which starts this again from the foreground. Returns false if refused.
     */
    private fun goForeground(status: String): Boolean {
        val notification = buildNotification(status)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Mic service refused in the background (${e.message}); reopening through the app")
            stopSelf()
            PhoneAgentService.instance?.bringUpListening()
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.voice_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.voice_service_channel_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceAssistantService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_stat_nik)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
