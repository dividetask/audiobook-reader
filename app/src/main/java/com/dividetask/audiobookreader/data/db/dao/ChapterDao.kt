package com.dividetask.audiobookreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    fun getByBookId(bookId: Long): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY `index`")
    suspend fun getByBookIdOnce(bookId: Long): List<ChapterEntity>

    @Insert
    suspend fun insertAll(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}
