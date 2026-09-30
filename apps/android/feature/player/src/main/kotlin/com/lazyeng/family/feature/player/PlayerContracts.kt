package com.lazyeng.family.feature.player

import androidx.media3.common.Player
import com.lazyeng.family.core.model.DictionaryEntry
import com.lazyeng.family.core.model.ProfileId
import com.lazyeng.family.core.model.SubtitleLineId
import com.lazyeng.family.core.common.ContextExplanation
import com.lazyeng.family.core.common.ContextExplanationRequest
import kotlinx.coroutines.flow.StateFlow

enum class PlayerPhase { LOADING, READY, BUFFERING, ENDED, ERROR }

data class PlayerEngineState(
    val phase: PlayerPhase = PlayerPhase.LOADING,
    val positionMs: Long = 0L,
    val durationMs: Long? = null,
    val isPlaying: Boolean = false,
    val message: String? = null,
)

interface PlayerEngine {
    val player: Player?
    val state: StateFlow<PlayerEngineState>
    fun prepare(uri: String, startPositionMs: Long)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)
    fun release()
}

data class PlayerPlaybackSession(
    val profileId: ProfileId,
    val videoId: String,
    val learnerLevel: String = "",
    val uiLanguage: String = "zh-CN",
    val title: String,
    val uri: String,
    val durationMs: Long,
    val resumePositionMs: Long,
    val subtitles: List<PlayerSubtitleCue> = emptyList(),
    val lookupWord: (suspend (surface: String, lemma: String, lineId: SubtitleLineId, sentence: String, start: Int, end: Int) -> PlayerDictionaryLookup)? = null,
    val explainContext: (suspend (ContextExplanationRequest, localMeaning: String?, forceRegenerate: Boolean) -> ContextExplanation?)? = null,
    val isWordSaved: (suspend (String) -> Boolean)? = null,
    val listSavedWords: (suspend () -> Set<String>)? = null,
    val saveWord: (suspend (PlayerVocabularyCapture) -> Boolean)? = null,
    val scopeValid: suspend () -> Boolean,
    val saveProgress: suspend (positionMs: Long, durationMs: Long) -> Unit,
)

data class PlayerDictionaryLookup(
    val entry: DictionaryEntry?,
    val lemma: String? = entry?.lemma,
    val unavailableMessage: String? = null,
)

enum class PronunciationAccent { AMERICAN, BRITISH }

enum class PronunciationState { INITIALIZING, READY, SPEAKING, UNAVAILABLE, ERROR }

enum class AiExplanationPhase { NOT_CONFIGURED, LOADING, SUCCESS, FALLBACK }

data class PlayerAiExplanationState(
    val phase: AiExplanationPhase = AiExplanationPhase.NOT_CONFIGURED,
    val explanation: ContextExplanation? = null,
    val message: String? = null,
)

interface PronunciationPlayer {
    val state: StateFlow<PronunciationState>
    fun speak(text: String, accent: PronunciationAccent)
    fun stop()
    fun release()
}

data class PlayerVocabularyCapture(
    val profileId: ProfileId,
    val videoId: String,
    val surface: String,
    val lemma: String,
    val dictionaryEntry: DictionaryEntry?,
    val lineId: SubtitleLineId,
    val sentence: String,
    val timestampMs: Long,
)

data class PlayerSubtitleCue(
    val id: SubtitleLineId,
    val sequence: Int,
    val startMs: Long,
    val endMs: Long,
    val englishText: String,
    val chineseText: String? = null,
)

enum class SubtitleDisplayMode { BILINGUAL, ENGLISH_ONLY, CHINESE_ONLY, HIDDEN }

sealed interface PlayerSessionResult {
    data class Ready(val session: PlayerPlaybackSession) : PlayerSessionResult
    data class Failed(val message: String) : PlayerSessionResult
}

sealed interface PlayerUiEvent {
    data object TogglePlayback : PlayerUiEvent
    data class Seek(val positionMs: Long) : PlayerUiEvent
    data class VolumeChanged(val volume: Float) : PlayerUiEvent
    data class SpeedChanged(val speed: Float) : PlayerUiEvent
    data object ToggleFullscreen : PlayerUiEvent
    data class SetSubtitleMode(val mode: SubtitleDisplayMode) : PlayerUiEvent
    data class LookupWord(val surface: String, val normalized: String, val start: Int, val end: Int, val cue: PlayerSubtitleCue) : PlayerUiEvent
    data object CloseLookup : PlayerUiEvent
    data object AddLookupToVocabulary : PlayerUiEvent
    data object RetryAiExplanation : PlayerUiEvent
    data object ToggleSentenceLoop : PlayerUiEvent
    data object SetLoopA : PlayerUiEvent
    data object SetLoopB : PlayerUiEvent
    data object ClearAbLoop : PlayerUiEvent
    data object LifecycleStopped : PlayerUiEvent
    data object Retry : PlayerUiEvent
    data object Exit : PlayerUiEvent
}

sealed interface PlayerUiEffect {
    data object NavigateBack : PlayerUiEffect
    data class Message(val text: String) : PlayerUiEffect
}
