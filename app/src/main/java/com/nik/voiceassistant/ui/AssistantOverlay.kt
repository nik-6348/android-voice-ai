package com.nik.voiceassistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nik.voiceassistant.ConnectionStatus
import com.nik.voiceassistant.MainActivity
import com.nik.voiceassistant.R
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.service.PhoneAgentService
import com.nik.voiceassistant.ui.components.OrbMode
import com.nik.voiceassistant.ui.components.VoiceBars
import com.nik.voiceassistant.ui.components.VoiceOrb
import com.nik.voiceassistant.ui.components.activeWithHold
import com.nik.voiceassistant.ui.components.rememberFrameClock
import com.nik.voiceassistant.ui.components.rememberVoiceLevel
import com.nik.voiceassistant.ui.components.safeList
import com.nik.voiceassistant.ui.components.safeSender
import com.nik.voiceassistant.ui.components.safeString
import com.nik.voiceassistant.ui.components.safeText
import com.nik.voiceassistant.ui.theme.NikTheme
import com.nik.voiceassistant.ui.theme.NikVoiceTheme
import com.nik.voiceassistant.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Assistant panel over whatever app is open while a conversation runs and the app's own screen
 * isn't showing: a floating card in the app's theme with the assistant's orb, what the user said,
 * the reply as it is spoken, the plan and current thinking, options to pick, and a bottom bar with
 * the voice level, stop and the mic. A soft glow in the theme colours rises from the bottom edge
 * with the voice and while it works.
 *
 * The card can be dragged anywhere by its top (or the mini bubble), and remembers where it was
 * left; TalkBack users get "move" actions instead. Only the card takes touches, and it lets the
 * assistant's own taps and swipes through. Nothing animates (no frames) while it is idle, and the
 * screen is kept on only while the assistant is talking or working.
 *
 * If the system removes the windows (screen control switched off, e.g. by PaymentGuard while a
 * payment app is open), they are noticed as gone and shown again once screen control is back.
 */
class AssistantOverlay(private val session: VoiceSession) {

    companion object {
        private const val TAG = "AssistantOverlay"
        private const val PREF_X = "overlay_x"
        private const val PREF_Y = "overlay_y"
        private const val PREF_W = "overlay_width"
        private const val PREF_H = "overlay_reply_height"
        private const val PREF_MINI = "overlay_mini"
        private const val MIN_WIDTH_DP = 260
        private const val MIN_REPLY_DP = 48f
        // Reply area height until the user resizes it (4 lines); fixed, so streaming text doesn't resize the window
        private const val DEFAULT_REPLY_DP = 92f
        // Retry showing the windows after the system removed them (or before it is possible)
        private const val RETRY_MS = 2_000L
        // Keep the screen on this long after the last words / work, so short gaps don't let it sleep
        private const val KEEP_ON_AFTER_MS = 5_000L
        // TalkBack "move" actions shift the card by this much
        private const val NUDGE_DP = 64
    }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = session.context.getSharedPreferences("voice_assistant_settings", Context.MODE_PRIVATE)
    private var owner: OverlayOwner? = null
    private var glow: ComposeView? = null
    private var panel: ComposeView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    // The window manager the views were added with (removal and updates go through it)
    private var windows: WindowManager? = null
    // A conversation is running and the app isn't showing: the overlay should be up
    @Volatile private var wanted = false
    // The panel's window was attached once (so "not attached" later means it was removed)
    private var panelAttached = false
    // We are removing the views ourselves (not the system)
    private var removing = false
    // The views were added through the accessibility service: after a removal, wait for it to return
    private var addedViaService = false

    fun bind(scope: CoroutineScope, appVisible: StateFlow<Boolean>) {
        scope.launch {
            combine(session.isRecording, appVisible) { live, visible -> live && !visible }
                .distinctUntilChanged()
                .collect {
                    wanted = it
                    if (it) show() else hideAll()
                }
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

    private val retryShow = Runnable {
        if (!wanted) return@Runnable
        // Removed with screen control: don't fall back to an app overlay over (say) a payment app
        if (addedViaService && panel != null && PhoneAgentService.instance == null) retryLater()
        else show()
    }

    private fun retryLater() {
        if (!wanted) return
        main.removeCallbacks(retryShow)
        main.postDelayed(retryShow, RETRY_MS)
    }

    private fun show() {
        main.removeCallbacks(retryShow)
        panel?.let { existing ->
            // Up (or still being attached): nothing to do
            if (!panelAttached || existing.isAttachedToWindow) return
            // Its window is gone (the system removed it): clear the old views and start over
            hideAll()
        }
        val ctx = overlayContext() ?: return retryLater() // neither screen control nor the overlay permission yet
        val wm = ctx.getSystemService(WindowManager::class.java) ?: return
        val viaService = PhoneAgentService.instance != null
        val lifecycleOwner = OverlayOwner().also { it.start() }

        fun composeView(content: @Composable () -> Unit) = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { NikVoiceTheme { content() } }
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
        // x / y: offset from the bottom centre, where the user last dragged it. No FLAG_KEEP_SCREEN_ON:
        // the panel keeps the screen on only while the assistant talks or works (Panel).
        val panelView = composeView { Panel() }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            runCatching { wm.removeViewImmediate(glowView) }
            lifecycleOwner.stop()
            retryLater()
            return
        }
        owner = lifecycleOwner
        glow = glowView
        panel = panelView
        panelParams = params
        windows = wm
        panelAttached = false
        addedViaService = viaService
        panelView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                if (panel === v) panelAttached = true
            }

            override fun onViewDetachedFromWindow(v: View) {
                // Not removed by us: the system took the window away. Show it again when possible
                if (panel === v && !removing) retryLater()
            }
        })
        // A size change (expand / collapse, longer reply) must not push it off screen
        panelView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> keepOnScreen() }
    }

    private fun hideAll() {
        main.removeCallbacks(retryShow)
        removing = true
        // Remove through the manager the views were added with, else the current one; a window the
        // system already destroyed may still be registered on our side, so always try
        val managers = listOfNotNull(windows, overlayContext()?.getSystemService(WindowManager::class.java)).distinct()
        listOfNotNull(panel, glow).forEach { v ->
            for (wm in managers) if (runCatching { wm.removeViewImmediate(v) }.isSuccess) break
            runCatching { v.disposeComposition() }
        }
        removing = false
        panel = null
        glow = null
        panelParams = null
        windows = null
        panelAttached = false
        owner?.stop()
        owner = null
    }

    // Each pass-through change gets a number; a change that arrives late (after a newer one) is dropped
    private val passSeq = AtomicInteger()

    /**
     * While the assistant's own gesture plays over the panel, the panel lets touches through
     * (called from the tool thread). Changing the window relayouts it, which shows as a blink, so
     * it is only done when the gesture's area actually crosses the panel; most taps and swipes don't.
     */
    private fun setPassThrough(on: Boolean, area: RectF) {
        val seq = passSeq.incrementAndGet()
        if (on) {
            var crosses = false
            onMain {
                if (seq != passSeq.get()) return@onMain
                val view = panel ?: return@onMain
                val loc = IntArray(2).also(view::getLocationOnScreen)
                crosses = RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat())
                    .intersect(area)
                setTouchable(!crosses)
            }
            // Let the window change reach input dispatch before the gesture starts
            if (crosses) Thread.sleep(40)
        } else onMain {
            if (seq == passSeq.get()) setTouchable(true)
        }
    }

    private fun setTouchable(touchable: Boolean) {
        val view = panel ?: return
        val params = panelParams ?: return
        val flags = if (touchable) params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (flags == params.flags) return
        params.flags = flags
        runCatching { windows?.updateViewLayout(view, params) }
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

    // Full card, or just the bubble (mini); the user's choice is kept
    private var expanded by mutableStateOf(!prefs.getBoolean(PREF_MINI, false))
    // Resizing past the smallest width: letting go turns the card into the bubble
    private var shrinkToMini = false

    private fun showFull(value: Boolean) {
        expanded = value
        prefs.edit().putBoolean(PREF_MINI, !value).apply()
    }

    // Size the user set with the corner grip: card width (px) and reply area height (dp); 0 = automatic
    private var userWidthPx = prefs.getInt(PREF_W, 0)
    private var replyHeightDp by mutableFloatStateOf(prefs.getFloat(PREF_H, 0f))
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
        runCatching { windows?.updateViewLayout(view, params) }
    }

    /** TalkBack "move" actions: shift by a step and remember the place. */
    private fun nudge(dxDp: Int, dyDp: Int): Boolean {
        val params = panelParams ?: return false
        val density = panel?.resources?.displayMetrics?.density ?: return false
        moveTo(params.x + (dxDp * density).toInt(), params.y + (dyDp * density).toInt())
        prefs.edit().putInt(PREF_X, params.x).putInt(PREF_Y, params.y).apply()
        return true
    }

    /**
     * Expanded: the card spans the screen less a margin (a WRAP_CONTENT overlay window is first
     * measured at the narrow "dialog width" and Compose doesn't ask for more). Collapsed: wraps the bubble.
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
        runCatching { windows?.updateViewLayout(view, params) }
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
                startReplyDp = replyHeightDp.takeIf { it > 0 } ?: DEFAULT_REPLY_DP
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
                    // Pulled well past the smallest size: shows faded, and becomes the bubble on release
                    shrinkToMini = wanted < minWidth - (56 * m.density).toInt()
                    view.alpha = if (shrinkToMini) 0.55f else 1f
                    val width = wanted.coerceIn(minWidth, m.widthPixels)
                    if (width != params.width) {
                        params.width = width
                        runCatching { windows?.updateViewLayout(view, params) }
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

    @Composable
    private fun moveActions(): List<CustomAccessibilityAction> {
        val up = stringResource(R.string.overlay_move_up)
        val down = stringResource(R.string.overlay_move_down)
        val left = stringResource(R.string.overlay_move_left)
        val right = stringResource(R.string.overlay_move_right)
        return remember(up, down, left, right) {
            listOf(
                CustomAccessibilityAction(up) { nudge(0, NUDGE_DP) },
                CustomAccessibilityAction(down) { nudge(0, -NUDGE_DP) },
                CustomAccessibilityAction(left) { nudge(-NUDGE_DP, 0) },
                CustomAccessibilityAction(right) { nudge(NUDGE_DP, 0) }
            )
        }
    }

    /** Corner grip: two short diagonal lines in the top-right corner. */
    @Composable
    private fun ResizeGrip(modifier: Modifier) {
        val color = MaterialTheme.colorScheme.outline
        Box(
            modifier.size(24.dp).resizesWindow().clearAndSetSemantics {}.drawBehind {
                val w = 2.dp.toPx()
                drawLine(color, Offset(size.width * 0.38f, size.height * 0.30f), Offset(size.width * 0.70f, size.height * 0.62f), w, StrokeCap.Round)
                drawLine(color, Offset(size.width * 0.56f, size.height * 0.30f), Offset(size.width * 0.70f, size.height * 0.44f), w, StrokeCap.Round)
            }
        )
    }

    // ---- UI ----

    /** The voice level (assistant or, with the mic on, the user), for drawing only. */
    @Composable
    private fun voiceLevel(): State<Float> {
        val source = remember {
            combine(session.micAmplitude, session.aiAmplitude, session.isMicMuted) { mic, ai, muted -> maxOf(ai, if (muted) 0f else mic) }
        }
        return rememberVoiceLevel(source, gain = 8f)
    }

    /** Soft colour blobs rising from the bottom edge, swaying while it works, brighter with the voice. */
    @Composable
    private fun BottomGlow() {
        val tool by session.activeToolStatus.collectAsState()
        val busy = tool != null
        val level = voiceLevel()
        // Frames only while it works; the voice alone just redraws
        val clock = rememberFrameClock(busy)
        val scheme = MaterialTheme.colorScheme
        val colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary)
        Box(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clearAndSetSemantics {}
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    val r = w * 0.45f
                    // Shaders made once per size, centred on the origin and moved with translate
                    val blobs = colors.map { c -> Brush.radialGradient(listOf(c.copy(alpha = 0.5f), c.copy(alpha = 0f)), center = Offset.Zero, radius = r) }
                    val edge = Brush.horizontalGradient(colors + colors.first())
                    onDrawBehind {
                        val t = clock.value * 1.4f
                        val lv = level.value
                        val strength = ((if (busy) 0.5f else 0.2f) + 0.8f * lv).coerceAtMost(1f)
                        blobs.forEachIndexed { i, brush ->
                            val fx = when (i) {
                                0 -> 0.2f + 0.1f * sin(t)
                                1 -> 0.5f + 0.12f * sin(t * 0.7f + 2f)
                                else -> 0.8f + 0.1f * sin(t * 0.9f + 4f)
                            }
                            translate(w * fx, h * 1.05f) { drawCircle(brush, radius = r, center = Offset.Zero, alpha = strength) }
                        }
                        val line = 2.dp.toPx() + 3.dp.toPx() * lv
                        drawRect(edge, topLeft = Offset(0f, h - line), size = Size(w, line), alpha = 0.5f + 0.5f * strength)
                    }
                }
        )
    }

    @Composable
    private fun Panel() {
        val status by session.connectionStatus.collectAsState()
        val tool by session.activeToolStatus.collectAsState()
        val plan by session.plan.collectAsState()
        val muted by session.isMicMuted.collectAsState()
        val speaking by remember { session.aiAmplitude.activeWithHold(0.01f, 600) }.collectAsState(initial = false)
        val level = voiceLevel()
        val expanded = expanded
        LaunchedEffect(expanded) { setPanelWidth(expanded) }

        val working = tool != null || plan?.status == "working"
        val connecting = status == ConnectionStatus.CONNECTING
        // Frames only for what moves on its own: the spinning rim / orb while working or connecting,
        // the voice bars while speaking in the full card. Idle or listening: none.
        val clock = rememberFrameClock(working || connecting || (expanded && speaking))

        // The screen stays on while it talks or works (and a moment after), not for the whole conversation
        val view = LocalView.current
        val busy = working || speaking || connecting
        LaunchedEffect(busy) {
            if (!busy) delay(KEEP_ON_AFTER_MS)
            view.keepScreenOn = busy
        }

        val orbMode = when {
            connecting -> OrbMode.CONNECTING
            status == ConnectionStatus.ERROR -> OrbMode.ERROR
            working -> OrbMode.THINKING
            speaking -> OrbMode.SPEAKING
            !muted -> OrbMode.LISTENING
            else -> OrbMode.MUTED
        }

        val scheme = MaterialTheme.colorScheme
        val cardWidth = LocalConfiguration.current.screenWidthDp.dp - 20.dp
        val shape = if (expanded) RoundedCornerShape(28.dp) else CircleShape
        val rimActive = working || connecting

        Box(Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
            Box(
                Modifier
                    .then(if (expanded) Modifier.fillMaxWidth() else Modifier.widthIn(max = cardWidth))
                    .shadow(12.dp, shape)
                    .clip(shape)
                    // Rim: a turning gradient while it works, else a quiet outline
                    .drawWithCache {
                        val sweep = Brush.sweepGradient(listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary))
                        onDrawBehind {
                            drawRect(scheme.outlineVariant)
                            if (rimActive) rotate(clock.value * 200f) { drawCircle(sweep, radius = size.maxDimension) }
                        }
                    }
                    .padding(1.5.dp)
                    .clip(shape)
                    .background(scheme.surfaceContainerHigh)
                // No size animation: the window itself would resize every frame, which flickers
            ) {
                if (expanded) Expanded(orbMode, muted, speaking, working, connecting, level, clock) { showFull(false) }
                else Mini(orbMode, muted, level, clock) { showFull(true) }
            }
        }
    }

    @Composable
    private fun Expanded(
        orbMode: OrbMode, muted: Boolean, speaking: Boolean, working: Boolean, connecting: Boolean,
        level: State<Float>, clock: State<Float>, onCollapse: () -> Unit
    ) {
        val name by session.assistantName.collectAsState()
        val reply by session.liveReply.collectAsState()
        val transcripts by session.transcripts.collectAsState()
        val plan by session.plan.collectAsState()
        val choices by session.choices.collectAsState()
        val tool by session.activeToolStatus.collectAsState()
        val scheme = MaterialTheme.colorScheme

        val userLine = remember(transcripts) { transcripts.lastOrNull { it.safeSender == "user" }?.safeText?.trim().orEmpty() }
        val shownReply = reply.ifBlank { transcripts.lastOrNull { it.safeSender == "assistant" }?.safeText.orEmpty() }.trim()
        val state = when {
            connecting -> stringResource(R.string.status_connecting)
            tool != null -> stringResource(R.string.status_working_on, tool.orEmpty())
            speaking -> stringResource(R.string.status_speaking)
            !muted -> stringResource(R.string.status_listening)
            working -> stringResource(R.string.status_thinking)
            else -> stringResource(R.string.overlay_tap_mic)
        }
        val moveLabel = stringResource(R.string.overlay_move_panel)
        val collapseLabel = stringResource(R.string.overlay_shrink)
        val actions = moveActions()

        Column(Modifier.padding(start = Spacing.m, end = Spacing.s, bottom = Spacing.m - 4.dp)) {
            // Top: drag handle, orb + name, and the window buttons. Drag anywhere here to move it;
            // the grip in the corner resizes it.
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .dragsWindow()
                            .semantics {
                                contentDescription = moveLabel
                                customActions = actions
                            }
                            .padding(top = 10.dp, bottom = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(scheme.outlineVariant))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Orb and name: drag to move too. Kept apart from the buttons, whose taps
                        // the drag handling would otherwise swallow.
                        Row(Modifier.weight(1f).dragsWindow(), verticalAlignment = Alignment.CenterVertically) {
                            VoiceOrb(level, orbMode, Modifier.size(36.dp).clearAndSetSemantics {}, clock)
                            Spacer(Modifier.width(Spacing.s + 2.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = MaterialTheme.typography.titleSmall, color = scheme.onSurface, maxLines = 1)
                                StatusText(state, active = working || connecting, clock = clock)
                            }
                        }
                        PanelIconButton(Icons.Filled.KeyboardArrowDown, collapseLabel, onClick = onCollapse)
                        PanelIconButton(Icons.AutoMirrored.Filled.OpenInNew, stringResource(R.string.overlay_open_app), iconSize = 18.dp) { openApp() }
                        PanelIconButton(Icons.Filled.Close, stringResource(R.string.cd_end_conversation)) { session.stop() }
                    }
                }
                ResizeGrip(Modifier.align(Alignment.TopEnd))
            }

            // Plan: segmented progress, current step and the assistant's thinking
            plan?.takeIf { it.status != "done" }?.let { p ->
                Spacer(Modifier.height(Spacing.s + 4.dp))
                PlanStrip(
                    goal = safeString(p.goal),
                    steps = safeList(p.steps),
                    currentStep = p.currentStep,
                    status = safeString(p.status),
                    note = safeString(p.note),
                    clock = clock
                )
            }

            // What the user asked (bubble on the right), then the reply as it is spoken
            if (userLine.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.s + 4.dp))
                Box(Modifier.fillMaxWidth().padding(end = Spacing.s), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        userLine, style = MaterialTheme.typography.bodyMedium, color = scheme.onPrimaryContainer,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .widthIn(max = 260.dp)
                            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 6.dp, bottomStart = 18.dp, bottomEnd = 18.dp))
                            .background(scheme.primaryContainer)
                            .padding(horizontal = Spacing.m - 4.dp, vertical = Spacing.s)
                    )
                }
            }
            // Fixed height (the user can resize it): streaming text scrolls inside instead of growing the window
            Spacer(Modifier.height(Spacing.s + 2.dp))
            val scroll = rememberScrollState()
            LaunchedEffect(shownReply) { scroll.animateScrollTo(scroll.maxValue) }
            Text(
                shownReply.ifEmpty { "…" },
                style = MaterialTheme.typography.bodyLarge,
                color = if (shownReply.isEmpty()) scheme.onSurfaceVariant else scheme.onSurface,
                modifier = Modifier
                    .padding(end = Spacing.s)
                    .fillMaxWidth()
                    .height((replyHeightDp.takeIf { it > 0 } ?: DEFAULT_REPLY_DP).dp)
                    .verticalScroll(scroll)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )

            // Options to tap (show_choices)
            AnimatedVisibility(choices != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Column(Modifier.padding(top = Spacing.s + 2.dp, end = Spacing.s)) {
                    choices?.let { c ->
                        Text(safeString(c.question), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
                        safeList<String>(c.options).forEach { option ->
                            Text(
                                option, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp))
                                    .clickable(role = Role.Button) { session.pickChoice(option) }
                                    .padding(horizontal = 14.dp, vertical = 13.dp)
                            )
                        }
                    }
                }
            }

            // Bottom bar: voice level + hint, stop, mic
            Spacer(Modifier.height(Spacing.m - 4.dp))
            Row(
                Modifier
                    .padding(end = Spacing.s)
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                VoiceBars(
                    level = level, active = speaking || !muted, clock = clock,
                    colors = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primary),
                    idleColor = scheme.outline,
                    modifier = Modifier.clearAndSetSemantics {}
                )
                Spacer(Modifier.width(Spacing.s + 2.dp))
                Text(
                    stringResource(
                        when {
                            speaking -> R.string.status_speaking
                            !muted -> R.string.status_listening
                            working -> R.string.overlay_mic_off_working
                            else -> R.string.overlay_tap_mic
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                AnimatedVisibility(speaking, enter = fadeIn(), exit = fadeOut()) {
                    PanelIconButton(Icons.Filled.Stop, stringResource(R.string.cd_stop_reply), tint = scheme.onSurface) { session.interrupt() }
                }
                MicButton(muted, level, 44.dp)
            }
        }
    }

    /** The plan in one compact block: goal, segmented progress, current step, latest thought. */
    @Composable
    private fun PlanStrip(goal: String, steps: List<String>, currentStep: Int, status: String, note: String, clock: State<Float>) {
        val scheme = MaterialTheme.colorScheme
        val ext = NikTheme.extended
        Column(
            Modifier
                .padding(end = Spacing.s)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.surfaceContainerHighest)
                .padding(horizontal = Spacing.m - 4.dp, vertical = Spacing.s + 2.dp)
        ) {
            Text(goal, style = MaterialTheme.typography.labelLarge, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val total = steps.size
            if (total > 0) {
                val step = currentStep.coerceIn(1, total)
                val blocked = status == "blocked"
                val pulsing = status == "working"
                val doneColor = scheme.primary
                val track = scheme.outlineVariant
                val nowColor = if (blocked) ext.warning else scheme.primary
                Spacer(Modifier.height(Spacing.s))
                Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (i in 1..total) {
                        val done = i < step
                        val current = i == step
                        Box(
                            Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(CircleShape)
                                .drawBehind {
                                    when {
                                        done -> drawRect(doneColor)
                                        current -> {
                                            drawRect(track)
                                            val pulse = if (pulsing) 0.5f + 0.5f * sin(clock.value * 4f) else 0.6f
                                            drawRect(nowColor, size = Size(size.width * (0.35f + 0.4f * pulse), size.height))
                                        }
                                        else -> drawRect(track)
                                    }
                                }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.overlay_step, step, total, steps.getOrElse(step - 1) { "" }),
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            // Always one line, so a new thought doesn't change the card's height
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Lightbulb, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    note.ifBlank { stringResource(R.string.overlay_working_on_it) },
                    style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (status == "blocked") ext.warning else scheme.onSurfaceVariant
                )
            }
        }
    }

    /**
     * Mini: the orb and the mic. The orb shows what's going on; tap it to open the card again, drag
     * it to move. TalkBack: double-tap opens it, and the actions menu moves it.
     */
    @Composable
    private fun Mini(orbMode: OrbMode, muted: Boolean, level: State<Float>, clock: State<Float>, onExpand: () -> Unit) {
        val name by session.assistantName.collectAsState()
        val expandLabel = stringResource(R.string.overlay_expand, name)
        val openAction = stringResource(R.string.action_expand)
        val actions = moveActions()
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .dragsWindow(onTap = onExpand)
                    .semantics {
                        contentDescription = expandLabel
                        role = Role.Button
                        onClick(label = openAction) { onExpand(); true }
                        customActions = actions
                    },
                contentAlignment = Alignment.Center
            ) {
                VoiceOrb(level, orbMode, Modifier.size(44.dp), clock)
            }
            Spacer(Modifier.width(2.dp))
            MicButton(muted, level, 40.dp)
        }
    }

    /** Status line with a light sweeping across it while something is in progress (drawn, not recomposed). */
    @Composable
    private fun StatusText(text: String, active: Boolean, clock: State<Float>) {
        val scheme = MaterialTheme.colorScheme
        val highlight = scheme.primary
        Text(
            text, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.then(
                if (!active) Modifier
                else Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val x = (clock.value * 0.8f) % 1f
                        val w = size.width
                        val center = -0.3f * w + x * 1.6f * w
                        drawRect(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, highlight, Color.Transparent),
                                startX = center - w * 0.25f, endX = center + w * 0.25f
                            ),
                            blendMode = BlendMode.SrcAtop
                        )
                    }
            )
        )
    }

    /** Mic on: primary, with a ring that follows the user's voice. Mic off: quiet. */
    @Composable
    private fun MicButton(muted: Boolean, level: State<Float>, size: Dp) {
        val scheme = MaterialTheme.colorScheme
        val ring = scheme.primary
        Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
            if (!muted) {
                Box(
                    Modifier.size(size + 8.dp).clearAndSetSemantics {}.drawBehind {
                        drawCircle(ring.copy(alpha = 0.18f + 0.3f * level.value), radius = this.size.minDimension / 2 * (0.86f + 0.14f * level.value))
                    }
                )
            }
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(if (muted) scheme.surfaceContainerHigh else scheme.primary)
                    .clickable(role = Role.Button) { session.toggleMicMute() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = stringResource(if (muted) R.string.cd_unmute else R.string.cd_mute),
                    tint = if (muted) scheme.onSurfaceVariant else scheme.onPrimary,
                    modifier = Modifier.size(size * 0.48f)
                )
            }
        }
    }

    @Composable
    private fun PanelIconButton(
        icon: ImageVector,
        description: String,
        iconSize: Dp = 22.dp,
        tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
        onClick: () -> Unit
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
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
            if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
        }
    }
}
