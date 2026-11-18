package com.falsterboplan.lights.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.falsterboplan.lights.R

/** The brand palette, plus a dark cocoa for text (none of the palette colours is legible on cream). */
object Brand {
    val Pink = Color(0xFFE94E76)
    val PinkDeep = Color(0xFFB93258)
    val Rose = Color(0xFFD68089)
    val Clay = Color(0xFFC6A49A)
    val Mint = Color(0xFFC5E5D8)
    val Cream = Color(0xFFF5EAD6)
    val Card = Color(0xFFFFF8EC)
    val Sand = Color(0xFFEBD9C6)
    val Ink = Color(0xFF54332A)
    val InkSoft = Color(0xFF7A5549)
}

@OptIn(ExperimentalTextApi::class)
private fun nunito(weight: Int) = Font(
    R.font.nunito,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

@OptIn(ExperimentalTextApi::class)
private fun fraunces(weight: Int) = Font(
    R.font.fraunces,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("SOFT", 100f),
        FontVariation.Setting("opsz", 72f),
    ),
)

val Nunito = FontFamily(nunito(500), nunito(700), nunito(800))
val Fraunces = FontFamily(fraunces(700), fraunces(900))

private val Colors = lightColorScheme(
    primary = Brand.Pink,
    onPrimary = Color.White,
    primaryContainer = Brand.Mint,
    onPrimaryContainer = Brand.Ink,
    secondary = Brand.Rose,
    onSecondary = Color.White,
    secondaryContainer = Brand.Mint,
    onSecondaryContainer = Brand.Ink,
    tertiary = Brand.Clay,
    background = Brand.Cream,
    onBackground = Brand.Ink,
    surface = Brand.Cream,
    onSurface = Brand.Ink,
    surfaceVariant = Brand.Sand,
    onSurfaceVariant = Brand.InkSoft,
    surfaceContainerLowest = Brand.Card,
    surfaceContainerLow = Brand.Card,
    surfaceContainer = Brand.Card,
    surfaceContainerHigh = Brand.Cream,
    surfaceContainerHighest = Brand.Card,
    surfaceTint = Color.Transparent,
    outline = Brand.Clay,
    outlineVariant = Brand.Sand,
    inverseSurface = Brand.Ink,
    inverseOnSurface = Brand.Cream,
    error = Color(0xFFA3262E),
    scrim = Brand.Ink,
)

private fun body(size: Int, weight: Int, line: Int) =
    TextStyle(fontFamily = Nunito, fontWeight = FontWeight(weight), fontSize = size.sp, lineHeight = line.sp)

private fun display(size: Int, line: Int) =
    TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Bold, fontSize = size.sp, lineHeight = line.sp)

private val Type = Typography(
    headlineLarge = display(34, 38),
    headlineMedium = display(30, 34),
    headlineSmall = display(26, 30),
    titleLarge = display(26, 30),
    titleMedium = body(17, 800, 22),
    titleSmall = body(15, 800, 20),
    bodyLarge = body(17, 500, 24),
    bodyMedium = body(15, 500, 21),
    bodySmall = body(14, 500, 19),
    labelLarge = body(15, 800, 20),
    labelMedium = body(13, 800, 16),
    labelSmall = body(12, 700, 16),
)

private val Rounded = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun HomeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = Rounded, content = content)
}

@Composable
fun brandSwitchColors(): SwitchColors = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Brand.Pink,
    uncheckedThumbColor = Color.White,
    uncheckedTrackColor = Brand.Clay,
    uncheckedBorderColor = Color.Transparent,
    disabledCheckedThumbColor = Color.White,
    disabledUncheckedThumbColor = Color.White,
    disabledUncheckedBorderColor = Color.Transparent,
)
