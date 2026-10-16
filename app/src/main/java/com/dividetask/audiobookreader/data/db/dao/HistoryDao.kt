package com.dividetask.audiobookreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.db.entity.PositionJumpEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert
    suspend fun insertSession(session: ListenSessionEntity): Long

    @Query("UPDATE listen_sessions SET endedAt = :endedAt, endPositionMs = :endPositionMs WHERE id = :id")
    suspend fun updateSessionEnd(id: Long, endedAt: Long, endPositionMs: Long)

    @Query("SELECT * FROM listen_sessions WHERE bookId = :bookId ORDER BY startedAt DESC LIMIT 200")
    fun getSessions(bookId: Long): Flow<List<ListenSessionEntity>>

    @Insert
    suspend fun insertJump(jump: PositionJumpEntity): Long

    @Query("SELECT * FROM position_jumps WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestJump(bookId: Long): PositionJumpEntity?

    @Query("UPDATE position_jumps SET toPositionMs = :toPositionMs, reason = :reason, timestamp = :timestamp WHERE id = :id")
    suspend fun updateJump(id: Long, toPositionMs: Long, reason: String, timestamp: Long)

    @Query("SELECT * FROM position_jumps WHERE bookId = :bookId ORDER BY timestamp DESC LIMIT 200")
    fun getJumps(bookId: Long): Flow<List<PositionJumpEntity>>
}
