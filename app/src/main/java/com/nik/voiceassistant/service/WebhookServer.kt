package com.nik.voiceassistant.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.nik.voiceassistant.data.macro.MacroDispatch
import com.nik.voiceassistant.data.macro.MacroStore
import com.nik.voiceassistant.data.macro.Trigger
import com.nik.voiceassistant.tools.DeviceToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.NetworkInterface

/**
 * Tiny HTTP server (port 7474) that runs on the phone itself, reachable only from the same local
 * network (Wi-Fi or Tailscale). An incoming POST/GET to /webhook/<path> fires any macro that has a
 * matching Trigger.Webhook. Never exposed to the open internet — the phone has no public IP and no
 * port-forwarding is required or set up.
 *
 * Start it once from VoiceSession.init; it lives for the lifetime of that session.
 */
object WebhookServer {
    const val PORT = 7474
    private const val TAG = "WebhookServer"

    private var serverSocket: ServerSocket? = null

    fun start(context: Context, scope: CoroutineScope, toolExecutor: () -> DeviceToolExecutor?) {
        if (serverSocket?.isClosed == false) return
        scope.launch(Dispatchers.IO) {
            try {
                val ss = ServerSocket(PORT).also { serverSocket = it }
                Log.i(TAG, "Webhook server listening on port $PORT")
                while (isActive && !ss.isClosed) {
                    val client = try { ss.accept() } catch (e: SocketException) { break }
                    launch { handle(client, context, toolExecutor) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Webhook server failed to start or crashed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    fun stop() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Webhook server stopped")
    }

    /** Best-effort: returns the phone's LAN IP, or null if Wi-Fi is disconnected. */
    fun localAddress(context: Context): String? {
        return try {
            // Try Wi-Fi first (fast path)
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val wifiIp = wm?.connectionInfo?.ipAddress ?: 0
            if (wifiIp != 0) {
                return "${wifiIp and 0xff}.${(wifiIp shr 8) and 0xff}.${(wifiIp shr 16) and 0xff}.${(wifiIp shr 24) and 0xff}"
            }
            // Fall back to iterating network interfaces (works for Tailscale too)
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { iface -> iface.inetAddresses.asSequence() }
                ?.firstOrNull { addr -> !addr.isLoopbackAddress && addr.hostAddress?.contains(':') == false }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    private suspend fun handle(client: Socket, context: Context, toolExecutor: () -> DeviceToolExecutor?) =
        withContext(Dispatchers.IO) {
            try {
                client.soTimeout = 3000
                val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                val writer = PrintWriter(client.getOutputStream(), true)

                // Parse the request line: "POST /webhook/my-path HTTP/1.1"
                val requestLine = reader.readLine() ?: return@withContext
                val parts = requestLine.split(" ")
                val rawPath = if (parts.size >= 2) parts[1] else "/"

                // Collect headers
                val headers = mutableMapOf<String, String>()
                var line: String?
                while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                    val colon = line!!.indexOf(':')
                    if (colon > 0) headers[line!!.substring(0, colon).trim().lowercase()] = line!!.substring(colon + 1).trim()
                }

                // Normalise path and extract query params
                val (pathPart, queryPart) = if ('?' in rawPath) {
                    rawPath.substringBefore('?') to rawPath.substringAfter('?')
                } else { rawPath to "" }
                val queryParams = queryPart.split('&').associate {
                    val kv = it.split('=', limit = 2); (kv.getOrNull(0) ?: "") to (kv.getOrNull(1) ?: "")
                }

                // Webhook path: /webhook/<path> or /<path>
                val webhookPath = pathPart.removePrefix("/webhook").trimStart('/').lowercase()
                val tokenFromHeader = headers["x-webhook-token"]
                val tokenFromQuery = queryParams["token"]

                Log.i(TAG, "Webhook request: $requestLine path=$webhookPath")

                // Find matching macros
                val macros = MacroStore.getAll(context).filter { macro ->
                    macro.enabled && macro.triggers.any { trig ->
                        trig is Trigger.Webhook &&
                            trig.path.trimStart('/').lowercase() == webhookPath &&
                            (trig.secret.isNullOrEmpty() ||
                                trig.secret == tokenFromHeader || trig.secret == tokenFromQuery)
                    }
                }

                if (macros.isEmpty()) {
                    val wrongSecret = MacroStore.getAll(context).any { macro ->
                        macro.enabled && macro.triggers.any { trig ->
                            trig is Trigger.Webhook && trig.path.trimStart('/').lowercase() == webhookPath
                        }
                    }
                    val (status, msg) = if (wrongSecret) "403 Forbidden" to "wrong_token"
                                        else "404 Not Found" to "no_webhook_for_path"
                    sendResponse(writer, status, """{"ok":false,"error":"$msg"}""")
                    return@withContext
                }

                val executor = toolExecutor()
                if (executor == null) {
                    sendResponse(writer, "503 Service Unavailable", """{"ok":false,"error":"assistant_not_ready"}""")
                    return@withContext
                }
                macros.forEach { macro ->
                    MacroDispatch.run(context, macro, executor)
                    Log.i(TAG, "Fired webhook macro '${macro.title}' for path /$webhookPath")
                }

                sendResponse(writer, "200 OK", """{"ok":true,"fired":${macros.size}}""")
            } catch (e: Exception) {
                Log.w(TAG, "Error handling webhook request: ${e.message}")
            } finally {
                try { client.close() } catch (_: Exception) {}
            }
        }

    private fun sendResponse(writer: PrintWriter, status: String, body: String) {
        writer.print("HTTP/1.1 $status\r\n")
        writer.print("Content-Type: application/json\r\n")
        writer.print("Content-Length: ${body.toByteArray().size}\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.print(body)
        writer.flush()
    }
}
