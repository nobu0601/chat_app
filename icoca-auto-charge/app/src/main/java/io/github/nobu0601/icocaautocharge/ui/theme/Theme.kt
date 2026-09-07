package io.github.nobu0601.icocaautocharge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val IcocaBlue = Color(0xFF0B4F9E)
private val IcocaBlueLight = Color(0xFF6FA8DC)

private val LightColors = lightColorScheme(
    primary = IcocaBlue,
    secondary = Color(0xFF3E6B99),
)

private val DarkColors = darkColorScheme(
    primary = IcocaBlueLight,
    secondary = Color(0xFF8FB4D6),
)

@Composable
fun IcocaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
