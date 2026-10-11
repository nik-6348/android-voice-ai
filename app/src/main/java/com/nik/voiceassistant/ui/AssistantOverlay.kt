package com.nik.voiceassistant.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
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
 * Google-Assistant-style panel over whatever app is open while a conversation runs and Nik's own
 * screen isn't showing: a glow flashes up from the bottom edge on wake, then a card shows what is
 * being said, with a glow line that pulses with the voices.
 *
 * Drawn as an accessibility overlay (screen control must be on; no extra permission). It never
 * takes touches, so the assistant can still tap things underneath, like a Send button.
 */
class AssistantOverlay(private val session: VoiceSession) {

    companion object {
        private const val TAG = "AssistantOverlay"
        private val CYAN = Color.parseColor("#22D3EE")
        private val VIOLET = Color.parseColor("#A855F7")
        private val PINK = Color.parseColor("#F472B6")
    }

    private var panel: View? = null
    private var label: TextView? = null
    private var glow: View? = null

    fun bind(scope: CoroutineScope, appVisible: StateFlow<Boolean>) {
        scope.launch {
            combine(session.isRecording, appVisible) { live, visible -> live && !visible }
                .distinctUntilChanged()
                .collect { if (it) show() else hide() }
        }
        scope.launch {
            session.transcripts.collect { label?.text = latestLine(it) }
        }
        scope.launch {
            combine(session.aiAmplitude, session.micAmplitude) { ai, mic -> maxOf(ai, mic) }.collect { level ->
                glow?.let {
                    val k = minOf(1f, level * 8f)
                    it.alpha = 0.55f + 0.45f * k
                    it.scaleY = 1f + 1.5f * k
                }
            }
        }
    }

    private fun latestLine(items: List<TranscriptItem>): String {
        val last = items.lastOrNull { it.sender == "assistant" || it.sender == "user" } ?: return "Listening…"
        return last.text.trim().takeLast(160)
    }

    private fun show() {
        if (panel != null) return
        val agent = PhoneAgentService.instance ?: return // screen control is off: no overlay
        val wm = agent.getSystemService(WindowManager::class.java) ?: return
        val dp = agent.resources.displayMetrics.density

        val title = TextView(agent).apply {
            text = "Nik"
            setTextColor(CYAN)
            textSize = 12f
            letterSpacing = 0.15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val line = TextView(agent).apply {
            text = latestLine(session.transcripts.value)
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }
        val card = LinearLayout(agent).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F00F1424"))
                cornerRadius = 26 * dp
                setStroke((1 * dp).toInt(), Color.parseColor("#3322D3EE"))
            }
            setPadding((18 * dp).toInt(), (14 * dp).toInt(), (18 * dp).toInt(), (16 * dp).toInt())
            addView(title)
            addView(line)
        }
        val bar = View(agent).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(CYAN, VIOLET, PINK, CYAN))
            alpha = 0.55f
        }
        // Soft colour wash from the bottom edge, flashed once on wake
        val flash = View(agent).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.TRANSPARENT, Color.parseColor("#5522D3EE"), Color.parseColor("#88A855F7"))
            )
            alpha = 0f
        }
        val root = LinearLayout(agent).apply {
            orientation = LinearLayout.VERTICAL
            addView(flash, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (120 * dp).toInt()))
            addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins((12 * dp).toInt(), 0, (12 * dp).toInt(), (10 * dp).toInt())
            })
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (4 * dp).toInt()))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM }

        try {
            wm.addView(root, params)
        } catch (e: Exception) {
            Log.w(TAG, "Overlay not shown: ${e.message}")
            return
        }
        panel = root
        label = line
        glow = bar

        // Slide up, flash the glow, then let it settle
        root.translationY = 220 * dp
        root.animate().translationY(0f).setDuration(240).start()
        flash.animate().alpha(1f).setDuration(220).withEndAction {
            flash.animate().alpha(0f).setDuration(700).start()
        }.start()
    }

    private fun hide() {
        val root = panel ?: return
        panel = null
        label = null
        glow = null
        val wm = PhoneAgentService.instance?.getSystemService(WindowManager::class.java)
        root.animate().translationY(root.height.toFloat()).alpha(0f).setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    runCatching { wm?.removeView(root) }
                }
            }).start()
    }
}
