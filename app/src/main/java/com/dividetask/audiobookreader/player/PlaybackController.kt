package com.dividetask.audiobookreader.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dividetask.audiobookreader.data.repository.HistoryRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackState(
    val bookId: Long = -1,
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0,
    val durationMs: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val currentMediaItemIndex: Int = 0,
    val title: String = "",
    val author: String = ""
)

@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debugLog: AppDebugLog,
    private val playbackPreferences: PlaybackPreferences,
    private val historyRepository: HistoryRepository
) {
    private var mediaController: MediaController? = null

    /**
     * Positions and seeks go to the real player: the session's player reports
     * chapter-relative values for the lock screen (see ChapterProgressPlayer).
     * Falls back to the controller until the service has created the player.
     */
    private val player: Player?
        get() = PlayerHolder.player ?: mediaController
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    fun connect() {
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener({
            mediaController = controllerFuture.get()
            _isConnected.value = true
            mediaController?.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    debugLog.log("PLAY", "isPlaying: $isPlaying")
                    updateState()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    val stateName = when (state) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "READY"
                        Player.STATE_ENDED -> "ENDED"
                        else -> "UNKNOWN($state)"
                    }
                    debugLog.log("PLAY", "State: $stateName")
                    updateState()
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    debugLog.log("PLAY", "Media item: ${mediaItem?.mediaMetadata?.title}")
                    updateState()
                }

                override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                    updateState()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    debugLog.log("PLAY", "ERROR: ${error.errorCodeName} — ${error.message}")
                    debugLog.log("PLAY", "Cause: ${error.cause?.message}")
                }
            })
            updateState()
        }, MoreExecutors.directExecutor())
    }

    fun disconnect() {
        mediaController?.release()
        mediaController = null
        _isConnected.value = false
    }

    fun play(bookId: Long, fileUri: String, title: String, author: String, coverUri: String?, startPositionMs: Long) {
        val controller = mediaController ?: run {
            debugLog.log("PLAY", "ERROR: No media controller")
            return
        }
        debugLog.section("Playback: $title")
        debugLog.log("PLAY", "File: $fileUri")
        debugLog.log("PLAY", "Start position: ${startPositionMs / 1000}s")

        val file = java.io.File(Uri.parse(fileUri).path ?: "")
        debugLog.log("PLAY", "File exists: ${file.exists()}, size: ${file.length() / 1_048_576}MB")

        _playbackState.value = _playbackState.value.copy(bookId = bookId)

        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(author)
            .apply {
                if (coverUri != null) {
                    setArtworkUri(Uri.parse(coverUri))
                }
            }
            .build()

        val mimeType = when {
            fileUri.endsWith(".m4b") || fileUri.endsWith(".m4a") -> "audio/mp4"
            fileUri.endsWith(".mp3") -> "audio/mpeg"
            fileUri.endsWith(".ogg") -> "audio/ogg"
            fileUri.endsWith(".flac") -> "audio/flac"
            fileUri.endsWith(".aax") -> "audio/mp4"
            else -> null
        }
        debugLog.log("PLAY", "MIME type: $mimeType")

        val mediaItem = MediaItem.Builder()
            .setMediaId(bookId.toString())
            .setUri(fileUri)
            .setMediaMetadata(metadata)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        // Pause first so the service closes the previous book's listen session at the right position.
        if (controller.isPlaying) controller.pause()
        // Start position goes with the item: a separate seekTo right after setMediaItem
        // is dropped while the item is still loading (seek command not yet available),
        // which made books restart from the beginning.
        controller.setMediaItem(mediaItem, startPositionMs)
        controller.prepare()
        controller.play()
        debugLog.log("PLAY", "Play command sent")
    }

    fun togglePlayPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) {
            debugLog.log("USER", "Pause tapped")
            controller.pause()
        } else {
            debugLog.log("USER", "Play tapped")
            controller.play()
        }
    }

    /** Seeks and records the jump in the listen history so the user can get back. */
    fun seekTo(positionMs: Long, reason: String = "Seek") {
        val player = player ?: return
        val from = player.currentPosition
        val duration = player.duration
        val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        debugLog.log("USER", "$reason to ${target / 1000}s", AppDebugLog.LogLevel.VERBOSE)
        player.seekTo(target)
        updateState()
        val bookId = _playbackState.value.bookId
        scope.launch { historyRepository.recordJump(bookId, from, target, reason) }
    }

    fun skipForward() {
        val player = player ?: return
        val seconds = playbackPreferences.skipForwardSeconds.value
        seekTo(player.currentPosition + seconds * 1000L, "Skip forward")
    }

    fun skipBackward() {
        val player = player ?: return
        val seconds = playbackPreferences.skipBackSeconds.value
        seekTo(player.currentPosition - seconds * 1000L, "Skip back")
    }

    fun setPlaybackSpeed(speed: Float) {
        debugLog.log("USER", "Speed: ${speed}x")
        mediaController?.setPlaybackSpeed(speed)
    }

    fun updateState() {
        if (mediaController == null) return
        val player = player ?: return
        _playbackState.value = _playbackState.value.copy(
            isPlaying = player.isPlaying,
            currentPositionMs = player.currentPosition,
            durationMs = player.duration.coerceAtLeast(0),
            playbackSpeed = player.playbackParameters.speed,
            currentMediaItemIndex = player.currentMediaItemIndex,
            title = player.mediaMetadata.title?.toString() ?: "",
            author = player.mediaMetadata.artist?.toString() ?: ""
        )
    }

    fun getCurrentPosition(): Long = player?.currentPosition ?: 0
    fun getDuration(): Long = player?.duration?.coerceAtLeast(0) ?: 0
}
