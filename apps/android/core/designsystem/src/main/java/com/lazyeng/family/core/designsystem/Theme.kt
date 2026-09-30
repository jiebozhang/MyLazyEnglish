package com.lazyeng.family.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

private fun lightScheme(palette: LazyEngPalette) = lightColorScheme(
    primary = palette.primaryDeep,
    onPrimary = palette.surface,
    primaryContainer = palette.mintSoft,
    onPrimaryContainer = palette.primaryDeep,
    secondary = palette.blueDeep,
    onSecondary = palette.surface,
    secondaryContainer = palette.blueSoft,
    onSecondaryContainer = palette.blueDeep,
    tertiary = palette.warning,
    onTertiary = palette.textPrimary,
    error = palette.danger,
    onError = palette.surface,
    background = palette.background,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceSoft,
    onSurfaceVariant = palette.textSecondary,
    outline = palette.borderStrong,
    outlineVariant = palette.border,
)

private fun darkScheme(palette: LazyEngPalette) = darkColorScheme(
    primary = palette.primaryMint,
    onPrimary = ColorTokens.DarkInk,
    primaryContainer = palette.mintSoft,
    onPrimaryContainer = palette.primaryMint,
    secondary = palette.dawnBlue,
    onSecondary = ColorTokens.DarkInk,
    secondaryContainer = palette.blueSoft,
    onSecondaryContainer = palette.dawnBlue,
    tertiary = palette.warning,
    onTertiary = ColorTokens.DarkInk,
    error = palette.danger,
    onError = ColorTokens.DarkInk,
    background = palette.background,
    onBackground = palette.textPrimary,
    surface = palette.surface,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surfaceSoft,
    onSurfaceVariant = palette.textSecondary,
    outline = palette.borderStrong,
    outlineVariant = palette.border,
)

private object ColorTokens {
    val DarkInk = androidx.compose.ui.graphics.Color(0xFF102019)
}

@Composable
fun LazyEngTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkLazyEngPalette else LightLazyEngPalette
    androidx.compose.runtime.CompositionLocalProvider(LocalLazyEngPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) darkScheme(palette) else lightScheme(palette),
            typography = LazyEngTypography,
            shapes = LazyEngMaterialShapes,
            content = content,
        )
    }
}
