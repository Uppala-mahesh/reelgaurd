package com.example.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val AuroraColorScheme = darkColorScheme(
    primary = Violet,
    onPrimary = Ink,
    secondary = Aqua,
    onSecondary = Ink,
    tertiary = Mint,
    background = Ink,
    onBackground = TextBright,
    surface = Surface1,
    onSurface = TextBright,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    error = Rose,
    outline = HairLine
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            @Suppress("DEPRECATION")
            window?.statusBarColor = Ink.toArgb()
            @Suppress("DEPRECATION")
            window?.navigationBarColor = Ink.toArgb()
        }
    }

    MaterialTheme(
        colorScheme = AuroraColorScheme,
        typography = Typography,
        content = content
    )
}
