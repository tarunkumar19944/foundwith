package app.foundwith.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.foundwith.R

// Facebook's look (Y, 23 Nov 2026): white cards on light grey, Facebook blue, grey secondary buttons,
// red counts; a Facebook-like dark grey when the phone is in dark mode. Blues, greens and reds are the
// accessible shades (4.5:1 or better for text), not the brand's lighter ones.
private val FbBlue = Color(0xFF0862F5)          // Facebook's blue, a shade deeper: 4.6:1 on the grey page, 5.2:1 under white text
private val FbBlueText = Color(0xFF0450C2)      // blue text on the light blue tint
private val FbBlueTint = Color(0xFFE7F3FF)
private val FbGround = Color(0xFFF0F2F5)
private val FbGrey = Color(0xFFE4E6EB)          // secondary buttons, icon circles
private val FbRule = Color(0xFFCED0D4)
private val FbInk = Color(0xFF050505)
private val FbMuted = Color(0xFF65676B)
private val FbGreen = Color(0xFF188038)
private val FbRed = Color(0xFFD21F3C)

private val DkGround = Color(0xFF18191A)
private val DkCard = Color(0xFF242526)
private val DkRaised = Color(0xFF3A3B3C)
private val DkRule = Color(0xFF3E4042)
private val DkInk = Color(0xFFE4E6EB)
private val DkMuted = Color(0xFFB0B3B8)
private val DkBlue = Color(0xFF5AA2FF)          // light blue with dark text on it, readable on dark grey
private val DkGreen = Color(0xFF45BD62)
private val DkRed = Color(0xFFFF6B7A)

private val LightColours = lightColorScheme(
    primary = FbBlue, onPrimary = Color.White,
    primaryContainer = FbBlueTint, onPrimaryContainer = FbBlueText,
    inversePrimary = DkBlue,
    secondary = FbBlue, onSecondary = Color.White,
    secondaryContainer = FbGrey, onSecondaryContainer = FbInk,
    tertiary = FbGreen, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6F4EA), onTertiaryContainer = Color(0xFF0D652D),
    background = FbGround, onBackground = FbInk,
    surface = Color.White, onSurface = FbInk,
    surfaceVariant = FbGrey, onSurfaceVariant = FbMuted,
    surfaceTint = Color.White,
    inverseSurface = DkCard, inverseOnSurface = DkInk,
    outline = FbRule, outlineVariant = FbGrey,
    error = FbRed, onError = Color.White,
    errorContainer = Color(0xFFFFEBEE), onErrorContainer = Color(0xFF9F1129),
    scrim = Color.Black,
    surfaceBright = Color.White, surfaceDim = FbGround,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color.White, surfaceContainerHigh = Color.White, surfaceContainerHighest = FbGrey,
)

private val DarkColours = darkColorScheme(
    primary = DkBlue, onPrimary = Color(0xFF001B3D),
    primaryContainer = Color(0xFF263951), onPrimaryContainer = Color(0xFFA8C8FF),
    inversePrimary = FbBlue,
    secondary = DkBlue, onSecondary = Color(0xFF001B3D),
    secondaryContainer = DkRaised, onSecondaryContainer = DkInk,
    tertiary = DkGreen, onTertiary = Color(0xFF00210B),
    tertiaryContainer = Color(0xFF1E3A28), onTertiaryContainer = Color(0xFFA6E3B8),
    background = DkGround, onBackground = DkInk,
    surface = DkCard, onSurface = DkInk,
    surfaceVariant = DkRaised, onSurfaceVariant = DkMuted,
    surfaceTint = DkCard,
    inverseSurface = DkInk, inverseOnSurface = DkGround,
    outline = Color(0xFF4E4F50), outlineVariant = DkRule,
    error = DkRed, onError = Color(0xFF3B0009),
    errorContainer = Color(0xFF4A1F25), onErrorContainer = Color(0xFFFFB3BC),
    scrim = Color.Black,
    surfaceBright = DkRaised, surfaceDim = DkGround,
    surfaceContainerLowest = DkGround, surfaceContainerLow = Color(0xFF1E1F20),
    surfaceContainer = DkCard, surfaceContainerHigh = DkCard, surfaceContainerHighest = DkRaised,
)

/** The green of "Live" and a count going up. */
val LiveGreen: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.tertiary

/** Your own number's card: a plain card with a light blue edge. */
val HeroColor: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surface
val HeroEdge: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

/** Atkinson Hyperlegible Next: made so 1, l and I and 0 and O never look alike. Three weights only. */
private val Atkinson = FontFamily(
    Font(R.font.atkinson_hyperlegible_next_regular, FontWeight.Normal),
    Font(R.font.atkinson_hyperlegible_next_semibold, FontWeight.SemiBold),
    Font(R.font.atkinson_hyperlegible_next_bold, FontWeight.Bold),
)

/** Every digit the same width, so times, counts and numbers line up. */
private fun TextStyle.ours(weight: FontWeight) = copy(fontFamily = Atkinson, fontWeight = weight, fontFeatureSettings = "tnum")

private val AppTypography = Typography().let { b ->
    Typography(
        displayLarge = b.displayLarge.ours(FontWeight.Bold),
        displayMedium = b.displayMedium.ours(FontWeight.Bold),
        displaySmall = b.displaySmall.ours(FontWeight.Bold),
        headlineLarge = b.headlineLarge.ours(FontWeight.Bold),
        headlineMedium = b.headlineMedium.ours(FontWeight.Bold),
        headlineSmall = b.headlineSmall.ours(FontWeight.Bold),
        titleLarge = b.titleLarge.ours(FontWeight.Bold),
        titleMedium = b.titleMedium.ours(FontWeight.SemiBold),
        titleSmall = b.titleSmall.ours(FontWeight.SemiBold),
        bodyLarge = b.bodyLarge.ours(FontWeight.Normal).copy(lineHeight = 24.sp),
        bodyMedium = b.bodyMedium.ours(FontWeight.Normal).copy(fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = b.bodySmall.ours(FontWeight.Normal).copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = b.labelLarge.ours(FontWeight.SemiBold),
        labelMedium = b.labelMedium.ours(FontWeight.SemiBold).copy(fontSize = 13.sp),
        labelSmall = b.labelSmall.ours(FontWeight.SemiBold).copy(fontSize = 12.sp),
    )
}

@Composable
fun FoundWithTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colours = if (dark) DarkColours else LightColours
    // Text that names no colour takes the page's ink on every screen, not only inside a Surface.
    MaterialTheme(colorScheme = colours, typography = AppTypography) {
        CompositionLocalProvider(LocalContentColor provides colours.onSurface, content = content)
    }
}

/** The tint behind names of people the viewer knows. */
val highlightMark: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

/** Phone numbers read best with figures of equal width. */
val numberStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodyMedium.copy(
        letterSpacing = 0.3.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
