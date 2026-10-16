package com.dividetask.audiobookreader.data.repository

import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

interface BookRepository {
    fun getAllBooks(): Flow<List<BookEntity>>
    suspend fun getBookById(id: Long): BookEntity?
    suspend fun getBookByFileUri(fileUri: String): BookEntity?
    suspend fun insertBook(book: BookEntity, chapters: List<ChapterEntity>): Long
    suspend fun updatePosition(bookId: Long, positionMs: Long, chapterIndex: Int)
    suspend fun deleteBook(id: Long)
    suspend fun getAllBooksOnce(): List<BookEntity>
    suspend fun updateFileUri(bookId: Long, fileUri: String)
    fun getChapters(bookId: Long): Flow<List<ChapterEntity>>
    suspend fun getChaptersOnce(bookId: Long): List<ChapterEntity>
}
