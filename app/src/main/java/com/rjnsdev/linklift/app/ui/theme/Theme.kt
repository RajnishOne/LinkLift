package com.rjnsdev.linklift.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = LinkLiftAccent,
    onPrimary = LinkLiftTextPrimary,
    secondary = LinkLiftBlue,
    onSecondary = LinkLiftTextPrimary,
    tertiary = LinkLiftCyan,
    background = LinkLiftBackground,
    onBackground = LinkLiftTextPrimary,
    surface = LinkLiftSurface,
    onSurface = LinkLiftTextPrimary,
    surfaceVariant = LinkLiftSurfaceAlt,
    onSurfaceVariant = LinkLiftTextSecondary,
    outline = LinkLiftOutline,
    error = LinkLiftError,
    onError = LinkLiftTextPrimary
)

private val LightColorScheme = lightColorScheme(
    primary = LinkLiftAccent,
    onPrimary = LinkLiftTextPrimary,
    secondary = LinkLiftBlue,
    onSecondary = LinkLiftTextPrimary,
    tertiary = LinkLiftCyan,
    background = LinkLiftLightBackground,
    onBackground = LinkLiftLightTextPrimary,
    surface = LinkLiftLightSurface,
    onSurface = LinkLiftLightTextPrimary,
    surfaceVariant = LinkLiftLightSurfaceAlt,
    onSurfaceVariant = LinkLiftLightTextSecondary,
    outline = LinkLiftLightOutline,
    error = LinkLiftError,
    onError = LinkLiftTextPrimary
)

@Composable
fun LinkLiftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}