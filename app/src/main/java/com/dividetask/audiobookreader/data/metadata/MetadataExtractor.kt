package com.dividetask.audiobookreader.data.metadata

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BookMetadata(
    val title: String,
    val author: String,
    val durationMs: Long,
    val coverUri: String?,
    val chapters: List<ChapterMetadata>
)

data class ChapterMetadata(
    val title: String,
    val startPositionMs: Long,
    val endPositionMs: Long,
    val index: Int
)

@Singleton
class MetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun extract(uri: Uri): BookMetadata {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)

            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: uri.lastPathSegment?.substringBeforeLast('.') ?: "Unknown Title"
            val author = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?: "Unknown Author"
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val coverUri = extractCoverArt(retriever, uri)

            return BookMetadata(
                title = title,
                author = author,
                durationMs = durationMs,
                coverUri = coverUri,
                chapters = emptyList()
            )
        } finally {
            retriever.release()
        }
    }

    private fun extractCoverArt(retriever: MediaMetadataRetriever, sourceUri: Uri): String? {
        val art = retriever.embeddedPicture ?: return null
        val coversDir = File(context.filesDir, "covers")
        coversDir.mkdirs()
        val coverFile = File(coversDir, "${sourceUri.hashCode()}.jpg")
        FileOutputStream(coverFile).use { it.write(art) }
        return Uri.fromFile(coverFile).toString()
    }

    fun toBookEntity(uri: Uri, metadata: BookMetadata): BookEntity {
        return BookEntity(
            title = metadata.title,
            author = metadata.author,
            coverUri = metadata.coverUri,
            fileUri = uri.toString(),
            durationMs = metadata.durationMs
        )
    }

    fun toChapterEntities(bookId: Long, metadata: BookMetadata): List<ChapterEntity> {
        return metadata.chapters.map { chapter ->
            ChapterEntity(
                bookId = bookId,
                title = chapter.title,
                startPositionMs = chapter.startPositionMs,
                endPositionMs = chapter.endPositionMs,
                index = chapter.index
            )
        }
    }
}
