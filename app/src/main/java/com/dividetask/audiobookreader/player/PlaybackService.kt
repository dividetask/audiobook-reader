package com.dividetask.audiobookreader.player

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.dividetask.audiobookreader.MainActivity
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.repository.HistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var bookRepository: BookRepository
    @Inject lateinit var historyRepository: HistoryRepository

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionSaveJob: Job? = null

    /** The listening span in progress; written to the DB once it lasts [MIN_SESSION_MS]. */
    private class ActiveSession(val bookId: Long, val startedAt: Long, val startPositionMs: Long) {
        var rowId: Long = -1
    }
    private var activeSession: ActiveSession? = null
    private val sessionMutex = Mutex()

    /** PlaybackController sets the book id as the media item's mediaId. */
    private val currentBookId: Long
        get() = mediaSession?.player?.currentMediaItem?.mediaId?.toLongOrNull() ?: -1

    @OptIn(UnstableApi::class)
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
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
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

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        endSession()
        saveCurrentPosition()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startPeriodicPositionSave() {
        positionSaveJob?.cancel()
        positionSaveJob = serviceScope.launch {
            while (true) {
                delay(30_000)
                saveCurrentPosition()
                writeSession(activeSession, mediaSession?.player?.currentPosition ?: continue)
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
        val player = mediaSession?.player ?: return
        val positionMs = player.currentPosition
        serviceScope.launch {
            val chapters = bookRepository.getChaptersOnce(bookId)
            val chapterIndex = chapters.indexOfLast { it.startPositionMs <= positionMs }.coerceAtLeast(0)
            bookRepository.updatePosition(bookId, positionMs, chapterIndex)
        }
    }

    private fun startSession() {
        val bookId = currentBookId
        if (bookId < 0) return
        val player = mediaSession?.player ?: return
        activeSession = ActiveSession(bookId, System.currentTimeMillis(), player.currentPosition)
    }

    private fun endSession(endPositionMs: Long? = null) {
        val session = activeSession ?: return
        activeSession = null
        val position = endPositionMs ?: mediaSession?.player?.currentPosition ?: return
        writeSession(session, position)
    }

    /** Inserts or updates the session row. Spans shorter than [MIN_SESSION_MS] are not recorded. */
    private fun writeSession(session: ActiveSession?, endPositionMs: Long) {
        session ?: return
        val now = System.currentTimeMillis()
        if (session.rowId < 0 && now - session.startedAt < MIN_SESSION_MS) return
        serviceScope.launch {
            sessionMutex.withLock {
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
            }
        }
    }

    companion object {
        private const val MIN_SESSION_MS = 10_000L
    }
}
