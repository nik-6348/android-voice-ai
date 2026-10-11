package com.nik.voiceassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nik.voiceassistant.MainActivity
import com.nik.voiceassistant.R

/**
 * Foreground Service that keeps the Voice Assistant microphone capture and
 * WebSocket connection alive in the background.
 */
class VoiceAssistantService : Service() {

    companion object {
        private const val TAG = "VoiceAssistantService"
        private const val CHANNEL_ID = "voice_assistant_foreground"
        private const val NOTIFICATION_ID = 4201

        const val ACTION_START = "com.nik.voiceassistant.action.START"
        const val ACTION_STOP = "com.nik.voiceassistant.action.STOP"

        fun start(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceAssistantService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): VoiceAssistantService = this@VoiceAssistantService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stopping foreground service")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.i(TAG, "Starting foreground service")
                val notification = buildNotification("Listening to voice in background...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }
        return START_STICKY
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
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nik Voice Assistant")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
