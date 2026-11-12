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

// FabriC++ palette (InfinitumCorp/fabric android Theme.kt): white paper, ink, one muted grey,
// one hairline grey and a single blue accent for actions and links. Square corners everywhere.
val Paper = Color(0xFFFFFFFF)
val Ink = Color(0xFF111820)
val Muted = Color(0xFF52606B)
val Line = Color(0xFFD6DCE1)
val Accent = Color(0xFF2454DD)

// ÆTHER names kept as aliases so every screen follows the Fabric palette.
val InkButton = Accent
val Body = Muted
val Rule = Line
val RuleStrong = Color(0xFFB4BDC5)
val Wash = Color(0xFFF4F6F8)
val Alert = Color(0xFFB42318)
/** Own position and own tracks on the map. */
val Friendly = Accent
/** Hazard markers and the measuring tool. */
val Caution = Color(0xFFB54708)

private val scheme = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondary = Muted,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Wash,
    onSurfaceVariant = Muted,
    surfaceContainer = Paper,
    surfaceContainerHigh = Paper,
    surfaceContainerHighest = Wash,
    surfaceContainerLow = Paper,
    error = Alert,
    outline = Line,
    outlineVariant = Line,
)

val Sans = FontFamily.SansSerif
val Mono = FontFamily.Monospace

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.4).sp, color = Ink),
    headlineMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 28.sp, letterSpacing = (-0.4).sp, color = Ink),
    titleLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 22.sp, color = Ink),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 18.sp, color = Ink),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp, color = Ink),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp, color = Ink),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 18.sp, color = Muted),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 10.sp, letterSpacing = 0.12.em),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 12.sp, letterSpacing = 0.04.em),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 14.sp, letterSpacing = 0.02.em),
)

private val square = RoundedCornerShape(0.dp)
private val shapes = Shapes(extraSmall = square, small = square, medium = square, large = square, extraLarge = square)

@Composable
fun AetherTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type, shapes = shapes, content = content)
}
