package com.agentlife.conversation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColorScheme = lightColorScheme(
    primary = AppColors.LightPrimary,
    onPrimary = AppColors.LightSurface,
    primaryContainer = AppColors.LightSurfaceHigh,
    onPrimaryContainer = AppColors.LightPrimary,
    secondary = AppColors.LightMuted,
    onSecondary = AppColors.LightSurface,
    secondaryContainer = AppColors.LightSurface,
    onSecondaryContainer = AppColors.LightMuted,
    background = AppColors.LightBackground,
    onBackground = AppColors.LightOnSurface,
    surface = AppColors.LightSurface,
    onSurface = AppColors.LightOnSurface,
    surfaceVariant = AppColors.LightSurfaceVariant,
    onSurfaceVariant = AppColors.LightMuted,
    outline = AppColors.LightOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = AppColors.DarkPrimary,
    onPrimary = AppColors.DarkOnPrimary,
    primaryContainer = AppColors.DarkPrimaryContainer,
    onPrimaryContainer = AppColors.DarkOnPrimaryContainer,
    secondary = AppColors.DarkSecondary,
    onSecondary = AppColors.DarkSurface,
    secondaryContainer = AppColors.DarkSurfaceContainer,
    onSecondaryContainer = AppColors.DarkText,
    tertiary = AppColors.DarkTertiary,
    background = AppColors.DarkBackground,
    onBackground = AppColors.DarkOnSurface,
    surface = AppColors.DarkSurface,
    onSurface = AppColors.DarkOnSurface,
    surfaceVariant = AppColors.DarkSurfaceVariant,
    onSurfaceVariant = AppColors.DarkMuted,
    outline = AppColors.DarkOutline,
    outlineVariant = AppColors.DarkOutlineVariant,
    error = AppColors.DarkError,
    onError = AppColors.DarkOnError,
)

/**
 * 符合 Material Design 3 规范的主题系统。
 * 在 Android 12+ (API 31+) 上优先使用系统动态取色 (Dynamic Color / Monet)，
 * 随壁纸和系统个性化自动生成调色板，完全不使用固定死颜色；
 * 在低于 Android 12 或动态取色未开启时，采用符合 M3 Token 阶梯的标准调色板。
 */
@Composable
fun AgentLifeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}