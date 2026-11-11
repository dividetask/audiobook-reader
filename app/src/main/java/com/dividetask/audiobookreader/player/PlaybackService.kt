package com.dividetask.audiobookreader.player

import android.app.PendingIntent
import android.database.sqlite.SQLiteConstraintException
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.dividetask.audiobookreader.MainActivity
import com.dividetask.audiobookreader.R
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.repository.HistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var bookRepository: BookRepository
    @Inject lateinit var historyRepository: HistoryRepository
    @Inject lateinit var playbackPreferences: PlaybackPreferences

    private var mediaSession: MediaSession? = null
    /** The real player. The session gets [chapterPlayer], which reports chapter-relative progress. */
    private var exoPlayer: ExoPlayer? = null
    private var chapterPlayer: ChapterProgressPlayer? = null
    private val playingBookId = MutableStateFlow(-1L)
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // DB writes run here, not in serviceScope: onDestroy cancels serviceScope, which
    // used to drop the final position save before it ran.
    private val persistScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var positionSaveJob: Job? = null

    /** The listening span in progress; written to the DB once it lasts [MIN_SESSION_MS]. */
    private class ActiveSession(val bookId: Long, val startedAt: Long, val startPositionMs: Long) {
        var rowId: Long = -1
    }
    private var activeSession: ActiveSession? = null
    private val sessionMutex = Mutex()

    /** PlaybackController sets the book id as the media item's mediaId. */
    private val currentBookId: Long
        get() = exoPlayer?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1

    @OptIn(UnstableApi::class)
    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    startSession()
                    startPeriodicPositionSave()
                } else {
                    stopPeriodicPositionSave()
                    endSession()
                    saveCurrentPosition()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // A seek splits the listening span so each session covers continuous audio.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && activeSession != null) {
                    endSession(oldPosition.positionMs)
                    if (player.isPlaying) startSession()
                }
                chapterPlayer?.checkChapter()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                playingBookId.value = currentBookId
                if (activeSession != null) {
                    endSession()
                    if (player.isPlaying) startSession()
                }
            }
        })

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionPlayer = ChapterProgressPlayer(player)
        exoPlayer = player
        chapterPlayer = sessionPlayer
        PlayerHolder.player = player

        mediaSession = MediaSession.Builder(this, sessionPlayer)
            .setSessionActivity(pendingIntent)
            .setCallback(SessionCallback())
            .setCustomLayout(skipButtons())
            .build()

        // Keep the lock screen's chapter list in step with the playing book
        serviceScope.launch {
            playingBookId
                .flatMapLatest { id -> if (id < 0) flowOf(emptyList()) else bookRepository.getChapters(id) }
                .collect { sessionPlayer.chapters = it }
        }
        // Deleting the playing book: stop, and drop the listening span so nothing is
        // written for a book that's gone (history rows must point at an existing book)
        serviceScope.launch {
            combine(playingBookId, bookRepository.getAllBooks()) { id, books -> id >= 0 && books.none { it.id == id } }
                .distinctUntilChanged()
                .collect { deleted -> if (deleted) stopDeletedBook() }
        }
        // The session doesn't notice chapter boundaries during normal playback
        serviceScope.launch {
            while (true) {
                delay(1_000)
                sessionPlayer.checkChapter()
            }
        }
        // Button labels show the configured seconds
        serviceScope.launch {
            combine(playbackPreferences.skipBackSeconds, playbackPreferences.skipForwardSeconds) { _, _ -> }
                .collect { mediaSession?.setCustomLayout(skipButtons()) }
        }
    }

    private fun skipButtons(): List<CommandButton> = listOf(
        CommandButton.Builder()
            .setDisplayName("Back ${playbackPreferences.skipBackSeconds.value}s")
            .setIconResId(R.drawable.ic_skip_back)
            .setSessionCommand(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
            .build(),
        CommandButton.Builder()
            .setDisplayName("Forward ${playbackPreferences.skipForwardSeconds.value}s")
            .setIconResId(R.drawable.ic_skip_forward)
            .setSessionCommand(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
            .build()
    )

    /**
     * System controllers (lock screen, notification, Bluetooth) get play/pause and
     * the two skip buttons only: no seeking, no previous/next, so the progress bar
     * can't be dragged and headphone next/previous do nothing. The app's own
     * controller keeps the default commands.
     */
    @OptIn(UnstableApi::class)
    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            if (controller.packageName == packageName && !session.isMediaNotificationController(controller)) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            }
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                    Player.COMMAND_SEEK_BACK,
                    Player.COMMAND_SEEK_FORWARD
                )
                .build()
            val sessionCommands = SessionCommands.Builder()
                .add(SessionCommand(COMMAND_SKIP_BACK, Bundle.EMPTY))
                .add(SessionCommand(COMMAND_SKIP_FORWARD, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(skipButtons())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                COMMAND_SKIP_BACK -> skip(forward = false)
                COMMAND_SKIP_FORWARD -> skip(forward = true)
                else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private fun skip(forward: Boolean) {
        val player = exoPlayer ?: return
        val seconds = if (forward) playbackPreferences.skipForwardSeconds.value else playbackPreferences.skipBackSeconds.value
        val from = player.currentPosition
        val duration = player.duration
        val target = (from + (if (forward) 1 else -1) * seconds * 1000L)
            .let { if (duration > 0) it.coerceIn(0, duration) else it.coerceAtLeast(0) }
        player.seekTo(target)
        val bookId = currentBookId
        val reason = if (forward) "Lock screen skip forward" else "Lock screen skip back"
        serviceScope.launch { historyRepository.recordJump(bookId, from, target, reason) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        endSession()
        saveCurrentPosition()
        PlayerHolder.player = null
        mediaSession?.release()
        exoPlayer?.release()
        mediaSession = null
        exoPlayer = null
        chapterPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopDeletedBook() {
        activeSession = null
        stopPeriodicPositionSave()
        exoPlayer?.run {
            clearMediaItems()
            stop()
        }
    }

    private fun startPeriodicPositionSave() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (true) {
                delay(30_000)
                saveCurrentPosition()
                writeSession(activeSession, exoPlayer?.currentPosition ?: continue)
            }
        }
    }

    private fun stopPeriodicPositionSave() {
        positionSaveJob?.cancel()
        positionSaveJob = null
    }

    private fun saveCurrentPosition() {
        val bookId = currentBookId
        if (bookId < 0) return
        val player = exoPlayer ?: return
        val positionMs = player.currentPosition
        persistScope.launch {
            val chapters = bookRepository.getChaptersOnce(bookId)
            val chapterIndex = chapters.indexOfLast { it.startPositionMs <= positionMs }.coerceAtLeast(0)
            bookRepository.updatePosition(bookId, positionMs, chapterIndex)
        }
    }

    private fun startSession() {
        val bookId = currentBookId
        if (bookId < 0) return
        val player = exoPlayer ?: return
        activeSession = ActiveSession(bookId, System.currentTimeMillis(), player.currentPosition)
    }

    private fun endSession(endPositionMs: Long? = null) {
        val session = activeSession ?: return
        activeSession = null
        val position = endPositionMs ?: exoPlayer?.currentPosition ?: return
        writeSession(session, position)
    }

    /** Inserts or updates the session row. Spans shorter than [MIN_SESSION_MS] are not recorded. */
    private fun writeSession(session: ActiveSession?, endPositionMs: Long) {
        session ?: return
        val now = System.currentTimeMillis()
        if (session.rowId < 0 && now - session.startedAt < MIN_SESSION_MS) return
        persistScope.launch {
            sessionMutex.withLock {
                try {
                    if (session.rowId < 0) {
                        session.rowId = historyRepository.insertSession(
                            ListenSessionEntity(
                                bookId = session.bookId,
                                startedAt = session.startedAt,
                                endedAt = now,
                                startPositionMs = session.startPositionMs,
                                endPositionMs = endPositionMs
                            )
                        )
                    } else {
                        historyRepository.updateSessionEnd(session.rowId, now, endPositionMs)
                    }
                } catch (e: SQLiteConstraintException) {
                    // The book was deleted since the span started
                }
            }
        }
    }

    companion object {
        private const val MIN_SESSION_MS = 10_000L
        private const val COMMAND_SKIP_BACK = "com.dividetask.audiobookreader.SKIP_BACK"
        private const val COMMAND_SKIP_FORWARD = "com.dividetask.audiobookreader.SKIP_FORWARD"
    }
}
