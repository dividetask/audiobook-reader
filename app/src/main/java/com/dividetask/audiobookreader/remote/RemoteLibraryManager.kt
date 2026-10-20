package com.dividetask.audiobookreader.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.data.metadata.MetadataExtractor
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Progress of a server download; [fraction] is null while queued. */
data class RemoteDownload(val fraction: Float?)

/** Lists the books on the SFTP server and downloads them into the local library. */
@Singleton
class RemoteLibraryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sftp: SftpClient,
    private val settings: ServerSettings,
    private val tokenStore: AudibleTokenStore,
    private val bookRepository: BookRepository,
    private val metadataExtractor: MetadataExtractor,
    private val debugLog: AppDebugLog
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val jobs = ConcurrentHashMap<String, Job>()

    private val _files = MutableStateFlow<List<RemoteFile>>(emptyList())
    val files: StateFlow<List<RemoteFile>> = _files.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Active and queued downloads, keyed by [RemoteFile.relPath]. */
    private val _downloads = MutableStateFlow<Map<String, RemoteDownload>>(emptyMap())
    val downloads: StateFlow<Map<String, RemoteDownload>> = _downloads.asStateFlow()

    val isConfigured: Boolean get() = settings.isConfigured

    private val downloadDir: File
        get() = File(context.getExternalFilesDir(null), "remote_books")

    fun localFile(file: RemoteFile) = File(downloadDir, file.relPath)

    fun isOnDevice(file: RemoteFile): Boolean = localFile(file).let { it.exists() && it.length() == file.size }

    fun refresh() {
        if (_isLoading.value || !settings.isConfigured) return
        scope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _files.value = sftp.use { it.listAudio() }
                debugLog.log("SFTP", "Server has ${_files.value.size} audio files")
            } catch (e: Exception) {
                debugLog.log("SFTP", "List failed: ${e.message}")
                _error.value = e.message ?: "Could not reach server"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun download(file: RemoteFile) {
        if (file.relPath in _downloads.value) return
        if (tokenStore.wifiOnlyDownloads && !onWifi()) {
            _error.value = "WiFi only is on — connect to WiFi to download"
            return
        }
        _downloads.value = _downloads.value + (file.relPath to RemoteDownload(null))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                downloadMutex.withLock { downloadNow(file) }
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    debugLog.log("SFTP", "Download failed: ${file.relPath} — ${e.message}")
                    _error.value = "Download failed: ${e.message}"
                }
            } finally {
                _downloads.value = _downloads.value - file.relPath
                jobs.remove(file.relPath)
            }
        }
        jobs[file.relPath] = job
        job.start()
    }

    fun cancel(file: RemoteFile) {
        jobs[file.relPath]?.cancel()
    }

    fun clearError() { _error.value = null }

    private suspend fun downloadNow(file: RemoteFile) {
        val target = localFile(file)
        val part = File(target.parentFile, target.name + ".part")
        target.parentFile?.mkdirs()
        debugLog.section("Server download: ${file.relPath}")

        if (!isOnDevice(file)) {
            val job = kotlinx.coroutines.currentCoroutineContext()[Job]
            var lastUpdate = 0L
            sftp.use { conn ->
                conn.download(file.relPath, part, file.size) { done, total ->
                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 500) {
                        lastUpdate = now
                        val f = if (total > 0) done.toFloat() / total else 0f
                        _downloads.value = _downloads.value + (file.relPath to RemoteDownload(f))
                    }
                    job?.isActive != false
                }
            }
            job?.let { if (!it.isActive) throw CancellationException() }
            if (part.length() != file.size) throw java.io.IOException("Incomplete: ${part.length()} of ${file.size} bytes")
            target.delete()
            part.renameTo(target)
        }

        val uri = Uri.fromFile(target)
        if (bookRepository.getBookByFileUri(uri.toString()) != null) {
            debugLog.log("SFTP", "Already in library")
            return
        }
        val metadata = try { metadataExtractor.extract(uri) } catch (e: Exception) { null }
        val entity = if (metadata != null) {
            metadataExtractor.toBookEntity(uri, metadata).let {
                if (it.author == "Unknown Author" && file.folder.isNotEmpty()) it.copy(author = file.folder) else it
            }
        } else {
            com.dividetask.audiobookreader.data.db.entity.BookEntity(
                title = file.title, author = file.folder.ifEmpty { "Unknown Author" },
                coverUri = null, fileUri = uri.toString(), durationMs = 0
            )
        }
        bookRepository.insertBook(entity, metadata?.let { metadataExtractor.toChapterEntities(0, it) } ?: emptyList())
        debugLog.log("SFTP", "Added to library: ${entity.title}")
    }

    private fun onWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
