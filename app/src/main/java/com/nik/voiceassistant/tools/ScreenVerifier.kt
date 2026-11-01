package com.nik.voiceassistant.tools

import android.os.Build
import android.util.Base64
import android.util.Log
import com.nik.voiceassistant.service.PhoneAgentService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

private const val TAG = "ScreenVerifier"

/**
 * Two-tier screen verifier.
 *
 * **Tier-1** ([ElementVerifier]): local keyword match against accessibility elements, ~0–5 ms.
 * Returns immediately when conclusive (yes/no).
 *
 * **Tier-2** (vision): captures a screenshot, sends it to the gateway `/v1/screen/verify`, ~2–7 s.
 * Only used when Tier-1 is inconclusive, when [forceVision] is true, or when
 * [understandingMode] is [MODE_EVERY_ACTION].
 *
 * @param gatewayBase  Lambda returning the HTTP base URL of the gateway (e.g. "https://…").
 * @param apiKey       Lambda returning the current API key for gateway auth.
 * @param agent        Lambda returning the [PhoneAgentService] instance, or null.
 * @param understandingMode  "text_only" | "smart" | "every_action" (default "smart").
 * @param deniedApps   App packages that should never send screenshots (banking denylist).
 */
class ScreenVerifier(
    private val gatewayBase: () -> String,
    private val apiKey: () -> String,
    private val agent: () -> PhoneAgentService?,
    var understandingMode: String = MODE_SMART,
    private val deniedApps: Set<String> = emptySet()
) {
    companion object {
        const val MODE_TEXT_ONLY = "text_only"
        const val MODE_SMART = "smart"
        const val MODE_EVERY_ACTION = "every_action"

        // Rate limit: 1 screenshot per second (AccessibilityService limit).
        private const val RATE_LIMIT_MS = 1000L

        /**
         * Heuristic: if ≥90% of the JPEG bytes are near-zero (<15), the image is likely
         * all-black (FLAG_SECURE or screen off). Works on raw bytes without decoding.
         */
        fun isBlackImage(jpeg: ByteArray): Boolean {
            if (jpeg.isEmpty()) return false
            // Count bytes that are exactly zero; real JPEG data uses non-zero values even in dark
            // regions (encoding overhead). FLAG_SECURE images are literally all-zero after the
            // header, so a ≥90% zero-byte ratio is a reliable indicator.
            val zeroBytes = jpeg.count { it == 0.toByte() }
            return zeroBytes.toDouble() / jpeg.size >= 0.90
        }
    }

    @Volatile private var lastCaptureAt = 0L

    private val http = OkHttpClient.Builder()
        .callTimeout(7, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    /**
     * Main entry: run tier-1 element check, fall back to tier-2 vision if needed.
     *
     * @param forceVision  Skip tier-1 and go straight to tier-2 (e.g. from [lookAtScreen]).
     */
    suspend fun verify(
        expect: String,
        elements: List<String>,
        app: String,
        goal: String,
        stepIndex: Int,
        stepTitle: String,
        lastTool: String,
        lastArgs: Map<String, Any?>,
        forceVision: Boolean = false
    ): VerifyResult {
        // Tier-1: fast local element match (skip when forceVision or every_action mode)
        if (!forceVision && understandingMode != MODE_EVERY_ACTION) {
            ElementVerifier.check(expect, elements, app)?.let { return it }
        }

        // Text-only mode: don't send screenshots
        if (understandingMode == MODE_TEXT_ONLY) {
            return VerifyResult(
                verified = "unsure",
                via = "text_only",
                evidence = "Screen understanding is set to text only; no screenshot taken",
                blocker = null,
                stepDone = false,
                nextAction = null,
                elementRefs = emptyList()
            )
        }

        // SDK < 30: no AccessibilityService screenshot support
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return VerifyResult(
                verified = "unsure",
                via = "text_only",
                evidence = "Screenshot not supported on Android < 11",
                blocker = null,
                stepDone = false,
                nextAction = null,
                elementRefs = emptyList()
            )
        }

        // Banking/DRM denylist
        if (app in deniedApps) {
            return VerifyResult(
                verified = "unsure",
                via = "text_only",
                evidence = "App '$app' is in the banking denylist; no screenshot sent",
                blocker = null,
                stepDone = false,
                nextAction = null,
                elementRefs = emptyList()
            )
        }

        // Capture screenshot (rate-limited to 1/sec)
        val jpeg = captureJpeg()
            ?: return VerifyResult(
                verified = "unsure",
                via = "timeout",
                evidence = "Screenshot unavailable (FLAG_SECURE or rate limit)",
                blocker = null,
                stepDone = false,
                nextAction = null,
                elementRefs = emptyList()
            )

        // Tier-2: send to gateway
        return callGateway(jpeg, expect, elements, app, goal, stepIndex, stepTitle, lastTool, lastArgs)
    }

    /**
     * Captures a JPEG of the app window (preferred) or full screen.
     * Rate-limited to 1/second. Returns null on FLAG_SECURE or capture failure.
     */
    suspend fun captureJpeg(): ByteArray? = withContext(Dispatchers.IO) {
        // Rate limit
        val now = System.currentTimeMillis()
        val wait = RATE_LIMIT_MS - (now - lastCaptureAt)
        if (wait > 0) Thread.sleep(wait)

        val svc = agent() ?: return@withContext null

        val jpeg = try {
            svc.appWindowJpeg() ?: svc.screenshotJpeg(hideOverlay = true)
        } catch (e: Exception) {
            Log.w(TAG, "Screenshot failed: ${e.message}")
            null
        }

        lastCaptureAt = System.currentTimeMillis()

        if (jpeg == null || isBlackImage(jpeg)) {
            Log.d(TAG, "Screenshot is null or black (FLAG_SECURE?)")
            return@withContext null
        }

        jpeg
    }

    private suspend fun callGateway(
        jpeg: ByteArray,
        expect: String,
        elements: List<String>,
        app: String,
        goal: String,
        stepIndex: Int,
        stepTitle: String,
        lastTool: String,
        lastArgs: Map<String, Any?>
    ): VerifyResult = withContext(Dispatchers.IO) {
        val imageB64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("image_base64", imageB64)
            put("image_mime", "image/jpeg")
            put("file_base64", imageB64)
            put("file_name", "screen.jpg")
            put("file_mime", "image/jpeg")
            put("app", app)
            put("goal", goal)
            put("step", JSONObject().apply {
                put("index", stepIndex)
                put("title", stepTitle)
                put("expect", expect)
            })
            put("last_action", JSONObject().apply {
                put("tool", lastTool)
                put("args", JSONObject(lastArgs.mapValues { it.value?.toString() ?: "" }))
            })
            put("elements", JSONArray(elements.take(40)))
            put("question", "Did the action succeed? Is the expected state visible?")
        }

        val request = Request.Builder()
            .url("${gatewayBase()}/v1/screen/verify")
            .header("Authorization", "Bearer ${apiKey()}")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Gateway verify failed: ${response.code}")
                    return@withContext unsure("gateway_error_${response.code}")
                }
                val json = JSONObject(response.body?.string().orEmpty())
                parseGatewayResponse(json)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gateway verify exception: ${e.message}")
            unsure("network_error: ${e.message?.take(60)}")
        }
    }

    private fun parseGatewayResponse(json: JSONObject): VerifyResult {
        val verified = when (json.optString("verified")) {
            "yes" -> "yes"
            "no" -> "no"
            else -> "unsure"
        }
        val elementRefs = json.optJSONArray("element_refs")?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }
        } ?: emptyList()
        val nextActionJson = json.optJSONObject("next_action")
        val nextAction: Map<String, Any?>? = nextActionJson?.let { na ->
            val argsJson = na.optJSONObject("args")
            val argsMap: Map<String, Any?> = argsJson?.let { obj ->
                val keys = obj.keys()
                val map = mutableMapOf<String, Any?>()
                while (keys.hasNext()) { val k = keys.next(); map[k] = obj.opt(k) }
                map
            } ?: emptyMap()
            mapOf("tool" to na.optString("tool"), "args" to argsMap, "why" to na.optString("why"))
        }
        return VerifyResult(
            verified = verified,
            via = "vision",
            evidence = json.optString("evidence", json.optString("screen_summary", "Vision check complete")),
            blocker = json.optString("blocker").takeIf { it.isNotEmpty() && it != "null" },
            stepDone = json.optBoolean("step_done", verified == "yes"),
            nextAction = nextAction,
            elementRefs = elementRefs
        )
    }

    private fun unsure(reason: String) = VerifyResult(
        verified = "unsure",
        via = "vision",
        evidence = reason,
        blocker = null,
        stepDone = false,
        nextAction = null,
        elementRefs = emptyList()
    )
}
