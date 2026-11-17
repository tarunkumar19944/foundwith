package app.foundwith.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.foundwith.R

// WhoHas: charcoal and gold (approved by Y, 17 Nov 2026). Gold is the one accent; red and green
// only say what kind of call it was. No pure white or pure black anywhere, so it's easy on the eyes.
private val Ground = Color(0xFF14151A)      // the page
private val Card = Color(0xFF1D1F26)        // cards, the tab bar
private val Sheet = Color(0xFF23252D)       // sheets and dialogs
private val Raised = Color(0xFF272A33)      // icon circles, inputs
private val Rule = Color(0xFF2C2F38)
private val RuleStrong = Color(0xFF3A3D47)
private val Ink = Color(0xFFE7E8EC)
private val Muted = Color(0xFFA3A6B1)
private val Gold = Color(0xFFD9B65C)
private val GoldSoft = Color(0xFFE8CF8E)
private val GoldTint = Color(0xFF3A3220)
private val OnGold = Color(0xFF14151A)
private val Red = Color(0xFFF2867E)
val LiveGreen = Color(0xFF7CCB98)

/** Your own number's card: charcoal with a gold edge. */
val HeroColor = Color(0xFF24252C)
val HeroEdge = GoldTint

private val Colours = darkColorScheme(
    primary = Gold, onPrimary = OnGold,
    primaryContainer = GoldTint, onPrimaryContainer = GoldSoft,
    inversePrimary = Color(0xFF7A5C16),
    secondary = Gold, onSecondary = OnGold,
    secondaryContainer = GoldTint, onSecondaryContainer = GoldSoft,
    tertiary = LiveGreen, onTertiary = Color(0xFF0E1F14),
    tertiaryContainer = Color(0xFF1E3326), onTertiaryContainer = Color(0xFF9ED8B2),
    background = Ground, onBackground = Ink,
    surface = Card, onSurface = Ink,
    surfaceVariant = Raised, onSurfaceVariant = Muted,
    surfaceTint = Ink,
    inverseSurface = Ink, inverseOnSurface = Ground,
    outline = RuleStrong, outlineVariant = Rule,
    error = Red, onError = OnGold,
    errorContainer = Color(0xFF3B2626), onErrorContainer = Color(0xFFF0B8B0),
    scrim = Color(0xFF08090C),
    surfaceBright = Color(0xFF2E3039), surfaceDim = Ground,
    surfaceContainerLowest = Ground, surfaceContainerLow = Color(0xFF191A20),
    surfaceContainer = Card, surfaceContainerHigh = Sheet, surfaceContainerHighest = Raised,
)

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
fun FoundWithTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colours, typography = AppTypography, content = content)
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
