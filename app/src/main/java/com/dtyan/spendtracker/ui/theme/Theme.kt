package com.dtyan.spendtracker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Brand = Color(0xFF2E7D5B)
private val BrandLight = Color(0xFF6FBF9A)

private val LightColors = lightColorScheme(
    primary = Brand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8EBD3),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF092017),
    tertiary = Color(0xFF3D6373),
    background = Color(0xFFF7FBF7),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFF7FBF7),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDBE5DD),
    onSurfaceVariant = Color(0xFF3F4943),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFF6F7973),
)

private val DarkColors = darkColorScheme(
    primary = BrandLight,
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF00522F),
    onPrimaryContainer = Color(0xFFB8EBD3),
    secondary = Color(0xFFB2CCBE),
    onSecondary = Color(0xFF1E352B),
    secondaryContainer = Color(0xFF344C41),
    onSecondaryContainer = Color(0xFFCEE9DA),
    tertiary = Color(0xFFA4CDDF),
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DF),
    surfaceVariant = Color(0xFF3F4943),
    onSurfaceVariant = Color(0xFFBFC9C2),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF899390),
)

/** Палитра для графиков — различима и в светлой, и в тёмной теме. */
val ChartPalette: List<Color> = listOf(
    Color(0xFF4CAF50), Color(0xFFFF7043), Color(0xFF42A5F5), Color(0xFFAB47BC),
    Color(0xFFFFCA28), Color(0xFF26A69A), Color(0xFFEC407A), Color(0xFF5C6BC0),
    Color(0xFF8D6E63), Color(0xFF29B6F6), Color(0xFFD4E157), Color(0xFF78909C),
)

@Composable
fun SpendTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
