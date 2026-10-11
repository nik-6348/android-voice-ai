package com.nik.voiceassistant.tools

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.nik.voiceassistant.service.PhoneAgentService

/**
 * The phone tools the voice model can call. They are generic on purpose: the model reads the
 * screen and decides what to tap or type, and uses launch_intent with an action / URI it knows
 * or finds with Google Search, so no app needs its own code here.
 */
class DeviceToolExecutor(
    private val context: Context,
    // end_conversation: the session ends once the goodbye has played
    private val onEndConversation: () -> Unit = {},
    // look_at_screen: (screenshot JPEG, prompt) -> what a vision model sees, or null on failure
    private val describeImage: ((ByteArray, String) -> String?)? = null,
    // show_choices: (question, options) shown as a tappable list in the app and the overlay
    private val onShowChoices: (String, List<String>) -> Unit = { _, _ -> },
    // Whether the user said yes after the last list was shown (one use); gates send / pay / delete taps
    private val takeConfirmation: () -> Boolean = { false }
) {

    companion object {
        private const val TAG = "DeviceToolExecutor"
        private const val MAX_APPS = 60

        // Buttons that act on the user's behalf for others to see, or can't be undone (Share / Reply only open
        // the next screen, so they don't count)
        private val NEEDS_YES = Regex(
            """(?i)\b(send|post|publish|submit|pay|buy|purchase|order|delete|remove|transfer)\b|भेज|पोस्ट|भुगतान|डिलीट"""
        )
        private const val ASK_FIRST = "Not pressed: this sends / pays / deletes, and the user hasn't confirmed yet. Say who it goes to " +
            "and the exact text (or what will happen), call show_choices with \"Haan, bhej do\", \"Nahi\" and \"Badalna hai\", " +
            "and wait for their answer before tapping it again."

        private const val AGENT_OFF = "Screen control is off. Ask the user to enable \"Nik's voice\" in " +
            "Settings > Accessibility (you can open it with launch_intent action android.settings.ACCESSIBILITY_SETTINGS)."
    }

    private val packageManager: PackageManager = context.packageManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    /**
     * Function declarations sent to Gemini in the session setup message (JSON Schema parameters).
     * Adding a tool = a declaration here + a branch in execute(); the server needs no change.
     */
    val declarations: List<Map<String, Any>> = listOf(
        function(
            "read_screen",
            "Reads what is on the phone screen now: the foreground app and a numbered list of elements " +
                "like [3] Button \"Send\" (tap). Call it before acting on the screen or when the user refers to what is on it."
        ),
        function(
            "look_at_screen",
            "Looks at a screenshot of the screen and describes what is visible: videos and whether they play, images, " +
                "games, a floating mini player, anything the read_screen list misses. Slower (~4 s): use it when read_screen " +
                "isn't enough, when the user asks what's on the screen, or to check that something worked.",
            optional = listOf("question" to param("string", "What you want to know, e.g. \"is the video playing?\""))
        ),
        function(
            "tap",
            "Taps an element on screen by its number from the latest screen, or by its visible text. Returns the new screen.",
            optional = listOf(
                "element_id" to param("integer", "Element number from the latest screen, e.g. 3 for [3]"),
                "text" to param("string", "Visible text of the element, when no element_id"),
                "long_press" to param("boolean", "Long-press instead of tap")
            )
        ),
        function(
            "type_text",
            "Types text into a text field (replacing its content): the given element, else the focused field. Returns the new screen.",
            required = listOf("text" to param("string", "Text to type")),
            optional = listOf(
                "element_id" to param("integer", "The text field's element number"),
                "submit" to param("boolean", "Press the keyboard's enter/search/send key after typing")
            )
        ),
        function(
            "scroll",
            "Scrolls the screen or a list to see more. Returns the new screen.",
            required = listOf("direction" to param("string", "Where to move the view", enum = listOf("down", "up", "left", "right"))),
            optional = listOf("element_id" to param("integer", "The scrollable element's number, if not the main list"))
        ),
        function(
            "swipe",
            "Swipes a finger across an element or the screen (direction = where the finger moves). " +
                "E.g. to close an app: navigate recents, then swipe up on that app's card. Returns the new screen.",
            required = listOf("direction" to param("string", "Finger direction", enum = listOf("up", "down", "left", "right"))),
            optional = listOf("element_id" to param("integer", "Element to swipe on; default the screen center"))
        ),
        function(
            "find_contact",
            "Looks up the user's contacts by name and returns matching names with phone numbers, " +
                "for calling (launch_intent action android.intent.action.DIAL, uri tel:<number>) or messaging.",
            required = listOf("name" to param("string", "Contact name or part of it, e.g. \"Nikhil\""))
        ),
        function(
            "navigate",
            "System navigation: back, home, recent apps, notification shade, quick settings, lock screen, screenshot, power menu. Returns the new screen.",
            required = listOf(
                "action" to param(
                    "string", "The navigation action",
                    enum = listOf("back", "home", "recents", "notifications", "quick_settings", "lock_screen", "screenshot", "power_dialog")
                )
            )
        ),
        function(
            "show_choices",
            "Shows the user a list to pick from by tapping (in the app and over other apps), e.g. when several contacts " +
                "match a name. Also read the options out briefly; the user answers by tapping or by saying one. " +
                "Their pick comes back as what they said.",
            required = listOf(
                "question" to param("string", "Short heading, e.g. \"Kaun sa Nikhil?\""),
                "options" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "2-8 options, e.g. \"Nikhil Rajput · +91 98765 43210\"")
            )
        ),
        function(
            "make_call",
            "Calls a phone number right away (not just dialing), on speaker unless told otherwise. For a name, " +
                "find_contact first. After it starts, say one short line and call end_conversation so you don't talk over the call.",
            required = listOf("number" to param("string", "Phone number, e.g. +919876543210")),
            optional = listOf("speaker" to param("boolean", "Put the call on speaker (default true)"))
        ),
        function(
            "media_control",
            "Controls whatever is playing (YouTube, Spotify, any player), even in the background or a floating player: " +
                "pause / play / next / previous / stop. Use it to stop or pause music and videos.",
            required = listOf("action" to param("string", "What to do", enum = listOf("pause", "play", "play_pause", "next", "previous", "stop")))
        ),
        function(
            "set_volume",
            "Sets the media volume (music, videos, and also your own voice). Give a level, or a change.",
            optional = listOf(
                "level" to param("integer", "0-100 percent"),
                "change" to param("string", "Relative change", enum = listOf("up", "down", "mute", "unmute"))
            )
        ),
        function(
            "wait",
            "Waits for the screen to settle (an app or page still loading), then returns the new screen.",
            optional = listOf("seconds" to param("integer", "1-8, default 2"))
        ),
        function(
            "open_app",
            "Opens an installed app by its name. If the name is ambiguous or not found, use list_apps.",
            required = listOf("app_name" to param("string", "App name, e.g. \"YouTube\""))
        ),
        function(
            "list_apps",
            "Lists installed apps (name and package), optionally filtered by a name fragment.",
            optional = listOf("query" to param("string", "Part of the app name or package"))
        ),
        function(
            "launch_intent",
            "Starts any Android intent: deep links, settings pages, dialer, maps, web pages, share, etc. " +
                "Examples: action android.settings.WIFI_SETTINGS; uri tel:9876543210 (action android.intent.action.DIAL); " +
                "uri geo:0,0?q=pizza; uri https://wa.me/919876543210?text=hi; uri https://www.youtube.com/results?search_query=songs. " +
                "If you don't know the right action or URI scheme, find it with Google Search first. Returns the new screen.",
            optional = listOf(
                "action" to param("string", "Intent action; default android.intent.action.VIEW"),
                "uri" to param("string", "Data URI"),
                "package" to param("string", "Limit to this app package"),
                "extras" to param("string", "JSON object of string/number/boolean extras, e.g. {\"query\":\"pizza\"}")
            )
        ),
        function(
            "end_conversation",
            "Ends the conversation when the user is done (bye, bas, thank you, that's all). Say a short goodbye first; " +
                "the assistant then sleeps until the user says \"Hi Nik\" again."
        ),
        function(
            "toggle_flashlight",
            "Turns the phone's flashlight (torch) on or off.",
            required = listOf("enabled" to param("boolean", "true = on, false = off"))
        ),
        function(
            "toggle_bluetooth",
            "Turns Bluetooth on or off. On Android 13+ this opens Bluetooth settings; then read_screen and tap the switch.",
            required = listOf("enabled" to param("boolean", "true = on, false = off"))
        )
    )

    private fun param(type: String, description: String, enum: List<String>? = null): Map<String, Any> =
        buildMap {
            put("type", type)
            put("description", description)
            enum?.let { put("enum", it) }
        }

    private fun function(
        name: String,
        description: String,
        required: List<Pair<String, Map<String, Any>>> = emptyList(),
        optional: List<Pair<String, Map<String, Any>>> = emptyList()
    ): Map<String, Any> = buildMap {
        put("name", name)
        put("description", description)
        if (required.isNotEmpty() || optional.isNotEmpty()) {
            put(
                "parameters", mapOf(
                    "type" to "object",
                    "properties" to (required + optional).toMap(),
                    "required" to required.map { it.first }
                )
            )
        }
    }

    /**
     * Runs one tool call and returns its result (sent back to the model). Screen actions block
     * for a moment while the UI settles, so call this off the WebSocket thread.
     */
    fun execute(toolName: String, arguments: Map<String, Any?>?): Map<String, Any?> {
        Log.i(TAG, "Executing tool: $toolName with arguments: $arguments")
        val args = arguments.orEmpty()
        fun str(key: String) = args[key]?.toString()?.takeIf { it.isNotBlank() }
        fun int(key: String) = args[key]?.toString()?.toDoubleOrNull()?.toInt()
        fun bool(key: String) = args[key]?.toString()?.toBooleanStrictOrNull()
        val agent = PhoneAgentService.instance

        return try {
            when (toolName) {
                "read_screen" -> agent?.readScreen() ?: mapOf("error" to AGENT_OFF)

                "look_at_screen" -> if (agent == null) mapOf("error" to AGENT_OFF) else lookAtScreen(agent, str("question"))

                "media_control" -> mediaControl(str("action") ?: "play_pause")

                "make_call" -> makeCall(str("number"), bool("speaker") != false)

                "show_choices" -> {
                    // Arrays arrive as JSON text (see LiveAudioWebSocketClient.executeToolCall)
                    val options = runCatching { JsonParser.parseString(str("options") ?: "[]").asJsonArray.map { it.asString } }
                        .getOrDefault(emptyList()).filter { it.isNotBlank() }.take(8)
                    if (options.isEmpty()) mapOf("error" to "Give at least one option")
                    else {
                        onShowChoices(str("question") ?: "Choose one", options)
                        mapOf("shown" to true, "next" to "Read the options out briefly and wait for the user's pick")
                    }
                }

                "set_volume" -> setVolume(int("level"), str("change"))

                "wait" -> {
                    Thread.sleep((int("seconds") ?: 2).coerceIn(1, 8) * 1000L)
                    agent?.readScreen() ?: mapOf("done" to "waited")
                }

                "tap" -> when {
                    agent == null -> mapOf("error" to AGENT_OFF)
                    // Confirmation is enforced here, not left to the model: it once asked and sent without waiting
                    agent.targetLabel(int("element_id"), str("text"))?.let { NEEDS_YES.containsMatchIn(it) } == true && !takeConfirmation() ->
                        mapOf("error" to ASK_FIRST)
                    else -> agent.tap(int("element_id"), str("text"), bool("long_press") == true)
                }

                "type_text" -> agent?.typeText(str("text") ?: "", int("element_id"), bool("submit") == true) ?: mapOf("error" to AGENT_OFF)

                "scroll" -> agent?.scroll(str("direction") ?: "down", int("element_id")) ?: mapOf("error" to AGENT_OFF)

                "swipe" -> agent?.swipe(str("direction") ?: "up", int("element_id")) ?: mapOf("error" to AGENT_OFF)

                "navigate" -> agent?.navigate(str("action") ?: "") ?: mapOf("error" to AGENT_OFF)

                "find_contact" -> findContact(str("name") ?: "")

                "open_app" -> {
                    val name = str("app_name") ?: str("name") ?: ""
                    val pkg = findApp(name)
                    if (pkg != null && launchPackage(pkg)) withScreen(mapOf("success" to true, "opened" to pkg))
                    else mapOf("success" to false, "error" to "No installed app matches '$name'; call list_apps to see the names")
                }

                "list_apps" -> mapOf("apps" to listApps(str("query")))

                "end_conversation" -> {
                    onEndConversation()
                    mapOf("success" to true, "done" to "conversation will end after your goodbye")
                }

                "launch_intent" -> launchIntent(str("action"), str("uri"), str("package"), str("extras"))

                "toggle_flashlight" -> {
                    val enabled = bool("enabled") ?: str("state")?.equals("on", ignoreCase = true) ?: true
                    mapOf("success" to toggleFlashlight(enabled), "flashlight_state" to if (enabled) "on" else "off")
                }

                "toggle_bluetooth" -> {
                    val enabled = bool("enabled") ?: str("state")?.equals("on", ignoreCase = true) ?: true
                    mapOf("success" to true, "message" to toggleBluetooth(enabled))
                }

                else -> mapOf("error" to "Unsupported tool '$toolName'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool '$toolName': ${e.message}", e)
            mapOf("error" to (e.message ?: "Execution failed"))
        }
    }

    /** Adds the screen an app or intent launch led to, when screen control is on. */
    private fun withScreen(result: Map<String, Any?>): Map<String, Any?> {
        val agent = PhoneAgentService.instance ?: return result
        return result + ("screen" to agent.screenAfterLaunch())
    }

    // ---- Apps ----

    private data class App(val label: String, val pkg: String)

    private fun launchableApps(): List<App> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(launcher, 0)
        }
        return activities.map { App(it.loadLabel(packageManager).toString().trim(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun listApps(query: String?): List<String> {
        val q = query?.trim()?.lowercase()
        return launchableApps()
            .filter { q.isNullOrEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            .take(MAX_APPS)
            .map { "${it.label} (${it.pkg})" }
    }

    /** Package of the best match: exact label, then label containing the name (either way), then package name. */
    private fun findApp(rawName: String): String? {
        val name = rawName.trim().lowercase()
        if (name.isEmpty()) return null
        val apps = launchableApps()
        return (apps.firstOrNull { it.label.lowercase() == name || it.pkg.lowercase() == name }
            ?: apps.firstOrNull { it.label.lowercase().contains(name) }
            ?: apps.firstOrNull { name.contains(it.label.lowercase()) && it.label.length > 2 }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(name.replace(" ", "")) })?.pkg
    }

    private fun launchPackage(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "Launched package: $packageName")
        return true
    }

    private fun launchIntent(action: String?, uri: String?, pkg: String?, extrasJson: String?): Map<String, Any?> {
        if (action == null && uri == null && pkg == null) return mapOf("error" to "Give at least an action, uri or package")
        val intent = Intent(action ?: Intent.ACTION_VIEW).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        uri?.let { intent.data = Uri.parse(it) }
        pkg?.let { intent.setPackage(it) }
        extrasJson?.let { json ->
            val extras = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
                ?: return mapOf("error" to "extras must be a JSON object")
            for ((key, value) in extras.entrySet()) {
                val p = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: continue
                when {
                    p.isBoolean -> intent.putExtra(key, p.asBoolean)
                    // Most extras are ints (e.g. android.intent.extra.alarm.HOUR); a Long would be ignored
                    p.isNumber -> p.asNumber.toString().let { n ->
                        n.toIntOrNull()?.let { intent.putExtra(key, it) }
                            ?: n.toLongOrNull()?.let { intent.putExtra(key, it) }
                            ?: intent.putExtra(key, n.toDouble())
                    }
                    else -> intent.putExtra(key, p.asString)
                }
            }
        }
        // A web link an installed app handles (wa.me, youtube.com, maps…) opens in that app, not the browser
        val app = if (pkg == null && intent.data?.scheme in setOf("http", "https")) appForWebLink(intent) else null
        app?.let { intent.setPackage(it) }
        if (intent.resolveActivity(packageManager) == null) {
            return mapOf("success" to false, "error" to "No app on this phone handles that intent; try another action/URI or open the app and use the screen")
        }
        context.startActivity(intent)
        return withScreen(buildMap {
            put("success", true)
            put("launched", listOfNotNull(action, uri, pkg).joinToString(" "))
            app?.let { put("opened_in", it) }
        })
    }

    /** The first non-browser app that handles this web link, or null (then the default browser opens it). */
    private fun appForWebLink(intent: Intent): String? {
        val browsers = handlers(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))).toSet()
        return handlers(intent).firstOrNull { it !in browsers }
    }

    private fun handlers(intent: Intent): List<String> {
        @Suppress("DEPRECATION")
        val found = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return found.map { it.activityInfo.packageName }.distinct()
    }

    // ---- Seeing the screen ----

    private fun lookAtScreen(agent: PhoneAgentService, question: String?): Map<String, Any?> {
        val screen = agent.readScreen()
        val jpeg = agent.screenshotJpeg()
            ?: return mapOf("error" to "Couldn't take a screenshot (the app may block them); use the elements list", "screen" to screen)
        val describe = describeImage ?: return mapOf("error" to "Screen description isn't available", "screen" to screen)
        @Suppress("UNCHECKED_CAST")
        val list = (screen["elements"] as? List<String>).orEmpty().joinToString("\n").take(6000)
        val prompt = "This is a screenshot of an Android phone. " +
            (question?.let { "Question about it: $it\n" } ?: "") +
            "Describe what is on the screen for someone who can't see it: the app, what is shown or playing " +
            "(video playing or paused, a floating mini player), dialogs, and the important buttons and where they are. " +
            "At most 6 short sentences, no markdown. When you mention something that is in this list of on-screen " +
            "elements, give its [number]:\n$list"
        val seen = describe(jpeg, prompt)
            ?: return mapOf("error" to "Couldn't get a description of the screen right now", "screen" to screen)
        return mapOf("description" to seen, "screen" to screen)
    }

    // ---- Calls ----

    private fun makeCall(number: String?, speaker: Boolean): Map<String, Any?> {
        if (number.isNullOrBlank()) return mapOf("error" to "Give a phone number (use find_contact for a name)")
        val tel = Uri.fromParts("tel", number.replace(Regex("[\\s()-]"), ""), null)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            // Without the permission the dialer can only be opened with the number filled in
            context.startActivity(Intent(Intent.ACTION_DIAL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return withScreen(mapOf(
                "success" to false,
                "error" to "Phone call permission is off, so only the dialer opened; tap its Call button, and tell the user to allow Phone calls for Nik's voice"
            ))
        }
        context.startActivity(Intent(Intent.ACTION_CALL, tel).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var onSpeaker = false
        if (speaker) {
            Thread.sleep(2500) // let the call screen come up
            // The in-call screen's own Speaker button is what the dialer respects; the audio route is the fallback
            onSpeaker = PhoneAgentService.instance?.switchOn(listOf("Speaker", "Speakerphone", "स्पीकर")) == true
            if (!onSpeaker) {
                val audio = context.getSystemService(AudioManager::class.java)
                @Suppress("DEPRECATION")
                runCatching { audio?.isSpeakerphoneOn = true; onSpeaker = audio?.isSpeakerphoneOn == true }
            }
        }
        return withScreen(mapOf(
            "success" to true,
            "calling" to number,
            "speaker" to onSpeaker,
            "next" to "The call is on (a SIM choice may be showing: pick one if so). Say one short line, then call end_conversation."
        ))
    }

    // ---- Media ----

    private fun mediaControl(action: String): Map<String, Any?> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return mapOf("error" to "No audio service")
        val key = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        // Goes to the app with the active media session, like a headset button
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
        Thread.sleep(400)
        return mapOf("success" to true, "done" to action, "something_playing" to audio.isMusicActive)
    }

    private fun setVolume(level: Int?, change: String?): Map<String, Any?> {
        val audio = context.getSystemService(AudioManager::class.java) ?: return mapOf("error" to "No audio service")
        val stream = AudioManager.STREAM_MUSIC
        val max = audio.getStreamMaxVolume(stream)
        when {
            level != null -> audio.setStreamVolume(stream, (level.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            change == "up" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            change == "down" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            change == "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
            change == "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
            else -> return mapOf("error" to "Give a level (0-100) or a change (up, down, mute, unmute)")
        }
        return mapOf("success" to true, "volume_percent" to audio.getStreamVolume(stream) * 100 / max)
    }

    // ---- Contacts ----

    private fun findContact(name: String): Map<String, Any?> {
        if (name.isBlank()) return mapOf("error" to "Give a name to look up")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return mapOf("error" to "Contacts permission is off; ask the user to allow Contacts for Nik's voice, or search the name inside the app on screen")
        }
        val phone = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val matches = linkedSetOf<String>()
        context.contentResolver.query(
            phone, columns, "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%${name.trim()}%"),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
        )?.use { c ->
            while (c.moveToNext() && matches.size < 10) {
                val number = c.getString(1)?.replace(Regex("[\\s()-]"), "") ?: continue
                // wa.me links need the international number as digits only
                matches += "${c.getString(0)}: $number" + if (number.startsWith("+")) " (digits for wa.me: ${number.drop(1)})" else ""
            }
        }
        return if (matches.isEmpty()) mapOf("contacts" to emptyList<String>(), "note" to "No contact matches '$name'")
        else mapOf("contacts" to matches.toList())
    }

    // ---- Hardware ----

    /**
     * Toggles camera torch mode on/off.
     */
    fun toggleFlashlight(enabled: Boolean): Boolean {
        val cm = cameraManager ?: run {
            Log.e(TAG, "CameraManager not available")
            return false
        }

        try {
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    cm.setTorchMode(id, enabled)
                    Log.i(TAG, "Flashlight set to $enabled on camera $id")
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight: ${e.message}", e)
        }
        return false
    }

    /**
     * Toggles Bluetooth or opens Bluetooth settings.
     */
    @Suppress("DEPRECATION")
    fun toggleBluetooth(enabled: Boolean): String {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "Bluetooth hardware not supported"

        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                if (enabled) adapter.enable() else adapter.disable()
                "Bluetooth ${if (enabled) "enabled" else "disabled"}"
            } else {
                val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "Opened Bluetooth settings (Android 13+ apps can't switch it directly); read_screen and tap the Bluetooth switch"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth toggle error: ${e.message}", e)
            "Bluetooth action error: ${e.message}"
        }
    }
}
