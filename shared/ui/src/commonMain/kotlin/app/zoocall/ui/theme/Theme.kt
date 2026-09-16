package app.zoocall.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.zoocall.core.app.ThemeMode

/** Brand seed: Zoocall Teal #0F9D8A (docs/05 §2). Tonal values generated around the seed, AA-checked. */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9CF2DF),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE8E0),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF4A5C92),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDAE2FF),
    onTertiaryContainer = Color(0xFF001848),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FBF8),
    onBackground = Color(0xFF171D1B),
    surface = Color(0xFFF5FBF8),
    onSurface = Color(0xFF171D1B),
    surfaceVariant = Color(0xFFDAE5E1),
    onSurfaceVariant = Color(0xFF3F4946),
    outline = Color(0xFF6F7976),
    outlineVariant = Color(0xFFBEC9C5),
    inverseSurface = Color(0xFF2B3230),
    inverseOnSurface = Color(0xFFECF2EF),
    inversePrimary = Color(0xFF80D5C3),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF5F2),
    surfaceContainer = Color(0xFFE9EFEC),
    surfaceContainerHigh = Color(0xFFE3EAE7),
    surfaceContainerHighest = Color(0xFFDEE4E1),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF80D5C3),
    onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF9CF2DF),
    secondary = Color(0xFFB1CCC4),
    onSecondary = Color(0xFF1C3530),
    secondaryContainer = Color(0xFF334B45),
    onSecondaryContainer = Color(0xFFCCE8E0),
    tertiary = Color(0xFFB3C5FF),
    onTertiary = Color(0xFF1A2D60),
    tertiaryContainer = Color(0xFF324478),
    onTertiaryContainer = Color(0xFFDAE2FF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1513),
    onBackground = Color(0xFFDEE4E1),
    surface = Color(0xFF0E1513),
    onSurface = Color(0xFFDEE4E1),
    surfaceVariant = Color(0xFF3F4946),
    onSurfaceVariant = Color(0xFFBEC9C5),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF3F4946),
    inverseSurface = Color(0xFFDEE4E1),
    inverseOnSurface = Color(0xFF2B3230),
    inversePrimary = Color(0xFF006B5E),
    surfaceContainerLowest = Color(0xFF090F0E),
    surfaceContainerLow = Color(0xFF171D1B),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF252B2A),
    surfaceContainerHighest = Color(0xFF303634),
)

/** Semantic colors Material doesn't define (docs/05 §2 tokens). */
@Immutable
data class ZoocallColors(
    val accept: Color,
    val onAccept: Color,
    val decline: Color,
    val onDecline: Color,
    val verified: Color,
    val warning: Color,
    val live: Color,
    val presenceAvailable: Color,
    val presenceBusy: Color,
    val presenceAway: Color,
)

private val LightExtra = ZoocallColors(
    accept = Color(0xFF1B7F3B), onAccept = Color.White,
    decline = Color(0xFFC5221F), onDecline = Color.White,
    verified = Color(0xFF006B5E), warning = Color(0xFF8A5100), live = Color(0xFFC5221F),
    presenceAvailable = Color(0xFF1B7F3B), presenceBusy = Color(0xFFC5221F), presenceAway = Color(0xFF8A5100),
)

private val DarkExtra = ZoocallColors(
    accept = Color(0xFF1B7F3B), onAccept = Color.White,
    decline = Color(0xFFC5221F), onDecline = Color.White,
    verified = Color(0xFF80D5C3), warning = Color(0xFFFFB95C), live = Color(0xFFFF8A80),
    presenceAvailable = Color(0xFF6DD58C), presenceBusy = Color(0xFFFF8A80), presenceAway = Color(0xFFFFB95C),
)

val LocalZoocallColors = staticCompositionLocalOf { LightExtra }

object ZoocallTheme {
    val colors: ZoocallColors
        @Composable get() = LocalZoocallColors.current
}

private val ZoocallShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Spacing scale 4/8/12/16/24/32 (docs/05 §2). */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** Android 12+ wallpaper colors; null elsewhere. */
@Composable
expect fun dynamicColorScheme(dark: Boolean): ColorScheme?

@Composable
fun ZoocallTheme(
    mode: ThemeMode = ThemeMode.System,
    dynamicColor: Boolean = true,
    /** The call screen is always dark (docs/05 §3). */
    forceDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = forceDark || when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = (if (dynamicColor && !forceDark) dynamicColorScheme(dark) else null) ?: if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalZoocallColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(colorScheme = scheme, shapes = ZoocallShapes, content = content)
    }
}
