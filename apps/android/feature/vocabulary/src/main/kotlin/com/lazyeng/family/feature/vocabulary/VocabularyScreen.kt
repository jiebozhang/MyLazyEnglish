package com.lazyeng.family.feature.vocabulary

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.core.model.VocabularyStatus
import com.lazyeng.family.feature.player.PronunciationAccent
import com.lazyeng.family.feature.player.PronunciationPlayer
import com.lazyeng.family.feature.player.PronunciationState
import com.lazyeng.family.feature.player.SystemPronunciationPlayer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun VocabularyRoute(
    model: VocabularyViewModel,
    listState: LazyListState,
    onEffect: (VocabularyUiEffect) -> Unit,
    onFiltersChanged: (VocabularyStatus?, String?) -> Unit = { _, _ -> },
    onStartReview: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val speech = remember { SystemPronunciationPlayer(context) }
    val speechState by speech.state.collectAsStateWithLifecycle()
    val latestEffect by rememberUpdatedState(onEffect)
    LaunchedEffect(model) { model.effectFlow.collect { latestEffect(it) } }
    DisposableEffect(owner, speech) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) speech.stop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            speech.stop()
            speech.release()
        }
    }
    VocabularyScreen(
        state = state,
        listState = listState,
        pronunciationPlayer = speech,
        pronunciationState = speechState,
        onEvent = { event ->
            model.onEvent(event)
            val updated = model.state.value
            when (event) {
                is VocabularyUiEvent.SelectStatus -> onFiltersChanged(updated.statusFilter, updated.sourceFilter)
                is VocabularyUiEvent.SelectSource -> onFiltersChanged(updated.statusFilter, updated.sourceFilter)
                VocabularyUiEvent.ClearFilters -> onFiltersChanged(null, null)
                else -> Unit
            }
        },
        onStartReview = onStartReview,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabularyScreen(
    state: VocabularyUiState,
    listState: LazyListState,
    onEvent: (VocabularyUiEvent) -> Unit,
    pronunciationPlayer: PronunciationPlayer? = null,
    pronunciationState: PronunciationState = PronunciationState.UNAVAILABLE,
    onStartReview: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    val scroll = rememberScrollState()
    var sourceMenu by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); onEvent(VocabularyUiEvent.ClearMessage) }
    }
    Surface(modifier.fillMaxSize(), color = LazyEngColors.Background) {
        Scaffold(containerColor = LazyEngColors.Background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = LazyEngSpacing.large, vertical = LazyEngSpacing.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记住真实表达", style = MaterialTheme.typography.labelLarge, color = LazyEngColors.PrimaryDeep)
                        Text("我的生词本", style = MaterialTheme.typography.headlineSmall, color = LazyEngColors.TextPrimary)
                    }
                    Text("${state.rows.size} 词", style = MaterialTheme.typography.labelLarge, color = LazyEngColors.PrimaryDeep)
                    IconButton(onClick = { onEvent(VocabularyUiEvent.Refresh) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("vocabulary-refresh")) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新生词本")
                    }
                }
                if (!state.loading) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = LazyEngSpacing.medium).testTag("vocabulary-review-entry"),
                        colors = CardDefaults.cardColors(containerColor = LazyEngColors.SubtitleHighlight),
                        shape = LazyEngShapes.card,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("今日待复习 ${state.todayDueCount} 词", style = MaterialTheme.typography.titleMedium, color = LazyEngColors.TextPrimary)
                                Text("按自己的节奏，慢慢记牢", style = MaterialTheme.typography.bodySmall, color = LazyEngColors.TextSecondary)
                            }
                            PrimaryButton(
                                "开始复习",
                                onStartReview,
                                Modifier.heightIn(min = 48.dp).testTag("start-vocabulary-review"),
                            )
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = LazyEngSpacing.medium)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    VocabularyStatus.entries.forEach { status ->
                        val selected = state.statusFilter == status
                        FilterChip(
                            selected = selected,
                            onClick = { onEvent(VocabularyUiEvent.SelectStatus(if (selected) null else status)) },
                            label = { Text(status.label()) },
                            modifier = Modifier.heightIn(min = 48.dp).testTag("vocabulary-status-${status.name}"),
                        )
                    }
                    Box {
                        TextButton(onClick = { sourceMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("vocabulary-source-filter")) {
                            Text(state.sourceFilter?.let { "来源 ${it.take(8)}" } ?: "全部来源")
                        }
                        DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                            DropdownMenuItem(text = { Text("全部来源") }, onClick = {
                                sourceMenu = false; onEvent(VocabularyUiEvent.SelectSource(null))
                            })
                            state.sources.forEach { source -> DropdownMenuItem(text = { Text(source) }, onClick = {
                                sourceMenu = false; onEvent(VocabularyUiEvent.SelectSource(source))
                            }) }
                        }
                    }
                }
                if (state.statusFilter != null || state.sourceFilter != null) {
                    TextButton(onClick = { onEvent(VocabularyUiEvent.ClearFilters) }, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)) {
                        Text("清除筛选")
                    }
                }
                when {
                    state.loading -> Column(Modifier.fillMaxSize().padding(LazyEngSpacing.large), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium)) {
                        LoadingSkeleton(); LoadingSkeleton(); LoadingSkeleton()
                    }
                    state.message != null && state.rows.isEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(scroll), contentAlignment = Alignment.Center) {
                        ErrorState("无法加载生词本", state.message, retryLabel = "重试", onRetry = { onEvent(VocabularyUiEvent.Refresh) })
                    }
                    state.rows.isEmpty() -> Box(Modifier.fillMaxSize().verticalScroll(scroll).testTag("vocabulary-empty"), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = LazyEngColors.PrimaryMint, modifier = Modifier.size(48.dp))
                            EmptyState("这里暂时没有单词", "在看视频时点按字幕单词，就能收藏到这里。")
                        }
                    }
                    state.visibleRows.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState("没有符合条件的生词", "试试其他状态或来源，或者清除筛选。", actionLabel = "清除筛选", onAction = { onEvent(VocabularyUiEvent.ClearFilters) })
                    }
                    else -> PullToRefreshBox(
                        isRefreshing = state.loading,
                        onRefresh = { onEvent(VocabularyUiEvent.Refresh) },
                        modifier = Modifier.fillMaxSize().testTag("vocabulary-list-refresh"),
                    ) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize().testTag("vocabulary-list"),
                            contentPadding = PaddingValues(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small),
                            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
                        ) {
                            items(state.visibleRows, key = { it.item.id }) { row ->
                                VocabularyCard(
                                    row = row,
                                    pronunciationPlayer = pronunciationPlayer,
                                    pronunciationState = pronunciationState,
                                    onWatch = { onEvent(VocabularyUiEvent.WatchContext(row.item.id)) },
                                    onDelete = { onEvent(VocabularyUiEvent.RequestDelete(row.item.id)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    state.pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { onEvent(VocabularyUiEvent.CancelDelete) },
            title = { Text("移除这个生词？") },
            text = { Text("它会从当前孩子的生词本中移除。") },
            confirmButton = { TextButton(onClick = { onEvent(VocabularyUiEvent.ConfirmDelete) }, modifier = Modifier.testTag("confirm-vocabulary-delete")) { Text("移除") } },
            dismissButton = { TextButton(onClick = { onEvent(VocabularyUiEvent.CancelDelete) }) { Text("取消") } },
        )
    }
}

@Composable
fun VocabularyCard(
    row: VocabularyRow,
    pronunciationPlayer: PronunciationPlayer?,
    pronunciationState: PronunciationState,
    onWatch: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = row.entry
    val context = row.context
    Column(
        modifier.fillMaxWidth().clip(LazyEngShapes.card).background(LazyEngColors.Surface)
            .padding(LazyEngSpacing.medium).testTag("vocabulary-card-${row.item.id}"),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(context?.surface ?: row.item.lemma, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    color = LazyEngColors.TextPrimary, modifier = Modifier.testTag("vocabulary-word-${row.item.id}"))
                Text(
                    listOfNotNull(context?.phonetic ?: entry?.phonetic, entry?.partOfSpeech?.takeIf { it.isNotBlank() }, row.item.status.label()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = LazyEngColors.TextSecondary,
                )
            }
            IconButton(
                onClick = { pronunciationPlayer?.speak(context?.surface ?: row.item.lemma, PronunciationAccent.AMERICAN) },
                enabled = pronunciationPlayer != null && pronunciationState != PronunciationState.INITIALIZING,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("vocabulary-pronounce-${row.item.id}")
                    .semantics { contentDescription = if (pronunciationState == PronunciationState.SPEAKING) "正在播放发音" else "播放英文发音" },
            ) { Icon(painterResource(com.lazyeng.family.feature.player.R.drawable.ic_volume_sound), contentDescription = null) }
            IconButton(onClick = onDelete, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("vocabulary-delete-${row.item.id}")) {
                Icon(Icons.Default.Close, contentDescription = "移除 ${row.item.lemma}")
            }
        }
        entry?.senses?.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextPrimary) }
        context?.let { saved ->
            Text(
                highlightSurface(saved.contextSentence, saved.surface.ifBlank { row.item.lemma }),
                style = MaterialTheme.typography.bodyLarge,
                color = LazyEngColors.TextPrimary,
                modifier = Modifier.fillMaxWidth().clip(LazyEngShapes.card).background(LazyEngColors.SurfaceSoft).padding(LazyEngSpacing.medium)
                    .testTag("vocabulary-context-${row.item.id}"),
            )
            val date = (saved.createdAt ?: Instant.EPOCH).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy/M/d", Locale.ROOT))
            Text("收藏于 $date${saved.videoId?.let { " · 视频 ${it.value.take(8)}" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = LazyEngColors.TextSecondary)
            OutlinedButton(
                onClick = onWatch,
                enabled = saved.videoId != null,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("vocabulary-watch-${row.item.id}"),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(LazyEngSpacing.small))
                Text("观看原句 · ${formatTimestamp(saved.timestampMs)}")
            }
        } ?: Text("暂无原句记录", style = MaterialTheme.typography.bodySmall, color = LazyEngColors.TextSecondary)
    }
}

@Composable
private fun highlightSurface(sentence: String, surface: String) = buildAnnotatedString {
    val range = sentence.indexOf(surface, ignoreCase = true)
    if (range < 0 || surface.isBlank()) append(sentence) else {
        append(sentence.substring(0, range))
        withStyle(SpanStyle(background = LazyEngColors.SubtitleHighlight, fontWeight = FontWeight.Bold)) {
            append(sentence.substring(range, range + surface.length))
        }
        append(sentence.substring(range + surface.length))
    }
}

private fun VocabularyStatus.label(): String = when (this) {
    VocabularyStatus.LEARNING -> "学习中"
    VocabularyStatus.REVIEW -> "待复习"
    VocabularyStatus.MASTERED -> "已掌握"
    VocabularyStatus.PAUSED -> "暂停"
}

private fun formatTimestamp(timestampMs: Long): String {
    val seconds = timestampMs.coerceAtLeast(0) / 1000
    return "%02d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
