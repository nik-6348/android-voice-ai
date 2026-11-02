package com.nik.voiceassistant.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * High-definition, engaging HUD overlay for Android TV.
 * Uses TYPE_ACCESSIBILITY_OVERLAY via PhoneAgentService so no extra permissions are required.
 *
 * Supports two distinct presentation modes:
 * 1. Expanded Listening Panel: Wide glassmorphism panel with glowing orb, live transcribed speech,
 *    and actionable TV hints (completely covers native Jio UI).
 * 2. Compact Action HUD: Streamlined floating pill during LLM thinking, tool execution, and completed states.
 */
object TvHudOverlay {
    private const val TAG = "TvHudOverlay"

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null

    // Expanded Listening UI
    private var listeningLayout: LinearLayout? = null
    private var listeningOrbView: ImageView? = null
    private var listeningSpeechView: TextView? = null

    // Compact Action HUD UI
    private var compactLayout: LinearLayout? = null
    private var compactDotView: ImageView? = null
    private var compactTitleView: TextView? = null
    private var compactSubtitleView: TextView? = null

    private var pulseAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    @Volatile
    private var isVisible = false

    @Volatile
    private var isListeningMode = false

    val isListening: Boolean
        get() = isVisible && isListeningMode

    fun isListeningOrActive(): Boolean = isVisible

    /**
     * Instantly shows the expanded Listening Panel when the remote mic is triggered.
     * Replaces the native Jio Voice Assistant UI with Shiv's custom neon glass UI.
     */
    fun showListening(context: Context) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isListeningMode = true
                listeningLayout?.visibility = View.VISIBLE
                compactLayout?.visibility = View.GONE

                // Default initial waiting speech state
                listeningSpeechView?.text = "Boliye, main sun raha hoon..."
                listeningSpeechView?.setTextColor(Color.parseColor("#94A3B8"))
                listeningSpeechView?.setTypeface(null, android.graphics.Typeface.ITALIC)

                val density = hostContext.resources.displayMetrics.density
                val widthPx = (720 * density).toInt()
                val yOffset = (36 * density).toInt()

                attachOrUpdateWindow(hostContext, widthPx, yOffset)
                startOrbPulseAnimation(listeningOrbView)

                // Generous safety dismiss in case no speech arrives
                scheduleDismiss(12000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show listening HUD: ${e.message}")
            }
        }
    }

    /**
     * Updates the recognized speech in real time as the user speaks into the remote.
     */
    fun updateSpeech(speechText: String) {
        mainHandler.post {
            if (!isVisible || !isListeningMode) return@post
            listeningSpeechView?.text = "\"$speechText\""
            listeningSpeechView?.setTextColor(Color.parseColor("#F8FAFC"))
            listeningSpeechView?.setTypeface(null, android.graphics.Typeface.BOLD)
            scheduleDismiss(10000L)
        }
    }

    /**
     * Transitions smoothly to the compact processing HUD when the speech is being analyzed.
     */
    fun showProcessing(context: Context, query: String) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isListeningMode = false
                listeningLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE

                compactTitleView?.text = "⚡ Shiv Soch Raha Hai..."
                compactSubtitleView?.visibility = View.VISIBLE
                compactSubtitleView?.text = query
                setDotColor(Color.parseColor("#38BDF8")) // Cyan

                val density = hostContext.resources.displayMetrics.density
                val widthPx = (480 * density).toInt()
                val yOffset = (48 * density).toInt()

                attachOrUpdateWindow(hostContext, widthPx, yOffset)
                startOrbPulseAnimation(compactDotView)
                scheduleDismiss(10000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show processing HUD: ${e.message}")
            }
        }
    }

    /**
     * Displays tool execution progress (e.g. YouTube search, tapping card).
     */
    fun showAction(context: Context, actionTitle: String, details: String? = null) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isListeningMode = false
                listeningLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE

                compactTitleView?.text = actionTitle
                if (!details.isNullOrBlank()) {
                    compactSubtitleView?.visibility = View.VISIBLE
                    compactSubtitleView?.text = details
                } else {
                    compactSubtitleView?.visibility = View.GONE
                }
                setDotColor(Color.parseColor("#F59E0B")) // Amber

                val density = hostContext.resources.displayMetrics.density
                val widthPx = (480 * density).toInt()
                val yOffset = (48 * density).toInt()

                attachOrUpdateWindow(hostContext, widthPx, yOffset)
                scheduleDismiss(12000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show action HUD: ${e.message}")
            }
        }
    }

    /**
     * Displays instant playback stopped confirmation.
     */
    fun showStopped(context: Context) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isListeningMode = false
                listeningLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE

                compactTitleView?.text = "⏹️ Playback Stopped"
                compactSubtitleView?.visibility = View.VISIBLE
                compactSubtitleView?.text = "Audio aur video pause kar diya gaya"
                setDotColor(Color.parseColor("#EF4444")) // Red

                val density = hostContext.resources.displayMetrics.density
                val widthPx = (460 * density).toInt()
                val yOffset = (48 * density).toInt()

                attachOrUpdateWindow(hostContext, widthPx, yOffset)
                stopPulseAnimation()
                scheduleDismiss(2200L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show stopped HUD: ${e.message}")
            }
        }
    }

    /**
     * Displays completion message and auto-dismisses.
     */
    fun showCompleted(context: Context, message: String? = null) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isListeningMode = false
                listeningLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE

                compactTitleView?.text = "✓ ${message ?: "Completed"}"
                compactSubtitleView?.visibility = View.GONE
                setDotColor(Color.parseColor("#10B981")) // Green

                val density = hostContext.resources.displayMetrics.density
                val widthPx = (440 * density).toInt()
                val yOffset = (48 * density).toInt()

                attachOrUpdateWindow(hostContext, widthPx, yOffset)
                stopPulseAnimation()
                scheduleDismiss(2200L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show completed HUD: ${e.message}")
            }
        }
    }

    /**
     * Standard unified show method for backwards compatibility with VoiceSession.
     */
    fun show(context: Context, title: String, subtitle: String? = null, autoDismissMs: Long = 6000L) {
        mainHandler.post {
            when {
                title.contains("✓") || title.contains("Ready") || title.contains("Play") -> showCompleted(context, subtitle ?: title)
                title.contains("⚙️") -> showAction(context, title, subtitle)
                title.contains("⏹️") -> showStopped(context)
                title.contains("🎙️") && subtitle?.contains("processing", ignoreCase = true) == true -> showProcessing(context, title.replace("🎙️", "").trim(' ', '"'))
                else -> {
                    val service = PhoneAgentService.instance
                    val hostContext = service ?: context.applicationContext
                    ensureViews(hostContext)

                    isListeningMode = false
                    listeningLayout?.visibility = View.GONE
                    compactLayout?.visibility = View.VISIBLE

                    compactTitleView?.text = title
                    if (!subtitle.isNullOrBlank()) {
                        compactSubtitleView?.visibility = View.VISIBLE
                        compactSubtitleView?.text = subtitle
                    } else {
                        compactSubtitleView?.visibility = View.GONE
                    }

                    val color = when {
                        title.contains("⚠️") || title.contains("Error") -> Color.parseColor("#EF4444")
                        else -> Color.parseColor("#38BDF8")
                    }
                    setDotColor(color)

                    val density = hostContext.resources.displayMetrics.density
                    val widthPx = (480 * density).toInt()
                    val yOffset = (48 * density).toInt()

                    attachOrUpdateWindow(hostContext, widthPx, yOffset)
                    scheduleDismiss(autoDismissMs)
                }
            }
        }
    }

    /**
     * Standard unified update method for VoiceSession tool updates.
     */
    fun update(title: String, subtitle: String? = null, autoDismissMs: Long = 4000L) {
        mainHandler.post {
            if (!isVisible) return@post
            when {
                title.contains("✓") -> showCompleted(PhoneAgentService.instance ?: return@post, subtitle ?: title)
                title.contains("⚙️") -> showAction(PhoneAgentService.instance ?: return@post, title, subtitle)
                else -> {
                    compactTitleView?.text = title
                    if (!subtitle.isNullOrBlank()) {
                        compactSubtitleView?.visibility = View.VISIBLE
                        compactSubtitleView?.text = subtitle
                    } else {
                        compactSubtitleView?.visibility = View.GONE
                    }
                    scheduleDismiss(autoDismissMs)
                }
            }
        }
    }

    fun scheduleDismiss(delayMs: Long) {
        mainHandler.post {
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            if (delayMs > 0) {
                val r = Runnable { hide() }
                dismissRunnable = r
                mainHandler.postDelayed(r, delayMs)
            }
        }
    }

    fun hide() {
        mainHandler.post {
            if (!isVisible || rootContainer == null) return@post
            dismissRunnable?.let { mainHandler.removeCallbacks(it) }
            stopPulseAnimation()
            rootContainer?.animate()
                ?.alpha(0f)
                ?.translationY(30f)
                ?.setDuration(200)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        try {
                            if (isVisible && rootContainer != null) {
                                windowManager?.removeViewImmediate(rootContainer)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error removing view: ${e.message}")
                        } finally {
                            isVisible = false
                            isListeningMode = false
                        }
                    }
                })
                ?.start()
        }
    }

    private fun attachOrUpdateWindow(context: Context, targetWidthPx: Int, targetYPx: Int) {
        val targetContext = PhoneAgentService.instance ?: context
        val wm = (targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm

        val service = PhoneAgentService.instance
        val type = when {
            service != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(targetContext) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        if (rootContainer?.parent != null && !isVisible) {
            try {
                wm.removeViewImmediate(rootContainer)
            } catch (ignored: Exception) {}
        }

        if (!isVisible && rootContainer != null) {
            val params = WindowManager.LayoutParams(
                targetWidthPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = targetYPx
            }

            rootContainer?.alpha = 0f
            rootContainer?.translationY = 40f
            try {
                wm.addView(rootContainer, params)
                isVisible = true

                rootContainer?.animate()
                    ?.alpha(1f)
                    ?.translationY(0f)
                    ?.setDuration(220)
                    ?.start()
            } catch (e: Exception) {
                Log.w(TAG, "addView failed: ${e.message}")
            }
        } else if (isVisible && rootContainer != null) {
            val params = rootContainer?.layoutParams as? WindowManager.LayoutParams
            if (params != null && (params.width != targetWidthPx || params.y != targetYPx)) {
                params.width = targetWidthPx
                params.y = targetYPx
                try {
                    wm.updateViewLayout(rootContainer, params)
                } catch (e: Exception) {
                    Log.w(TAG, "updateViewLayout failed: ${e.message}")
                }
            }
        }
    }

    private fun setDotColor(color: Int) {
        (compactDotView?.drawable as? GradientDrawable)?.setColor(color)
    }

    private fun startOrbPulseAnimation(targetView: View?) {
        targetView ?: return
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(targetView, "alpha", 0.4f, 1f).apply {
            duration = 550
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        listeningOrbView?.alpha = 1f
        compactDotView?.alpha = 1f
    }

    private fun ensureViews(context: Context) {
        if (rootContainer != null) return

        val density = context.resources.displayMetrics.density

        // Root Container
        rootContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ==========================================
        // 1. EXPANDED LISTENING PANEL (Replaces Jio UI)
        // ==========================================
        listeningLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (26 * density).toInt()
            val padV = (18 * density).toInt()
            setPadding(padH, padV, padH, padV)

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F40A0F1D")) // 95% Deep Obsidian Slate
                cornerRadius = 24 * density
                setStroke((2.5f * density).toInt(), Color.parseColor("#06B6D4")) // Neon Cyan
            }
            background = bg
            elevation = 24 * density
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Header Row: Orb, Title, Spacer, Listening Badge
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        listeningOrbView = ImageView(context).apply {
            val orbSize = (22 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(orbSize, orbSize).apply {
                marginEnd = (12 * density).toInt()
            }
            val orbDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#06B6D4")) // Cyan Glow Orb
            }
            setImageDrawable(orbDrawable)
        }
        headerRow.addView(listeningOrbView)

        val brandTitle = TextView(context).apply {
            text = "✨ SHIV AI ASSISTANT"
            textSize = 14f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.06f
        }
        headerRow.addView(brandTitle)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        headerRow.addView(spacer)

        val badge = TextView(context).apply {
            text = "● SUN RAHA HOON"
            textSize = 11f
            setTextColor(Color.parseColor("#10B981"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val bPadH = (10 * density).toInt()
            val bPadV = (4 * density).toInt()
            setPadding(bPadH, bPadV, bPadH, bPadV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2510B981"))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), Color.parseColor("#4D10B981"))
            }
        }
        headerRow.addView(badge)
        listeningLayout?.addView(headerRow)

        // Divider
        val divider = View(context).apply {
            val dHeight = (1 * density).toInt().coerceAtLeast(1)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dHeight
            ).apply {
                topMargin = (10 * density).toInt()
                bottomMargin = (10 * density).toInt()
            }
            setBackgroundColor(Color.parseColor("#2638BDF8"))
        }
        listeningLayout?.addView(divider)

        // Speech Text Display (Real-time live transcript)
        listeningSpeechView = TextView(context).apply {
            textSize = 19f
            setTextColor(Color.parseColor("#94A3B8"))
            setTypeface(null, android.graphics.Typeface.ITALIC)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Boliye, main sun raha hoon..."
        }
        listeningLayout?.addView(listeningSpeechView)

        // Footer Hints Row
        val hintText = TextView(context).apply {
            text = "💡 'Stop' ya 'Ruko' bol kar pause karein  •  Back button dabakar cancel"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            val marginT = (8 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginT }
        }
        listeningLayout?.addView(hintText)

        rootContainer?.addView(listeningLayout)

        // ==========================================
        // 2. COMPACT ACTION HUD (Processing & Actions)
        // ==========================================
        compactLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (22 * density).toInt()
            val padV = (14 * density).toInt()
            setPadding(padH, padV, padH, padV)

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#F40B0F19"))
                cornerRadius = 28 * density
                setStroke((2 * density).toInt(), Color.parseColor("#38BDF8"))
            }
            background = bg
            elevation = 16 * density
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        compactDotView = ImageView(context).apply {
            val iconSize = (18 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                marginEnd = (14 * density).toInt()
            }
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#38BDF8"))
            }
            setImageDrawable(dot)
        }
        compactLayout?.addView(compactDotView)

        val compactTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        compactTitleView = TextView(context).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        compactTextCol.addView(compactTitleView)

        compactSubtitleView = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            val margin = (2 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = margin }
        }
        compactTextCol.addView(compactSubtitleView)

        compactLayout?.addView(compactTextCol)
        rootContainer?.addView(compactLayout)
    }
}
