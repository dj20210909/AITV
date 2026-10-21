package com.aitv.assistant

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B63CE),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E9FF),
    onPrimaryContainer = Color(0xFF001D36),
    secondary = Color(0xFF00696E),
    secondaryContainer = Color(0xFFB8ECEF),
    background = Color(0xFFF7F9FF),
    surface = Color(0xFFF7F9FF),
    surfaceVariant = Color(0xFFE6EEF8),
    onSurfaceVariant = Color(0xFF43505F),
    outline = Color(0xFF73808F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD8E9FF),
    secondary = Color(0xFF9CD9DE),
    secondaryContainer = Color(0xFF005055),
    background = Color(0xFF09121C),
    surface = Color(0xFF0E1822),
    surfaceVariant = Color(0xFF1B2733),
    onSurfaceVariant = Color(0xFFBCC8D6),
    outline = Color(0xFF87939F)
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) DarkColors else LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = AppShapes,
        content = content
    )
}
