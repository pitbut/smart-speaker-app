package com.pit.phonecleaner.ui

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

private val Light = lightColorScheme(
    primary = Color(0xFF1B8A4B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7F1C9),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4F6354),
    tertiary = Color(0xFF3B6470),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF6FBF4),
    surface = Color(0xFFF6FBF4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7EDB9C),
    onPrimary = Color(0xFF00391B),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = Color(0xFFB7F1C9),
    secondary = Color(0xFFB6CCB9),
    tertiary = Color(0xFFA3CDDA),
    background = Color(0xFF101510),
    surface = Color(0xFF101510),
)

val Warning = Color(0xFFE08A00)

@Composable
fun CleanerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
