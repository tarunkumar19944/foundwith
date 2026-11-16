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

// WhoHas: indigo for actions and the main numbers, saffron only for what's new or changed.
private val Indigo = Color(0xFF4F46E5)
private val IndigoTint = Color(0xFFEEF0FF)
private val IndigoInk = Color(0xFF231C8F)
private val Saffron = Color(0xFFFF9933)
private val SaffronTint = Color(0xFFFFF1E0)
private val SaffronInk = Color(0xFF7A3A00)
private val Ink = Color(0xFF15172A)
private val Ground = Color(0xFFF7F8FC)
private val Raised = Color(0xFFEEF0F6)
private val Muted = Color(0xFF5C6070)
private val Rule = Color(0xFFE3E5EE)
private val RuleStrong = Color(0xFFC9CCDA)
private val Red = Color(0xFFC62E3A)
val LiveGreen = Color(0xFF187A42)

private val IndigoDark = Color(0xFFA5A0FF)
private val IndigoTintDark = Color(0xFF26235A)
private val SaffronDark = Color(0xFFFFB061)
private val SaffronTintDark = Color(0xFF3D2810)
private val InkDark = Color(0xFFECEDF5)
private val GroundDark = Color(0xFF0C0D16)
private val SurfaceDark = Color(0xFF15172A)
private val RaisedDark = Color(0xFF1E2135)
private val MutedDark = Color(0xFFA6AABD)
private val RuleDark = Color(0xFF2A2D44)
private val RuleStrongDark = Color(0xFF3B3F5A)
private val RedDark = Color(0xFFF06A70)

private val LightColors = lightColorScheme(
    primary = Indigo, onPrimary = Color.White,
    primaryContainer = IndigoTint, onPrimaryContainer = IndigoInk,
    secondary = Saffron, onSecondary = Ink,
    secondaryContainer = SaffronTint, onSecondaryContainer = SaffronInk,
    tertiary = LiveGreen, onTertiary = Color.White,
    background = Ground, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    surfaceVariant = Raised, onSurfaceVariant = Muted,
    outline = RuleStrong, outlineVariant = Rule,
    error = Red, onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = IndigoDark, onPrimary = Color(0xFF15124A),
    primaryContainer = IndigoTintDark, onPrimaryContainer = Color(0xFFDCDAFF),
    secondary = SaffronDark, onSecondary = Color(0xFF2A1600),
    secondaryContainer = SaffronTintDark, onSecondaryContainer = Color(0xFFFFD9AE),
    tertiary = Color(0xFF6FD69B), onTertiary = Color(0xFF00210F),
    background = GroundDark, onBackground = InkDark,
    surface = SurfaceDark, onSurface = InkDark,
    surfaceVariant = RaisedDark, onSurfaceVariant = MutedDark,
    outline = RuleStrongDark, outlineVariant = RuleDark,
    error = RedDark, onError = Color.Black,
)

private val AppTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
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

/** The tint behind names of people the viewer knows. */
val highlightMark: Color
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.primaryContainer

/** Phone numbers read best with figures of equal width. */
val numberStyle: TextStyle
    @Composable @ReadOnlyComposable
    get() = MaterialTheme.typography.bodyMedium.copy(
        fontSize = 15.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
