package com.nik.voiceassistant.service

import android.util.Log
import android.view.KeyEvent
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

    fun sendCommand(cmd: String): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", BRIDGE_PORT), TIMEOUT_MS)
                s.getOutputStream().write((cmd + "\n").toByteArray())
                s.getOutputStream().flush()
            }
            Log.d(TAG, "Sent command to bridge: $cmd")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Bridge socket unavailable: ${e.message}")
            false
        }
    }

    fun injectKey(keyCode: Int): Boolean {
        return sendCommand("input keyevent $keyCode")
    }

    fun tuneChannel(channelDigits: String): Boolean {
        val cmd = buildString {
            channelDigits.forEachIndexed { index, digit ->
                val code = KeyEvent.KEYCODE_0 + (digit - '0')
                if (index > 0) append(" && sleep 0.22 && ")
                append("input keyevent $code")
            }
            append(" && sleep 0.4 && input keyevent ${KeyEvent.KEYCODE_DPAD_CENTER}")
        }
        return sendCommand(cmd)
    }

    fun sendKey(keyCode: Int): Boolean = injectKey(keyCode)

    fun dpadCenter(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_CENTER)
    fun dpadDown(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_DOWN)
    fun dpadUp(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_UP)
    fun dpadLeft(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_LEFT)
    fun dpadRight(): Boolean = injectKey(KeyEvent.KEYCODE_DPAD_RIGHT)
}

