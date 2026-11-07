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
import com.nik.voiceassistant.SecurePrefs
import com.nik.voiceassistant.data.trace.AgentTrace
import com.nik.voiceassistant.security.ActionGuard
import com.nik.voiceassistant.security.AgentPolicy
import com.nik.voiceassistant.service.PaymentGuard
import com.nik.voiceassistant.service.PhoneAgentService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    // screenVerifier: two-tier verify (tier-1 elements + tier-2 vision gateway)
    val screenVerifier: ScreenVerifier? = null,
    // show_choices: (question, options) shown as a tappable list in the app and the overlay
    private val onShowChoices: (String, List<String>) -> Unit = { _, _ -> },
    // onShowAutomationReview: shows the 3 Pillars review card before saving an automation
    private val onShowAutomationReview: (com.nik.voiceassistant.data.macro.AutomationReview) -> Unit = {},
    // Whether the user said yes (spoken, typed or tapped) after this time; one use
    private val confirmedSince: (Long) -> Boolean = { false },
    // What the user themself said or typed for the current request (never screen text)
    private val userText: () -> String = { "" },
    // research: a question -> an answer found on the web, or null on failure (blocking)
    private val research: ((String) -> String?)? = null,
    // planEngine: drives PlanStep state transitions for the current goal
    val planEngine: PlanEngine = PlanEngine(),
    private val onReviewApproved: (String) -> Unit = {}
) : ToolExecutor {

    companion object {
        private const val TAG = "DeviceToolExecutor"
        private const val MAX_APPS = 60

        // The yes / no shown to the user when an action needs their OK (VoiceSession reads the answer)
        const val CONFIRM_YES = "Yes, do it"
        const val CONFIRM_NO = "No"
        // A yes counts for this long after the question was shown
        private const val CONFIRM_WINDOW_MS = 120_000L

        private const val SELF_BLOCKED = "Blocked: that would stop, remove or switch off you (this assistant app) and end the task. " +
            "Skip yourself: act on the other apps, or tell the user it's the assistant itself."
        // Buttons that stop, remove or switch off an app (label or view id); applied only when the target is this app
        private val DESTRUCTIVE = Regex("""(?i)force.?stop|uninstall|clear.?(data|storage|cache)|disable|deactivate|turn.?off|remove|delete|बंद|हटा|अनइंस्टॉल""")

        private const val AGENT_OFF = "Screen control is off. Ask the user to enable \"Nik's voice\" in " +
            "Settings > Accessibility (you can open it with launch_intent action android.settings.ACCESSIBILITY_SETTINGS)."
    }

    private val packageManager: PackageManager = context.packageManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

    val isTvDevice: Boolean by lazy {
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION) ||
            (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /**
     * Function declarations sent to Gemini in the session setup message (JSON Schema parameters).
     * Adding a tool = a declaration here + a branch in execute(); the server needs no change.
     */
    val declarations: List<Map<String, Any>> by lazy {
        if (!isTvDevice) rawDeclarations else {
            val tvAllowed = setOf(
                "search_tv", "dpad", "open_app", "list_apps", "set_volume",
                "wait", "end_conversation", "get_time", "navigate", "launch_intent"
            )
            rawDeclarations.filter { it["name"] in tvAllowed }
        }
    }

    private val rawDeclarations: List<Map<String, Any>> = listOf(
        function(
            "read_screen",
            "Reads what is on the phone screen now: the foreground app and a numbered list of elements " +
                "like [3] Button \"Send\" (tap). Call it before acting on the screen or when the user refers to what is on it."
        ),
        function(
            "unlock_phone",
            "Unlocks the phone using the PIN, password or pattern saved in Settings (whichever lock type is set). " +
                "Only works if Auto-unlock is turned ON in Settings. " +
                "If Auto-unlock is OFF or no credential is saved, do NOT call this tool; instead, inform the user: 'Phone locked hai, kripya pehle phone unlock karein'."
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
            "Taps an element on screen by its number from the latest screen, or by its visible text. Returns the new screen " +
                "and \"messages\": what the app showed in response (toasts such as \"You are now 4 steps away from being a developer\" " +
                "or \"You are already a developer\"). Read them: they say whether it worked.",
            optional = listOf(
                "element_id" to param("integer", "Element number from the latest screen, e.g. 3 for [3]"),
                "text" to param("string", "Visible text of the element, when no element_id"),
                "long_press" to param("boolean", "Long-press instead of tap"),
                "times" to param("integer", "Tap this many times in quick succession (1-15), for things that need repeated taps, e.g. Build number 7 times"),
                "expect" to param("string", "What you expect to be true on screen after this action, e.g. \"Settings screen is open\"")
            )
        ),
        function(
            "type_text",
            "Types text into a text field (replacing its content): the given element, else the focused field. If several " +
                "fields are on screen and none is focused, it lists them so you can pick. The result says which field was used. Returns the new screen.",
            required = listOf("text" to param("string", "Text to type")),
            optional = listOf(
                "element_id" to param("integer", "The text field's element number"),
                "submit" to param("boolean", "Press the keyboard's enter/search/send key after typing. Leave it out to press it automatically in search fields; false never presses it"),
                "expect" to param("string", "What you expect to be true on screen after this action")
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
                "max_scrolls" to param("integer", "Most scrolls with until_text, default 10"),
                "expect" to param("string", "What you expect to be true on screen after this action")
            )
        ),
        function(
            "update_plan",
            "Your working plan for a multi-step goal, shown to the user as live progress. Call it at goal start " +
                "(goal + steps) and whenever you change approach (status blocked + note on what failed). " +
                "Call complete_step when each step is done.",
            required = listOf("goal" to param("string", "The user's goal in one line, in English")),
            optional = listOf(
                "steps" to mapOf(
                    "type" to "array",
                    "items" to mapOf(
                        "type" to "object",
                        "properties" to mapOf(
                            "title" to mapOf("type" to "string", "description" to "Short step label, in English"),
                            "detail" to mapOf("type" to "string", "description" to "What to do in this step"),
                            "expect" to mapOf("type" to "string", "description" to "What you expect to be true after completing it")
                        ),
                        "required" to listOf("title")
                    ),
                    "description" to "Ordered steps to reach the goal, in English"
                ),
                "status" to param("string", "Where the goal stands", enum = listOf("working", "blocked", "done", "failed")),
                "note" to param("string", "Your current thinking in English: what you see, what you decided and why (shown to the user)")
            )
        ),
        function(
            "complete_step",
            "Marks the current step as done. Only call this after confirming the step succeeded " +
                "(verify block showed verified=yes, or for background steps, the API confirmed it). " +
                "The next step starts automatically.",
            required = listOf(
                "step_id" to param("string", "The id of the step to mark done, e.g. \"s1\""),
                "summary" to param("string", "One line: what you did and the outcome")
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
            optional = listOf(
                "element_id" to param("integer", "Element to swipe on; default the screen center"),
                "expect" to param("string", "What you expect to be true on screen after this action")
            )
        ),
        function(
            "search_settings",
            "Searches this phone's own Settings for a setting by name (opens Settings search, types it, shows the results). " +
                "The quickest way to any setting on any phone: then tap the matching result. Returns the results screen.",
            required = listOf("query" to param("string", "The setting's name as it appears in Settings, e.g. \"Build number\", \"USB debugging\""))
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
            ),
            optional = listOf("expect" to param("string", "What you expect to be true on screen after this action"))
        ),
        function(
            "show_choices",
            "Shows the user a list to pick from by tapping (in the app and over other apps), e.g. when several contacts " +
                "match a name. Also read the options out briefly; the user answers by tapping or by saying one. " +
                "Their pick comes back as what they said.",
            required = listOf(
                "question" to param("string", "Short heading in English, e.g. \"Which Nikhil?\""),
                "options" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "2-8 options, e.g. \"Nikhil Rajput · +91 98765 43210\"")
            )
        ),
        function(
            "make_call",
            "Places a phone call. If the user explicitly requested a call (e.g. 'Rahul ko call lagao' or 'Call 9876543210'), place the call directly and narrate 'Calling...'. If there is contact ambiguity, confirm the contact first. " +
                "If it returns needs_confirmation, ask the user and call it again only after they say yes.",
            required = listOf("number" to param("string", "Phone number, e.g. +919876543210")),
            optional = listOf("speaker" to param("boolean", "Put the call on speaker (default true)"))
        ),
        function(
            "send_sms",
            "Sends a text SMS directly in the background via cellular radio without opening the Messages app or waking the screen. " +
                "Give the destination phone number (or look it up with find_contact first) and the message text.",
            required = listOf(
                "number" to param("string", "Destination phone number, e.g. +919876543210"),
                "message" to param("string", "Text of the SMS to send")
            )
        ),
        function(
            "reply_notification",
            "Instantly replies to an incoming message (WhatsApp, Telegram, Signal, Google Messages, Slack, etc.) headlessly in the " +
                "background using Android's notification direct reply, with ZERO UI opened and without waking the screen. " +
                "Use this to answer incoming messages or auto-reply to notifications.",
            required = listOf(
                "message" to param("string", "Reply text to send")
            ),
            optional = listOf(
                "sender" to param("string", "Sender name or contact to reply to (e.g. \"Papa\", \"Rahul\"). If omitted, replies to the latest incoming message"),
                "app" to param("string", "App name or package (e.g. \"WhatsApp\", \"Telegram\", \"Messages\"). If omitted, matches any messaging app")
            )
        ),
        function(
            "list_recent_messages",
            "Lists recent incoming messages and notifications that can be replied to headlessly in the background. " +
                "Returns sender, app, message snippet, and whether direct reply is available."
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
            "dpad",
            "Navigates Android TV / Jio STB apps using directional keys, select/OK, back, home, or play_pause. " +
                "Essential for TV apps where touch gestures are not used.",
            required = listOf("action" to param("string", "One of: up, down, left, right, select, ok, back, home, play_pause", listOf("up", "down", "left", "right", "select", "ok", "back", "home", "play_pause"))),
            optional = listOf("repeat" to param("integer", "How many times to repeat the directional key (1-10, default 1)"))
        ),
        function(
            "search_tv",
            "Plays or tunes content on Android TV / Jio Set-Top Box. " +
                "Use app='livetv' for broadcast TV channels (Aaj Tak, Sony Max, Zee Cinema, Star Sports, Star Plus, Colors, ABP News, Discovery, Cartoon Network, etc.), channel numbers, or live TV broadcast. " +
                "Use app='global' for movies, web series, TV shows, and OTT titles (Stree 2, Mirzapur, Panchayat, Asur, Kalki, Jawan, etc.) across all streaming apps. " +
                "Use app='youtube' for songs, music, bhajans, YouTube creators (CarryMinati, MrBeast), funny clips, tutorials. " +
                "Always set play_first=true to directly play or tune immediately without leaving the user on a search page.",
            required = listOf("query" to param("string", "Search query: movie title, web series name, TV channel name, or song title")),
            optional = listOf(
                "app" to param("string", "Target platform: 'livetv' for TV channels, 'global' for movies/shows/series across all apps, 'youtube' for songs/videos, 'jiocinema', 'hotstar'", listOf("livetv", "global", "youtube", "jiocinema", "hotstar")),
                "channel_number" to param("string", "Optional channel Content ID if known (e.g. 301036 for Aaj Tak)"),
                "play_first" to param("boolean", "If true, automatically plays the video, opens the top matching movie card, or tunes to the TV channel directly (always pass true)")
            )
        ),
        function(
            "open_app",
            "Opens an installed app by its name (use the full name, e.g. \"Google Keep\"). If several apps match it lists them; " +
                "if none does, use list_apps.",
            required = listOf("app_name" to param("string", "App name, e.g. \"YouTube\"")),
            optional = listOf("expect" to param("string", "What you expect to be true on screen after this action, e.g. \"YouTube home screen visible\""))
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
                "extras" to param("string", "JSON object of string/number/boolean extras, e.g. {\"query\":\"pizza\"}"),
                "expect" to param("string", "What you expect to be true on screen after this action")
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
            required = listOf("url_or_search" to param("string", "A web address, or what to search for")),
            optional = listOf("expect" to param("string", "What you expect to be true on screen after this action"))
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
        ),
        function(
            "get_time",
            "Returns the phone's current local date, time, day of week, and timezone — live from the device. " +
                "Call this whenever the user asks what time or date it is, or when you need the current time for a calculation. " +
                "Never guess or use the system-prompt time for a direct time question."
        ),
        function(
            "schedule_followup",
            "Schedules yourself to wake up later and run a fresh instruction on this device, even if the app isn't open " +
                "then. DYNAMIC RESOLUTION: If contacts are involved, resolve them with find_contact first. " +
                "Before arming, a Review UI will appear on screen for the user. Set confirmed=true once approved.",
            required = listOf(
                "instruction" to param("string", "What to do when the time comes, as a complete fresh instruction, e.g. 'Call Sanjog at +919876543210'"),
                "in_minutes" to param("integer", "Minutes from now to wait, 1 to 1440 (24 hours)")
            ),
            optional = listOf(
                "confirmed" to param("boolean", "Set to true once the user has approved the follow-up in the Review UI or by saying approve")
            )
        ),
        function(
            "register_webhook",
            "Registers a webhook trigger on this phone's local HTTP server (port 7474). An HTTP POST or GET to " +
                "http://<phone-ip>:7474/webhook/<path> on the same Wi-Fi / Tailscale network will fire the given " +
                "instruction automatically, as if the user had asked it. Great for smart-home events, IoT alerts, " +
                "or any external system that can make an HTTP call. Returns the full webhook URL and a curl example. " +
                "The server is NOT reachable from the open internet.",
            required = listOf(
                "path" to param("string", "URL path for this webhook, e.g. 'water-pump-done' or 'door-opened'. No slashes."),
                "instruction" to param("string", "What to do when the webhook fires, e.g. 'User ko batao ki paani pump band ho gaya'")
            ),
            optional = listOf(
                "label" to param("string", "A short human-readable name, e.g. 'Pump done'"),
                "secret" to param("string", "Optional shared-secret token. If set, callers must pass it as X-Webhook-Token header or ?token= query param.")
            )
        ),
        function(
            "create_automation",
            "Creates a new automation macro. DYNAMIC RESOLUTION MANDATORY: Never hardcode triggers, actions, contact numbers, or parameters. " +
                "If contacts are involved, call find_contact first and show_choices if ambiguous. Before saving, a Review UI will automatically " +
                "appear on screen displaying the 3 Pillars (Trigger, Action, Constraints). Set confirmed=true once approved.",
            required = listOf(
                "title" to param("string", "Short human-readable name, e.g. 'Headphones → Spotify'"),
                "trigger_type" to param("string", "phrase|scheduled|broadcast_event|condition|webhook|notification|incoming_call|missed_call|charger_connected|charger_disconnected|wifi_connected|boot|manual"),
                "instruction" to param("string", "What to do when triggered — a complete AI instruction as if the user said it")
            ),
            optional = listOf(
                "confirmed" to param("boolean", "Set to true only after the user has reviewed and approved the automation in the Review UI or by saying approve"),
                "trigger_phrase" to param("string", "For phrase: the voice command phrase"),
                "scheduled_hour" to param("integer", "For scheduled: hour 0-23"),
                "scheduled_minute" to param("integer", "For scheduled: minute 0-59"),
                "scheduled_days" to param("string", "For scheduled: comma-separated Calendar day numbers 1=Sun..7=Sat, blank=every day"),
                "broadcast_action" to param("string", "For broadcast_event: Android intent action string e.g. android.intent.action.HEADSET_PLUG"),
                "broadcast_extras" to param("string", "For broadcast_event: JSON {\"key\":\"value\"} pairs that must match intent extras. Blank value = key must exist. E.g. {\"state\":\"1\"} for headphones plugged in."),
                "broadcast_label" to param("string", "For broadcast_event: human-readable trigger description"),
                "condition_instruction" to param("string", "For condition: what to check each interval using tools (screen, apps, web)"),
                "condition_interval_minutes" to param("integer", "For condition: check every N minutes (default 10)"),
                "condition_stop_on_met" to param("boolean", "For condition: disable after first fire? (default true)"),
                "condition_label" to param("string", "For condition: human-readable description"),
                "webhook_path" to param("string", "For webhook: URL path (no slashes)"),
                "webhook_secret" to param("string", "For webhook: optional token"),
                "notification_package" to param("string", "For notification: app package name"),
                "notification_app_label" to param("string", "For notification: app display name"),
                "notification_title_contains" to param("string", "For notification: title must contain this"),
                "notification_text_contains" to param("string", "For notification: body must contain this"),
                "wifi_ssid" to param("string", "For wifi_connected: SSID to match (blank=any)"),
                "call_contact_filter" to param("string", "For incoming/missed_call: phone number or 'unknown'"),
                "call_contact_label" to param("string", "For incoming/missed_call: contact display name"),
                "constraint_wifi_only" to param("boolean", "Constraint: true = only run when Wi-Fi is connected"),
                "constraint_screen_off" to param("boolean", "Constraint: true = only run when screen is off"),
                "constraint_battery_at_least" to param("integer", "Constraint: minimum battery % (e.g. 20)"),
                "constraint_battery_at_most" to param("integer", "Constraint: maximum battery % (e.g. 80)"),
                "constraint_time_from_hour" to param("integer", "Constraint: time window start hour (0-23)"),
                "constraint_time_from_minute" to param("integer", "Constraint: time window start minute (0-59)"),
                "constraint_time_to_hour" to param("integer", "Constraint: time window end hour (0-23)"),
                "constraint_time_to_minute" to param("integer", "Constraint: time window end minute (0-59)")
            )
        ),
        function(
            "list_automations",
            "Lists all saved automations with their id, title, trigger summary, and enabled state. " +
                "Use this before edit_automation or delete_automation to get the id."
        ),
        function(
            "edit_automation",
            "Edit an existing automation's title, instruction, trigger, constraints, or on/off state. " +
                "Allows changing all 3 pillars (Trigger, Action, Constraints) like Tasker / MacroDroid.",
            required = listOf("id" to param("string", "Macro id from list_automations")),
            optional = listOf(
                "title" to param("string", "New title"),
                "instruction" to param("string", "New instruction — what the AI should do when triggered"),
                "enabled" to param("boolean", "true = on, false = off"),
                "trigger_type" to param("string", "phrase|scheduled|broadcast_event|condition|webhook|notification|incoming_call|missed_call|charger_connected|charger_disconnected|wifi_connected|boot|manual"),
                "trigger_phrase" to param("string", "For phrase: voice command phrase"),
                "scheduled_hour" to param("integer", "For scheduled: hour 0-23"),
                "scheduled_minute" to param("integer", "For scheduled: minute 0-59"),
                "scheduled_days" to param("string", "For scheduled: comma-separated days 1=Sun..7=Sat"),
                "notification_package" to param("string", "For notification: app package name"),
                "notification_app_label" to param("string", "For notification: app display name"),
                "notification_title_contains" to param("string", "For notification: title filter"),
                "notification_text_contains" to param("string", "For notification: body filter"),
                "wifi_ssid" to param("string", "For wifi_connected: SSID filter"),
                "constraint_wifi_only" to param("boolean", "true = only run when Wi-Fi is connected"),
                "constraint_screen_off" to param("boolean", "true = only run when screen is off"),
                "constraint_battery_at_least" to param("integer", "Minimum battery % (e.g. 20)"),
                "constraint_battery_at_most" to param("integer", "Maximum battery % (e.g. 80)"),
                "constraint_time_from_hour" to param("integer", "Time window start hour (0-23)"),
                "constraint_time_from_minute" to param("integer", "Time window start minute (0-59)"),
                "constraint_time_to_hour" to param("integer", "Time window end hour (0-23)"),
                "constraint_time_to_minute" to param("integer", "Time window end minute (0-59)"),
                "clear_constraints" to param("boolean", "true = remove all constraints")
            )
        ),
        function(
            "delete_automation",
            "Permanently delete an automation. Confirm with the user before calling this.",
            required = listOf("id" to param("string", "Macro id from list_automations"))
        ),
        function(
            "report_condition",
            "INTERNAL: called only during a condition-check turn. Reports whether the condition is currently met. " +
                "If met=true, the macro's action runs next. If met=false, the next check is already scheduled — end the turn silently.",
            required = listOf(
                "macro_id" to param("string", "The macro_id given in the condition check instruction"),
                "met" to param("boolean", "true = condition IS met now, false = not yet")
            )
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
    // One action at a time: the model's tool thread and an automation run share the screen and the progress state
    private val actionLock = ReentrantLock()

    /**
     * Runs one tool call. trusted: the user started it themself (an automation they ran), which
     * counts as asking for calls / sends / deletes; payments and wipes still need their yes.
     */
    private val passiveTools = setOf(
        "update_plan", "complete_step", "read_screen", "look_at_screen", "list_apps",
        "find_contact", "list_recent_messages", "list_automations",
        "end_conversation", "wait"
    )

    override fun execute(toolName: String, arguments: Map<String, Any?>?, trusted: Boolean): Map<String, Any?> = actionLock.withLock {
        // Arguments can hold message text and numbers: only the tool name is logged
        Log.i(TAG, "Executing tool: $toolName")
        // Auto-start a 1-step plan when acting without an existing plan
        if (!isTvDevice && toolName !in passiveTools) {
            val current = planEngine.plan.value
            if (current == null || current.status in setOf("done", "failed")) {
                val userGoal = userText().lines().lastOrNull { it.isNotBlank() }?.trim() ?: toolName
                planEngine.start(userGoal, listOf(PlanStep("s1", userGoal)))
            }
        }
        val result = run(toolName, arguments.orEmpty(), trusted)
        val checked = withProgressCheck(toolName, arguments.orEmpty(), result)

        // Screen verify: run when the tool has an "expect" param and a verifier is available
        val expect = arguments?.get("expect") as? String
        if (!expect.isNullOrBlank() && toolName in acting && screenVerifier != null) {
            @Suppress("UNCHECKED_CAST")
            val elements = (checked["screen"] as? Map<String, Any?>)?.let { s ->
                s["elements"] as? List<String>
            }.orEmpty()
            val appPkg = (checked["screen"] as? Map<String, Any?>)?.get("app") as? String ?: ""
            val vr = kotlinx.coroutines.runBlocking {
                screenVerifier.verify(
                    expect = expect,
                    elements = elements,
                    app = appPkg,
                    goal = plan?.goal ?: "",
                    stepIndex = (plan?.currentStep ?: 1) - 1,
                    stepTitle = toolName,
                    lastTool = toolName,
                    lastArgs = arguments.orEmpty()
                )
            }
            pendingVerifyResult = vr
            return@withLock checked + mapOf(
                "verify" to mapOf(
                    "verified" to vr.verified,
                    "via" to vr.via,
                    "evidence" to vr.evidence,
                    "blocker" to vr.blocker,
                    "step_done" to vr.stepDone
                )
            )
        }

        checked
    }

    // ---- Asking before actions with consequences (see ActionGuard) ----

    private data class Pending(val key: String, val at: Long)
    @Volatile private var pending: Pending? = null
    @Volatile private var pendingReviewKey: String? = null
    @Volatile private var pendingReviewAt: Long = 0L
    // The last number put in the dialer: a code there (*21*…#) makes its Call button a dial code
    @Volatile private var lastDialed: String? = null

    /**
     * Null when the action may go ahead now. Otherwise the user is shown a yes / no and the model is
     * told to wait: once they say yes, the same call (same key) goes through, once.
     */
    private fun gate(risk: ActionGuard.Risk, key: String, what: String, trusted: Boolean): Map<String, Any?>? {
        if (!ActionGuard.needsYes(risk, AgentPolicy.current.confirmLevel, userText(), trusted)) return null
        val p = pending
        if (p != null && p.key == key && System.currentTimeMillis() - p.at < CONFIRM_WINDOW_MS && confirmedSince(p.at)) {
            pending = null
            AgentTrace.log(AgentTrace.Kind.TOOL, "User confirmed: $what")
            return null
        }
        pending = Pending(key, System.currentTimeMillis())
        AgentTrace.log(AgentTrace.Kind.WARNING, "Waiting for the user's OK to $what")
        onShowChoices("$what?", listOf(CONFIRM_YES, CONFIRM_NO))
        return mapOf(
            "success" to false,
            "needs_confirmation" to what,
            "error" to "Not done yet: this needs the user's OK first (${risk.what}), and a yes / no is now shown to them. " +
                "Ask them in one short line (\"$what?\") and wait for the answer. If they say yes, call this same tool again " +
                "with the same arguments; if no, don't do it."
        )
    }

    private fun digits(number: String) = number.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }

    private fun run(toolName: String, args: Map<String, Any?>, trusted: Boolean): Map<String, Any?> {
        fun str(key: String) = args[key]?.toString()?.takeIf { it.isNotBlank() }
        fun int(key: String) = args[key]?.toString()?.toDoubleOrNull()?.toInt()
        fun bool(key: String) = args[key]?.toString()?.toBooleanStrictOrNull()
        val agent = PhoneAgentService.instance
        // Screen control is off while a payment app is open (they block payments otherwise)
        PaymentGuard.pausedFor?.let { pkg ->
            if (agent == null && toolName in setOf("read_screen", "look_at_screen", "tap", "type_text", "scroll", "swipe", "navigate", "search_settings", "unlock_phone")) {
                return mapOf("error" to "Screen control is paused while the payment app ($pkg) is open: payment apps refuse to pay while an assistant can read the screen. " +
                    "Let the user complete the payment themselves; screen control comes back when they leave it.")
            }
        }

        return try {
            when (toolName) {
                "read_screen" -> {
                    val screen = agent?.readScreen() ?: mapOf("error" to AGENT_OFF)
                    // When accessibility returns nothing (secure screen, WebView-heavy app, system UI),
                    // augment with a screenshot description so the model still knows what it's looking at.
                    augmentWithScreenshot(agent, screen)
                }

                "unlock_phone" -> when {
                    !SecurePrefs.isAutoUnlockEnabled(context) ->
                        mapOf("success" to false, "error" to "Auto-unlock is OFF in Settings for security. Tell the user: 'Phone locked hai, kripya pehle phone unlock karein.'")
                    !SecurePrefs.hasPin(context) ->
                        mapOf("success" to false, "error" to "No PIN is saved in Settings. Tell the user: 'Phone locked hai aur PIN saved nahi hai. Kripya pehle phone unlock karein.'")
                    // Only for a request the user made themself, never for something read on the screen
                    !trusted && userText().isBlank() ->
                        mapOf("success" to false, "error" to "The phone is unlocked only for a request the user made. Ask the user what they want first.")
                    agent == null -> mapOf("error" to AGENT_OFF)
                    else -> unlock(agent)
                }

                "look_at_screen" -> if (agent == null) mapOf("error" to AGENT_OFF) else lookAtScreen(agent, str("question"))

                "media_control" -> mediaControl(str("action") ?: "play_pause")

                "make_call" -> {
                    val number = str("number")
                    val risk = if (ActionGuard.isDialCode(number)) ActionGuard.Risk.DIAL_CODE else ActionGuard.Risk.CALL
                    number?.let { gate(risk, "call:${digits(it)}", "Call $it", trusted) } ?: makeCall(number, bool("speaker") != false)
                }

                "send_sms" -> {
                    val number = str("number") ?: return mapOf("error" to "Give destination phone number")
                    val message = str("message") ?: return mapOf("error" to "Give SMS message text")
                    val risk = ActionGuard.Risk.SEND
                    gate(risk, "sms:${digits(number)}", "Send SMS to $number: $message", trusted)
                        ?: com.nik.voiceassistant.service.DirectSmsManager.sendSms(context, number, message)
                }

                "reply_notification" -> {
                    val message = str("message") ?: return mapOf("error" to "Give reply message text")
                    val sender = str("sender")
                    val app = str("app")
                    val risk = ActionGuard.Risk.SEND
                    gate(risk, "reply:${sender ?: app ?: "msg"}", "Reply '$message' to ${sender ?: app ?: "message"}", trusted)
                        ?: com.nik.voiceassistant.service.DirectReplyManager.reply(context, app, sender, message)
                }

                "list_recent_messages" -> {
                    mapOf("messages" to com.nik.voiceassistant.service.DirectReplyManager.listActiveReplyable())
                }

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

                "tap" -> if (agent == null) mapOf("error" to AGENT_OFF) else tap(agent, int("element_id"), str("text"), bool("long_press") == true, int("times") ?: 1, trusted)

                "type_text" -> when {
                    agent == null -> mapOf("error" to AGENT_OFF)
                    else -> {
                        val text = str("text") ?: ""
                        // Enter in a chat box sends the message; in a search box it just searches
                        val sends = bool("submit") == true && agent.submitSends(int("element_id"))
                        (if (sends) gate(ActionGuard.Risk.SEND, "send:${text.hashCode()}", "Send \"${text.take(60)}\"", trusted) else null)
                            ?: agent.typeText(text, int("element_id"), bool("submit"))
                    }
                }

                "scroll" -> agent?.scroll(str("direction") ?: "down", int("element_id"), str("until_text"), int("max_scrolls") ?: 10)
                    ?: mapOf("error" to AGENT_OFF)

                "update_plan" -> {
                    val goal = str("goal") ?: plan?.goal ?: userText().lines().lastOrNull { it.isNotBlank() }?.trim() ?: "Current task"
                    val rawSteps = args["steps"] ?: args["step_list"]
                    val steps: List<PlanStep> = when {
                        rawSteps is List<*> && rawSteps.firstOrNull() is Map<*, *> ->
                            @Suppress("UNCHECKED_CAST")
                            (rawSteps as List<Map<*, *>>).mapIndexed { i, m ->
                                PlanStep("s${i+1}", m["title"] as? String ?: "", m["detail"] as? String ?: "", m["expect"] as? String ?: "")
                            }
                        rawSteps is List<*> ->
                            rawSteps.mapIndexed { i, s -> PlanStep("s${i+1}", s.toString()) }
                        else -> parseRawStepTitles(rawSteps)?.mapIndexed { i, t -> PlanStep("s${i+1}", t) } ?: emptyList()
                    }
                    val existing = planEngine.plan.value
                    val statusArg = str("status")
                    if (existing == null || existing.status in setOf("done", "failed") || !existing.goal.equals(goal, ignoreCase = true)) {
                        planEngine.start(goal, steps.ifEmpty { listOf(PlanStep("s1", goal)) })
                    } else {
                        planEngine.replan(steps.ifEmpty { existing.steps.filter { it.status != StepStatus.DONE } }, str("note"))
                    }
                    if (statusArg == "blocked") planEngine.fail(planEngine.plan.value?.steps?.firstOrNull { it.status == StepStatus.RUNNING }?.id ?: "s1", str("note").orEmpty().ifBlank { "blocked" })
                    if (statusArg != null && statusArg in setOf("done", "failed")) planEngine.finish(statusArg)
                    val runningStep = planEngine.plan.value?.steps?.firstOrNull { it.status == StepStatus.RUNNING }
                    mapOf(
                        "saved" to true,
                        "running_step_id" to (runningStep?.id ?: "none"),
                        "next" to "Now execute step ${planEngine.plan.value?.currentStep ?: 1}: ${runningStep?.title ?: ""}. Call complete_step(step_id=\"${runningStep?.id ?: "s1"}\") after it succeeds."
                    )
                }

                "complete_step" -> {
                    val stepId = str("step_id") ?: "s1"
                    val summary = str("summary") ?: "done"
                    val vr = pendingVerifyResult.also { pendingVerifyResult = null }
                    val err = planEngine.requestComplete(stepId, summary, vr)
                    if (err != null) mapOf("error" to err)
                    else mapOf("ok" to true, "next_step" to planEngine.plan.value?.steps?.firstOrNull { it.status == StepStatus.RUNNING }?.title)
                }

                "research" -> research(str("question") ?: "")

                "search_settings" -> if (agent == null) mapOf("error" to AGENT_OFF) else searchSettings(agent, str("query") ?: "")

                "swipe" -> agent?.swipe(str("direction") ?: "up", int("element_id")) ?: mapOf("error" to AGENT_OFF)

                "navigate" -> agent?.navigate(str("action") ?: "") ?: mapOf("error" to AGENT_OFF)

                "dpad" -> if (agent == null) mapOf("error" to AGENT_OFF) else agent.dpad(str("action") ?: "select", int("repeat") ?: 1)

                "search_tv" -> searchTv(str("query") ?: "", str("app"), bool("play_first") ?: false, str("channel_number"))

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

                "launch_intent" -> launchIntent(str("action"), str("uri"), str("package"), str("extras"), trusted)

                "open_website" -> openWebsite(str("url_or_search") ?: "")

                "toggle_flashlight" -> {
                    val enabled = bool("enabled") ?: str("state")?.equals("on", ignoreCase = true) ?: true
                    mapOf("success" to toggleFlashlight(enabled), "flashlight_state" to if (enabled) "on" else "off")
                }

                "toggle_bluetooth" -> {
                    val enabled = bool("enabled") ?: str("state")?.equals("on", ignoreCase = true) ?: true
                    mapOf("success" to true, "message" to toggleBluetooth(enabled))
                }

                "schedule_followup" -> {
                    val instruction = str("instruction")?.trim()
                    val minutes = int("in_minutes")
                    val confirmed = bool("confirmed") == true
                    when {
                        instruction.isNullOrBlank() -> mapOf("error" to "Give an instruction to run when the time comes")
                        minutes == null || minutes < 1 -> mapOf("error" to "Give in_minutes, at least 1")
                        minutes > 1440 -> mapOf("error" to "Too far out; keep it within 1440 minutes (24 hours)")
                        else -> {
                            val key = "followup:${instruction.take(40)}:$minutes"
                            val isConfirmed = confirmed || (pendingReviewKey == key && System.currentTimeMillis() - pendingReviewAt < CONFIRM_WINDOW_MS && confirmedSince(pendingReviewAt))
                            if (!isConfirmed) {
                                val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, minutes) }
                                val trigger = com.nik.voiceassistant.data.macro.Trigger.Scheduled(
                                    hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                                    minute = cal.get(java.util.Calendar.MINUTE),
                                    days = emptySet()
                                )
                                val title = instruction.take(60)
                                val macro = com.nik.voiceassistant.data.macro.Macro(
                                    title = title,
                                    description = "Scheduled follow-up",
                                    triggers = listOf(trigger),
                                    instruction = instruction,
                                    action = com.nik.voiceassistant.data.macro.Action.AlwaysAsk
                                )
                                val timeStr = String.format(java.util.Locale.US, "%02d:%02d", trigger.hour, trigger.minute)
                                val triggerDesc = "Scheduled in $minutes min ($timeStr)"
                                pendingReviewKey = key
                                pendingReviewAt = System.currentTimeMillis()
                                val review = com.nik.voiceassistant.data.macro.AutomationReview(
                                    title = title,
                                    triggerSummary = triggerDesc,
                                    actionSummary = instruction,
                                    constraintsSummary = "None",
                                    onApprove = {
                                        pendingReviewKey = null
                                        saveAndArmMacro(macro, trigger)
                                        onReviewApproved("Follow-up '$title' scheduled for $timeStr approved.")
                                    },
                                    onCancel = {
                                        pendingReviewKey = null
                                        onReviewApproved("Follow-up '$title' cancelled.")
                                    }
                                )
                                onShowAutomationReview(review)
                                onShowChoices("⚡ Review Automation: $title", listOf("✅ Approve & Create", "❌ Cancel"))
                                mapOf(
                                    "status" to "needs_review",
                                    "review_displayed" to true,
                                    "title" to title,
                                    "trigger" to triggerDesc,
                                    "action" to instruction,
                                    "constraints" to "None",
                                    "message" to "Review UI is displayed on user screen. Ask user to review the 3 pillars and tap 'Approve & Create' or say 'Approve'."
                                )
                            } else {
                                pendingReviewKey = null
                                scheduleFollowup(instruction, minutes)
                            }
                        }
                    }
                }

                "get_time" -> {
                    val tz = java.util.TimeZone.getDefault()
                    val now = java.util.Date()
                    val date = java.text.SimpleDateFormat("EEEE, d MMMM yyyy", java.util.Locale.ENGLISH).also { it.timeZone = tz }.format(now)
                    val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ENGLISH).also { it.timeZone = tz }.format(now)
                    val tzName = tz.getDisplayName(false, java.util.TimeZone.SHORT)
                    mapOf("date" to date, "time" to time, "timezone" to tzName, "epoch_ms" to now.time)
                }

                "register_webhook" -> {
                    val path = str("path")?.trim()?.trimStart('/')?.lowercase()
                    val instruction = str("instruction")?.trim()
                    val label = str("label")?.trim() ?: ""
                    val secret = str("secret")?.trim()
                    when {
                        path.isNullOrBlank() -> mapOf("error" to "Give a path for the webhook URL")
                        instruction.isNullOrBlank() -> mapOf("error" to "Give an instruction to run when the webhook fires")
                        else -> registerWebhook(path, instruction, label, secret)
                    }
                }

                "create_automation" -> {
                    val title = str("title")?.trim()
                    val triggerType = str("trigger_type")?.trim()
                    val instruction = str("instruction")?.trim()
                    val confirmed = bool("confirmed") == true
                    when {
                        title.isNullOrBlank() -> mapOf("error" to "Give a title")
                        triggerType.isNullOrBlank() -> mapOf("error" to "Give a trigger_type")
                        instruction.isNullOrBlank() -> mapOf("error" to "Give an instruction")
                        else -> {
                            val key = "create_auto:$title:$triggerType"
                            val isConfirmed = confirmed || (pendingReviewKey == key && System.currentTimeMillis() - pendingReviewAt < CONFIRM_WINDOW_MS && confirmedSince(pendingReviewAt))
                            if (!isConfirmed) {
                                val trigger = parseTrigger(triggerType, args, title)
                                    ?: return mapOf("error" to "Invalid parameters for trigger_type '$triggerType'")
                                val constraints = parseConstraints(args) ?: emptyList()
                                val triggerDesc = trigger.describe()
                                val constraintsDesc = if (constraints.isEmpty()) "None" else constraints.joinToString("; ") { it.describe() }
                                val macro = com.nik.voiceassistant.data.macro.Macro(
                                    title = title,
                                    triggers = listOf(trigger),
                                    constraints = constraints,
                                    instruction = instruction,
                                    action = com.nik.voiceassistant.data.macro.Action.AlwaysAsk
                                )
                                pendingReviewKey = key
                                pendingReviewAt = System.currentTimeMillis()
                                val review = com.nik.voiceassistant.data.macro.AutomationReview(
                                    title = title,
                                    triggerSummary = triggerDesc,
                                    actionSummary = instruction,
                                    constraintsSummary = constraintsDesc,
                                    onApprove = {
                                        pendingReviewKey = null
                                        saveAndArmMacro(macro, trigger)
                                        onReviewApproved("Automation '$title' has been approved and saved.")
                                    },
                                    onCancel = {
                                        pendingReviewKey = null
                                        onReviewApproved("Automation '$title' creation cancelled by user.")
                                    }
                                )
                                onShowAutomationReview(review)
                                onShowChoices("⚡ Review Automation: $title", listOf("✅ Approve & Create", "❌ Cancel"))
                                mapOf(
                                    "status" to "needs_review",
                                    "review_displayed" to true,
                                    "title" to title,
                                    "trigger" to triggerDesc,
                                    "action" to instruction,
                                    "constraints" to constraintsDesc,
                                    "message" to "Review UI is displayed on user screen with 3 pillars (Trigger, Action, Constraints). Ask user to review and tap 'Approve & Create' or say 'Approve' to confirm."
                                )
                            } else {
                                pendingReviewKey = null
                                createAutomation(title, triggerType, instruction, args)
                            }
                        }
                    }
                }

                "list_automations" -> {
                    val macros = com.nik.voiceassistant.data.macro.MacroStore.getAll(context)
                    mapOf("automations" to macros.map { m ->
                        mapOf(
                            "id" to m.id,
                            "title" to m.title,
                            "trigger" to m.triggerSummary(),
                            "action" to m.instruction,
                            "constraints" to m.constraints.map { it.describe() },
                            "enabled" to m.enabled,
                            "run_count" to m.runCount
                        )
                    }, "count" to macros.size)
                }

                "edit_automation" -> {
                    val id = str("id")?.trim()
                    if (id.isNullOrBlank()) return mapOf("error" to "Give the automation id from list_automations")
                    val macros = com.nik.voiceassistant.data.macro.MacroStore.getAll(context)
                    val old = macros.firstOrNull { it.id == id } ?: return mapOf("error" to "Automation not found. Use list_automations to get the id.")
                    val newTitle = str("title")?.trim()?.ifBlank { null } ?: old.title
                    val newTrigger = str("trigger_type")?.let { parseTrigger(it, args, newTitle) }
                    val newConstraints = parseConstraints(args, old.constraints)
                    val newInstruction = str("instruction")?.trim()?.ifBlank { null } ?: old.instruction
                    val newEnabled = bool("enabled") ?: old.enabled

                    val updated = old.copy(
                        title = newTitle,
                        instruction = newInstruction,
                        enabled = newEnabled,
                        triggers = if (newTrigger != null) listOf(newTrigger) else old.triggers,
                        constraints = newConstraints ?: old.constraints
                    )
                    com.nik.voiceassistant.data.macro.MacroStore.save(context, updated)

                    // Re-arm or cancel scheduler
                    if (updated.enabled && updated.triggers.any { it is com.nik.voiceassistant.data.macro.Trigger.Scheduled }) {
                        com.nik.voiceassistant.service.ScheduledTriggerMonitor.scheduleOne(context, updated)
                    } else if (!updated.enabled || old.triggers.any { it is com.nik.voiceassistant.data.macro.Trigger.Scheduled }) {
                        com.nik.voiceassistant.service.ScheduledTriggerMonitor.cancel(context, id)
                    }
                    com.nik.voiceassistant.service.GenericBroadcastMonitor.refresh(context) { null }

                    mapOf(
                        "success" to true,
                        "id" to updated.id,
                        "title" to updated.title,
                        "trigger" to updated.triggerSummary(),
                        "constraints" to updated.constraints.map { it.describe() },
                        "enabled" to updated.enabled
                    )
                }

                "delete_automation" -> {
                    val id = str("id")?.trim()
                    if (id.isNullOrBlank()) return mapOf("error" to "Give the automation id")
                    com.nik.voiceassistant.data.macro.MacroStore.delete(context, id)
                    com.nik.voiceassistant.service.ConditionPollingEngine.cancel(context, id)
                    com.nik.voiceassistant.service.ScheduledTriggerMonitor.cancel(context, id)
                    com.nik.voiceassistant.service.GenericBroadcastMonitor.refresh(context) { null }
                    mapOf("success" to true)
                }

                "report_condition" -> {
                    val macroId = str("macro_id")?.trim()
                    val met = bool("met")
                    when {
                        macroId.isNullOrBlank() -> mapOf("error" to "Give macro_id")
                        met == null -> mapOf("error" to "Give met (true/false)")
                        !met -> mapOf("result" to "not_met", "next" to "Condition not met. End this turn silently — do not speak to the user.")
                        else -> {
                            val macro = com.nik.voiceassistant.data.macro.MacroStore.getAll(context).firstOrNull { it.id == macroId }
                                ?: return mapOf("error" to "Macro $macroId not found")
                            val trig = macro.triggers.firstOrNull { it is com.nik.voiceassistant.data.macro.Trigger.Condition } as? com.nik.voiceassistant.data.macro.Trigger.Condition
                            if (trig?.stopOnMet == true) {
                                com.nik.voiceassistant.data.macro.MacroStore.update(context, macroId) { it.copy(enabled = false) }
                                com.nik.voiceassistant.service.ConditionPollingEngine.cancel(context, macroId)
                            }
                            mapOf("result" to "met", "next" to "Condition IS MET. Now carry out this action for the user: ${macro.instruction}")
                        }
                    }
                }

                else -> mapOf("error" to "Unsupported tool '$toolName'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool '$toolName': ${e.message}", e)
            mapOf("error" to (e.message ?: "Execution failed"))
        }
    }

    /** A tap, after the checks: never against this app itself, and with the user's OK where it matters. */
    private fun tap(agent: PhoneAgentService, elementId: Int?, text: String?, longPress: Boolean, times: Int, trusted: Boolean): Map<String, Any?> {
        if (harmsSelf(agent, elementId, text)) return mapOf("error" to SELF_BLOCKED)
        val target = agent.target(elementId, text)
        val label = target?.label?.ifBlank { null } ?: text
        var risk = ActionGuard.classifyTap(label, target?.viewId)
        // The dialer's Call button with a code in it runs that code (e.g. call forwarding)
        if (risk == ActionGuard.Risk.CALL && ActionGuard.isDialCode(lastDialed)) risk = ActionGuard.Risk.DIAL_CODE
        if (risk != null) {
            val what = when (risk) {
                ActionGuard.Risk.DIAL_CODE -> "Dial ${lastDialed ?: "this code"}"
                else -> "Press \"${label?.take(60) ?: "this button"}\""
            }
            gate(risk, "tap:${risk.name}:${label?.lowercase()}", what, trusted)?.let { return it }
        }
        // One confirmed press is one press
        return agent.tap(elementId, text, longPress, if (risk != null) 1 else times)
    }

    private fun unlock(agent: PhoneAgentService): Map<String, Any?> = when (agent.unlockWithSavedPin()) {
        PhoneAgentService.Unlock.DONE -> {
            SecurePrefs.noteUnlockSuccess(context)
            withScreen(mapOf("success" to true, "done" to "phone unlocked"))
        }
        PhoneAgentService.Unlock.NO_PIN -> mapOf("success" to false, "error" to "No PIN/password/pattern is saved. Tell the user: 'Phone locked hai, kripya pehle phone unlock karein.'")
        PhoneAgentService.Unlock.NOT_NUMERIC -> mapOf("success" to false, "error" to "The saved credential looks invalid for the selected lock type. Ask the user to unlock the phone and re-check it in Settings.")
        PhoneAgentService.Unlock.NO_KEYPAD -> mapOf("success" to false, "error" to "The lock screen's PIN pad / password field / pattern grid wasn't found, so nothing was entered. Ask the user to unlock the phone.")
        PhoneAgentService.Unlock.WRONG_PIN -> {
            val turnedOff = SecurePrefs.noteUnlockFailure(context)
            AgentTrace.log(AgentTrace.Kind.WARNING, if (turnedOff) "Saved credential failed twice: auto-unlock turned off" else "Saved credential didn't unlock the phone")
            mapOf(
                "success" to false,
                "error" to if (turnedOff) "The saved credential failed again, so auto-unlock was turned off to protect the phone. Tell the user to unlock it themself and update it in Settings."
                else "The saved credential didn't unlock the phone (it may have changed). Don't try again; ask the user to unlock it."
            )
        }
    }

    // ---- Planning, research and progress ----

    // The model's plan for the goal in progress; read planEngine.plan for reactive UI.
    val plan: TaskPlan? get() = planEngine.plan.value

    // Pending verify result from the last acting tool; consumed by complete_step
    @Volatile private var pendingVerifyResult: VerifyResult? = null

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
            val tz = java.util.TimeZone.getDefault()
            val sdf = java.text.SimpleDateFormat("EEE d MMM yyyy HH:mm:ss", java.util.Locale.ENGLISH).also { it.timeZone = tz }
            append("time ${sdf.format(java.util.Date())} ${tz.getDisplayName(false, java.util.TimeZone.SHORT)}")
        }
    }

    /**
     * The user's own phone numbers, read from the SIMs (labelled with the SIM's name), for messages
     * to themself. Empty without the phone-number permission or when the SIM doesn't store its number.
     */
    fun ownNumbers(): List<String> {
        val granted = { p: String -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
        if (!granted(Manifest.permission.READ_PHONE_STATE) || !granted(Manifest.permission.READ_PHONE_NUMBERS)) return emptyList()
        return try {
            val subs = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            subs?.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
                @Suppress("DEPRECATION")
                val number = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) subs?.getPhoneNumber(info.subscriptionId) else info.number)
                    ?.replace(Regex("[\\s()-]"), "")?.takeIf { it.length >= 6 } ?: return@mapNotNull null
                val international = when {
                    number.startsWith("+") -> number
                    info.countryIso.isNullOrBlank() -> number
                    else -> runCatching {
                        android.telephony.PhoneNumberUtils.formatNumberToE164(number, info.countryIso.uppercase())
                    }.getOrNull() ?: number
                }
                "$international (SIM ${info.simSlotIndex + 1}: ${info.displayName})"
            }.distinct()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun parseRawStepTitles(raw: Any?): List<String>? {
        if (raw == null) return null
        when (raw) {
            is Collection<*> -> {
                val list = raw.mapNotNull { item ->
                    when (item) {
                        is Map<*, *> -> item["title"]?.toString()?.trim()
                        else -> item?.toString()?.trim()
                    }
                }.filter { it.isNotBlank() }
                if (list.isNotEmpty()) return list
            }
            is Array<*> -> {
                val list = raw.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
                if (list.isNotEmpty()) return list
            }
        }
        val text = raw.toString().trim()
        if (text.isBlank()) return null

        val fromJson = runCatching {
            val elem = JsonParser.parseString(text)
            if (elem.isJsonArray) {
                elem.asJsonArray.mapNotNull { el ->
                    if (el.isJsonObject) el.asJsonObject.get("title")?.asString?.trim()
                    else runCatching { el.asString.trim() }.getOrNull()
                }.filter { it.isNotBlank() }
            } else null
        }.getOrNull()
        if (!fromJson.isNullOrEmpty()) return fromJson

        val stripped = if (text.startsWith("[") && text.endsWith("]")) {
            text.substring(1, text.length - 1)
        } else {
            text
        }
        val items = if (stripped.contains("\n")) {
            stripped.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }
        } else if (stripped.contains(",")) {
            stripped.split(",").map { it.trim().removeSurrounding("\"").removeSurrounding("'").trim() }
        } else {
            listOf(stripped.trim().removeSurrounding("\"").removeSurrounding("'").trim())
        }
        return items.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
    }

    /** Saves a one-off Scheduled-trigger macro and arms its alarm; ScheduledMacroReceiver runs it and disables it. */
    private fun scheduleFollowup(instruction: String, minutes: Int): Map<String, Any?> {
        val cal = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, minutes) }
        val macro = com.nik.voiceassistant.data.macro.Macro(
            title = instruction.take(60),
            description = "Scheduled follow-up",
            triggers = listOf(
                com.nik.voiceassistant.data.macro.Trigger.Scheduled(
                    hour = cal.get(java.util.Calendar.HOUR_OF_DAY),
                    minute = cal.get(java.util.Calendar.MINUTE),
                    days = emptySet()
                )
            ),
            instruction = instruction,
            action = com.nik.voiceassistant.data.macro.Action.AlwaysAsk
        )
        com.nik.voiceassistant.data.macro.MacroStore.save(context, macro)
        com.nik.voiceassistant.service.ScheduledTriggerMonitor.scheduleOne(context, macro)
        return mapOf(
            "success" to true,
            "scheduled_in_minutes" to minutes,
            "next" to "Tell the user briefly that you'll check back in about $minutes minutes, then you can end this turn."
        )
    }

    private fun parseConstraints(args: Map<String, Any?>, current: List<com.nik.voiceassistant.data.macro.Constraint> = emptyList()): List<com.nik.voiceassistant.data.macro.Constraint>? {
        fun b(k: String) = args[k]?.let { it == true || it.toString().equals("true", ignoreCase = true) }
        fun i(k: String) = args[k]?.toString()?.toIntOrNull()

        if (b("clear_constraints") == true) return emptyList()

        val hasAny = args.keys.any { it.startsWith("constraint_") }
        if (!hasAny) return null

        val list = mutableListOf<com.nik.voiceassistant.data.macro.Constraint>()
        if (b("constraint_wifi_only") == true) list.add(com.nik.voiceassistant.data.macro.Constraint.WifiConnected)
        if (b("constraint_screen_off") == true) list.add(com.nik.voiceassistant.data.macro.Constraint.ScreenOff)
        i("constraint_battery_at_least")?.let { list.add(com.nik.voiceassistant.data.macro.Constraint.BatteryAtLeast(it)) }
        i("constraint_battery_at_most")?.let { list.add(com.nik.voiceassistant.data.macro.Constraint.BatteryAtMost(it)) }
        val fromH = i("constraint_time_from_hour")
        val toH = i("constraint_time_to_hour")
        if (fromH != null && toH != null) {
            val fromM = i("constraint_time_from_minute") ?: 0
            val toM = i("constraint_time_to_minute") ?: 0
            list.add(com.nik.voiceassistant.data.macro.Constraint.TimeWindow(fromH, fromM, toH, toM))
        }
        val daysStr = args["constraint_days"]?.toString()?.trim()
        if (!daysStr.isNullOrBlank()) {
            val days = daysStr.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (days.isNotEmpty()) list.add(com.nik.voiceassistant.data.macro.Constraint.Days(days))
        }
        return list
    }

    private fun parseTrigger(triggerType: String, args: Map<String, Any?>, title: String): com.nik.voiceassistant.data.macro.Trigger? {
        fun s(k: String) = args[k]?.toString()?.trim()?.ifBlank { null }
        fun i(k: String) = args[k]?.toString()?.toIntOrNull()
        fun b(k: String) = args[k]?.let { it == true || it.toString().equals("true", ignoreCase = true) }

        return when (triggerType) {
            "phrase" -> com.nik.voiceassistant.data.macro.Trigger.Phrase(s("trigger_phrase") ?: title)
            "scheduled" -> {
                val h = i("scheduled_hour") ?: return null
                val m = i("scheduled_minute") ?: 0
                val days = s("scheduled_days")?.split(",")?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: (1..7).toSet()
                com.nik.voiceassistant.data.macro.Trigger.Scheduled(h, m, days)
            }
            "broadcast_event" -> {
                val action = s("broadcast_action") ?: return null
                val extrasJson = s("broadcast_extras") ?: "{}"
                val extras: Map<String, String> = try {
                    @Suppress("UNCHECKED_CAST")
                    com.google.gson.Gson().fromJson(extrasJson, Map::class.java) as? Map<String, String> ?: emptyMap()
                } catch (_: Exception) { emptyMap() }
                com.nik.voiceassistant.data.macro.Trigger.BroadcastEvent(action, extras, s("broadcast_label") ?: "")
            }
            "condition" -> {
                val check = s("condition_instruction") ?: return null
                val interval = i("condition_interval_minutes") ?: 10
                val stop = b("condition_stop_on_met") ?: true
                com.nik.voiceassistant.data.macro.Trigger.Condition(check, interval, stop, s("condition_label") ?: "")
            }
            "webhook" -> {
                val path = s("webhook_path") ?: return null
                com.nik.voiceassistant.data.macro.Trigger.Webhook(path, s("webhook_secret"))
            }
            "notification" -> com.nik.voiceassistant.data.macro.Trigger.NotificationReceived(
                s("notification_package"), s("notification_app_label"),
                s("notification_title_contains"), s("notification_text_contains")
            )
            "incoming_call" -> com.nik.voiceassistant.data.macro.Trigger.IncomingCall(s("call_contact_filter"), s("call_contact_label"))
            "missed_call"   -> com.nik.voiceassistant.data.macro.Trigger.MissedCall(s("call_contact_filter"), s("call_contact_label"))
            "charger_connected"    -> com.nik.voiceassistant.data.macro.Trigger.ChargerConnected
            "charger_disconnected" -> com.nik.voiceassistant.data.macro.Trigger.ChargerDisconnected
            "wifi_connected" -> com.nik.voiceassistant.data.macro.Trigger.WifiConnected(s("wifi_ssid"))
            "boot"   -> com.nik.voiceassistant.data.macro.Trigger.BootCompleted
            "manual" -> com.nik.voiceassistant.data.macro.Trigger.Manual
            else -> null
        }
    }

    private fun saveAndArmMacro(macro: com.nik.voiceassistant.data.macro.Macro, trigger: com.nik.voiceassistant.data.macro.Trigger) {
        com.nik.voiceassistant.data.macro.MacroStore.save(context, macro)
        when (trigger) {
            is com.nik.voiceassistant.data.macro.Trigger.Scheduled ->
                com.nik.voiceassistant.service.ScheduledTriggerMonitor.scheduleOne(context, macro)
            is com.nik.voiceassistant.data.macro.Trigger.Condition ->
                com.nik.voiceassistant.service.ConditionPollingEngine.scheduleOne(context, macro)
            is com.nik.voiceassistant.data.macro.Trigger.BroadcastEvent ->
                com.nik.voiceassistant.service.GenericBroadcastMonitor.refresh(context) { null }
            else -> {}
        }
    }

    private fun createAutomation(title: String, triggerType: String, instruction: String, args: Map<String, Any?>): Map<String, Any?> {
        val trigger = parseTrigger(triggerType, args, title)
            ?: return mapOf("error" to "Invalid parameters for trigger_type '$triggerType'")
        val constraints = parseConstraints(args) ?: emptyList()

        val macro = com.nik.voiceassistant.data.macro.Macro(
            title = title,
            triggers = listOf(trigger),
            constraints = constraints,
            instruction = instruction,
            action = com.nik.voiceassistant.data.macro.Action.AlwaysAsk
        )
        saveAndArmMacro(macro, trigger)

        if (trigger is com.nik.voiceassistant.data.macro.Trigger.Webhook) {
            val ip = com.nik.voiceassistant.service.WebhookServer.localAddress(context) ?: "PHONE_IP"
            return mapOf("success" to true, "id" to macro.id, "trigger" to trigger.describe(),
                "webhook_url" to "http://$ip:${com.nik.voiceassistant.service.WebhookServer.PORT}/webhook/${trigger.path}")
        }

        return mapOf("success" to true, "id" to macro.id, "trigger" to trigger.describe(),
            "next" to "Automation '${title}' created and armed. Tell the user it's saved and active.")
    }

    private fun registerWebhook(path: String, instruction: String, label: String, secret: String?): Map<String, Any?> {
        val macro = com.nik.voiceassistant.data.macro.Macro(
            title = label.ifBlank { instruction.take(60) },
            description = "Webhook trigger: /$path",
            triggers = listOf(
                com.nik.voiceassistant.data.macro.Trigger.Webhook(path = path, secret = secret?.ifBlank { null }, label = label)
            ),
            instruction = instruction,
            action = com.nik.voiceassistant.data.macro.Action.AlwaysAsk
        )
        com.nik.voiceassistant.data.macro.MacroStore.save(context, macro)
        val ip = com.nik.voiceassistant.service.WebhookServer.localAddress(context) ?: "PHONE_IP"
        val url = "http://$ip:${com.nik.voiceassistant.service.WebhookServer.PORT}/webhook/$path"
        val curl = "curl -X POST \"$url\"" + if (!secret.isNullOrBlank()) " -H \"X-Webhook-Token: $secret\"" else ""
        return mapOf(
            "success" to true,
            "webhook_url" to url,
            "curl_example" to curl,
            "note" to "Webhook registered. Only reachable on the same Wi-Fi or Tailscale network, not from the internet. " +
                "Tell the user: URL = $url" + if (!secret.isNullOrBlank()) ", token = $secret" else ""
        )
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
        val answer = ask(prompt) ?: return researchViaBrowser(question)
        planEngine.plan.value?.tried?.add("research")
        return mapOf("answer" to answer, "next" to "Follow it on the screen; names may differ slightly, so match what read_screen shows.")
    }

    /**
     * When accessibility capture returned nothing (WebView-heavy apps, some system screens), takes a
     * screenshot and asks the vision model to describe it, appending the result to the screen map.
     * Returns the original map unchanged when elements are already present or vision isn't available.
     */
    private fun augmentWithScreenshot(agent: PhoneAgentService?, screen: Map<String, Any?>): Map<String, Any?> {
        if (agent == null) return screen
        @Suppress("UNCHECKED_CAST")
        val elements = screen["elements"] as? List<*>
        if (!elements.isNullOrEmpty()) return screen
        // Use screenVerifier.captureJpeg() when available, else fall back to direct capture
        val jpeg = if (screenVerifier != null) {
            kotlinx.coroutines.runBlocking { screenVerifier.captureJpeg() }
        } else {
            try { agent.screenshotJpeg(hideOverlay = true) } catch (_: Exception) { null }
        } ?: return screen
        val describe = describeImage ?: return screen
        val seen = describe.invoke(jpeg,
            "Describe what is on this Android phone screen: the app name, visible text, buttons, and any dialogs. " +
            "Be brief and factual, no markdown, under 6 lines.") ?: return screen
        return screen + mapOf(
            "visual_description" to seen,
            "note" to "Accessibility returned no elements; this description is from a screenshot — use it to decide what to do next."
        )
    }

    /**
     * Fallback when the gateway research call fails: opens a Google search in the default browser,
     * waits for it to load, takes a screenshot, and uses the vision model to extract the answer.
     */
    private fun researchViaBrowser(question: String): Map<String, Any?> {
        val agent = PhoneAgentService.instance
        val describe = describeImage
        if (agent == null || describe == null) {
            return mapOf("error" to "Research failed; open_website with 'google.com/search?q=YOUR_QUESTION' to search manually, then look_at_screen")
        }
        return try {
            val searchUrl = "https://www.google.com/search?q=${Uri.encode(question)}"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            Thread.sleep(3500)
            val jpeg = agent.screenshotJpeg(hideOverlay = false)
                ?: return mapOf("error" to "Research failed; browser opened for '$question' but screenshot unavailable — look_at_screen to read results.")
            val seen = describe(jpeg,
                "This is a Google search results page on an Android phone. Search query: \"$question\". " +
                "Extract and summarize the key factual information shown. Plain text only, at most 10 short lines.")
                ?: return mapOf("error" to "Research failed; browser opened for '$question' but couldn't read results — use look_at_screen.")
            planEngine.plan.value?.tried?.add("research")
            mapOf(
                "answer" to seen,
                "source" to "browser_search",
                "next" to "Answer came from a live browser search (gateway unavailable). The browser is now open; navigate back or home when done."
            )
        } catch (e: Exception) {
            mapOf("error" to "Research failed; try open_website with a Google search URL manually, then look_at_screen")
        }
    }

    /**
     * Adds a warning when actions stop making progress: the same screen after several actions in a
     * row, or the same call over and over. It tells the model to change approach instead of looping.
     */
    private fun withProgressCheck(toolName: String, args: Map<String, Any?>, result: Map<String, Any?>): Map<String, Any?> {
        if (isTvDevice) return result
        planEngine.plan.value?.tried?.add(toolName)
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
        val untried = approaches.filterKeys { it !in (planEngine.plan.value?.tried ?: emptySet<String>()) }.values.toList().ifEmpty { approaches.values.toList() }
        val why = if (repeats >= 3) "You have made this same call $repeats times" else "The screen hasn't changed after the last ${sameScreenStreak + 1} actions"
        return result + mapOf(
            "progress_warning" to "$why: direct action is not progressing. Do NOT give up (kabhi bhi haar nahi manna hai)! Immediately call research or open_website with Google search for exact steps on this phone ({DEVICE}), update_plan with the researched steps, and follow them.",
            "untried_approaches" to untried
        )
    }

    /** Clears the plan and progress tracking (a new conversation). */
    fun resetPlan() {
        planEngine.reset()
        sameScreenStreak = 0
        lastScreenHash = 0
        recentCalls.clear()
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
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        @Suppress("DEPRECATION")
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0)) +
                packageManager.queryIntentActivities(leanback, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(launcher, 0) +
                packageManager.queryIntentActivities(leanback, 0)
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
        val intent = packageManager.getLeanbackLaunchIntentForPackage(packageName)
            ?: packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        Log.i(TAG, "Launched package: $packageName")
        return true
    }

    private fun launchIntent(action: String?, uri: String?, pkg: String?, extrasJson: String?, trusted: Boolean): Map<String, Any?> {
        if (action == null && uri == null && pkg == null) return mapOf("error" to "Give at least an action, uri or package")
        // Uninstalling / deleting this app itself
        val removing = action != null && (action.contains("DELETE") || action.contains("UNINSTALL"))
        if (removing && (uri?.contains(context.packageName) == true || pkg == context.packageName)) return mapOf("error" to SELF_BLOCKED)
        // Intents that act on their own go through the same checks as the buttons that do the same
        val tel = uri?.takeIf { it.startsWith("tel:", ignoreCase = true) }?.let { Uri.decode(it.substringAfter(':')) }
        if (action == Intent.ACTION_DIAL && tel != null) lastDialed = tel
        val placesCall = action != null && action.startsWith("android.intent.action.CALL")
        when {
            placesCall -> {
                val risk = if (ActionGuard.isDialCode(tel)) ActionGuard.Risk.DIAL_CODE else ActionGuard.Risk.CALL
                gate(risk, "call:${digits(tel.orEmpty())}", "Call ${tel ?: "this number"}", trusted)?.let { return it }
            }
            removing -> gate(ActionGuard.Risk.DELETE, "intent:$action:$uri", "Uninstall or delete ${uri ?: pkg ?: "this"}", trusted)?.let { return it }
        }
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
        // Started directly (not checked with resolveActivity first): without QUERY_ALL_PACKAGES most
        // apps are invisible to that check, but starting them still works
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            return mapOf("success" to false, "error" to "No app on this phone handles that intent; try another action/URI or open the app and use the screen")
        } catch (e: SecurityException) {
            return mapOf("success" to false, "error" to "That screen can't be opened by other apps; open it through the app's own menus instead")
        }
        return withScreen(buildMap {
            put("success", true)
            put("launched", listOfNotNull(action, uri, pkg).joinToString(" "))
            app?.let { put("opened_in", it) }
        })
    }

    /**
     * Opens this phone's Settings search, types the query and returns the results. The search screen
     * is whatever activity handles Settings' search action on this phone (found at runtime); without
     * one, Settings opens and its own search field is used.
     */
    private fun searchSettings(agent: PhoneAgentService, query: String): Map<String, Any?> {
        if (query.isBlank()) return mapOf("error" to "Give the setting's name")
        val search = Intent("com.android.settings.action.SETTINGS_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val opened = search.resolveActivity(packageManager) != null && runCatching { context.startActivity(search) }.isSuccess
        if (!opened) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            agent.screenAfterLaunch()
            // Settings' own search box (or the button that opens it)
            agent.tap(null, "Search", longPress = false)
        }
        agent.screenAfterLaunch()
        val typed = agent.typeText(query, null, submit = true)
        if (typed["error"] != null) return typed + ("note" to "Settings search didn't take the text; tap its search box (read_screen) and use type_text")
        // Results come in as you type; give them a moment
        Thread.sleep(700)
        return mapOf(
            "success" to true,
            "done" to "searched Settings for \"$query\"",
            "next" to "Tap the result that matches best (its path is shown under it). If nothing matches, research the setting's name on this phone.",
            "screen" to agent.readScreen()
        )
    }

    /**
     * Cleans conversational words, verbs, speech recognition artifacts (like 'achaar' for 'achha sa'),
     * and platform names to yield a razor-sharp search query for YouTube / TV apps.
     */
    private fun cleanTvQuery(rawQuery: String): String {
        var q = rawQuery.trim()
        // Common speech recognition phonetic fixes
        q = q.replace(Regex("(?i)\\bnine\\s*x\\b"), "9x")
        q = q.replace(Regex("(?i)\\bzee\\s*ciman[a-z]*\\b"), "zee cinema")
        q = q.replace(Regex("(?i)\\bzee\\s*cinam[a-z]*\\b"), "zee cinema")
        // Strip out target platform phrases
        q = q.replace(Regex("(?i)\\b(on\\s+)?(youtube|jiocinema|jio\\s+cinema|jiotv\\+?|jio\\s+tv\\+?|live\\s+tv|hotstar|yt|tv)\\s*(par|pe|me|mein)?\\b"), " ")
        // Strip out action verbs & commands
        q = q.replace(Regex("(?i)\\b(lagade|laga\\s+do|laga\\s+de|lagao|lagana|lagane|lagaye|lagan|laga|chala\\s+do|chala\\s+de|chalao|chalana|chalaye|chalane|chala|bajao|baja\\s+do|baja\\s+de|bajade|sunao|suna\\s+do|suna\\s+de|sunade|play\\s+karo|play\\s+kar\\s+do|play|khol\\s+do|khol|kholo|open\\s+karo|open|search\\s+karo|search|dhundo|dikhaye|dikhao)\\b"), " ")
        // Strip out fillers & speech recognition noise (including misrecognized 'rao' for 'chalao' or 'lao')
        q = q.replace(Regex("(?i)\\b(achaar|achha\\s+sa|achha\\s+wala|achhe|badhiya\\s+sa|badhiya|koi\\s+bhi|koi|ek|zara|thoda|kripya|please|bhai|yaar|bhi|rao|lao)\\b"), " ")
        // Strip out dangling prepositions at ends
        q = q.replace(Regex("(?i)^\\s*(ke|ka|ki|ko|pe|par|me|mein)\\s+"), " ")
        q = q.replace(Regex("(?i)\\s+(ke|ka|ki|ko|pe|par|me|mein)\\s*$"), " ")
        q = q.replace(Regex("\\s+"), " ").trim()
        return q.ifBlank { rawQuery.trim() }
    }

    /**
     * Direct Android TV search intent: opens YouTube TV, Live TV (JioTV+), JioCinema, Hotstar, or Android TV global search.
     * Automatically clicks and plays the top search result without blocking or leaving on the results page.
     */
    private data class ResolvedYtVideo(val videoId: String, val title: String, val playUri: String)

    private fun resolveYouTubeVideo(query: String, cardIndex: Int = 1): ResolvedYtVideo? {
        val candidates = mutableListOf(query.trim())
        val words = query.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }
        if (words.size > 2) {
            candidates.add(words.dropLast(1).joinToString(" "))
        }

        for (q in candidates) {
            try {
                val encoded = Uri.encode(q)
                val uri = Uri.parse("content://com.jio.yt.GLOBALSEARCH/search_suggest_query/$encoded")
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use { c ->
                    val count = c.count
                    if (count > 0) {
                        val targetRow = (cardIndex - 1).coerceIn(0, count - 1)
                        if (c.moveToPosition(targetRow)) {
                            val videoIdCol = c.getColumnIndex("videoId")
                            val intentDataCol = c.getColumnIndex("suggest_intent_data")
                            val text1Col = c.getColumnIndex("suggest_text_1")

                            val videoId = if (videoIdCol >= 0) c.getString(videoIdCol) else null
                            val intentData = if (intentDataCol >= 0) c.getString(intentDataCol) else null
                            val title = if (text1Col >= 0) c.getString(text1Col) ?: q else q

                            val effectiveVideoId = videoId
                                ?: (if (!intentData.isNullOrBlank()) Uri.parse(intentData).getQueryParameter("videoId") else null)

                            if (!effectiveVideoId.isNullOrBlank()) {
                                Log.i(TAG, "Resolved YouTube video via GLOBALSEARCH: '$title' ($effectiveVideoId) for query '$q'")
                                return ResolvedYtVideo(
                                    videoId = effectiveVideoId,
                                    title = title,
                                    playUri = "https://www.youtube.com/watch?v=$effectiveVideoId"
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed resolving query '$q' in GLOBALSEARCH: ${e.message}")
            }
        }
        return null
    }

    private fun verifyMediaPlayback(expectedPackages: List<String>, timeoutMs: Long = 4000L): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val startTime = System.currentTimeMillis()

        // Allow player at least 1200ms to initialize and buffer
        Thread.sleep(1200)

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val activePkg = PhoneAgentService.instance?.rootInActiveWindow?.packageName?.toString()
            val pkgMatches = expectedPackages.any { it.equals(activePkg, ignoreCase = true) }

            val isMusicActive = am?.isMusicActive == true
            val hasMediaTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    am?.activePlaybackConfigurations?.any { config ->
                        config.audioAttributes.usage == android.media.AudioAttributes.USAGE_MEDIA
                    } == true
                } catch (_: Exception) {
                    false
                }
            } else {
                false
            }

            if (pkgMatches && (isMusicActive || hasMediaTrack)) {
                Log.d(TAG, "Media playback verified active for $activePkg (musicActive=$isMusicActive, hasMediaTrack=$hasMediaTrack)")
                return true
            }

            Thread.sleep(250)
        }

        Log.w(TAG, "verifyMediaPlayback timed out. No playback verified for $expectedPackages")
        return false
    }

    private fun tuneJioTvChannel(channelDigits: String?, channelDisplay: String): Map<String, Any?> {
        com.nik.voiceassistant.service.TvHudOverlay.hide()

        val uriStr = if (!channelDigits.isNullOrBlank()) {
            "jiotvplus://open.classicplayer?cid=$channelDigits&source=VA"
        } else {
            "jiotvplus://open.classicplayer"
        }

        val tuneUri = Uri.parse(uriStr)
        val tuneIntent = Intent(Intent.ACTION_VIEW, tuneUri).apply {
            setPackage("com.jio.media.jiotvplus")
            putExtra("APP_NAME", "jiotv+")
            putExtra("clickSource", "channelLaunch")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        try {
            context.startActivity(tuneIntent)
            com.nik.voiceassistant.service.TvPlaybackStateManager.saveChannel(context, channelDigits, channelDisplay)
        } catch (e: Exception) {
            Log.e(TAG, "Primary JioTV+ tuneIntent failed, trying app scheme fallback", e)
            try {
                val appUriStr = if (!channelDigits.isNullOrBlank()) {
                    "app://open.classicplayer?cid=$channelDigits&source=VA"
                } else {
                    "app://open.classicplayer"
                }
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Uri.parse(appUriStr)).apply {
                    setPackage("com.jio.media.jiotvplus")
                    putExtra("APP_NAME", "jiotv+")
                    putExtra("clickSource", "channelLaunch")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                return mapOf("success" to false, "error" to "JioTV+ open nahi ho paya: ${e2.message}")
            }
        }

        val verified = verifyMediaPlayback(
            expectedPackages = listOf("com.jio.media.jiotvplus", "com.jio.media.livetv"),
            timeoutMs = 4000L
        )

        com.nik.voiceassistant.service.TvHudOverlay.hide()

        return if (verified) {
            mapOf("success" to true, "opened" to "Live TV tuned to $channelDisplay")
        } else {
            mapOf("success" to false, "error" to "Channel $channelDisplay playback verify nahi ho paya")
        }
    }

    private fun isSongOrMusicQuery(query: String): Boolean {
        val lower = query.lowercase().trim()
        val musicKeywords = listOf(
            "song", "songs", "gana", "gaana", "geet", "music", "bhajan", "aarti",
            "remix", "dj", "track", "rap", "mashup", "dhol", "bhojpuri gana",
            "sad song", "romantic song", "party song", "audio", "mp3", "kirtan"
        )
        if (musicKeywords.any { lower.contains(it) }) return true

        // Known viral creators / YouTube channels
        val creatorKeywords = listOf(
            "carryminati", "mrbeast", "bhuvan bam", "bb ki vines", "ashish chanchlani",
            "round2hell", "amit bhadana", "vlog", "recipe", "standup comedy", "standup"
        )
        return creatorKeywords.any { lower.contains(it) }
    }

    private data class StbContentCandidate(
        val title: String,
        val subtitle: String,
        val contentId: String,
        val intentAction: String,
        val isChannel: Boolean,
        val score: Double
    )

    private fun calculateMatchScore(query: String, title: String): Double {
        val qNorm = query.lowercase().replace(Regex("[^a-z0-9]"), " ").replace(Regex("\\s+"), " ").trim()
        val tNorm = title.lowercase().replace(Regex("[^a-z0-9]"), " ").replace(Regex("\\s+"), " ").trim()
        if (qNorm.isEmpty() || tNorm.isEmpty()) return 0.0

        if (qNorm == tNorm) return 1.0
        if (tNorm.startsWith(qNorm)) return 0.95
        if (tNorm.contains(qNorm)) return 0.90
        if (qNorm.contains(tNorm)) return 0.85

        val qTokens = qNorm.split(" ").filter { it.length > 1 }.toSet()
        val tTokens = tNorm.split(" ").filter { it.length > 1 }.toSet()
        if (qTokens.isEmpty() || tTokens.isEmpty()) return 0.0

        val intersection = qTokens.intersect(tTokens).size
        val diceScore = (2.0 * intersection) / (qTokens.size + tTokens.size)

        // Number check: if query has digits (e.g. "2" in "stree 2"), require or boost matching digits
        val qDigits = Regex("\\b\\d+\\b").findAll(qNorm).map { it.value }.toSet()
        val tDigits = Regex("\\b\\d+\\b").findAll(tNorm).map { it.value }.toSet()
        val digitMatchBonus = if (qDigits.isNotEmpty()) {
            if (qDigits == tDigits) 0.15 else -0.30
        } else 0.0

        return (diceScore + digitMatchBonus).coerceIn(0.0, 1.0)
    }

    private fun searchGlobalStbContent(cleanQuery: String): StbContentCandidate? {
        val candidatesToTry = mutableListOf(cleanQuery)
        val cleanNoMovie = cleanQuery.replace(Regex("(?i)\\b(movie|film|picture|web series|series|show|serial)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (cleanNoMovie.isNotBlank() && cleanNoMovie != cleanQuery) {
            candidatesToTry.add(cleanNoMovie)
        }

        val uri = Uri.parse("content://com.jio.media.jiotvplus")
        var bestCandidate: StbContentCandidate? = null

        for (q in candidatesToTry) {
            try {
                val cursor = context.contentResolver.query(uri, null, null, arrayOf(q), null)
                cursor?.use { c ->
                    val text1Col = c.getColumnIndex("suggest_text_1")
                    val text2Col = c.getColumnIndex("suggest_text_2")
                    val actionCol = c.getColumnIndex("suggest_intent_action")

                    while (c.moveToNext()) {
                        val title = if (text1Col >= 0) c.getString(text1Col) ?: "" else ""
                        val subtitle = if (text2Col >= 0) c.getString(text2Col) ?: "" else ""
                        val intentAction = if (actionCol >= 0) c.getString(actionCol) ?: "" else ""

                        val rawId = intentAction.substringAfterLast("/")
                        val isChannel = rawId.length in 5..8 && rawId.all { it.isDigit() }
                        val score = calculateMatchScore(cleanNoMovie.ifBlank { cleanQuery }, title)

                        Log.i(TAG, "GlobalSearch Candidate: '$title' ($rawId, isChannel=$isChannel) score=$score")

                        if (bestCandidate == null || score > bestCandidate!!.score) {
                            bestCandidate = StbContentCandidate(
                                title = title,
                                subtitle = subtitle,
                                contentId = rawId,
                                intentAction = intentAction,
                                isChannel = isChannel,
                                score = score
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Global search query failed for '$q': ${e.message}")
            }

            if (bestCandidate != null && bestCandidate!!.score >= 0.70) {
                break
            }
        }

        return bestCandidate?.takeIf { it.score >= 0.30 }
    }

    private fun searchTv(query: String, targetApp: String?, autoPlay: Boolean = false, explicitChannel: String? = null): Map<String, Any?> {
        if (query.isBlank()) return mapOf("error" to "Give a search query")
        val lowerQ = query.lowercase().trim()
        val cleanQuery = cleanTvQuery(query)
        val appParam = targetApp?.lowercase()?.trim()
        Log.i(TAG, "searchTv: rawQuery='$query', targetApp='$targetApp', autoPlay=$autoPlay, cleanQuery='$cleanQuery'")

        // 1. Live TV Intent Check
        val isTvIntent = when (appParam) {
            "livetv", "jiotv" -> true
            "youtube", "global", "movies", "series", "shows", "jiocinema", "hotstar" -> false
            else -> JioTvChannels.isTvChannel(query) || JioTvChannels.isTvChannel(cleanQuery) || !explicitChannel.isNullOrBlank()
        }

        if (isTvIntent) {
            val isGenericLiveTv = cleanQuery.isEmpty() ||
                cleanQuery.equals("live tv", ignoreCase = true) ||
                cleanQuery.equals("tv", ignoreCase = true) ||
                cleanQuery.equals("jiotv", ignoreCase = true) ||
                cleanQuery.equals("jio tv", ignoreCase = true)

            if (isGenericLiveTv && explicitChannel.isNullOrBlank()) {
                return tuneJioTvChannel(null, "Live TV")
            }

            val channelDigits = explicitChannel?.takeIf { it.isNotBlank() }
                ?: JioTvChannels.resolveJioTvChannel(context, cleanQuery)
                ?: Regex("\\b(\\d{1,4})\\b").find(cleanQuery)?.groupValues?.get(1)

            if (channelDigits == null) {
                return mapOf("success" to false, "error" to "Channel '$cleanQuery' ka channel number nahi mila. Kripya channel number bataiye.")
            }

            val channelDisplay = "$cleanQuery (Channel $channelDigits)"
            return tuneJioTvChannel(channelDigits, channelDisplay)
        }

        // 2. Global STB Search Check (Movies, Web Series, TV Shows, OTT content, or general content)
        val isGlobalSearchIntent = when (appParam) {
            "global", "movies", "series", "shows" -> true
            "youtube" -> false
            "jiocinema", "hotstar" -> false
            else -> !isSongOrMusicQuery(cleanQuery)
        }

        if (isGlobalSearchIntent) {
            com.nik.voiceassistant.service.TvHudOverlay.hide()

            val bestMatch = searchGlobalStbContent(cleanQuery)
            if (bestMatch != null) {
                Log.i(TAG, "Selected Top Matching Global Result: '${bestMatch.title}' (score=${bestMatch.score}, isChannel=${bestMatch.isChannel})")

                if (bestMatch.isChannel) {
                    return tuneJioTvChannel(bestMatch.contentId, bestMatch.title)
                }

                try {
                    val uri = Uri.parse("jiotvplus://${bestMatch.contentId}?source=VA")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.jio.media.jiotvplus")
                        putExtra("APP_NAME", "jiotv+")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    }
                    context.startActivity(intent)
                    com.nik.voiceassistant.service.TvPlaybackStateManager.saveGlobalContent(context, bestMatch.contentId, bestMatch.title)

                    if (autoPlay) {
                        Thread {
                            try {
                                Thread.sleep(1200L)
                                com.nik.voiceassistant.service.TvInputBridge.dpadCenter()
                            } catch (_: Exception) {}
                        }.start()
                    }

                    return withScreen(mapOf(
                        "success" to true,
                        "opened" to "${bestMatch.title} (STB Universal Details Card)"
                    ))
                } catch (e: Exception) {
                    Log.w(TAG, "Primary JioTV+ URI launch failed, trying action intent: ${e.message}")
                    try {
                        val action = bestMatch.intentAction.ifBlank { "search/${bestMatch.contentId}" }
                        val fallbackIntent = Intent(action).apply {
                            component = android.content.ComponentName("com.jio.media.jiotvplus", "com.jio.media.stb.ondemand.patchwall.views.MainActivity")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        context.startActivity(fallbackIntent)
                        if (autoPlay) {
                            Thread {
                                try {
                                    Thread.sleep(1200L)
                                    com.nik.voiceassistant.service.TvInputBridge.dpadCenter()
                                } catch (_: Exception) {}
                            }.start()
                        }
                        return withScreen(mapOf(
                            "success" to true,
                            "opened" to "${bestMatch.title} (STB Universal Details Card)"
                        ))
                    } catch (e2: Exception) {
                        Log.w(TAG, "Failed launching STB detail card for ${bestMatch.title}: ${e2.message}")
                    }
                }
            }

            // If no specific card matched, launch Jio STB Global Voice Search (searches across all apps)
            try {
                val gva = Intent("com.jio.media.stb.ondemand.patchwall.GVA").apply {
                    putExtra("SEARCH_KEY", cleanQuery)
                }
                context.sendBroadcast(gva)
                return withScreen(mapOf(
                    "success" to true,
                    "opened" to "Jio Global Search for \"$cleanQuery\""
                ))
            } catch (e: Exception) {
                Log.w(TAG, "GVA broadcast failed, falling back to YouTube: ${e.message}")
            }
        }

        // 3. YouTube Intent (Songs, music, bhajans, YouTube creators, clips)
        val app = appParam ?: "youtube"
        val targetCard = when {
            lowerQ.contains("second") || lowerQ.contains("dusra") || lowerQ.contains("2nd") || lowerQ.contains("do number") -> 2
            lowerQ.contains("third") || lowerQ.contains("teesra") || lowerQ.contains("3rd") || lowerQ.contains("teen number") -> 3
            lowerQ.contains("fourth") || lowerQ.contains("chautha") || lowerQ.contains("4th") -> 4
            else -> 1
        }

        if (app == "youtube") {
            com.nik.voiceassistant.service.TvHudOverlay.hide()

            // Try fast direct video playback via Jio YouTube ContentProvider
            val resolvedVideo = resolveYouTubeVideo(cleanQuery, targetCard)
            if (resolvedVideo != null) {
                Log.i(TAG, "Playing resolved YouTube video: ${resolvedVideo.title} (${resolvedVideo.videoId})")
                val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse(resolvedVideo.playUri)).apply {
                    component = android.content.ComponentName("com.jio.yt", "dev.cobalt.app.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                try {
                    context.startActivity(playIntent)
                    com.nik.voiceassistant.service.TvPlaybackStateManager.saveYouTube(context, resolvedVideo.videoId, resolvedVideo.title)
                    val verified = verifyMediaPlayback(listOf("com.jio.yt"), 4000L)
                    com.nik.voiceassistant.service.TvHudOverlay.hide()
                    return if (verified) {
                        withScreen(mapOf(
                            "success" to true,
                            "opened" to "Playing YouTube video: ${resolvedVideo.title}"
                        ))
                    } else {
                        withScreen(mapOf(
                            "success" to false,
                            "error" to "YouTube video playback start nahi ho paya"
                        ))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Direct YouTube play intent failed, falling back to standard intent: ${e.message}")
                }
            }

            // Fallback: standard YouTube intent
            val hasJioYt = packageManager.getLeanbackLaunchIntentForPackage("com.jio.yt") != null ||
                packageManager.getLaunchIntentForPackage("com.jio.yt") != null
            val fallbackIntent = if (hasJioYt) {
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$cleanQuery")).apply {
                    component = android.content.ComponentName("com.jio.yt", "dev.cobalt.app.MainActivity")
                }
            } else {
                Intent(Intent.ACTION_SEARCH).apply {
                    setPackage("com.google.android.youtube.tv")
                    putExtra("query", cleanQuery)
                }
            }.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            return try {
                context.startActivity(fallbackIntent)
                com.nik.voiceassistant.service.TvHudOverlay.hide()
                withScreen(mapOf("success" to true, "opened" to "YouTube par \"$cleanQuery\" search kar diya hai, video select kijiye"))
            } catch (e: Exception) {
                mapOf("success" to false, "error" to (e.message ?: "Failed to start YouTube"))
            }
        }

        // 4. For specific apps like jiocinema, hotstar
        val intent = when (app) {
            "jiocinema" -> Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.jiocinema.com/search/$cleanQuery")
                setPackage("com.jio.media.stb")
            }
            "hotstar" -> Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.hotstar.com/in/search?q=$cleanQuery")
                setPackage("in.startv.hotstar")
            }
            else -> Intent(Intent.ACTION_SEARCH).apply {
                putExtra("query", cleanQuery)
            }
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        com.nik.voiceassistant.service.TvHudOverlay.hide()
        return try {
            context.startActivity(intent)
            withScreen(mapOf("success" to true, "opened" to "$app search for \"$cleanQuery\""))
        } catch (e: Exception) {
            mapOf("success" to false, "error" to (e.message ?: "Failed to start TV app"))
        }
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
        try {
            context.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            return mapOf("success" to false, "error" to "No browser found")
        }
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
        @Suppress("UNCHECKED_CAST")
        val elements = (screen["elements"] as? List<String>).orEmpty()
        val appPkg = screen["app"] as? String ?: ""

        // Prefer ScreenVerifier (tier-2 vision) when available
        if (screenVerifier != null) {
            val vr = kotlinx.coroutines.runBlocking {
                screenVerifier.verify(
                    expect = question ?: "Describe what is on the screen",
                    elements = elements,
                    app = appPkg,
                    goal = plan?.goal ?: "",
                    stepIndex = (plan?.currentStep ?: 1) - 1,
                    stepTitle = "look_at_screen",
                    lastTool = "look_at_screen",
                    lastArgs = emptyMap(),
                    forceVision = true
                )
            }
            return mapOf(
                "description" to vr.evidence,
                "screen" to screen,
                "verified" to vr.verified,
                "via" to vr.via
            )
        }

        // Fallback: use describeImage lambda
        val jpeg = agent.appWindowJpeg()
            ?: return mapOf("error" to "Couldn't take a screenshot (the app may block them); use the elements list", "screen" to screen)
        val describe = describeImage ?: return mapOf("error" to "Screen description isn't available", "screen" to screen)
        val list = elements.joinToString("\n").take(6000)
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
        lastDialed = null
        val res = CallPlacer.place(context, number, speaker)
        if (!res.success) {
            return withScreen(mapOf(
                "success" to false,
                "error" to (res.error ?: "Call failed")
            ))
        }
        return withScreen(mapOf(
            "success" to true,
            "calling" to res.calling,
            "speaker" to res.speaker,
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
        // For TV apps like YouTube (com.jio.yt) that don't hook media session
        com.nik.voiceassistant.service.PhoneAgentService.instance?.let { s ->
            if (s.rootInActiveWindow?.packageName == "com.jio.yt" && (action == "pause" || action == "stop" || action == "play_pause")) {
                s.dpad("play_pause")
            }
        }
        Thread.sleep(300)
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
        return when {
            matches.isEmpty() -> mapOf(
                "contacts" to emptyList<String>(),
                "count" to 0,
                "note" to "No contact matches '$name'. Ask the user to clarify the contact name or provide a phone number."
            )
            matches.size > 1 -> mapOf(
                "contacts" to matches.toList(),
                "count" to matches.size,
                "multiple_matches" to true,
                "next" to "Multiple contacts found! DO NOT guess or call blindly. Call show_choices with these contact options so the user picks the exact one before creating the automation or placing the call."
            )
            else -> mapOf(
                "contacts" to matches.toList(),
                "count" to 1,
                "match" to matches.first()
            )
        }
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
            // Android 12 asks for the Bluetooth permission at runtime; without it, the settings page instead
            val allowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && allowed) {
                @android.annotation.SuppressLint("MissingPermission") // checked just above
                val done = if (enabled) adapter.enable() else adapter.disable()
                if (done) "Bluetooth ${if (enabled) "enabled" else "disabled"}" else "Bluetooth didn't switch; open Bluetooth settings and tap the switch"
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
