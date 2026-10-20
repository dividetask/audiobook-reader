package com.dividetask.audiobookreader.data.metadata

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills in a book's chapter list without re-downloading audio: first by probing the
 * local file with ffprobe, then (for Audible books, named `{asin}.m4b`) from the
 * Audible content metadata API.
 */
@Singleton
class ChapterLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val apiClient: AudibleApiClient,
    private val debugLog: AppDebugLog
) {
    private val asinPattern = Regex("^[A-Z0-9]{10}$")

    suspend fun ensureChapters(book: BookEntity) = withContext(Dispatchers.IO) {
        if (book.chaptersChecked || book.fileUri.endsWith(".aax")) return@withContext
        if (bookRepository.getChaptersOnce(book.id).isNotEmpty()) {
            bookRepository.setChaptersChecked(book.id)
            return@withContext
        }

        debugLog.section("Chapters: ${book.title}")
        val uri = Uri.parse(book.fileUri)
        var chapters = readFromFile(uri)
        var definitive = true

        if (chapters.isEmpty()) {
            val asin = uri.path?.let { File(it).nameWithoutExtension }?.takeIf { asinPattern.matches(it) }
            if (asin != null) {
                debugLog.log("CHAP", "No chapters in file, asking Audible ($asin)")
                val result = apiClient.getChapters(asin)
                result.debugLog.forEach { debugLog.log("CHAP", it) }
                val audible = result.data
                if (audible == null) {
                    // Network or auth failure — try again next time the book is opened.
                    definitive = false
                } else {
                    chapters = audible.sortedBy { it.startOffsetMs }.mapIndexed { i, c ->
                        ChapterEntity(
                            bookId = book.id,
                            title = c.title.ifBlank { "Chapter ${i + 1}" },
                            startPositionMs = c.startOffsetMs,
                            endPositionMs = c.startOffsetMs + c.lengthMs,
                            index = i
                        )
                    }
                }
            }
        }

        if (chapters.isNotEmpty()) {
            bookRepository.replaceChapters(book.id, chapters)
        }
        debugLog.log("CHAP", "Found ${chapters.size} chapters")
        if (definitive) bookRepository.setChaptersChecked(book.id)
    }

    private fun readFromFile(uri: Uri): List<ChapterEntity> {
        val input = when (uri.scheme) {
            "content" -> FFmpegKitConfig.getSafParameterForRead(context, uri)
            else -> uri.path ?: return emptyList()
        }
        return try {
            val array = ffprobeJson("-show_chapters", input)?.optJSONArray("chapters")
                ?: return emptyList()

            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val title = obj.optJSONObject("tags")?.optString("title").orEmpty()
                ChapterEntity(
                    bookId = 0,
                    title = title.ifBlank { "Chapter ${i + 1}" },
                    startPositionMs = (obj.optString("start_time").toDoubleOrNull() ?: 0.0).times(1000).toLong(),
                    endPositionMs = (obj.optString("end_time").toDoubleOrNull() ?: 0.0).times(1000).toLong(),
                    index = i
                )
            }
        } catch (e: Exception) {
            debugLog.log("CHAP", "ffprobe failed: ${e.message}")
            emptyList()
        }
    }
}
