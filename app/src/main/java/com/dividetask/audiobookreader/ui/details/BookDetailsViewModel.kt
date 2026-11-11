package com.dividetask.audiobookreader.ui.details

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.audible.api.AudibleBook
import com.dividetask.audiobookreader.audible.api.AudibleImages
import com.dividetask.audiobookreader.audible.api.AudiblePerson
import com.dividetask.audiobookreader.audible.download.AudibleDownloadManager
import com.dividetask.audiobookreader.audible.download.DownloadProgress
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import com.dividetask.audiobookreader.data.details.AudibleDetails
import com.dividetask.audiobookreader.data.details.BookDetailsRepository
import com.dividetask.audiobookreader.data.details.HardcoverBook
import com.dividetask.audiobookreader.data.details.HardcoverReview
import com.dividetask.audiobookreader.data.details.HardcoverUserReview
import com.dividetask.audiobookreader.data.details.Section
import com.dividetask.audiobookreader.data.details.SeriesBook
import com.dividetask.audiobookreader.data.details.StoreAvailability
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.player.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReviewerReviews(val username: String, val reviews: Section<List<HardcoverUserReview>>)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookDetailsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: BookDetailsRepository,
    private val bookRepository: BookRepository,
    private val audibleBookDao: AudibleBookDao,
    private val downloadManager: AudibleDownloadManager,
    private val playbackController: PlaybackController
) : ViewModel() {

    val asin: String = checkNotNull(savedStateHandle["asin"])

    val audible = MutableStateFlow<Section<AudibleDetails>>(Section.Loading)
    /** null when the book isn't part of a series. */
    val series = MutableStateFlow<Section<List<SeriesBook>>?>(null)
    val stores = MutableStateFlow<Section<List<StoreAvailability>>>(Section.Loading)
    val hardcover = MutableStateFlow<Section<HardcoverBook>>(Section.Loading)
    val reviewer = MutableStateFlow<ReviewerReviews?>(null)

    /** The downloaded copy in the local library, if any. */
    val localBook = MutableStateFlow<BookEntity?>(null)
    /** The book in the user's Audible library, if owned. */
    val owned = MutableStateFlow<AudibleBookEntity?>(null)

    val chapters: StateFlow<List<ChapterEntity>> = localBook
        .flatMapLatest { b -> if (b == null) flowOf(emptyList()) else bookRepository.getChapters(b.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val downloadProgress: StateFlow<DownloadProgress?> = downloadManager.downloadProgress
    val queuedAsins: StateFlow<Set<String>> = downloadManager.queuedAsins

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            localBook.value = bookRepository.getAllBooksOnce()
                .firstOrNull { it.fileUri.contains("/audiobooks/$asin.") }
            owned.value = audibleBookDao.getAllOnce().firstOrNull { it.asin == asin }

            val details = repository.audibleDetails(asin)
            audible.value = details
            val loaded = (details as? Section.Loaded)?.value
            val title = loaded?.title ?: owned.value?.title ?: localBook.value?.title ?: return@launch
            val author = loaded?.authors?.firstOrNull() ?: owned.value?.author ?: localBook.value?.author

            loaded?.series?.firstOrNull()?.asin?.let { seriesAsin ->
                series.value = Section.Loading
                launch { series.value = repository.seriesBooks(seriesAsin) }
            }
            launch { stores.value = Section.Loaded(repository.availability(title, author)) }
            launch { hardcover.value = repository.hardcover(title, author) }
        }
    }

    fun showReviewer(review: HardcoverReview) {
        val id = review.userId ?: return
        reviewer.value = ReviewerReviews(review.username, Section.Loading)
        viewModelScope.launch {
            val result = repository.hardcoverUserReviews(id)
            if (reviewer.value?.username == review.username) {
                reviewer.value = ReviewerReviews(review.username, result)
            }
        }
    }

    fun closeReviewer() {
        reviewer.value = null
    }

    fun isDownloaded(asin: String): Boolean = downloadManager.isBookDownloaded(asin)

    fun download() {
        val book = owned.value ?: return
        downloadManager.queueDownload(
            AudibleBook(
                asin = book.asin,
                title = book.title,
                authors = listOf(AudiblePerson(book.author)),
                runtimeLengthMin = book.runtimeMinutes,
                productImages = book.coverUrl?.let { AudibleImages(image500 = it) }
            )
        )
    }

    /** Chapter jumps only make sense when this book is the one loaded in the player. */
    val isPlayingThisBook: Boolean
        get() = localBook.value?.id?.let { it == playbackController.playbackState.value.bookId } == true

    fun jumpToChapter(chapter: ChapterEntity) {
        if (isPlayingThisBook) playbackController.seekTo(chapter.startPositionMs, "Chapter list")
    }
}
