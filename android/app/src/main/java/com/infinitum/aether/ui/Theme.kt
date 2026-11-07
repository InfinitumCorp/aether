package com.infinitum.aether.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// Infinitum product palette (infinitum-corporation.vercel.app): white page,
// near-black ink, one grey for body copy, two hairline greys. No accent hue.
val Paper = Color(0xFFFFFFFF)
val Ink = Color(0xFF141619)
val InkButton = Color(0xFF16181A)
val Body = Color(0xFF525A5B)
val Rule = Color(0xFFD8DCDA)
val RuleStrong = Color(0xFFB3BCBB)
val Wash = Color(0xFFF5F6F5)
val Alert = Color(0xFFB42318)

private val scheme = lightColorScheme(
    primary = InkButton,
    onPrimary = Paper,
    secondary = Body,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Wash,
    onSurfaceVariant = Body,
    surfaceContainer = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = Wash,
    surfaceContainerLow = Paper,
    error = Alert,
    outline = RuleStrong,
    outlineVariant = Rule,
)

val Sans = FontFamily.SansSerif
val Mono = FontFamily.Monospace

private val type = Typography(
    // Display: large, regular weight, tight tracking (site h1 = -0.055em).
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-0.055).em),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 33.sp, letterSpacing = (-0.05).em),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = (-0.03).em),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 17.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp),
    // Kicker: tiny monospace caps, the site's section labels.
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.12.em),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 11.sp, letterSpacing = 0.08.em),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 13.sp, letterSpacing = 0.02.em),
)

private val square = RoundedCornerShape(0.dp)
private val shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)

@Composable
fun AetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}
