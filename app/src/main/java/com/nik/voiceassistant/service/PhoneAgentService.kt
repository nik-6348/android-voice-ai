package com.nik.voiceassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import com.nik.voiceassistant.security.Redactor
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets the voice model see and drive the phone: reads the current screen as a numbered list of
 * elements and taps, types, scrolls and navigates on them. Nothing here knows about any app;
 * the model decides what to do from what is on screen.
 *
 * The user enables it once in Settings > Accessibility. While enabled, the app may also start
 * activities from the background (Android exempts bound accessibility services).
 */
class PhoneAgentService : AccessibilityService() {

    companion object {
        private const val TAG = "PhoneAgentService"
        private const val MAX_ELEMENTS = 150
        private const val MAX_LABEL = 80
        // Time for the UI to settle after an action before the screen is read again
        private const val SETTLE_MS = 350L
        // Packages that draw the lock screen's PIN pad (AOSP, Pixel, Samsung, Moto, Xiaomi…)
        private val KEYGUARD_PACKAGES = setOf("com.android.systemui", "com.android.keyguard", "com.miui.keyguard")

        @Volatile
        var instance: PhoneAgentService? = null
            private set

        // Set by the assistant overlay: true = let touches through it while a gesture plays in this
        // area of the screen (it only changes itself when the gesture actually crosses it)
        @Volatile
        var overlayPassThrough: ((Boolean, android.graphics.RectF) -> Unit)? = null

        // Set by the assistant overlay: true = hide it (for a screenshot of the app underneath)
        @Volatile
        var overlayHidden: ((Boolean) -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, PhoneAgentService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    // Elements from the last screen read, addressed by their [n] in the list
    @Volatile
    private var elements: List<AccessibilityNodeInfo> = emptyList()
    // What each of those elements was (kind + label) when it was listed, to notice a changed screen
    @Volatile
    private var elementSignatures: List<String> = emptyList()

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Phone agent connected")
        // Bound at boot and after updates too: start listening for "Hi Nik" without the app being opened
        if (com.nik.voiceassistant.VoiceSession.get(this).isWakeWordEnabled.value && !VoiceAssistantService.listeningOk) bringUpListening()
    }

    /**
     * Starts "Hi Nik" listening from the background with a working mic: Android only grants the mic
     * to a foreground service started while the app is visible, so this opens the invisible
     * AssistActivity for a moment (accessibility services may start activities from the background).
     */
    fun bringUpListening() {
        Log.i(TAG, "Bringing up \"Hi Nik\" listening through the app")
        try {
            startActivity(
                Intent(this, com.nik.voiceassistant.AssistActivity::class.java)
                    .setAction(com.nik.voiceassistant.AssistActivity.ACTION_KEEP_LISTENING)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the app to start listening: ${e.message}")
        }
    }

    // Short messages shown on screen (toasts, accessibility announcements), newest last, with their time
    private val messages = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>()
    // When the action being run started: its result reports the messages shown since then
    @Volatile private var actionStartedAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { PaymentGuard.onForeground(this, it) }
            return
        }
        val isToast = event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && event.parcelableData !is android.app.Notification
        if (!isToast && event.eventType != AccessibilityEvent.TYPE_ANNOUNCEMENT) return
        val text = event.text.joinToString(" ").trim().takeIf { it.isNotEmpty() } ?: return
        messages.addLast(System.currentTimeMillis() to text.take(200))
        while (messages.size > 20) messages.pollFirst()
    }

    private fun markAction() { actionStartedAt = System.currentTimeMillis() }

    /** Messages shown since the current action started (a toast can lag the action a little). */
    private fun messagesSinceAction(): List<String> =
        messages.filter { it.first >= actionStartedAt }.map { Redactor.forModel(it.second) }.distinct()

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ---- Reading the screen ----

    /**
     * The current screen: foreground app, whether the keyboard is up, and one line per element:
     * `[n] Kind "label" (traits)`. Actionable containers take their children's text as label.
     */
    /**
     * The current screen; fast adaptive polling while screen settles.
     */
    fun readScreen(): Map<String, Any?> {
        var result = readScreenOnce()
        var tries = 0
        while (tries++ < 4 && (result["elements"] as? List<*>).isNullOrEmpty()) {
            Thread.sleep(80)
            result = readScreenOnce()
        }
        return result
    }

    private fun readScreenOnce(): Map<String, Any?> {
        val active = rootInActiveWindow
        // Every window the user can see, top-most first: the app, a floating (picture-in-picture)
        // player, split screen, an open notification shade or system dialog. The status/navigation
        // bars, the keyboard and our own overlay are left out.
        val shown = windows.filter { w ->
            when (w.type) {
                AccessibilityWindowInfo.TYPE_APPLICATION -> true
                AccessibilityWindowInfo.TYPE_SYSTEM -> w.isActive || w.isFocused
                else -> false
            }
        }.sortedByDescending { it.layer }
        if (shown.isEmpty() && active == null) return mapOf("error" to "The screen can't be read right now; try again")

        val found = mutableListOf<AccessibilityNodeInfo>()
        val lines = mutableListOf<String>()
        if (shown.isEmpty()) {
            collect(active, found, lines, null)
        } else {
            for (w in shown) {
                val root = w.root ?: continue
                val tags = buildList {
                    if (w.isInPictureInPictureMode) add("floating picture-in-picture player")
                    if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) add("system")
                    if (w.isActive) add("active")
                }
                val title = clean(w.title) ?: root.packageName?.toString()
                lines += "== Window: $title (${root.packageName}${if (tags.isEmpty()) "" else ", " + tags.joinToString(", ")}) =="
                collect(root, found, lines, null)
            }
        }
        elements = found
        elementSignatures = found.map(::signature)
        return mapOf(
            "app" to active?.packageName?.toString(),
            "locked" to isLocked(),
            "keyboard_open" to windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD },
            "elements" to lines,
            "truncated" to (found.size >= MAX_ELEMENTS)
        )
    }

    /**
     * The screen as a JPEG about 720 px wide, or null: Android 10 and older, an app that blocks
     * screenshots (banking, DRM video shows black), or the system refusing (too soon after the last).
     */
    fun screenshotJpeg(hideOverlay: Boolean = true): ByteArray? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) screenshotJpegR(hideOverlay) else null

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun screenshotJpegR(hideOverlay: Boolean): ByteArray? {
        val latch = CountDownLatch(1)
        var shot: Bitmap? = null
        if (hideOverlay) overlayHidden?.invoke(true)
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    shot = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Screenshot failed: $errorCode")
                    latch.countDown()
                }
            })
            latch.await(3, TimeUnit.SECONDS)
        } finally {
            if (hideOverlay) overlayHidden?.invoke(false)
        }
        return shot?.let { jpeg(it, 70) }
    }

    /**
     * The app on screen as a JPEG, for the model to look at after each action. Android 14+ captures
     * the app's own window (without the assistant overlay, so nothing needs hiding or flickers);
     * older versions capture the whole screen as is. Null if not possible (secure apps show black).
     */
    fun appWindowJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val window = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .let { app -> app.firstOrNull { it.isActive } ?: app.maxByOrNull { it.layer } }
            if (window != null) {
                val latch = CountDownLatch(1)
                var shot: Bitmap? = null
                takeScreenshotOfWindow(window.id, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        shot = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "Window screenshot failed: $errorCode")
                        latch.countDown()
                    }
                })
                latch.await(3, TimeUnit.SECONDS)
                shot?.let { return jpeg(it, 60) }
            }
        }
        return screenshotJpeg(hideOverlay = false)
    }

    private fun jpeg(full: Bitmap, quality: Int): ByteArray {
        val width = minOf(720, full.width)
        val scaled = Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }

    private fun collect(node: AccessibilityNodeInfo?, found: MutableList<AccessibilityNodeInfo>, lines: MutableList<String>, covered: String?) {
        if (node == null || found.size >= MAX_ELEMENTS) return
        var coveredText = covered
        if (node.isVisibleToUser) {
            val actionable = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable || node.isCheckable
            val own = ownLabel(node)
            // Plain text already shown as part of an actionable parent's label is skipped
            if (actionable || (own != null && coveredText?.contains(own) != true)) {
                val label = own ?: childText(node) ?: node.viewIdResourceName?.substringAfterLast('/')
                lines += "[${found.size}] ${kind(node)}${label?.let { " \"$it\"" } ?: ""}${traits(node)}"
                found += node
                if (actionable && label != null) coveredText = label
            }
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), found, lines, coveredText)
    }

    // Screen text goes to the AI: one-time codes and card numbers are masked when the user chose that
    private fun clean(s: CharSequence?): String? =
        s?.toString()?.replace('\n', ' ')?.replace('"', '\'')?.trim()?.let(Redactor::forModel)?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }

    private fun ownLabel(node: AccessibilityNodeInfo): String? =
        // What is typed in a password field is never read out
        if (node.isPassword) (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clean(node.hintText) else null) ?: "password field"
        else clean(node.text) ?: clean(node.contentDescription)
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clean(node.hintText) else null)
            ?: (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clean(node.paneTitle) else null)

    private fun childText(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 3) return null
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            (ownLabel(child) ?: childText(child, depth + 1))?.let { parts += it }
            if (parts.size >= 3) break
        }
        return clean(parts.joinToString(" · "))
    }

    /** What an element is, to tell whether the same [n] still means the same thing after the screen changed. */
    private fun signature(node: AccessibilityNodeInfo): String =
        kind(node) + "|" + (ownLabel(node) ?: childText(node) ?: node.viewIdResourceName.orEmpty())

    private fun kind(node: AccessibilityNodeInfo): String =
        node.className?.toString()?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: "View"

    private fun traits(node: AccessibilityNodeInfo): String {
        val t = buildList {
            if (node.isClickable) add("tap")
            if (node.isLongClickable) add("long-press")
            if (node.isEditable) add("edit")
            if (node.isScrollable) add("scroll")
            if (node.isCheckable) add(if (node.isChecked) "on" else "off")
            if (node.isSelected) add("selected")
            if (node.isFocused) add("focused")
            if (!node.isEnabled) add("disabled")
        }
        return if (t.isEmpty()) "" else " (${t.joinToString(",")})"
    }

    // ---- Acting on it ----

    /** An element by its [n] from the last read, or the first visible element showing this text. */
    private fun find(elementId: Int?, text: String?): AccessibilityNodeInfo? {
        if (elementId != null) {
            val cached = elements.getOrNull(elementId)
            if (cached != null) {
                if (cached.refresh()) return cached
                val r = Rect().also { cached.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) return cached
            }
            // Stale: read the screen again, but only use [n] if it is still the same element. The model
            // chose it from the old list; on a changed screen [n] may now be another button (Send, Delete…)
            val was = elementSignatures.getOrNull(elementId)
            readScreenOnce()
            val now = elements.getOrNull(elementId) ?: return null
            return now.takeIf { was == null || signature(it) == was }
        }
        if (text.isNullOrBlank()) return null
        val matches = rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty().filter { it.isVisibleToUser }
        return matches.firstOrNull { ownLabel(it).equals(text, ignoreCase = true) } ?: matches.firstOrNull()
    }

    private fun notFound(elementId: Int?, text: String?) =
        mapOf("error" to "Element ${elementId?.let { "[$it]" } ?: "\"$text\""} not found; call read_screen and use a current element id")

    /** Result of an action plus the screen it led to, so the model can carry on without another call. */
    private fun after(done: String, ok: Boolean = true): Map<String, Any?> {
        Thread.sleep(SETTLE_MS)
        val shown = messagesSinceAction()
        return buildMap {
            put("success", ok)
            put("done", done)
            // What the app said back (e.g. "You are now a developer!", "No need, you are already a developer")
            if (shown.isNotEmpty()) put("messages", shown)
            put("screen", readScreen())
        }
    }

    /** The label of what a tap on this element would press (its own text, or the clickable parent's). */
    fun targetLabel(elementId: Int?, text: String?): String? = target(elementId, text)?.label?.ifBlank { null } ?: text

    /**
     * Taps an element, times times in quick succession (some settings react only to repeated taps,
     * e.g. Build number 7 times). The result includes the messages the app showed meanwhile.
     */
    fun tap(elementId: Int?, text: String?, longPress: Boolean, times: Int = 1): Map<String, Any?> {
        val node = find(elementId, text) ?: return notFound(elementId, text)
        markAction()
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        // Labels are often on a child of the clickable view
        var target: AccessibilityNodeInfo? = node
        while (target != null && !(if (longPress) target.isLongClickable else target.isClickable)) target = target.parent
        val count = times.coerceIn(1, 15)
        var ok = true
        repeat(count) { i ->
            ok = (target?.performAction(action) == true || run {
                val r = Rect().also { node.getBoundsInScreen(it) }
                gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), if (longPress) 800 else 60)
            }) && ok
            if (i < count - 1) Thread.sleep(180)
        }
        // Toasts from the last taps can take a moment
        if (count > 1) Thread.sleep(400)
        return after("${if (longPress) "long-pressed" else "tapped"} ${label(target ?: node)}${if (count > 1) " $count times" else ""}", ok)
    }

    /**
     * Types into a field: the given one, else the focused one, else the only one on screen. With
     * several fields and none focused it doesn't guess: it lists them for the model to pick.
     * submit null = press enter automatically when the field looks like a search / address box.
     */
    fun typeText(text: String, elementId: Int?, submit: Boolean?): Map<String, Any?> {
        markAction()
        val given = elementId?.let { id ->
            val n = find(id, null) ?: return notFound(id, null)
            // An element inside the field, or its label: use the editable one in or around it
            n.takeIf { it.isEditable } ?: firstMatching(n) { it.isEditable } ?: generateSequence(n.parent) { it.parent }.take(3).firstNotNullOfOrNull { p -> firstMatching(p) { it.isEditable } }
                ?: return mapOf("error" to "Element [$id] isn't a text field; pick a field (marked edit) from the screen")
        }
        val node = given
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: run {
                val fields = appRoots().flatMap { root -> allMatching(root) { it.isEditable && it.isVisibleToUser } }
                if (fields.size > 1) {
                    readScreenOnce()
                    val listed = fields.map { f -> "[${elements.indexOf(f).takeIf { it >= 0 } ?: "?"}] ${fieldName(f)}" }
                    return mapOf(
                        "error" to "Several text fields are on screen; call type_text again with the element_id of the one you mean",
                        "fields" to listed
                    )
                }
                fields.firstOrNull()
            }
            ?: return mapOf("error" to "No text field found; tap the field first or pass its element_id")
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val field = fieldName(node)
        val secret = node.isPassword
        val press = submit ?: looksLikeSearch(node)
        var submitted = false
        if (ok && press && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            submitted = node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return after("typed ${if (secret) "the password" else "\"$text\""} into $field${if (submitted) " and pressed enter" else ""}", ok) +
            buildMap {
                put("field", field)
                // Its text must not be kept in logs, history or automations
                if (secret) put("sensitive", true)
                if (press && !submitted) put("note", "The enter key couldn't be pressed here: tap the search / send button")
            }
    }

    /** How a field presents itself: its hint, label or id. */
    private fun fieldName(node: AccessibilityNodeInfo): String {
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) clean(node.hintText) else null
        return listOfNotNull(hint, clean(node.contentDescription), node.viewIdResourceName?.substringAfterLast('/'))
            .distinct().joinToString(" / ").ifBlank { kind(node) }.let { "field \"$it\"" }
    }

    /** A single-line field that presents itself as search / address / URL (from its own hint and id). */
    private fun looksLikeSearch(node: AccessibilityNodeInfo): Boolean {
        if (node.isMultiLine) return false
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText?.toString().orEmpty() else ""
        val about = "$hint ${node.contentDescription ?: ""} ${node.viewIdResourceName ?: ""}"
        return Regex("(?i)search|query|find|url|address|omnibox|खोज|सर्च").containsMatchIn(about)
    }

    /** Whether pressing enter in this field (or the focused one) would send it rather than search. */
    fun submitSends(elementId: Int?): Boolean {
        val node = elementId?.let { find(it, null) } ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return true
        val field = node.takeIf { it.isEditable } ?: firstMatching(node) { it.isEditable } ?: node
        return !looksLikeSearch(field)
    }

    /** What a tap on an element would act on, for safety checks. */
    data class Target(val label: String, val rowLabel: String, val viewId: String?, val checkable: Boolean, val checked: Boolean, val app: String?)

    fun target(elementId: Int?, text: String?): Target? {
        val node = find(elementId, text) ?: return null
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        val acting = clickable ?: node
        // The row it sits in (a list item: app name + switch), for "whose button is this"
        val row = acting.parent
        // Each text once: the node, its clickable parent and that parent's children often repeat the same label
        val label = listOfNotNull(ownLabel(node), ownLabel(acting), childText(acting)).distinct().joinToString(" ")
        val rowLabel = listOfNotNull(label, row?.let { ownLabel(it) }, row?.let { childText(it) }).joinToString(" ")
        val switch = listOf(node, acting).firstOrNull { it.isCheckable }
        return Target(label, rowLabel, acting.viewIdResourceName ?: node.viewIdResourceName, switch != null, switch?.isChecked == true, rootInActiveWindow?.packageName?.toString())
    }

    /** Whether one of these names is a heading near the top of the screen (a page about that app). */
    fun screenShowsTitle(names: List<String>): Boolean {
        val top = allMatching(rootInActiveWindow) { it.isVisibleToUser && (ownLabel(it) != null) }.take(12)
        return top.any { n -> ownLabel(n)?.let { label -> names.any { label.equals(it, ignoreCase = true) } } == true }
    }

    /**
     * Scrolls a list and reports whether its content really moved (a dispatched swipe isn't proof:
     * it can land on a non-scrolling area or a list already at its end). Tries, in order, the
     * container's own accessibility scroll action, a drag inside the container, and a drag across
     * the screen, checking the content after each. With untilText, keeps going (up to maxScrolls)
     * until an element showing that text is on screen, and returns its element id.
     */
    fun scroll(direction: String, elementId: Int?, untilText: String? = null, maxScrolls: Int = 10): Map<String, Any?> {
        val dir = direction.lowercase().takeIf { it in setOf("up", "down", "left", "right") }
            ?: return mapOf("error" to "direction must be up, down, left or right")
        val wanted = untilText?.trim()?.takeIf { it.isNotEmpty() }

        if (wanted == null) {
            val (moved, how) = scrollOnce(dir, elementId)
            Thread.sleep(SETTLE_MS)
            val screen = readScreen()
            return if (moved) mapOf("success" to true, "moved" to true, "done" to "scrolled $dir ($how)", "screen" to screen)
            else mapOf(
                "success" to false, "moved" to false,
                "error" to "Nothing moved when scrolling $dir: this list is at its end in that direction, or that area doesn't scroll. " +
                    "Don't repeat it: try the other direction, a different scrollable element_id, the app's search, or another route.",
                "screen" to screen
            )
        }

        var scrolls = 0
        while (true) {
            visibleWithText(wanted)?.let { node ->
                Thread.sleep(SETTLE_MS)
                val screen = readScreen()
                val id = elements.indexOfFirst { it == node }.takeIf { it >= 0 }
                    ?: elements.indexOfFirst { n -> ownLabel(n)?.contains(wanted, ignoreCase = true) == true }.takeIf { it >= 0 }
                return mapOf("success" to true, "found" to wanted, "element_id" to id, "scrolls" to scrolls, "screen" to screen)
            }
            if (scrolls >= maxScrolls.coerceIn(1, 30)) break
            val (moved, _) = scrollOnce(dir, elementId)
            if (!moved) break // reached the end of the list
            scrolls++
            Thread.sleep(200)
        }
        return mapOf(
            "success" to false, "found" to null, "scrolls" to scrolls,
            "error" to "\"$wanted\" isn't on screen after $scrolls scroll(s) $dir (${if (scrolls < maxScrolls) "reached the end" else "gave up"}). " +
                "It may have another name here, be in the other direction, or behind a menu: use the app's search, scroll the other way, or research the path.",
            "screen" to readScreen()
        )
    }

    /** One scroll step; returns whether the content moved and how it was done. */
    private fun scrollOnce(dir: String, elementId: Int?): Pair<Boolean, String> {
        val container = scrollContainer(dir, elementId)
        val area = container ?: rootInActiveWindow
        var before = contentSignature(area)

        // 1. The list's own scroll action: exact, and unaffected by overlays or gesture timing
        if (container != null) {
            val actions = container.actionList.map { it.id }
            val directional = when (dir) {
                "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
                "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
                "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
                else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            }
            val linear = if (dir == "down" || dir == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            for (action in listOf(directional, linear).distinct().filter { it in actions }) {
                if (container.performAction(action) && changed(area, before)) return true to "list action"
            }
            before = contentSignature(area)
        }

        // 2. A finger drag inside the list, then 3. across the whole screen
        val m = resources.displayMetrics
        val screenRect = Rect(0, 0, m.widthPixels, m.heightPixels)
        val bounds = container?.let { Rect().also(it::getBoundsInScreen) }?.takeIf { it.intersect(screenRect) && it.width() > 40 && it.height() > 40 }
        for (r in listOfNotNull(bounds, screenRect).distinct()) {
            val (x1, y1, x2, y2) = dragPath(dir, r)
            if (gesture(x1, y1, x2, y2, 420) && changed(area, before)) return true to "drag"
            before = contentSignature(area)
        }
        return false to "none"
    }

    /** Drag from 80% to 20% of the area (finger moves opposite to where the view goes), centred on it. */
    private fun dragPath(dir: String, r: Rect): List<Float> {
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        return when (dir) {
            "up" -> listOf(cx, r.top + r.height() * 0.22f, cx, r.top + r.height() * 0.80f)
            "left" -> listOf(r.left + r.width() * 0.20f, cy, r.left + r.width() * 0.80f, cy)
            "right" -> listOf(r.left + r.width() * 0.80f, cy, r.left + r.width() * 0.20f, cy)
            else -> listOf(cx, r.top + r.height() * 0.80f, cx, r.top + r.height() * 0.22f)
        }
    }

    /**
     * The list to scroll: the given element (or its nearest scrollable parent), else the largest
     * visible scrollable view that can move that way, across all app windows.
     */
    private fun scrollContainer(dir: String, elementId: Int?): AccessibilityNodeInfo? {
        if (elementId != null) {
            var n = find(elementId, null)
            while (n != null && !n.isScrollable) n = n.parent
            if (n != null) return n
        }
        val horizontal = dir == "left" || dir == "right"
        val wantedAction = when (dir) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        }
        val candidates = appRoots().flatMap { root -> allMatching(root) { it.isScrollable && it.isVisibleToUser } }
        fun area(n: AccessibilityNodeInfo) = Rect().also(n::getBoundsInScreen).let { it.width().toLong() * it.height() }
        fun fits(n: AccessibilityNodeInfo): Boolean {
            val r = Rect().also(n::getBoundsInScreen)
            return if (horizontal) r.width() >= r.height() / 3 else r.height() >= r.width() / 3
        }
        return candidates.filter { n -> n.actionList.any { it.id == wantedAction } }.maxByOrNull(::area)
            ?: candidates.filter(::fits).maxByOrNull(::area)
            ?: candidates.maxByOrNull(::area)
    }

    /** Text and position of what is visible in this area; changes when its content moves. */
    private fun contentSignature(root: AccessibilityNodeInfo?): Int {
        root ?: return 0
        root.refresh()
        val sb = StringBuilder()
        val r = Rect()
        var count = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (queue.isNotEmpty() && count < 400) {
            val n = queue.removeFirst()
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(r)
                sb.append(n.text).append(n.contentDescription).append(r.top).append(',').append(r.left).append(';')
                count++
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return sb.toString().hashCode()
    }

    /** Waits briefly for the area's content to differ from before (scroll animations take a moment). */
    private fun changed(area: AccessibilityNodeInfo?, before: Int): Boolean {
        repeat(6) {
            Thread.sleep(120)
            if (contentSignature(area) != before) return true
        }
        return false
    }

    /** A visible element (in any app window) whose text or description contains this text. */
    private fun visibleWithText(text: String): AccessibilityNodeInfo? {
        val screen = resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }
        return appRoots().asSequence()
            .flatMap { it.findAccessibilityNodeInfosByText(text).orEmpty().asSequence() }
            .firstOrNull { n -> n.isVisibleToUser && Rect().also(n::getBoundsInScreen).let { it.intersect(screen) && it.height() > 4 } }
    }

    /** Roots of the app windows on screen (falls back to the active window). */
    private fun appRoots(): List<AccessibilityNodeInfo> {
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.sortedByDescending { it.layer }.mapNotNull { it.root }
        return roots.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    private fun allMatching(root: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        root?.let { queue.add(it) }
        while (queue.isNotEmpty() && out.size < 50) {
            val n = queue.removeFirst()
            if (test(n)) out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return out
    }

    /**
     * A finger swipe (direction = the way the finger moves) from an element's center, or the screen's:
     * e.g. up on an app card in recents closes that app; left/right flips pages and carousels.
     */
    fun swipe(direction: String, elementId: Int?): Map<String, Any?> {
        markAction()
        val m = resources.displayMetrics
        val (w, h) = m.widthPixels.toFloat() to m.heightPixels.toFloat()
        val (x, y) = if (elementId != null) {
            val node = find(elementId, null) ?: return notFound(elementId, null)
            Rect().also { node.getBoundsInScreen(it) }.let { it.exactCenterX() to it.exactCenterY() }
        } else w / 2 to h / 2
        val (dx, dy) = when (direction.lowercase()) {
            "up" -> 0f to -h * 0.4f
            "down" -> 0f to h * 0.4f
            "left" -> -w * 0.4f to 0f
            "right" -> w * 0.4f to 0f
            else -> return mapOf("error" to "direction must be up, down, left or right")
        }
        val ok = gesture(x, y, (x + dx).coerceIn(1f, w - 1), (y + dy).coerceIn(1f, h - 1), 200)
        return after("swiped $direction", ok)
    }

    /**
     * Turns on a switch-like button found by its label (e.g. the in-call "Speaker" button), without
     * turning it off if it is already on. Returns whether it is on now.
     */
    fun switchOn(labels: List<String>): Boolean {
        for (label in labels) {
            val node = find(null, label) ?: continue
            var target: AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            target ?: continue
            if (target.isCheckable && target.isChecked) return true
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /** Whether the phone is locked right now (screen may be on or off). */
    fun isLocked(): Boolean {
        val km = getSystemService(android.app.KeyguardManager::class.java) ?: return false
        return km.isKeyguardLocked
    }

    /** How an unlock attempt went. */
    enum class Unlock { DONE, NO_PIN, NOT_NUMERIC, NO_KEYPAD, WRONG_PIN }

    /**
     * Unlocks the phone with the user's saved credential (SecurePrefs), entirely on the device: the
     * credential is read here, never sent anywhere and never logged. Dispatches to the PIN, password
     * or pattern gesture depending on what kind of lock is saved in Settings.
     */
    fun unlockWithSavedPin(): Unlock {
        val km = getSystemService(android.app.KeyguardManager::class.java) ?: return Unlock.NO_KEYPAD
        if (!km.isKeyguardLocked) return Unlock.DONE
        return when (com.nik.voiceassistant.SecurePrefs.lockType(this)) {
            com.nik.voiceassistant.SecurePrefs.LockType.PASSWORD -> unlockWithPassword(km)
            com.nik.voiceassistant.SecurePrefs.LockType.PATTERN -> unlockWithPattern(km)
            com.nik.voiceassistant.SecurePrefs.LockType.PIN -> unlockWithPin(km)
        }
    }

    /**
     * Taps a saved numeric PIN into the lock screen's own keypad. Digits are only ever tapped in the
     * lock screen's own window (the system UI), so an app drawing a look-alike keypad over the lock
     * screen can't receive the PIN.
     */
    private fun unlockWithPin(km: android.app.KeyguardManager): Unlock {
        val pin = com.nik.voiceassistant.SecurePrefs.pin(this) ?: return Unlock.NO_PIN
        if (pin.isEmpty() || !pin.all { it.isDigit() }) return Unlock.NOT_NUMERIC

        Log.i(TAG, "Unlock: waking the screen (PIN)")
        wakeScreen()
        Thread.sleep(600)
        // Reveal the PIN pad: swipe up from the bottom (the lock screen shows the clock first)
        val m = resources.displayMetrics
        gesture(m.widthPixels / 2f, m.heightPixels * 0.85f, m.widthPixels / 2f, m.heightPixels * 0.2f, 250)
        Thread.sleep(800)

        // Check the whole keypad is there before the first digit: nothing is typed into a partial match
        if (pin.toSet().any { keypadKey(it) == null }) {
            Log.w(TAG, "Unlock: lock screen PIN pad not found")
            return Unlock.NO_KEYPAD
        }
        for (digit in pin) {
            val key = keypadKey(digit) ?: return Unlock.NO_KEYPAD
            if (!press(key)) return Unlock.NO_KEYPAD
            Thread.sleep(200)
        }
        // Many PINs auto-submit at the right length; otherwise press enter/done/continue
        Thread.sleep(500)
        repeat(2) {
            if (km.isKeyguardLocked) {
                tapKeypadEnter()
                Thread.sleep(800)
            }
        }
        val stillLocked = km.isKeyguardLocked
        Log.i(TAG, "Unlock finished; unlocked=${!stillLocked}")
        return if (stillLocked) Unlock.WRONG_PIN else Unlock.DONE
    }

    /** Sets the saved password straight into the lock screen's password field, then confirms. */
    private fun unlockWithPassword(km: android.app.KeyguardManager): Unlock {
        val password = com.nik.voiceassistant.SecurePrefs.pin(this) ?: return Unlock.NO_PIN
        if (password.isEmpty()) return Unlock.NO_PIN

        Log.i(TAG, "Unlock: waking the screen (password)")
        wakeScreen()
        Thread.sleep(600)
        val m = resources.displayMetrics
        gesture(m.widthPixels / 2f, m.heightPixels * 0.85f, m.widthPixels / 2f, m.heightPixels * 0.2f, 250)
        Thread.sleep(800)

        val field = passwordField()
        if (field == null) {
            Log.w(TAG, "Unlock: lock screen password field not found")
            return Unlock.NO_KEYPAD
        }
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password) }
        if (!field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return Unlock.NO_KEYPAD

        Thread.sleep(400)
        repeat(2) {
            if (km.isKeyguardLocked) {
                tapKeypadEnter()
                Thread.sleep(800)
            }
        }
        val stillLocked = km.isKeyguardLocked
        Log.i(TAG, "Unlock finished; unlocked=${!stillLocked}")
        return if (stillLocked) Unlock.WRONG_PIN else Unlock.DONE
    }

    /** Draws the saved pattern as one continuous gesture on the lock screen's pattern grid. */
    private fun unlockWithPattern(km: android.app.KeyguardManager): Unlock {
        val cells = com.nik.voiceassistant.SecurePrefs.pattern(this) ?: return Unlock.NO_PIN
        if (cells.size < 4 || cells.any { it !in 0..8 }) return Unlock.NOT_NUMERIC

        Log.i(TAG, "Unlock: waking the screen (pattern)")
        wakeScreen()
        Thread.sleep(600)
        val m = resources.displayMetrics
        gesture(m.widthPixels / 2f, m.heightPixels * 0.85f, m.widthPixels / 2f, m.heightPixels * 0.2f, 250)
        Thread.sleep(800)

        val bounds = patternViewBounds()
        if (bounds == null) {
            Log.w(TAG, "Unlock: lock screen pattern grid not found")
            return Unlock.NO_KEYPAD
        }
        val dots = patternDotCenters(bounds)
        val points = cells.map { dots[it] }
        if (!patternGesture(points)) return Unlock.NO_KEYPAD

        // The pattern submits itself on finger-lift; no separate confirm key
        Thread.sleep(600)
        val stillLocked = km.isKeyguardLocked
        Log.i(TAG, "Unlock finished; unlocked=${!stillLocked}")
        return if (stillLocked) Unlock.WRONG_PIN else Unlock.DONE
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        val pm = getSystemService(android.os.PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        pm.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
            "niksvoice:unlock"
        ).apply { acquire(5000); try { release() } catch (_: Exception) {} }
    }

    /** Roots of the lock screen's windows (drawn by the system UI), top-most first. */
    private fun keyguardRoots(): List<AccessibilityNodeInfo> =
        (windows.sortedByDescending { it.layer }.mapNotNull { it.root } + listOfNotNull(rootInActiveWindow))
            .distinct()
            .filter { it.packageName?.toString() in KEYGUARD_PACKAGES }

    /**
     * A key of the lock screen keypad showing exactly this digit ("2", or "2 ABC" with its letters),
     * never a node that merely contains it (a clock reading 10:41).
     */
    private fun keypadKey(digit: Char): AccessibilityNodeInfo? {
        val exact = Regex("^$digit(\\s+[A-Za-z+]*)?$")
        fun isKey(n: AccessibilityNodeInfo) = listOfNotNull(n.text, n.contentDescription).any { exact.matches(it.toString().trim()) }
        for (root in keyguardRoots()) {
            val node = root.findAccessibilityNodeInfosByText(digit.toString()).orEmpty().firstOrNull { it.isVisibleToUser && isKey(it) }
                ?: firstMatching(root) { it.isVisibleToUser && isKey(it) }
                ?: continue
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) return node
        }
        return null
    }

    /** The lock screen's editable password field, if a password (not PIN/pattern) lock is showing. */
    private fun passwordField(): AccessibilityNodeInfo? {
        for (root in keyguardRoots()) {
            val field = firstMatching(root) { it.isVisibleToUser && it.isEditable }
            if (field != null) return field
        }
        return null
    }

    /**
     * The lock screen's pattern grid, found by its class name or view id. OEMs vary the lock screen's
     * look but the pattern view itself is a single custom-drawn view (no per-dot nodes), so this finds
     * the one container rather than individual dots.
     */
    private fun patternViewBounds(): Rect? {
        for (root in keyguardRoots()) {
            val node = firstMatching(root) { n ->
                val cls = n.className?.toString()?.lowercase().orEmpty()
                val id = n.viewIdResourceName?.lowercase().orEmpty()
                n.isVisibleToUser && (cls.contains("lockpatternview") || cls.contains("patternview") || id.contains("pattern"))
            } ?: continue
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) return r
        }
        return null
    }

    /** The screen centers of a 3x3 pattern's 9 dots (index 0 = top-left, row-major), from the grid's bounds. */
    private fun patternDotCenters(bounds: Rect): List<PointF> {
        val cellW = bounds.width() / 3f
        val cellH = bounds.height() / 3f
        return (0 until 9).map { i ->
            val row = i / 3
            val col = i % 3
            PointF(bounds.left + cellW * (col + 0.5f), bounds.top + cellH * (row + 0.5f))
        }
    }

    /** One continuous finger-drag through every point in order, like drawing a lock pattern. */
    private fun patternGesture(points: List<PointF>, durationMs: Long = 700): Boolean {
        if (points.size < 2) return false
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (p in points.drop(1)) lineTo(p.x, p.y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val latch = CountDownLatch(1)
        var ok = false
        return try {
            val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (sent) latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
            ok
        } catch (e: Exception) {
            Log.w(TAG, "Pattern gesture failed: ${e.message}")
            false
        }
    }

    /** One tap on a keypad key: a real touch, or its click action if the touch couldn't be played. */
    private fun press(node: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { node.getBoundsInScreen(it) }
        if (gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)) return true
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** The lock screen's enter / OK key (by id, description or text), only in the lock screen's windows. */
    private fun tapKeypadEnter(): Boolean {
        val roots = keyguardRoots()
        if (roots.isEmpty()) return false
        val words = setOf("enter", "ok", "done", "confirm", "continue", "unlock", "next", "go")
        for (root in roots) {
            val key = firstMatching(root) { n ->
                val id = n.viewIdResourceName?.substringAfterLast('/')?.lowercase().orEmpty()
                val name = (n.contentDescription ?: n.text)?.toString()?.trim()?.lowercase().orEmpty()
                n.isVisibleToUser && (id.contains("enter") || id.contains("confirm") || id.contains("submit") || id.contains("continue") || name in words)
            } ?: continue
            if (press(key)) return true
        }
        // No labelled key: the enter key sits bottom-right of the 3x4 keypad on most lock screens
        val m = resources.displayMetrics
        val x = m.widthPixels * 0.74f
        val y = m.heightPixels * 0.805f
        return gesture(x, y, x, y, 60)
    }

    fun navigate(action: String): Map<String, Any?> {
        markAction()
        val global = when (action.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power_dialog" -> GLOBAL_ACTION_POWER_DIALOG
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else null
            "screenshot" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else null
            else -> null
        } ?: return mapOf("error" to "Unknown or unsupported navigation action '$action'")
        return after(action, performGlobalAction(global))
    }

    /** The screen after something outside this service changed it (an app or intent launch). */
    fun screenAfterLaunch(): Map<String, Any?> {
        Thread.sleep(SETTLE_MS + 400)
        return readScreen()
    }

    // A row's name is usually the text of its children ("Build number · 1.2.3"), not the row itself
    private fun label(node: AccessibilityNodeInfo) = (ownLabel(node) ?: childText(node))?.let { "\"$it\"" } ?: kind(node)

    private fun firstMatching(root: AccessibilityNodeInfo?, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        root?.let { queue.add(it) }
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (test(n)) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    /**
     * A tap (same start and end) or swipe; waits until the system has played it. Our own overlay
     * lets touches through meanwhile, so a gesture under the assistant panel reaches the app.
     */
    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val latch = CountDownLatch(1)
        var ok = false
        val pad = 24f
        val area = android.graphics.RectF(minOf(x1, x2) - pad, minOf(y1, y2) - pad, maxOf(x1, x2) + pad, maxOf(y1, y2) + pad)
        overlayPassThrough?.invoke(true, area)
        try {
            val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
            }, null)
            if (sent) latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        } finally {
            overlayPassThrough?.invoke(false, area)
        }
        return ok
    }
}
