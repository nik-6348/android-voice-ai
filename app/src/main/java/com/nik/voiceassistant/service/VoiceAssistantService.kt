package com.nik.voiceassistant.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyManager
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

        // Getting the mic back through the app (bringUp): the first try right away, then after 5 s,
        // 15 s, 1 min, and every 5 min from then on
        private val BRING_UP_DELAYS_MS = longArrayOf(0, 5_000, 15_000, 60_000, 300_000)
        // A working mic for this long counts as healthy: the next failure retries right away again
        private const val HEALTHY_RESET_MS = 60_000L
        // During a phone call, how often to look whether it's over
        private const val CALL_RECHECK_MS = 30_000L

        /** Listening for "Hi Nik" with a mic that works (not silenced by Android). */
        @Volatile var listeningOk = false
            private set

        /**
         * Called (main thread) when a conversation is on but the service couldn't hold the mic in the
         * foreground (Android refused, or the mic permission is gone): in the background Android will
         * mute the mic. The service has stopped itself and retries through the app with backoff.
         */
        @Volatile var onConversationForegroundFailed: (() -> Unit)? = null

        // Running in the foreground with the mic right now
        @Volatile private var foreground = false

        // Bring-up retry state; main thread only. Process-wide: the service stops and restarts between tries
        private val mainHandler = Handler(Looper.getMainLooper())
        private var bringUpAttempts = 0
        private var bringUpPending = false
        private var healthySince = 0L

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
            // Without it the microphone service can't go foreground, and a startForegroundService() that
            // never reaches startForeground() crashes the app
            if (!hasMicPermission(context)) {
                Log.w(TAG, "No microphone permission: not starting the voice service")
                return
            }
            val intent = Intent(context, VoiceAssistantService::class.java).setAction(action)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                // e.g. started from the background on Android 12+: it starts next time the app opens
                Log.w(TAG, "Could not start the voice service: ${e.message}")
            }
        }

        private fun hasMicPermission(context: Context) =
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        private fun onMicHealthy() {
            healthySince = SystemClock.elapsedRealtime()
        }

        /**
         * Gets a working mic back through the app: PhoneAgentService opens the invisible AssistActivity,
         * which refreshes this service from the foreground. With backoff, because while another app
         * holds the mic (camera, a call, a recorder) every refresh comes back silenced again and would
         * otherwise relaunch the app every ~2 s.
         */
        private fun requestBringUp(context: Context) {
            val app = context.applicationContext
            if (healthySince != 0L && SystemClock.elapsedRealtime() - healthySince >= HEALTHY_RESET_MS) bringUpAttempts = 0
            healthySince = 0L
            if (bringUpPending) return
            val delay = BRING_UP_DELAYS_MS[minOf(bringUpAttempts, BRING_UP_DELAYS_MS.lastIndex)]
            bringUpAttempts++
            bringUpPending = true
            if (delay > 0) Log.i(TAG, "Retrying the mic through the app in ${delay / 1000} s")
            mainHandler.postDelayed({ bringUp(app) }, delay)
        }

        private fun cancelBringUp() {
            mainHandler.removeCallbacksAndMessages(null)
            bringUpPending = false
            bringUpAttempts = 0
        }

        private fun bringUp(context: Context) {
            bringUpPending = false
            val session = VoiceSession.get(context)
            // Still needed? (the user may have turned "Hi Nik" off, or the mic came back meanwhile)
            val needed = if (session.isActive) !foreground else session.isWakeWordEnabled.value && !listeningOk
            if (!needed || !hasMicPermission(context)) return
            if (inPhoneCall(context, session.isActive)) {
                // The call has the mic and relaunching can't change that: look again once it may be over
                Log.i(TAG, "Phone call in progress: not reopening the app for the mic")
                bringUpPending = true
                mainHandler.postDelayed({ bringUp(context) }, CALL_RECHECK_MS)
                return
            }
            PhoneAgentService.instance?.bringUpListening()
        }

        /** A phone or VoIP call (other than our own conversation's call audio) is ringing or on. */
        private fun inPhoneCall(context: Context, ownConversation: Boolean): Boolean {
            val mode = context.getSystemService(AudioManager::class.java)?.mode
            if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE) return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && mode == AudioManager.MODE_CALL_SCREENING) return true
            if (mode == AudioManager.MODE_IN_COMMUNICATION && !ownConversation) return true
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
            return try {
                @Suppress("DEPRECATION")
                context.getSystemService(TelephonyManager::class.java)?.callState.let { it != null && it != TelephonyManager.CALL_STATE_IDLE }
            } catch (e: SecurityException) {
                false
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
        detector = WakeWordDetector(
            context = this,
            assistantNameProvider = { session.assistantName.value },
            onListening = { silenced ->
                listeningOk = !silenced
                // Started in the background (or another app is recording), Android hands us a muted mic:
                // get it back through the app
                if (silenced) requestBringUp(this) else onMicHealthy()
            },
            onError = {
                // The detector gave up on the mic after its own retries: restart it through the app
                mainHandler.post {
                    listeningOk = false
                    requestBringUp(this)
                }
            },
            onWake = { preroll ->
                // Wake word detected: the detector has let go of the mic; the conversation takes over
                listeningOk = false
                WakeWordManager.playWakeChime()
                session.start(preroll)
            }
        )
        session.onConversationEnded = { listenOrStop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val name = session.assistantName.value
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping")
                // The user stopped it: no retry may bring it back
                cancelBringUp()
                detector.stop()
                if (session.isActive) session.stop()
                foreground = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONVERSATION -> {
                val ok = goForeground("Listening… (conversation active)")
                detector.stop()
                listeningOk = false
                if (!ok) return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                // Called while AssistActivity is visible: going foreground now gets the mic for good
                detector.stop()
                if (!goForeground("Say \"Hi $name\" to talk")) return START_NOT_STICKY
                if (!session.isActive) detector.start()
            }
            else -> { // ACTION_LISTEN, or a restart by the system
                // A restart after the process was killed comes with no intent (and a fresh, idle session):
                // only take the mic back if the user still has "Hi Nik" on
                if (intent == null && !session.isActive && !session.isWakeWordEnabled.value) {
                    Log.i(TAG, "Restarted by the system with the wake word off: stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (session.isActive) {
                    goForeground("Listening… (conversation active)")
                } else if (goForeground("Say \"Hi $name\" to talk")) {
                    detector.start()
                }
            }
        }
        return START_STICKY
    }

    private fun listenOrStop() {
        if (session.isWakeWordEnabled.value) {
            val name = session.assistantName.value
            if (goForeground("Say \"Hi $name\" to talk")) detector.start()
        } else {
            foreground = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Also frees the native wake word model; a restarted service loads it again
        detector.release()
        foreground = false
        listeningOk = false
        session.onConversationEnded = null
        super.onDestroy()
    }

    /**
     * Shows the notification and claims the mic. Android refuses a microphone foreground service
     * started from the background; then the accessibility service briefly opens AssistActivity,
     * which starts this again from the foreground (with backoff). Returns false if refused; the
     * service has stopped itself then.
     */
    private fun goForeground(status: String): Boolean {
        val notification = buildNotification(status)
        val micAllowed = hasMicPermission(this)
        try {
            // Tried even without the mic permission: where Android allows it (before 14) this honours the
            // startForegroundService() contract, so stopping below doesn't crash the app
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            if (micAllowed) {
                foreground = true
                return true
            }
            Log.w(TAG, "No microphone permission: stopping")
        } catch (e: Exception) {
            Log.w(TAG, "Mic service refused (${e.message})${if (micAllowed) "; reopening through the app" else ""}")
            // Without the permission a retry can only fail the same way
            if (micAllowed) requestBringUp(this)
        }
        detector.stop()
        listeningOk = false
        foreground = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (session.isActive) onConversationForegroundFailed?.invoke()
        return false
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
