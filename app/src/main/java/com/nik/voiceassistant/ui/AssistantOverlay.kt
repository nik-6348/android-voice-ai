package com.nik.voiceassistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nik.voiceassistant.ConnectionStatus
import com.nik.voiceassistant.MainActivity
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.service.PhoneAgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Assistant panel over whatever app is open while a conversation runs and Nik's own screen isn't
 * showing, in the style of Gemini's overlay: a floating dark card with the sparkle, what the user
 * said, the reply as it is spoken, the assistant's plan and current thinking, options to pick, and
 * a bottom bar with the voice level, stop and the mic. The mic is off while the assistant works.
 * A soft Gemini-coloured glow rises from the bottom edge with the voice and while it works.
 *
 * The card can be dragged anywhere by its top (or the collapsed pill), and remembers where it was
 * left. Only the card takes touches, and it lets the assistant's own taps and swipes through.
 */
class AssistantOverlay(private val session: VoiceSession) {

    companion object {
        private const val TAG = "AssistantOverlay"
        private val BLUE = Color(0xFF4285F4)
        private val PURPLE = Color(0xFF9B72CB)
        private val PINK = Color(0xFFD96570)
        private val CYAN = Color(0xFF5BC0EB)
        private val GEMINI = listOf(BLUE, PURPLE, PINK, BLUE)
        private val SPARKLE = listOf(Color(0xFF1C7DFF), Color(0xFF9B72CB), Color(0xFFF06C8F))
        private val CARD_TOP = Color(0xFA232427)
        private val CARD_BOTTOM = Color(0xFA1A1B1E)
        private val SURFACE = Color(0xFF2D2E31)
        private val TEXT = Color(0xFFE3E3E3)
        private val MUTED = Color(0xFF9AA0A6)
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
        private const val PREF_W = "overlay_width"
        private const val PREF_H = "overlay_reply_height"
        private const val MIN_WIDTH_DP = 260
        private const val MIN_REPLY_DP = 48f
        // Reply area height until the user resizes it (4 lines); fixed, so streaming text doesn't resize the window
        private const val DEFAULT_REPLY_DP = 92f
        private const val PREF_MINI = "overlay_mini"
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = session.context.getSharedPreferences("voice_assistant_settings", Context.MODE_PRIVATE)
    private var owner: OverlayOwner? = null
    private var glow: ComposeView? = null
    private var panel: ComposeView? = null
    private var panelParams: WindowManager.LayoutParams? = null

    fun bind(scope: CoroutineScope, appVisible: StateFlow<Boolean>) {
        scope.launch {
            combine(session.isRecording, appVisible) { live, visible -> live && !visible }
                .distinctUntilChanged()
                .collect { if (it) show() else hideAll() }
        }
        PhoneAgentService.overlayPassThrough = ::setPassThrough
        PhoneAgentService.overlayHidden = ::setHidden
    }

    // ---- Windows ----

    private fun overlayContext(): Context? =
        PhoneAgentService.instance ?: session.context.takeIf { Settings.canDrawOverlays(it) }

    private fun windowType(): Int = when {
        PhoneAgentService.instance != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    }

    private fun windowManager(): WindowManager? = overlayContext()?.getSystemService(WindowManager::class.java)

    private fun show() {
        if (panel != null) return
        val ctx = overlayContext() ?: return // neither screen control nor the overlay permission
        val wm = windowManager() ?: return
        val lifecycleOwner = OverlayOwner().also { it.start() }

        fun composeView(content: @Composable () -> Unit) = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent(content)
        }

        // Bottom-edge glow: never takes touches
        val glowView = composeView { BottomGlow() }
        val glowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        // The card: touchable only where it is drawn; the rest of the screen stays with the app.
        // x / y: offset from the bottom centre, where the user last dragged it
        val panelView = composeView { Panel() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = prefs.getInt(PREF_X, 0)
            y = prefs.getInt(PREF_Y, (8 * ctx.resources.displayMetrics.density).toInt())
        }

        try {
            wm.addView(glowView, glowParams)
            wm.addView(panelView, params)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay not shown: ${e.message}")
            runCatching { wm.removeView(glowView) }
            lifecycleOwner.stop()
            return
        }
        owner = lifecycleOwner
        glow = glowView
        panel = panelView
        panelParams = params
        // A size change (expand / collapse, longer reply) must not push it off screen
        panelView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepOnScreen() }
    }

    private fun hideAll() {
        val wm = windowManager()
        listOfNotNull(panel, glow).forEach { v -> runCatching { wm?.removeView(v) } }
        panel = null
        glow = null
        panelParams = null
        owner?.stop()
        owner = null
    }

    // The panel is letting touches through for a gesture right now
    @Volatile private var passingThrough = false

    /**
     * While the assistant's own gesture plays over the panel, the panel lets touches through
     * (called from the tool thread). Changing the window relayouts it, which shows as a blink, so
     * it is only done when the gesture's area actually crosses the panel; most taps and swipes don't.
     */
    private fun setPassThrough(on: Boolean, area: android.graphics.RectF) {
        if (on) {
            var crosses = false
            onMain {
                val view = panel ?: return@onMain
                val params = panelParams ?: return@onMain
                val loc = IntArray(2).also(view::getLocationOnScreen)
                crosses = android.graphics.RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat())
                    .intersect(area)
                if (!crosses) return@onMain
                params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching { windowManager()?.updateViewLayout(view, params) }
                passingThrough = true
            }
            // Let the window change reach input dispatch before the gesture starts
            if (crosses) Thread.sleep(40)
        } else if (passingThrough) onMain {
            passingThrough = false
            val view = panel ?: return@onMain
            val params = panelParams ?: return@onMain
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            runCatching { windowManager()?.updateViewLayout(view, params) }
        }
    }

    /** Hides the overlay for a screenshot of the screen (look_at_screen), so it shows the app only. */
    private fun setHidden(hidden: Boolean) = onMain {
        listOfNotNull(panel, glow).forEach { it.visibility = if (hidden) View.INVISIBLE else View.VISIBLE }
    }.also { if (hidden) Thread.sleep(80) }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        main.post { try { block() } finally { latch.countDown() } }
        latch.await(500, TimeUnit.MILLISECONDS)
    }

    // ---- Dragging ----

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false

    // Full card, or just the icons (mini); the user's choice is kept
    private var expanded by mutableStateOf(!prefs.getBoolean(PREF_MINI, false))
    // Resizing past the smallest width: letting go turns the card into the icons
    private var shrinkToMini = false

    private fun showFull(value: Boolean) {
        expanded = value
        prefs.edit().putBoolean(PREF_MINI, !value).apply()
    }

    // Size the user set with the corner grip: card width (px) and reply area height (dp); 0 = automatic
    private var userWidthPx = prefs.getInt(PREF_W, 0)
    private var replyHeightDp by mutableFloatStateOf(prefs.getFloat(PREF_H, 0f))
    // Height the reply area has now, to start a resize from
    private var shownReplyDp = DEFAULT_REPLY_DP
    private var startReplyDp = 0f
    private var lastGripTap = 0L

    /** Moves the card to this offset from the bottom centre, kept fully on screen. */
    private fun moveTo(x: Int, y: Int) {
        val view = panel ?: return
        val params = panelParams ?: return
        val m = view.resources.displayMetrics
        val maxX = maxOf(0, (m.widthPixels - view.width) / 2)
        val maxY = maxOf(0, m.heightPixels - view.height - (24 * m.density).toInt())
        val nx = x.coerceIn(-maxX, maxX)
        val ny = y.coerceIn(0, maxY)
        if (nx == params.x && ny == params.y) return
        params.x = nx
        params.y = ny
        runCatching { windowManager()?.updateViewLayout(view, params) }
    }

    /**
     * Expanded: the card spans the screen less a margin (a WRAP_CONTENT overlay window is first
     * measured at the narrow "dialog width" and Compose doesn't ask for more). Collapsed: wraps the pill.
     */
    private fun setPanelWidth(expanded: Boolean) {
        val view = panel ?: return
        val params = panelParams ?: return
        val m = view.resources.displayMetrics
        val width = if (expanded) {
            (userWidthPx.takeIf { it > 0 } ?: (m.widthPixels - (12 * m.density).toInt())).coerceIn((MIN_WIDTH_DP * m.density).toInt(), m.widthPixels)
        } else WindowManager.LayoutParams.WRAP_CONTENT
        if (params.width == width) return
        params.width = width
        runCatching { windowManager()?.updateViewLayout(view, params) }
    }

    private fun keepOnScreen() {
        val params = panelParams ?: return
        moveTo(params.x, params.y)
    }

    /**
     * Drag the card by this area (raw screen coordinates, since the window moves under the finger);
     * a touch that doesn't move is a tap.
     */
    @SuppressLint("ReturnFromAwaitPointerEventScope")
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Modifier.dragsWindow(onTap: (() -> Unit)? = null): Modifier = pointerInteropFilter { e ->
        val params = panelParams ?: return@pointerInteropFilter false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startX = params.x; startY = params.y
                dragging = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                val slop = panel?.let { ViewConfiguration.get(it.context).scaledTouchSlop } ?: 16
                if (!dragging && hypot(dx, dy) > slop) dragging = true
                if (dragging) moveTo(startX + dx.toInt(), startY - dy.toInt())
                true
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) prefs.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
                else onTap?.invoke()
                dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; true }
            else -> false
        }
    }

    /**
     * Resize the card by its corner grip: sideways changes the width (it stays centred where it is),
     * up / down changes the height of the reply area. A double tap goes back to the automatic size.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun Modifier.resizesWindow(): Modifier = pointerInteropFilter { e ->
        val view = panel ?: return@pointerInteropFilter false
        val params = panelParams ?: return@pointerInteropFilter false
        val m = view.resources.displayMetrics
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startX = view.width
                startReplyDp = replyHeightDp.takeIf { it > 0 } ?: shownReplyDp
                dragging = false
                shrinkToMini = false
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > ViewConfiguration.get(view.context).scaledTouchSlop) dragging = true
                if (dragging) {
                    val minWidth = (MIN_WIDTH_DP * m.density).toInt()
                    val wanted = (startX + 2 * dx).toInt()
                    // Pulled well past the smallest size: shows faded, and becomes just the icons on release
                    shrinkToMini = wanted < minWidth - (56 * m.density).toInt()
                    view.alpha = if (shrinkToMini) 0.55f else 1f
                    val width = wanted.coerceIn(minWidth, m.widthPixels)
                    if (width != params.width) {
                        params.width = width
                        runCatching { windowManager()?.updateViewLayout(view, params) }
                    }
                    replyHeightDp = (startReplyDp - dy / m.density).coerceIn(MIN_REPLY_DP, m.heightPixels / m.density * 0.6f)
                }
                true
            }
            MotionEvent.ACTION_UP -> {
                view.alpha = 1f
                if (dragging && shrinkToMini) {
                    // Keep the card's size for when it is opened again
                    replyHeightDp = startReplyDp.takeIf { prefs.getFloat(PREF_H, 0f) > 0 } ?: 0f
                    showFull(false)
                } else if (dragging) {
                    userWidthPx = params.width
                    prefs.edit().putInt(PREF_W, userWidthPx).putFloat(PREF_H, replyHeightDp).apply()
                } else {
                    val now = System.currentTimeMillis()
                    if (now - lastGripTap < 350) {
                        userWidthPx = 0
                        replyHeightDp = 0f
                        prefs.edit().remove(PREF_W).remove(PREF_H).apply()
                        setPanelWidth(true)
                    }
                    lastGripTap = now
                }
                dragging = false
                true
            }
            MotionEvent.ACTION_CANCEL -> { view.alpha = 1f; dragging = false; shrinkToMini = false; true }
            else -> false
        }
    }

    /** Corner grip: two short diagonal lines in the top-right corner. */
    @Composable
    private fun ResizeGrip(modifier: Modifier) {
        Box(
            modifier.size(22.dp).resizesWindow().drawBehind {
                val c = Color.White.copy(alpha = 0.35f)
                val w = 2.dp.toPx()
                drawLine(c, Offset(size.width * 0.38f, size.height * 0.30f), Offset(size.width * 0.70f, size.height * 0.62f), w, androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(c, Offset(size.width * 0.56f, size.height * 0.30f), Offset(size.width * 0.70f, size.height * 0.44f), w, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        )
    }

    // ---- UI ----

    /** Seconds since shown, every frame. Read it inside draw lambdas so frames only redraw. */
    @Composable
    private fun clock(): State<Float> {
        val t = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) withFrameNanos { now -> t.floatValue = (now - start) / 1_000_000_000f }
        }
        return t
    }

    @Composable
    private fun voiceLevel(): Float {
        val ai by session.aiAmplitude.collectAsState()
        val mic by session.micAmplitude.collectAsState()
        val muted by session.isMicMuted.collectAsState()
        val level by animateFloatAsState(minOf(1f, maxOf(ai, if (muted) 0f else mic) * 8f), tween(140), label = "level")
        return level
    }

    /** Gemini Live-style glow: soft colour blobs rising from the bottom edge, swaying, brighter with the voice. */
    @Composable
    private fun BottomGlow() {
        val level = voiceLevel()
        val tool by session.activeToolStatus.collectAsState()
        val busy = tool != null
        val time = clock()
        val strength by animateFloatAsState((if (busy) 0.55f else 0.25f) + 0.75f * level, tween(220), label = "glow")
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .drawBehind {
                    val t = time.value * (if (busy) 1.6f else 0.8f)
                    val w = size.width
                    val h = size.height
                    val blobs = listOf(
                        BLUE to (0.18f + 0.10f * sin(t)),
                        PURPLE to (0.50f + 0.12f * sin(t * 0.7f + 2f)),
                        PINK to (0.82f + 0.10f * sin(t * 0.9f + 4f)),
                        CYAN to (0.35f + 0.25f * sin(t * 0.5f + 1f))
                    )
                    blobs.forEach { (color, fx) ->
                        val r = w * (0.42f + 0.10f * strength)
                        drawCircle(
                            Brush.radialGradient(
                                listOf(color.copy(alpha = 0.55f * strength), color.copy(alpha = 0f)),
                                center = Offset(w * fx, h * 1.05f), radius = r
                            ),
                            radius = r, center = Offset(w * fx, h * 1.05f)
                        )
                    }
                    // A crisp gradient line right at the edge
                    drawRect(
                        Brush.horizontalGradient(GEMINI, startX = -w * (t % 1f), endX = w * 2 - w * (t % 1f)),
                        topLeft = Offset(0f, h - (2.dp.toPx() + 3.dp.toPx() * level)),
                        size = Size(w, 2.dp.toPx() + 3.dp.toPx() * level),
                        alpha = 0.6f + 0.4f * strength.coerceAtMost(1f)
                    )
                }
        )
    }

    @Composable
    private fun Panel() {
        val name by session.assistantName.collectAsState()
        val reply by session.liveReply.collectAsState()
        val transcripts by session.transcripts.collectAsState()
        val plan by session.plan.collectAsState()
        val tool by session.activeToolStatus.collectAsState()
        val muted by session.isMicMuted.collectAsState()
        val choices by session.choices.collectAsState()
        val status by session.connectionStatus.collectAsState()
        val ai by session.aiAmplitude.collectAsState()
        val level = voiceLevel()
        val time = clock()
        val expanded = expanded
        LaunchedEffect(expanded) { setPanelWidth(expanded) }

        val speaking = ai > 0.01f
        val working = tool != null || plan?.status == "working"
        val userLine = transcripts.lastOrNull { it.sender == "user" }?.text?.trim().orEmpty()
        val shownReply = reply.ifBlank { transcripts.lastOrNull { it.sender == "assistant" }?.text.orEmpty() }.trim()
        val state = when {
            status == ConnectionStatus.CONNECTING -> "Connecting…"
            tool != null -> tool!!
            speaking -> "Speaking…"
            !muted -> "Listening…"
            working -> "Thinking…"
            else -> "Tap the mic to talk"
        }

        val cardWidth = LocalConfiguration.current.screenWidthDp.dp - 20.dp
        val shape = if (expanded) RoundedCornerShape(28.dp) else CircleShape
        val borderAlpha by animateFloatAsState(if (working) 1f else if (speaking) 0.6f else 0.18f, tween(400), label = "border")

        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .then(if (expanded) Modifier.fillMaxWidth() else Modifier.widthIn(max = cardWidth))
                    .shadow(18.dp, shape, ambientColor = PURPLE, spotColor = BLUE)
                    .clip(shape)
                    // Gradient rim, spinning while the assistant works; the card covers all but the edge
                    .drawBehind {
                        drawRect(Color(0xFF3C4043))
                        rotate(time.value * (if (working) 200f else 50f)) {
                            drawCircle(Brush.sweepGradient(GEMINI), radius = size.maxDimension, alpha = borderAlpha)
                        }
                    }
                    .padding(1.2.dp)
                    .clip(shape)
                    .background(Brush.verticalGradient(listOf(CARD_TOP, CARD_BOTTOM)))
                // No size animation: the window itself would resize every frame, which flickers
            ) {
                if (expanded) Expanded(name, state, userLine, shownReply, muted, speaking, working, level, time) { showFull(false) }
                else Mini(muted, working, level, time) { showFull(true) }
            }
        }
    }

    @Composable
    private fun Expanded(
        name: String, state: String, userLine: String, reply: String,
        muted: Boolean, speaking: Boolean, working: Boolean, level: Float, time: State<Float>,
        onCollapse: () -> Unit
    ) {
        val plan by session.plan.collectAsState()
        val choices by session.choices.collectAsState()
        val tool by session.activeToolStatus.collectAsState()

        Column(Modifier.padding(start = 16.dp, end = 10.dp, bottom = 12.dp)) {
            // Top: drag handle, sparkle + name, and the window buttons. Drag anywhere here to move it;
            // the grip in the corner resizes it.
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    // Drag handle (moves the card)
                    Box(Modifier.fillMaxWidth().dragsWindow().padding(top = 10.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Sparkle and name: drag to move too. Kept apart from the buttons, whose taps
                        // the drag handling would otherwise swallow.
                        Row(Modifier.weight(1f).dragsWindow(), verticalAlignment = Alignment.CenterVertically) {
                            Sparkle(24.dp, working, level, time)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, color = TEXT, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                ShimmerText(state, active = working || state == "Connecting…", time = time)
                            }
                        }
                        // Shrink to just the icons / open the full app / end the conversation
                        IconButton(Icons.Default.KeyboardArrowDown, "Shrink to icon", onClick = onCollapse)
                        IconButton(Icons.AutoMirrored.Filled.OpenInNew, "Open in app", size = 18.dp) { openApp() }
                        IconButton(Icons.Default.Close, "End") { session.stop() }
                    }
                }
                ResizeGrip(Modifier.align(Alignment.TopEnd))
            }

            // Plan: segmented progress, current step and the assistant's thinking
            plan?.takeIf { it.status != "done" }?.let { p ->
                Spacer(Modifier.height(12.dp))
                Column(
                    Modifier
                        .padding(end = 6.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.04f))
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(p.goal, color = TEXT, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val total = p.steps.size
                    if (total > 0) {
                        val step = p.currentStep.coerceIn(1, total)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (i in 1..total) {
                                val done = i < step || p.status == "done"
                                val current = i == step && p.status != "done"
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(4.dp)
                                        .clip(CircleShape)
                                        .drawBehind {
                                            when {
                                                done -> drawRect(Brush.horizontalGradient(listOf(BLUE, PURPLE)))
                                                current -> {
                                                    drawRect(Color.White.copy(alpha = 0.12f))
                                                    val pulse = 0.5f + 0.5f * sin(time.value * 4f)
                                                    drawRect(
                                                        if (p.status == "blocked") Color(0xFFFBBF24) else BLUE,
                                                        size = Size(size.width * (0.35f + 0.4f * pulse), size.height)
                                                    )
                                                }
                                                else -> drawRect(Color.White.copy(alpha = 0.12f))
                                            }
                                        }
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Step $step of $total · ${p.steps.getOrElse(step - 1) { "" }}",
                            color = MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Always one line, so a new thought doesn't change the card's height
                    (p.note?.takeIf { it.isNotBlank() } ?: "Working on it…").let { note ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.Top) {
                            Sparkle(12.dp, spinning = true, level = 0f, time = time, modifier = Modifier.padding(top = 2.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                note, fontSize = 12.sp, lineHeight = 16.sp, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (p.status == "blocked") Color(0xFFFCD34D) else Color(0xFFC9B8F5)
                            )
                        }
                    }
                }
            }

            // What the user asked (bubble on the right), then the reply as it is spoken
            if (userLine.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().padding(end = 6.dp), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        userLine, color = TEXT, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                            .background(SURFACE)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
            // Fixed height (the user can resize it): streaming text scrolls inside instead of growing the window
            Spacer(Modifier.height(10.dp))
            val scroll = rememberScrollState()
            LaunchedEffect(reply) { scroll.animateScrollTo(scroll.maxValue) }
            Text(
                reply.ifEmpty { "…" }, color = if (reply.isEmpty()) MUTED else TEXT, fontSize = 16.sp, lineHeight = 23.sp,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .fillMaxWidth()
                    .height((replyHeightDp.takeIf { it > 0 } ?: DEFAULT_REPLY_DP).dp)
                    .verticalScroll(scroll)
            )

            // Options to tap (show_choices), as Gemini-style chips
            AnimatedVisibility(choices != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = 10.dp, end = 6.dp)) {
                    choices?.let { c ->
                        Text(c.question, color = MUTED, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        c.options.forEach { option ->
                            Text(
                                option, color = TEXT, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(SURFACE)
                                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                                    .clickable { session.pickChoice(option) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // Bottom bar, like Gemini's prompt bar: voice level + hint, stop, mic
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .padding(end = 6.dp)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(SURFACE)
                    .padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VoiceBars(level, active = speaking || !muted, time = time)
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        speaking -> "Speaking"
                        !muted -> "Listening…"
                        working -> "Mic off · working"
                        else -> "Tap mic to talk"
                    },
                    color = MUTED, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                AnimatedVisibility(speaking, enter = fadeIn(), exit = fadeOut()) {
                    Box(
                        Modifier.padding(end = 6.dp).size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                            .clickable { session.interrupt() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Stop, "Stop reply", tint = TEXT, modifier = Modifier.size(18.dp))
                    }
                }
                MicButton(muted, level, 42.dp, time)
            }
        }
    }

    /**
     * Mini: just the icons. The sparkle shows what's going on (spins while working, swells with the
     * voice); tap it to open the card again, drag it to move. The mic works as in the card.
     */
    @Composable
    private fun Mini(muted: Boolean, working: Boolean, level: Float, time: State<Float>, onExpand: () -> Unit) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).dragsWindow(onTap = onExpand),
                contentAlignment = Alignment.Center
            ) {
                Sparkle(24.dp, working, level, time)
            }
            Spacer(Modifier.width(2.dp))
            MicButton(muted, level, 38.dp, time)
        }
    }

    /** Gemini's four-point sparkle in its gradient: spins while working, swells with the voice. */
    @Composable
    private fun Sparkle(size: Dp, spinning: Boolean, level: Float, time: State<Float>, modifier: Modifier = Modifier) {
        Box(
            modifier.size(size).drawBehind {
                val t = time.value
                val angle = if (spinning) t * 180f else 12f * sin(t * 1.5f)
                val s = 0.86f + 0.14f * sin(t * 2.2f) + 0.25f * level
                rotate(angle) { scale(s) { drawSparkle(this) } }
            }
        )
    }

    private fun drawSparkle(scope: DrawScope) = with(scope) {
        val w = size.width
        val h = size.height
        val cx = w / 2
        val cy = h / 2
        val path = Path().apply {
            moveTo(cx, 0f)
            quadraticBezierTo(cx * 1.08f, cy * 0.92f, w, cy)
            quadraticBezierTo(cx * 1.08f, cy * 1.08f, cx, h)
            quadraticBezierTo(cx * 0.92f, cy * 1.08f, 0f, cy)
            quadraticBezierTo(cx * 0.92f, cy * 0.92f, cx, 0f)
            close()
        }
        drawPath(path, Brush.linearGradient(SPARKLE, start = Offset(0f, h), end = Offset(w, 0f)))
    }

    /** Text with a light sweeping across it while something is in progress. */
    @Composable
    private fun ShimmerText(text: String, active: Boolean, time: State<Float>) {
        if (!active) {
            Text(text, color = MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            return
        }
        val x = (time.value * 0.8f) % 1f
        Text(
            text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 12.sp,
                brush = Brush.linearGradient(
                    listOf(MUTED, Color.White, Color(0xFFB9A4F0), MUTED),
                    start = Offset(900f * x - 450f, 0f), end = Offset(900f * x + 150f, 0f)
                )
            )
        )
    }

    /** Four rounded bars that dance with the voice (Gemini Live), flat dots when quiet. */
    @Composable
    private fun VoiceBars(level: Float, active: Boolean, time: State<Float>) {
        val amp by animateFloatAsState(if (active) 0.25f + 0.75f * level else 0f, tween(160), label = "bars")
        Box(
            Modifier.size(width = 26.dp, height = 22.dp).drawBehind {
                val bar = 3.5.dp.toPx()
                val gap = (size.width - bar * 4) / 3
                val colors = listOf(BLUE, PURPLE, PINK, CYAN)
                for (i in 0 until 4) {
                    val wave = 0.5f + 0.5f * sin(time.value * 9f + i * 1.3f)
                    val h = bar + (size.height - bar) * amp * (0.35f + 0.65f * wave)
                    drawRoundRect(
                        if (active) colors[i] else MUTED,
                        topLeft = Offset(i * (bar + gap), (size.height - h) / 2),
                        size = Size(bar, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(bar / 2)
                    )
                }
            }
        )
    }

    /** Mic on: Gemini gradient with a ring that follows the user's voice. Mic off: calm and grey. */
    @Composable
    private fun MicButton(muted: Boolean, level: Float, size: Dp, time: State<Float>) {
        val ring by animateFloatAsState(if (muted) 1f else 1.12f + 0.4f * level, tween(140), label = "ring")
        Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
            if (!muted) {
                Box(
                    Modifier.size(size).drawBehind {
                        val r = size.toPx() / 2 * ring
                        rotate(time.value * 120f) {
                            drawCircle(Brush.sweepGradient(GEMINI), radius = r, alpha = 0.35f)
                        }
                    }
                )
            }
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(
                        if (muted) Brush.linearGradient(listOf(Color(0xFF45474A), Color(0xFF3A3B3E)))
                        else Brush.linearGradient(listOf(BLUE, PURPLE, PINK))
                    )
                    .clickable { session.toggleMicMute() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (muted) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = if (muted) "Turn mic on" else "Turn mic off",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.48f)
                )
            }
        }
    }

    @Composable
    private fun IconButton(icon: ImageVector, description: String, size: Dp = 20.dp, onClick: () -> Unit) {
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = description, tint = MUTED, modifier = Modifier.size(size))
        }
    }

    private fun openApp() {
        val ctx = overlayContext() ?: return
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Lifecycle for the overlay's Compose views (they live outside any activity). */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
