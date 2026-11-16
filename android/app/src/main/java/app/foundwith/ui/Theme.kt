package app.foundwith.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// The prototype's look: a phone diary with highlighter marks on the people you know.
private val Ink = Color(0xFF15181E)
private val Ground = Color(0xFFF3F4EF)
private val Raised = Color(0xFFE9EAE4)
private val Muted = Color(0xFF555B65)
private val Rule = Color(0xFFDCDED6)
private val RuleStrong = Color(0xFFC2C5BB)
private val Highlight = Color(0xFFFFDB33)
private val HighlightInk = Color(0xFF2A2300)
private val Red = Color(0xFFC2362A)

private val InkDark = Color(0xFFECEEF1)
private val GroundDark = Color(0xFF090B0E)
private val SurfaceDark = Color(0xFF13161B)
private val RaisedDark = Color(0xFF1B1F25)
private val MutedDark = Color(0xFFA3AAB4)
private val RuleDark = Color(0xFF272C34)
private val RuleStrongDark = Color(0xFF3A414B)
private val HighlightDark = Color(0xFFF5D23C)
private val RedDark = Color(0xFFF0604F)

private val LightColors = lightColorScheme(
    primary = Ink, onPrimary = Color.White,
    secondary = Highlight, onSecondary = HighlightInk,
    secondaryContainer = Highlight, onSecondaryContainer = HighlightInk,
    background = Ground, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Raised, onSurfaceVariant = Muted,
    outline = RuleStrong, outlineVariant = Rule,
    error = Red, onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = InkDark, onPrimary = SurfaceDark,
    secondary = HighlightDark, onSecondary = HighlightInk,
    secondaryContainer = HighlightDark, onSecondaryContainer = HighlightInk,
    background = GroundDark, onBackground = InkDark,
    surface = SurfaceDark, onSurface = InkDark,
    surfaceVariant = RaisedDark, onSurfaceVariant = MutedDark,
    outline = RuleStrongDark, outlineVariant = RuleDark,
    error = RedDark, onError = Color.Black,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun FoundWithTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}

/** The highlighter colour behind names of people the viewer knows. */
val highlightMark: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.secondary.copy(alpha = if (isSystemInDarkTheme()) 0.35f else 0.6f)

/** Phone numbers read best with figures of equal width. */
val numberStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
