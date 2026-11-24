package dev.heatcontrol.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.heatcontrol.protocol.ModeType

/** Warm "heating" palette: ember and amber for heat, ice blue for cold, leaf green for eco. */
object Heat {
    val Ember = Color(0xFFFF6A3D)
    val EmberDeep = Color(0xFFE5482A)
    val Amber = Color(0xFFFFB547)
    val Ice = Color(0xFF63B3FF)
    val Leaf = Color(0xFF3FD69A)
    val Slate = Color(0xFF8A90A0)

    val flame = Brush.linearGradient(listOf(Color(0xFFFF8A3D), Color(0xFFE8432F)))
    val dial = listOf(Ice, Amber, Ember, EmberDeep)

    fun modeColor(m: ModeType?) = when (m) {
        ModeType.MANUAL -> Ember
        ModeType.ECO -> Leaf
        ModeType.FROST -> Ice
        ModeType.SCHEDULE -> Amber
        ModeType.BOOST -> EmberDeep
        else -> Slate
    }
}

private val DarkScheme = darkColorScheme(
    primary = Heat.Ember,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3B1D14),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Heat.Amber,
    onSecondary = Color(0xFF2A1A00),
    tertiary = Heat.Ice,
    background = Color(0xFF0D0E12),
    onBackground = Color(0xFFEDEEF2),
    surface = Color(0xFF0D0E12),
    onSurface = Color(0xFFEDEEF2),
    onSurfaceVariant = Color(0xFF9CA2B0),
    surfaceContainerLowest = Color(0xFF0A0B0E),
    surfaceContainerLow = Color(0xFF13151A),
    surfaceContainer = Color(0xFF171A20),
    surfaceContainerHigh = Color(0xFF20242C),
    surfaceContainerHighest = Color(0xFF2A2F39),
    outline = Color(0xFF3B404C),
    outlineVariant = Color(0xFF262A33),
    error = Color(0xFFFF6B6B),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE5522A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D8),
    onPrimaryContainer = Color(0xFF3B1205),
    secondary = Color(0xFFD98A00),
    tertiary = Color(0xFF2F7FD6),
    background = Color(0xFFF5F3F0),
    onBackground = Color(0xFF1B1C20),
    surface = Color(0xFFF5F3F0),
    onSurface = Color(0xFF1B1C20),
    onSurfaceVariant = Color(0xFF6A6F7B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFAF8),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0EEEA),
    surfaceContainerHighest = Color(0xFFE7E4DF),
    outline = Color(0xFFCFCBC4),
    outlineVariant = Color(0xFFE4E0DA),
    error = Color(0xFFD93A3A),
)

private val base = Typography()
private val AppTypography = base.copy(
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

val BigTemp = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Light, letterSpacing = (-2).sp)

@Composable
fun HeatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content,
    )
}
