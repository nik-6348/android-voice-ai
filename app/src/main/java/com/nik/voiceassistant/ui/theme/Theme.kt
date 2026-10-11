package com.nik.voiceassistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = CyanAccent,
    secondary = PurpleAccent,
    background = BgDark,
    surface = SurfaceDark,
    onPrimary = BgDark,
    onSecondary = BgDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun NikVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
