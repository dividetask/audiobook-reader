package com.dividetask.audiobookreader.data.repository

import com.dividetask.audiobookreader.data.db.dao.HistoryDao
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.db.entity.PositionJumpEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class HistoryRepository @Inject constructor(
    private val historyDao: HistoryDao
) {
    fun getSessions(bookId: Long): Flow<List<ListenSessionEntity>> = historyDao.getSessions(bookId)

    fun getJumps(bookId: Long): Flow<List<PositionJumpEntity>> = historyDao.getJumps(bookId)

    suspend fun insertSession(session: ListenSessionEntity): Long = historyDao.insertSession(session)

    suspend fun updateSessionEnd(id: Long, endedAt: Long, endPositionMs: Long) =
        historyDao.updateSessionEnd(id, endedAt, endPositionMs)

    /**
     * Records a jump. Rapid jumps (e.g. tapping skip several times) are merged into
     * one entry that keeps the original "from" position.
     */
    suspend fun recordJump(bookId: Long, fromPositionMs: Long, toPositionMs: Long, reason: String) {
        if (bookId < 0 || abs(toPositionMs - fromPositionMs) < 1_000) return
        val now = System.currentTimeMillis()
        val latest = historyDao.getLatestJump(bookId)
        if (latest != null && now - latest.timestamp < MERGE_WINDOW_MS) {
            historyDao.updateJump(latest.id, toPositionMs, reason, now)
        } else {
            historyDao.insertJump(
                PositionJumpEntity(
                    bookId = bookId,
                    timestamp = now,
                    fromPositionMs = fromPositionMs,
                    toPositionMs = toPositionMs,
                    reason = reason
                )
            )
        }
    }

    companion object {
        private const val MERGE_WINDOW_MS = 5_000L
    }
}
