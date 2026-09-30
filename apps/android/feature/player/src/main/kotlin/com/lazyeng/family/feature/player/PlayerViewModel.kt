package com.lazyeng.family.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lazyeng.family.core.common.Clock
import com.lazyeng.family.core.common.DispatcherProvider
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.common.ContextExplanationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlayerUiState(
    val phase: PlayerPhase = PlayerPhase.LOADING,
    val title: String = "",
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val volume: Float = 1f,
    val speed: Float = 1f,
    val fullscreen: Boolean = false,
    val message: String? = null,
    val subtitleMode: SubtitleDisplayMode = SubtitleDisplayMode.BILINGUAL,
    val hasChineseSubtitles: Boolean = false,
    val selectedWord: PlayerWordSelection? = null,
    val lookupEntry: DictionaryEntry? = null,
    val lookupLemma: String? = null,
    val lookupLoading: Boolean = false,
    val lookupSaved: Boolean = false,
    val savedLemmas: Set<String> = emptySet(),
    val lookupCanSave: Boolean = false,
    val lookupSaving: Boolean = false,
    val lookupMessage: String? = null,
    val aiExplanation: PlayerAiExplanationState = PlayerAiExplanationState(),
    val sentenceLoop: PlayerSubtitleCue? = null,
    val loopStartMs: Long? = null,
    val loopEndMs: Long? = null,
    val feedbackMessage: String? = null,
)

data class PlayerWordSelection(
    val surface: String,
    val lemma: String,
    val start: Int,
    val end: Int,
    val cue: PlayerSubtitleCue,
    val positionMs: Long,
)

class PlayerViewModel(
    private val loadSession: suspend () -> PlayerSessionResult,
    private val engineFactory: () -> PlayerEngine,
    private val clock: Clock,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {
    private val mutableState = MutableStateFlow(PlayerUiState())
    val state = mutableState.asStateFlow()
    private val mutablePosition = MutableStateFlow(0L)
    val positionMs = mutablePosition.asStateFlow()
    private val mutableSubtitles = MutableStateFlow<List<PlayerSubtitleCue>>(emptyList())
    val subtitles = mutableSubtitles.asStateFlow()
    private val mutableActiveSubtitleIndex = MutableStateFlow<Int?>(null)
    val activeSubtitleIndex = mutableActiveSubtitleIndex.asStateFlow()
    private val effectChannel = Channel<PlayerUiEffect>(Channel.BUFFERED)
    val effects = effectChannel.receiveAsFlow()
    private var session: PlayerPlaybackSession? = null
    private var subtitleTimeline = SubtitleTimelineIndex(emptyList())
    private var engine: PlayerEngine? = null
    private var engineJob: Job? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var progressWriteJob: Job? = null
    private var lookupJob: Job? = null
    private var aiExplanationJob: Job? = null
    private var lastPersistedPosition = -1L
    private var lastPersistedAtMs = 0L
    private var lastLoopSeekBoundary: Long? = null

    init { load() }

    val mediaPlayer get() = engine?.player

    fun onEvent(event: PlayerUiEvent) {
        when (event) {
            PlayerUiEvent.TogglePlayback -> {
                val current = engine ?: return
                if (state.value.isPlaying) {
                    current.pause()
                    persist(force = true)
                } else current.play()
            }
            is PlayerUiEvent.Seek -> {
                val position = event.positionMs.coerceIn(0L, state.value.durationMs)
                engine?.seekTo(position)
                mutablePosition.value = position
                mutableActiveSubtitleIndex.value = subtitleTimeline.findCueIndex(position)
                if (state.value.sentenceLoop?.let { position !in it.startMs until it.endMs } == true) {
                    mutableState.update { it.copy(sentenceLoop = null) }
                }
                persist(force = true, positionMs = position)
            }
            is PlayerUiEvent.VolumeChanged -> {
                engine?.setVolume(event.volume)
                mutableState.update { it.copy(volume = event.volume.coerceIn(0f, 1f)) }
            }
            is PlayerUiEvent.SpeedChanged -> {
                engine?.setSpeed(event.speed)
                mutableState.update { it.copy(speed = event.speed.coerceIn(0.5f, 2f)) }
            }
            PlayerUiEvent.ToggleFullscreen -> mutableState.update { it.copy(fullscreen = !it.fullscreen) }
            is PlayerUiEvent.SetSubtitleMode -> mutableState.update {
                if (event.mode == SubtitleDisplayMode.CHINESE_ONLY && !it.hasChineseSubtitles) it
                else it.copy(subtitleMode = event.mode)
            }
            is PlayerUiEvent.LookupWord -> lookup(event)
            PlayerUiEvent.CloseLookup -> closeLookup()
            PlayerUiEvent.AddLookupToVocabulary -> addSelectedWord()
            PlayerUiEvent.RetryAiExplanation -> retryAiExplanation()
            PlayerUiEvent.ToggleSentenceLoop -> toggleSentenceLoop()
            PlayerUiEvent.SetLoopA -> {
                val position = mutablePosition.value.coerceAtLeast(0L)
                mutableState.update { it.copy(loopStartMs = position, loopEndMs = null, sentenceLoop = null) }
                mutableState.update { it.copy(feedbackMessage = "已设置 A 点 ${formatLoopTime(position)}") }
            }
            PlayerUiEvent.SetLoopB -> {
                val snapshot = state.value
                val position = mutablePosition.value.coerceAtLeast(0L)
                if (snapshot.loopStartMs == null || position <= snapshot.loopStartMs) {
                    mutableState.update { it.copy(loopEndMs = null) }
                    mutableState.update { it.copy(feedbackMessage = "B 点必须在 A 点之后") }
                } else {
                    mutableState.update { it.copy(loopEndMs = position, sentenceLoop = null) }
                    mutableState.update { it.copy(feedbackMessage = "已设置 B 点，开始 AB 复读") }
                }
            }
            PlayerUiEvent.ClearAbLoop -> mutableState.update { it.copy(loopStartMs = null, loopEndMs = null) }
            PlayerUiEvent.LifecycleStopped -> {
                engine?.pause()
                persist(force = true)
            }
            PlayerUiEvent.Retry -> load()
            PlayerUiEvent.Exit -> viewModelScope.launch {
                engine?.pause()
                persistAwait(force = true)
                effectChannel.send(PlayerUiEffect.NavigateBack)
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        engineJob?.cancel()
        saveJob?.cancel()
        progressWriteJob?.cancel()
        lookupJob?.cancel()
        aiExplanationJob?.cancel()
        engine?.pause()
        engine?.release()
        engine = null
        session = null
        lastLoopSeekBoundary = null
        subtitleTimeline = SubtitleTimelineIndex(emptyList())
        mutablePosition.value = 0L
        mutableSubtitles.value = emptyList()
        mutableActiveSubtitleIndex.value = null
        lastPersistedPosition = -1L
        lastPersistedAtMs = 0L
        mutableState.value = PlayerUiState()
        loadJob = viewModelScope.launch {
            val result = try { withContext(dispatchers.io) { loadSession() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { PlayerSessionResult.Failed("无法读取视频，请返回详情页重试。") }
            when (result) {
                is PlayerSessionResult.Failed -> mutableState.value = PlayerUiState(phase = PlayerPhase.ERROR, message = result.message)
                is PlayerSessionResult.Ready -> {
                    val value = result.session
                    if (!value.scopeValid()) {
                        mutableState.value = PlayerUiState(phase = PlayerPhase.ERROR, message = "当前档案已变化，请返回并重新打开视频。")
                        return@launch
                    }
                    session = value
                    val instance = engineFactory()
                    engine = instance
                    mutableState.value = PlayerUiState(
                        title = value.title,
                        durationMs = value.durationMs,
                        hasChineseSubtitles = value.subtitles.any { !it.chineseText.isNullOrBlank() },
                    )
                    subtitleTimeline = SubtitleTimelineIndex(value.subtitles)
                    mutableSubtitles.value = subtitleTimeline.cues
                    mutablePosition.value = value.resumePositionMs.coerceIn(0L, value.durationMs)
                    mutableActiveSubtitleIndex.value = subtitleTimeline.findCueIndex(value.resumePositionMs)
                    value.listSavedWords?.let { loadSavedWords ->
                        launch(dispatchers.io) {
                            val lemmas = try { loadSavedWords() } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) { emptySet() }
                            if (session === value && value.scopeValid()) {
                                mutableState.update { it.copy(savedLemmas = lemmas) }
                            }
                        }
                    }
                    engineJob?.cancel()
                    engineJob = launch {
                        instance.state.collect { snapshot ->
                            val position = snapshot.positionMs.coerceIn(0L, value.durationMs)
                            mutablePosition.value = position
                            val activeIndex = subtitleTimeline.findCueIndex(position)
                            if (mutableActiveSubtitleIndex.value != activeIndex) {
                                mutableActiveSubtitleIndex.value = activeIndex
                            }
                            val old = mutableState.value
                            val duration = snapshot.durationMs?.takeIf { it > 0L } ?: value.durationMs
                            if (old.phase != snapshot.phase || old.isPlaying != snapshot.isPlaying ||
                                old.durationMs != duration || old.message != snapshot.message
                            ) {
                                mutableState.value = old.copy(
                                    phase = snapshot.phase,
                                    durationMs = duration,
                                    isPlaying = snapshot.isPlaying,
                                    message = snapshot.message,
                                )
                            }
                            handleLoopBoundary(snapshot)
                        }
                    }
                    instance.prepare(value.uri, value.resumePositionMs.coerceIn(0L, value.durationMs))
                    startProgressPersistence()
                }
            }
        }
    }

    private fun startProgressPersistence() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            while (true) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                if (state.value.isPlaying) persist(force = true)
            }
        }
    }

    private fun lookup(event: PlayerUiEvent.LookupWord) {
        if (event.start < 0 || event.end <= event.start || event.end > event.cue.englishText.length ||
            event.cue.englishText.substring(event.start, event.end) != event.surface
        ) return
        lookupJob?.cancel()
        aiExplanationJob?.cancel()
        engine?.pause()
        val selection = PlayerWordSelection(event.surface, event.normalized, event.start, event.end, event.cue, mutablePosition.value)
        val explain = session?.explainContext
        mutableState.update {
            it.copy(selectedWord = selection, lookupEntry = null, lookupLemma = null, lookupLoading = true,
                lookupSaved = false, lookupCanSave = session?.saveWord != null, lookupSaving = false, lookupMessage = null,
                aiExplanation = PlayerAiExplanationState(
                    phase = if (explain == null) AiExplanationPhase.NOT_CONFIGURED else AiExplanationPhase.LOADING,
                    message = if (explain == null) "AI 语境助读尚未设置，不影响离线查词。" else null,
                ))
        }
        lookupJob = viewModelScope.launch(dispatchers.io) {
            val current = session ?: return@launch
            val result = try {
                current.lookupWord?.invoke(event.surface, event.normalized, event.cue.id, event.cue.englishText, event.start, event.end)
            }
            catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { PlayerDictionaryLookup(null, unavailableMessage = "本地词典暂不可用，请检查词典包。") }
            val matchedLemma = result?.lemma ?: event.normalized
            val saved = try { current.isWordSaved?.invoke(matchedLemma) ?: false } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (current.scopeValid() && state.value.selectedWord.matchesSelection(selection)) {
                mutableState.update {
                    it.copy(selectedWord = it.selectedWord?.copy(lemma = matchedLemma),
                        lookupEntry = result?.entry, lookupLemma = result?.lemma,
                        lookupMessage = result?.unavailableMessage,
                        lookupSaved = saved, lookupLoading = false,
                        savedLemmas = if (saved) it.savedLemmas + matchedLemma else it.savedLemmas)
                }
                if (current.explainContext != null) startAiExplanation(selection, matchedLemma, result?.entry?.senses?.firstOrNull())
            }
        }
    }

    private fun closeLookup() {
        lookupJob?.cancel()
        aiExplanationJob?.cancel()
        mutableState.update {
            it.copy(selectedWord = null, lookupEntry = null, lookupLemma = null, lookupLoading = false,
                lookupMessage = null, aiExplanation = PlayerAiExplanationState())
        }
    }

    private fun retryAiExplanation() {
        val selection = state.value.selectedWord ?: return
        val current = session ?: return
        if (current.explainContext == null) return
        startAiExplanation(selection, state.value.lookupLemma ?: selection.lemma, state.value.lookupEntry?.senses?.firstOrNull(), forceRegenerate = true)
    }

    private fun startAiExplanation(
        selection: PlayerWordSelection,
        lemma: String,
        localMeaning: String?,
        forceRegenerate: Boolean = false,
    ) {
        aiExplanationJob?.cancel()
        val current = session ?: return
        val explain = current.explainContext ?: return
        val cues = mutableSubtitles.value
        val cueIndex = cues.indexOfFirst { it.id == selection.cue.id }
        val request = ContextExplanationRequest(
            word = selection.surface,
            lemma = lemma,
            sentenceEnglish = selection.cue.englishText,
            previousLineEnglish = cues.getOrNull(cueIndex - 1)?.englishText,
            nextLineEnglish = cues.getOrNull(cueIndex + 1)?.englishText,
            learnerLevel = current.learnerLevel,
            uiLanguage = current.uiLanguage,
        )
        mutableState.update { it.copy(aiExplanation = PlayerAiExplanationState(AiExplanationPhase.LOADING)) }
        aiExplanationJob = viewModelScope.launch(dispatchers.io) {
            val result = try { explain(request, localMeaning, forceRegenerate) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (current.scopeValid() && state.value.selectedWord.matchesSelection(selection)) {
                val safe = result?.takeIf {
                    it.safetyFlag == "safe" && it.meaningZh.isNotBlank() && it.meaningZh.length <= 24 &&
                        it.whyHereZh.isNotBlank() && it.whyHereZh.length <= 80 && (it.confidence?.let { value -> value in 0f..1f } != false)
                }
                mutableState.update { state ->
                    state.copy(aiExplanation = if (safe != null) {
                        PlayerAiExplanationState(AiExplanationPhase.SUCCESS, safe.copy(
                            meaningZh = safe.meaningZh.cleanAiDisplay(),
                            whyHereZh = safe.whyHereZh.cleanAiDisplay(),
                            simpleExampleEnglish = safe.simpleExampleEnglish?.cleanAiDisplay(),
                            simpleExampleChinese = safe.simpleExampleChinese?.cleanAiDisplay(),
                            partOfSpeech = safe.partOfSpeech?.cleanAiDisplay(),
                        ))
                    } else {
                        PlayerAiExplanationState(AiExplanationPhase.FALLBACK, message = "AI 暂不可用，已为您呈现离线词典。")
                    })
                }
            }
        }
    }

    private fun String.cleanAiDisplay(): String = replace(Regex("[\\u0000-\\u001f\\u007f]"), " ")
        .replace(Regex("[*_`#>]"), "").replace(Regex("\\s+"), " ").trim()

    private fun PlayerWordSelection?.matchesSelection(expected: PlayerWordSelection): Boolean =
        this?.let {
            it.surface == expected.surface && it.start == expected.start && it.end == expected.end &&
                it.cue.id == expected.cue.id && it.positionMs == expected.positionMs
        } == true

    private fun addSelectedWord() {
        val current = session ?: return
        val selection = state.value.selectedWord ?: return
        val save = current.saveWord ?: run {
            mutableState.update { it.copy(lookupMessage = "生词本存储尚未接入，请稍后再试。") }
            return
        }
        mutableState.update { it.copy(lookupSaving = true, lookupMessage = null) }
        viewModelScope.launch(dispatchers.io) {
            val saved = try {
                current.scopeValid() && save(PlayerVocabularyCapture(
                    profileId = current.profileId,
                    videoId = current.videoId,
                    surface = selection.surface,
                    lemma = selection.lemma,
                    dictionaryEntry = state.value.lookupEntry,
                    lineId = selection.cue.id,
                    sentence = selection.cue.englishText,
                    timestampMs = selection.positionMs,
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { false }
            if (current.scopeValid() && state.value.selectedWord.matchesSelection(selection)) {
                mutableState.update {
                    it.copy(lookupSaving = false, lookupSaved = saved,
                        savedLemmas = if (saved) it.savedLemmas + selection.lemma else it.savedLemmas,
                        lookupMessage = if (saved) null else "暂时无法加入生词本，请稍后重试。")
                }
            }
        }
    }

    private fun toggleSentenceLoop() {
        if (engine == null || state.value.phase in setOf(PlayerPhase.LOADING, PlayerPhase.ERROR, PlayerPhase.ENDED)) return
        val cue = mutableActiveSubtitleIndex.value?.let(mutableSubtitles.value::getOrNull)
        if (cue == null || cue.endMs <= cue.startMs) {
            mutableState.update { it.copy(feedbackMessage = "当前没有可复读的字幕句") }
            return
        }
        val enabled = state.value.sentenceLoop?.id != cue.id
        mutableState.update {
            it.copy(sentenceLoop = if (enabled) cue else null,
                loopStartMs = if (enabled) null else it.loopStartMs,
                loopEndMs = if (enabled) null else it.loopEndMs)
        }
        if (enabled) {
            engine?.seekTo(cue.startMs)
            mutablePosition.value = cue.startMs
            engine?.play()
            mutableState.update { it.copy(feedbackMessage = "已开启单句复读") }
        }
    }

    private fun handleLoopBoundary(snapshot: PlayerEngineState) {
        if (!snapshot.isPlaying) {
            lastLoopSeekBoundary = null
            return
        }
        val current = state.value
        val range = current.loopEndMs?.let { end -> current.loopStartMs?.let { start -> start to end } }
            ?: current.sentenceLoop?.let { it.startMs to it.endMs }
            ?: run { lastLoopSeekBoundary = null; return }
        val (start, end) = range
        if (end <= start) return
        if (snapshot.positionMs < end) {
            lastLoopSeekBoundary = null
            return
        }
        if (lastLoopSeekBoundary == end) return
        lastLoopSeekBoundary = end
        engine?.seekTo(start)
        mutablePosition.value = start
        mutableActiveSubtitleIndex.value = subtitleTimeline.findCueIndex(start)
        engine?.play()
    }

    private fun formatLoopTime(ms: Long) = "%d:%02d".format(ms / 60_000, (ms / 1_000) % 60)

    private fun persist(force: Boolean, positionMs: Long = mutablePosition.value) {
        val current = session ?: return
        val duration = state.value.durationMs.takeIf { it > 0L } ?: current.durationMs
        val position = positionMs.coerceIn(0L, duration)
        val now = clock.now().toEpochMilli()
        if (!force && position == lastPersistedPosition && now - lastPersistedAtMs < PROGRESS_SAVE_INTERVAL_MS) return
        progressWriteJob?.cancel()
        progressWriteJob = viewModelScope.launch(dispatchers.io) {
            try {
                if (!current.scopeValid()) return@launch
                current.saveProgress(position, duration)
                lastPersistedPosition = position
                lastPersistedAtMs = now
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "播放进度暂时无法保存，请返回后重试。") }
            }
        }
    }

    private suspend fun persistAwait(force: Boolean, positionMs: Long = mutablePosition.value) {
        val current = session ?: return
        val duration = state.value.durationMs.takeIf { it > 0L } ?: current.durationMs
        val position = positionMs.coerceIn(0L, duration)
        val now = clock.now().toEpochMilli()
        if (!force && position == lastPersistedPosition && now - lastPersistedAtMs < PROGRESS_SAVE_INTERVAL_MS) return
        progressWriteJob?.cancelAndJoin()
        progressWriteJob = viewModelScope.launch(dispatchers.io) {
            try {
                if (!current.scopeValid()) return@launch
                current.saveProgress(position, duration)
                lastPersistedPosition = position
                lastPersistedAtMs = now
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(message = "播放进度暂时无法保存，请返回后重试。") }
            }
        }
        progressWriteJob?.join()
    }

    override fun onCleared() {
        lookupJob?.cancel()
        aiExplanationJob?.cancel()
        engine?.let { current ->
            current.pause()
            persist(force = true)
            current.release()
        }
        super.onCleared()
    }

    companion object { const val PROGRESS_SAVE_INTERVAL_MS = 10_000L }
}
