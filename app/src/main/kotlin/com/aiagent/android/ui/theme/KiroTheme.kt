package com.aiagent.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Visual tokens taken straight from the Kiro Mobile Chat web project's CSS
 * variables (`src/app/globals.css`). Keeping them in one place lets the rest
 * of the UI stay 1:1 with the original design.
 */
object KiroColors {
    val Background = Color(0xFF0B0D12)
    val Foreground = Color(0xFFE7E9EE)
    val Surface = Color(0xFF14171F)
    val Surface2 = Color(0xFF1B1F2A)
    val Border = Color(0xFF262B38)
    val Accent = Color(0xFF7C5CFF)
    val Accent2 = Color(0xFF5CB4FF)
    val Muted = Color(0xFF8A90A2)
    val UserBubble = Color(0xFF1E2430)
    val AssistantBubble = Color(0xFF161B25)
    val Danger = Color(0xFFE36464)
    val Success = Color(0xFF58C896)
}

/** Linear gradient used on the brand badge, send button, progress bars. */
val KiroAccentBrush: Brush
    get() = Brush.linearGradient(
        colors = listOf(KiroColors.Accent, KiroColors.Accent2),
    )

@Composable
fun KiroTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = KiroColors.Accent,
        onPrimary = Color.White,
        secondary = KiroColors.Accent2,
        onSecondary = Color.White,
        background = KiroColors.Background,
        onBackground = KiroColors.Foreground,
        surface = KiroColors.Surface,
        onSurface = KiroColors.Foreground,
        surfaceVariant = KiroColors.Surface2,
        onSurfaceVariant = KiroColors.Foreground,
        outline = KiroColors.Border,
        error = KiroColors.Danger,
    )
    MaterialTheme(
        colorScheme = scheme,
        content = content,
    )
}
