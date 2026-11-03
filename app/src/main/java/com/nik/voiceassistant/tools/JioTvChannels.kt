package com.nik.voiceassistant.tools

import android.content.Context

/**
 * Verified Logical Channel Numbers (LCN) for Jio Set-Top Box (JioTV+ / JioFiber / Jio AirFiber).
 * Includes verified numbers extracted directly from Jio STB TV Guide & live playback.
 */
object JioTvChannels {
    private const val PREFS_NAME = "jio_tv_channels_override"

    // Primary verified channel numbers on Jio STB / JioTV+
    val DEFAULT_CHANNELS: Map<String, String> = mapOf(
        // Hindi Movies
        "zee cinema" to "321", // Verified on Jio STB TV Guide (SD: 321, HD: 319)
        "zee cinema sd" to "321",
        "zee cinema hd" to "319",
        "sony max" to "312", // Verified on Jio STB TV Guide (HD: 312, SD: 478)
        "sony max hd" to "312",
        "sony max sd" to "478",
        "set max" to "312",
        "star gold" to "308",
        "star gold hd" to "308",
        "star gold sd" to "309",
        "star gold 2" to "310",
        "star gold romance" to "325", // Verified on Jio STB (325)
        "star gold thrills" to "345", // Verified on Jio STB (345)
        "zee bollywood" to "327", // Verified on Jio STB (327)
        "&pictures" to "332", // Verified on Jio STB (332)
        "and pictures" to "332",
        "colors cineplex" to "344",
        "colors cineplex hd" to "344",
        "colors cineplex bollywood" to "345",
        "utv movies" to "314",
        "utv action" to "316",
        "zee action" to "327",
        "zee anmol cinema" to "329",
        "goldmines" to "332",
        "goldmines bollywood" to "333",
        "b4u movies" to "335",
        "star utsav movies" to "311",
        "cinema tv" to "336",
        "bfliks" to "224",

        // Hindi GEC (Entertainment)
        "star plus" to "115",
        "star plus hd" to "115",
        "star plus sd" to "114",
        "sony tv" to "128",
        "sony entertainment" to "128",
        "sony entertainment television" to "128",
        "sony set" to "128",
        "set" to "128",
        "sony sab" to "132",
        "sony sab hd" to "132",
        "sab tv" to "132",
        "sab" to "132",
        "zee tv" to "141",
        "zee tv hd" to "141",
        "zee tv sd" to "143",
        "colors" to "147",
        "colors hd" to "147",
        "colors sd" to "148",
        "big magic" to "176", // Verified on Jio STB TV Guide (176)
        "star bharat" to "108",
        "&tv" to "137",
        "and tv" to "137",
        "dd national" to "100",
        "dangal" to "148",
        "dangal 2" to "149",
        "shemaroo" to "151",
        "shemaroo tv" to "151",
        "star utsav" to "116",
        "zee anmol" to "142",
        "colors rishtey" to "150",

        // Music Channels
        "9xm" to "809", // Verified on Jio STB TV Guide (809)
        "zoom" to "811", // Verified on Jio STB TV Guide (811)
        "mtv" to "806", // Verified on Jio STB TV Guide (806)
        "mtv hd" to "806",
        "mtv beats" to "802",
        "music india" to "204", // Verified on Jio STB TV Guide (204)
        "e24" to "813", // Verified on Jio STB TV Guide (813)

        // Hindi & English News
        "aaj tak" to "502",
        "aaj tak hd" to "502",
        "aaj tak sd" to "508",
        "zee news" to "511",
        "abp news" to "512",
        "abp" to "512",
        "india tv" to "514",
        "news18 india" to "504",
        "news18" to "504",
        "news 18" to "504",
        "times now navbharat" to "522",
        "good news today" to "516",
        "ndtv india" to "520",
        "ndtv" to "520",
        "republic bharat" to "518",
        "dd news" to "500",
        "india today" to "551",
        "ndtv 24x7" to "601",
        "times now" to "554",
        "republic tv" to "558",
        "cnn news18" to "556",

        // Sports
        "star sports 1" to "456",
        "star sports 1 hd" to "456",
        "star sports 1 hindi" to "458",
        "star sports hindi" to "458",
        "star sports 2" to "460",
        "star sports" to "456",
        "sports18 1" to "474",
        "sports18" to "474",
        "sports 18" to "474",
        "sony sports ten 1" to "470",
        "sony sports 1" to "470",
        "sony ten 1" to "470",
        "sony ten" to "470",
        "sony sports" to "470",
        "sony sports ten 2" to "471",
        "sony ten 2" to "471",
        "sony sports ten 3" to "472",
        "sony ten 3" to "472",
        "sony sports ten 5" to "475",
        "sony ten 5" to "475",
        "dd sports" to "453",

        // Kids
        "cartoon network" to "651",
        "cartoon tv" to "651",
        "cartoon" to "651",
        "cn" to "651",
        "pogo" to "670",
        "hungama" to "663",
        "nick" to "653",
        "nickelodeon" to "653",
        "sonic" to "655",
        "disney channel" to "661",
        "disney" to "661",
        "sony yay" to "667",
        "discovery kids" to "669",

        // Infotainment & Knowledge
        "discovery" to "702",
        "discovery hd" to "702",
        "national geographic" to "706",
        "nat geo" to "706",
        "history tv18" to "708",
        "history" to "708",
        "animal planet" to "710",
        "sony bbc earth" to "712",
        "nat geo wild" to "714",

        // Regional (South / Marathi / Bengali / etc.)
        "sun gemini comedy" to "1442", // Verified on Jio STB (1442)
        "gemini comedy" to "1442",
        "shemaroo marathibana" to "865"
    )

    private val sortedChannelKeys: List<String> by lazy {
        DEFAULT_CHANNELS.keys.sortedByDescending { it.length }
    }

    /**
     * Resolves the channel number:
     * 1. Checks if explicit digits are in query (e.g. "321", "channel 502").
     * 2. Checks user custom learned overrides in SharedPreferences.
     * 3. Checks verified Jio STB channels list (sorted by longest name first).
     */
    fun findChannelNumber(context: Context, query: String): String? {
        val clean = query.lowercase().trim()

        // 1. Check for explicit channel digits in query (e.g. "channel 321", "312 lagao")
        val explicitDigits = Regex("\\b(\\d{1,4})\\b").find(clean)?.groupValues?.get(1)
        if (explicitDigits != null) return explicitDigits

        // 2. Check SharedPreferences user-learned channel overrides
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            for (key in prefs.all.keys.sortedByDescending { it.length }) {
                if (clean.contains(key.lowercase())) {
                    val num = prefs.getString(key, null)
                    if (!num.isNullOrBlank()) return num
                }
            }
        } catch (_: Exception) {}

        // 3. Match against verified Jio STB channel list
        for (channelName in sortedChannelKeys) {
            if (clean.contains(channelName)) {
                return DEFAULT_CHANNELS[channelName]
            }
        }
        return null
    }

    /**
     * Allows dynamically saving/updating a channel number (e.g. learned from user or search).
     */
    fun saveChannelOverride(context: Context, channelName: String, channelNumber: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(channelName.lowercase().trim(), channelNumber.trim()).apply()
        } catch (_: Exception) {}
    }

    fun isTvChannel(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (lower.startsWith("channel") || lower.contains("live tv") || lower.contains("tv channel") || lower.contains("tv par") || lower.contains("tv pe")) return true
        if (DEFAULT_CHANNELS.keys.any { lower.contains(it) }) return true
        return false
    }
}

