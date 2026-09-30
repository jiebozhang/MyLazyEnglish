package com.lazyeng.family.spikes.safmedia3

import android.content.Intent
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.FileNotFoundException
import java.util.concurrent.Executors

private const val PREFS = "saf_media3_spike"
private const val URI_KEY = "persisted_video_uri"
private const val LOG_TAG = "SafMedia3Spike"

// Technical diagnostics are intentionally English and are not production UI copy.
@Suppress("SetTextI18n", "UseKtx")
class SafMedia3SpikeActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null
    private var currentUri: Uri? = null
    private val metadataWorker = Executors.newSingleThreadExecutor()
    private var requestGeneration = 0

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode != RESULT_OK || uri == null) {
            showError("SELECTION_CANCELLED: no media permission or data was stored")
            return@registerForActivityResult
        }
        try {
            val readFlags = data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, readFlags)
            check(getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(URI_KEY, uri.toString()).commit())
            currentUri = uri
            inspectAndPrepare(uri, restored = false)
        } catch (error: SecurityException) {
            showError("PERMISSION_NOT_PERSISTED: ${error.javaClass.simpleName}")
        } catch (error: Exception) {
            showError(classify(error))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 15f; setPadding(12, 12, 12, 12) }
        playerView = PlayerView(this).apply {
            useController = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            contentDescription = "Selected local video player"
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(status)
            addView(button("Choose local video") {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "video/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                picker.launch(intent)
            })
            addView(button("Read metadata") { currentUri?.let { inspect(it) } ?: showError("NO_URI: choose a video first") })
            addView(button("Play / resume") {
                player?.let {
                    if (it.playbackState == Player.STATE_ENDED) it.seekTo(0)
                    it.play()
                } ?: currentUri?.let { inspectAndPrepare(it, restored = true) }
                    ?: showError("NO_URI: choose a video first")
            })
            addView(button("Pause") {
                player?.let { pausedPlayer ->
                    pausedPlayer.pause()
                    val position = pausedPlayer.currentPosition
                    window.decorView.postDelayed({
                        if (player === pausedPlayer && !isDestroyed) {
                            recordStatus("PAUSE_OBSERVED beforeMs=$position; afterMs=${pausedPlayer.currentPosition}; playing=${pausedPlayer.isPlaying}")
                        }
                    }, 400)
                } ?: showError("NO_PLAYER: prepare a selected video first")
            })
            addView(button("Seek to 2 seconds") {
                player?.let { it.seekTo(2_000L.coerceAtMost(it.duration.coerceAtLeast(0))) }
                    ?: showError("NO_PLAYER: prepare a selected video first")
            })
            addView(playerView)
        }
        setContentView(root)
        recordStatus("SESSION pid=${android.os.Process.myPid()}")

        val saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(URI_KEY, null)
        if (saved != null) {
            currentUri = Uri.parse(saved)
            inspectAndPrepare(currentUri!!, restored = true)
        } else {
            status.text = "Ready. Choose a local video using the system picker."
        }
    }

    override fun onDestroy() {
        requestGeneration++
        metadataWorker.shutdownNow()
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }
    }

    private fun inspectAndPrepare(uri: Uri, restored: Boolean) {
        loadMetadata(uri) { metadata ->
            val persisted = contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            status.text = buildString {
                append(if (restored) "RESTORED_AFTER_PROCESS_RESTART\n" else "PERSISTED_URI_PERMISSION\n")
                append("Persisted read grant: $persisted\n")
                append("URI accessible without recopy: true\n")
                append(metadata)
            }
            recordStatus(status.text.toString())
            prepare(uri, autoplay = restored)
        }
    }

    private fun inspect(uri: Uri) {
        loadMetadata(uri) { metadata ->
            status.text = "METADATA_OK\n$metadata"
            recordStatus(status.text.toString())
        }
    }

    private fun loadMetadata(uri: Uri, onSuccess: (String) -> Unit) {
        val generation = ++requestGeneration
        status.text = "Reading selected document..."
        metadataWorker.execute {
            val result = runCatching { readMetadata(uri) }
            runOnUiThread {
                if (!isDestroyed && generation == requestGeneration) {
                    result.fold(onSuccess, { showError(classify(it)) })
                }
            }
        }
    }

    private fun readMetadata(uri: Uri): String {
        // Preserve provider access errors before retriever wraps them as IllegalArgumentException.
        contentResolver.openFileDescriptor(uri, "r")?.use { }
            ?: throw FileNotFoundException("Provider returned no file descriptor")
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val codec = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(descriptor.fileDescriptor)
                    (0 until extractor.trackCount).firstNotNullOfOrNull { index ->
                        extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME)
                            ?.takeIf { it.startsWith("video/") }
                    }
                } finally {
                    extractor.release()
                }
            } ?: throw FileNotFoundException("Provider returned no file descriptor")
            return "durationMs=$duration, resolution=${width}x$height, containerMime=$mime, videoCodec=$codec, rotation=$rotation"
        } finally {
            retriever.release()
        }
    }

    private fun prepare(uri: Uri, autoplay: Boolean) {
        try {
            val next = player ?: ExoPlayer.Builder(this).build().also {
                player = it
                playerView.player = it
                it.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        recordStatus("FIRST_FRAME_RENDERED positionMs=${it.currentPosition}")
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        recordStatus("IS_PLAYING=$isPlaying; positionMs=${it.currentPosition}")
                    }

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                            recordStatus("SEEK_OBSERVED fromMs=${oldPosition.positionMs}; toMs=${newPosition.positionMs}")
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val state = when (playbackState) {
                            Player.STATE_IDLE -> "IDLE"
                            Player.STATE_BUFFERING -> "BUFFERING"
                            Player.STATE_READY -> "READY"
                            Player.STATE_ENDED -> "ENDED"
                            else -> "UNKNOWN"
                        }
                        recordStatus("PLAYER_STATE=$state; playing=${it.isPlaying}")
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        showError("PLAYBACK_ERROR: ${error.errorCodeName}")
                    }
                })
            }
            next.setMediaItem(MediaItem.fromUri(uri))
            next.prepare()
            next.playWhenReady = autoplay
            if (autoplay) {
                status.append("\nPlayback requested; waiting for player callbacks")
                recordStatus("PLAYBACK_REQUESTED")
            }
        } catch (error: SecurityException) {
            showError(classify(error))
        } catch (error: Exception) {
            showError(classify(error))
        }
    }

    private fun classify(error: Throwable): String {
        val uri = currentUri
        val hasPersistedReadGrant = uri != null && contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return "${MediaAccessFailure.from(error, hasPersistedReadGrant)}: select an accessible video again"
    }

    private fun showError(message: String) {
        status.text = message
        recordStatus(message)
    }

    private fun recordStatus(message: String) {
        val sanitized = message.replace(Regex("(?i)content://[^\\s,]+"), "<uri>")
        openFileOutput("spike-report.txt", MODE_PRIVATE or MODE_APPEND).use { it.write((sanitized + "\n").toByteArray()) }
        Log.i(LOG_TAG, sanitized)
    }
}
