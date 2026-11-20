package com.dividetask.audiobookreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A book bought on Libro.fm. Libro.fm identifies books by ISBN. */
@Entity(tableName = "libro_books")
data class LibroBookEntity(
    @PrimaryKey val isbn: String,
    val title: String,
    val author: String,
    val narrator: String?,
    val durationSec: Int?,
    val sizeBytes: Long?,
    val coverUrl: String?,
    val seriesTitle: String?,
    val seriesNum: String?,
    /** When it was added to the Libro.fm library, as Libro.fm sends it (ISO date), for sorting. */
    val addedAt: String?,
    val lastSynced: Long = System.currentTimeMillis()
)
