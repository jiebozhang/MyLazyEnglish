package com.lazyeng.family.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val FamilyColorScheme = lightColorScheme(
    primary = MintGreen,
    secondary = DawnBlue,
    background = WarmBackground,
    surface = androidx.compose.ui.graphics.Color.White,
    onBackground = PrimaryText,
    onSurface = PrimaryText,
    onSurfaceVariant = SecondaryText,
)

@Composable
fun LazyEngTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FamilyColorScheme,
        content = content,
    )
}
