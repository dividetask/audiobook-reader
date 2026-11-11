package com.dividetask.audiobookreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audible_books")
data class AudibleBookEntity(
    @PrimaryKey val asin: String,
    val title: String,
    val author: String,
    val narrator: String?,
    val runtimeMinutes: Int?,
    val coverUrl: String?,
    val seriesTitle: String?,
    val seriesSequence: String?,
    val seriesAsin: String? = null,
    val purchaseDate: String?,
    val lastSynced: Long = System.currentTimeMillis()
)
