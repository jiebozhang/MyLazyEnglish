package com.lazyeng.family.feature.player

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.lazyeng.family.core.designsystem.LazyEngColors
import com.lazyeng.family.core.designsystem.LazyEngCornerSize
import com.lazyeng.family.core.designsystem.LazyEngMotion
import com.lazyeng.family.core.designsystem.LazyEngSpacing
import com.lazyeng.family.core.designsystem.LazyEngTypeScale
import com.lazyeng.family.core.common.subtitle.SubtitleTokenKind
import com.lazyeng.family.core.common.subtitle.SubtitleTokenizer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import java.util.Locale
import android.content.res.Configuration

@Composable
fun PlayerRoute(viewModel: PlayerViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val activity = LocalActivity.current
    val context = LocalContext.current
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val pronunciationPlayer = remember(state.selectedWord != null) {
        if (state.selectedWord != null) SystemPronunciationPlayer(context) else null
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { if (it == PlayerUiEffect.NavigateBack) onBack() }
    }
    DisposableEffect(owner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                viewModel.onEvent(PlayerUiEvent.LifecycleStopped)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(owner, pronunciationPlayer) {
        val observer = pronunciationPlayer?.let { speechPlayer ->
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) speechPlayer.stop()
            }.also(owner.lifecycle::addObserver)
        }
        onDispose {
            observer?.let(owner.lifecycle::removeObserver)
            pronunciationPlayer?.stop()
            pronunciationPlayer?.release()
        }
    }
    DisposableEffect(activity, state.isPlaying) {
        activity?.window?.let { window ->
            if (state.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    DisposableEffect(activity, state.fullscreen, configuration.orientation) {
        val window = activity?.window
        val immersive = state.fullscreen || configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars()) }
    }
    BackHandler { viewModel.onEvent(PlayerUiEvent.Exit) }
    PlayerScreen(
        state = state,
        player = viewModel.mediaPlayer,
        positionMs = viewModel.positionMs,
        subtitles = viewModel.subtitles,
        activeSubtitleIndex = viewModel.activeSubtitleIndex,
        onEvent = viewModel::onEvent,
        pronunciationPlayer = pronunciationPlayer,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
fun PlayerScreen(
    state: PlayerUiState,
    player: androidx.media3.common.Player?,
    positionMs: StateFlow<Long>,
    subtitles: StateFlow<List<PlayerSubtitleCue>>,
    activeSubtitleIndex: StateFlow<Int?>,
    onEvent: (PlayerUiEvent) -> Unit,
    pronunciationPlayer: PronunciationPlayer? = null,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).testTag("player-screen")) {
        val landscape = maxWidth > maxHeight
        val subtitleCues by subtitles.collectAsStateWithLifecycle()
        val hasChineseSubtitles = subtitleCues.any { !it.chineseText.isNullOrBlank() }
        val layoutMode = playerLayoutMode(maxWidth.value, maxHeight.value)
        val compactLandscape = layoutMode == PlayerLayoutMode.COMPACT_LANDSCAPE
        val layoutTag = layoutMode.testTag
        val transcriptState = rememberLazyListState()
        var controlsVisible by remember { mutableStateOf(true) }
        var transcriptVisible by remember { mutableStateOf(false) }
        LaunchedEffect(state.isPlaying) {
            if (!state.isPlaying) controlsVisible = true
        }
        LaunchedEffect(controlsVisible, state.isPlaying, state.phase, state.selectedWord) {
            if (controlsVisible && state.isPlaying && state.phase == PlayerPhase.READY && state.selectedWord == null) {
                delay(5_000L)
                controlsVisible = false
            }
        }
        Box(
            Modifier.fillMaxSize().testTag(layoutTag).pointerInput(layoutMode) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                    controlsVisible = true
                }
            },
        ) {
        if (compactLandscape) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                PlayerVideoSurface(
                    state, player, subtitles, activeSubtitleIndex, onEvent,
                    Modifier.fillMaxSize(),
                    showSubtitleOverlay = landscape && !controlsVisible,
                )
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                    enter = fadeIn(tween(LazyEngMotion.normalMillis)),
                    exit = fadeOut(tween(LazyEngMotion.normalMillis)),
                ) {
                    PlayerHeader(state.title, state.fullscreen, onEvent, Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.72f)))
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                    enter = fadeIn(tween(LazyEngMotion.normalMillis)),
                    exit = fadeOut(tween(LazyEngMotion.normalMillis)),
                ) {
                    PlayerControls(state, positionMs, onEvent, Modifier.background(Color.Black.copy(alpha = 0.78f)), hasChineseSubtitles)
                }
                IconButton(
                    onClick = { transcriptVisible = !transcriptVisible; controlsVisible = true },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(LazyEngSpacing.small)
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = if (transcriptVisible) "关闭字幕列表" else "显示字幕列表"
                            stateDescription = if (transcriptVisible) "已展开" else "已收起"
                        }.testTag("player-transcript-drawer-toggle"),
                ) {
                    Text(if (transcriptVisible) "›" else "字幕", color = Color.White)
                }
                AnimatedVisibility(
                    visible = transcriptVisible,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(min = 280.dp, max = 420.dp)
                        .fillMaxWidth(0.48f).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
                    enter = fadeIn(tween(LazyEngMotion.normalMillis)),
                    exit = fadeOut(tween(LazyEngMotion.normalMillis)),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        IconButton(onClick = { transcriptVisible = false }, modifier = Modifier.align(Alignment.End)
                            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "关闭字幕列表" }.testTag("player-transcript-drawer-close")) {
                            Text("关闭")
                        }
                        PlayerTranscript(
                            subtitles, activeSubtitleIndex, state.subtitleMode,
                            state.selectedWord, state.savedLemmas, onEvent, Modifier.weight(1f).fillMaxWidth(), transcriptState,
                        )
                    }
                }
            }
        } else if (landscape || layoutMode == PlayerLayoutMode.EXPANDED) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.62f).fillMaxHeight()) {
                    PlayerHeader(state.title, state.fullscreen, onEvent, Modifier.fillMaxWidth())
                    PlayerVideoSurface(state, player, subtitles, activeSubtitleIndex, onEvent, Modifier.weight(1f).fillMaxWidth(),
                        showSubtitleOverlay = landscape && state.fullscreen)
                    AnimatedVisibility(visible = controlsVisible, enter = fadeIn(tween(LazyEngMotion.normalMillis)),
                        exit = fadeOut(tween(LazyEngMotion.normalMillis))) {
                        PlayerControls(state, positionMs, onEvent, hasChineseSubtitles = hasChineseSubtitles)
                    }
                }
                PlayerTranscript(
                    subtitles, activeSubtitleIndex, state.subtitleMode, state.selectedWord, state.savedLemmas, onEvent,
                    Modifier.weight(0.38f).fillMaxHeight().background(MaterialTheme.colorScheme.surface), transcriptState,
                )
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                PlayerHeader(state.title, state.fullscreen, onEvent, Modifier.fillMaxWidth())
                PlayerVideoSurface(
                    state, player, subtitles, activeSubtitleIndex, onEvent,
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                    showSubtitleOverlay = landscape && state.fullscreen,
                )
                PlayerTranscript(
                    subtitles, activeSubtitleIndex, state.subtitleMode, state.selectedWord, state.savedLemmas, onEvent,
                    Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surface), transcriptState,
                )
                AnimatedVisibility(visible = controlsVisible, enter = fadeIn(tween(LazyEngMotion.normalMillis)),
                    exit = fadeOut(tween(LazyEngMotion.normalMillis))) {
                    PlayerControls(state, positionMs, onEvent, hasChineseSubtitles = hasChineseSubtitles)
                }
            }
        }
        }
        state.selectedWord?.let { selection ->
            ModalBottomSheet(
                onDismissRequest = { onEvent(PlayerUiEvent.CloseLookup) },
                shape = com.lazyeng.family.core.designsystem.LazyEngShapes.bottomSheet,
            ) {
                DisposableEffect(pronunciationPlayer) {
                    onDispose { pronunciationPlayer?.stop() }
                }
                ContextualDictionaryBottomSheet(state, selection, onEvent, pronunciationPlayer)
            }
        }
    }
}

@Composable
private fun PlayerHeader(title: String, fullscreen: Boolean, onEvent: (PlayerUiEvent) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color.Black).padding(horizontal = LazyEngSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = { onEvent(PlayerUiEvent.Exit) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = "返回视频详情" }.testTag("player-back")) {
            Text("返回", color = Color.White)
        }
        Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = LazyEngSpacing.small))
        TextButton(onClick = { onEvent(PlayerUiEvent.ToggleFullscreen) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { contentDescription = if (fullscreen) "退出全屏" else "进入全屏" }.testTag("player-fullscreen")) {
            Text(if (fullscreen) "退出全屏" else "全屏", color = Color.White)
        }
    }
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun PlayerVideoSurface(
    state: PlayerUiState,
    player: androidx.media3.common.Player?,
    subtitles: StateFlow<List<PlayerSubtitleCue>>,
    activeSubtitleIndex: StateFlow<Int?>,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier,
    showSubtitleOverlay: Boolean,
) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (player != null) AndroidView(
            factory = { context -> PlayerView(context).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
                contentDescription = "${state.title} 视频画面"
            } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize().testTag("player-surface"),
        )
        if (state.phase == PlayerPhase.BUFFERING || state.phase == PlayerPhase.LOADING) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.testTag("player-buffering"))
        }
        if (state.phase == PlayerPhase.ERROR) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                Text(state.message ?: "视频文件无法打开。", color = Color.White, style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("player-error"))
                Button(onClick = { onEvent(PlayerUiEvent.Retry) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
                TextButton(onClick = { onEvent(PlayerUiEvent.Exit) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("返回详情", color = Color.White)
                }
            }
        }
        if (showSubtitleOverlay) {
            PlayerSubtitleOverlay(subtitles, activeSubtitleIndex, state.subtitleMode, state.selectedWord, state.savedLemmas, onEvent)
        }
    }
}

@Composable
private fun BoxScope.PlayerSubtitleOverlay(
    subtitles: StateFlow<List<PlayerSubtitleCue>>,
    activeSubtitleIndex: StateFlow<Int?>,
    mode: SubtitleDisplayMode,
    selectedWord: PlayerWordSelection?,
    savedLemmas: Set<String>,
    onEvent: (PlayerUiEvent) -> Unit,
) {
    val cues by subtitles.collectAsStateWithLifecycle()
    val activeIndex by activeSubtitleIndex.collectAsStateWithLifecycle()
    val cue = activeIndex?.let(cues::getOrNull)
    AnimatedVisibility(
        visible = cue != null && mode != SubtitleDisplayMode.HIDDEN,
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(LazyEngSpacing.medium),
        enter = fadeIn(tween(LazyEngMotion.normalMillis)),
        exit = fadeOut(tween(LazyEngMotion.normalMillis)),
    ) {
        cue?.let { current ->
            Column(
                Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.2f)
                    .verticalScroll(rememberScrollState()).clip(RoundedCornerShape(LazyEngCornerSize.card))
                    .background(Color.Black.copy(alpha = 0.72f)).padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = buildString {
                            append("第 ${current.sequence} 句。 ")
                            if (mode != SubtitleDisplayMode.CHINESE_ONLY) append(current.englishText)
                            if (mode != SubtitleDisplayMode.ENGLISH_ONLY && !current.chineseText.isNullOrBlank()) {
                                append("。${current.chineseText}")
                            }
                        }
                    }.testTag("player-subtitle-overlay"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (mode != SubtitleDisplayMode.CHINESE_ONLY) {
                    ClickableSubtitleText(current, current.englishText, "overlay", Color.White, onEvent,
                        Modifier.fillMaxWidth(), LazyEngTypeScale.subtitle, selectedWord, savedLemmas)
                }
                if (mode != SubtitleDisplayMode.ENGLISH_ONLY && !current.chineseText.isNullOrBlank()) {
                    Text(current.chineseText, color = Color.White.copy(alpha = 0.9f),
                        fontSize = LazyEngTypeScale.subtitleTranslation, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

@Composable
private fun PlayerTranscript(
    subtitles: StateFlow<List<PlayerSubtitleCue>>,
    activeSubtitleIndex: StateFlow<Int?>,
    mode: SubtitleDisplayMode,
    selectedWord: PlayerWordSelection?,
    savedLemmas: Set<String>,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val cues by subtitles.collectAsStateWithLifecycle()
    val activeIndex by activeSubtitleIndex.collectAsStateWithLifecycle()
    var manualScrollPaused by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        var resumeJob: Job? = null
        var activeDrag: DragInteraction.Start? = null
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    activeDrag = interaction
                    resumeJob?.cancel()
                    manualScrollPaused = true
                }
                is DragInteraction.Stop -> if (activeDrag == interaction.start) {
                    activeDrag = null
                    resumeJob?.cancel()
                    resumeJob = launch { delay(AUTO_SCROLL_PAUSE_MS); manualScrollPaused = false }
                }
                is DragInteraction.Cancel -> if (activeDrag == interaction.start) {
                    activeDrag = null
                    resumeJob?.cancel()
                    resumeJob = launch { delay(AUTO_SCROLL_PAUSE_MS); manualScrollPaused = false }
                }
            }
        }
    }
    LaunchedEffect(activeIndex, manualScrollPaused, cues.size) {
        val index = activeIndex
        if (!manualScrollPaused && index != null && index in cues.indices) {
            val centerOffset = -(listState.layoutInfo.viewportSize.height / 3)
            listState.animateScrollToItem(index, centerOffset)
        }
    }

    val hasTranslations = cues.any { !it.chineseText.isNullOrBlank() }
    Column(modifier.padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small)) {
        Text("字幕剧本", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        if (mode == SubtitleDisplayMode.BILINGUAL && !hasTranslations) {
            Text("当前没有中文字幕，仅显示原文。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("subtitle-translation-unavailable"))
        }
        when {
            cues.isEmpty() -> Text("暂无字幕", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("subtitle-empty"))
            mode == SubtitleDisplayMode.HIDDEN -> Text("字幕已关闭", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("subtitle-hidden"))
            else -> LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
                contentPadding = PaddingValues(vertical = LazyEngSpacing.small),
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("subtitle-transcript"),
            ) {
                itemsIndexed(cues, key = { _, cue -> cue.id.value }) { index, cue ->
                    val active = index == activeIndex
                    SubtitleCueRow(cue, active, mode, selectedWord, savedLemmas, onEvent)
                }
            }
        }
    }
}

@Composable
private fun SubtitleCueRow(
    cue: PlayerSubtitleCue,
    active: Boolean,
    mode: SubtitleDisplayMode,
    selectedWord: PlayerWordSelection?,
    savedLemmas: Set<String>,
    onEvent: (PlayerUiEvent) -> Unit,
) {
    Surface(
        onClick = { onEvent(PlayerUiEvent.Seek(cue.startMs)) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("subtitle-line-${cue.sequence}"),
        shape = RoundedCornerShape(12.dp),
        color = if (active) LazyEngColors.SubtitleHighlight else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small).semantics {
            selected = active
            stateDescription = if (active) "当前播放句" else "第 ${cue.sequence} 句，点击跳转播放"
        }) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (active) Text("正在播放", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("subtitle-current-indicator"))
                Spacer(Modifier.width(LazyEngSpacing.small))
                Text(formatTime(cue.startMs), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("subtitle-timestamp-${cue.sequence}"))
                if (active) {
                    Spacer(Modifier.weight(1f))
                    IconButton(
                        onClick = { onEvent(PlayerUiEvent.ToggleSentenceLoop) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                            .semantics { contentDescription = "复读当前句" }
                            .testTag("subtitle-repeat-${cue.sequence}"),
                    ) { Text("↻", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
                }
            }
            if (mode != SubtitleDisplayMode.CHINESE_ONLY) {
                ClickableSubtitleText(cue, cue.englishText, "line-${cue.sequence}",
                    if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    onEvent, Modifier.fillMaxWidth(), LazyEngTypeScale.subtitle, selectedWord, savedLemmas)
            } else {
                TextButton(onClick = { onEvent(PlayerUiEvent.Seek(cue.startMs)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("subtitle-seek-${cue.sequence}")) { Text("跳转到原句") }
            }
            val chinese = cue.chineseText
            if (mode == SubtitleDisplayMode.CHINESE_ONLY) {
                Text(chinese ?: "暂无此句翻译", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (mode == SubtitleDisplayMode.BILINGUAL && !chinese.isNullOrBlank()) {
                Text(chinese, style = MaterialTheme.typography.bodyMedium.copy(fontSize = LazyEngTypeScale.subtitleTranslation),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
private fun PlayerControls(
    state: PlayerUiState,
    positionMs: StateFlow<Long>,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    hasChineseSubtitles: Boolean = state.hasChineseSubtitles,
) {
    val position by positionMs.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(position.toFloat()) }
    LaunchedEffect(position) { if (!dragging) dragPosition = position.toFloat() }
    Column(modifier.fillMaxWidth().background(Color(0xFF171A19)).padding(horizontal = LazyEngSpacing.medium, vertical = LazyEngSpacing.small)
        .testTag("player-controls")) {
        Slider(
            value = (if (dragging) dragPosition else position.toFloat()).coerceIn(0f, state.durationMs.coerceAtLeast(1L).toFloat()),
            onValueChange = { dragging = true; dragPosition = it },
            onValueChangeFinished = {
                if (dragging) onEvent(PlayerUiEvent.Seek(dragPosition.toLong()))
                dragging = false
            },
            valueRange = 0f..state.durationMs.coerceAtLeast(1L).toFloat(),
            enabled = state.phase != PlayerPhase.ERROR && state.durationMs > 0L,
            modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp)
                .semantics {
                    contentDescription = "播放进度"
                    stateDescription = "${formatTime(position)} / ${formatTime(state.durationMs)}"
                }.testTag("player-seek"),
            thumb = {
                Box(Modifier.size(18.dp).background(LazyEngColors.PrimaryMint, androidx.compose.foundation.shape.CircleShape))
            },
            track = { sliderState ->
                SliderDefaults.Track(sliderState, modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(activeTrackColor = LazyEngColors.PrimaryMint,
                        inactiveTrackColor = Color.White.copy(alpha = 0.7f)))
            },
        )
        Text("${formatTime(position)} / ${formatTime(state.durationMs)}", color = Color.White,
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("player-time"))
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
            TextButton(onClick = { onEvent(PlayerUiEvent.TogglePlayback) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics {
                    contentDescription = if (state.isPlaying) "暂停播放" else "开始播放"
                    stateDescription = if (state.isPlaying) "正在播放" else "已暂停"
                }.testTag("player-play-pause"),
                enabled = state.phase !in setOf(PlayerPhase.LOADING, PlayerPhase.ERROR, PlayerPhase.ENDED)) {
                Text(if (state.isPlaying) "暂停" else "播放", color = Color.White)
            }
            var speedMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { speedMenu = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "播放速度"; stateDescription = "${state.speed} 倍" }.testTag("player-speed")) {
                    Text(String.format(Locale.ROOT, "%.2gx", state.speed), color = Color.White)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        DropdownMenuItem(text = { Text(String.format(Locale.ROOT, "%.2gx", speed)) }, onClick = {
                            onEvent(PlayerUiEvent.SpeedChanged(speed)); speedMenu = false
                        })
                    }
                }
            }
            var volumeMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { volumeMenu = !volumeMenu }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "音量设置" }.testTag("player-volume-menu")) {
                    Text("音量", color = Color.White)
                }
                DropdownMenu(expanded = volumeMenu, onDismissRequest = { volumeMenu = false }) {
                    Slider(value = state.volume, onValueChange = { onEvent(PlayerUiEvent.VolumeChanged(it)) },
                        modifier = Modifier.width(180.dp).padding(horizontal = LazyEngSpacing.small)
                            .semantics { contentDescription = "播放音量"; stateDescription = "${(state.volume * 100).toInt()}%" }
                            .testTag("player-volume"))
                }
            }
            var subtitleMenu by remember { mutableStateOf(false) }
            Box {
                TextButton(onClick = { subtitleMenu = true }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "字幕显示模式"; stateDescription = state.subtitleMode.label(hasChineseSubtitles) }
                    .testTag("subtitle-mode-menu")) {
                    Text(when (state.subtitleMode) {
                        SubtitleDisplayMode.BILINGUAL -> "双语"
                        SubtitleDisplayMode.ENGLISH_ONLY -> "英文"
                        SubtitleDisplayMode.CHINESE_ONLY -> "中文"
                        SubtitleDisplayMode.HIDDEN -> "关闭字幕"
                    }, color = Color.White)
                }
                DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }) {
                    SubtitleDisplayMode.entries.forEach { mode ->
                        DropdownMenuItem(
                            text = { Text(mode.label(hasChineseSubtitles)) },
                            enabled = mode != SubtitleDisplayMode.CHINESE_ONLY || hasChineseSubtitles,
                            onClick = { onEvent(PlayerUiEvent.SetSubtitleMode(mode)); subtitleMenu = false },
                        )
                    }
                }
            }
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall),
            verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.xSmall)) {
            TextButton(onClick = { onEvent(PlayerUiEvent.ToggleSentenceLoop) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "单句循环"; stateDescription = if (state.sentenceLoop != null) "已开启" else "已关闭" }
                .testTag("player-sentence-loop")) {
                Text(if (state.sentenceLoop != null) "取消单句复读" else "单句复读", color = Color.White)
            }
            TextButton(onClick = { onEvent(PlayerUiEvent.SetLoopA) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "设置 AB 循环起点 A" }.testTag("player-loop-a")) {
                Text(if (state.loopStartMs == null) "设置 A" else "A ${formatTime(state.loopStartMs)}", color = Color.White)
            }
            TextButton(onClick = { onEvent(PlayerUiEvent.SetLoopB) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics { contentDescription = "设置 AB 循环终点 B" }.testTag("player-loop-b")) {
                Text(if (state.loopEndMs == null) "设置 B" else "B ${formatTime(state.loopEndMs)}", color = Color.White)
            }
            if (state.loopStartMs != null || state.loopEndMs != null) {
                TextButton(onClick = { onEvent(PlayerUiEvent.ClearAbLoop) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics { contentDescription = "清除 AB 循环" }.testTag("player-loop-clear")) {
                    Text("清除 AB", color = Color.White)
                }
            }
        }
        state.feedbackMessage?.let { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClickableSubtitleText(
    cue: PlayerSubtitleCue,
    text: String,
    tagPrefix: String,
    color: Color,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit,
    selectedWord: PlayerWordSelection? = null,
    savedLemmas: Set<String> = emptySet(),
) {
    val tokens = remember(text) { SubtitleTokenizer.tokenize(text) }
    FlowRow(modifier = modifier.testTag("$tagPrefix-text"), horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.Center) {
        var cursor = 0
        tokens.forEach { token ->
            val chunk = text.substring(cursor, token.end)
            if (token.kind == SubtitleTokenKind.WORD) {
                Text(
                    text = chunk,
                    color = color,
                    fontSize = fontSize,
                    textDecoration = if (token.normalized in savedLemmas) androidx.compose.ui.text.style.TextDecoration.Underline else null,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(
                        onClickLabel = "查词 ${token.surface}",
                        onClick = { onEvent(PlayerUiEvent.LookupWord(token.surface, token.normalized, token.start, token.end, cue)) },
                    )
                        .semantics { contentDescription = "查词：${token.surface}" }
                        .testTag("$tagPrefix-word-${token.start}")
                        .then(if (selectedWord?.cue?.id == cue.id && selectedWord.start == token.start && selectedWord.end == token.end)
                            Modifier.background(LazyEngColors.PrimaryMint.copy(alpha = 0.4f)) else Modifier),
                )
            } else {
                Text(chunk, color = color, fontSize = fontSize)
            }
            cursor = token.end
        }
        if (cursor < text.length) Text(text.substring(cursor), color = color, fontSize = fontSize)
    }
}

@Composable
private fun ContextualDictionaryBottomSheet(
    state: PlayerUiState,
    selection: PlayerWordSelection,
    onEvent: (PlayerUiEvent) -> Unit,
    pronunciationPlayer: PronunciationPlayer?,
) {
    val ttsStateFlow = remember(pronunciationPlayer) {
        pronunciationPlayer?.state ?: kotlinx.coroutines.flow.MutableStateFlow(PronunciationState.UNAVAILABLE)
    }
    val ttsState by ttsStateFlow.collectAsStateWithLifecycle()
    var accent by remember { mutableStateOf(PronunciationAccent.AMERICAN) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      Column(
        Modifier.fillMaxWidth().heightIn(max = maxHeight * 0.85f).verticalScroll(rememberScrollState())
            .padding(horizontal = LazyEngSpacing.large, vertical = LazyEngSpacing.medium)
            .padding(bottom = LazyEngSpacing.large).testTag("lookup-bottom-sheet"),
        verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small),
      ) {
        Text(selection.surface, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.testTag("lookup-word"))
        state.lookupLemma?.takeIf { it != selection.surface }?.let {
            Text("原形 $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("lookup-lemma"))
        }
        state.lookupEntry?.let { entry ->
            entry.phonetic?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("lookup-phonetic")) }
            entry.cefr?.let { Text("CEFR $it", style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("lookup-cefr")) }
            entry.partOfSpeech?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
            entry.senses.forEachIndexed { index, sense ->
                Text(sense, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("lookup-sense-$index"))
            }
        }
        if (state.lookupLoading) CircularProgressIndicator(modifier = Modifier.testTag("lookup-loading"))
        else if (state.lookupEntry == null) {
            Text(
                state.lookupMessage ?: "本地离线词典未收录${if (selection.surface.isNotBlank()) "该词" else ""}" +
                    if (selection.lemma.isNotBlank() && selection.lemma != selection.surface) "（${selection.lemma}）" else "",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.testTag("lookup-empty"),
            )
        }
        Text("原句 · ${formatTime(selection.positionMs)}", style = MaterialTheme.typography.labelLarge)
        Text(
            buildAnnotatedString {
                val start = selection.start.coerceIn(0, selection.cue.englishText.length)
                val end = selection.end.coerceIn(start, selection.cue.englishText.length)
                append(selection.cue.englishText.substring(0, start))
                withStyle(SpanStyle(background = LazyEngColors.SubtitleHighlight, color = LazyEngColors.TextPrimary)) {
                    append(selection.cue.englishText.substring(start, end))
                }
                append(selection.cue.englishText.substring(end))
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("lookup-context"),
        )
        PlayerAiExplanationCard(state.aiExplanation, state.lookupLoading, onEvent)
        state.lookupMessage?.takeIf { state.lookupEntry != null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { pronunciationPlayer?.speak(selection.surface, accent) },
                    enabled = pronunciationPlayer != null && ttsState != PronunciationState.INITIALIZING,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .semantics {
                            contentDescription = "播放英文发音"
                            stateDescription = when (ttsState) {
                                PronunciationState.SPEAKING -> "正在发音"
                                PronunciationState.UNAVAILABLE, PronunciationState.ERROR -> "发音不可用"
                                else -> "就绪"
                            }
                        }.testTag("lookup-pronounce"),
                ) {
                    Icon(painterResource(com.lazyeng.family.feature.player.R.drawable.ic_volume_sound), contentDescription = "播放英文发音")
                }
                Text(if (ttsState == PronunciationState.SPEAKING) "正在发音" else "发音")
            }
            FilterChip(selected = accent == PronunciationAccent.AMERICAN,
                onClick = { accent = PronunciationAccent.AMERICAN }, label = { Text("美音") },
                modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = accent == PronunciationAccent.BRITISH,
                onClick = { accent = PronunciationAccent.BRITISH }, label = { Text("英音") },
                modifier = Modifier.heightIn(min = 48.dp))
            if (ttsState == PronunciationState.UNAVAILABLE || ttsState == PronunciationState.ERROR) {
                Text("英文语音不可用，请检查系统语音服务或下载英文语音包。", modifier = Modifier.testTag("lookup-tts-unavailable"),
                    color = MaterialTheme.colorScheme.error)
            }
            if (state.lookupCanSave) Button(
                onClick = { onEvent(PlayerUiEvent.AddLookupToVocabulary) },
                enabled = !state.lookupSaved && !state.lookupSaving,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .semantics {
                        contentDescription = if (state.lookupSaved) "已加入生词本" else "加入生词本"
                        stateDescription = when {
                            state.lookupSaved -> "已收藏"
                            state.lookupSaving -> "正在保存"
                            else -> "未收藏"
                        }
                    }.testTag("lookup-save"),
            ) {
                Text(when {
                    state.lookupSaved -> "已加入生词本"
                    state.lookupSaving -> "正在保存…"
                    else -> "加入生词本"
                })
            }
            OutlinedButton(onClick = { onEvent(PlayerUiEvent.CloseLookup) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("关闭") }
            OutlinedButton(onClick = {
                onEvent(PlayerUiEvent.CloseLookup)
                onEvent(PlayerUiEvent.TogglePlayback)
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("继续播放") }
        }
    }
    }
}

@Composable
private fun PlayerAiExplanationCard(
    state: PlayerAiExplanationState,
    localLookupLoading: Boolean,
    onEvent: (PlayerUiEvent) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("ai-explanation-card"),
        shape = RoundedCornerShape(LazyEngCornerSize.card),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(LazyEngSpacing.medium), verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
            Text("AI 语境助读", style = MaterialTheme.typography.titleSmall)
            when (state.phase) {
                AiExplanationPhase.NOT_CONFIGURED -> Text(
                    "${state.message ?: "AI 语境助读尚未设置。"} ${if (localLookupLoading) "正在查询离线词典…" else "离线释义和发音仍可正常使用。"}",
                    modifier = Modifier.testTag("ai-explanation-disabled"),
                    style = MaterialTheme.typography.bodyMedium,
                )
                AiExplanationPhase.LOADING -> {
                    Text("AI 正在结合台词分析语境…", modifier = Modifier.testTag("ai-explanation-loading"),
                        style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth().heightIn(min = 4.dp))
                    Text("离线释义已保留，不会影响继续看视频。", style = MaterialTheme.typography.bodySmall)
                }
                AiExplanationPhase.SUCCESS -> {
                    val explanation = state.explanation
                    if (explanation != null) {
                        Text(explanation.meaningZh, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.testTag("ai-explanation-meaning"))
                        Text(explanation.whyHereZh, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.testTag("ai-explanation-context"))
                        explanation.partOfSpeech?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                        explanation.simpleExampleEnglish?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        explanation.simpleExampleChinese?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                AiExplanationPhase.FALLBACK -> {
                    Text(state.message ?: "网络暂不可用，已呈现离线词典。",
                        modifier = Modifier.testTag("ai-explanation-fallback"), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { onEvent(PlayerUiEvent.RetryAiExplanation) },
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("ai-explanation-retry")) {
                        Text("重试")
                    }
                }
            }
        }
    }
}

private fun SubtitleDisplayMode.label(hasChinese: Boolean): String = when (this) {
    SubtitleDisplayMode.BILINGUAL -> "双语对照"
    SubtitleDisplayMode.ENGLISH_ONLY -> "仅英文"
    SubtitleDisplayMode.CHINESE_ONLY -> if (hasChinese) "仅中文" else "仅中文（暂无翻译）"
    SubtitleDisplayMode.HIDDEN -> "关闭字幕"
}

private fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val AUTO_SCROLL_PAUSE_MS = 5_000L

enum class PlayerLayoutMode(val testTag: String) {
    PORTRAIT("player-layout-portrait"),
    COMPACT_LANDSCAPE("player-layout-phone-landscape"),
    EXPANDED("player-layout-expanded"),
}

fun playerLayoutMode(widthDp: Float, heightDp: Float): PlayerLayoutMode = when {
    widthDp > heightDp && heightDp < 600f -> PlayerLayoutMode.COMPACT_LANDSCAPE
    widthDp >= 600f -> PlayerLayoutMode.EXPANDED
    else -> PlayerLayoutMode.PORTRAIT
}
