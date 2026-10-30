package com.dividetask.audiobookreader.audible.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.app.NotificationCompat
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.api.DownloadInfo
import com.dividetask.audiobookreader.audible.api.AudibleBook
import com.dividetask.audiobookreader.audible.auth.ActivationBytesProvider
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentLinkedQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class DownloadProgress(
    val asin: String,
    val title: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val state: DownloadState,
    val coverUrl: String? = null,
    val debugLog: List<String> = emptyList(),
    val message: String? = null
)

enum class DownloadState {
    QUEUED, DOWNLOADING, WAITING_FOR_WIFI, CONVERTING, COMPLETE, ERROR
}

@Singleton
class AudibleDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiClient: AudibleApiClient,
    private val httpClient: HttpClient,
    private val bookRepository: BookRepository,
    private val debugLog: AppDebugLog,
    private val tokenStore: AudibleTokenStore,
    private val converter: AaxConverter,
    private val activationBytesProvider: ActivationBytesProvider,
    private val pendingStore: PendingDownloadStore,
    private val requestLog: com.dividetask.audiobookreader.debug.AudibleRequestLog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val downloadQueue = ConcurrentLinkedQueue<AudibleBook>()
    private var isProcessing = false
    private var currentDownloadJob: kotlinx.coroutines.Job? = null
    private val maxRetries = 7
    private val retryDelays = longArrayOf(5_000, 60_000, 900_000, 3_600_000, 14_400_000, 28_800_000, 86_400_000)

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    private val _queuedAsins = MutableStateFlow<Set<String>>(emptySet())
    val queuedAsins: StateFlow<Set<String>> = _queuedAsins.asStateFlow()

    private val queuedTitles = mutableMapOf<String, String>()

    /** Books Audible's download limit turned away; retried by [PendingDownloadWorker]. */
    val pendingDownloads: StateFlow<Map<String, PendingDownload>> = pendingStore.items

    /**
     * Set when a download the user started is blocked by the download limit, so the app
     * can offer to get it from the Audible website instead. Not set for background retries.
     */
    private val _websiteOffer = MutableStateFlow<PendingDownload?>(null)
    val websiteOffer: StateFlow<PendingDownload?> = _websiteOffer.asStateFlow()

    /** Stops automatic retries for a pending book. */
    fun removePending(asin: String) {
        pendingStore.remove(asin)
        requestLog.event("pending-removed", asin)
        scheduleRetry()
    }

    fun dismissWebsiteOffer() {
        _websiteOffer.value = null
    }

    /** Set when the download being processed was turned away by the download limit. */
    private var throttledAsin: String? = null

    private val completedAsins = mutableSetOf<String>()

    private val audiobooksDir: File
        get() {
            val dir = File(context.getExternalFilesDir(null), "audiobooks")
            dir.mkdirs()
            return dir
        }

    init {
        val channel = NotificationChannel("downloads", "Downloads", NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

        scope.launch {
            bookRepository.getAllBooks().collect { books ->
                completedAsins.clear()
                books.forEach { book ->
                    // Any file in audiobooks/ named {asin}.{ext} — converted books are .m4b/.m4a, not .aax
                    val uri = book.fileUri
                    if (uri.contains("/audiobooks/")) {
                        completedAsins.add(uri.substringAfterLast("/").substringBeforeLast("."))
                    }
                }
                // A book imported another way (e.g. from the website) is no longer waiting to download
                _downloadProgress.value?.takeIf { it.state == DownloadState.ERROR && it.asin in completedAsins }?.let {
                    _downloadProgress.value = null
                }
            }
        }
    }

    private fun isWifiConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun canDownload(): Boolean {
        if (!isNetworkAvailable()) return false
        if (tokenStore.wifiOnlyDownloads && !isWifiConnected()) return false
        return true
    }

    private suspend fun waitForWifi() {
        if (canDownload()) return
        debugLog.log("DL", "Waiting for WiFi...")
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (canDownload() && cont.isActive) {
                        connectivityManager.unregisterNetworkCallback(this)
                        cont.resume(Unit)
                    }
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)
            cont.invokeOnCancellation {
                try { connectivityManager.unregisterNetworkCallback(callback) } catch (_: Exception) {}
            }
        }
        debugLog.log("DL", "WiFi connected — resuming")
    }

    private var activeAsin: String? = null

    fun queueDownload(book: AudibleBook) {
        requestLog.event("download-requested", "${book.asin}${if (retrying) " (automatic retry)" else ""}")
        if (apiClient.isThrottled) {
            // Don't add to the throttle by asking now; try once the limit should be over
            debugLog.log("DL", "Throttled — pending: ${book.title}")
            val pending = markPending(book, turnedAway = false)
            _downloadProgress.value = DownloadProgress(
                book.asin, book.title, 0, 0, DownloadState.ERROR,
                message = pendingMessage(pending)
            )
            return
        }
        if (downloadQueue.any { it.asin == book.asin }) return
        if (activeAsin == book.asin) return
        if (_queuedAsins.value.contains(book.asin)) return
        downloadQueue.add(book)
        queuedTitles[book.asin] = book.title
        _queuedAsins.value = _queuedAsins.value + book.asin
        debugLog.log("DL", "Queued: ${book.title}")
        processQueue()
    }

    private fun processQueue() {
        if (isProcessing) return
        val book = downloadQueue.poll() ?: return
        isProcessing = true

        currentDownloadJob = scope.launch {
            try {
                // Active before it leaves the queue, so retryDuePending never sees it as neither
                activeAsin = book.asin
                _queuedAsins.value = _queuedAsins.value - book.asin
                queuedTitles.remove(book.asin)
                throttledAsin = null
                downloadBookWithRetry(book)
                // Stays pending only while the download limit is what's stopping it
                if (throttledAsin != book.asin) pendingStore.remove(book.asin)
            } finally {
                activeAsin = null
                currentDownloadJob = null
                isProcessing = false
                processQueue()
            }
        }
    }

    private suspend fun downloadBookWithRetry(book: AudibleBook) {
        val asin = book.asin
        val title = book.title
        val log = mutableListOf<String>()
        val notifId = asin.hashCode()

        fun dl(msg: String) {
            log.add(msg)
            debugLog.log("DL", msg)
        }

        val bookCoverUrl = book.productImages?.image500 ?: book.productImages?.image1024
        debugLog.section("Download: $title")
        dl("ASIN: $asin")

        if (!canDownload()) {
            dl("No suitable network — waiting for WiFi")
            _downloadProgress.value = DownloadProgress(asin, title, 0, 0, DownloadState.WAITING_FOR_WIFI, bookCoverUrl, log)
            updateNotification(notifId, "$title (waiting for WiFi...)", 0, 0)
            waitForWifi()
        }

        var downloadInfo = fetchDownloadInfo(asin, log) ?: run {
            if (apiClient.isThrottled) {
                throttledAsin = asin
                val pending = markPending(book, turnedAway = true)
                dl("Pending — next try ${timeText(pending.nextAttemptAt)}")
                _downloadProgress.value = DownloadProgress(
                    asin, title, 0, 0, DownloadState.ERROR, bookCoverUrl, log,
                    message = pendingMessage(pending)
                )
                return
            }
            _downloadProgress.value = DownloadProgress(
                asin, title, 0, 0, DownloadState.ERROR, bookCoverUrl, log,
                message = lastLicenseError ?: "Could not get a download link from Audible"
            )
            return
        }
        var downloadUrl = downloadInfo.url
        var audibleKey = downloadInfo.key
        var audibleIv = downloadInfo.iv
        var urlFetchedAt = System.currentTimeMillis()

        val outputFile = File(audiobooksDir, "${asin}.aax")
        var backoffIndex = 0

        for (attempt in 1..maxRetries) {
            // Refresh URL if older than 30 minutes
            if (System.currentTimeMillis() - urlFetchedAt > 30 * 60 * 1000) {
                dl("Download URL expired — refreshing...")
                val fresh = fetchDownloadInfo(asin, log)
                if (fresh != null) {
                    downloadInfo = fresh
                    downloadUrl = fresh.url
                    audibleKey = fresh.key
                    audibleIv = fresh.iv
                    urlFetchedAt = System.currentTimeMillis()
                }
            }

            if (!canDownload()) {
                dl("No suitable network — waiting for WiFi")
                _downloadProgress.value = DownloadProgress(asin, title, outputFile.length(), 0, DownloadState.WAITING_FOR_WIFI, bookCoverUrl, log)
                updateNotification(notifId, "$title (waiting for WiFi...)", outputFile.length(), 0)
                waitForWifi()
                backoffIndex = 0
            }

            try {
                val existingBytes = if (outputFile.exists()) outputFile.length() else 0L
                val bytesAtStart = existingBytes
                if (existingBytes > 0) {
                    dl("Resuming from ${existingBytes / 1_048_576}MB (attempt $attempt)")
                } else if (attempt > 1) {
                    dl("Retrying from scratch (attempt $attempt)")
                }

                _downloadProgress.value = DownloadProgress(asin, title, existingBytes, 0, DownloadState.DOWNLOADING, bookCoverUrl, log)
                updateNotification(notifId, title, existingBytes, 0)

                httpClient.prepareGet(downloadUrl) {
                    header("User-Agent", "Audible/671 CFNetwork/1240.0.4 Darwin/20.6.0")
                    if (existingBytes > 0) {
                        header("Range", "bytes=$existingBytes-")
                    }
                }.execute { response ->
                    dl("HTTP ${response.status}")

                    val isResumed = response.status.value == 206
                    val contentLength = response.headers["Content-Length"]?.toLongOrNull() ?: 0L
                    val totalSize = if (isResumed) existingBytes + contentLength else contentLength
                    dl("Content-Length: ${contentLength / 1_048_576}MB${if (isResumed) " (total ~${totalSize / 1_048_576}MB)" else ""}")

                    if (response.status.value == 416) {
                        dl("Range not satisfiable — file may be complete (${existingBytes / 1_048_576}MB)")
                        return@execute
                    }

                    if (response.status.value == 403) {
                        dl("URL expired (403) — will refresh and retry")
                        urlFetchedAt = 0 // force refresh on next attempt
                        throw java.io.IOException("Download URL expired (403)")
                    }

                    if (response.status.value !in listOf(200, 206)) {
                        val channel = response.bodyAsChannel()
                        val buf = ByteArray(1024)
                        val n = channel.readAvailable(buf)
                        if (n > 0) dl("Error body: ${String(buf, 0, n).take(500)}")
                        _downloadProgress.value = DownloadProgress(asin, title, 0, 0, DownloadState.ERROR, bookCoverUrl, log)
                        notificationManager.cancel(notifId)
                        return@execute
                    }

                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(65536)
                    var totalBytesRead = existingBytes
                    var lastLogMB = totalBytesRead / 1_048_576

                    val raf = if (isResumed) {
                        RandomAccessFile(outputFile, "rw").apply { seek(existingBytes) }
                    } else null
                    val fos = if (raf == null) java.io.FileOutputStream(outputFile, isResumed) else null

                    try {
                        while (!channel.isClosedForRead) {
                            val bytesRead = channel.readAvailable(buffer)
                            if (bytesRead <= 0) break
                            if (raf != null) raf.write(buffer, 0, bytesRead) else fos!!.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            _downloadProgress.value = DownloadProgress(
                                asin, title, totalBytesRead, totalSize, DownloadState.DOWNLOADING, bookCoverUrl, log
                            )
                            val currentMB = totalBytesRead / 1_048_576
                            if (currentMB >= lastLogMB + 50) {
                                debugLog.log("DL", "Progress: ${currentMB}MB / ${totalSize / 1_048_576}MB",
                                    com.dividetask.audiobookreader.debug.AppDebugLog.LogLevel.VERBOSE)
                                lastLogMB = currentMB
                            }
                            if (totalBytesRead % (5 * 1_048_576) < 65536) {
                                updateNotification(notifId, title, totalBytesRead, totalSize)
                            }
                        }
                    } finally {
                        raf?.close()
                        fos?.close()
                    }

                    dl("Downloaded ${totalBytesRead / 1_048_576}MB total")

                    if (totalSize > 0 && totalBytesRead < totalSize) {
                        dl("Incomplete — got ${totalBytesRead} of ${totalSize} bytes")
                        throw java.io.IOException("Incomplete download")
                    }
                }

                if (!outputFile.exists() || outputFile.length() < 1000) {
                    dl("ERROR: File missing or too small")
                    _downloadProgress.value = DownloadProgress(asin, title, 0, 0, DownloadState.ERROR, bookCoverUrl, log)
                    notificationManager.cancel(notifId)
                    return
                }

                // Convert AAX to playable format
                dl("Converting...")
                _downloadProgress.value = DownloadProgress(asin, title, 0, 0, DownloadState.CONVERTING, bookCoverUrl, log)
                updateNotification(notifId, "$title (converting...)", 0, 0)

                val m4bFile = if (audibleKey != null && audibleIv != null) {
                    dl("Using audible_key/iv from license response")
                    converter.convertWithKeyIv(outputFile, audibleKey, audibleIv)
                } else {
                    dl("No key/iv — trying activation bytes")
                    val activationBytes = activationBytesProvider.getActivationBytes()
                    if (activationBytes != null) converter.convert(outputFile, activationBytes) else null
                }

                if (true) {
                    if (m4bFile != null && m4bFile.exists() && m4bFile.length() > 1000) {
                        dl("Conversion successful — ${m4bFile.length() / 1_048_576}MB")
                        outputFile.delete()
                        dl("Deleted AAX file")

                        val authorName = book.authors?.firstOrNull()?.name ?: "Unknown Author"
                        val durationMs = (book.runtimeLengthMin ?: 0) * 60_000L

                        bookRepository.insertBook(BookEntity(
                            title = title, author = authorName, coverUri = bookCoverUrl,
                            fileUri = android.net.Uri.fromFile(m4bFile).toString(), durationMs = durationMs
                        ), emptyList())
                        completedAsins.add(asin)
                        dl("Complete — added to library")

                        _downloadProgress.value = DownloadProgress(asin, title, m4bFile.length(), m4bFile.length(), DownloadState.COMPLETE, bookCoverUrl, log)
                        notificationManager.notify(notifId, NotificationCompat.Builder(context, "downloads")
                            .setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle("Download complete").setContentText(title)
                            .setAutoCancel(true).build())
                        return
                    } else {
                        dl("Conversion failed — keeping AAX file")
                    }
                }

                // Fallback: save AAX as-is if conversion failed
                val authorName = book.authors?.firstOrNull()?.name ?: "Unknown Author"
                val durationMs = (book.runtimeLengthMin ?: 0) * 60_000L

                bookRepository.insertBook(BookEntity(
                    title = title, author = authorName, coverUri = bookCoverUrl,
                    fileUri = android.net.Uri.fromFile(outputFile).toString(), durationMs = durationMs
                ), emptyList())
                completedAsins.add(asin)
                dl("Saved as AAX (conversion unavailable)")

                _downloadProgress.value = DownloadProgress(asin, title, outputFile.length(), outputFile.length(), DownloadState.COMPLETE, bookCoverUrl, log)
                notificationManager.notify(notifId, NotificationCompat.Builder(context, "downloads")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("Download complete").setContentText(title)
                    .setAutoCancel(true).build())
                return

            } catch (e: Exception) {
                val bytesNow = if (outputFile.exists()) outputFile.length() else 0L
                val bytesAtStart = if (attempt == 1) 0L else bytesNow
                val progressMade = bytesNow - (if (outputFile.exists()) bytesNow else 0L)

                dl("Attempt $attempt failed: ${e::class.simpleName}: ${e.message}")
                Log.e("AudibleDownload", "Download attempt $attempt failed for $asin", e)

                val madeProgress = outputFile.exists() && outputFile.length() > 1_048_576 &&
                    (attempt == 1 || outputFile.length() > bytesAtStart)

                if (madeProgress) {
                    backoffIndex = 0
                    dl("Progress was made — resetting backoff to 5s")
                }

                if (attempt < maxRetries) {
                    val waitMs = retryDelays[backoffIndex.coerceAtMost(retryDelays.size - 1)]
                    val waitText = when {
                        waitMs >= 3_600_000 -> "${waitMs / 3_600_000}h"
                        waitMs >= 60_000 -> "${waitMs / 60_000}m"
                        else -> "${waitMs / 1000}s"
                    }
                    dl("Waiting $waitText before retry...")
                    updateNotification(notifId, "$title (retrying in $waitText...)", outputFile.length(), 0)
                    backoffIndex++
                    delay(waitMs)
                } else {
                    dl("All $maxRetries attempts failed")
                    _downloadProgress.value = DownloadProgress(asin, title, 0, 0, DownloadState.ERROR, bookCoverUrl, log)
                    notificationManager.cancel(notifId)
                }
            }
        }
    }

    private fun updateNotification(id: Int, title: String, downloaded: Long, total: Long) {
        val notification = NotificationCompat.Builder(context, "downloads")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setProgress(
                if (total > 0) 100 else 0,
                if (total > 0) (downloaded * 100 / total).toInt() else 0,
                total <= 0
            )
            .setOngoing(true).setSilent(true).build()
        notificationManager.notify(id, notification)
    }

    private var lastLicenseError: String? = null

    companion object {
        private val PENDING_BACKOFF_MS = longArrayOf(15 * 60_000L, 30 * 60_000L, 60 * 60_000L, 2 * 3_600_000L, 4 * 3_600_000L)
    }

    private suspend fun fetchDownloadInfo(asin: String, log: MutableList<String>): com.dividetask.audiobookreader.audible.api.DownloadInfo? {
        fun dl(msg: String) { log.add(msg); debugLog.log("DL", msg) }
        dl("Getting download URL...")
        val result = apiClient.getDownloadUrl(asin)
        result.debugLog.forEach { debugLog.log("DL", it) }
        val info = result.data
        lastLicenseError = result.error
        if (info == null) {
            dl("ERROR: No download URL obtained${result.error?.let { " — $it" } ?: ""}")
            return null
        }
        dl("Download URL: ${info.url.take(100)}...")
        if (info.key != null) dl("Decryption key: present (${info.key.length} chars)")
        return info
    }

    // ---------------------------------------------------------------- Pending (download limit)

    /**
     * Adds or updates [book] on the pending list. Each time Audible turns it away the wait
     * grows (15 min, 30 min, 1 h, 2 h, then every 4 h), with ±20% jitter so pending books
     * don't all ask at once — and never before Audible's limit should have lifted.
     */
    private fun markPending(book: AudibleBook, turnedAway: Boolean): PendingDownload {
        val existing = pendingStore.get(book.asin)
        val attempts = (existing?.attempts ?: 0) + if (turnedAway) 1 else 0
        val now = System.currentTimeMillis()
        val backoff = PENDING_BACKOFF_MS[(attempts - 1).coerceIn(0, PENDING_BACKOFF_MS.size - 1)]
        val jittered = (backoff * kotlin.random.Random.nextDouble(0.8, 1.2)).toLong()
        val afterLimit = apiClient.throttledUntil + kotlin.random.Random.nextLong(60_000, 5 * 60_000)
        val pending = PendingDownload(
            asin = book.asin,
            title = book.title,
            author = book.authors?.firstOrNull()?.name,
            coverUrl = book.productImages?.image500 ?: book.productImages?.image1024,
            runtimeMinutes = book.runtimeLengthMin,
            attempts = attempts,
            nextAttemptAt = maxOf(now + jittered, afterLimit)
        )
        pendingStore.put(pending)
        debugLog.log("DL", "Pending: ${book.title} (turned away $attempts×), next try ${timeText(pending.nextAttemptAt)}")
        requestLog.event("pending", "${book.asin} turned away $attempts×, next try ${timeText(pending.nextAttemptAt)}")
        if (!retrying) _websiteOffer.value = pending
        scheduleRetry()
        return pending
    }

    private fun pendingMessage(pending: PendingDownload) =
        "Audible is limiting downloads. \"${pending.title}\" will download automatically — next try ${timeText(pending.nextAttemptAt)}."

    private fun timeText(millis: Long): String =
        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(millis))

    /** True while [retryDuePending] runs; it reschedules when done (rescheduling now would cancel it). */
    @Volatile private var retrying = false

    /**
     * Schedules [PendingDownloadWorker] for the earliest pending book, or cancels it if none.
     * AudiobookApp calls this at startup.
     */
    fun scheduleRetry() {
        if (retrying) return
        val workManager = androidx.work.WorkManager.getInstance(context)
        val next = pendingStore.items.value.values.minOfOrNull { it.nextAttemptAt }
        if (next == null) {
            workManager.cancelUniqueWork(PendingDownloadWorker.WORK_NAME)
            return
        }
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(
                if (tokenStore.wifiOnlyDownloads) androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED
            )
            .build()
        val request = androidx.work.OneTimeWorkRequestBuilder<PendingDownloadWorker>()
            .setInitialDelay((next - System.currentTimeMillis()).coerceAtLeast(0), java.util.concurrent.TimeUnit.MILLISECONDS)
            .setConstraints(constraints)
            .build()
        workManager.enqueueUniqueWork(PendingDownloadWorker.WORK_NAME, androidx.work.ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Tries each pending book that's due, one at a time with 1–3 minutes between them,
     * stopping as soon as Audible's limit kicks in again. Returns once they've finished.
     */
    suspend fun retryDuePending() {
        val due = pendingStore.items.value.values
            .filter { it.nextAttemptAt <= System.currentTimeMillis() + 60_000 }
            .sortedBy { it.nextAttemptAt }
        debugLog.section("Retry pending downloads (${due.size} due)")
        requestLog.event("retry-run", "${due.size} due: ${due.joinToString(",") { it.asin }}")
        retrying = true
        try {
            retryEach(due)
        } finally {
            retrying = false
        }
        // Anything turned away again (or never tried) has a new time
        for (pending in pendingStore.items.value.values) {
            if (pending.nextAttemptAt <= System.currentTimeMillis()) {
                pendingStore.put(pending.copy(nextAttemptAt = maxOf(apiClient.throttledUntil, System.currentTimeMillis()) +
                    kotlin.random.Random.nextLong(5 * 60_000, 15 * 60_000)))
            }
        }
        scheduleRetry()
    }

    private suspend fun retryEach(due: List<PendingDownload>) {
        for ((i, pending) in due.withIndex()) {
            if (apiClient.isThrottled) {
                debugLog.log("DL", "Still limited by Audible — trying the rest later")
                break
            }
            if (i > 0) delay(kotlin.random.Random.nextLong(60_000, 180_000))
            val book = AudibleBook(
                asin = pending.asin,
                title = pending.title,
                authors = pending.author?.let { listOf(com.dividetask.audiobookreader.audible.api.AudiblePerson(it)) },
                runtimeLengthMin = pending.runtimeMinutes,
                productImages = pending.coverUrl?.let { com.dividetask.audiobookreader.audible.api.AudibleImages(image500 = it) }
            )
            debugLog.log("DL", "Retrying pending: ${pending.title}")
            queueDownload(book)
            // Wait for this book to finish before asking Audible for the next one
            while (activeAsin == book.asin || book.asin in _queuedAsins.value) delay(2_000)
        }
    }

    fun cancelDownload() {
        // Cancelling also stops automatic retries for it
        activeAsin?.let { pendingStore.remove(it) }
        scheduleRetry()
        currentDownloadJob?.cancel()
        downloadQueue.clear()
        _queuedAsins.value = emptySet()
        queuedTitles.clear()
        _downloadProgress.value = null
        debugLog.log("DL", "Download cancelled by user")
    }

    fun getQueuedTitle(asin: String): String = queuedTitles[asin] ?: asin

    fun isBookDownloaded(asin: String): Boolean {
        return completedAsins.contains(asin)
    }

    fun cleanupPartialDownloads() {
        val dir = audiobooksDir
        dir.listFiles()?.forEach { file ->
            val asin = file.nameWithoutExtension
            if ((file.name.endsWith(".aax") || file.name.endsWith(".m4b")) && !completedAsins.contains(asin)) {
                debugLog.log("DL", "Cleaning up partial file: ${file.name} (${file.length() / 1_048_576}MB)")
                file.delete()
            }
        }
    }
}
