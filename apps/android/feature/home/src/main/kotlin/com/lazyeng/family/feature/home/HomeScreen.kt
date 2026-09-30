package com.lazyeng.family.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.feature.home.HomeData.Companion.formatDuration

@Composable
fun HomeRoute(
    model: HomeViewModel,
    listState: LazyListState,
    onEffect: (HomeUiEffect) -> Unit,
) {
    val effectHandler by rememberUpdatedState(onEffect)
    LaunchedEffect(model) {
        model.effects.collect { effectHandler(it) }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                model.refresh()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val state by model.state.collectAsStateWithLifecycle()
    HomeScreen(
        state = state,
        onEvent = model::onEvent,
        listState = listState,
    )
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onEvent: (HomeUiEvent) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = LazyEngColors.Background,
    ) {
        when (state) {
            is HomeUiState.Loading -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(LazyEngSpacing.large)
                        .testTag("home-loading"),
                    verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
                ) {
                    LoadingSkeleton()
                    LoadingSkeleton()
                }
            }

            is HomeUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(LazyEngSpacing.large)
                        .testTag("home-error"),
                    contentAlignment = Alignment.Center,
                ) {
                    ErrorState(
                        title = "无法加载首页",
                        message = state.message,
                        retryLabel = if (state.canRetry) "重试" else null,
                        onRetry = { onEvent(HomeUiEvent.Refresh) },
                    )
                }
            }

            is HomeUiState.Empty -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("home-empty"),
                    contentPadding = PaddingValues(
                        horizontal = LazyEngSpacing.large,
                        vertical = LazyEngSpacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large),
                ) {
                    item {
                        HomeHeader(
                            profile = state.profile,
                            streakInfo = StreakInfo(0, false, "开启学习打卡第一天"),
                            onSwitchProfile = { onEvent(HomeUiEvent.SwitchProfileClicked) },
                        )
                    }
                    item {
                        EmptyState(
                            title = "还没有视频",
                            message = state.message,
                            actionLabel = "家长控制台",
                            onAction = { onEvent(HomeUiEvent.ParentConsoleClicked) },
                        )
                    }
                }
            }

            is HomeUiState.Content -> {
                val data = state.dashboard
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("home-content"),
                    contentPadding = PaddingValues(
                        horizontal = LazyEngSpacing.large,
                        vertical = LazyEngSpacing.medium,
                    ),
                    verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.large),
                ) {
                    // 1. Personalized Header
                    item {
                        HomeHeader(
                            profile = data.profile,
                            streakInfo = data.streakInfo,
                            onSwitchProfile = { onEvent(HomeUiEvent.SwitchProfileClicked) },
                        )
                    }

                    // 2. Continue Watching or First Video Guide
                    item {
                        if (data.continueWatching != null) {
                            ContinueWatchingCard(
                                item = data.continueWatching,
                                onPlay = {
                                    onEvent(
                                        HomeUiEvent.ContinueWatchingClicked(
                                            data.continueWatching.videoId,
                                            data.continueWatching.positionMs,
                                        )
                                    )
                                },
                            )
                        } else {
                            FirstVideoGuidanceCard(
                                onStart = { onEvent(HomeUiEvent.FirstVideoGuidanceClicked) },
                            )
                        }
                    }

                    // 3. Vocabulary Review Shortcut
                    item {
                        VocabularyReviewCard(
                            summary = data.vocabularySummary,
                            onClick = { onEvent(HomeUiEvent.VocabularyClicked) },
                        )
                    }

                    // 4. Recommendation Section Header
                    item {
                        SectionHeader(
                            title = "今日推荐 · 适合我的视频",
                            actionLabel = if (data.recommendations.isNotEmpty()) "视频库" else null,
                            onAction = { onEvent(HomeUiEvent.FirstVideoGuidanceClicked) },
                        )
                    }

                    // 5. Recommended Video Cards
                    if (data.recommendations.isEmpty()) {
                        item {
                            Text(
                                "暂无匹配推荐视频",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LazyEngColors.TextSecondary,
                            )
                        }
                    } else {
                        items(data.recommendations, key = { it.videoId.value }) { item ->
                            RecommendedVideoCard(
                                item = item,
                                onClick = { onEvent(HomeUiEvent.VideoCardClicked(item.videoId)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HomeHeader(
    profile: HomeProfileHeader,
    streakInfo: StreakInfo,
    onSwitchProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("home-header"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            ProfileAvatar(
                avatar = profile.avatarId,
                nickname = profile.nickname,
                modifier = Modifier
                    .clickable { onSwitchProfile() }
                    .testTag("home-profile-avatar"),
            )
            Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
                Text(
                    text = "你好，${profile.nickname}",
                    style = MaterialTheme.typography.titleLarge,
                    color = LazyEngColors.TextPrimary,
                )
                StatusChip(
                    label = "等级 ${profile.englishLevel}",
                    tone = StatusTone.LEARNING,
                    modifier = Modifier.testTag("home-level-badge"),
                )
            }
        }
        StatusChip(
            label = streakInfo.message,
            tone = if (streakInfo.hasWatchedToday) StatusTone.SUCCESS else StatusTone.NEUTRAL,
            modifier = Modifier.testTag("home-streak-badge"),
        )
    }
}

@Composable
fun ContinueWatchingCard(
    item: ContinueWatchingModel,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("continue-watching-card"),
        shape = LazyEngShapes.largeCard,
        colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = LazyEngElevation.raised),
    ) {
        Column(
            modifier = Modifier.padding(LazyEngSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        "继续你的故事",
                        style = MaterialTheme.typography.labelLarge,
                        color = LazyEngColors.PrimaryDeep,
                    )
                    Text(
                        "再看一小段吧",
                        style = MaterialTheme.typography.titleMedium,
                        color = LazyEngColors.TextPrimary,
                    )
                }
                item.levelLabel?.let {
                    StatusChip(it, StatusTone.LEARNING)
                }
            }

            // Cover artwork with fallback
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(LazyEngShapes.card)
                    .background(LazyEngColors.BlueSoft)
                    .testTag("continue-watching-artwork"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "▶ 继续播放",
                        style = MaterialTheme.typography.titleLarge,
                        color = LazyEngColors.BlueDeep,
                    )
                    Text(
                        "上次播到 ${formatDuration(item.positionMs)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LazyEngColors.BlueDeep,
                    )
                }
            }

            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "已看 ${item.progressPercent}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = LazyEngColors.TextSecondary,
                    )
                    Text(
                        "剩余 ${formatDuration(item.remainingMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        color = LazyEngColors.TextSecondary,
                    )
                }
                LinearProgressIndicator(
                    progress = { item.completion },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(LazyEngShapes.small)
                        .testTag("continue-watching-progress"),
                    color = LazyEngColors.PrimaryMint,
                    trackColor = LazyEngColors.SurfaceSoft,
                )
            }

            PrimaryButton(
                label = "继续播放",
                onClick = onPlay,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("continue-play-button"),
            )
        }
    }
}

@Composable
fun FirstVideoGuidanceCard(
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("first-video-guide-card"),
        shape = LazyEngShapes.largeCard,
        colors = CardDefaults.cardColors(containerColor = LazyEngColors.MintSoft),
        elevation = CardDefaults.cardElevation(defaultElevation = LazyEngElevation.raised),
    ) {
        Column(
            modifier = Modifier.padding(LazyEngSpacing.large),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            Text(
                "开启第一部视频",
                style = MaterialTheme.typography.titleLarge,
                color = LazyEngColors.PrimaryDeep,
            )
            Text(
                "挑一部喜欢的视频，开启今天的英语探索吧！遇到生词可以随时点击查词或添加到生词本。",
                style = MaterialTheme.typography.bodyMedium,
                color = LazyEngColors.TextPrimary,
            )
            PrimaryButton(
                label = "去挑选视频",
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("start-first-video-button"),
            )
        }
    }
}

@Composable
fun VocabularyReviewCard(
    summary: VocabularySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("vocabulary-card"),
        shape = LazyEngShapes.card,
        border = BorderStroke(LazyEngStroke.thin, LazyEngColors.Border),
        color = LazyEngColors.Surface,
        shadowElevation = LazyEngElevation.raised,
    ) {
        Row(
            modifier = Modifier.padding(LazyEngSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(LazyEngColors.MintSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "词",
                    style = MaterialTheme.typography.titleMedium,
                    color = LazyEngColors.PrimaryDeep,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
            ) {
                Text(
                    summary.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = LazyEngColors.TextPrimary,
                )
                Text(
                    if (summary.pendingCount > 0) "有 ${summary.pendingCount} 个生词等你复习" else "暂无待复习生词 · 词库整装待发",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LazyEngColors.TextSecondary,
                )
            }
            StatusChip(
                label = "去复习",
                tone = StatusTone.LEARNING,
                modifier = Modifier.testTag("vocabulary-review-chip"),
            )
        }
    }
}

@Composable
fun RecommendedVideoCard(
    item: HomeVideoCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .testTag("recommendation-card-${item.videoId.value}"),
        shape = LazyEngShapes.card,
        colors = CardDefaults.cardColors(containerColor = LazyEngColors.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = LazyEngElevation.raised),
    ) {
        Row(
            modifier = Modifier.padding(LazyEngSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
        ) {
            // Thumbnail / Fallback Box
            Box(
                modifier = Modifier
                    .size(width = 88.dp, height = 64.dp)
                    .clip(LazyEngShapes.small)
                    .background(LazyEngColors.BlueSoft),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "视频",
                        style = MaterialTheme.typography.labelLarge,
                        color = LazyEngColors.BlueDeep,
                    )
                    Text(
                        item.durationLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = LazyEngColors.BlueDeep,
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = LazyEngColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    item.levelLabel?.let { StatusChip(it, StatusTone.LEARNING) }
                    item.subtitleWordCount?.let { StatusChip("$it 词", StatusTone.NEUTRAL) }
                }
            }
        }
    }
}
