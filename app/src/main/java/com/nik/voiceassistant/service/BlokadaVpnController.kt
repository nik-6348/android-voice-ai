package com.nik.voiceassistant.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.Calendar

/**
 * Manages Blokada AdBlocker VPN activation safely on Android TV / STB devices.
 *
 * Problem it solves:
 * Jio STB lacks a hardware RTC battery and starts at year 2015 on boot.
 * If Blokada VPN (using DNS-over-HTTPS / DoH) is started before system time is
 * synchronized via NTP over raw Wi-Fi/Ethernet, Cloudflare DoH SSL certificate
 * validation fails (CertificateNotYetValid). This causes a permanent deadlock:
 * DNS fails -> NTP cannot resolve time.android.com -> Clock stays at 2015 ->
 * All HTTPS OTT apps (Hotstar, Zee5, etc.) show network errors even with Wi-Fi connected!
 *
 * Solution:
 * 1. Blokada is NEVER started while year < 2024.
 * 2. If boot happens offline, a dynamic NetworkCallback is registered.
 * 3. When Wi-Fi/Internet connects later, the callback waits for NTP to set year >= 2024,
 *    and ONLY THEN safely launches Blokada.
 */
object BlokadaVpnController {
    private const val TAG = "BlokadaVpnController"
    private const val BLOKADA_PKG = "org.blokada.origin.alarm"
    private const val MIN_VALID_YEAR = 2024

    @Volatile
    private var isListening = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun isSystemTimeSynchronized(): Boolean {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return year >= MIN_VALID_YEAR
    }

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    fun ensureBlokadaVpnSafely(context: Context) {
        val appContext = context.applicationContext

        Thread {
            try {
                if (isSystemTimeSynchronized()) {
                    Log.i(TAG, "STB clock is valid (year: ${Calendar.getInstance().get(Calendar.YEAR)}). Launching Blokada if needed.")
                    launchBlokadaIfInactive(appContext)
                    return@Thread
                }

                Log.w(TAG, "STB clock is NOT synchronized (current year: ${Calendar.getInstance().get(Calendar.YEAR)} < $MIN_VALID_YEAR).")
                Log.w(TAG, "Internet is likely offline. Holding Blokada startup until NTP synchronizes system time to prevent DoH SSL deadlock.")
                registerNetworkListener(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Error in ensureBlokadaVpnSafely", e)
            }
        }.start()
    }

    @Synchronized
    private fun registerNetworkListener(context: Context) {
        if (isListening) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network is available. Waiting for NTP clock sync before launching Blokada...")
                checkClockAndLaunch(context)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                    Log.i(TAG, "Network validated. Checking clock for Blokada...")
                    checkClockAndLaunch(context)
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "Network lost.")
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
            isListening = true
            Log.i(TAG, "Registered NetworkCallback for NTP sync and safe Blokada launch.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
        }
    }

    private fun checkClockAndLaunch(context: Context) {
        Thread {
            try {
                var attempts = 0
                while (attempts < 40) {
                    if (isSystemTimeSynchronized()) {
                        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
                        Log.i(TAG, "System clock successfully synchronized to year $currentYear! Starting Blokada safely.")
                        launchBlokadaIfInactive(context)
                        unregisterNetworkListener(context)
                        return@Thread
                    }
                    Thread.sleep(500L)
                    attempts++
                }
                Log.w(TAG, "NTP did not sync clock within 20s. Waiting for next network event.")
            } catch (e: Exception) {
                Log.e(TAG, "Error waiting for NTP sync", e)
            }
        }.start()
    }

    @Synchronized
    private fun unregisterNetworkListener(context: Context) {
        if (!isListening) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
        networkCallback = null
        isListening = false
        Log.i(TAG, "Unregistered NetworkCallback after successful Blokada initialization.")
    }

    private fun launchBlokadaIfInactive(context: Context) {
        if (!isSystemTimeSynchronized()) {
            Log.w(TAG, "ABORT: Refusing to start Blokada because system clock is NOT synchronized (< $MIN_VALID_YEAR)!")
            return
        }

        if (isVpnActive(context)) {
            Log.i(TAG, "Blokada VPN is already active. No launch needed.")
            return
        }

        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(BLOKADA_PKG) == null) {
            Log.w(TAG, "Blokada package $BLOKADA_PKG is not installed on this device.")
            return
        }

        Log.i(TAG, "Starting Blokada AdBlocker...")
        val launchIntent = pm.getLaunchIntentForPackage(BLOKADA_PKG)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        if (launchIntent != null) {
            context.startActivity(launchIntent)
            Thread.sleep(1500L)
            PhoneAgentService.instance?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}
