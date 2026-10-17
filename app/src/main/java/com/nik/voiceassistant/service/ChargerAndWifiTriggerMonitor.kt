package com.nik.voiceassistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.util.Log
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.data.macro.MacroDispatch
import com.nik.voiceassistant.data.macro.MacroEngine
import com.nik.voiceassistant.data.macro.Trigger

/** Matches charger-plugged/unplugged and Wi-Fi-connected triggers, best-effort (never the critical path). */
object ChargerAndWifiTriggerMonitor {
    private const val TAG = "ChargerWifiTrigger"

    @Volatile private var receiver: BroadcastReceiver? = null
    @Volatile private var appContext: Context? = null

    fun start(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        appContext = app
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                try {
                    handle(app, intent)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't handle ${intent.action}: ${e.javaClass.simpleName}")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        try {
            app.registerReceiver(r, filter)
            receiver = r
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't register the charger/Wi-Fi receiver: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        val app = appContext
        val r = receiver
        if (app != null && r != null) runCatching { app.unregisterReceiver(r) }
        receiver = null
        appContext = null
    }

    private fun handle(context: Context, intent: Intent) {
        val matches = when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> MacroEngine.matching(context) { it is Trigger.ChargerConnected }
            Intent.ACTION_POWER_DISCONNECTED -> MacroEngine.matching(context) { it is Trigger.ChargerDisconnected }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val wifi = context.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val connected = wifi?.connectionInfo?.networkId != -1
                if (!connected) emptyList() else {
                    @Suppress("DEPRECATION")
                    val ssid = wifi?.connectionInfo?.ssid?.trim('"').orEmpty()
                    MacroEngine.matching(context) { t -> t is Trigger.WifiConnected && (t.ssidContains.isNullOrBlank() || ssid.contains(t.ssidContains, ignoreCase = true)) }
                }
            }
            else -> emptyList()
        }
        if (matches.isEmpty()) return
        val executor = VoiceSession.get(context).getToolExecutor()
        for (macro in matches) MacroDispatch.run(context, macro, executor)
    }
}
