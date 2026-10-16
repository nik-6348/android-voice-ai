package com.nik.voiceassistant.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/**
 * A 3x3 dot grid the user draws a pattern on with their finger, like Android's own pattern lock.
 * Cells are numbered 0-8, row-major (0 top-left, 8 bottom-right). Calls [onPatternComplete] with the
 * drawn order once the finger lifts, only if at least 4 dots were connected (Android's own minimum).
 */
@Composable
fun PatternPad(
    modifier: Modifier = Modifier,
    onPatternComplete: (List<Int>) -> Unit
) {
    var selected by remember { mutableStateOf(listOf<Int>()) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    val dotColor = MaterialTheme.colorScheme.outline
    val activeColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .size(240.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragPos = offset
                        selected = listOfNotNull(dotAt(offset, size.width.toFloat()))
                    },
                    onDrag = { change, _ ->
                        dragPos = change.position
                        dotAt(change.position, size.width.toFloat())?.let { idx ->
                            if (idx !in selected) selected = selected + idx
                        }
                    },
                    onDragEnd = {
                        // The finger often lifts right as it reaches the last dot, before a move event
                        // past it is reported: check the last known position once more here too.
                        dragPos?.let { pos ->
                            dotAt(pos, size.width.toFloat())?.let { idx ->
                                if (idx !in selected) selected = selected + idx
                            }
                        }
                        dragPos = null
                        if (selected.size >= 4) onPatternComplete(selected)
                        selected = listOf()
                    },
                    onDragCancel = {
                        dragPos = null
                        selected = listOf()
                    }
                )
            }
    ) {
        val cell = size.width / 3f
        val centers = (0 until 9).map { i -> Offset(cell * (i % 3) + cell / 2, cell * (i / 3) + cell / 2) }

        if (selected.size > 1) {
            for (i in 0 until selected.size - 1) {
                drawLine(activeColor, centers[selected[i]], centers[selected[i + 1]], strokeWidth = 8f)
            }
        }
        val pos = dragPos
        if (selected.isNotEmpty() && pos != null) {
            drawLine(activeColor, centers[selected.last()], pos, strokeWidth = 8f)
        }
        centers.forEachIndexed { i, c ->
            val active = i in selected
            drawCircle(if (active) activeColor else dotColor, radius = if (active) 18f else 12f, center = c)
        }
    }
}

/** Which dot (if any, by closeness) contains this touch point, out of a 3x3 grid of the given pixel width. */
private fun dotAt(offset: Offset, widthPx: Float): Int? {
    if (widthPx <= 0f) return null
    val cell = widthPx / 3f
    val col = (offset.x / cell).toInt().coerceIn(0, 2)
    val row = (offset.y / cell).toInt().coerceIn(0, 2)
    val centerX = cell * col + cell / 2
    val centerY = cell * row + cell / 2
    val dist = hypot((offset.x - centerX).toDouble(), (offset.y - centerY).toDouble())
    return if (dist < cell * 0.42) row * 3 + col else null
}
