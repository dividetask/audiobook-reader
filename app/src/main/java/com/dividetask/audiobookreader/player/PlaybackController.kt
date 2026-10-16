package com.dividetask.audiobookreader.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.google.common.util.concurrent.MoreExecutors
import dagger.hilt.android.qualifiers.ApplicationContext
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
    private val debugLog: AppDebugLog
) {
    private var mediaController: MediaController? = null

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
            .setUri(fileUri)
            .setMediaMetadata(metadata)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        controller.setMediaItem(mediaItem)
        controller.prepare()
        controller.seekTo(startPositionMs)
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

    fun seekTo(positionMs: Long) {
        debugLog.log("USER", "Seek to ${positionMs / 1000}s", com.dividetask.audiobookreader.debug.AppDebugLog.LogLevel.VERBOSE)
        mediaController?.seekTo(positionMs)
    }

    fun skipForward(ms: Long = 30_000) {
        val controller = mediaController ?: return
        debugLog.log("USER", "Skip forward ${ms / 1000}s")
        controller.seekTo(minOf(controller.currentPosition + ms, controller.duration))
    }

    fun skipBackward(ms: Long = 30_000) {
        val controller = mediaController ?: return
        debugLog.log("USER", "Skip backward ${ms / 1000}s")
        controller.seekTo(maxOf(controller.currentPosition - ms, 0))
    }

    fun setPlaybackSpeed(speed: Float) {
        debugLog.log("USER", "Speed: ${speed}x")
        mediaController?.setPlaybackSpeed(speed)
    }

    fun updateState() {
        val controller = mediaController ?: return
        _playbackState.value = _playbackState.value.copy(
            isPlaying = controller.isPlaying,
            currentPositionMs = controller.currentPosition,
            durationMs = controller.duration.coerceAtLeast(0),
            playbackSpeed = controller.playbackParameters.speed,
            currentMediaItemIndex = controller.currentMediaItemIndex,
            title = controller.mediaMetadata.title?.toString() ?: "",
            author = controller.mediaMetadata.artist?.toString() ?: ""
        )
    }

    fun getCurrentPosition(): Long = mediaController?.currentPosition ?: 0
    fun getDuration(): Long = mediaController?.duration?.coerceAtLeast(0) ?: 0
}
