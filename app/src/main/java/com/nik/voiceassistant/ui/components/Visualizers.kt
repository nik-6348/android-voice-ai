package com.nik.voiceassistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * The assistant's hero: a soft sphere in the theme's primary → tertiary colours. It swells with the
 * voice, sways while speaking, has a turning arc while connecting or thinking, a ring that follows
 * the user's voice while listening, and goes quiet (no frames at all) when idle.
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
            OrbMode.IDLE, OrbMode.MUTED -> scheme.secondary
            else -> scheme.primary
        }, tween(500), label = "orb1"
    )
    val c2 by animateColorAsState(
        when (mode) {
            OrbMode.ERROR -> scheme.errorContainer
            OrbMode.IDLE, OrbMode.MUTED -> scheme.primary
            else -> scheme.tertiary
        }, tween(500), label = "orb2"
    )
    Box(
        modifier.drawWithCache {
            val r0 = size.minDimension / 2f
            val baseR = r0 * 0.56f
            val c = Offset(size.width / 2f, size.height / 2f)
            val halo = Brush.radialGradient(
                listOf(c1.copy(alpha = 0.30f), c2.copy(alpha = 0.10f), Color.Transparent), center = c, radius = r0
            )
            val body = Brush.linearGradient(
                listOf(c1, c2), start = Offset(c.x - baseR, c.y - baseR), end = Offset(c.x + baseR, c.y + baseR)
            )
            val sheenCenter = Offset(c.x - baseR * 0.35f, c.y - baseR * 0.4f)
            val sheen = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent), center = sheenCenter, radius = baseR * 0.75f)
            val arc = Brush.sweepGradient(listOf(c1.copy(alpha = 0f), c1, c2, c1.copy(alpha = 0f)), center = c)
            val stroke = Stroke(width = r0 * 0.035f)
            onDrawBehind {
                val t = clock.value
                val lv = level.value
                val sway = if (mode == OrbMode.SPEAKING) 0.03f * sin(t * 2.4f) else 0f
                // Halo, brighter and wider with the voice
                scale(0.82f + 0.25f * lv, c) { drawCircle(halo, r0, c) }
                when (mode) {
                    OrbMode.CONNECTING, OrbMode.THINKING -> rotate(t * (if (mode == OrbMode.THINKING) 160f else 240f), c) {
                        drawCircle(arc, radius = baseR * 1.22f, center = c, style = stroke)
                    }
                    OrbMode.LISTENING -> drawCircle(
                        c1.copy(alpha = 0.25f + 0.45f * lv), radius = baseR * (1.16f + 0.22f * lv), center = c, style = stroke
                    )
                    else -> {}
                }
                scale(1f + 0.16f * lv + sway, c) {
                    drawCircle(body, baseR, c)
                    drawCircle(sheen, baseR, c)
                }
            }
        }
    )
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
