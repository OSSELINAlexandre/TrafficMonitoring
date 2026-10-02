package com.trafficmonitor.privacy.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Teal = Color(0xFF0B6E6A)
private val TealDark = Color(0xFF8FD9D3)
private val Ink = Color(0xFF1A1C1C)
private val Paper = Color(0xFFF3F6F6)
private val Night = Color(0xFF101414)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC5EBE7),
    onPrimaryContainer = Color(0xFF00201E),
    secondary = Color(0xFF3E5C76),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003734),
    primaryContainer = Color(0xFF0E4F4C),
    onPrimaryContainer = Color(0xFFC5EBE7),
    secondary = Color(0xFFB7C9DC),
    background = Night,
    onBackground = Color(0xFFE3E6E6),
    surface = Night,
    onSurface = Color(0xFFE3E6E6),
)

@Composable
fun TrafficMonitorTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
