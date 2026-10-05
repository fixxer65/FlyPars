package ru.pobedamonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Палитра приложения: мягкие небесно-голубые «авиа»-тона + тёплый коралловый
 * акцент. Есть светлая и тёмная темы; градиенты для шапки и фона экрана.
 */

@Immutable
data class AppGradients(
    val header: List<Color>,
    val background: List<Color>,
)

// ── Светлая тема ────────────────────────────────────────────────────────────
private val LightColors = lightColorScheme(
    primary = Color(0xFF2F6BD8),            // небо днём
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FF),
    onPrimaryContainer = Color(0xFF001A42),

    secondary = Color(0xFFE8590C),          // тёплый оранжевый акцент (цены)
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE1CB),
    onSecondaryContainer = Color(0xFF3D1200),

    tertiary = Color(0xFF1F9D6E),           // свежий зелёный (погода/успех)
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDEFDF),
    onTertiaryContainer = Color(0xFF002818),

    background = Color(0xFFF3F7FE),
    onBackground = Color(0xFF10141D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10141D),
    surfaceVariant = Color(0xFFE2EAF8),
    onSurfaceVariant = Color(0xFF45516A),
    outline = Color(0xFF76839C),
    outlineVariant = Color(0xFFD3DCEC),

    error = Color(0xFFC01F2E),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// ── Тёмная тема: полуночное небо ────────────────────────────────────────────
private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF002E6B),
    primaryContainer = Color(0xFF16407F),
    onPrimaryContainer = Color(0xFFDCE8FF),

    secondary = Color(0xFFFFB68C),
    onSecondary = Color(0xFF4A2000),
    secondaryContainer = Color(0xFF6E3300),
    onSecondaryContainer = Color(0xFFFFDDBF),

    tertiary = Color(0xFF8FD8B6),
    onTertiary = Color(0xFF003924),
    tertiaryContainer = Color(0xFF135C3E),
    onTertiaryContainer = Color(0xFFCDEFDF),

    background = Color(0xFF0B1120),
    onBackground = Color(0xFFDFE6F5),
    surface = Color(0xFF141B2E),
    onSurface = Color(0xFFDFE6F5),
    surfaceVariant = Color(0xFF2C3650),
    onSurfaceVariant = Color(0xFFB4BFDA),
    outline = Color(0xFF808CA8),
    outlineVariant = Color(0xFF2C3650),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightGradients = AppGradients(
    header = listOf(Color(0xFF2F6BD8), Color(0xFF5A8DEE), Color(0xFF7FA8F2)),
    background = listOf(Color(0xFFEDF3FE), Color(0xFFF6F9FF), Color(0xFFFFFFFF)),
)

private val DarkGradients = AppGradients(
    header = listOf(Color(0xFF10233F), Color(0xFF16304F), Color(0xFF1B3A60)),
    background = listOf(Color(0xFF0B1120), Color(0xFF0E1526), Color(0xFF121A2E)),
)

/** Вертикальный градиент фона экрана. */
@Composable
fun screenBackgroundBrush(): Brush {
    val g = LocalAppGradients.current
    return Brush.verticalGradient(g.background)
}

/** Градиент шапки (слева направо, по диагонали). */
@Composable
fun headerBrush(): Brush {
    val g = LocalAppGradients.current
    return Brush.linearGradient(g.header, start = Offset.Zero, end = Offset.Infinite)
}

private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            letterSpacing = (-0.3).sp,
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 21.sp,
            lineHeight = 27.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
    )
}

// Более «пухлые» скругления — современный мягкий стиль
private val AppShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(30.dp),
)

val LocalAppGradients = staticCompositionLocalOf { LightGradients }

@Composable
fun PobedaMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val gradients = if (darkTheme) DarkGradients else LightGradients
    androidx.compose.runtime.CompositionLocalProvider(LocalAppGradients provides gradients) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}
