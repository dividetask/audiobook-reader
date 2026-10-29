package com.dividetask.audiobookreader.audible.download

import android.content.Context
import android.net.Uri
import com.dividetask.audiobookreader.audible.auth.ActivationBytesProvider
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
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
    val finished: Boolean = false,
    /** 0..1 for the current step, or null when it can't be measured. */
    val progress: Float? = null
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
    private val pendingStore: PendingDownloadStore,
    private val debugLog: AppDebugLog,
    private val requestLog: com.dividetask.audiobookreader.debug.AudibleRequestLog
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
        scope.launch {
            mutex.withLock {
                runImport(displayName, knownAsin = null) { dir, step, progress ->
                    step("Copying")
                    val copy = File(dir, "part1.aax")
                    copyFromUri(uri, copy) { progress("Copying", it) }?.let { Fetched(error = it) } ?: Fetched(listOf(copy))
                }
            }
        }
    }

    /**
     * Downloads [asin] from the Audible website (when the API's download limit blocks it),
     * using the website session the in-app browser signed into, then imports it. [urls] are
     * the book's download links in order: one, or one per part for books Audible splits.
     */
    fun importFromWebsite(urls: List<String>, userAgent: String?, asin: String, title: String) {
        scope.launch {
            mutex.withLock {
                runImport("$title.aax", knownAsin = asin) { dir, step, progress ->
                    val files = mutableListOf<File>()
                    for ((i, url) in urls.withIndex()) {
                        val label = if (urls.size > 1) "Downloading part ${i + 1} of ${urls.size}" else "Downloading from Audible website"
                        step(label)
                        val part = File(dir, "part${i + 1}.aax")
                        downloadWithWebsiteCookies(url, userAgent, part) { progress(label, it) }
                            ?.let { return@runImport Fetched(error = it) }
                        files += part
                    }
                    Fetched(files)
                }
            }
        }
    }

    fun clearStatus() {
        if (_status.value?.finished == true) _status.value = null
    }

    /** The .aax file(s) of one book, in order, or why they couldn't be fetched. */
    private class Fetched(val files: List<File> = emptyList(), val error: String? = null)

    /**
     * [fetch] puts the book's .aax file(s) in the given folder, reporting steps and progress.
     * Each file is decrypted and converted; several parts are joined into one book, with
     * the parts' chapters laid end to end.
     */
    private suspend fun runImport(
        displayName: String,
        knownAsin: String?,
        fetch: suspend (File, (String) -> Unit, (String, Float) -> Unit) -> Fetched
    ) {
        debugLog.section("Import AAX: $displayName")
        var title = displayName.substringBeforeLast('.')
        importDir.listFiles()?.forEach { it.delete() }

        fun step(text: String) {
            debugLog.log("IMPORT", text)
            _status.value = AaxImportStatus(title, text)
        }
        var lastProgressAt = 0L
        fun progress(text: String, fraction: Float) {
            // Callbacks arrive many times a second; update the UI a few times a second
            val now = System.currentTimeMillis()
            if (now - lastProgressAt < 250 && fraction < 1f) return
            lastProgressAt = now
            _status.value = AaxImportStatus(title, text, progress = fraction)
        }
        fun fail(text: String) {
            debugLog.log("IMPORT", "FAILED: $text")
            _status.value = AaxImportStatus(title, "Import failed: $text", finished = true)
            importDir.listFiles()?.forEach { it.delete() }
        }

        try {
            val fetched = fetch(importDir, ::step, ::progress)
            fetched.error?.let { return fail(it) }
            val parts = fetched.files.ifEmpty { return fail("Nothing was downloaded") }
            debugLog.log("IMPORT", "Got ${parts.size} file(s), ${parts.sumOf { it.length() } / 1_048_576}MB")

            val tags = probeTags(parts[0])
            val match = knownAsin?.let { asin -> audibleBookDao.getAllOnce().firstOrNull { it.asin == asin } }
                ?: matchToLibrary(tags.title, tags.artist, displayName)
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

            val converted = mutableListOf<File>()
            for ((i, part) in parts.withIndex()) {
                // The converter names its output after the input, so give each part its final name
                val aax = File(importDir, if (parts.size == 1) "$baseName.aax" else "${baseName}_part${i + 1}.aax")
                part.renameTo(aax)
                val label = if (parts.size > 1) "Converting part ${i + 1} of ${parts.size}" else "Converting"
                step(label)
                converted += converter.convert(aax, activationBytes) { progress(label, it) }
                    ?: return fail("Conversion failed — see the debug log")
                aax.delete()
            }

            var chapters = emptyList<ChapterEntity>()
            val joined = if (converted.size == 1) converted[0] else {
                chapters = partChapters(converted)
                val total = converted.sumOf { durationMsOf(it) ?: 0L }
                val out = File(importDir, "$baseName.${converted[0].extension}")
                step("Joining ${converted.size} parts")
                if (!converter.concat(converted, out, total) { progress("Joining ${converted.size} parts", it) }) {
                    return fail("Joining the parts failed — see the debug log")
                }
                converted.forEach { it.delete() }
                out
            }

            val dest = File(audiobooksDir, joined.name)
            if (!joined.renameTo(dest)) {
                joined.copyTo(dest, overwrite = true)
                joined.delete()
            }

            bookRepository.insertBook(
                BookEntity(
                    title = title,
                    author = match?.author ?: tags.artist ?: "Unknown Author",
                    coverUri = match?.coverUrl,
                    fileUri = Uri.fromFile(dest).toString(),
                    durationMs = durationMsOf(dest) ?: tags.durationMs ?: ((match?.runtimeMinutes ?: 0) * 60_000L),
                    // A joined book's chapters come from its parts, not the file
                    chaptersChecked = chapters.isNotEmpty()
                ),
                chapters
            )
            debugLog.log("IMPORT", "Added to library: ${dest.name}${if (chapters.isNotEmpty()) ", ${chapters.size} chapters" else ""}")
            // No longer waiting on Audible's download limit
            match?.asin?.let { pendingStore.remove(it) }
            _status.value = AaxImportStatus(title, "Imported \"$title\"", finished = true)
        } catch (e: Exception) {
            fail("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun durationMsOf(file: File): Long? = try {
        ffprobeJson("-show_format", file.absolutePath)?.optJSONObject("format")
            ?.optString("duration")?.toDoubleOrNull()?.times(1000)?.toLong()
    } catch (e: Exception) {
        null
    }

    /** Each part's chapters, shifted by the length of the parts before it. */
    private fun partChapters(parts: List<File>): List<ChapterEntity> {
        val chapters = mutableListOf<ChapterEntity>()
        var offset = 0L
        for ((p, part) in parts.withIndex()) {
            val array = try {
                ffprobeJson("-show_chapters", part.absolutePath)?.optJSONArray("chapters")
            } catch (e: Exception) {
                null
            }
            val partLength = durationMsOf(part) ?: 0L
            if (array == null || array.length() == 0) {
                // No chapters in this part: make the whole part one chapter
                chapters += ChapterEntity(bookId = 0, title = "Part ${p + 1}", startPositionMs = offset,
                    endPositionMs = offset + partLength, index = chapters.size)
            } else {
                for (i in 0 until array.length()) {
                    val c = array.getJSONObject(i)
                    val name = c.optJSONObject("tags")?.optString("title").orEmpty()
                    chapters += ChapterEntity(
                        bookId = 0,
                        title = name.ifBlank { "Chapter ${chapters.size + 1}" },
                        startPositionMs = offset + ((c.optString("start_time").toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        endPositionMs = offset + ((c.optString("end_time").toDoubleOrNull() ?: 0.0) * 1000).toLong(),
                        index = chapters.size
                    )
                }
            }
            offset += partLength
        }
        return chapters
    }

    private fun copyFromUri(uri: Uri, dest: File, onProgress: (Float) -> Unit): String? {
        val size = fileSize(uri)
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> copyStream(input, output, size, onProgress) }
        } ?: return "Could not open the file"
        return null
    }

    /**
     * Fetches [url] with the in-app browser's cookies, following redirects by hand so each
     * host (audible.com, then the download server) gets its own cookies.
     */
    private fun downloadWithWebsiteCookies(url: String, userAgent: String?, dest: File, onProgress: (Float) -> Unit): String? {
        val cookies = android.webkit.CookieManager.getInstance()
        var current = url
        repeat(10) {
            val connection = java.net.URL(current).openConnection() as java.net.HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                cookies.getCookie(current)?.let { connection.setRequestProperty("Cookie", it) }
                userAgent?.let { connection.setRequestProperty("User-Agent", it) }
                val started = System.currentTimeMillis()
                val code = connection.responseCode
                debugLog.log("IMPORT", "GET ${current.substringBefore('?')}: $code")
                requestLog.request("website-download", "GET", current, code,
                    System.currentTimeMillis() - started, connection.contentLengthLong)
                when (code) {
                    in 300..399 -> {
                        val location = connection.getHeaderField("Location") ?: return "Redirect without a location"
                        current = java.net.URL(java.net.URL(current), location).toString()
                    }
                    200 -> {
                        val type = connection.contentType.orEmpty()
                        if (type.startsWith("text/") || "xml" in type) {
                            // An error page, e.g. <Message>…download individual parts.</Message>
                            val body = connection.inputStream.bufferedReader().use { it.readText().take(4000) }
                            val message = Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                            debugLog.log("IMPORT", "Not a book: $type ${body.take(300)}")
                            return message?.let { "Audible: $it" } ?: "Audible sent a web page instead of the book — sign in again"
                        }
                        connection.inputStream.use { input ->
                            dest.outputStream().use { output -> copyStream(input, output, connection.contentLengthLong, onProgress) }
                        }
                        // .aax files are MP4 containers: "ftyp" at byte 4
                        val header = ByteArray(8)
                        dest.inputStream().use { it.read(header) }
                        if (String(header, 4, 4, Charsets.US_ASCII) != "ftyp") return "The download isn't an .aax file"
                        return null
                    }
                    else -> {
                        val body = connection.errorStream?.bufferedReader()?.use { it.readText().take(4000) }.orEmpty()
                        debugLog.log("IMPORT", "HTTP $code body: ${body.take(500)}")
                        val message = Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                        return message?.let { "Audible (HTTP $code): $it" } ?: "Audible website returned HTTP $code"
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        return "Too many redirects"
    }

    private fun copyStream(input: java.io.InputStream, output: java.io.OutputStream, size: Long, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(1 shl 20)
        var copied = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            output.write(buffer, 0, n)
            copied += n
            if (size > 0) onProgress(copied.toFloat() / size)
        }
    }

    private fun fileSize(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
            ?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L

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
