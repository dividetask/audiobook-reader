package com.dividetask.audiobookreader.ui.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.metadata.MetadataExtractor
import com.dividetask.audiobookreader.data.repository.BookRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val metadataExtractor: MetadataExtractor,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = bookRepository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    fun importBook(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
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
            } catch (e: Exception) {
                _importError.value = "Failed to import: ${e.message}"
            }
        }
    }

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
        }
    }

    fun clearError() {
        _importError.value = null
    }
}
