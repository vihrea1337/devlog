package io.github.vihrea1337.devlog.android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Фирменные цвета DevLog — те же, что на веб-странице, чтобы приложение и сайт
// выглядели одним продуктом.
private val Brand = Color(0xFF3B6EF5)
private val BrandDark = Color(0xFF8AA8FF)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5FF),
    onPrimaryContainer = Color(0xFF0B2370),
    secondary = Color(0xFF5B6478),
    secondaryContainer = Color(0xFFE2E6F1),
    tertiary = Color(0xFF1F9D55),
    background = Color(0xFFF6F7FB),
    surface = Color(0xFFF6F7FB),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1F7),
    surfaceContainerHigh = Color(0xFFE9ECF4),
    outlineVariant = Color(0xFFDADFEA),
)

private val DarkColors = darkColorScheme(
    primary = BrandDark,
    onPrimary = Color(0xFF0B2370),
    primaryContainer = Color(0xFF243C8F),
    onPrimaryContainer = Color(0xFFDDE5FF),
    secondary = Color(0xFFBFC6DA),
    secondaryContainer = Color(0xFF3A4152),
    tertiary = Color(0xFF6FD69A),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceContainerLowest = Color(0xFF0C0E12),
    surfaceContainerLow = Color(0xFF191C22),
    surfaceContainer = Color(0xFF1D2027),
    surfaceContainerHigh = Color(0xFF272A31),
    outlineVariant = Color(0xFF3A3F4B),
)

/**
 * Цвета статусов обработки ИИ. Их нет в стандартной палитре Material, поэтому — свой
 * набор, тоже со светлым и тёмным вариантом, раздаваемый через CompositionLocal
 * (как MaterialTheme.colorScheme, только наш).
 */
@Immutable
data class StatusColors(val pending: Color, val done: Color, val failed: Color, val muted: Color)

private val LightStatus = StatusColors(Color(0xFFB7791F), Color(0xFF1F9D55), Color(0xFFD64545), Color(0xFF6B7382))
private val DarkStatus = StatusColors(Color(0xFFF2B451), Color(0xFF6FD69A), Color(0xFFFF8A8A), Color(0xFF9AA2B1))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    )
}

/**
 * Тема приложения: светлая/тёмная по системной настройке.
 *
 * dynamicColor — «Material You» (Android 12+): цвета из обоев телефона. По умолчанию
 * выключен: у продукта узнаваемый фирменный синий, а не случайный цвет обоев.
 */
@Composable
fun DevLogTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusColors provides if (darkTheme) DarkStatus else LightStatus,
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
