package com.dividetask.audiobookreader.audible.download

import android.content.Context
import android.net.Uri
import com.dividetask.audiobookreader.audible.auth.ActivationBytesProvider
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.metadata.ffprobeJson
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AaxImportStatus(
    val title: String,
    val step: String,
    /** True once the import has succeeded or failed; [step] is then the result message. */
    val finished: Boolean = false
)

/**
 * Imports an .aax file the user copied to the phone (e.g. downloaded from the
 * Audible website): copies it into app storage, matches it to the Audible library,
 * converts it with the account's activation bytes, and deletes the app's copy.
 * The user's original file is never touched.
 */
@Singleton
class AaxImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audibleBookDao: AudibleBookDao,
    private val bookRepository: BookRepository,
    private val converter: AaxConverter,
    private val activationBytesProvider: ActivationBytesProvider,
    private val debugLog: AppDebugLog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val _status = MutableStateFlow<AaxImportStatus?>(null)
    val status: StateFlow<AaxImportStatus?> = _status.asStateFlow()

    private val audiobooksDir: File
        get() = File(context.getExternalFilesDir(null), "audiobooks").apply { mkdirs() }

    // Separate from audiobooks/ so a leftover copy is never mistaken for a partial download
    private val importDir: File
        get() = File(context.getExternalFilesDir(null), "import").apply { mkdirs() }

    fun import(uri: Uri, displayName: String) {
        scope.launch { mutex.withLock { runImport(uri, displayName) } }
    }

    fun clearStatus() {
        if (_status.value?.finished == true) _status.value = null
    }

    private suspend fun runImport(uri: Uri, displayName: String) {
        debugLog.section("Import AAX: $displayName")
        var title = displayName.substringBeforeLast('.')
        importDir.listFiles()?.forEach { it.delete() }

        fun step(text: String) {
            debugLog.log("IMPORT", text)
            _status.value = AaxImportStatus(title, text)
        }
        fun fail(text: String) {
            debugLog.log("IMPORT", "FAILED: $text")
            _status.value = AaxImportStatus(title, "Import failed: $text", finished = true)
            importDir.listFiles()?.forEach { it.delete() }
        }

        try {
            step("Copying")
            val copy = File(importDir, "import.aax")
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy.outputStream().use { input.copyTo(it, 1 shl 20) }
            } ?: return fail("Could not open the file")
            debugLog.log("IMPORT", "Copied ${copy.length() / 1_048_576}MB")

            val tags = probeTags(copy)
            val match = matchToLibrary(tags.title, tags.artist, displayName)
            title = match?.title ?: tags.title ?: title
            debugLog.log("IMPORT", "Tags: title=${tags.title}, artist=${tags.artist}")
            debugLog.log("IMPORT", "Audible match: ${match?.let { "${it.title} (${it.asin})" } ?: "none"}")

            val baseName = match?.asin ?: "import_${System.currentTimeMillis()}"
            if (match != null && audiobooksDir.listFiles()?.any {
                    it.nameWithoutExtension == match.asin && it.extension != "aax"
                } == true) {
                return fail("\"$title\" is already in your library")
            }

            step("Getting activation bytes")
            val activationBytes = activationBytesProvider.getActivationBytes()
                ?: return fail("Could not get activation bytes from Audible")

            // The converter names its output after the input, so give the copy its final name
            val aax = File(importDir, "$baseName.aax")
            copy.renameTo(aax)

            step("Converting")
            val converted = converter.convert(aax, activationBytes)
                ?: return fail("Conversion failed — see the debug log")
            aax.delete()

            val dest = File(audiobooksDir, converted.name)
            if (!converted.renameTo(dest)) {
                converted.copyTo(dest, overwrite = true)
                converted.delete()
            }

            bookRepository.insertBook(
                BookEntity(
                    title = title,
                    author = match?.author ?: tags.artist ?: "Unknown Author",
                    coverUri = match?.coverUrl,
                    fileUri = Uri.fromFile(dest).toString(),
                    durationMs = tags.durationMs ?: ((match?.runtimeMinutes ?: 0) * 60_000L)
                ),
                emptyList()
            )
            debugLog.log("IMPORT", "Added to library: ${dest.name}")
            _status.value = AaxImportStatus(title, "Imported \"$title\"", finished = true)
        } catch (e: Exception) {
            fail("${e::class.simpleName}: ${e.message}")
        }
    }

    private data class Tags(val title: String?, val artist: String?, val durationMs: Long?)

    /** AAX metadata (title, author, duration) is readable without decrypting. */
    private fun probeTags(file: File): Tags {
        return try {
            val format = ffprobeJson("-show_format", file.absolutePath)?.optJSONObject("format")
                ?: return Tags(null, null, null)
            val tags = format.optJSONObject("tags")
            Tags(
                title = tags?.optString("title")?.takeIf { it.isNotBlank() },
                artist = tags?.optString("artist")?.takeIf { it.isNotBlank() },
                durationMs = format.optString("duration").toDoubleOrNull()?.times(1000)?.toLong()
            )
        } catch (e: Exception) {
            debugLog.log("IMPORT", "ffprobe failed: ${e.message}")
            Tags(null, null, null)
        }
    }

    /**
     * Matches by title (from the file's tags, then its name — website downloads are
     * named like "ThePlayerofGames_ep7.aax"), using the author to break ties.
     */
    private suspend fun matchToLibrary(tagTitle: String?, tagArtist: String?, fileName: String): AudibleBookEntity? {
        val library = audibleBookDao.getAllOnce()
        val fileTitle = fileName.substringBeforeLast('.').replace(Regex("_ep\\d+$", RegexOption.IGNORE_CASE), "")

        for (candidate in listOfNotNull(tagTitle, fileTitle)) {
            val key = normalize(candidate)
            if (key.isEmpty()) continue
            val exact = library.filter { normalize(it.title) == key }
            val matches = exact.ifEmpty {
                library.filter {
                    val t = normalize(it.title)
                    // Prefix matches only for longer titles, so "It" doesn't match "It Ends with Us"
                    t.length >= 8 && key.length >= 8 && (key.startsWith(t) || t.startsWith(key))
                }
            }
            if (matches.size == 1) return matches[0]
            if (matches.size > 1) {
                val author = tagArtist?.let(::normalize)
                return matches.firstOrNull { author != null && normalize(it.author) == author }
                    ?: matches.maxByOrNull { normalize(it.title).length }
            }
        }
        return null
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")
}
