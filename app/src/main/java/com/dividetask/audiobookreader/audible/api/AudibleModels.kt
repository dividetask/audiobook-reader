package com.dividetask.audiobookreader.audible.api

import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AudibleLibraryResponse(
    val items: List<AudibleBook> = emptyList()
)

@Serializable
data class AudibleBook(
    val asin: String = "",
    val title: String = "",
    @SerialName("subtitle") val subtitle: String? = null,
    @SerialName("authors") val authors: List<AudiblePerson>? = null,
    @SerialName("narrators") val narrators: List<AudiblePerson>? = null,
    @SerialName("runtime_length_min") val runtimeLengthMin: Int? = null,
    @SerialName("product_images") val productImages: AudibleImages? = null,
    @SerialName("series") val series: List<AudibleSeries>? = null,
    @SerialName("purchase_date") val purchaseDate: String? = null
)

@Serializable
data class AudiblePerson(
    val name: String = ""
)

@Serializable
data class AudibleImages(
    @SerialName("500") val image500: String? = null,
    @SerialName("1024") val image1024: String? = null
)

@Serializable
data class AudibleSeries(
    val asin: String? = null,
    val title: String? = null,
    @SerialName("sequence") val sequence: String? = null
)

@Serializable
data class AudibleChapterResponse(
    @SerialName("content_metadata") val contentMetadata: AudibleContentMetadata? = null
)

@Serializable
data class AudibleContentMetadata(
    @SerialName("chapter_info") val chapterInfo: AudibleChapterInfo? = null
)

@Serializable
data class AudibleChapterInfo(
    val chapters: List<AudibleChapter> = emptyList()
)

@Serializable
data class AudibleChapter(
    val title: String = "",
    @SerialName("start_offset_ms") val startOffsetMs: Long = 0,
    @SerialName("length_ms") val lengthMs: Long = 0,
    val chapters: List<AudibleChapter>? = null
)

fun AudibleBook.toEntity() = AudibleBookEntity(
    asin = asin,
    title = title,
    author = authors?.firstOrNull()?.name ?: "Unknown Author",
    narrator = narrators?.firstOrNull()?.name,
    runtimeMinutes = runtimeLengthMin,
    coverUrl = productImages?.image500 ?: productImages?.image1024,
    seriesTitle = series?.firstOrNull()?.title,
    seriesSequence = series?.firstOrNull()?.sequence,
    purchaseDate = purchaseDate
)
