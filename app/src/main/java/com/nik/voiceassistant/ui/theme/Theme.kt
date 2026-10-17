package com.nik.voiceassistant.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** Extra colour roles on top of MaterialTheme.colorScheme. */
object NikTheme {
    val extended: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}

/** 8 dp grid. */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

/**
 * The app's theme: follows the system's light / dark setting, uses Material You colours from the
 * wallpaper on Android 12+ and the brand palette before that. Also used by the overlay windows
 * (no activity there, so the system bar colours are only set for an activity).
 */
@Composable
fun NikVoiceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = remember(darkTheme, dynamicColor, context) {
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            darkTheme -> BrandDarkScheme
            else -> BrandLightScheme
        }
    }
    val extended = if (darkTheme) DarkExtended else LightExtended

    val view = LocalView.current
    val activity = remember(context) { context.findActivity() }
    if (activity != null && !view.isInEditMode) {
        SideEffect {
            val window = activity.window
            @Suppress("DEPRECATION")
            window.statusBarColor = colorScheme.surface.toArgb()
            @Suppress("DEPRECATION")
            window.navigationBarColor = colorScheme.surfaceContainer.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalExtendedColors provides extended) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
