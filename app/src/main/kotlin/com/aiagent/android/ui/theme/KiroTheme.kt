package com.aiagent.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Dark-orange palette inspired by Anthropic's Claude Code terminal UI — deep
 * brown-tinted backgrounds with warm amber accents instead of the original
 * Kiro purple. All surfaces, borders, bubbles and accents are pulled from one
 * place so the rest of the app picks up the new look without touching call
 * sites.
 */
object KiroColors {
    // Warm near-black backgrounds (slight orange tint vs neutral grey).
    val Background = Color(0xFF0E0A06)
    val Foreground = Color(0xFFF1E6D6)
    val Surface = Color(0xFF18120D)
    val Surface2 = Color(0xFF221913)
    val Border = Color(0xFF3A2A1E)
    // Claude Code burnt-orange + amber hi-light for icons / focus rings.
    val Accent = Color(0xFFCC785C)
    val Accent2 = Color(0xFFFFB066)
    val Muted = Color(0xFFA08775)
    val UserBubble = Color(0xFF2A1D13)
    val AssistantBubble = Color(0xFF1B140E)
    val Danger = Color(0xFFE36464)
    val Success = Color(0xFF7DB37D)
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
