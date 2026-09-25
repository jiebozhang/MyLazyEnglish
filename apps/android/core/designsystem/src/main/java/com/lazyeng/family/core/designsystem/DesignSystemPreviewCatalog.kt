package com.lazyeng.family.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density

private object DesignSystemFixtures {
    const val videoTitle = "The Red Kite"
    const val videoDetail = "动画短片 · Power Up 2"
    val subtitleWords = listOf(
        SubtitleWord("The "), SubtitleWord("little ", lookupTarget = true),
        SubtitleWord("kite "), SubtitleWord("flies "), SubtitleWord("above "), SubtitleWord("the "), SubtitleWord("park."),
    )
}

@Preview(name = "LazyEng Design System · 412dp", widthDp = 412, showBackground = true)
@Composable
fun LazyEngDesignSystemCatalogPreview() {
    LazyEngTheme {
        CatalogContent(Modifier.fillMaxWidth())
    }
}

@Preview(name = "LazyEng Design System · Large text", widthDp = 412, showBackground = true)
@Composable
fun LazyEngDesignSystemLargeTextPreview() {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
        LazyEngTheme {
            CatalogContent(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CatalogContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(LazyEngSpacing.large),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large),
    ) {
        SectionHeader("基础操作", actionLabel = "查看全部", onAction = {})
        PrimaryButton("加入学习", onClick = {}, modifier = Modifier.fillMaxWidth())
        PrimaryButton("正在处理", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)
        SecondaryButton("稍后再说", onClick = {}, modifier = Modifier.fillMaxWidth())
        SecondaryButton("暂不可用", onClick = {}, modifier = Modifier.fillMaxWidth(), enabled = false)

        SectionHeader("状态")
        Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
            StatusChip("已掌握", StatusTone.SUCCESS)
            StatusChip("学习中", StatusTone.LEARNING)
            StatusChip("待复习", StatusTone.WARNING)
            StatusChip("暂不可用", StatusTone.DANGER)
            StatusChip("未开始", StatusTone.NEUTRAL)
        }

        SectionHeader("推荐视频")
        VideoCard(
            title = DesignSystemFixtures.videoTitle,
            detail = DesignSystemFixtures.videoDetail,
            duration = "08:24",
            status = "英文字幕",
            onClick = {},
        )
        DataCard(label = "今日学习", value = "12 分钟", detail = "查词 8 次 · 复习 5 个")

        SectionHeader("交互字幕")
        SubtitleLine(
            words = DesignSystemFixtures.subtitleWords,
            active = true,
            translation = "小风筝飞过公园。",
            onWordClick = {},
        )
        SubtitleLine(
            words = DesignSystemFixtures.subtitleWords,
            active = false,
            onWordClick = {},
        )

        SectionHeader("反馈状态")
        EmptyState("还没有收藏", "点按字幕中的单词，把喜欢的表达留在这里。", actionLabel = "浏览视频", onAction = {})
        ErrorState("暂时无法加载", "本地内容仍可继续使用。", retryLabel = "重试", onRetry = {})
        LoadingSkeleton()

        SectionHeader("复习与安全")
        Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
            RatingButton("忘了", RatingChoice.FORGOT, onClick = {})
            RatingButton("有点模糊", RatingChoice.UNCERTAIN, onClick = {}, selected = true)
            RatingButton("记得", RatingChoice.REMEMBERED, onClick = {})
        }
        BottomSheetHandle()
        PinDots(enteredCount = 2)
    }
}
