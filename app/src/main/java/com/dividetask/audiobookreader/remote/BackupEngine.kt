package com.dividetask.audiobookreader.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.api.toEntity
import com.dividetask.audiobookreader.audible.auth.ActivationBytesProvider
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.audible.download.AaxConverter
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.series.SeriesRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupPhase { IDLE, CHECKING, DOWNLOADING, CONVERTING, UPLOADING, CANCELLING }

data class BackupStatus(
    val phase: BackupPhase = BackupPhase.IDLE,
    val title: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val remaining: Int = 0,
    /** Server path being uploaded (relative to the folder), "" when not uploading. */
    val path: String = ""
)

sealed class BackupOutcome {
    /** Nothing left to back up. */
    data object AllDone : BackupOutcome()
    /** Stopped after the requested number of books; more remain. */
    data object MoreRemaining : BackupOutcome()
    /** Couldn't run (server unreachable, not signed in...). [retry] = worth trying again later. */
    data class Failed(val message: String, val retry: Boolean) : BackupOutcome()
}

/**
 * Backs up books to the SFTP server in two passes:
 * - phone copies: every book on the phone not yet on the server is uploaded (and kept);
 * - Audible: books not on the phone are downloaded → converted → uploaded → the
 *   phone copy deleted, one at a time.
 */
@Singleton
class BackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ServerSettings,
    private val storage: RemoteStorage,
    private val apiClient: AudibleApiClient,
    private val httpClient: HttpClient,
    private val converter: AaxConverter,
    private val activationBytesProvider: ActivationBytesProvider,
    private val audibleBookDao: AudibleBookDao,
    private val tokenStore: AudibleTokenStore,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val websiteBackup: WebsiteBackupNotifier,
    private val libroBookDao: com.dividetask.audiobookreader.data.db.dao.LibroBookDao,
    private val importer: dagger.Lazy<com.dividetask.audiobookreader.audible.download.AaxImporter>,
    private val debugLog: AppDebugLog
) {
    private val mutex = Mutex()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Unfinished uploads on the server this phone won't continue, waiting for the user to decide. */
    private val _abandoned = MutableStateFlow<List<UnfinishedUpload>>(emptyList())
    val abandoned: StateFlow<List<UnfinishedUpload>> = _abandoned.asStateFlow()

    /** Books waiting to be uploaded in this run, after the current one: title → server path. */
    private val _queue = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val queue: StateFlow<List<Pair<String, String>>> = _queue.asStateFlow()

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val workDir: File
        get() = File(context.getExternalFilesDir(null), "backup_work").apply { mkdirs() }

    /** Where the Server tab saves downloads; those are already on the server. */
    private val serverDownloadsDir: File
        get() = File(context.getExternalFilesDir(null), "remote_books")

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Server backup", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** Uploads every book on the phone that isn't on the server yet. Never contacts Audible. */
    suspend fun runLocal(): BackupOutcome = locked { runLocalLocked() }

    /**
     * Downloads, converts and uploads up to [maxBooks] Audible books that aren't on the phone
     * or server. [force] ("Back up now") asks Audible even while backing off after a refusal.
     */
    suspend fun runAudible(maxBooks: Int, force: Boolean = false, asin: String? = null): BackupOutcome =
        locked { runAudibleLocked(maxBooks, force, asin) }

    /** Cancels the unfinished uploads the user chose to remove. */
    suspend fun cancelAbandoned() {
        val list = _abandoned.value
        _abandoned.value = emptyList()
        if (list.isEmpty()) return
        locked {
            debugLog.section("Server Backup (cancel unfinished uploads)")
            var cancelled = 0
            try {
                storage.use { conn ->
                    for ((i, u) in list.withIndex()) {
                        setStatus(BackupStatus(BackupPhase.CANCELLING, u.relPath, i.toLong(), list.size.toLong(), list.size - i))
                        conn.abortUpload(u)
                        cancelled++
                        debugLog.log("BACKUP", "Cancelled unfinished upload: ${u.relPath}")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                debugLog.log("BACKUP", "Cancelling unfinished uploads failed: ${e.message}")
            }
            debugLog.log("BACKUP", "Cancelled $cancelled of ${list.size} unfinished uploads")
            BackupOutcome.AllDone
        }
    }

    /** Keeps these unfinished uploads on the server and doesn't ask about them again. */
    fun keepAbandoned() {
        val list = _abandoned.value
        _abandoned.value = emptyList()
        settings.keptUploads = settings.keptUploads + list.map { it.uploadId }
        list.forEach { debugLog.log("BACKUP", "Keeping unfinished upload: ${it.relPath}") }
    }

    /**
     * Finds unfinished uploads this phone won't continue: for a path a book of ours uses, every one
     * except the one [S3Connection.upload] would pick up; for any other path, all of them. Each is
     * logged; the ones not already kept are offered to the user to cancel.
     */
    private fun checkAbandoned(expectedBases: Set<String>) {
        val all = try {
            storage.use { it.unfinishedUploads() }
        } catch (e: Exception) {
            debugLog.log("BACKUP", "Could not list unfinished uploads: ${e.message}")
            return
        }
        val abandoned = all.groupBy { it.relPath }.flatMap { (rel, uploads) ->
            if (rel.substringBeforeLast('.').lowercase() !in expectedBases) return@flatMap uploads
            val resumed = uploads.firstOrNull { it.current } ?: uploads.maxByOrNull { it.initiated }
            uploads.filter { it !== resumed }
        }
        val resumable = all.size - abandoned.size
        if (resumable > 0) debugLog.log("BACKUP", "$resumable unfinished upload(s) will be continued")
        val kept = settings.keptUploads
        for (u in abandoned) {
            val note = if (u.uploadId in kept) " (kept)" else ""
            debugLog.log("BACKUP", "Unfinished upload can't be continued$note: ${u.relPath}, " +
                "${u.size / 1_048_576}MB, started ${u.initiated}")
        }
        // Forget kept IDs that are gone from the server
        settings.keptUploads = kept.intersect(all.map { it.uploadId }.toSet())
        _abandoned.value = abandoned.filter { it.uploadId !in kept }
    }

    private suspend fun locked(block: suspend () -> BackupOutcome): BackupOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block()
            } finally {
                _queue.value = emptyList()
                _status.value = BackupStatus()
                notificationManager.cancel(NOTIFICATION_ID)
            }
        }
    }

    /** A book on the phone, the file to upload, and where it goes on the server. */
    private data class LocalCopy(val book: BookEntity, val file: File, val remoteBase: String, val asin: String?)

    /**
     * Books in the library whose file is on the phone. Files that came from the
     * server and AAX files (still DRM-protected) are left out.
     */
    private suspend fun localCopies(audibleByAsin: Map<String, AudibleBookEntity>): List<LocalCopy> {
        var outside = 0
        val copies = bookRepository.getAllBooksOnce().mapNotNull { book ->
            val uri = Uri.parse(book.fileUri)
            if (uri.scheme != "file") { outside++; return@mapNotNull null }
            val file = File(uri.path ?: return@mapNotNull null)
            if (!file.exists() || file.length() < 1000 || file.extension.equals("aax", true)) return@mapNotNull null
            if (file.absolutePath.startsWith(serverDownloadsDir.absolutePath)) return@mapNotNull null
            val asin = book.asin?.takeIf { it.isNotEmpty() }
                ?: file.nameWithoutExtension.takeIf { it in audibleByAsin }
            // Audible books use the Audible title/author, so both passes agree on the name
            val base = asin?.let { audibleByAsin[it] }?.let { remoteBase(it) }
                ?: "${safeName(book.author)}/${safeName(book.title)}"
            LocalCopy(book, file, base, asin)
        }
        if (outside > 0) debugLog.log("BACKUP", "$outside book(s) stored outside the app can't be backed up")
        return copies
    }

    private fun serverPaths(): Set<String> =
        storage.use { conn -> conn.listAudio() }.map { it.relPath.substringBeforeLast('.').lowercase() }.toSet()

    private suspend fun runLocalLocked(): BackupOutcome {
        debugLog.section("Server Backup (phone copies)")
        if (!settings.isConfigured) return finish(BackupOutcome.Failed("Server not set up", retry = false))
        pausedOutcome("Phone copies")?.let { return it }
        setStatus(BackupStatus(BackupPhase.CHECKING, "Checking phone copies"))

        val audibleByAsin = audibleBookDao.getAllOnce().associateBy { it.asin }
        var done = 0
        var failed = 0
        val tried = mutableSetOf<Long>()
        // Books added while uploading are picked up by the next round
        while (true) {
            val onServer = try {
                serverPaths()
            } catch (e: ServerException) {
                return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = !e.settingsProblem), prefix = "Phone copies")
            }
            val failures = settings.failures
            val pending = localCopies(audibleByAsin).filter {
                it.book.id !in tried && it.remoteBase.lowercase() !in onServer &&
                    (failures[localKey(it.book)] ?: 0) < MAX_FAILURES
            }.sortedByDescending {
                // Finish an upload already started before starting another
                settings.hasUnfinishedUpload("${it.remoteBase}.${it.file.extension}")
            }
            if (pending.isEmpty()) break
            _queue.value = pending.map { it.book.title to "${it.remoteBase}.${it.file.extension}" }
            debugLog.log("BACKUP", "${pending.size} phone copies to upload")
            for ((i, copy) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                tried += copy.book.id
                _queue.value = pending.drop(i + 1).map { it.book.title to "${it.remoteBase}.${it.file.extension}" }
                val key = localKey(copy.book)
                try {
                    upload(copy.file, copy.remoteBase, copy.book.title, pending.size - i)
                    done++
                    settings.failures = settings.failures - key
                    debugLog.log("BACKUP", "Uploaded: ${copy.book.title}")
                    // Downloaded from the website only to back it up: the phone copy goes, as for Audible backups
                    if (copy.asin != null && copy.asin in settings.websiteBackupAsins) {
                        removePhoneCopy(copy)
                        settings.websiteBackupAsins = settings.websiteBackupAsins - copy.asin
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (e is ServerException && e.dailyLimit) return dailyLimitReached(e, copy.book.title, "Phone copies")
                    // Wrong settings aren't the book's fault: don't count it towards skipping the book
                    if (e is ServerException && e.settingsProblem) {
                        return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = false), prefix = "Phone copies")
                    }
                    // Lost connection: stop and retry later; uploads resume where they stopped
                    if (isConnectionProblem(e)) {
                        debugLog.log("BACKUP", "Connection lost uploading ${copy.book.title}: ${e::class.simpleName}: ${e.message}")
                        return finish(BackupOutcome.Failed("connection lost — will retry (${e.message})", retry = true), prefix = "Phone copies")
                    }
                    failed++
                    val count = (settings.failures[key] ?: 0) + 1
                    settings.failures = settings.failures + (key to count)
                    debugLog.log("BACKUP", "Failed (${count}/$MAX_FAILURES): ${copy.book.title} — ${e::class.simpleName}: ${e.message}")
                }
            }
        }
        checkAbandoned(localCopies(audibleByAsin).map { it.remoteBase.lowercase() }.toSet() +
            audibleByAsin.values.map { remoteBase(it).lowercase() })
        if (done == 0 && failed == 0) return finish(BackupOutcome.AllDone, "all on server", prefix = "Phone copies")
        return finish(BackupOutcome.AllDone,
            "uploaded $done" + (if (failed > 0) ", $failed failed" else ""), prefix = "Phone copies")
    }

    private suspend fun runAudibleLocked(maxBooks: Int, force: Boolean, onlyAsin: String? = null): BackupOutcome {
        debugLog.section("Server Backup (Audible)")
        onlyAsin?.let { debugLog.log("BACKUP", "Requested from the Server tab: $it") }
        // A Libro.fm book (tapped in the Server tab): no Audible involved
        if (onlyAsin?.startsWith(LIBRO_PREFIX) == true) {
            if (!settings.isConfigured) return finish(BackupOutcome.Failed("Server not set up", retry = false))
            return libroPass(onlyAsin.removePrefix(LIBRO_PREFIX))
                ?: finish(BackupOutcome.AllDone, "already on the server", prefix = "Libro.fm")
        }
        if (!settings.isConfigured) return finish(BackupOutcome.Failed("Server not set up", retry = false))
        if (!tokenStore.isAuthenticated) return finish(BackupOutcome.Failed("Not signed in to Audible", retry = false))
        // No new book while uploads wait for tomorrow: the one waiting is finished first
        pausedOutcome("Audible")?.let { return it }
        if (!force && apiClient.isThrottled) {
            websiteBackup.promptThrottled(null)
            // Libro.fm has no such limit: back up one of its books instead
            if (onlyAsin == null) libroPass(null)?.let { return it }
            return finish(BackupOutcome.Failed(apiClient.throttleMessage(), retry = false), prefix = "Audible")
        }

        setStatus(BackupStatus(BackupPhase.CHECKING, "Checking for new books"))

        // Refresh the Audible library so new purchases are picked up
        try {
            val books = apiClient.getLibrary().data
            if (!books.isNullOrEmpty()) {
                val entities = books.map { it.toEntity() }
                audibleBookDao.insertAll(entities)
                audibleBookDao.deleteNotIn(entities.map { it.asin })
                seriesRepository.refresh()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            debugLog.log("BACKUP", "Library refresh failed, using cached list: ${e.message}")
        }
        val library = audibleBookDao.getAllOnce()
        // Books already on the phone are uploaded by the phone-copies pass instead
        val onPhone = localCopies(library.associateBy { it.asin }).mapNotNull { it.asin }.toSet()

        val onServer = try {
            serverPaths()
        } catch (e: ServerException) {
            return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = !e.settingsProblem), prefix = "Audible")
        }

        val failures = settings.failures
        val pending = library.filter {
            it.asin !in onPhone && remoteBase(it).lowercase() !in onServer &&
                // A book the user asked for is tried even after earlier failures
                if (onlyAsin != null) it.asin == onlyAsin else (failures[it.asin] ?: 0) < MAX_FAILURES
        }.sortedByDescending { book ->
            // A book already downloaded or converted by an earlier run is finished first
            workDir.listFiles()?.any { it.nameWithoutExtension == book.asin } == true
        }
        debugLog.log("BACKUP", "${library.size} Audible books, ${onPhone.size} on phone, ${pending.size} to download and back up")
        if (pending.isEmpty()) {
            // Audible is done: Libro.fm books next
            if (onlyAsin == null) libroPass(null)?.let { return it }
            return finish(BackupOutcome.AllDone, "nothing left to download", prefix = "Audible")
        }

        var done = 0
        var failed = 0
        for ((i, book) in pending.withIndex()) {
            if (done >= maxBooks) break
            currentCoroutineContext().ensureActive()
            val remaining = pending.size - i
            try {
                backUpBook(book, remaining, force)
                done++
                settings.failures = settings.failures - book.asin
                debugLog.log("BACKUP", "Backed up: ${book.title}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is ServerException && e.dailyLimit) return dailyLimitReached(e, book.title, "Audible")
                if (e is AudibleThrottledException) {
                    websiteBackup.promptThrottled(book.title)
                    return finish(BackupOutcome.Failed(e.message ?: "Throttled by Audible", retry = true), prefix = "Audible")
                }
                if (e is ServerException && e.settingsProblem) {
                    return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = false), prefix = "Audible")
                }
                if (isConnectionProblem(e)) {
                    debugLog.log("BACKUP", "Connection lost on ${book.title}: ${e::class.simpleName}: ${e.message}")
                    return finish(BackupOutcome.Failed("connection lost (${e.message})", retry = true), prefix = "Audible")
                }
                failed++
                val count = (settings.failures[book.asin] ?: 0) + 1
                settings.failures = settings.failures + (book.asin to count)
                debugLog.log("BACKUP", "Failed (${count}/$MAX_FAILURES): ${book.title} — ${e::class.simpleName}: ${e.message}")
                // One download per run: a failure uses up this run rather than starting another book
                return finish(BackupOutcome.Failed("${book.title}: ${e.message}", retry = e is IOException), prefix = "Audible")
            }
        }

        val left = pending.size - done - failed
        val summary = "backed up $done book${if (done == 1) "" else "s"}" + (if (left > 0) ", $left remaining" else "")
        return finish(if (left > 0) BackupOutcome.MoreRemaining else BackupOutcome.AllDone, summary, prefix = "Audible")
    }

    private fun localKey(book: BookEntity) = "book:${book.id}"

    private fun pausedText() = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(settings.uploadsPausedUntil))

    /** While uploads wait for tomorrow, nothing new is started. */
    private fun pausedOutcome(prefix: String): BackupOutcome? =
        if (settings.uploadsPaused) finish(BackupOutcome.Failed("server upload limit reached — continuing ${pausedText()}",
            retry = false), prefix = prefix) else null

    /**
     * The storage stopped taking uploads (Backblaze daily caps reset at midnight GMT): pause
     * uploads until just after then. The interrupted upload is continued first next time.
     */
    private fun dailyLimitReached(e: ServerException, title: String, prefix: String): BackupOutcome {
        val utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 5)
            set(java.util.Calendar.SECOND, 0)
        }
        settings.uploadsPausedUntil = utc.timeInMillis
        debugLog.log("BACKUP", "Server stopped accepting uploads while uploading $title (${e.message}) — " +
            "pausing uploads until ${pausedText()}")
        return finish(BackupOutcome.Failed("server upload limit reached on \"$title\" — continuing ${pausedText()}",
            retry = false), prefix = prefix)
    }

    private fun libroBase(book: com.dividetask.audiobookreader.data.db.entity.LibroBookEntity) =
        "${safeName(book.author)}/${safeName(book.title)}"

    /**
     * Backs up one Libro.fm book that's neither on the phone nor on the server ([onlyIsbn], or
     * the next one): downloaded, joined, uploaded, then the phone copy is deleted. Null when
     * there's none to do.
     */
    private suspend fun libroPass(onlyIsbn: String?): BackupOutcome? {
        val libro = libroBookDao.getAllOnce()
        if (libro.isEmpty()) return null
        pausedOutcome("Libro.fm")?.let { return it }
        val imp = importer.get()
        val onServer = try {
            serverPaths()
        } catch (e: ServerException) {
            return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = !e.settingsProblem), prefix = "Libro.fm")
        }
        val failures = settings.failures
        val pending = libro.filter {
            !imp.isLibroDownloaded(it.isbn) && libroBase(it).lowercase() !in onServer &&
                if (onlyIsbn != null) it.isbn == onlyIsbn else (failures[LIBRO_PREFIX + it.isbn] ?: 0) < MAX_FAILURES
        }
        if (pending.isEmpty()) return null
        val book = pending.first()
        debugLog.log("BACKUP", "${libro.size} Libro.fm books, ${pending.size} to download and back up: ${book.title}")
        val key = LIBRO_PREFIX + book.isbn
        try {
            setStatus(BackupStatus(BackupPhase.DOWNLOADING, book.title, remaining = pending.size))
            // Mirror the download's progress in the backup status
            val ok = kotlinx.coroutines.coroutineScope {
                val mirror = launch {
                    imp.status.collect { s ->
                        if (s != null && !s.finished && s.title == book.title) {
                            val phase = if (s.step.startsWith("Converting")) BackupPhase.CONVERTING else BackupPhase.DOWNLOADING
                            setStatus(BackupStatus(phase, book.title, ((s.progress ?: 0f) * 1000).toLong(),
                                if (s.progress != null) 1000 else 0, pending.size))
                        }
                    }
                }
                try { imp.importLibro(book) } finally { mirror.cancel() }
            }
            if (!ok) throw IOException(imp.status.value?.step ?: "Libro.fm download failed")
            val file = imp.libroFile(book.isbn)
            upload(file, libroBase(book), book.title, pending.size)
            bookRepository.getBookByFileUri(Uri.fromFile(file).toString())?.let { bookRepository.deleteBook(it.id) }
            if (file.delete()) debugLog.log("BACKUP", "Removed phone copy after upload: ${file.name}")
            settings.failures = settings.failures - key
            debugLog.log("BACKUP", "Backed up (Libro.fm): ${book.title}")
            return finish(if (pending.size > 1) BackupOutcome.MoreRemaining else BackupOutcome.AllDone,
                "backed up ${book.title}" + if (pending.size > 1) ", ${pending.size - 1} remaining" else "", prefix = "Libro.fm")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e is ServerException && e.dailyLimit) return dailyLimitReached(e, book.title, "Libro.fm")
            if (e is ServerException && e.settingsProblem) {
                return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = false), prefix = "Libro.fm")
            }
            if (isConnectionProblem(e)) {
                return finish(BackupOutcome.Failed("connection lost (${e.message})", retry = true), prefix = "Libro.fm")
            }
            val count = (settings.failures[key] ?: 0) + 1
            settings.failures = settings.failures + (key to count)
            debugLog.log("BACKUP", "Failed (${count}/$MAX_FAILURES): ${book.title} — ${e::class.simpleName}: ${e.message}")
            return finish(BackupOutcome.Failed("${book.title}: ${e.message}", retry = e is IOException), prefix = "Libro.fm")
        }
    }

    private suspend fun removePhoneCopy(copy: LocalCopy) {
        bookRepository.deleteBook(copy.book.id)
        listOf(copy.file, File(copy.file.parentFile, "${copy.file.nameWithoutExtension}.aax"))
            .filter { it.exists() && it.delete() }
            .forEach { debugLog.log("BACKUP", "Removed phone copy after upload: ${it.name}") }
    }

    /**
     * The next Audible book for the website backup: not on the phone, not on the server, not
     * given up on. Null when every book is backed up.
     */
    suspend fun nextForWebsite(): AudibleBookEntity? = withContext(Dispatchers.IO) {
        val library = audibleBookDao.getAllOnce()
        val onPhone = localCopies(library.associateBy { it.asin }).mapNotNull { it.asin }.toSet()
        // Forget website downloads that never reached the phone (e.g. the app closed mid-download)
        settings.websiteBackupAsins = settings.websiteBackupAsins.intersect(onPhone)
        val onServer = serverPaths()
        val failures = settings.failures
        library.firstOrNull {
            it.asin !in onPhone && remoteBase(it).lowercase() !in onServer &&
                (failures[it.asin] ?: 0) < MAX_FAILURES
        }
    }

    /** A website backup of [asin] failed for a reason other than a refusal: counts towards skipping it. */
    fun websiteFailed(asin: String) {
        settings.failures = settings.failures + (asin to (settings.failures[asin] ?: 0) + 1)
    }

    /** The network went away or the storage had a hiccup: not the book's fault, so not counted against it. */
    private fun isConnectionProblem(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if ((t is ServerException && !t.settingsProblem) || t is java.net.SocketException ||
                t is java.net.UnknownHostException || t is java.net.SocketTimeoutException ||
                t is javax.net.ssl.SSLException
            ) return true
            t = t.cause
        }
        return false
    }

    private fun finish(outcome: BackupOutcome, message: String? = null, prefix: String? = null): BackupOutcome {
        val text = message ?: (outcome as? BackupOutcome.Failed)?.message ?: ""
        settings.lastRunAt = System.currentTimeMillis()
        settings.lastRunMessage = if (prefix != null) "$prefix: $text" else text
        debugLog.log("BACKUP", settings.lastRunMessage)
        return outcome
    }

    private suspend fun backUpBook(book: AudibleBookEntity, remaining: Int, force: Boolean) {
        val title = book.title
        val estimate = (book.runtimeMinutes ?: 500) * 1_000_000L
        val free = StatFs(workDir.absolutePath).availableBytes
        if (free < estimate * 2 + 200_000_000L) {
            throw IOException("Not enough free storage (${free / 1_048_576}MB free, need ~${(estimate * 2) / 1_048_576}MB)")
        }

        val aax = File(workDir, "${book.asin}.aax")
        // The AAX is deleted only after a successful conversion, so a converted
        // file left by an interrupted run is complete only when the AAX is gone
        val converted = if (aax.exists()) null else workDir.listFiles()?.firstOrNull {
            it.nameWithoutExtension == book.asin && it.extension != "aax" && it.length() > 1000
        }

        val output = converted ?: run {
            val result = apiClient.getDownloadUrl(book.asin, force)
            val info = result.data ?: run {
                // A throttle applies to every book, so stop the run rather than burn through the list
                if (apiClient.isThrottled) throw AudibleThrottledException(apiClient.throttleMessage())
                throw IOException(result.error ?: "Could not get download URL")
            }
            downloadAax(info.url, aax, title, remaining)

            setStatus(BackupStatus(BackupPhase.CONVERTING, title, remaining = remaining))
            val out = if (info.key != null && info.iv != null) {
                converter.convertWithKeyIv(aax, info.key, info.iv)
            } else {
                activationBytesProvider.getActivationBytes()?.let { converter.convert(aax, it) }
            }
            if (out == null || !out.exists() || out.length() < 1000) throw IOException("Conversion failed")
            aax.delete()
            out
        }

        upload(output, remoteBase(book), title, remaining)
        output.delete()
        aax.delete()
    }

    private suspend fun upload(file: File, remoteBase: String, title: String, remaining: Int) {
        val rel = "$remoteBase.${file.extension}"
        val ctx = currentCoroutineContext()
        setStatus(BackupStatus(BackupPhase.UPLOADING, title, 0, file.length(), remaining, rel))
        var lastUpdate = 0L
        storage.use { conn ->
            conn.upload(file, rel) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1000) {
                    lastUpdate = now
                    setStatus(BackupStatus(BackupPhase.UPLOADING, title, done, total, remaining, rel))
                }
                ctx.isActive
            }
        }
        ctx.ensureActive()
    }

    private suspend fun downloadAax(url: String, file: File, title: String, remaining: Int) {
        val existing = if (file.exists()) file.length() else 0L
        if (existing > 0) debugLog.log("BACKUP", "Resuming download from ${existing / 1_048_576}MB")
        setStatus(BackupStatus(BackupPhase.DOWNLOADING, title, existing, 0, remaining))

        httpClient.prepareGet(url) {
            header("User-Agent", "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0")
            if (existing > 0) header("Range", "bytes=$existing-")
        }.execute { response ->
            val code = response.status.value
            if (code == 416) return@execute // already complete
            if (code != 200 && code != 206) throw IOException("Download failed: HTTP $code")

            val resumed = code == 206
            val length = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
            val total = if (resumed) existing + length else length
            var read = if (resumed) existing else 0L
            var lastUpdate = 0L

            val channel = response.bodyAsChannel()
            val buffer = ByteArray(65536)
            FileOutputStream(file, resumed).use { out ->
                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    val n = channel.readAvailable(buffer)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    read += n
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 1000) {
                        lastUpdate = now
                        setStatus(BackupStatus(BackupPhase.DOWNLOADING, title, read, total, remaining))
                    }
                }
            }
            if (total > 0 && read < total) throw IOException("Download incomplete: $read of $total bytes")
        }
        if (!file.exists() || file.length() < 1000) throw IOException("Downloaded file missing or too small")
    }

    /** Server path without extension: "<Author>/<Title>". */
    private fun remoteBase(book: AudibleBookEntity) = "${safeName(book.author)}/${safeName(book.title)}"

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').ifBlank { "Unknown" }

    private fun setStatus(status: BackupStatus) {
        _status.value = status
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    fun foregroundInfo(): ForegroundInfo {
        val notification = buildNotification(_status.value.takeIf { it.phase != BackupPhase.IDLE }
            ?: BackupStatus(BackupPhase.CHECKING, "Starting backup"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(status: BackupStatus) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(when (status.phase) {
                BackupPhase.DOWNLOADING -> "Backup: downloading"
                BackupPhase.CONVERTING -> "Backup: converting"
                BackupPhase.UPLOADING -> "Backup: uploading"
                BackupPhase.CANCELLING -> "Backup: cancelling unfinished uploads"
                else -> "Server backup"
            })
            .setContentText(status.title)
            .setProgress(
                if (status.total > 0) 100 else 0,
                if (status.total > 0) (status.bytes * 100 / status.total).toInt() else 0,
                status.total <= 0
            )
            .setOngoing(true)
            .setSilent(true)
            .build()

    companion object {
        const val CHANNEL = "backup"
        const val NOTIFICATION_ID = 0x5F7B
        const val MAX_FAILURES = 5
        /** Marks a Libro.fm ISBN where an Audible ASIN is expected (Server tab requests, failure counts). */
        const val LIBRO_PREFIX = "libro:"
    }
}

/** Audible is refusing license requests for now; retrying other books won't help. */
class AudibleThrottledException(message: String) : IOException(message)
