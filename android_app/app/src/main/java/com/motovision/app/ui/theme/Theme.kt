package com.motovision.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.runtime.Composable


private val DarkColorScheme = darkColorScheme(
    primary = CyberNeonBlue,
    secondary = CyberNeonYellow,
    tertiary = CyberNeonRed,
    background = CyberBlack,
    surface = CyberDarkGrey,
    onPrimary = CyberBlack,
    onSecondary = CyberBlack,
    onTertiary = TextWhite,
    onBackground = TextWhite,
    onSurface = TextWhite,
)

@Composable
fun MotoVisionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false, // TẮT Dynamic Color để giữ đúng chất Cyberpunk
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme // Luôn dùng Dark Scheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Đặt màu thanh trạng thái (Status Bar) thành trong suốt hoặc đen
            window.statusBarColor = CyberBlack.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography, // File Type.kt giữ nguyên mặc định cũng được
        content = content
    )
}