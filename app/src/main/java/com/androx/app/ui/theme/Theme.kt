package com.androx.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androx.app.data.Accent
import androidx.compose.ui.unit.sp

// Androx brand palette: black, white, charcoal, greys and silver. Red is kept only for errors.
private val Black = Color(0xFF000000)
private val White = Color(0xFFFFFFFF)
private val Charcoal = Color(0xFF1C1C1E)
private val DarkGrey = Color(0xFF3A3A3C)
private val Silver = Color(0xFFC0C0C0)
private val LightGrey = Color(0xFFE4E4E6)

private val AndroxLight: ColorScheme = lightColorScheme(
    primary = Charcoal,
    onPrimary = White,
    primaryContainer = LightGrey,
    onPrimaryContainer = Charcoal,
    secondary = Color(0xFF5C5C60),
    onSecondary = White,
    secondaryContainer = Color(0xFFE9E9EB),
    onSecondaryContainer = Charcoal,
    tertiary = Color(0xFF6E6E73),
    onTertiary = White,
    tertiaryContainer = Color(0xFFDADADD),
    onTertiaryContainer = Charcoal,
    background = Color(0xFFFBFBFB),
    onBackground = Charcoal,
    surface = Color(0xFFFBFBFB),
    onSurface = Charcoal,
    surfaceVariant = Color(0xFFE4E4E6),
    onSurfaceVariant = Color(0xFF48484A),
    surfaceTint = Charcoal,
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD1D1D6),
    surfaceContainerLowest = White,
    surfaceContainerLow = Color(0xFFF5F5F6),
    surfaceContainer = Color(0xFFEFEFF0),
    surfaceContainerHigh = Color(0xFFE9E9EB),
    surfaceContainerHighest = Color(0xFFE3E3E5),
    inverseSurface = Charcoal,
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = Silver,
)

private val AndroxDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFE6E6E8),
    onPrimary = Charcoal,
    primaryContainer = DarkGrey,
    onPrimaryContainer = Color(0xFFF2F2F7),
    secondary = Silver,
    onSecondary = Charcoal,
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFE6E6E8),
    tertiary = Color(0xFFA1A1A6),
    onTertiary = Charcoal,
    tertiaryContainer = Color(0xFF38383A),
    onTertiaryContainer = Color(0xFFE6E6E8),
    background = Color(0xFF0E0E0E),
    onBackground = Color(0xFFE6E6E8),
    surface = Color(0xFF0E0E0E),
    onSurface = Color(0xFFE6E6E8),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFAEAEB2),
    surfaceTint = Color(0xFFE6E6E8),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF3A3A3C),
    surfaceContainerLowest = Black,
    surfaceContainerLow = Color(0xFF151516),
    surfaceContainer = Color(0xFF1A1A1C),
    surfaceContainerHigh = Color(0xFF232325),
    surfaceContainerHighest = Color(0xFF2C2C2E),
    inverseSurface = Color(0xFFE6E6E8),
    inverseOnSurface = Charcoal,
    inversePrimary = Charcoal,
)

private val base = Typography()

/** Material 3 type scale with slightly stronger titles and labels, closer to Google's own apps. */
private val AndroxTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Normal),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Medium),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
)

/** Rounder corners than the M3 defaults, as in Android 14/15 system apps. */
private val AndroxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun hsl(h: Float, s: Float, l: Float): Color = Color.hsl(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))

/** A full tonal scheme grown from a single hue. Not Material You's HCT, but close in feel and fully offline. */
private fun accentScheme(hue: Float, dark: Boolean): ColorScheme =
    if (!dark) {
        lightColorScheme(
            primary = hsl(hue, 0.52f, 0.36f),
            onPrimary = White,
            primaryContainer = hsl(hue, 0.62f, 0.88f),
            onPrimaryContainer = hsl(hue, 0.75f, 0.11f),
            secondary = hsl(hue, 0.20f, 0.38f),
            onSecondary = White,
            secondaryContainer = hsl(hue, 0.28f, 0.88f),
            onSecondaryContainer = hsl(hue, 0.35f, 0.12f),
            tertiary = hsl(hue + 55f, 0.30f, 0.38f),
            onTertiary = White,
            tertiaryContainer = hsl(hue + 55f, 0.42f, 0.88f),
            onTertiaryContainer = hsl(hue + 55f, 0.50f, 0.12f),
            background = hsl(hue, 0.22f, 0.975f),
            onBackground = hsl(hue, 0.12f, 0.10f),
            surface = hsl(hue, 0.22f, 0.975f),
            onSurface = hsl(hue, 0.12f, 0.10f),
            surfaceVariant = hsl(hue, 0.18f, 0.89f),
            onSurfaceVariant = hsl(hue, 0.12f, 0.29f),
            surfaceTint = hsl(hue, 0.52f, 0.36f),
            outline = hsl(hue, 0.08f, 0.45f),
            outlineVariant = hsl(hue, 0.14f, 0.80f),
            surfaceContainerLowest = White,
            surfaceContainerLow = hsl(hue, 0.24f, 0.955f),
            surfaceContainer = hsl(hue, 0.22f, 0.935f),
            surfaceContainerHigh = hsl(hue, 0.20f, 0.915f),
            surfaceContainerHighest = hsl(hue, 0.18f, 0.895f),
            inverseSurface = hsl(hue, 0.10f, 0.19f),
            inverseOnSurface = hsl(hue, 0.20f, 0.94f),
            inversePrimary = hsl(hue, 0.70f, 0.78f),
        )
    } else {
        darkColorScheme(
            primary = hsl(hue, 0.68f, 0.78f),
            onPrimary = hsl(hue, 0.80f, 0.14f),
            primaryContainer = hsl(hue, 0.46f, 0.26f),
            onPrimaryContainer = hsl(hue, 0.70f, 0.90f),
            secondary = hsl(hue, 0.30f, 0.76f),
            onSecondary = hsl(hue, 0.40f, 0.14f),
            secondaryContainer = hsl(hue, 0.22f, 0.26f),
            onSecondaryContainer = hsl(hue, 0.40f, 0.90f),
            tertiary = hsl(hue + 55f, 0.42f, 0.78f),
            onTertiary = hsl(hue + 55f, 0.55f, 0.14f),
            tertiaryContainer = hsl(hue + 55f, 0.30f, 0.27f),
            onTertiaryContainer = hsl(hue + 55f, 0.55f, 0.90f),
            background = hsl(hue, 0.14f, 0.075f),
            onBackground = hsl(hue, 0.10f, 0.90f),
            surface = hsl(hue, 0.14f, 0.075f),
            onSurface = hsl(hue, 0.10f, 0.90f),
            surfaceVariant = hsl(hue, 0.12f, 0.25f),
            onSurfaceVariant = hsl(hue, 0.12f, 0.78f),
            surfaceTint = hsl(hue, 0.68f, 0.78f),
            outline = hsl(hue, 0.08f, 0.58f),
            outlineVariant = hsl(hue, 0.10f, 0.28f),
            surfaceContainerLowest = hsl(hue, 0.14f, 0.05f),
            surfaceContainerLow = hsl(hue, 0.14f, 0.10f),
            surfaceContainer = hsl(hue, 0.14f, 0.125f),
            surfaceContainerHigh = hsl(hue, 0.13f, 0.16f),
            surfaceContainerHighest = hsl(hue, 0.12f, 0.20f),
            inverseSurface = hsl(hue, 0.10f, 0.90f),
            inverseOnSurface = hsl(hue, 0.12f, 0.17f),
            inversePrimary = hsl(hue, 0.52f, 0.36f),
        )
    }

/** Monospace style for the script editor. */
val CodeTextStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

@Composable
fun AndroxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    accent: Accent = Accent.ANDROX,
    pureBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        accent != Accent.ANDROX -> accentScheme(accent.hue, darkTheme)
        darkTheme -> AndroxDark
        else -> AndroxLight
    }
    if (darkTheme && pureBlack) {
        scheme = scheme.copy(
            background = Black,
            surface = Black,
            surfaceContainerLowest = Black,
            surfaceContainerLow = Color(0xFF0A0A0A),
            surfaceContainer = Color(0xFF111112),
            surfaceContainerHigh = Color(0xFF1A1A1C),
            surfaceContainerHighest = Color(0xFF232325),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = AndroxTypography, shapes = AndroxShapes, content = content)
}
