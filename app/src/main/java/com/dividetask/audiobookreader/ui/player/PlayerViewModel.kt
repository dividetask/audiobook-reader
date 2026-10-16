package com.dividetask.audiobookreader.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.player.PlaybackController
import com.dividetask.audiobookreader.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    val playbackController: PlaybackController
) : ViewModel() {

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book: StateFlow<BookEntity?> = _book.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState

    private var positionUpdateJob: kotlinx.coroutines.Job? = null

    fun loadBook(bookId: Long) {
        viewModelScope.launch {
            val bookEntity = bookRepository.getBookById(bookId) ?: return@launch
            _book.value = bookEntity

            if (bookEntity.fileUri.endsWith(".aax")) return@launch

            playbackController.connect()

            viewModelScope.launch {
                playbackController.isConnected.collect { connected ->
                    if (connected && playbackState.value.bookId != bookId) {
                        playbackController.play(
                            bookId = bookEntity.id,
                            fileUri = bookEntity.fileUri,
                            title = bookEntity.title,
                            author = bookEntity.author,
                            coverUri = bookEntity.coverUri,
                            startPositionMs = bookEntity.currentPositionMs
                        )
                        startPositionUpdates()
                    }
                }
            }
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (true) {
                delay(500)
                playbackController.updateState()
            }
        }
    }

    fun togglePlayPause() {
        playbackController.togglePlayPause()
    }

    fun seekTo(positionMs: Long) {
        playbackController.seekTo(positionMs)
    }

    fun skipForward() {
        playbackController.skipForward()
    }

    fun skipBackward() {
        playbackController.skipBackward()
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackController.setPlaybackSpeed(speed)
    }

    override fun onCleared() {
        super.onCleared()
        positionUpdateJob?.cancel()
    }
}
