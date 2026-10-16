package com.dividetask.audiobookreader.data.repository

import com.dividetask.audiobookreader.data.db.dao.BookDao
import com.dividetask.audiobookreader.data.db.dao.ChapterDao
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookRepositoryImpl @Inject constructor(
    private val bookDao: BookDao,
    private val chapterDao: ChapterDao
) : BookRepository {

    override fun getAllBooks(): Flow<List<BookEntity>> = bookDao.getAll()

    override suspend fun getBookById(id: Long): BookEntity? = bookDao.getById(id)

    override suspend fun getBookByFileUri(fileUri: String): BookEntity? = bookDao.getByFileUri(fileUri)

    override suspend fun insertBook(book: BookEntity, chapters: List<ChapterEntity>): Long {
        val bookId = bookDao.insert(book)
        if (chapters.isNotEmpty()) {
            chapterDao.insertAll(chapters.map { it.copy(bookId = bookId) })
        }
        return bookId
    }

    override suspend fun updatePosition(bookId: Long, positionMs: Long, chapterIndex: Int) {
        bookDao.updatePosition(bookId, positionMs, chapterIndex)
    }

    override suspend fun getAllBooksOnce(): List<BookEntity> = bookDao.getAllOnce()

    override suspend fun updateFileUri(bookId: Long, fileUri: String) = bookDao.updateFileUri(bookId, fileUri)

    override suspend fun deleteBook(id: Long) {
        bookDao.delete(id)
    }

    override fun getChapters(bookId: Long): Flow<List<ChapterEntity>> = chapterDao.getByBookId(bookId)

    override suspend fun getChaptersOnce(bookId: Long): List<ChapterEntity> = chapterDao.getByBookIdOnce(bookId)

    override suspend fun replaceChapters(bookId: Long, chapters: List<ChapterEntity>) {
        chapterDao.deleteByBookId(bookId)
        chapterDao.insertAll(chapters.map { it.copy(id = 0, bookId = bookId) })
    }

    override suspend fun setChaptersChecked(bookId: Long) = bookDao.setChaptersChecked(bookId)
}
