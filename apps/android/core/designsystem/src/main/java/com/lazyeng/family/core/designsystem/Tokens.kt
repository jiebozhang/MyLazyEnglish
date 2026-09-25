package com.lazyeng.family.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LazyEngSpacing {
    val xSmall = 4.dp
    val small = 8.dp
    val mediumSmall = 12.dp
    val medium = 16.dp
    val large = 20.dp
    val xLarge = 24.dp
    val xxLarge = 32.dp
}

object LazyEngCornerSize {
    val small = 8.dp
    val card = 16.dp
    val prominentCard = 20.dp
    val largeCard = 24.dp
    val bottomSheet = 28.dp
}

object LazyEngShapes {
    val small = RoundedCornerShape(LazyEngCornerSize.small)
    val card = RoundedCornerShape(LazyEngCornerSize.card)
    val prominentCard = RoundedCornerShape(LazyEngCornerSize.prominentCard)
    val largeCard = RoundedCornerShape(LazyEngCornerSize.largeCard)
    val bottomSheet = RoundedCornerShape(
        topStart = LazyEngCornerSize.bottomSheet,
        topEnd = LazyEngCornerSize.bottomSheet,
    )
}

object LazyEngTouchTarget {
    val minimum = 48.dp
}

object LazyEngTypeScale {
    val subtitle = 19.sp
    val subtitleTranslation = 15.sp
}

object LazyEngElevation {
    val flat: Dp = 0.dp
    val raised: Dp = 2.dp
    val overlay: Dp = 8.dp
}

object LazyEngStroke {
    val thin = 1.dp
    val strong = 2.dp
}

object LazyEngMotion {
    const val normalMillis = 200
    const val slowMillis = 300
}

val LazyEngTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
)

val LazyEngMaterialShapes = Shapes(
    extraSmall = LazyEngShapes.small,
    small = LazyEngShapes.card,
    medium = LazyEngShapes.prominentCard,
    large = LazyEngShapes.largeCard,
    extraLarge = LazyEngShapes.bottomSheet,
)
