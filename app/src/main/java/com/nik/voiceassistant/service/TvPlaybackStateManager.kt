package com.nik.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

object TvPlaybackStateManager {
    private const val TAG = "TvPlaybackState"
    private const val PREFS_NAME = "tv_playback_state"
    private const val KEY_MODE = "last_mode" // "livetv", "global", "youtube", "app"
    private const val KEY_TARGET_ID = "last_target_id"
    private const val KEY_TITLE = "last_title"
    private const val KEY_PKG = "last_pkg"
    private const val KEY_AUTO_RESUME = "auto_resume_on_boot"

    fun saveChannel(context: Context, channelId: String?, channelName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, "livetv")
            .putString(KEY_TARGET_ID, channelId)
            .putString(KEY_TITLE, channelName)
            .putString(KEY_PKG, "com.jio.media.jiotvplus")
            .apply()
        Log.i(TAG, "Saved last played Live TV channel: $channelName ($channelId)")
    }

    fun saveYouTube(context: Context, videoId: String, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, "youtube")
            .putString(KEY_TARGET_ID, videoId)
            .putString(KEY_TITLE, title)
            .putString(KEY_PKG, "com.jio.yt")
            .apply()
        Log.i(TAG, "Saved last played YouTube video: $title ($videoId)")
    }

    fun saveGlobalContent(context: Context, contentId: String, title: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_MODE, "global")
            .putString(KEY_TARGET_ID, contentId)
            .putString(KEY_TITLE, title)
            .putString(KEY_PKG, "com.jio.media.jiotvplus")
            .apply()
        Log.i(TAG, "Saved last played Global content: $title ($contentId)")
    }

    fun saveForegroundApp(context: Context, packageName: String) {
        if (packageName.isBlank() || packageName.contains("launcher") ||
            packageName.contains("voiceassistant") || packageName.contains("blokada") ||
            packageName.contains("settings") || packageName.contains("inputmethod")) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val edit = prefs.edit().putString(KEY_PKG, packageName)

        when {
            packageName == "com.jio.media.jiotvplus" -> {
                // If user is in JioTV+, keep or set mode as livetv
                if (prefs.getString(KEY_MODE, null) != "global") {
                    edit.putString(KEY_MODE, "livetv")
                }
            }
            packageName == "com.jio.yt" || packageName == "com.google.android.youtube.tv" -> {
                edit.putString(KEY_MODE, "youtube")
            }
            else -> {
                // OTT Apps: Hotstar, Zee5, JioCinema, SonyLiv, Prime Video, etc.
                edit.putString(KEY_MODE, "app")
            }
        }
        edit.apply()
        Log.i(TAG, "Tracked foreground app for auto-resume: $packageName (mode=${prefs.getString(KEY_MODE, "app")})")
    }

    fun isAutoResumeEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_RESUME, true)
    }

    fun setAutoResumeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_RESUME, enabled).apply()
    }

    fun resumeLastPlayed(context: Context) {
        if (!isAutoResumeEnabled(context)) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(KEY_MODE, "livetv") ?: "livetv"
        val targetId = prefs.getString(KEY_TARGET_ID, null)
        val title = prefs.getString(KEY_TITLE, "")
        val pkg = prefs.getString(KEY_PKG, "com.jio.media.jiotvplus") ?: "com.jio.media.jiotvplus"

        Log.i(TAG, "Resuming last played content on TV boot: mode=$mode, targetId=$targetId, title=$title, pkg=$pkg")

        when (mode) {
            "livetv" -> {
                val uriStr = if (!targetId.isNullOrBlank()) {
                    "jiotvplus://open.classicplayer?cid=$targetId&source=VA"
                } else {
                    "jiotvplus://open.classicplayer"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriStr)).apply {
                    setPackage("com.jio.media.jiotvplus")
                    putExtra("APP_NAME", "jiotv+")
                    putExtra("clickSource", "channelLaunch")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val fallback = context.packageManager.getLaunchIntentForPackage("com.jio.media.jiotvplus")
                    fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    fallback?.let { context.startActivity(it) }
                }
            }
            "youtube" -> {
                val ytIntent = if (!targetId.isNullOrBlank()) {
                    val playUri = "https://www.youtube.com/watch?v=$targetId"
                    Intent(Intent.ACTION_VIEW, Uri.parse(playUri)).apply {
                        component = android.content.ComponentName("com.jio.yt", "dev.cobalt.app.MainActivity")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                } else {
                    (context.packageManager.getLeanbackLaunchIntentForPackage("com.jio.yt")
                        ?: context.packageManager.getLaunchIntentForPackage("com.jio.yt"))?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                }
                try {
                    if (ytIntent != null) context.startActivity(ytIntent)
                } catch (e: Exception) {
                    context.packageManager.getLaunchIntentForPackage("com.jio.yt")?.let {
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(it)
                    }
                }
            }
            "global" -> {
                if (!targetId.isNullOrBlank()) {
                    val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse("jiotvplus://$targetId?source=VA")).apply {
                        setPackage("com.jio.media.jiotvplus")
                        putExtra("APP_NAME", "jiotv+")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                    try {
                        context.startActivity(viewIntent)
                        Thread {
                            try {
                                Thread.sleep(1500L)
                                TvInputBridge.dpadCenter()
                            } catch (_: Exception) {}
                        }.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error auto-resuming global content", e)
                    }
                }
            }
            else -> {
                // OTT Apps (Hotstar, Zee5, SonyLIV, JioCinema, etc.)
                val launchIntent = context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                    ?: context.packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    try {
                        context.startActivity(launchIntent)
                        // Send MEDIA_PLAY after short delay to auto-resume playback
                        Thread {
                            try {
                                Thread.sleep(2500L)
                                val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                                am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                                am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY))
                            } catch (_: Exception) {}
                        }.start()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to auto-resume OTT app: $pkg", e)
                    }
                }
            }
        }
    }
}
