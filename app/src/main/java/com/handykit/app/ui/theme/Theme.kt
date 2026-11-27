package com.handykit.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 取自 demo/index.html 的视觉基线。深色模式首版不承诺。
private val LightColors = lightColorScheme(
    primary = Color(0xFF166C5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEAF4EF),
    onPrimaryContainer = Color(0xFF0B4A3E),
    secondary = Color(0xFF3C6F62),
    surface = Color.White,
    onSurface = Color(0xFF172B2A),
    surfaceVariant = Color(0xFFF7F9F7),
    onSurfaceVariant = Color(0xFF768382),
    outline = Color(0xFFE7ECEB),
    error = Color(0xFFB3261E),
)

@Composable
fun HandyKitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // 首版固定浅色：Demo 基线为浅色，深色未做对比度验证。
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
