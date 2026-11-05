package io.github.easylsp.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B60), onPrimary = Color.White,
    primaryContainer = Color(0xFFA5F2DE), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4B635D), secondaryContainer = Color(0xFFCDE8DF),
    background = Color(0xFFF4F7F5), surface = Color(0xFFF9FCFA),
    surfaceContainer = Color(0xFFEDF2EE), surfaceContainerLow = Color.White,
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF89D6C3), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005047), onPrimaryContainer = Color(0xFFA5F2DE),
    secondary = Color(0xFFB1CCC3), secondaryContainer = Color(0xFF334C44),
    background = Color(0xFF101513), surface = Color(0xFF151D19),
    surfaceContainer = Color(0xFF202924), surfaceContainerLow = Color(0xFF19221D),
)

@Composable fun EasylspTheme(appearance: String, monet: Boolean = false, content: @Composable () -> Unit) {
    val dark = appearance == "dark" || (appearance == "system" && isSystemInDarkTheme())
    val colors = if (monet) { if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current) } else if (dark) DarkColors else LightColors
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        window.statusBarColor = Color.Transparent.toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()
        window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, shapes = Shapes(medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(16.dp)), content = content)
}
