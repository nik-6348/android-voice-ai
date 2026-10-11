package com.nik.voiceassistant.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.nik.voiceassistant.TranscriptItem
import com.nik.voiceassistant.VoiceSession
import com.nik.voiceassistant.service.PhoneAgentService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Assistant-style indicator over whatever app is open while a conversation runs and Nik's own
 * screen isn't showing (accessibility overlays: screen control must be on, no extra permission):
 *  - a thin glow wave along the bottom edge, showing only while someone speaks (never takes touches)
 *  - a small pill above it with the latest line said; touching the screen anywhere (or the pill)
 *    tucks it away so it never hides what you're doing, and it comes back with the next line said.
 * Taps the assistant itself makes (during a tool call) don't count as the user touching.
 */
class AssistantOverlay(private val session: VoiceSession) {

    companion object {
        private const val TAG = "AssistantOverlay"
        private val CYAN = Color.parseColor("#22D3EE")
        private val VIOLET = Color.parseColor("#A855F7")
        private val PINK = Color.parseColor("#F472B6")
        // Voice level (RMS, 0..1) above which the bottom wave shows; room noise stays below it
        private const val SPEAKING_LEVEL = 0.015f
    }

    private var glow: View? = null
    private var pill: View? = null
    private var dot: View? = null
    private var label: TextView? = null

    // The line showing when the user tucked the pill away; a newer line brings it back
    private var tuckedAtLine: String? = null

    fun bind(scope: CoroutineScope, appVisible: StateFlow<Boolean>) {
        scope.launch {
            combine(session.isRecording, appVisible) { live, visible -> live && !visible }
                .distinctUntilChanged()
                .collect { if (it) show() else hideAll() }
        }
        scope.launch {
            session.transcripts.collect { items ->
                label?.text = latestLine(items)
                // Tucked away, and someone has said something new since: show it again
                if (pill == null && glow != null && latestItem(items)?.id != tuckedAtLine) showPill()
            }
        }
        scope.launch {
            combine(session.aiAmplitude, session.micAmplitude) { ai, mic -> maxOf(ai, mic) }.collect { level ->
                val k = minOf(1f, level * 8f)
                glow?.let {
                    // Shows while someone speaks, fades out (smoothly, ~0.3 s) when they stop
                    val target = if (level > SPEAKING_LEVEL) 0.45f + 0.55f * k else 0f
                    it.alpha = it.alpha * 0.75f + target * 0.25f
                    it.scaleY = 1f + 1.2f * k
                }
                dot?.let {
                    it.scaleX = 1f + 0.6f * k
                    it.scaleY = 1f + 0.6f * k
                }
            }
        }
    }

    private fun latestItem(items: List<TranscriptItem>) = items.lastOrNull { it.sender == "assistant" || it.sender == "user" }

    private fun latestLine(items: List<TranscriptItem>): String =
        latestItem(items)?.text?.trim()?.takeLast(120) ?: "Listening…"

    private fun windowManager(): WindowManager? = PhoneAgentService.instance?.getSystemService(WindowManager::class.java)

    private fun show() {
        if (glow != null) return
        val agent = PhoneAgentService.instance ?: return // screen control is off: no overlay
        val wm = windowManager() ?: return
        val dp = agent.resources.displayMetrics.density
        tuckedAtLine = null

        // Bottom-edge glow line
        val line = View(agent).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, VIOLET, PINK, CYAN))
            alpha = 0f
            pivotY = (4 * dp)
        }
        val lineParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, (4 * dp).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        try {
            wm.addView(line, lineParams)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay not shown: ${e.message}")
            return
        }
        glow = line
        // Wake flash: the line flares, then settles
        line.animate().alpha(1f).scaleY(3f).setDuration(200).withEndAction {
            line.animate().scaleY(1f).alpha(0f).setDuration(500).start()
        }.start()
        showPill()
    }

    /** The small pill: pulsing dot + latest line; floats up from the bottom. */
    private fun showPill() {
        if (pill != null) return
        val agent = PhoneAgentService.instance ?: return
        val wm = windowManager() ?: return
        val dp = agent.resources.displayMetrics.density
        val pulse = View(agent).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(CYAN, VIOLET)).apply { shape = GradientDrawable.OVAL }
        }
        val text = TextView(agent).apply {
            text = latestLine(session.transcripts.value)
            setTextColor(Color.WHITE)
            textSize = 14f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
            maxWidth = (agent.resources.displayMetrics.widthPixels * 0.72f).toInt()
            setPadding((10 * dp).toInt(), 0, 0, 0)
        }
        val box = LinearLayout(agent).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E60F1424"))
                cornerRadius = 22 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#4422D3EE"))
            }
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            addView(pulse, LinearLayout.LayoutParams((10 * dp).toInt(), (10 * dp).toInt()))
            addView(text)
            alpha = 0f
            translationY = 40 * dp
        }
        // Touchable only on the pill itself; any touch elsewhere arrives as ACTION_OUTSIDE
        val boxParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = (36 * dp).toInt()
        }
        box.setOnTouchListener(dismissOnTouch())

        try {
            wm.addView(box, boxParams)
        } catch (e: Exception) {
            Log.w(TAG, "Pill not shown: ${e.message}")
            return
        }
        pill = box
        dot = pulse
        label = text
        box.animate().alpha(1f).translationY(0f).setDuration(220).start()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun dismissOnTouch() = View.OnTouchListener { _, event ->
        when (event.actionMasked) {
            // Elsewhere on screen: the user's own touch, unless the assistant is tapping right now
            MotionEvent.ACTION_OUTSIDE -> if (!session.webSocketClient.isToolRunning()) tuckPill()
            MotionEvent.ACTION_UP -> tuckPill()
        }
        false
    }

    /** The user touched the screen: hide the pill until someone says something new. */
    private fun tuckPill() {
        tuckedAtLine = latestItem(session.transcripts.value)?.id
        hidePill()
    }

    /** Removes the pill; the bottom wave keeps showing whenever someone speaks. */
    private fun hidePill() {
        val box = pill ?: return
        pill = null
        dot = null
        label = null
        box.animate().alpha(0f).translationY(box.height.toFloat()).setDuration(160).withEndAction {
            runCatching { windowManager()?.removeView(box) }
        }.start()
    }

    private fun hideAll() {
        hidePill()
        val line = glow ?: return
        glow = null
        line.animate().alpha(0f).setDuration(200).withEndAction {
            runCatching { windowManager()?.removeView(line) }
        }.start()
    }
}
