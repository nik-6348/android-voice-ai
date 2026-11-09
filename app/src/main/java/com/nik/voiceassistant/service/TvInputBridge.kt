package com.nik.voiceassistant.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.view.KeyEvent
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Direct input bridge for Android TV / Set-Top Box.
 * Android TV does not have a physical touchscreen device in the kernel, so
 * AccessibilityService.dispatchGesture() is rejected with 'Gesture cancelled'.
 *
 * TvInputBridge sends DPAD and key events via the local shell bridge on 127.0.0.1:9998
 * with sub-10ms latency, enabling full remote control and auto-play in TV apps (Cobalt YouTube, etc.).
 */
object TvInputBridge {
    private const val TAG = "TvInputBridge"
    private const val BRIDGE_PORT = 9998
    private const val TIMEOUT_MS = 60

    private val BRIDGE_SCRIPT = "/data/local/tmp/start_tv_bridge.sh"
    @Volatile private var bridgeStartAttempted = false

    private fun ensureBridge() {
        if (bridgeStartAttempted) return
        bridgeStartAttempted = true
        try {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "nohup sh $BRIDGE_SCRIPT > /data/local/tmp/bridge.log 2>&1 &"))
            Log.i(TAG, "Bridge daemon start attempted")
            Thread.sleep(400)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start bridge daemon: ${e.message}")
        }
    }

    fun sendCommand(cmd: String): Boolean {
        // Try once; if the socket is dead, restart the bridge and retry once.
        repeat(2) { attempt ->
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", BRIDGE_PORT), TIMEOUT_MS)
                    s.getOutputStream().write((cmd + "\n").toByteArray())
                    s.getOutputStream().flush()
                }
                Log.d(TAG, "Sent command to bridge: $cmd")
                return true
            } catch (e: Exception) {
                if (attempt == 0) {
                    Log.w(TAG, "Bridge socket unavailable, restarting bridge: ${e.message}")
                    bridgeStartAttempted = false
                    ensureBridge()
                } else {
                    Log.w(TAG, "Bridge unavailable after restart: ${e.message}")
                }
            }
        }
        return false
    }

    fun injectKey(keyCode: Int): Boolean {
        return sendCommand("input keyevent $keyCode")
    }

    fun tuneChannel(channelDigits: String): Boolean {
        // JioTV+'s channel-number overlay drops/resets digits typed faster than ~300ms apart
        // (confirmed on-device: "502" at 150ms gaps lands as "02" -> "No Channel Found").
        // 450ms is the verified-safe gap.
        val cmd = buildString {
            channelDigits.forEachIndexed { index, digit ->
                val code = KeyEvent.KEYCODE_0 + (digit - '0')
                if (index > 0) append(" && sleep 0.45 && ")
                append("input keyevent $code")
            }
            append(" && sleep 0.6 && input keyevent ${KeyEvent.KEYCODE_DPAD_CENTER}")
        }
        return sendCommand(cmd)
    }

    fun sendKey(keyCode: Int): Boolean = injectKey(keyCode)

    fun dpadCenter(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
    fun dpadDown(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_DOWN)
    fun dpadUp(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_UP)
    fun dpadLeft(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_LEFT)
    fun dpadRight(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_RIGHT)

    /**
     * Shell screencap fallback for apps that set FLAG_SECURE (e.g. YouTube TV / Cobalt),
     * which blocks AccessibilityService.takeScreenshot(). The shell user bypasses FLAG_SECURE
     * since it reads directly from the framebuffer.
     */
    fun screencap(): ByteArray? {
        val path = "/data/local/tmp/shiv_screen.png"
        // Write file and chmod so the app process can read it back
        sendCommand("rm -f $path; screencap -p $path; chmod 644 $path")
        // Poll until screencap writes the file (typically 200–600ms on TV hardware)
        val deadline = System.currentTimeMillis() + 2500L
        val file = File(path)
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(120)
            if (file.exists() && file.length() > 1024L) break
        }
        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "screencap: file not ready after 2.5s")
            return null
        }
        return try {
            val bmp = BitmapFactory.decodeFile(path) ?: return null.also { Log.w(TAG, "screencap: BitmapFactory returned null") }
            val width = minOf(720, bmp.width)
            val scaled = Bitmap.createScaledBitmap(bmp, width, bmp.height * width / bmp.width, true)
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 65, it) }.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "screencap: decode failed: ${e.message}")
            null
        }
    }
}

