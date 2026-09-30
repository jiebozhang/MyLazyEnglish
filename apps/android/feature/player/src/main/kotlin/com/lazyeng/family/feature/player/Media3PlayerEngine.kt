package com.lazyeng.family.feature.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class Media3PlayerEngine(context: Context) : PlayerEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exoPlayer = ExoPlayer.Builder(context.applicationContext).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        setHandleAudioBecomingNoisy(true)
        addListener(object : Media3Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = publish()
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish()
            override fun onPlayerError(error: PlaybackException) {
                mutableState.value = PlayerEngineState(
                    phase = PlayerPhase.ERROR,
                    positionMs = currentPosition.coerceAtLeast(0L),
                    durationMs = duration.takeIf { it != C.TIME_UNSET },
                    message = "视频文件无法打开，请检查文件是否仍可访问。",
                )
            }
        })
    }
    private val mutableState = MutableStateFlow(PlayerEngineState())
    override val player: Media3Player get() = exoPlayer
    override val state = mutableState.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                publish()
                delay(250L)
            }
        }
    }

    override fun prepare(uri: String, startPositionMs: Long) {
        mutableState.value = PlayerEngineState(phase = PlayerPhase.LOADING, positionMs = startPositionMs)
        exoPlayer.setMediaItem(MediaItem.fromUri(uri), startPositionMs.coerceAtLeast(0L))
        exoPlayer.prepare()
        exoPlayer.play()
    }

    override fun play() = exoPlayer.play()
    override fun pause() = exoPlayer.pause()
    override fun seekTo(positionMs: Long) = exoPlayer.seekTo(positionMs.coerceAtLeast(0L))
    override fun setVolume(volume: Float) { exoPlayer.volume = volume.coerceIn(0f, 1f) }
    override fun setSpeed(speed: Float) { exoPlayer.setPlaybackSpeed(speed.coerceIn(0.5f, 2f)) }

    override fun release() {
        scope.cancel()
        exoPlayer.release()
    }

    private fun publish() {
        if (mutableState.value.phase == PlayerPhase.ERROR) return
        val phase = when (exoPlayer.playbackState) {
            Media3Player.STATE_BUFFERING -> PlayerPhase.BUFFERING
            Media3Player.STATE_ENDED -> PlayerPhase.ENDED
            Media3Player.STATE_READY -> PlayerPhase.READY
            else -> PlayerPhase.LOADING
        }
        mutableState.value = PlayerEngineState(
            phase = phase,
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0L),
            durationMs = exoPlayer.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L),
            isPlaying = exoPlayer.isPlaying,
        )
    }
}
