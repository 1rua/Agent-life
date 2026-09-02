package com.agentlife.conversation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object AppColors {
    val LightCanvas = Color(0xFFF1F4F1)
    val LightSurface = Color(0xFFE3ECE7)
    val LightSurfaceHigh = Color(0xFFD5E2DC)
    val LightPrimary = Color(0xFF2F645C)
    val LightAccent = Color(0xFFB8874A)
    val LightText = Color(0xFF1C2724)
    val LightMuted = Color(0xFF63706B)
    val LightError = Color(0xFFA95E50)

    // Dark Palette (MD3 Amber Dynamic Token Architecture from HTML Motion Preview)
    val DarkCanvas = Color(0xFF0F0D09)
    val DarkSurface = Color(0xFF16130D)
    val DarkSurfaceContainerLow = Color(0xFF1F1B14)
    val DarkSurfaceContainer = Color(0xFF231F18)
    val DarkSurfaceContainerHigh = Color(0xFF2E2922)
    val DarkSurfaceContainerHighest = Color(0xFF39342C)
    val DarkSurfaceHigh = DarkSurfaceContainerHigh
    val DarkPrimary = Color(0xFFFFBA2B)
    val DarkOnPrimary = Color(0xFF432C00)
    val DarkPrimaryContainer = Color(0xFF604000)
    val DarkOnPrimaryContainer = Color(0xFFFFDE9F)
    val DarkSecondary = Color(0xFFDBC39E)
    val DarkTertiary = Color(0xFFB4CE9D)
    val DarkAccent = Color(0xFFFFBA2B)
    val DarkText = Color(0xFFE9E1D8)
    val DarkMuted = Color(0xFF998F80)
    val DarkOutline = Color(0xFF4D4539)
    val DarkOutlineVariant = Color(0xFF342D23)
    val DarkError = Color(0xFFFFB4AB)
    val DarkOnError = Color(0xFF690005)

    // Status colors
    val StatusOnline = Color(0xFF4ADE80)
    val StatusWarning = Color(0xFFFBBF24)

    val LightBackground = LightCanvas
    val LightSurfaceVariant = LightSurfaceHigh
    val LightOnSurface = LightText
    val LightOutline = LightMuted

    val DarkBackground = DarkCanvas
    val DarkSurfaceVariant = DarkSurfaceContainerHigh
    val DarkOnSurface = DarkText
    val DarkOutlineDefault = DarkOutline
}

object Dimensions {
    val MinimumTouchTarget = 48.dp
    val DockedBall = 56.dp
    val StrokeHairline = 1.dp
    val StrokeStitch = 2.5.dp
}