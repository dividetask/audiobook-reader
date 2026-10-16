package com.dividetask.audiobookreader.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.db.entity.PositionJumpEntity
import com.dividetask.audiobookreader.data.metadata.ChapterLoader
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.repository.HistoryRepository
import com.dividetask.audiobookreader.player.PlaybackController
import com.dividetask.audiobookreader.player.PlaybackPreferences
import com.dividetask.audiobookreader.player.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val historyRepository: HistoryRepository,
    private val chapterLoader: ChapterLoader,
    playbackPreferences: PlaybackPreferences,
    val playbackController: PlaybackController
) : ViewModel() {

    private val _book = MutableStateFlow<BookEntity?>(null)
    val book: StateFlow<BookEntity?> = _book.asStateFlow()

    private val bookId = MutableStateFlow(-1L)

    val playbackState: StateFlow<PlaybackState> = playbackController.playbackState

    val skipBackSeconds: StateFlow<Int> = playbackPreferences.skipBackSeconds
    val skipForwardSeconds: StateFlow<Int> = playbackPreferences.skipForwardSeconds

    val chapters: StateFlow<List<ChapterEntity>> = bookId
        .flatMapLatest { id -> if (id < 0) flowOf(emptyList()) else bookRepository.getChapters(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Index into [chapters] of the chapter at the current position, or -1 if there are none. */
    val currentChapterIndex: StateFlow<Int> = combine(chapters, playbackState) { chapters, state ->
        chapterIndexAt(chapters, state.currentPositionMs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    val sessions: StateFlow<List<ListenSessionEntity>> = bookId
        .flatMapLatest { id -> if (id < 0) flowOf(emptyList()) else historyRepository.getSessions(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val jumps: StateFlow<List<PositionJumpEntity>> = bookId
        .flatMapLatest { id -> if (id < 0) flowOf(emptyList()) else historyRepository.getJumps(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var positionUpdateJob: kotlinx.coroutines.Job? = null

    fun loadBook(bookId: Long) {
        this.bookId.value = bookId
        viewModelScope.launch {
            val bookEntity = bookRepository.getBookById(bookId) ?: return@launch
            _book.value = bookEntity

            if (bookEntity.fileUri.endsWith(".aax")) return@launch

            launch { chapterLoader.ensureChapters(bookEntity) }

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
                    }
                    if (connected) startPositionUpdates()
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

    /** Restarts the current chapter, or goes to the previous one if within 3s of its start. */
    fun previousChapter() {
        val chapters = chapters.value
        val index = currentChapterIndex.value
        if (index < 0) return
        val position = playbackState.value.currentPositionMs
        val target = if (position - chapters[index].startPositionMs > 3_000 || index == 0) {
            chapters[index].startPositionMs
        } else {
            chapters[index - 1].startPositionMs
        }
        playbackController.seekTo(target, "Previous chapter")
    }

    fun nextChapter() {
        val chapters = chapters.value
        val index = currentChapterIndex.value
        if (index < 0 || index >= chapters.lastIndex) return
        playbackController.seekTo(chapters[index + 1].startPositionMs, "Next chapter")
    }

    fun jumpToChapter(index: Int) {
        val chapter = chapters.value.getOrNull(index) ?: return
        playbackController.seekTo(chapter.startPositionMs, "Chapter list")
    }

    fun jumpToHistory(positionMs: Long) {
        playbackController.seekTo(positionMs, "History")
    }

    fun setPlaybackSpeed(speed: Float) {
        playbackController.setPlaybackSpeed(speed)
    }

    override fun onCleared() {
        super.onCleared()
        positionUpdateJob?.cancel()
    }
}

fun chapterIndexAt(chapters: List<ChapterEntity>, positionMs: Long): Int {
    if (chapters.isEmpty()) return -1
    return chapters.indexOfLast { it.startPositionMs <= positionMs }.coerceAtLeast(0)
}

/** End of a chapter, falling back to the next chapter's start or the book's end if the stored end is missing. */
fun chapterEndMs(chapters: List<ChapterEntity>, index: Int, bookDurationMs: Long): Long {
    val chapter = chapters[index]
    if (chapter.endPositionMs > chapter.startPositionMs) return chapter.endPositionMs
    return chapters.getOrNull(index + 1)?.startPositionMs ?: bookDurationMs
}
