package com.nik.voiceassistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.nik.voiceassistant.ui.theme.BrandCyan
import com.nik.voiceassistant.ui.theme.BrandPink
import com.nik.voiceassistant.ui.theme.BrandViolet
import kotlin.math.min

/**
 * The assistant's "face": a glowing orb that breathes while idle, swells with the voice level,
 * and has a slowly turning gradient ring while a conversation is live.
 *   level - current voice level (0..1, user or assistant)
 *   live  - a conversation is running
 *   speaking - the assistant is the one talking (warmer colours)
 */
@Composable
fun VoiceOrb(level: Float, live: Boolean, speaking: Boolean, modifier: Modifier = Modifier) {
    val loop = rememberInfiniteTransition(label = "orb")
    val spin by loop.animateFloat(0f, 360f, infiniteRepeatable(tween(if (live) 6000 else 14000, easing = LinearEasing)), label = "spin")
    val breath by loop.animateFloat(0.96f, 1.04f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "breath")
    val voice by animateFloatAsState(min(1f, level * 7f), spring(dampingRatio = 0.55f, stiffness = 220f), label = "voice")
    val glow by animateFloatAsState(if (live) 1f else 0.45f, tween(600), label = "glow")

    val core = if (speaking) listOf(BrandPink, BrandViolet, Color(0xFF1E1B4B)) else listOf(BrandCyan, BrandViolet, Color(0xFF0B1026))

    Canvas(modifier) {
        val c = center
        val base = size.minDimension / 2f * 0.62f
        val r = base * breath * (1f + 0.22f * voice)

        // Soft halo, brighter with the voice
        drawCircle(
            brush = Brush.radialGradient(
                listOf(core[0].copy(alpha = 0.35f * glow + 0.25f * voice), core[1].copy(alpha = 0.12f * glow), Color.Transparent),
                center = c, radius = r * 1.75f
            ),
            radius = r * 1.75f, center = c
        )
        // Turning gradient ring
        rotate(spin, c) {
            drawCircle(
                brush = Brush.sweepGradient(listOf(BrandCyan, BrandViolet, BrandPink, BrandCyan), center = c),
                radius = r * 1.18f, center = c,
                style = Stroke(width = r * (0.035f + 0.04f * voice)),
                alpha = 0.35f + 0.5f * glow
            )
        }
        // The orb, lit from the upper left
        drawCircle(
            brush = Brush.radialGradient(core, center = Offset(c.x - r * 0.35f, c.y - r * 0.4f), radius = r * 1.6f),
            radius = r, center = c
        )
        // Highlight
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), center = Offset(c.x - r * 0.35f, c.y - r * 0.45f), radius = r * 0.55f),
            radius = r * 0.55f, center = Offset(c.x - r * 0.35f, c.y - r * 0.45f)
        )
    }
}
