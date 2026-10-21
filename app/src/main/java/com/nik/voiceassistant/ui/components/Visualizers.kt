package com.nik.voiceassistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import kotlin.math.sin

/*
 * Voice visuals that stay cheap: amplitudes and time are handed over as State and only read inside
 * draw lambdas, so a changing voice level redraws the orb without recomposing the screen around it,
 * and nothing asks for frames while there is nothing to animate.
 */

/** Seconds that advance every frame, but only while [running]; read it in draw lambdas. */
@Composable
fun rememberFrameClock(running: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val base = time.floatValue
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> time.floatValue = base + (now - start) / 1_000_000_000f }
    }
    return time
}

/** A 0..1 voice level from a raw amplitude flow, smoothed; read it in draw lambdas only. */
@Composable
fun rememberVoiceLevel(amplitude: Flow<Float>, gain: Float = 7f): State<Float> {
    val level = remember { Animatable(0f) }
    LaunchedEffect(amplitude) {
        amplitude.collectLatest { level.animateTo((it * gain).coerceIn(0f, 1f), tween(120)) }
    }
    return level.asState()
}

/**
 * True while the amplitude is above [threshold], staying true for [holdMs] after it drops, so the
 * gaps between words don't flip "Speaking" on and off.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
fun Flow<Float>.activeWithHold(threshold: Float = 0.02f, holdMs: Long = 700): Flow<Boolean> =
    map { it > threshold }
        .distinctUntilChanged()
        .transformLatest { on ->
            if (!on) delay(holdMs)
            emit(on)
        }
        .distinctUntilChanged()

/** What the assistant is doing, as the orb shows it. */
enum class OrbMode(val animates: Boolean) {
    IDLE(false), CONNECTING(true), LISTENING(false), THINKING(true), SPEAKING(true), MUTED(false), ERROR(false)
}

/**
 * The assistant's hero: featuring the cyber Trishul logo with reactive audio glow,
 * pulse wave rings, rotating arcs, and voice amplitude scaling.
 */
@Composable
fun VoiceOrb(
    level: State<Float>,
    mode: OrbMode,
    modifier: Modifier = Modifier,
    clock: State<Float> = rememberFrameClock(mode.animates)
) {
    val scheme = MaterialTheme.colorScheme
    val c1 by animateColorAsState(
        when (mode) {
            OrbMode.ERROR -> scheme.error
            OrbMode.SPEAKING -> Color(0xFFFFD700) // Gold
            OrbMode.IDLE, OrbMode.MUTED -> Color(0xFF00E5FF) // Cyber Cyan
            else -> Color(0xFF00E5FF)
        }, tween(500), label = "orb1"
    )
    val c2 by animateColorAsState(
        when (mode) {
            OrbMode.ERROR -> scheme.errorContainer
            OrbMode.SPEAKING -> Color(0xFFFF9100)
            OrbMode.IDLE, OrbMode.MUTED -> Color(0xFF1C7DFF) // Electric Blue
            else -> Color(0xFF9B72CB)
        }, tween(500), label = "orb2"
    )

    val lv = level.value
    val t = clock.value
    val sway = if (mode == OrbMode.SPEAKING) 0.03f * sin(t * 2.4f) else 0f
    val scaleFactor = 1f + 0.12f * lv + sway

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Dynamic glowing aura & reactive audio ripples behind the logo
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r0 = size.minDimension / 2f
            val baseR = r0 * 0.72f
            val c = Offset(size.width / 2f, size.height / 2f)

            // Outer reactive glow
            val halo = Brush.radialGradient(
                listOf(c1.copy(alpha = 0.45f + 0.35f * lv), c2.copy(alpha = 0.15f), Color.Transparent),
                center = c, radius = r0 * (0.85f + 0.25f * lv)
            )
            drawCircle(halo, r0, c)

            // Dynamic rings when active
            val stroke = Stroke(width = r0 * 0.035f)
            when (mode) {
                OrbMode.CONNECTING, OrbMode.THINKING -> {
                    val arc = Brush.sweepGradient(listOf(c1.copy(alpha = 0f), c1, c2, c1.copy(alpha = 0f)), center = c)
                    rotate(t * (if (mode == OrbMode.THINKING) 160f else 240f), c) {
                        drawCircle(arc, radius = baseR * 1.15f, center = c, style = stroke)
                    }
                }
                OrbMode.LISTENING -> {
                    drawCircle(
                        c1.copy(alpha = 0.35f + 0.45f * lv),
                        radius = baseR * (1.08f + 0.22f * lv),
                        center = c,
                        style = stroke
                    )
                    drawCircle(
                        c2.copy(alpha = 0.20f + 0.25f * lv),
                        radius = baseR * (1.20f + 0.28f * lv),
                        center = c,
                        style = Stroke(width = r0 * 0.025f)
                    )
                }
                OrbMode.SPEAKING -> {
                    drawCircle(
                        c1.copy(alpha = 0.40f + 0.40f * lv),
                        radius = baseR * (1.10f + 0.18f * lv),
                        center = c,
                        style = stroke
                    )
                }
                else -> {}
            }
        }

        // The Cyber Trishul Logo Image with audio reactivity & neon border
        Box(
            modifier = Modifier
                .fillMaxSize(0.76f)
                .graphicsLayer {
                    scaleX = scaleFactor
                    scaleY = scaleFactor
                }
                .clip(CircleShape)
                .background(Color(0xFF080C14))
                .border(
                    width = 2.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            c1.copy(alpha = 0.85f + 0.15f * lv),
                            c2.copy(alpha = 0.65f + 0.35f * lv)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = "Nik Voice Assistant",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Four rounded bars that move with the voice; flat dots when quiet. */
@Composable
fun VoiceBars(
    level: State<Float>,
    active: Boolean,
    clock: State<Float>,
    colors: List<Color>,
    idleColor: Color,
    modifier: Modifier = Modifier,
    width: Dp = 24.dp,
    height: Dp = 20.dp
) {
    Box(
        modifier.size(width, height).drawWithCache {
            val bar = size.width / 7f
            val gap = (size.width - bar * 4) / 3
            onDrawBehind {
                val amp = if (active) 0.2f + 0.8f * level.value else 0f
                for (i in 0 until 4) {
                    val wave = 0.5f + 0.5f * sin(clock.value * 9f + i * 1.3f)
                    val h = bar + (size.height - bar) * amp * (0.35f + 0.65f * wave)
                    drawRoundRect(
                        if (active) colors[i % colors.size] else idleColor,
                        topLeft = Offset(i * (bar + gap), (size.height - h) / 2),
                        size = Size(bar, h),
                        cornerRadius = CornerRadius(bar / 2)
                    )
                }
            }
        }
    )
}
