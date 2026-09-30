package com.lazyeng.family.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class LazyEngPalette(
    val background: Color,
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val primaryMint: Color,
    val primaryDeep: Color,
    val dawnBlue: Color,
    val blueDeep: Color,
    val surfaceSoft: Color,
    val border: Color,
    val borderStrong: Color,
    val warning: Color,
    val danger: Color,
    val subtitleHighlight: Color,
    val mintSoft: Color,
    val blueSoft: Color,
)

internal val LightLazyEngPalette = LazyEngPalette(
    background = Color(0xFFFAF8F3),
    surface = Color(0xFFFFFFFF),
    textPrimary = Color(0xFF2D3B36),
    textSecondary = Color(0xFF58665F),
    primaryMint = Color(0xFF34D399),
    primaryDeep = Color(0xFF138A66),
    dawnBlue = Color(0xFF38BDFA),
    blueDeep = Color(0xFF147DB5),
    surfaceSoft = Color(0xFFF3F6F3),
    border = Color(0xFFE5ECE7),
    borderStrong = Color(0xFFAAB9B1),
    warning = Color(0xFFF5B83D),
    danger = Color(0xFFE85D68),
    subtitleHighlight = Color(0xFFCFF7E2),
    mintSoft = Color(0xFFEAFBF3),
    blueSoft = Color(0xFFEAF7FE),
)

internal val DarkLazyEngPalette = LazyEngPalette(
    background = Color(0xFF111714),
    surface = Color(0xFF1D2521),
    textPrimary = Color(0xFFF1F5F2),
    textSecondary = Color(0xFFBBC8C0),
    primaryMint = Color(0xFF70E4B3),
    primaryDeep = Color(0xFF86DDB7),
    dawnBlue = Color(0xFF83CFFF),
    blueDeep = Color(0xFF8BCDF1),
    surfaceSoft = Color(0xFF29342E),
    border = Color(0xFF3A4840),
    borderStrong = Color(0xFF718078),
    warning = Color(0xFFFFD17B),
    danger = Color(0xFFFF9299),
    subtitleHighlight = Color(0xFF345743),
    mintSoft = Color(0xFF244936),
    blueSoft = Color(0xFF233D4A),
)

internal val LocalLazyEngPalette = staticCompositionLocalOf { LightLazyEngPalette }

object LazyEngColors {
    val Background: Color @Composable get() = LocalLazyEngPalette.current.background
    val Surface: Color @Composable get() = LocalLazyEngPalette.current.surface
    val TextPrimary: Color @Composable get() = LocalLazyEngPalette.current.textPrimary
    val TextSecondary: Color @Composable get() = LocalLazyEngPalette.current.textSecondary
    val PrimaryMint: Color @Composable get() = LocalLazyEngPalette.current.primaryMint
    val PrimaryDeep: Color @Composable get() = LocalLazyEngPalette.current.primaryDeep
    val DawnBlue: Color @Composable get() = LocalLazyEngPalette.current.dawnBlue
    val BlueDeep: Color @Composable get() = LocalLazyEngPalette.current.blueDeep
    val SurfaceSoft: Color @Composable get() = LocalLazyEngPalette.current.surfaceSoft
    val Border: Color @Composable get() = LocalLazyEngPalette.current.border
    val BorderStrong: Color @Composable get() = LocalLazyEngPalette.current.borderStrong
    val Warning: Color @Composable get() = LocalLazyEngPalette.current.warning
    val Danger: Color @Composable get() = LocalLazyEngPalette.current.danger
    val SubtitleHighlight: Color @Composable get() = LocalLazyEngPalette.current.subtitleHighlight
    val MintSoft: Color @Composable get() = LocalLazyEngPalette.current.mintSoft
    val BlueSoft: Color @Composable get() = LocalLazyEngPalette.current.blueSoft
}
