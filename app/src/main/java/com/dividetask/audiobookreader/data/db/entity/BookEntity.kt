package com.dividetask.audiobookreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val coverUri: String?,
    val fileUri: String,
    val durationMs: Long,
    val currentPositionMs: Long = 0,
    val currentChapterIndex: Int = 0,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long = 0,
    /** True once chapter extraction has run, so it isn't retried on every open. */
    val chaptersChecked: Boolean = false,
    /** Audible ASIN from the file's tags; "" once the tags were read and had none. */
    val asin: String? = null
)
