package com.dividetask.audiobookreader.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A series fetched from the Audible catalog; [lastSynced] decides when it is fetched again. */
@Entity(tableName = "series")
data class SeriesEntity(
    @PrimaryKey val seriesAsin: String,
    val title: String,
    val lastSynced: Long = System.currentTimeMillis()
)

/** One title in a series, owned or not. */
@Entity(
    tableName = "series_books",
    primaryKeys = ["seriesAsin", "asin"],
    indices = [Index("seriesAsin")]
)
data class SeriesBookEntity(
    val seriesAsin: String,
    val asin: String,
    val title: String,
    val author: String?,
    val sequence: String?,
    val coverUrl: String?
)

/**
 * The Audible catalog match for a book that isn't in the Audible library (a file added
 * from the phone or the server), keyed by its normalized title and author. A null
 * [asin] means nothing matched; it is looked up again after a while.
 */
@Entity(tableName = "series_lookups")
data class SeriesLookupEntity(
    @PrimaryKey val key: String,
    val asin: String?,
    val seriesAsin: String?,
    val seriesTitle: String?,
    val sequence: String?,
    val checkedAt: Long = System.currentTimeMillis()
)
