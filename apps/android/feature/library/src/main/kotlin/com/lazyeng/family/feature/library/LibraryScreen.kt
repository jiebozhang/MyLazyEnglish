package com.lazyeng.family.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.EmptyState
import com.lazyeng.family.core.designsystem.ErrorState
import com.lazyeng.family.core.designsystem.LazyEngSpacing
import com.lazyeng.family.core.designsystem.LazyEngTouchTarget
import com.lazyeng.family.core.designsystem.LoadingSkeleton
import com.lazyeng.family.core.designsystem.PinDots
import com.lazyeng.family.core.designsystem.PinKeypad
import com.lazyeng.family.core.designsystem.PrimaryButton
import com.lazyeng.family.core.designsystem.SectionHeader
import com.lazyeng.family.core.designsystem.VideoCard
import com.lazyeng.family.core.model.Video
import com.lazyeng.family.core.model.VideoId
import com.lazyeng.family.core.model.VideoStatus

@Composable
fun LibraryRoute(model: LibraryViewModel, initialFilters: LibraryFilters, onFiltersChanged: (LibraryFilters) -> Unit,
    listState: LazyListState, onVideo: (VideoId, Boolean) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val callback by rememberUpdatedState(onVideo)
    LaunchedEffect(model) {
        model.onEvent(LibraryUiEvent.RestoreFilters(initialFilters))
        model.onEvent(LibraryUiEvent.Refresh)
        model.effects.collect { effect ->
            when (effect) {
                is LibraryUiEffect.OpenVideo -> callback(effect.videoId, effect.parentView)
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) model.onBackground()
            if (event == Lifecycle.Event.ON_START) model.onEvent(LibraryUiEvent.Refresh)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.onBackground() }
    }
    LibraryScreen(state, { event ->
        model.onEvent(event)
        val next = when (event) {
            is LibraryUiEvent.RestoreFilters -> event.filters
            is LibraryUiEvent.QueryChanged -> state.filters.copy(query = event.value)
            is LibraryUiEvent.LevelSelected -> state.filters.copy(level = event.value)
            is LibraryUiEvent.TopicSelected -> state.filters.copy(topic = event.value)
            is LibraryUiEvent.SourceSelected -> state.filters.copy(source = event.value)
            is LibraryUiEvent.SubtitleSelected -> state.filters.copy(subtitle = event.value)
            LibraryUiEvent.ClearFilters -> LibraryFilters()
            else -> null
        }
        next?.let(onFiltersChanged)
    }, listState)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun LibraryScreen(state: LibraryUiState, onEvent: (LibraryUiEvent) -> Unit, listState: LazyListState,
    modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(horizontal = LazyEngSpacing.large)) {
        SectionHeader("视频库", Modifier.padding(top = LazyEngSpacing.medium))
        OutlinedTextField(
            value = state.filters.query,
            onValueChange = { onEvent(LibraryUiEvent.QueryChanged(it)) },
            modifier = Modifier.fillMaxWidth().testTag("library-search"),
            label = { Text("搜索视频") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "搜索") },
            singleLine = true,
            minLines = 1,
        )
        if (state.isChild) {
            TextButton(
                onClick = { onEvent(if (state.overrideActive) LibraryUiEvent.EndOverride else LibraryUiEvent.RequestOverride) },
                modifier = Modifier.fillMaxWidth().testTag("parent-content-override"),
                enabled = !state.loading,
            ) { Text(if (state.overrideActive) "退出家长查看模式" else "家长 PIN：查看全部内容") }
        }
        if (state.videos.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.weight(1f),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
            ) {
                item(key = "filters") {
                    Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
                        FilterGroup("等级", listOf("全部") + state.levels,
                            selected = state.filters.level.ifBlank { "全部" }, testTag = "filter-level") {
                            onEvent(LibraryUiEvent.LevelSelected(if (it == "全部") "" else it))
                        }
                        FilterGroup("主题", listOf("全部") + state.topics,
                            selected = state.filters.topic.ifBlank { "全部" }, testTag = "filter-topic") {
                            onEvent(LibraryUiEvent.TopicSelected(if (it == "全部") "" else it))
                        }
                        FilterGroup("来源", listOf("全部") + state.sources.map(::sourceLabel),
                            selected = state.filters.source.takeIf(String::isNotBlank)?.let(::sourceLabel) ?: "全部",
                            testTag = "filter-source") { label ->
                            onEvent(LibraryUiEvent.SourceSelected(state.sources.firstOrNull { sourceLabel(it) == label }.orEmpty()))
                        }
                        FilterGroup("字幕", listOf("全部", "有英文字幕", "无字幕", "状态未知"),
                            selected = state.filters.subtitle.label(), testTag = "filter-subtitle") { label ->
                            onEvent(LibraryUiEvent.SubtitleSelected(LibrarySubtitleFilter.entries.first { it.label() == label }))
                        }
                    }
                }
                when {
                    state.loading -> item { LoadingSkeleton(Modifier.fillMaxWidth()) }
                    state.message != null -> item { ErrorState("暂时无法加载", state.message, retryLabel = "重试", onRetry = { onEvent(LibraryUiEvent.Refresh) }) }
                    state.visibleVideos.isEmpty() -> item {
                        EmptyState(
                            if (state.filters.active) "没有符合条件的视频" else "还没有视频",
                            if (state.filters.active) "试试调整搜索词或清除筛选条件。" else "请让家长从导入入口添加本地视频。",
                            actionLabel = if (state.filters.active) "清除搜索与筛选" else null,
                            onAction = if (state.filters.active) ({ onEvent(LibraryUiEvent.ClearFilters) }) else null,
                            modifier = Modifier.testTag("library-empty"),
                        )
                    }
                    else -> items(state.visibleVideos, key = { it.id.value }) { video ->
                        VideoCard(
                            title = video.metadata.title,
                            detail = listOfNotNull(video.metadata.cefrLevel, video.metadata.topicTags.takeIf { it.isNotEmpty() }?.joinToString(" · "))
                                .joinToString(" · ").ifBlank { "本地学习视频" },
                            duration = formatLibraryDuration(video.metadata.durationMs),
                            status = video.status.childLabel(),
                            onClick = { onEvent(LibraryUiEvent.VideoClicked(video.id)) },
                            modifier = Modifier.testTag("library-video-${video.id.value}"),
                        )
                    }
                }
            }
        } else when {
            state.loading -> LoadingSkeleton(Modifier.fillMaxWidth().padding(top = LazyEngSpacing.medium))
            state.message != null -> ErrorState("暂时无法加载", state.message, retryLabel = "重试", onRetry = { onEvent(LibraryUiEvent.Refresh) })
            state.filters.active -> EmptyState("没有符合条件的视频", "清除搜索词与筛选条件后再试。",
                Modifier.weight(1f).testTag("library-empty"), actionLabel = "清除搜索与筛选",
                onAction = { onEvent(LibraryUiEvent.ClearFilters) })
            else -> EmptyState("还没有视频", "请让家长从导入入口添加本地视频。", Modifier.weight(1f), actionLabel = null)
        }
    }

    if (state.pinGateOpen) {
        AlertDialog(
            onDismissRequest = { onEvent(LibraryUiEvent.CancelPin) },
            title = { Text("家长验证") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                    Text("输入家长 PIN 后，临时显示适龄范围外的内容。此操作会留下审计记录。")
                    PinDots(state.enteredCount)
                    if (state.remainingSeconds > 0) Text("请等待 ${state.remainingSeconds} 秒后再试", color = MaterialTheme.colorScheme.error)
                    PinKeypad(state.enteredCount, !state.loading && state.remainingSeconds == 0L,
                        if (state.loading) "验证中…" else "验证 PIN",
                        { onEvent(LibraryUiEvent.Digit(it)) }, { onEvent(LibraryUiEvent.Erase) }, { onEvent(LibraryUiEvent.SubmitPin) })
                    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { onEvent(LibraryUiEvent.CancelPin) }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, values: List<String>, selected: String, testTag: String,
    onSelect: (String) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
            values.forEach { value ->
                FilterChip(selected == value, { onSelect(value) }, { Text(value) },
                    modifier = Modifier.heightIn(min = LazyEngTouchTarget.minimum).testTag("$testTag-$value"))
            }
        }
    }
}

private fun LibrarySubtitleFilter.label() = when (this) {
    LibrarySubtitleFilter.ALL -> "全部"
    LibrarySubtitleFilter.AVAILABLE -> "有英文字幕"
    LibrarySubtitleFilter.MISSING -> "无字幕"
    LibrarySubtitleFilter.UNKNOWN -> "状态未知"
}

private fun sourceLabel(value: String) = when (value) {
    "LOCAL_FILE" -> "本地文件"
    "PLATFORM_REFERENCE" -> "平台来源"
    else -> "其他来源"
}

private fun VideoStatus.childLabel() = when (this) {
    VideoStatus.DRAFT -> "尚未准备好"
    VideoStatus.PROCESSING -> "正在准备"
    VideoStatus.NEEDS_SUBTITLE -> "等待英文字幕"
    VideoStatus.FAILED -> "暂时无法观看"
    VideoStatus.READY -> "可以观看"
    VideoStatus.ARCHIVED -> "已归档"
}

private fun formatLibraryDuration(durationMs: Long?): String = durationMs?.let {
    val seconds = it.coerceAtLeast(0) / 1_000
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
} ?: "--:--"
