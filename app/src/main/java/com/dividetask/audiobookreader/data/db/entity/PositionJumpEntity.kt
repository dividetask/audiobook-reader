package com.dividetask.audiobookreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-initiated position change (seek, skip, chapter change) — lets the user get back to [fromPositionMs]. */
@Entity(
    tableName = "position_jumps",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bookId")]
)
data class PositionJumpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val timestamp: Long,
    val fromPositionMs: Long,
    val toPositionMs: Long,
    val reason: String
)
