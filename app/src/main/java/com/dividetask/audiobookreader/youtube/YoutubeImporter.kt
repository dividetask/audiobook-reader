package com.dividetask.audiobookreader.youtube

import android.content.Context
import android.net.Uri
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.download.AaxConverter
import com.dividetask.audiobookreader.audible.download.AaxImportStatus
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Adds a book from a YouTube link: reads the video's details with yt-dlp, identifies
 * the book in the Audible catalog from the title (and the description's "Author:" line),
 * downloads the audio, converts it to .m4b and adds it with the video's chapters.
 * Files are stored as `audiobooks/yt_{videoId}.m4b`.
 */
@Singleton
class YoutubeImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepository: BookRepository,
    private val converter: AaxConverter,
    private val audibleApi: AudibleApiClient,
    private val debugLog: AppDebugLog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var initialized = false
    private var updateChecked = false

    private val _status = MutableStateFlow<AaxImportStatus?>(null)
    val status: StateFlow<AaxImportStatus?> = _status.asStateFlow()

    private val audiobooksDir: File
        get() = File(context.getExternalFilesDir(null), "audiobooks").apply { mkdirs() }

    private val downloadDir: File
        get() = File(context.getExternalFilesDir(null), "youtube").apply { mkdirs() }

    fun import(url: String) {
        scope.launch { mutex.withLock { runImport(url.trim()) } }
    }

    fun clearStatus() {
        if (_status.value?.finished == true) _status.value = null
    }

    private suspend fun runImport(url: String) {
        debugLog.section("Import YouTube: $url")
        var title = "YouTube video"

        fun step(text: String) {
            debugLog.log("YT", text)
            _status.value = AaxImportStatus(title, text)
        }
        var lastProgressAt = 0L
        fun progress(text: String, fraction: Float) {
            val now = System.currentTimeMillis()
            if (now - lastProgressAt < 250 && fraction < 1f) return
            lastProgressAt = now
            _status.value = AaxImportStatus(title, text, progress = fraction)
        }
        fun fail(text: String) {
            debugLog.log("YT", "FAILED: $text")
            _status.value = AaxImportStatus(title, "Import failed: $text", finished = true)
            downloadDir.listFiles()?.forEach { it.delete() }
        }

        try {
            step("Starting yt-dlp")
            val ytdl = YoutubeDL.getInstance()
            if (!initialized) {
                ytdl.init(context)
                initialized = true
            }
            // YouTube changes often and breaks old yt-dlp builds, so update once per launch
            if (!updateChecked) {
                step("Updating yt-dlp")
                try {
                    val result = ytdl.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)
                    debugLog.log("YT", "yt-dlp update: $result (${ytdl.versionName(context)})")
                } catch (e: Exception) {
                    debugLog.log("YT", "yt-dlp update failed, using bundled version: ${e.message}")
                }
                updateChecked = true
            }

            step("Reading video details")
            val infoRequest = YoutubeDLRequest(url).apply {
                addOption("--dump-single-json")
                addOption("--no-playlist")
            }
            val infoResponse = ytdl.execute(infoRequest)
            val info = JSONObject(infoResponse.out.substring(infoResponse.out.indexOf('{')))
            val videoId = info.optString("id").ifBlank { return fail("yt-dlp returned no video id") }
            val videoTitle = info.optString("title").ifBlank { videoId }
            val description = info.optString("description")
            val durationMs = (info.optDouble("duration", 0.0) * 1000).toLong()
            title = videoTitle
            debugLog.log("YT", "Video: $videoTitle ($videoId), ${durationMs / 60_000} min, channel=${info.optString("channel")}")

            val dest = File(audiobooksDir, "yt_$videoId.m4b")
            if (dest.exists()) return fail("This video is already in your library")

            val described = parseDescription(description)
            val guess = parseTitle(videoTitle, described.author)
            debugLog.log("YT", "Parsed: title=\"${guess.title}\", author=${guess.author ?: described.author}")

            step("Identifying book")
            val match = findOnAudible(guess.title, guess.author ?: described.author, videoTitle, durationMs)
            debugLog.log("YT", "Audible match: ${match?.let { "${it.title} by ${it.author} (${it.asin}, ${it.runtimeMinutes} min)" } ?: "none"}")
            title = match?.title ?: guess.title

            step("Downloading")
            downloadDir.listFiles()?.forEach { it.delete() }
            val downloadRequest = YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                addOption("-o", "${downloadDir.absolutePath}/$videoId.%(ext)s")
            }
            ytdl.execute(downloadRequest, "yt_$videoId") { percent, _, _ ->
                if (percent >= 0) progress("Downloading", percent / 100f)
            }
            val audio = downloadDir.listFiles()
                ?.filter { it.nameWithoutExtension == videoId && it.length() > 0 }
                ?.maxByOrNull { it.length() }
                ?: return fail("Download produced no file")
            debugLog.log("YT", "Downloaded ${audio.name} (${audio.length() / 1_048_576}MB)")

            step("Converting")
            val converted = File(downloadDir, "yt_$videoId.m4b")
            if (!converter.convertToM4b(audio, converted, durationMs) { progress("Converting", it) }) {
                return fail("Conversion failed — see the debug log")
            }
            audio.delete()
            if (!converted.renameTo(dest)) {
                converted.copyTo(dest, overwrite = true)
                converted.delete()
            }

            step("Loading chapters")
            val chapters = youtubeChapters(info).ifEmpty { audibleChapters(match, durationMs) }
            debugLog.log("YT", "Chapters: ${chapters.size}")

            bookRepository.insertBook(
                BookEntity(
                    title = title,
                    author = match?.author ?: guess.author ?: described.author
                        ?: info.optString("channel").ifBlank { "Unknown Author" },
                    coverUri = match?.coverUrl ?: info.optString("thumbnail").ifBlank { null },
                    fileUri = Uri.fromFile(dest).toString(),
                    durationMs = durationMs,
                    // Nothing more to find in the file, so don't probe it again
                    chaptersChecked = chapters.isNotEmpty()
                ),
                chapters
            )
            debugLog.log("YT", "Added to library: ${dest.name}")
            _status.value = AaxImportStatus(title, "Imported \"$title\"", finished = true)
        } catch (e: Exception) {
            fail("${e::class.simpleName}: ${e.message?.lines()?.lastOrNull { it.isNotBlank() }}")
        }
    }

    // ---------------------------------------------------------------- Parsing

    private data class Described(val author: String?, val narrator: String?)

    /** Book uploads often list details as "Author: Blake Crouch" lines. */
    private fun parseDescription(description: String): Described {
        fun field(vararg names: String): String? = description.lineSequence()
            .map { it.trim() }
            .firstNotNullOfOrNull { line ->
                names.firstNotNullOfOrNull { name ->
                    Regex("^$name\\s*[:\\-]\\s*(.+)$", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.trim()
                }
            }?.takeIf { it.isNotBlank() }
        return Described(
            author = field("author", "authors", "written by"),
            narrator = field("narrator", "narrated by", "read by")
        )
    }

    private data class TitleGuess(val title: String, val author: String?)

    private val junk = Regex(
        "\\b(full|complete|unabridged|abridged|free|entire|whole|length)?\\s*(audio\\s*books?|audio\\s*book|audiolibro)\\b" +
            "|\\b(full|complete)\\s+(book|length|version)\\b|\\bunabridged\\b|\\bpart\\s*\\d+\\b",
        RegexOption.IGNORE_CASE
    )

    /**
     * "Dark Matter by Blake Crouch | Full Audiobook" → ("Dark Matter", "Blake Crouch").
     * Bracketed text and audiobook boilerplate are removed; [knownAuthor] (from the
     * description) is stripped from the title wherever it appears.
     */
    private fun parseTitle(raw: String, knownAuthor: String?): TitleGuess {
        var t = raw.replace(Regex("[\\[(【{][^\\])】}]*[\\])】}]"), " ")
        if (knownAuthor != null) t = t.replace(knownAuthor, " ", ignoreCase = true)
        // Of the "|"-separated parts, keep the one with the most left after removing boilerplate
        t = t.split('|', '•', '｜').map { it.replace(junk, " ") }.maxByOrNull { it.trim().length } ?: t
        var author: String? = null
        Regex("^(.+?)\\s+(?:by|written by|from)\\s+(.+)$", RegexOption.IGNORE_CASE).find(t)?.let {
            t = it.groupValues[1]
            author = it.groupValues[2].trim(' ', '-', '–', '—', ',', ':').takeIf(String::isNotBlank)
        }
        val title = t.replace(Regex("\\s+"), " ").trim(' ', '-', '–', '—', ',', ':', '/').ifBlank { raw }
        return TitleGuess(title, author)
    }

    // ---------------------------------------------------------------- Audible

    private data class Match(
        val asin: String,
        val title: String,
        val author: String,
        val runtimeMinutes: Int?,
        val coverUrl: String?
    )

    /**
     * Searches the Audible catalog and picks the result whose title appears in the video
     * title, preferring the author from the video and a length close to the video's.
     */
    private suspend fun findOnAudible(title: String, author: String?, videoTitle: String, durationMs: Long): Match? {
        val params = mapOf(
            "keywords" to listOfNotNull(title, author).joinToString(" "),
            "num_results" to "10",
            "products_sort_by" to "Relevance",
            "response_groups" to "contributors,media,product_attrs",
            "image_sizes" to "500,1024"
        )
        val result = audibleApi.getCatalog("products", params)
        result.debugLog.forEach { debugLog.log("YT", it) }
        val products = result.data?.get("products").arr().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            Match(
                asin = o["asin"].str() ?: return@mapNotNull null,
                title = o["title"].str() ?: return@mapNotNull null,
                author = o["authors"].arr().mapNotNull { (it as? JsonObject)?.get("name").str() }.joinToString(", "),
                runtimeMinutes = o["runtime_length_min"].str()?.toDoubleOrNull()?.toInt(),
                coverUrl = (o["product_images"] as? JsonObject)?.let { it["1024"].str() ?: it["500"].str() }
            )
        }
        if (products.isEmpty()) return null

        val haystack = normalize(videoTitle) + normalize(title)
        val wantedAuthor = author?.let(::normalize)
        return products
            .filter { normalize(it.title).length >= 3 && haystack.contains(normalize(it.title)) }
            .maxByOrNull { m ->
                var score = normalize(m.title).length.toDouble()
                if (wantedAuthor != null && normalize(m.author).contains(wantedAuthor)) score += 100
                val runtime = m.runtimeMinutes
                if (runtime != null && durationMs > 0 && closeLength(runtime, durationMs)) score += 50
                score
            }
            // Title written differently on YouTube: accept the author's book of the same length
            ?: products.firstOrNull { m ->
                wantedAuthor != null && normalize(m.author).contains(wantedAuthor) &&
                    m.runtimeMinutes?.let { closeLength(it, durationMs) } == true
            }
    }

    /** Within 2% (or 3 minutes) — the same recording, so Audible's chapter times fit. */
    private fun closeLength(runtimeMinutes: Int, durationMs: Long): Boolean {
        val diff = abs(runtimeMinutes * 60_000L - durationMs)
        return diff <= maxOf(durationMs / 50, 180_000L)
    }

    private fun youtubeChapters(info: JSONObject): List<ChapterEntity> {
        val array = info.optJSONArray("chapters") ?: return emptyList()
        return (0 until array.length()).map { i ->
            val c = array.getJSONObject(i)
            ChapterEntity(
                bookId = 0,
                title = c.optString("title").ifBlank { "Chapter ${i + 1}" },
                startPositionMs = (c.optDouble("start_time", 0.0) * 1000).toLong(),
                endPositionMs = (c.optDouble("end_time", 0.0) * 1000).toLong(),
                index = i
            )
        }
    }

    /** Uses Audible's chapter list only when the video is the same length as the Audible edition. */
    private suspend fun audibleChapters(match: Match?, durationMs: Long): List<ChapterEntity> {
        val runtime = match?.runtimeMinutes ?: return emptyList()
        if (!closeLength(runtime, durationMs)) {
            debugLog.log("YT", "Length differs from Audible ($runtime min) — not using its chapters")
            return emptyList()
        }
        val result = audibleApi.getChapters(match.asin)
        result.debugLog.forEach { debugLog.log("YT", it) }
        return result.data.orEmpty().sortedBy { it.startOffsetMs }.mapIndexed { i, c ->
            ChapterEntity(
                bookId = 0,
                title = c.title.ifBlank { "Chapter ${i + 1}" },
                startPositionMs = c.startOffsetMs,
                endPositionMs = minOf(c.startOffsetMs + c.lengthMs, durationMs),
                index = i
            )
        }.filter { it.startPositionMs < durationMs }
    }

    private fun normalize(s: String): String = s.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}
