package com.eiamjun.event.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The shop's own palette, so the event stall and the shop's till look like the same business:
// dark wood signage, gold, receipt paper, basil for "ready", red for anything that needs a hand.
val Wood = Color(0xFF241712)
val Wood2 = Color(0xFF34231A)
val Paper = Color(0xFFFBF6EA)
val PaperDim = Color(0xFFF1E9D6)
val InkText = Color(0xFF2A2018)
val InkTextDim = Color(0xFF6B5B48)
val CreamDim = Color(0xFFC7B393)
val Gold = Color(0xFFD9A544)
val Basil = Color(0xFF4C7A4A)
val Alert = Color(0xFFB23A2B)

/** Prices and queue numbers. Louder than the wood palette, because the number is the point. */
val PriceOrange = Color(0xFFC2410C)

private val EventColorScheme = lightColorScheme(
    primary = Gold,
    onPrimary = InkText,
    secondary = Basil,
    onSecondary = Color.White,
    error = Alert,
    onError = Color.White,
    background = Paper,
    onBackground = InkText,
    surface = Paper,
    onSurface = InkText,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkTextDim,
    outline = Color(0xFFE5D8B8)
)

/**
 * Every size is a step or two above stock Material, the same call the shop's till made: a 5.5"
 * handheld, older eyes, and a queue of people waiting. Readability beats density, and that is a
 * requirement here rather than a preference.
 *
 * TODO: swap [FontFamily.SansSerif] for Sarabun — a looped Thai face, much easier to read on a
 * small screen than a loopless one. The shop's till carries the same TODO and the same reason.
 */
private val eventFont = FontFamily.SansSerif

private val EventTypography = Typography(
    displayLarge = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Bold, fontSize = 64.sp),
    displaySmall = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Bold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Bold, fontSize = 26.sp),
    titleLarge = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Bold, fontSize = 19.sp),
    bodyLarge = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Normal, fontSize = 18.sp),
    bodyMedium = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    labelLarge = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Bold, fontSize = 18.sp),
    labelMedium = TextStyle(fontFamily = eventFont, fontWeight = FontWeight.Medium, fontSize = 15.sp)
)

@Composable
fun EiamJunEventTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EventColorScheme,
        typography = EventTypography,
        content = content
    )
}
