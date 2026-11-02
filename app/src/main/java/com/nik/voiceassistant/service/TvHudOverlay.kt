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
 * 1. Full-Screen Immersion Mode (Listening & Informational Answers):
 *    - Completely covers the screen (masking native Jio Voice Assist UI)
 *    - Dynamic glowing mic waveform bars reacting to speech
 *    - User question display ("AAPNE POOCHHA")
 *    - Agent answer card ("SHIV KA JAWAAB") for Q&A without opening any app
 * 2. Compact Bottom Action HUD:
 *    - Automatically collapses to a sleek bottom floating pill during tool execution
 *      (e.g. Live TV tuning, YouTube card playback) so content plays completely unblocked!
 */
object TvHudOverlay {
    private const val TAG = "TvHudOverlay"

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null

    // 1. Full-Screen Immersion UI
    private var fullScreenLayout: LinearLayout? = null
    private var statusBadgeView: TextView? = null
    private var listeningSpeechView: TextView? = null
    private var answerCardContainer: LinearLayout? = null
    private var agentAnswerView: TextView? = null
    private val waveBars = ArrayList<View>()
    private val waveAnimators = ArrayList<ValueAnimator>()

    // 2. Compact Bottom Action HUD UI
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
    private var isFullScreenMode = false

    val isListening: Boolean
        get() = isVisible && isFullScreenMode

    fun isListeningOrActive(): Boolean = isVisible

    /**
     * Instantly shows the Full-Screen Immersion Overlay when remote mic is triggered.
     * Completely masks native Jio Voice Assistant UI with Shiv's neon audio wave UI.
     */
    fun showListening(context: Context) {
        mainHandler.post {
            try {
                if (isVisible && isFullScreenMode) {
                    // Already actively listening: ignore repetitive accessibility events to prevent flicker
                    return@post
                }
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isFullScreenMode = true
                fullScreenLayout?.visibility = View.VISIBLE
                compactLayout?.visibility = View.GONE
                answerCardContainer?.visibility = View.GONE

                // Reset badge to listening state
                statusBadgeView?.text = "● SUN RAHA HOON"
                statusBadgeView?.setTextColor(Color.parseColor("#10B981")) // Emerald Green
                (statusBadgeView?.background as? GradientDrawable)?.apply {
                    setColor(Color.parseColor("#2510B981"))
                    setStroke(2, Color.parseColor("#4D10B981"))
                }

                // Default initial waiting speech state
                listeningSpeechView?.text = "Boliye, main sun raha hoon..."
                listeningSpeechView?.setTextColor(Color.parseColor("#94A3B8"))
                listeningSpeechView?.setTypeface(null, android.graphics.Typeface.ITALIC)

                attachOrUpdateWindow(hostContext, isFullScreen = true)
                startWaveAnimation()

                // Safety dismiss in case no speech arrives for 15s
                scheduleDismiss(15000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show listening HUD: ${e.message}")
            }
        }
    }

    /**
     * Updates recognized speech in real time as the user speaks into the remote.
     */
    fun updateSpeech(speechText: String) {
        mainHandler.post {
            if (!isVisible || !isFullScreenMode) return@post
            val formatted = "\"$speechText\""
            if (listeningSpeechView?.text?.toString() == formatted) return@post
            listeningSpeechView?.text = formatted
            listeningSpeechView?.setTextColor(Color.parseColor("#F8FAFC"))
            listeningSpeechView?.setTypeface(null, android.graphics.Typeface.BOLD)
            scheduleDismiss(15000L)
        }
    }

    /**
     * Transitions smoothly to thinking state while Gemini analyzes the user's query.
     */
    fun showProcessing(context: Context, query: String) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isFullScreenMode = true
                fullScreenLayout?.visibility = View.VISIBLE
                compactLayout?.visibility = View.GONE
                answerCardContainer?.visibility = View.GONE

                statusBadgeView?.text = "⚡ SOCH RAHA HOON..."
                statusBadgeView?.setTextColor(Color.parseColor("#38BDF8")) // Cyan
                (statusBadgeView?.background as? GradientDrawable)?.apply {
                    setColor(Color.parseColor("#2538BDF8"))
                    setStroke(2, Color.parseColor("#4D38BDF8"))
                }

                listeningSpeechView?.text = "\"$query\""
                listeningSpeechView?.setTextColor(Color.parseColor("#F8FAFC"))
                listeningSpeechView?.setTypeface(null, android.graphics.Typeface.BOLD)

                attachOrUpdateWindow(hostContext, isFullScreen = true)
                scheduleDismiss(12000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show processing HUD: ${e.message}")
            }
        }
    }

    /**
     * Displays an informational answer directly on the Full-Screen Overlay without opening any app!
     */
    fun showAnswer(context: Context, userQuestion: String?, agentAnswer: String) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isFullScreenMode = true
                fullScreenLayout?.visibility = View.VISIBLE
                compactLayout?.visibility = View.GONE

                statusBadgeView?.text = "💬 JAWAAB"
                statusBadgeView?.setTextColor(Color.parseColor("#A78BFA")) // Purple / Lavender
                (statusBadgeView?.background as? GradientDrawable)?.apply {
                    setColor(Color.parseColor("#25A78BFA"))
                    setStroke(2, Color.parseColor("#4DA78BFA"))
                }

                if (!userQuestion.isNullOrBlank()) {
                    listeningSpeechView?.text = "\"$userQuestion\""
                    listeningSpeechView?.setTextColor(Color.parseColor("#F8FAFC"))
                    listeningSpeechView?.setTypeface(null, android.graphics.Typeface.BOLD)
                }

                answerCardContainer?.visibility = View.VISIBLE
                agentAnswerView?.text = agentAnswer

                attachOrUpdateWindow(hostContext, isFullScreen = true)
                stopWaveAnimation()
                scheduleDismiss(9000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show answer HUD: ${e.message}")
            }
        }
    }

    /**
     * Transitions from Full Screen to Compact Bottom Pill HUD when an ACTION starts
     * (e.g. tuning Live TV, starting YouTube video) so content plays unblocked.
     */
    fun showAction(context: Context, actionTitle: String, details: String? = null) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isFullScreenMode = false
                fullScreenLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE
                stopWaveAnimation()

                compactTitleView?.text = actionTitle
                if (!details.isNullOrBlank()) {
                    compactSubtitleView?.visibility = View.VISIBLE
                    compactSubtitleView?.text = details
                } else {
                    compactSubtitleView?.visibility = View.GONE
                }
                setDotColor(Color.parseColor("#F59E0B")) // Amber

                attachOrUpdateWindow(hostContext, isFullScreen = false)
                startOrbPulseAnimation(compactDotView)
                scheduleDismiss(12000L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show action HUD: ${e.message}")
            }
        }
    }

    /**
     * Displays instant playback stopped confirmation in the bottom pill HUD.
     */
    fun showStopped(context: Context) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isFullScreenMode = false
                fullScreenLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE
                stopWaveAnimation()

                compactTitleView?.text = "⏹️ Playback Stopped"
                compactSubtitleView?.visibility = View.VISIBLE
                compactSubtitleView?.text = "Audio aur video pause kar diya gaya"
                setDotColor(Color.parseColor("#EF4444")) // Red

                attachOrUpdateWindow(hostContext, isFullScreen = false)
                stopPulseAnimation()
                scheduleDismiss(2200L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show stopped HUD: ${e.message}")
            }
        }
    }

    /**
     * Displays completion message in the bottom pill HUD and auto-dismisses.
     */
    fun showCompleted(context: Context, message: String? = null) {
        mainHandler.post {
            try {
                val service = PhoneAgentService.instance
                val hostContext = service ?: context.applicationContext
                ensureViews(hostContext)

                isFullScreenMode = false
                fullScreenLayout?.visibility = View.GONE
                compactLayout?.visibility = View.VISIBLE
                stopWaveAnimation()

                compactTitleView?.text = "✓ ${message ?: "Completed"}"
                compactSubtitleView?.visibility = View.GONE
                setDotColor(Color.parseColor("#10B981")) // Green

                attachOrUpdateWindow(hostContext, isFullScreen = false)
                stopPulseAnimation()
                scheduleDismiss(2500L)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show completed HUD: ${e.message}")
            }
        }
    }

    /**
     * Standard unified update method for VoiceSession tool updates.
     */
    fun update(title: String, subtitle: String? = null, autoDismissMs: Long = 4000L) {
        mainHandler.post {
            val service = PhoneAgentService.instance ?: return@post
            when {
                title.contains("✓") -> showCompleted(service, subtitle ?: title)
                title.contains("⚙️") || title.contains("⚡") -> showAction(service, title, subtitle)
                title.contains("⏹️") -> showStopped(service)
                title.startsWith("Shiv:") -> showAnswer(service, null, subtitle ?: title.removePrefix("Shiv:").trim())
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

    /**
     * Backwards-compatible show method.
     */
    fun show(context: Context, title: String, subtitle: String? = null, autoDismissMs: Long = 6000L) {
        update(title, subtitle, autoDismissMs)
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
            stopWaveAnimation()
            rootContainer?.animate()
                ?.alpha(0f)
                ?.translationY(20f)
                ?.setDuration(180)
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
                            isFullScreenMode = false
                        }
                    }
                })
                ?.start()
        }
    }

    private fun attachOrUpdateWindow(context: Context, isFullScreen: Boolean) {
        val targetContext = PhoneAgentService.instance ?: context
        val wm = (targetContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager) ?: return
        windowManager = wm

        val service = PhoneAgentService.instance
        val type = when {
            service != null -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(targetContext) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

        val density = targetContext.resources.displayMetrics.density
        val targetWidth = if (isFullScreen) WindowManager.LayoutParams.MATCH_PARENT else (520 * density).toInt()
        val targetHeight = if (isFullScreen) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        val targetGravity = if (isFullScreen) Gravity.FILL else (Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        val targetY = if (isFullScreen) 0 else (44 * density).toInt()

        if (rootContainer?.parent != null && !isVisible) {
            try {
                wm.removeViewImmediate(rootContainer)
            } catch (ignored: Exception) {}
        }

        if (!isVisible && rootContainer != null) {
            val params = WindowManager.LayoutParams(
                targetWidth,
                targetHeight,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = targetGravity
                y = targetY
            }

            if (isFullScreen) {
                rootContainer?.alpha = 1f
                rootContainer?.translationY = 0f
            } else {
                rootContainer?.alpha = 0f
                rootContainer?.translationY = 30f
            }
            try {
                wm.addView(rootContainer, params)
                isVisible = true

                if (!isFullScreen) {
                    rootContainer?.animate()
                        ?.alpha(1f)
                        ?.translationY(0f)
                        ?.setDuration(220)
                        ?.start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "addView failed: ${e.message}")
            }
        } else if (isVisible && rootContainer != null) {
            val params = rootContainer?.layoutParams as? WindowManager.LayoutParams
            if (params != null && (params.width != targetWidth || params.height != targetHeight || params.gravity != targetGravity || params.y != targetY)) {
                params.width = targetWidth
                params.height = targetHeight
                params.gravity = targetGravity
                params.y = targetY
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
        compactDotView?.alpha = 1f
    }

    private fun startWaveAnimation() {
        stopWaveAnimation()
        waveBars.forEachIndexed { i, bar ->
            val density = bar.resources.displayMetrics.density
            val minH = (12 * density).toInt()
            val maxH = ((26 + (i % 3) * 16) * density).toInt()
            val anim = ValueAnimator.ofInt(minH, maxH).apply {
                duration = (360 + (i * 90)).toLong()
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { va ->
                    val h = va.animatedValue as Int
                    val lp = bar.layoutParams
                    lp.height = h
                    bar.layoutParams = lp
                }
                start()
            }
            waveAnimators.add(anim)
        }
    }

    private fun stopWaveAnimation() {
        waveAnimators.forEach { it.cancel() }
        waveAnimators.clear()
    }

    private fun ensureViews(context: Context) {
        if (rootContainer != null) return

        val density = context.resources.displayMetrics.density

        // Root Container
        rootContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // ==========================================
        // 1. FULL-SCREEN IMMERSION OVERLAY (Listening & Answers)
        // ==========================================
        fullScreenLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (52 * density).toInt()
            val padV = (36 * density).toInt()
            setPadding(padH, padV, padH, padV)

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#FF070B16")) // 100% Solid Deep Obsidian - completely covers native Jio UI
            }
            background = bg
            elevation = 32 * density
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Header Row: Logo, Title, Spacer, Status Badge
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val brandTitle = TextView(context).apply {
            text = "✨ SHIV AI ASSISTANT"
            textSize = 15f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.08f
        }
        headerRow.addView(brandTitle)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        headerRow.addView(spacer)

        statusBadgeView = TextView(context).apply {
            text = "● SUN RAHA HOON"
            textSize = 11.5f
            setTextColor(Color.parseColor("#10B981"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            val bPadH = (12 * density).toInt()
            val bPadV = (5 * density).toInt()
            setPadding(bPadH, bPadV, bPadH, bPadV)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2510B981"))
                cornerRadius = 14 * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#4D10B981"))
            }
        }
        headerRow.addView(statusBadgeView)
        fullScreenLayout?.addView(headerRow)

        // Mic Wave Container (Equalizer bars)
        val waveContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val marginV = (24 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (60 * density).toInt()
            ).apply {
                topMargin = marginV
                bottomMargin = marginV
            }
        }

        waveBars.clear()
        for (i in 0 until 5) {
            val bar = View(context).apply {
                val barW = (7 * density).toInt()
                val barH = (16 * density).toInt()
                val marginH = (6 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(barW, barH).apply {
                    setMargins(marginH, 0, marginH, 0)
                }
                val barBg = GradientDrawable().apply {
                    setColor(Color.parseColor("#06B6D4")) // Neon Cyan
                    cornerRadius = 4 * density
                }
                background = barBg
            }
            waveBars.add(bar)
            waveContainer.addView(bar)
        }
        fullScreenLayout?.addView(waveContainer)

        // User Question Card
        val userCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val cPadH = (24 * density).toInt()
            val cPadV = (16 * density).toInt()
            setPadding(cPadH, cPadV, cPadH, cPadV)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E293B")) // Slate glass
                cornerRadius = 18 * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#4038BDF8"))
            }
            background = bg
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (18 * density).toInt()
            }
        }

        val userLabel = TextView(context).apply {
            text = "🗣️ AAPNE POOCHHA:"
            textSize = 12f
            setTextColor(Color.parseColor("#38BDF8"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
        }
        userCard.addView(userLabel)

        listeningSpeechView = TextView(context).apply {
            textSize = 21f
            setTextColor(Color.parseColor("#94A3B8"))
            setTypeface(null, android.graphics.Typeface.ITALIC)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "Boliye, main sun raha hoon..."
            val marginT = (6 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginT }
        }
        userCard.addView(listeningSpeechView)
        fullScreenLayout?.addView(userCard)

        // Agent Answer Card (for Informational Queries)
        answerCardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val cPadH = (24 * density).toInt()
            val cPadV = (16 * density).toInt()
            setPadding(cPadH, cPadV, cPadH, cPadV)
            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#1E064E3B")) // Deep Emerald glass
                cornerRadius = 18 * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#4010B981"))
            }
            background = bg
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val agentLabel = TextView(context).apply {
            text = "🤖 SHIV KA JAWAAB:"
            textSize = 12f
            setTextColor(Color.parseColor("#10B981"))
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.05f
        }
        answerCardContainer?.addView(agentLabel)

        agentAnswerView = TextView(context).apply {
            textSize = 19f
            setTextColor(Color.parseColor("#F1F5F9"))
            setTypeface(null, android.graphics.Typeface.NORMAL)
            maxLines = 5
            ellipsize = android.text.TextUtils.TruncateAt.END
            val marginT = (6 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = marginT }
        }
        answerCardContainer?.addView(agentAnswerView)
        fullScreenLayout?.addView(answerCardContainer)

        val bottomSpacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        }
        fullScreenLayout?.addView(bottomSpacer)

        // Footer Hints
        val hintText = TextView(context).apply {
            text = "💡 TV remote se bole  •  'Stop' bol kar pause karein  •  Back button dabakar cancel"
            textSize = 11.5f
            setTextColor(Color.parseColor("#64748B"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        fullScreenLayout?.addView(hintText)
        rootContainer?.addView(fullScreenLayout)

        // ==========================================
        // 2. COMPACT BOTTOM ACTION HUD (Tool Execution & Live Playback)
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
