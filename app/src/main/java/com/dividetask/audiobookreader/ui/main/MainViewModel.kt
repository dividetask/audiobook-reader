package com.dividetask.audiobookreader.ui.main

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.audible.api.AudibleApiClient
import com.dividetask.audiobookreader.audible.api.AudibleBook
import com.dividetask.audiobookreader.audible.api.toEntity
import com.dividetask.audiobookreader.audible.auth.AudibleTokenStore
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.audible.download.DownloadProgress
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.metadata.MetadataExtractor
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.series.SeriesIndex
import com.dividetask.audiobookreader.data.series.SeriesRepository
import com.dividetask.audiobookreader.debug.AppDebugLog
import com.dividetask.audiobookreader.ui.common.ListSortPreferences
import com.dividetask.audiobookreader.ui.common.SortOrder
import com.dividetask.audiobookreader.ui.common.SortTab
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val audibleBookDao: AudibleBookDao,
    private val apiClient: AudibleApiClient,
    private val tokenStore: AudibleTokenStore,
    private val downloadManager: AudibleDownloadManager,
    private val metadataExtractor: MetadataExtractor,
    private val converter: com.dividetask.audiobookreader.audible.download.AaxConverter,
    private val activationBytesProvider: com.dividetask.audiobookreader.audible.auth.ActivationBytesProvider,
    private val aaxImporter: com.dividetask.audiobookreader.audible.download.AaxImporter,
    private val seriesRepository: SeriesRepository,
    private val sortPreferences: ListSortPreferences,
    val playbackController: com.dividetask.audiobookreader.player.PlaybackController,
    private val debugLog: AppDebugLog,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val localBooks: StateFlow<List<BookEntity>> = bookRepository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            convertPendingAaxFiles()
        }
    }

    val audibleBooks: StateFlow<List<AudibleBookEntity>> = audibleBookDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val seriesIndex: StateFlow<SeriesIndex> = seriesRepository.index
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeriesIndex())

    val sortOrders: StateFlow<Map<SortTab, SortOrder>> = sortPreferences.orders

    fun setSortOrder(tab: SortTab, order: SortOrder) = sortPreferences.setOrder(tab, order)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _convertingBookId = MutableStateFlow<Long?>(null)
    val convertingBookId: StateFlow<Long?> = _convertingBookId.asStateFlow()

    val downloadProgress: StateFlow<DownloadProgress?> = downloadManager.downloadProgress
    val queuedAsins: StateFlow<Set<String>> = downloadManager.queuedAsins
    val pendingDownloads = downloadManager.pendingDownloads
    val websiteOffer = downloadManager.websiteOffer

    fun dismissWebsiteOffer() = downloadManager.dismissWebsiteOffer()

    fun removePending(asin: String) = downloadManager.removePending(asin)

    val isAudibleAuthenticated: Boolean
        get() = tokenStore.isAuthenticated

    private var lastSyncTime = 0L
    private val syncCooldownMs = 300_000L // 5 minutes

    /**
     * Opening a tab syncs at most every 5 minutes; [force] (pull to refresh) fetches unless
     * the last sync was under 30 s ago, so a pull right after opening the tab isn't a
     * second library request.
     */
    fun syncAudibleLibrary(force: Boolean = false) {
        if (!tokenStore.isAuthenticated || _isSyncing.value) return
        val now = System.currentTimeMillis()
        val cooldown = if (force) FORCED_SYNC_COOLDOWN_MS else syncCooldownMs
        if (now - lastSyncTime < cooldown) {
            if (force) debugLog.log("SYNC", "Library synced ${(now - lastSyncTime) / 1000}s ago — not fetching again")
            return
        }
        lastSyncTime = now

        viewModelScope.launch {
            _isSyncing.value = true
            debugLog.section("Audible Sync")
            try {
                val result = apiClient.getLibrary()
                val books = result.data ?: emptyList()
                debugLog.log("SYNC", "Fetched ${books.size} books from API")

                if (books.isNotEmpty()) {
                    val entities = books.map { it.toEntity() }
                    audibleBookDao.insertAll(entities)
                    audibleBookDao.deleteNotIn(entities.map { it.asin })
                    debugLog.log("SYNC", "Cache updated")
                }
                verifyDownloadedFiles()
                seriesRepository.refresh()
            } catch (e: Exception) {
                debugLog.log("SYNC", "Error: ${e.message}")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    val aaxImportStatus = aaxImporter.status

    fun clearAaxImportStatus() = aaxImporter.clearStatus()

    /**
     * Picked files can be several books, some split into parts (e.g. one file per chapter).
     * Audio files with the same album tag are one book; files with no album tag are grouped
     * by folder. A book of several files is joined into one; .aax files are each their own
     * book. An image is used as the cover of the books in its folder.
     */
    fun importLocalBooks(uris: List<Uri>) {
        // A single file is a book whatever its name says
        if (uris.size == 1) return importLocalBook(uris[0])
        viewModelScope.launch(Dispatchers.IO) {
            val files = uris.map { PickedFile(it, displayName(it), folderOf(it)) }
            fun isType(f: PickedFile, exts: List<String>) = exts.any { f.name.endsWith(".$it", ignoreCase = true) }
            val images = files.filter { isType(it, IMAGE_EXTENSIONS) }
            files.filter { it.name.endsWith(".aax", ignoreCase = true) }.forEach { importLocalBook(it.uri) }
            val books = files.filter { isType(it, AUDIO_EXTENSIONS) }.groupBy { f ->
                metadataExtractor.readAlbum(f.uri)?.let { "album:" + SeriesIndex.normalize(it) } ?: "folder:${f.folder}"
            }.values
            debugLog.log("IMPORT", "Picked ${files.size} files: ${books.size} book(s) of ${books.joinToString { it.size.toString() }} file(s)")
            for (parts in books) {
                if (parts.size == 1) {
                    importLocalBook(parts[0].uri)
                } else {
                    val cover = images.filter { it.folder == parts[0].folder }
                    aaxImporter.importJoined((parts + cover).map { it.uri to it.name })
                }
            }
        }
    }

    private class PickedFile(val uri: Uri, val name: String, val folder: String)

    /** The folder a picked file is in, from its document ID (e.g. "728D-3099:Books/One/01.mp3"). */
    private fun folderOf(uri: Uri): String = try {
        android.provider.DocumentsContract.getDocumentId(uri).substringBeforeLast('/', "")
    } catch (e: Exception) {
        ""
    }

    fun importLocalBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val name = displayName(uri)
                if (name.endsWith(".aax", ignoreCase = true)) {
                    // Copied into the app, converted, and matched to the Audible library
                    aaxImporter.import(uri, name)
                    return@launch
                }
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val existing = bookRepository.getBookByFileUri(uri.toString())
                if (existing != null) {
                    _importError.value = "Book already in library"
                    return@launch
                }
                val metadata = metadataExtractor.extract(uri)
                val bookEntity = metadataExtractor.toBookEntity(uri, metadata)
                val chapters = metadataExtractor.toChapterEntities(0, metadata)
                bookRepository.insertBook(bookEntity, chapters)
                // Find its series on Audible now rather than at the next library sync
                seriesRepository.refresh()
            } catch (e: Exception) {
                _importError.value = "Failed to import: ${e.message}"
            }
        }
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.let { return it } }
        return uri.lastPathSegment ?: ""
    }

    /**
     * Removes the book and deletes its file if the app made it (Audible downloads,
     * imports). Files picked from the phone's own storage are left alone.
     */
    fun deleteLocalBook(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepository.getBookById(bookId)
            bookRepository.deleteBook(bookId)
            book ?: return@launch
            val uri = Uri.parse(book.fileUri)
            val appDir = context.getExternalFilesDir(null)?.canonicalPath
            val path = uri.path
            if (uri.scheme == "file" && path != null && appDir != null) {
                val file = java.io.File(path).canonicalFile
                if (file.path.startsWith(appDir + java.io.File.separator)) {
                    // An Audible book may also still have its .aax next to the converted file
                    listOf(file, java.io.File(file.parentFile, "${file.nameWithoutExtension}.aax"))
                        .filter { it.exists() && it.delete() }
                        .forEach { debugLog.log("LIB", "Deleted file: ${it.name}") }
                }
            } else if (uri.scheme == "content") {
                try {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // Permission was never persisted
                }
            }
        }
    }

    fun downloadAudibleBook(audibleBook: AudibleBookEntity) {
        val book = AudibleBook(
            asin = audibleBook.asin,
            title = audibleBook.title,
            authors = listOf(com.dividetask.audiobookreader.audible.api.AudiblePerson(audibleBook.author)),
            runtimeLengthMin = audibleBook.runtimeMinutes,
            productImages = audibleBook.coverUrl?.let {
                com.dividetask.audiobookreader.audible.api.AudibleImages(image500 = it)
            }
        )
        downloadManager.queueDownload(book)
    }

    fun cancelDownload() = downloadManager.cancelDownload()
    fun isBookDownloaded(asin: String): Boolean = downloadManager.isBookDownloaded(asin)
    fun getQueuedTitle(asin: String): String = downloadManager.getQueuedTitle(asin)

    fun deleteDownload(asin: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = java.io.File(context.getExternalFilesDir(null), "audiobooks")
            listOf(".aax", ".m4b", ".m4a", ".mp3", ".ogg", ".flac").forEach { ext ->
                val file = java.io.File(dir, "$asin$ext")
                if (file.exists()) {
                    file.delete()
                    debugLog.log("DL", "Deleted file: $asin$ext")
                }
            }
            val books = bookRepository.getAllBooksOnce()
            books.filter { it.fileUri.contains(asin) }.forEach {
                bookRepository.deleteBook(it.id)
                debugLog.log("DL", "Removed from library: ${it.title}")
            }
        }
    }

    private suspend fun verifyDownloadedFiles() {
        val books = bookRepository.getAllBooksOnce()
        var removed = 0
        books.forEach { book ->
            if (book.fileUri.contains("/audiobooks/")) {
                val filePath = android.net.Uri.parse(book.fileUri).path ?: return@forEach
                val file = java.io.File(filePath)
                // Also check if AAX exists (might be awaiting conversion)
                val baseName = file.nameWithoutExtension
                val dir = file.parentFile
                val aaxExists = dir != null && java.io.File(dir, "$baseName.aax").exists()
                if (!file.exists() && !aaxExists) {
                    bookRepository.deleteBook(book.id)
                    removed++
                    debugLog.log("SYNC", "Removed missing file: ${book.title}")
                }
            }
        }
        if (removed > 0) debugLog.log("SYNC", "Cleaned up $removed books with missing files")
    }

    private suspend fun convertPendingAaxFiles() {
        val books = bookRepository.getAllBooksOnce()
        val aaxBooks = books.filter { it.fileUri.endsWith(".aax") }
        if (aaxBooks.isEmpty()) return

        debugLog.section("Convert Pending AAX Files")
        debugLog.log("CONV", "Found ${aaxBooks.size} unconverted AAX files")

        if (!tokenStore.isAuthenticated) {
            debugLog.log("CONV", "Not authenticated — cannot get activation bytes")
            return
        }

        val activationBytes = activationBytesProvider.getActivationBytes()
        if (activationBytes == null) {
            debugLog.log("CONV", "Could not get activation bytes — skipping conversion")
            return
        }

        for (book in aaxBooks) {
            val aaxPath = android.net.Uri.parse(book.fileUri).path ?: continue
            val aaxFile = java.io.File(aaxPath)
            if (!aaxFile.exists()) {
                debugLog.log("CONV", "AAX file missing for: ${book.title}")
                continue
            }

            _convertingBookId.value = book.id
            debugLog.log("CONV", "Converting: ${book.title}")
            val m4bFile = converter.convert(aaxFile, activationBytes)
            if (m4bFile != null && m4bFile.exists() && m4bFile.length() > 1000) {
                bookRepository.updateFileUri(book.id, android.net.Uri.fromFile(m4bFile).toString())
                aaxFile.delete()
                debugLog.log("CONV", "Converted and updated: ${book.title}")
            } else {
                debugLog.log("CONV", "Conversion failed for: ${book.title}")
            }
            _convertingBookId.value = null
        }
    }

    fun reconvertBook(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepository.getBookById(bookId) ?: return@launch
            val filePath = android.net.Uri.parse(book.fileUri).path ?: return@launch
            val file = java.io.File(filePath)

            if (file.name.endsWith(".m4b")) {
                debugLog.section("Re-convert: ${book.title}")

                val aaxFile = java.io.File(file.parent, file.nameWithoutExtension + ".aax")
                if (aaxFile.exists()) {
                    debugLog.log("CONV", "Found original AAX, re-converting")
                    val activationBytes = activationBytesProvider.getActivationBytes() ?: run {
                        debugLog.log("CONV", "No activation bytes")
                        return@launch
                    }
                    _convertingBookId.value = bookId
                    val m4bFile = converter.convert(aaxFile, activationBytes)
                    if (m4bFile != null && m4bFile.exists() && m4bFile.length() > 1000) {
                        file.delete()
                        bookRepository.updateFileUri(bookId, android.net.Uri.fromFile(m4bFile).toString())
                        debugLog.log("CONV", "Re-conversion successful")
                    }
                    _convertingBookId.value = null
                } else {
                    debugLog.log("CONV", "No AAX file — need to re-download")
                    file.delete()
                    bookRepository.deleteBook(bookId)
                    debugLog.log("CONV", "Deleted M4B and library entry — re-download from Audible tab")
                    _importError.value = "Book removed — re-download from the Audible tab"
                }
            }
        }
    }

    fun retryConversion(bookId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val book = bookRepository.getBookById(bookId) ?: return@launch
            if (!book.fileUri.endsWith(".aax")) return@launch
            val aaxPath = android.net.Uri.parse(book.fileUri).path ?: return@launch
            val aaxFile = java.io.File(aaxPath)
            if (!aaxFile.exists()) return@launch

            val activationBytes = activationBytesProvider.getActivationBytes() ?: return@launch
            _convertingBookId.value = bookId
            val outFile = converter.convert(aaxFile, activationBytes)
            if (outFile != null && outFile.exists() && outFile.length() > 1000) {
                bookRepository.updateFileUri(bookId, android.net.Uri.fromFile(outFile).toString())
                aaxFile.delete()
            }
            _convertingBookId.value = null
        }
    }

    fun checkFiles(book: BookEntity): Pair<Boolean, Boolean> {
        val filePath = android.net.Uri.parse(book.fileUri).path ?: return false to false
        val file = java.io.File(filePath)
        val dir = file.parentFile ?: return false to false
        val baseName = file.nameWithoutExtension
        val hasAax = java.io.File(dir, "$baseName.aax").exists()
        val hasConverted = file.exists() && !file.name.endsWith(".aax")
        return hasAax to hasConverted
    }

    fun deleteConvertedFile(book: BookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val filePath = android.net.Uri.parse(book.fileUri).path ?: return@launch
            val file = java.io.File(filePath)
            if (file.exists() && !file.name.endsWith(".aax")) {
                file.delete()
                debugLog.log("DL", "Deleted converted file: ${file.name}")
            }
            // Check if AAX exists — if so, update fileUri to point to it
            val aaxFile = java.io.File(file.parent, "${file.nameWithoutExtension}.aax")
            if (aaxFile.exists()) {
                bookRepository.updateFileUri(book.id, android.net.Uri.fromFile(aaxFile).toString())
                debugLog.log("DL", "Updated to AAX: ${aaxFile.name}")
            }
        }
    }

    fun deleteAaxFile(book: BookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val filePath = android.net.Uri.parse(book.fileUri).path ?: return@launch
            val dir = java.io.File(filePath).parentFile ?: return@launch
            val baseName = java.io.File(filePath).nameWithoutExtension
            val aaxFile = java.io.File(dir, "$baseName.aax")
            if (aaxFile.exists()) {
                aaxFile.delete()
                debugLog.log("DL", "Deleted AAX: ${aaxFile.name}")
            }
        }
    }

    fun redownloadAax(book: BookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val audibleBooks = audibleBookDao.getAllOnce()
            val match = audibleBooks.find { book.title == it.title }
            if (match != null) {
                debugLog.log("DL", "Re-downloading AAX: ${match.title}")
                downloadAudibleBook(match)
            } else {
                debugLog.log("DL", "No matching Audible book for: ${book.title}")
            }
        }
    }

    fun deleteFileAndRedownload(book: BookEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val filePath = android.net.Uri.parse(book.fileUri).path
            if (filePath != null) {
                java.io.File(filePath).delete()
                debugLog.log("DL", "Deleted file: ${java.io.File(filePath).name}")
            }
            bookRepository.deleteBook(book.id)
            debugLog.log("DL", "Removed from library: ${book.title}")

            // Find the matching Audible book and queue re-download
            val audibleBooks = audibleBookDao.getAllOnce()
            val match = audibleBooks.find { book.title == it.title }
            if (match != null) {
                debugLog.log("DL", "Re-downloading: ${match.title}")
                downloadAudibleBook(match)
            } else {
                debugLog.log("DL", "No matching Audible book found for re-download")
            }
        }
    }

    fun clearError() { _importError.value = null }

    private companion object {
        val AUDIO_EXTENSIONS = listOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "flac", "wav", "wma")
        val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png")
        const val FORCED_SYNC_COOLDOWN_MS = 30_000L
    }
}
