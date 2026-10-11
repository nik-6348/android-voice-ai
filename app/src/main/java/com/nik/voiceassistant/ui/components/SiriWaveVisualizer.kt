package com.nik.voiceassistant.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.nik.voiceassistant.ui.theme.CyanAccent
import com.nik.voiceassistant.ui.theme.PurpleAccent
import kotlin.math.sin

/**
 * Animated dynamic waveform visualizer inspired by Siri & Jarvis holograms.
 * Responds to real-time RMS voice amplitudes.
 */
@Composable
fun SiriWaveVisualizer(
    amplitude: Float,
    modifier: Modifier = Modifier.fillMaxWidth().height(160.dp),
    activeColor1: Color = CyanAccent,
    activeColor2: Color = PurpleAccent
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f

        // Base idle breathing amplitude + dynamic voice reaction
        val baseAmp = 8f
        val dynamicAmp = amplitude * (height * 0.42f)
        val totalAmp = baseAmp + dynamicAmp

        // Wave 1: Primary Cyan Harmonic
        val path1 = Path()
        path1.moveTo(0f, centerY)
        var x = 0f
        while (x <= width) {
            val progress = x / width
            // Bell curve window to taper wave at left and right edges
            val window = sin(progress * Math.PI).toFloat()
            val y = centerY + sin(progress * 4f * Math.PI.toFloat() + phase) * totalAmp * window
            path1.lineTo(x, y)
            x += 8f
        }
        drawPath(
            path = path1,
            brush = Brush.horizontalGradient(
                listOf(activeColor1.copy(alpha = 0.3f), activeColor1, activeColor1.copy(alpha = 0.3f))
            ),
            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        )

        // Wave 2: Secondary Purple Harmonic (slightly shifted frequency and phase)
        val path2 = Path()
        path2.moveTo(0f, centerY)
        x = 0f
        while (x <= width) {
            val progress = x / width
            val window = sin(progress * Math.PI).toFloat()
            val y = centerY + sin(progress * 5.2f * Math.PI.toFloat() - phase * 1.3f) * (totalAmp * 0.75f) * window
            path2.lineTo(x, y)
            x += 8f
        }
        drawPath(
            path = path2,
            brush = Brush.horizontalGradient(
                listOf(activeColor2.copy(alpha = 0.2f), activeColor2, activeColor2.copy(alpha = 0.2f))
            ),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Wave 3: Center Core Glow
        val path3 = Path()
        path3.moveTo(0f, centerY)
        x = 0f
        while (x <= width) {
            val progress = x / width
            val window = sin(progress * Math.PI).toFloat()
            val y = centerY + sin(progress * 2.8f * Math.PI.toFloat() + phase * 0.8f) * (totalAmp * 0.45f) * window
            path3.lineTo(x, y)
            x += 8f
        }
        drawPath(
            path = path3,
            color = Color.White.copy(alpha = 0.7f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
