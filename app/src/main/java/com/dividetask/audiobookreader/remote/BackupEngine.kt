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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupPhase { IDLE, CHECKING, DOWNLOADING, CONVERTING, UPLOADING }

data class BackupStatus(
    val phase: BackupPhase = BackupPhase.IDLE,
    val title: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val remaining: Int = 0
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
    private val sftp: SftpClient,
    private val apiClient: AudibleApiClient,
    private val httpClient: HttpClient,
    private val converter: AaxConverter,
    private val activationBytesProvider: ActivationBytesProvider,
    private val audibleBookDao: AudibleBookDao,
    private val tokenStore: AudibleTokenStore,
    private val seriesRepository: SeriesRepository,
    private val bookRepository: BookRepository,
    private val debugLog: AppDebugLog
) {
    private val mutex = Mutex()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

    /** Downloads, converts and uploads up to [maxBooks] Audible books that aren't on the phone or server. */
    suspend fun runAudible(maxBooks: Int): BackupOutcome = locked { runAudibleLocked(maxBooks) }

    private suspend fun locked(block: suspend () -> BackupOutcome): BackupOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                block()
            } finally {
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
        sftp.use { conn -> conn.listAudio() }.map { it.relPath.substringBeforeLast('.').lowercase() }.toSet()

    private suspend fun runLocalLocked(): BackupOutcome {
        debugLog.section("Server Backup (phone copies)")
        if (!settings.isConfigured) return finish(BackupOutcome.Failed("Server not set up", retry = false))
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
                return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = true), prefix = "Phone copies")
            }
            val failures = settings.failures
            val pending = localCopies(audibleByAsin).filter {
                it.book.id !in tried && it.remoteBase.lowercase() !in onServer &&
                    (failures[localKey(it.book)] ?: 0) < MAX_FAILURES
            }
            if (pending.isEmpty()) break
            debugLog.log("BACKUP", "${pending.size} phone copies to upload")
            for ((i, copy) in pending.withIndex()) {
                currentCoroutineContext().ensureActive()
                tried += copy.book.id
                val key = localKey(copy.book)
                try {
                    upload(copy.file, copy.remoteBase, copy.book.title, pending.size - i)
                    done++
                    settings.failures = settings.failures - key
                    debugLog.log("BACKUP", "Uploaded: ${copy.book.title}")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed++
                    val count = (settings.failures[key] ?: 0) + 1
                    settings.failures = settings.failures + (key to count)
                    debugLog.log("BACKUP", "Failed (${count}/$MAX_FAILURES): ${copy.book.title} — ${e::class.simpleName}: ${e.message}")
                    if (e is ServerException) {
                        return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = true), prefix = "Phone copies")
                    }
                }
            }
        }
        if (done == 0 && failed == 0) return finish(BackupOutcome.AllDone, "all on server", prefix = "Phone copies")
        return finish(BackupOutcome.AllDone,
            "uploaded $done" + (if (failed > 0) ", $failed failed" else ""), prefix = "Phone copies")
    }

    private suspend fun runAudibleLocked(maxBooks: Int): BackupOutcome {
        debugLog.section("Server Backup (Audible)")
        if (!settings.isConfigured) return finish(BackupOutcome.Failed("Server not set up", retry = false))
        if (!tokenStore.isAuthenticated) return finish(BackupOutcome.Failed("Not signed in to Audible", retry = false))

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
            return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = true), prefix = "Audible")
        }

        val failures = settings.failures
        val pending = library.filter {
            it.asin !in onPhone && remoteBase(it).lowercase() !in onServer && (failures[it.asin] ?: 0) < MAX_FAILURES
        }
        debugLog.log("BACKUP", "${library.size} Audible books, ${onPhone.size} on phone, ${pending.size} to download and back up")
        if (pending.isEmpty()) return finish(BackupOutcome.AllDone, "nothing left to download", prefix = "Audible")

        var done = 0
        var failed = 0
        for ((i, book) in pending.withIndex()) {
            if (done >= maxBooks) break
            currentCoroutineContext().ensureActive()
            val remaining = pending.size - i
            try {
                backUpBook(book, remaining)
                done++
                settings.failures = settings.failures - book.asin
                debugLog.log("BACKUP", "Backed up: ${book.title}")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (e is AudibleThrottledException) {
                    return finish(BackupOutcome.Failed(e.message ?: "Throttled by Audible", retry = true), prefix = "Audible")
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

    private fun finish(outcome: BackupOutcome, message: String? = null, prefix: String? = null): BackupOutcome {
        val text = message ?: (outcome as? BackupOutcome.Failed)?.message ?: ""
        settings.lastRunAt = System.currentTimeMillis()
        settings.lastRunMessage = if (prefix != null) "$prefix: $text" else text
        debugLog.log("BACKUP", settings.lastRunMessage)
        return outcome
    }

    private suspend fun backUpBook(book: AudibleBookEntity, remaining: Int) {
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
            val result = apiClient.getDownloadUrl(book.asin)
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
        setStatus(BackupStatus(BackupPhase.UPLOADING, title, 0, file.length(), remaining))
        var lastUpdate = 0L
        sftp.use { conn ->
            conn.upload(file, rel) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1000) {
                    lastUpdate = now
                    setStatus(BackupStatus(BackupPhase.UPLOADING, title, done, total, remaining))
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
    }
}

/** Audible is refusing license requests for now; retrying other books won't help. */
class AudibleThrottledException(message: String) : IOException(message)
