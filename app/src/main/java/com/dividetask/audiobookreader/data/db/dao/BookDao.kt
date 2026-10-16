package com.dividetask.audiobookreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastPlayed DESC, dateAdded DESC")
    fun getAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Query("UPDATE books SET currentPositionMs = :positionMs, currentChapterIndex = :chapterIndex, lastPlayed = :lastPlayed WHERE id = :bookId")
    suspend fun updatePosition(bookId: Long, positionMs: Long, chapterIndex: Int, lastPlayed: Long = System.currentTimeMillis())

    @Query("UPDATE books SET fileUri = :fileUri WHERE id = :bookId")
    suspend fun updateFileUri(bookId: Long, fileUri: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM books ORDER BY lastPlayed DESC")
    suspend fun getAllOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE fileUri = :fileUri LIMIT 1")
    suspend fun getByFileUri(fileUri: String): BookEntity?
}
