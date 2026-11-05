package com.nik.voiceassistant.tools

import android.content.Context
import android.net.Uri
import android.util.Log

/**
 * High-performance Dynamic Channel Resolution for Jio Set-Top Box (JioTV+ / JioFiber / Jio AirFiber).
 * 
 * Architecture:
 * 1. Queries native JioTV+ ContentProvider (content://com.jio.media.jiotvplus) live on device to get the
 *    exact 6-digit Content ID (e.g. Aaj Tak = 301036, Zee Cinema = 300310, Sony MAX = 300277).
 * 2. Caches resolved channels in SharedPreferences for instant 0ms subsequent access.
 * 3. Verified fallback dictionary of real 6-digit JioTV+ Content IDs.
 */
object JioTvChannels {
    private const val TAG = "JioTvChannels"
    private const val PREFS_NAME = "jio_tv_channels_override"

    // Verified real 6-digit Content IDs extracted directly from JioTV+ on Jio STB
    val DEFAULT_CHANNELS: Map<String, String> = mapOf(
        // Hindi News
        "aaj tak" to "301036",
        "aaj tak hd" to "301036",
        "abp news" to "300001",
        "abp" to "300001",
        "india tv" to "300007",
        "news18 india" to "300209",
        "news18" to "300209",
        "ndtv india" to "301049",
        "ndtv" to "301049",
        "zee news" to "300314",
        "dd news" to "300185",
        "times now navbharat" to "304015",
        "republic bharat" to "301550",

        // Hindi Movies
        "zee cinema" to "300310",
        "zee cinema hd" to "300330",
        "sony max" to "300277",
        "sony max hd" to "300277",
        "sony max 2" to "301559",
        "set max" to "300277",
        "star gold" to "301971",
        "star gold hd" to "301971",
        "star gold 2" to "302104",
        "colors cineplex" to "301385",
        "colors cineplex hd" to "301385",
        "zee bollywood" to "302061",
        "&pictures" to "300340",
        "and pictures" to "300340",

        // Hindi GEC (Entertainment)
        "star plus" to "301998",
        "star plus hd" to "301998",
        "colors" to "304140",
        "colors hd" to "301543",
        "zee tv" to "300378",
        "zee tv hd" to "300378",
        "sony tv" to "300274",
        "sony entertainment" to "300274",
        "sony sab" to "300275",
        "sab tv" to "300275",
        "sab" to "300275",
        "&tv" to "300345",
        "and tv" to "300345",
        "star bharat" to "301990",
        "dangal" to "301077",
        "dd national" to "300184",

        // Sports
        "star sports 1 hindi" to "301982",
        "star sports 1 hindi hd" to "301982",
        "star sports hindi" to "301982",
        "star sports 1" to "302115",
        "star sports 1 hd" to "301981",
        "star sports 2" to "301983",
        "star sports 2 hd" to "301983",
        "star sports" to "301982",
        "sports18 1" to "304030",
        "sports18" to "304030",
        "sony sports ten 1" to "301580",
        "sony sports ten 3" to "301582",
        "dd sports" to "300188",

        // Music
        "9xm" to "300010",
        "zoom" to "300375",
        "zing" to "300325",
        "mtv" to "301470",
        "mtv beats" to "301475",
        "b4u music" to "300060",
        "mastiii" to "300240",

        // Kids
        "cartoon network" to "301585",
        "cartoon network hd+" to "304059",
        "pogo" to "301586",
        "hungama" to "301995",
        "disney" to "301993",
        "nick" to "301480",
        "nickelodeon" to "301480",
        "sonic" to "301485",

        // Infotainment
        "discovery" to "300170",
        "national geographic" to "301975",
        "nat geo" to "301975",
        "history tv18" to "301490"
    )

    private val sortedChannelKeys: List<String> by lazy {
        DEFAULT_CHANNELS.keys.sortedByDescending { it.length }
    }

    /**
     * Resolves the channel to a verified 6-digit Content ID:
     * 1. If explicit 6-digit ID is already provided, use it directly.
     * 2. Checks cached/learned channel overrides from SharedPreferences (0ms).
     * 3. Queries JioTV+'s native ContentProvider dynamically (live search).
     * 4. Matches against verified static fallback map.
     */
    fun resolveJioTvChannel(context: Context, query: String): String? {
        val clean = cleanChannelName(query)
        if (clean.isBlank()) return null

        // 1. If query is already a 6-digit numeric Content ID (e.g. "301036")
        if (clean.length >= 5 && clean.all { it.isDigit() }) {
            return clean
        }

        // 2. Check SharedPreferences learned overrides
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cached = prefs.getString(clean.lowercase(), null)
            if (!cached.isNullOrBlank()) {
                Log.d(TAG, "Resolved '$clean' -> $cached from cached overrides")
                return cached
            }
        } catch (_: Exception) {}

        // 3. Dynamic resolution via JioTV+ native ContentProvider (Exact STB catalog)
        val dynamicId = queryJioTvProvider(context, clean)
        if (!dynamicId.isNullOrBlank()) {
            return dynamicId
        }

        // 4. Static dictionary fallback
        val cleanLower = clean.lowercase()
        for (key in sortedChannelKeys) {
            if (cleanLower.contains(key)) {
                val id = DEFAULT_CHANNELS[key]
                if (!id.isNullOrBlank()) {
                    Log.d(TAG, "Resolved '$clean' -> $id from static verified dictionary (key='$key')")
                    saveChannelOverride(context, clean, id)
                    return id
                }
            }
        }

        return null
    }

    /**
     * Cleans channel search query removing extraneous conversational words.
     */
    private fun cleanChannelName(query: String): String {
        var q = query.trim().lowercase()
        q = q.replace(Regex("(?i)\\b(channel|live tv|tv|jiotv|jio tv)\\b"), " ")
        q = q.replace(Regex("(?i)\\b(lagade|laga do|laga de|lagao|lagana|chala do|chala de|chalao|chalana|play|khol do|kholo|open|dikhao)\\b"), " ")
        q = q.replace(Regex("(?i)\\b(par|pe|me|mein|ke|ka|ki|ko)\\b"), " ")
        return q.replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Dynamically queries JioTV+'s internal SearchProvider for live TV channel Content IDs.
     * ContentProvider: content://com.jio.media.jiotvplus
     * Columns: _id, suggest_text_1, suggest_text_2, search_suggest_query, suggest_intent_action
     */
    fun queryJioTvProvider(context: Context, query: String): String? {
        val clean = query.trim()
        if (clean.isBlank()) return null

        val queriesToTry = mutableListOf(clean)
        val words = clean.split("\\s+".toRegex())
        if (words.size > 2) {
            queriesToTry.add(words.take(2).joinToString(" "))
        }

        for (qText in queriesToTry) {
            try {
                val uri = Uri.parse("content://com.jio.media.jiotvplus")
                val cursor = context.contentResolver.query(uri, null, null, arrayOf(qText), null)
                cursor?.use { c ->
                    val text1Col = c.getColumnIndex("suggest_text_1")
                    val queryCol = c.getColumnIndex("search_suggest_query")
                    val actionCol = c.getColumnIndex("suggest_intent_action")

                    var bestMatchId: String? = null
                    var bestMatchExact = false

                    while (c.moveToNext()) {
                        val title = if (text1Col >= 0) c.getString(text1Col) ?: "" else ""
                        val searchSuggest = if (queryCol >= 0) c.getString(queryCol) ?: "" else ""
                        val intentAction = if (actionCol >= 0) c.getString(actionCol) ?: "" else ""

                        val rawId = when {
                            searchSuggest.contains("/") -> searchSuggest.substringAfterLast("/")
                            intentAction.contains("/") -> intentAction.substringAfterLast("/")
                            else -> null
                        }

                        // Live TV channels have purely numeric Content IDs (usually 6 digits, e.g. 301036)
                        if (rawId != null && rawId.length in 5..8 && rawId.all { it.isDigit() }) {
                            val titleLower = title.lowercase().trim()
                            val qLower = clean.lowercase().trim()

                            val isExact = titleLower == qLower || titleLower.startsWith(qLower)
                            if (isExact) {
                                Log.i(TAG, "Exact dynamic channel match: '$title' -> $rawId (query='$clean')")
                                saveChannelOverride(context, clean, rawId)
                                return rawId
                            }

                            if (bestMatchId == null && (titleLower.contains(qLower) || qLower.contains(titleLower))) {
                                bestMatchId = rawId
                            }
                        }
                    }

                    if (bestMatchId != null) {
                        Log.i(TAG, "Partial dynamic channel match -> $bestMatchId (query='$clean')")
                        saveChannelOverride(context, clean, bestMatchId)
                        return bestMatchId
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "JioTV+ dynamic provider query failed for '$qText': ${e.message}")
            }
        }
        return null
    }

    /**
     * Saves a learned channel name -> Content ID mapping.
     */
    fun saveChannelOverride(context: Context, channelName: String, channelId: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(channelName.lowercase().trim(), channelId.trim()).apply()
        } catch (_: Exception) {}
    }

    fun isTvChannel(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (lower.startsWith("channel") || lower.contains("live tv") || lower.contains("tv channel") ||
            lower.contains("tv par") || lower.contains("tv pe") || lower.contains("tv me") || lower.contains("tv mein")) {
            return true
        }
        val clean = cleanChannelName(query)
        if (DEFAULT_CHANNELS.keys.any { clean.contains(it) || it.contains(clean) }) return true
        return false
    }
}
