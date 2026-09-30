package com.lazyeng.family.feature.vocabulary

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lazyeng.family.core.designsystem.*
import com.lazyeng.family.core.model.ReviewRating
import com.lazyeng.family.feature.player.PronunciationAccent
import com.lazyeng.family.feature.player.PronunciationPlayer
import com.lazyeng.family.feature.player.PronunciationState
import com.lazyeng.family.feature.player.SystemPronunciationPlayer

@Composable
fun FlashcardReviewRoute(
    model: FlashcardReviewViewModel,
    onEffect: (FlashcardReviewEffect) -> Unit,
    onBack: () -> Unit,
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
        onDispose { owner.lifecycle.removeObserver(observer); speech.stop(); speech.release() }
    }
    BackHandler { model.onEvent(FlashcardReviewEvent.RequestExit) }
    FlashcardReviewScreen(
        state = state,
        pronunciationPlayer = speech,
        pronunciationState = speechState,
        onEvent = model::onEvent,
        onBack = onBack,
    )
}

@Composable
fun FlashcardReviewScreen(
    state: FlashcardReviewUiState,
    pronunciationPlayer: PronunciationPlayer? = null,
    pronunciationState: PronunciationState = PronunciationState.UNAVAILABLE,
    onEvent: (FlashcardReviewEvent) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it); onEvent(FlashcardReviewEvent.ClearMessage) } }
    val reduceMotion = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    Surface(Modifier.fillMaxSize().testTag("flashcard-review"), color = LazyEngColors.Background) {
        Scaffold(containerColor = LazyEngColors.Background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).safeDrawingPadding().padding(horizontal = LazyEngSpacing.large)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LazyEngSpacing.medium),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("轻松复习", style = MaterialTheme.typography.labelLarge, color = LazyEngColors.PrimaryDeep)
                        Text("想一想，再翻卡", style = MaterialTheme.typography.titleLarge, color = LazyEngColors.TextPrimary)
                    }
                    IconButton(onClick = { onEvent(FlashcardReviewEvent.RequestExit) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("review-exit")) {
                        Icon(Icons.Default.Close, contentDescription = "退出复习")
                    }
                }
                when {
                    state.loading -> LoadingSkeleton(Modifier.fillMaxWidth().height(280.dp))
                    state.message != null && state.current == null && !state.complete && !state.initiallyEmpty -> {
                        ErrorState("暂时无法开始复习", state.message, retryLabel = "返回生词本", onRetry = onBack)
                    }
                    state.initiallyEmpty -> EmptyReview(onBack)
                    state.complete -> ReviewCompletion(state, onBack)
                    state.current != null -> {
                        Text("第 ${state.completedCount + 1} / ${state.completedCount + state.cards.size} 词", style = MaterialTheme.typography.titleMedium, color = LazyEngColors.TextPrimary, modifier = Modifier.testTag("review-progress"))
                        LinearProgressIndicator(
                            progress = { state.completedCount.toFloat() / (state.completedCount + state.cards.size).coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                            color = LazyEngColors.PrimaryDeep,
                            trackColor = LazyEngColors.Border,
                        )
                        FlashcardFace(
                            card = state.current!!,
                            flipped = state.flipped,
                            reduceMotion = reduceMotion,
                            pronunciationPlayer = pronunciationPlayer,
                            pronunciationState = pronunciationState,
                            onFlip = { onEvent(FlashcardReviewEvent.Flip) },
                            onWatch = { onEvent(FlashcardReviewEvent.WatchContext) },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
                        )
                        if (!state.flipped) {
                            Text("先在心里想一想，再点卡片翻面", style = MaterialTheme.typography.bodyMedium, color = LazyEngColors.TextSecondary, modifier = Modifier.align(Alignment.CenterHorizontally))
                        } else {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(LazyEngSpacing.small)) {
                                RatingButton("需要重来", "10 分钟后", ReviewRating.FORGOT, onEvent, Modifier.weight(1f), LazyEngColors.Danger)
                                RatingButton("差不多", "明天再看", ReviewRating.UNCERTAIN, onEvent, Modifier.weight(1f), LazyEngColors.Warning)
                                RatingButton("完全记住", "3 天后再看", ReviewRating.REMEMBERED, onEvent, Modifier.weight(1f), LazyEngColors.PrimaryDeep)
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.exitConfirmation) {
        AlertDialog(
            onDismissRequest = { onEvent(FlashcardReviewEvent.CancelExit) },
            title = { Text("先暂停复习吗？") },
            text = { Text("已经完成的评分会保存，剩余单词稍后还可以继续复习。") },
            confirmButton = { TextButton(onClick = { onEvent(FlashcardReviewEvent.ConfirmExit) }, modifier = Modifier.testTag("confirm-review-exit")) { Text("退出复习") } },
            dismissButton = { TextButton(onClick = { onEvent(FlashcardReviewEvent.CancelExit) }) { Text("继续复习") } },
        )
    }
}

@Composable
private fun FlashcardFace(
    card: Flashcard,
    flipped: Boolean,
    reduceMotion: Boolean,
    pronunciationPlayer: PronunciationPlayer?,
    pronunciationState: PronunciationState,
    onFlip: () -> Unit,
    onWatch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 420),
        label = "flashcard-flip",
    )
    val showBack = rotation > 90f
    val context = card.context
    val word = context?.surface ?: card.item.lemma
    Box(
        modifier = modifier.clip(LazyEngShapes.card).background(LazyEngColors.Surface)
            .heightIn(min = 300.dp)
            .clickable(role = Role.Button, onClick = onFlip)
            .semantics {
                contentDescription = if (flipped) "闪卡背面，点击返回单词" else "闪卡正面，$word，点击查看释义"
            }
            .testTag(if (flipped) "flashcard-back" else "flashcard-front"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
                .graphicsLayer { rotationY = rotation; cameraDistance = 12 * density }
                .padding(LazyEngSpacing.large),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (!showBack) {
                Text(word, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = LazyEngColors.TextPrimary)
                IconButton(
                    onClick = { pronunciationPlayer?.speak(word, PronunciationAccent.AMERICAN) },
                    enabled = pronunciationPlayer != null && pronunciationState != PronunciationState.INITIALIZING,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).testTag("review-pronounce")
                        .semantics { contentDescription = "播放英文发音" },
                ) { Icon(painterResource(com.lazyeng.family.feature.player.R.drawable.ic_volume_sound), contentDescription = null) }
                Text(blankSurface(context?.contextSentence.orEmpty(), word), style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextSecondary)
                TextButton(onClick = onFlip, modifier = Modifier.heightIn(min = 48.dp).testTag("review-flip")) {
                    Text("翻开卡片")
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth().graphicsLayer { rotationY = 180f },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                Text(card.entry?.senses?.firstOrNull() ?: context?.definition ?: "暂无释义", style = MaterialTheme.typography.headlineSmall, color = LazyEngColors.TextPrimary)
                Spacer(Modifier.height(LazyEngSpacing.small))
                Text("原形 · ${card.item.lemma}", style = MaterialTheme.typography.titleMedium, color = LazyEngColors.PrimaryDeep)
                Text(context?.phonetic ?: card.entry?.phonetic ?: "", style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextSecondary)
                Text(context?.contextSentence.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextPrimary, modifier = Modifier.padding(vertical = LazyEngSpacing.medium))
                OutlinedButton(onClick = onWatch, enabled = context?.videoId != null, modifier = Modifier.heightIn(min = 48.dp).testTag("review-watch-context")) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(Modifier.width(LazyEngSpacing.small)); Text("观看原句")
                }
                }
            }
        }
    }
}

@Composable
private fun RatingButton(label: String, interval: String, rating: ReviewRating, onEvent: (FlashcardReviewEvent) -> Unit, modifier: Modifier, tint: androidx.compose.ui.graphics.Color) {
    OutlinedButton(
        onClick = { onEvent(FlashcardReviewEvent.Rate(rating)) },
        enabled = true,
        modifier = modifier.heightIn(min = 64.dp).testTag("review-rating-${rating.name}"),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = tint),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(interval, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EmptyReview(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("review-empty"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Star, contentDescription = null, tint = LazyEngColors.PrimaryMint, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(LazyEngSpacing.medium))
        EmptyState("太棒了，今日没有待复习单词！", "新的生词会在这里等你。")
        PrimaryButton("返回生词本", onBack, Modifier.testTag("review-empty-back"))
    }
}

@Composable
private fun ReviewCompletion(state: FlashcardReviewUiState, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag("review-complete"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Check, contentDescription = null, tint = LazyEngColors.PrimaryDeep, modifier = Modifier.size(64.dp))
        Text("恭喜完成复习", style = MaterialTheme.typography.headlineSmall, color = LazyEngColors.TextPrimary)
        Text("本次完成 ${state.completedCount} 个词", style = MaterialTheme.typography.bodyLarge, color = LazyEngColors.TextSecondary)
        ReviewRating.entries.forEach { rating -> Text("${rating.label()}：${state.ratings[rating] ?: 0}", style = MaterialTheme.typography.bodyMedium) }
        PrimaryButton("返回生词本", onBack, Modifier.testTag("review-completion-back"))
    }
}

private fun ReviewRating.label(): String = when (this) {
    ReviewRating.FORGOT -> "需要重来"
    ReviewRating.UNCERTAIN -> "差不多"
    ReviewRating.REMEMBERED -> "完全记住"
}

private fun blankSurface(sentence: String, word: String): String {
    if (sentence.isBlank()) return "想想这个词在原句里的意思。"
    val range = sentence.indexOf(word, ignoreCase = true)
    return if (range < 0 || word.isBlank()) sentence else sentence.replaceRange(range, range + word.length, "_____" )
}
