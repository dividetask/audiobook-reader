package com.dividetask.audiobookreader.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity

/**
 * The player the MediaSession exposes to the system (lock screen, notification,
 * Bluetooth). It reports position and duration relative to the current chapter, so
 * the lock screen progress bar covers the chapter. The app itself reads the real
 * player through [PlayerHolder], so in-app positions stay absolute.
 *
 * Seeks pass through unchanged (absolute); the system controllers aren't allowed
 * to seek anyway.
 */
@OptIn(UnstableApi::class)
class ChapterProgressPlayer(private val player: Player) : ForwardingPlayer(player) {

    var chapters: List<ChapterEntity> = emptyList()
        set(value) {
            field = value
            notifyChapterChanged()
        }

    private val listeners = mutableListOf<Player.Listener>()
    private var lastChapterIndex = -1

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    fun chapterIndex(): Int =
        if (chapters.isEmpty()) -1
        else chapters.indexOfLast { it.startPositionMs <= player.currentPosition }.coerceAtLeast(0)

    /** Call periodically and after seeks; refreshes the system UI when the chapter changes. */
    fun checkChapter() {
        if (chapterIndex() != lastChapterIndex) notifyChapterChanged()
    }

    private fun range(): Pair<Long, Long>? {
        val index = chapterIndex()
        if (index < 0) return null
        val chapter = chapters[index]
        val start = chapter.startPositionMs
        val end = when {
            chapter.endPositionMs > start -> chapter.endPositionMs
            else -> chapters.getOrNull(index + 1)?.startPositionMs ?: player.duration
        }
        return if (end > start) start to end else null
    }

    private fun relative(absolute: Long): Long =
        range()?.let { (start, end) -> (absolute - start).coerceIn(0, end - start) } ?: absolute

    override fun getCurrentPosition(): Long = relative(super.getCurrentPosition())
    override fun getContentPosition(): Long = relative(super.getContentPosition())
    override fun getBufferedPosition(): Long = relative(super.getBufferedPosition())
    override fun getContentBufferedPosition(): Long = relative(super.getContentBufferedPosition())

    override fun getDuration(): Long =
        range()?.let { (start, end) -> end - start } ?: super.getDuration()

    override fun getContentDuration(): Long =
        range()?.let { (start, end) -> end - start } ?: super.getContentDuration()

    /**
     * The session only re-reads duration on metadata changes and position on
     * discontinuities, so fake both when the chapter changes.
     */
    private fun notifyChapterChanged() {
        lastChapterIndex = chapterIndex()
        val item = currentMediaItem
        val position = currentPosition
        val info = Player.PositionInfo(
            null, currentMediaItemIndex, item, null, currentPeriodIndex,
            position, position, C.INDEX_UNSET, C.INDEX_UNSET
        )
        listeners.toList().forEach {
            it.onMediaMetadataChanged(mediaMetadata)
            it.onPositionDiscontinuity(info, info, Player.DISCONTINUITY_REASON_INTERNAL)
        }
    }
}
