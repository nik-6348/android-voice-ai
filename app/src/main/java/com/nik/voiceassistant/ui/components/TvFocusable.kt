package com.nik.voiceassistant.ui.components

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp

// High-contrast Android TV focus colors
val TvFocusColor = Color(0xFF38BDF8) // Bright Cyan Blue
val TvFocusBorderWidth = 2.5.dp

/**
 * Makes any Compose component fully TV Remote / D-Pad friendly.
 * Shows a high-contrast glowing border on focus, subtle zoom, and triggers onSelect on DPAD_CENTER / ENTER.
 */
@Composable
fun Modifier.tvFocusable(
    shape: Shape = RoundedCornerShape(12.dp),
    scaleOnFocus: Boolean = true,
    onSelect: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused && scaleOnFocus) 1.03f else 1f,
        animationSpec = tween(120),
        label = "tv_focus_scale"
    )

    return this
        .scale(scale)
        .onFocusChanged { isFocused = it.isFocused }
        .border(
            width = if (isFocused) TvFocusBorderWidth else 0.dp,
            color = if (isFocused) TvFocusColor else Color.Transparent,
            shape = shape
        )
        .focusable()
        .onKeyEvent { keyEvent ->
            if (keyEvent.type == KeyEventType.KeyDown && onSelect != null) {
                when (keyEvent.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        onSelect()
                        true
                    }
                    else -> false
                }
            } else false
        }
}

/**
 * Essential for Android TV TextFields and Inputs.
 * Prevents D-Pad arrow keys from getting trapped inside the TextField,
 * allowing smooth navigation Up/Down/Back with the TV remote.
 */
@Composable
fun Modifier.tvDpadNavigable(
    focusManager: FocusManager,
    shape: Shape = RoundedCornerShape(24.dp),
    onSend: (() -> Unit)? = null
): Modifier {
    var isFocused by remember { mutableStateOf(false) }

    return this
        .onFocusChanged { isFocused = it.isFocused }
        .border(
            width = if (isFocused) TvFocusBorderWidth else 0.dp,
            color = if (isFocused) TvFocusColor else Color.Transparent,
            shape = shape
        )
        .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) {
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        focusManager.moveFocus(FocusDirection.Up)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusManager.moveFocus(FocusDirection.Down)
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (isFocused) {
                            focusManager.clearFocus()
                            true
                        } else false
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (onSend != null) {
                            onSend()
                            true
                        } else false
                    }
                    else -> false
                }
            } else false
        }
}

