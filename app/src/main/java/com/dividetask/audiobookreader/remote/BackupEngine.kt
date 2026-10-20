package com.dividetask.audiobookreader.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
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
 * Backs up Audible books to the SFTP server, one book at a time:
 * download AAX → convert → upload → delete local copy.
 * Books already in the local library are uploaded from there and kept.
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
    private val debugLog: AppDebugLog
) {
    private val mutex = Mutex()
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val _status = MutableStateFlow(BackupStatus())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val workDir: File
        get() = File(context.getExternalFilesDir(null), "backup_work").apply { mkdirs() }

    private val libraryDir: File
        get() = File(context.getExternalFilesDir(null), "audiobooks")

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Server backup", NotificationManager.IMPORTANCE_LOW)
        )
    }

    suspend fun run(maxBooks: Int): BackupOutcome = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                runLocked(maxBooks)
            } finally {
                _status.value = BackupStatus()
                notificationManager.cancel(NOTIFICATION_ID)
            }
        }
    }

    private suspend fun runLocked(maxBooks: Int): BackupOutcome {
        debugLog.section("Server Backup")
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
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            debugLog.log("BACKUP", "Library refresh failed, using cached list: ${e.message}")
        }
        val library = audibleBookDao.getAllOnce()

        val onServer = try {
            sftp.use { conn -> conn.listAudio() }.map { it.relPath.substringBeforeLast('.').lowercase() }.toSet()
        } catch (e: ServerException) {
            return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = true))
        }

        val failures = settings.failures
        val pending = library.filter {
            remoteBase(it).lowercase() !in onServer && (failures[it.asin] ?: 0) < MAX_FAILURES
        }
        debugLog.log("BACKUP", "${library.size} Audible books, ${library.size - pending.size} on server or skipped, ${pending.size} to back up")
        if (pending.isEmpty()) return finish(BackupOutcome.AllDone, "Up to date — ${library.size} books checked")

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
                failed++
                val count = (settings.failures[book.asin] ?: 0) + 1
                settings.failures = settings.failures + (book.asin to count)
                debugLog.log("BACKUP", "Failed (${count}/$MAX_FAILURES): ${book.title} — ${e::class.simpleName}: ${e.message}")
                if (maxBooks == 1) {
                    return finish(BackupOutcome.Failed("${book.title}: ${e.message}", retry = e is IOException))
                }
                if (e is ServerException) {
                    // Server went away — stop and let WorkManager try again later
                    return finish(BackupOutcome.Failed(e.message ?: "Server error", retry = true))
                }
            }
        }

        val left = pending.size - done - failed
        val summary = "Backed up $done book${if (done == 1) "" else "s"}" +
            (if (failed > 0) ", $failed failed" else "") +
            (if (left > 0) ", $left remaining" else "")
        return finish(if (left > 0) BackupOutcome.MoreRemaining else BackupOutcome.AllDone, summary)
    }

    private fun finish(outcome: BackupOutcome, message: String? = null): BackupOutcome {
        settings.lastRunAt = System.currentTimeMillis()
        settings.lastRunMessage = message ?: (outcome as? BackupOutcome.Failed)?.message ?: ""
        debugLog.log("BACKUP", settings.lastRunMessage)
        return outcome
    }

    private suspend fun backUpBook(book: AudibleBookEntity, remaining: Int) {
        val title = book.title
        val existing = AUDIO_EXTENSIONS.map { File(libraryDir, "${book.asin}.$it") }
            .firstOrNull { it.exists() && !it.name.endsWith(".aax") && it.length() > 1000 }

        if (existing != null) {
            debugLog.log("BACKUP", "Uploading from local library: ${existing.name}")
            upload(existing, book, remaining)
            return
        }

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
            val info = apiClient.getDownloadUrl(book.asin).data
                ?: throw IOException("Could not get download URL")
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

        upload(output, book, remaining)
        output.delete()
        aax.delete()
    }

    private suspend fun upload(file: File, book: AudibleBookEntity, remaining: Int) {
        val rel = "${remoteBase(book)}.${file.extension}"
        val ctx = currentCoroutineContext()
        setStatus(BackupStatus(BackupPhase.UPLOADING, book.title, 0, file.length(), remaining))
        var lastUpdate = 0L
        sftp.use { conn ->
            conn.upload(file, rel) { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 1000) {
                    lastUpdate = now
                    setStatus(BackupStatus(BackupPhase.UPLOADING, book.title, done, total, remaining))
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
