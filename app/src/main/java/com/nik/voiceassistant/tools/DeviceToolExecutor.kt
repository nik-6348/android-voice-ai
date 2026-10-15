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
    private val takeConfirmation: () -> Boolean = { false },
    // research: a question -> an answer found on the web, or null on failure (blocking)
    private val research: ((String) -> String?)? = null,
    // update_plan: the model's current plan, shown as live progress
    private val onPlan: (TaskPlan?) -> Unit = {}
) {

    companion object {
        private const val TAG = "DeviceToolExecutor"
        private const val MAX_APPS = 60

        // Only high-stakes actions: irreversible financial payments, money transfers, or destructive wipes
        private val NEEDS_YES = Regex(
            """(?i)\b(pay now|confirm payment|complete purchase|transfer funds|delete account|erase all|factory reset)\b|भुगतान करें|पैसे भेजें"""
        )
        private const val ASK_FIRST = "Not pressed: this action performs an irreversible payment or wipe, and the user hasn't confirmed yet. Say what will happen, call show_choices with \"Haan, confirm karo\", \"Nahi\", and wait for their answer before tapping it."

        private const val SELF_BLOCKED = "Blocked: that would stop, remove or switch off you (this assistant app) and end the task. " +
            "Skip yourself: act on the other apps, or tell the user it's the assistant itself."
        // Buttons that stop, remove or switch off an app (label or view id); applied only when the target is this app
        private val DESTRUCTIVE = Regex("""(?i)force.?stop|uninstall|clear.?(data|storage|cache)|disable|deactivate|turn.?off|remove|delete|बंद|हटा|अनइंस्टॉल""")

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
            "unlock_phone",
            "Unlocks the phone using the PIN saved in Settings. Only works if Auto-unlock is turned ON in Settings. " +
                "If Auto-unlock is OFF or no PIN is saved, do NOT call this tool; instead, inform the user: 'Phone locked hai, kripya pehle phone unlock karein'."
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
            "Types text into a text field (replacing its content): the given element, else the focused field. If several " +
                "fields are on screen and none is focused, it lists them so you can pick. The result says which field was used. Returns the new screen.",
            required = listOf("text" to param("string", "Text to type")),
            optional = listOf(
                "element_id" to param("integer", "The text field's element number"),
                "submit" to param("boolean", "Press the keyboard's enter/search/send key after typing. Leave it out to press it automatically in search fields; false never presses it")
            )
        ),
        function(
            "scroll",
            "Scrolls a list to see more and returns the new screen. The result's \"moved\" says whether the content really " +
                "moved; moved=false means that list is at its end that way (don't repeat it). To find something in a long " +
                "list, pass until_text: it keeps scrolling until that text is on screen and returns its element_id.",
            required = listOf("direction" to param("string", "Where to move the view", enum = listOf("down", "up", "left", "right"))),
            optional = listOf(
                "element_id" to param("integer", "The scrollable element's number (or any element inside it), if not the main list"),
                "until_text" to param("string", "Keep scrolling until an element showing this text is visible"),
                "max_scrolls" to param("integer", "Most scrolls with until_text, default 10")
            )
        ),
        function(
            "update_plan",
            "Your working plan for a multi-step goal, shown to the user as live progress. Call it when a goal starts " +
                "(goal + steps), as steps complete (current_step), and whenever you change approach (status blocked + note " +
                "on what failed). The result lists the approaches still untried when you are blocked.",
            required = listOf("goal" to param("string", "The user's goal in one line")),
            optional = listOf(
                "steps" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Ordered short steps to reach the goal"),
                "current_step" to param("integer", "1-based number of the step being done now"),
                "status" to param("string", "Where the goal stands", enum = listOf("working", "blocked", "done", "failed")),
                "note" to param("string", "Your current thinking: what you see, what you decided and why (shown to the user)")
            )
        ),
        function(
            "research",
            "Looks up on the web (Google) how to do something on THIS phone (its make, model and Android version are added " +
                "automatically) or any fact you aren't sure of: exact menu paths, setting names, settings intent actions, " +
                "deep links, app steps. Use it before guessing, and whenever an approach failed. ~3-6 s.",
            required = listOf("question" to param("string", "What to find out, e.g. \"how to enable developer options\""))
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
            "Places a phone call. If the user explicitly requested a call (e.g. 'Rahul ko call lagao' or 'Call 9876543210'), place the call directly and narrate 'Calling...'. If there is contact ambiguity, confirm the contact first.",
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
            "Opens an installed app by its name (use the full name, e.g. \"Google Keep\"). If several apps match it lists them; " +
                "if none does, use list_apps.",
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
            "Ends the voice session and puts the assistant to sleep. Call it ONLY when the user clearly says they are done " +
                "('bas', 'bye', 'band karo', 'that's all'). Never call it just because a task finished: after a task, ask if " +
                "there is anything else and keep listening. A 'thank you' or 'ok' is not a goodbye. Say a short goodbye first."
        ),
        function(
            "open_website",
            "Opens a website or a web search in the browser (not in an app). Give a URL (\"amazon.in\", " +
                "\"https://…\") or words to search for. Returns the new screen.",
            required = listOf("url_or_search" to param("string", "A web address, or what to search for"))
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
        // Never log a PIN (unlock_phone's one-time pin argument)
        Log.i(TAG, "Executing tool: $toolName with arguments: ${arguments?.mapValues { (k, v) -> if (k == "pin") "***" else v }}")
        val result = run(toolName, arguments.orEmpty())
        return withProgressCheck(toolName, arguments.orEmpty(), result)
    }

    private fun run(toolName: String, args: Map<String, Any?>): Map<String, Any?> {
        fun str(key: String) = args[key]?.toString()?.takeIf { it.isNotBlank() }
        fun int(key: String) = args[key]?.toString()?.toDoubleOrNull()?.toInt()
        fun bool(key: String) = args[key]?.toString()?.toBooleanStrictOrNull()
        val agent = PhoneAgentService.instance

        return try {
            when (toolName) {
                "read_screen" -> agent?.readScreen() ?: mapOf("error" to AGENT_OFF)

                "unlock_phone" -> {
                    if (!com.nik.voiceassistant.SecurePrefs.isAutoUnlockEnabled(context)) {
                        mapOf("success" to false, "error" to "Auto-unlock is OFF in Settings for security. Tell the user: 'Phone locked hai, kripya pehle phone unlock karein.'")
                    } else if (!com.nik.voiceassistant.SecurePrefs.hasPin(context)) {
                        mapOf("success" to false, "error" to "No PIN is saved in Settings. Tell the user: 'Phone locked hai aur PIN saved nahi hai. Kripya pehle phone unlock karein.'")
                    } else if (agent == null) {
                        mapOf("error" to AGENT_OFF)
                    } else {
                        val err = agent.unlockWithSavedPin()
                        if (err == null) withScreen(mapOf("success" to true, "done" to "phone unlocked"))
                        else mapOf("success" to false, "error" to err)
                    }
                }

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
                    harmsSelf(agent, int("element_id"), str("text")) -> mapOf("error" to SELF_BLOCKED)
                    // Confirmation is enforced here, not left to the model: it once asked and sent without waiting
                    agent.targetLabel(int("element_id"), str("text"))?.let { NEEDS_YES.containsMatchIn(it) } == true && !takeConfirmation() ->
                        mapOf("error" to ASK_FIRST)
                    else -> agent.tap(int("element_id"), str("text"), bool("long_press") == true)
                }

                "type_text" -> agent?.typeText(str("text") ?: "", int("element_id"), bool("submit")) ?: mapOf("error" to AGENT_OFF)

                "scroll" -> agent?.scroll(str("direction") ?: "down", int("element_id"), str("until_text"), int("max_scrolls") ?: 10)
                    ?: mapOf("error" to AGENT_OFF)

                "update_plan" -> updatePlan(
                    goal = str("goal") ?: plan?.goal ?: "",
                    steps = str("steps")?.let { s -> runCatching { JsonParser.parseString(s).asJsonArray.map { it.asString } }.getOrNull() },
                    currentStep = int("current_step"),
                    status = str("status"),
                    note = str("note")
                )

                "research" -> research(str("question") ?: "")

                "swipe" -> agent?.swipe(str("direction") ?: "up", int("element_id")) ?: mapOf("error" to AGENT_OFF)

                "navigate" -> agent?.navigate(str("action") ?: "") ?: mapOf("error" to AGENT_OFF)

                "find_contact" -> findContact(str("name") ?: "")

                "open_app" -> {
                    val name = str("app_name") ?: str("name") ?: ""
                    when (val match = findApp(name)) {
                        is AppMatch.One -> if (launchPackage(match.app.pkg)) withScreen(mapOf("success" to true, "opened" to "${match.app.label} (${match.app.pkg})"))
                            else mapOf("success" to false, "error" to "${match.app.label} can't be opened from the launcher")
                        is AppMatch.Several -> mapOf(
                            "success" to false,
                            "error" to "Several apps match '$name'; call open_app again with the exact name",
                            "matches" to match.apps.map { "${it.label} (${it.pkg})" }
                        )
                        AppMatch.None -> mapOf("success" to false, "error" to "No installed app matches '$name'; call list_apps to see the names")
                    }
                }

                "list_apps" -> mapOf("apps" to listApps(str("query")))

                "end_conversation" -> {
                    onEndConversation()
                    mapOf("success" to true, "done" to "conversation will end after your goodbye")
                }

                "launch_intent" -> launchIntent(str("action"), str("uri"), str("package"), str("extras"))

                "open_website" -> openWebsite(str("url_or_search") ?: "")

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

    // ---- Planning, research and progress ----

    // The model's plan for the goal in progress (update_plan); null when there is none
    @Volatile var plan: TaskPlan? = null
        private set

    // Tools that change the screen: the same screen after several of these in a row means no progress
    private val acting = setOf("tap", "type_text", "scroll", "swipe", "navigate", "launch_intent", "open_app", "open_website", "wait")
    private var lastScreenHash = 0
    private var sameScreenStreak = 0
    private val recentCalls = ArrayDeque<String>()

    // Ways to get unstuck, keyed by the tool each one uses; the untried ones are suggested when blocked
    private val approaches = linkedMapOf(
        "look_at_screen" to "look_at_screen to see what is really there (a dialog, permission prompt, popup or a different layout)",
        "research" to "research the exact path / setting name / intent for this phone model",
        "type_text" to "the app's own search: tap its search icon or field, type the item, tap the result",
        "launch_intent" to "launch_intent straight to the page (a settings action or deep link, found with research if unsure)",
        "scroll" to "scroll with until_text to find the item in a long list (or scroll the other direction)",
        "navigate" to "navigate back or home and take another route (another menu, app, or open_website for the web version)",
        "wait" to "wait for the page or app to finish loading"
    )

    /** One line per fact about this phone, for the instructions and for research. Read live, never assumed. */
    fun deviceProfile(): String {
        val metrics = context.resources.displayMetrics
        val launcher = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName
        return buildString {
            append("${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND}, device ${Build.DEVICE}), ")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}, ")
            append("screen ${metrics.widthPixels}x${metrics.heightPixels}, ")
            append("language ${java.util.Locale.getDefault().toLanguageTag()}, ")
            launcher?.let { append("home app $it, ") }
            defaultBrowser()?.let { append("browser $it, ") }
            append("time ${java.text.SimpleDateFormat("EEE d MMM yyyy HH:mm", java.util.Locale.ENGLISH).format(java.util.Date())}")
        }
    }

    /** Starts the plan for a new goal, or updates the current one. */
    private fun updatePlan(goal: String, steps: List<String>?, currentStep: Int?, status: String?, note: String?): Map<String, Any?> {
        if (goal.isBlank()) return mapOf("error" to "Give the goal")
        val old = plan
        val sameGoal = old != null && old.goal.equals(goal, ignoreCase = true) && old.status !in setOf("done", "failed")
        val next = TaskPlan(
            goal = goal,
            steps = steps?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: old?.steps.takeIf { sameGoal }.orEmpty(),
            currentStep = currentStep ?: old?.currentStep.takeIf { sameGoal } ?: 1,
            status = status ?: "working",
            note = note,
            tried = if (sameGoal) old!!.tried else java.util.Collections.synchronizedSet(mutableSetOf()),
            blockedCount = (if (sameGoal) old!!.blockedCount else 0) + if (status == "blocked") 1 else 0
        )
        plan = next
        if (!sameGoal) { sameScreenStreak = 0; recentCalls.clear() }
        onPlan(next)
        return when (next.status) {
            "blocked" -> mapOf(
                "saved" to true,
                "untried_approaches" to approaches.filterKeys { it !in next.tried }.values.toList().ifEmpty { approaches.values.toList() },
                "next" to "Pick the most promising untried approach and keep going; the goal isn't lost. " +
                    "Only after several different approaches failed, tell the user exactly what is stopping you."
            )
            "done", "failed" -> mapOf("saved" to true, "next" to "Check the screen shows the result, then tell the user the outcome in one short sentence.")
            else -> mapOf("saved" to true, "next" to "Carry on with step ${next.currentStep}; say in one short line what you're doing.")
        }
    }

    private fun research(question: String): Map<String, Any?> {
        if (question.isBlank()) return mapOf("error" to "Give a question")
        val ask = research ?: return mapOf("error" to "Research isn't available; rely on Google Search or the phone's own search")
        val app = PhoneAgentService.instance?.readScreen()?.get("app")
        val prompt = """
            Answer for an assistant that operates this Android phone through its screen and Android intents.
            Phone: ${deviceProfile()}${app?.let { "\nApp open now: $it" } ?: ""}
            Question: $question
            Search the web for the current, correct answer for this exact phone make, model and Android version
            (menus differ between makers and versions). Reply in plain text, no markdown, at most 12 short lines:
            1) the exact tap path with on-screen names as they appear on this phone (in its language),
            2) any Android intent action, settings action or deep link that opens the place directly,
            3) one alternative route if the first doesn't match.
        """.trimIndent()
        val answer = ask(prompt) ?: return mapOf("error" to "Research failed right now; try Google Search, the app's own search, or explore the menus")
        plan?.tried?.add("research")
        return mapOf("answer" to answer, "next" to "Follow it on the screen; names may differ slightly, so match what read_screen shows.")
    }

    /**
     * Adds a warning when actions stop making progress: the same screen after several actions in a
     * row, or the same call over and over. It tells the model to change approach instead of looping.
     */
    private fun withProgressCheck(toolName: String, args: Map<String, Any?>, result: Map<String, Any?>): Map<String, Any?> {
        plan?.tried?.add(toolName)
        @Suppress("UNCHECKED_CAST")
        val screen = (result["screen"] as? Map<String, Any?>) ?: result.takeIf { toolName == "read_screen" }
        val hash = (screen?.get("elements") as? List<*>)?.hashCode()
        if (hash != null) {
            sameScreenStreak = if (toolName in acting && hash == lastScreenHash) sameScreenStreak + 1 else if (toolName in acting) 0 else sameScreenStreak
            lastScreenHash = hash
        }
        val key = toolName + args.toSortedMap().toString()
        recentCalls.addLast(key)
        while (recentCalls.size > 8) recentCalls.removeFirst()
        val repeats = recentCalls.count { it == key }

        if (toolName !in acting || (sameScreenStreak < 2 && repeats < 3)) return result
        val untried = approaches.filterKeys { it !in (plan?.tried ?: emptySet<String>()) }.values.toList().ifEmpty { approaches.values.toList() }
        val why = if (repeats >= 3) "You have made this same call $repeats times" else "The screen hasn't changed after the last ${sameScreenStreak + 1} actions"
        return result + mapOf(
            "progress_warning" to "$why: this route isn't working. Stop repeating it and switch approach (call update_plan with status blocked and what you'll try).",
            "untried_approaches" to untried
        )
    }

    /** Clears the plan and progress tracking (a new conversation). */
    fun resetPlan() {
        plan = null
        sameScreenStreak = 0
        lastScreenHash = 0
        recentCalls.clear()
        onPlan(null)
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

    private sealed class AppMatch {
        data class One(val app: App) : AppMatch()
        data class Several(val apps: List<App>) : AppMatch()
        object None : AppMatch()
    }

    private fun words(s: String): List<String> = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * The app the name means. Exact label (or package, or label without spaces) wins. Otherwise each
     * word of the name is weighed by how rare it is among the installed apps' names (worked out from
     * this phone's apps, nothing fixed): a word in many names ("google", "app") counts for little and
     * can't match on its own, so "Google Keep" opens Keep, not Google. Every distinctive word must
     * match. Close scores come back as a list to choose from.
     */
    private fun findApp(rawName: String): AppMatch {
        val name = rawName.trim().lowercase()
        if (name.isEmpty()) return AppMatch.None
        val apps = launchableApps()
        apps.firstOrNull { it.label.lowercase() == name || it.pkg.lowercase() == name || it.label.lowercase().replace(" ", "") == name.replace(" ", "") }
            ?.let { return AppMatch.One(it) }

        val labelWords = apps.associateWith { words(it.label).toSet() }
        val df = mutableMapOf<String, Int>()
        labelWords.values.forEach { ws -> ws.forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = apps.size.coerceAtLeast(1)
        fun weight(w: String) = kotlin.math.ln((n + 1.0) / (1.0 + (df[w] ?: 0)))
        // A word in several app names is generic here
        val common = (n / 30).coerceAtLeast(2)
        val query = words(name)
        val distinctive = query.filter { (df[it] ?: 0) < common }

        val scored = apps.mapNotNull { app ->
            val ws = labelWords[app] ?: return@mapNotNull null
            fun hit(q: String) = q in ws || (q.length >= 3 && ws.any { it.startsWith(q) })
            if (distinctive.isNotEmpty() && !distinctive.all(::hit)) return@mapNotNull null
            if (distinctive.isEmpty() && !query.all(::hit)) return@mapNotNull null
            val score = query.sumOf { q -> if (q in ws) weight(q) else if (hit(q)) 0.6 * weight(q) else 0.0 } -
                0.05 * (ws.size - query.size).coerceAtLeast(0) // fewer extra words = closer
            app to score
        }.sortedByDescending { it.second }

        if (scored.isEmpty()) {
            // Last resort: the package name contains it ("whatsapp" -> com.whatsapp)
            return apps.firstOrNull { it.pkg.lowercase().contains(name.replace(" ", "")) }?.let { AppMatch.One(it) } ?: AppMatch.None
        }
        val best = scored.first()
        val runnerUp = scored.getOrNull(1)
        return if (runnerUp == null || best.second > runnerUp.second * 1.25 + 0.01) AppMatch.One(best.first)
        else AppMatch.Several(scored.take(5).map { it.first })
    }

    /** This app as the model should know it (it must never act against itself). */
    fun selfDescription(): String = "\"$selfLabel\", package ${context.packageName}"

    private val selfLabel: String by lazy { packageManager.getApplicationLabel(context.applicationInfo).toString() }

    // Every name this app shows in Settings: the app label and the screen-control service's label
    private val selfNames: List<String> by lazy {
        listOfNotNull(
            selfLabel,
            runCatching {
                packageManager.getServiceInfo(android.content.ComponentName(context, PhoneAgentService::class.java), 0).loadLabel(packageManager).toString()
            }.getOrNull()
        ).filter { it.isNotBlank() }.distinct()
    }

    /**
     * Whether a tap would stop, remove or switch off this app: a destructive button (force stop,
     * uninstall, clear data, disable…) on a page about this app, or this app's own switch (screen
     * control) when it is on. Taps on other apps' pages are never affected.
     */
    private fun harmsSelf(agent: PhoneAgentService, elementId: Int?, text: String?): Boolean {
        val target = agent.target(elementId, text) ?: return false
        val mentionsSelf = { s: String -> selfNames.any { s.contains(it, ignoreCase = true) } }
        val aboutSelf = mentionsSelf(target.rowLabel) || (target.app != context.packageName && agent.screenShowsTitle(selfNames))
        val destructive = DESTRUCTIVE.containsMatchIn(target.label) || DESTRUCTIVE.containsMatchIn(target.viewId.orEmpty())
        val switchingOff = target.checkable && target.checked && mentionsSelf(target.rowLabel)
        return (destructive && aboutSelf) || switchingOff
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
        // Uninstalling / deleting this app itself
        val removing = action != null && (action.contains("DELETE") || action.contains("UNINSTALL"))
        if (removing && (uri?.contains(context.packageName) == true || pkg == context.packageName)) return mapOf("error" to SELF_BLOCKED)
        // Media and files must really exist: a made-up content:// or file:// URI only fails later in the other app
        val uris = listOfNotNull(uri) + Regex("(content|file)://[^\"\\s,}]+").findAll(extrasJson.orEmpty()).map { it.value }
        uris.firstOrNull { !uriExists(Uri.parse(it)) }?.let { missing ->
            return mapOf(
                "success" to false,
                "error" to "$missing doesn't exist on this phone. Never make up content:// or file:// URIs: share or attach " +
                    "through the app's own UI (its share button, Attach > Gallery, or the screenshot preview's share)."
            )
        }
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

    /** Whether a content:// or file:// URI points at something that exists (other schemes: true). */
    private fun uriExists(uri: Uri): Boolean = when (uri.scheme?.lowercase()) {
        "content" -> runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
        "file" -> uri.path?.let { java.io.File(it).exists() } == true
        else -> true
    }

    /** A URL (or a Google search for the words) in the user's browser, never handed to another app. */
    private fun openWebsite(target: String): Map<String, Any?> {
        val t = target.trim()
        if (t.isEmpty()) return mapOf("error" to "Give a web address or something to search for")
        val looksLikeUrl = !t.contains(' ') && (t.startsWith("http://") || t.startsWith("https://") || Regex("""^[\w-]+(\.[\w-]+)+(/.*)?$""").matches(t))
        val url = when {
            !looksLikeUrl -> "https://www.google.com/search?q=" + Uri.encode(t)
            t.startsWith("http") -> t
            else -> "https://$t"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        defaultBrowser()?.let { intent.setPackage(it) }
        if (intent.resolveActivity(packageManager) == null) return mapOf("success" to false, "error" to "No browser found")
        context.startActivity(intent)
        return withScreen(mapOf("success" to true, "opened" to url, "browser" to intent.`package`))
    }

    /** The user's default browser, or any installed one if no default is set. */
    private fun defaultBrowser(): String? {
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val chosen = packageManager.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        // "android" means no default yet (the system would ask); pick the first browser instead
        return chosen?.takeIf { it != "android" } ?: handlers(probe).firstOrNull()
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
        val jpeg = agent.appWindowJpeg()
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
