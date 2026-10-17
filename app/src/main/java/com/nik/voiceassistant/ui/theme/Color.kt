package com.nik.voiceassistant.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Brand palette for phones without Material You (Android 11 and older) or when it is off: a calm
 * indigo primary with a teal accent, as Material 3 tonal palettes for light and dark. Screens never
 * use these values directly; they read MaterialTheme.colorScheme (and NikTheme.extended for the few
 * roles Material doesn't have), so dynamic colour and both themes just work.
 */

internal val BrandLightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF4A55C8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDFE0FF),
    onPrimaryContainer = Color(0xFF000D5F),
    inversePrimary = Color(0xFFBCC2FF),
    secondary = Color(0xFF5B5D72),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E1F9),
    onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF006A60),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFF9EF2E4),
    onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF4A55C8),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF3EFF7),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF8FF),
    surfaceDim = Color(0xFFDBD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9)
)

internal val BrandDarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFBCC2FF),
    onPrimary = Color(0xFF15228F),
    primaryContainer = Color(0xFF323DAF),
    onPrimaryContainer = Color(0xFFDFE0FF),
    inversePrimary = Color(0xFF4A55C8),
    secondary = Color(0xFFC4C5DD),
    onSecondary = Color(0xFF2D2F42),
    secondaryContainer = Color(0xFF434659),
    onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFF82D5C8),
    onTertiary = Color(0xFF003731),
    tertiaryContainer = Color(0xFF005048),
    onTertiaryContainer = Color(0xFF9EF2E4),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF131318),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF131318),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFBCC2FF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    outline = Color(0xFF91909A),
    outlineVariant = Color(0xFF46464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF39383F),
    surfaceDim = Color(0xFF131318),
    surfaceContainerLowest = Color(0xFF0E0E13),
    surfaceContainerLow = Color(0xFF1B1B21),
    surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF2A292F),
    surfaceContainerHighest = Color(0xFF35343A)
)

/** Roles Material 3 has no slot for: success / warning states and code blocks. */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val codeBackground: Color,
    val codeText: Color
)

internal val LightExtended = ExtendedColors(
    success = Color(0xFF1B6D2F),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFA6F5A8),
    onSuccessContainer = Color(0xFF002107),
    warning = Color(0xFF7A5900),
    warningContainer = Color(0xFFFFDF9E),
    onWarningContainer = Color(0xFF261A00),
    codeBackground = Color(0xFFF0EEF6),
    codeText = Color(0xFF1F3A5F)
)

internal val DarkExtended = ExtendedColors(
    success = Color(0xFF8AD88E),
    onSuccess = Color(0xFF003911),
    successContainer = Color(0xFF00531B),
    onSuccessContainer = Color(0xFFA6F5A8),
    warning = Color(0xFFF5BF48),
    warningContainer = Color(0xFF5C4300),
    onWarningContainer = Color(0xFFFFDF9E),
    codeBackground = Color(0xFF0E0E13),
    codeText = Color(0xFFB5E3DA)
)
