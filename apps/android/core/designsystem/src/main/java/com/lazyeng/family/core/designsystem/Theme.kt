package com.lazyeng.family.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LazyEngColorScheme = lightColorScheme(
    primary = LazyEngColors.PrimaryMint,
    onPrimary = LazyEngColors.TextPrimary,
    primaryContainer = LazyEngColors.MintSoft,
    onPrimaryContainer = LazyEngColors.PrimaryDeep,
    secondary = LazyEngColors.DawnBlue,
    onSecondary = LazyEngColors.TextPrimary,
    secondaryContainer = LazyEngColors.BlueSoft,
    onSecondaryContainer = LazyEngColors.BlueDeep,
    tertiary = LazyEngColors.Warning,
    onTertiary = LazyEngColors.TextPrimary,
    error = LazyEngColors.Danger,
    onError = Color.White,
    background = LazyEngColors.Background,
    onBackground = LazyEngColors.TextPrimary,
    surface = LazyEngColors.Surface,
    onSurface = LazyEngColors.TextPrimary,
    surfaceVariant = LazyEngColors.SurfaceSoft,
    onSurfaceVariant = LazyEngColors.TextSecondary,
    outline = LazyEngColors.Border,
    outlineVariant = LazyEngColors.BorderStrong,
)

@Composable
fun LazyEngTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LazyEngColorScheme,
        typography = LazyEngTypography,
        shapes = LazyEngMaterialShapes,
        content = content,
    )
}
