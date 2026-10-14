package com.nik.voiceassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
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

        @Volatile
        var instance: PhoneAgentService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(context, PhoneAgentService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }

    // Elements from the last screen read, addressed by their [n] in the list
    @Volatile
    private var elements: List<AccessibilityNodeInfo> = emptyList()

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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
    fun screenshotJpeg(): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var shot: Bitmap? = null
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
        val full = shot ?: return null
        val width = minOf(720, full.width)
        val scaled = Bitmap.createScaledBitmap(full, width, full.height * width / full.width, true)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
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

    private fun clean(s: CharSequence?): String? =
        s?.toString()?.replace('\n', ' ')?.replace('"', '\'')?.trim()?.take(MAX_LABEL)?.takeIf { it.isNotEmpty() }

    private fun ownLabel(node: AccessibilityNodeInfo): String? =
        clean(node.text) ?: clean(node.contentDescription)
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
        if (elementId != null) return elements.getOrNull(elementId)?.takeIf { it.refresh() }
        if (text.isNullOrBlank()) return null
        val matches = rootInActiveWindow?.findAccessibilityNodeInfosByText(text).orEmpty().filter { it.isVisibleToUser }
        return matches.firstOrNull { ownLabel(it).equals(text, ignoreCase = true) } ?: matches.firstOrNull()
    }

    private fun notFound(elementId: Int?, text: String?) =
        mapOf("error" to "Element ${elementId?.let { "[$it]" } ?: "\"$text\""} not found; call read_screen and use a current element id")

    /** Result of an action plus the screen it led to, so the model can carry on without another call. */
    private fun after(done: String, ok: Boolean = true): Map<String, Any?> {
        Thread.sleep(SETTLE_MS)
        return mapOf("success" to ok, "done" to done, "screen" to readScreen())
    }

    /** The label of what a tap on this element would press (its own text, or the clickable parent's). */
    fun targetLabel(elementId: Int?, text: String?): String? {
        val node = find(elementId, text) ?: return text
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return listOfNotNull(ownLabel(node), target?.let { ownLabel(it) }, target?.viewIdResourceName?.substringAfterLast('/'))
            .joinToString(" ").ifBlank { null }
    }

    fun tap(elementId: Int?, text: String?, longPress: Boolean): Map<String, Any?> {
        val node = find(elementId, text) ?: return notFound(elementId, text)
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        // Labels are often on a child of the clickable view
        var target: AccessibilityNodeInfo? = node
        while (target != null && !(if (longPress) target.isLongClickable else target.isClickable)) target = target.parent
        val ok = target?.performAction(action) == true || run {
            val r = Rect().also { node.getBoundsInScreen(it) }
            gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), if (longPress) 800 else 60)
        }
        return after("${if (longPress) "long-pressed" else "tapped"} ${label(node)}", ok)
    }

    fun typeText(text: String, elementId: Int?, submit: Boolean): Map<String, Any?> {
        val node = (if (elementId != null) find(elementId, null) else null)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: firstMatching(rootInActiveWindow) { it.isEditable && it.isVisibleToUser }
            ?: return mapOf("error" to "No text field found; tap the field first or pass its element_id")
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (ok && submit && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        return after("typed \"$text\" into ${label(node)}${if (submit) " and pressed enter" else ""}", ok)
    }

    fun scroll(direction: String, elementId: Int?): Map<String, Any?> {
        val dir = direction.lowercase()
        val node = (if (elementId != null) find(elementId, null) else null)
            ?: firstMatching(rootInActiveWindow) { it.isScrollable && it.isVisibleToUser }
        val directional = when (dir) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN
        }
        val linear = if (dir == "up" || dir == "left") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        val ok = node?.performAction(directional.id) == true || node?.performAction(linear) == true || run {
            // No scrollable view reported: swipe across the middle of the screen
            val m = resources.displayMetrics
            val (w, h) = m.widthPixels.toFloat() to m.heightPixels.toFloat()
            when (dir) {
                "up" -> gesture(w / 2, h * 0.3f, w / 2, h * 0.7f, 300)
                "left" -> gesture(w * 0.2f, h / 2, w * 0.8f, h / 2, 300)
                "right" -> gesture(w * 0.8f, h / 2, w * 0.2f, h / 2, 300)
                else -> gesture(w / 2, h * 0.7f, w / 2, h * 0.3f, 300)
            }
        }
        return after("scrolled $dir", ok)
    }

    /**
     * A finger swipe (direction = the way the finger moves) from an element's center, or the screen's:
     * e.g. up on an app card in recents closes that app; left/right flips pages and carousels.
     */
    fun swipe(direction: String, elementId: Int?): Map<String, Any?> {
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

    /**
     * Unlocks the phone with the user's saved PIN (SecurePrefs), entirely on the device: the PIN is
     * read here, never sent anywhere. Wakes the screen, swipes up to the PIN pad, taps the digits and
     * confirms. Only works for a numeric PIN; pattern / password / fingerprint can't be entered this way.
     * Returns null on success, or a short reason to tell the user.
     */
    fun unlockWithSavedPin(onetimePin: String? = null): String? {
        val km = getSystemService(android.app.KeyguardManager::class.java) ?: return "No keyguard service"
        if (!km.isKeyguardLocked) return null // already unlocked
        val pin = onetimePin?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
            ?: com.nik.voiceassistant.SecurePrefs.pin(this)
            ?: return "No PIN is saved. Ask the user for their PIN, then call unlock_phone with it."
        if (!pin.all { it.isDigit() }) return "Only a numeric PIN can be entered automatically."

        Log.i(TAG, "unlockWithSavedPin started: waking screen and swiping up")
        wakeScreen()
        Thread.sleep(600)
        // Reveal the PIN pad: swipe up from the bottom (the lock screen shows the clock first)
        val m = resources.displayMetrics
        gesture(m.widthPixels / 2f, m.heightPixels * 0.85f, m.widthPixels / 2f, m.heightPixels * 0.2f, 250)
        Thread.sleep(800)

        for (digit in pin) {
            if (!tapKeypadDigit(digit)) {
                Log.w(TAG, "Failed to tap digit '$digit'")
                return "Couldn't find the PIN keypad on screen."
            }
            Thread.sleep(200)
        }
        // Many PINs auto-submit at the right length; otherwise press enter/done/continue
        Thread.sleep(500)
        if (km.isKeyguardLocked) {
            Log.i(TAG, "Phone still locked after entering digits; tapping enter/continue key")
            tapKeypadEnter()
            Thread.sleep(900)
            if (km.isKeyguardLocked) {
                Log.i(TAG, "Phone still locked; re-tapping enter/continue key")
                tapKeypadEnter()
                Thread.sleep(700)
            }
        }
        val stillLocked = km.isKeyguardLocked
        Log.i(TAG, "unlockWithSavedPin finished; stillLocked=$stillLocked")
        return if (stillLocked) "The PIN didn't unlock the phone (it may be wrong)." else null
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

    /** Taps a digit on the lock-screen keypad, found by its text or content description. */
    private fun tapKeypadDigit(digit: Char): Boolean {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            for (w in windows) { w.root?.let { if (!contains(it)) add(it) } }
        }
        for (root in roots) {
            val candidates = root.findAccessibilityNodeInfosByText(digit.toString()).orEmpty()
                .filter { it.className?.contains("Button") == true || it.className?.contains("View") == true || it.isClickable }
            val node = candidates.firstOrNull { ownLabel(it) == digit.toString() || it.contentDescription?.toString() == digit.toString() }
                ?: candidates.firstOrNull()
                ?: findByDesc(root, digit.toString())
                ?: continue

            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.width() > 0 && r.height() > 0) {
                Log.i(TAG, "Tapping digit '$digit' at screen coords (${r.exactCenterX()}, ${r.exactCenterY()}) [bounds=$r]")
                // Use physical touch gesture first (hardware-level screen tap)
                val gestureOk = gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
                if (gestureOk) {
                    return true // Return immediately so digit is NOT tapped twice
                }
                // Fallback only if physical gesture wasn't dispatched
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                val clickOk = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                return clickOk
            }
        }
        return false
    }

    private fun tapKeypadEnter(): Boolean {
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            for (w in windows) { w.root?.let { if (!contains(it)) add(it) } }
        }
        val words = listOf("continue", "enter", "done", "ok", "confirm", "unlock", "next", "go", "check")

        // 1. Search across all windows for matching view ID, content description, or text
        for (root in roots) {
            // Check direct resource IDs
            val enterById = firstMatching(root) { n ->
                val res = n.viewIdResourceName?.lowercase() ?: ""
                res.contains("enter") || res.contains("continue") || res.contains("confirm") || res.contains("submit")
            }
            if (enterById != null) {
                val r = Rect().also { enterById.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    Log.i(TAG, "Found enter/continue key by ID: ${enterById.viewIdResourceName} at $r")
                    val gestureOk = gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
                    if (!gestureOk) {
                        enterById.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    return true
                }
            }

            // Check content description
            val enterByDesc = firstMatching(root) { n ->
                val desc = n.contentDescription?.toString()?.lowercase() ?: ""
                words.any { desc.contains(it) }
            }
            if (enterByDesc != null) {
                val r = Rect().also { enterByDesc.getBoundsInScreen(it) }
                if (r.width() > 0 && r.height() > 0) {
                    Log.i(TAG, "Found enter/continue key by desc: ${enterByDesc.contentDescription} at $r")
                    val gestureOk = gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
                    if (!gestureOk) {
                        enterByDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    return true
                }
            }

            // Check text
            for (word in words) {
                val candidates = root.findAccessibilityNodeInfosByText(word).orEmpty()
                for (node in candidates) {
                    val r = Rect().also { node.getBoundsInScreen(it) }
                    if (r.width() > 0 && r.height() > 0) {
                        Log.i(TAG, "Found enter/continue key by text '$word' at $r")
                        val gestureOk = gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 60)
                        if (!gestureOk) {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        return true
                    }
                }
            }
        }

        // 2. Geometrical keypad fallback:
        // On Android keypad layout (3 cols x 4 rows), the Enter/Continue key is bottom-right.
        val m = resources.displayMetrics
        val fallbackX = m.widthPixels * 0.74f
        val fallbackY = m.heightPixels * 0.805f
        Log.i(TAG, "No enter key node found in accessibility tree; tapping bottom-right keypad coords ($fallbackX, $fallbackY)")
        return gesture(fallbackX, fallbackY, fallbackX, fallbackY, 60)
    }

    private fun findByDesc(root: AccessibilityNodeInfo?, text: String): AccessibilityNodeInfo? =
        firstMatching(root) { it.contentDescription?.toString()?.equals(text, ignoreCase = true) == true }

    fun navigate(action: String): Map<String, Any?> {
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

    private fun label(node: AccessibilityNodeInfo) = ownLabel(node)?.let { "\"$it\"" } ?: kind(node)

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

    /** A tap (same start and end) or swipe; waits until the system has played it. */
    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(gestureDescription: GestureDescription?) { latch.countDown() }
        }, null)
        if (sent) latch.await(durationMs + 2000, TimeUnit.MILLISECONDS)
        return ok
    }
}
