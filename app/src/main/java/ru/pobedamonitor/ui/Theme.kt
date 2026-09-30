package ru.pobedamonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Цветовая палитра приложения: мягкие тёплые тона (молочный фон, песчаные
 * поверхности) + фирменный алый «Победы» как акцент.
 */

// Светлая тема
private val LightColors = lightColorScheme(
    primary = Color(0xFFD6303C),            // алый «Победы», чуть приглушённый
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1DE),   // нежно-розовый контейнер
    onPrimaryContainer = Color(0xFF410002),

    secondary = Color(0xFF7A5C3E),          // тёплый коричневый (доп. акцент)
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E3CF), // песочный
    onSecondaryContainer = Color(0xFF2C1800),

    tertiary = Color(0xFF3E6B5A),           // спокойный зелёный (погода/успех)
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD8EBE1),
    onTertiaryContainer = Color(0xFF072116),

    background = Color(0xFFFBF7F2),         // молочный — основной фон экрана
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFFFBF6),            // карточки — почти белые с тёплым оттенком
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFF3E9DC),     // вторичные поверхности (чипы, поля)
    onSurfaceVariant = Color(0xFF52440F),
    outline = Color(0xFF8A795F),
    outlineVariant = Color(0xFFDED0BC),

    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

// Тёмная тема — глубокий графит с тёплым подтоном
private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB3AC),
    onPrimary = Color(0xFF68000A),
    primaryContainer = Color(0xFF930012),
    onPrimaryContainer = Color(0xFFFFDAD6),

    secondary = Color(0xFFE7BDA0),
    onSecondary = Color(0xFF442B10),
    secondaryContainer = Color(0xFF5E4022),
    onSecondaryContainer = Color(0xFFFFDCC0),

    tertiary = Color(0xFFB4D5C4),
    onTertiary = Color(0xFF1B3A2C),
    tertiaryContainer = Color(0xFF2F5241),
    onTertiaryContainer = Color(0xFFD1F2DF),

    background = Color(0xFF16120E),
    onBackground = Color(0xFFECE0D3),
    surface = Color(0xFF1E1915),
    onSurface = Color(0xFFECE0D3),
    surfaceVariant = Color(0xFF4E453A),
    onSurfaceVariant = Color(0xFFD5C5B0),
    outline = Color(0xFF9E9080),
    outlineVariant = Color(0xFF4E453A),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            lineHeight = 28.sp,
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            lineHeight = 24.sp,
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            letterSpacing = 0.1.sp,
        ),
    )
}

@Composable
fun PobedaMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
