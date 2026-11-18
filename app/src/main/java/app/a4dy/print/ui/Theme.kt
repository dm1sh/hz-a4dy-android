// SPDX-License-Identifier: MIT
package app.a4dy.print.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF245940), onPrimary = Color.White, primaryContainer = Color(0xFFD9EDD9), onPrimaryContainer = Color(0xFF173F2A),
    secondary = Color(0xFF586951), secondaryContainer = Color(0xFFE5ECDC),
    background = Color(0xFFF5F7F1), surface = Color(0xFFF5F7F1), surfaceContainerLow = Color(0xFFEEF1E9),
    surfaceContainer = Color(0xFFE8ECE2), surfaceContainerHigh = Color(0xFFE2E7DC), outline = Color(0xFF76816F), outlineVariant = Color(0xFFD3DACC),
    onSurface = Color(0xFF1C251B), onSurfaceVariant = Color(0xFF576150))
private val Dark = darkColorScheme(
    primary = Color(0xFFAACFB0), onPrimary = Color(0xFF113821), primaryContainer = Color(0xFF284D32), onPrimaryContainer = Color(0xFFD9EDD9),
    background = Color(0xFF11170F), surface = Color(0xFF11170F), surfaceContainerLow = Color(0xFF1B2318),
    surfaceContainer = Color(0xFF242D20), surfaceContainerHigh = Color(0xFF2D3729), onSurface = Color(0xFFE3EBDC), onSurfaceVariant = Color(0xFFBBC7B4))

@Composable fun A4Theme(dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme(); val context = LocalContext.current
    val scheme = if (dynamic) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) } else if (dark) Dark else Light
    MaterialTheme(colorScheme = scheme, typography = Typography(
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 38.sp, lineHeight = 43.sp, fontWeight = FontWeight.Normal),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp),
        titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    ), content = content)
}
