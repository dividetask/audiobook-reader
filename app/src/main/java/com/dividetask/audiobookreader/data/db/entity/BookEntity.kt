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
    val lastPlayed: Long = 0
)
