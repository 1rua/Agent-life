package com.agentlife.conversation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = MossFlintColors.LightMoss,
    onPrimary = MossFlintColors.LightSurface,
    primaryContainer = MossFlintColors.LightMossContainer,
    onPrimaryContainer = MossFlintColors.LightMoss,
    secondary = MossFlintColors.LightFlint,
    onSecondary = MossFlintColors.LightSurface,
    secondaryContainer = MossFlintColors.LightFlintContainer,
    onSecondaryContainer = MossFlintColors.LightFlint,
    background = MossFlintColors.LightBackground,
    onBackground = MossFlintColors.LightOnSurface,
    surface = MossFlintColors.LightSurface,
    onSurface = MossFlintColors.LightOnSurface,
    surfaceVariant = MossFlintColors.LightSurfaceVariant,
    onSurfaceVariant = MossFlintColors.LightFlint,
    outline = MossFlintColors.LightOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = MossFlintColors.DarkMoss,
    onPrimary = MossFlintColors.DarkBackground,
    primaryContainer = MossFlintColors.DarkMossContainer,
    onPrimaryContainer = MossFlintColors.DarkMoss,
    secondary = MossFlintColors.DarkFlint,
    onSecondary = MossFlintColors.DarkBackground,
    secondaryContainer = MossFlintColors.DarkFlintContainer,
    onSecondaryContainer = MossFlintColors.DarkFlint,
    background = MossFlintColors.DarkBackground,
    onBackground = MossFlintColors.DarkOnSurface,
    surface = MossFlintColors.DarkSurface,
    onSurface = MossFlintColors.DarkOnSurface,
    surfaceVariant = MossFlintColors.DarkSurfaceVariant,
    onSurfaceVariant = MossFlintColors.DarkFlint,
    outline = MossFlintColors.DarkOutline,
)

@Composable
fun AgentLifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
