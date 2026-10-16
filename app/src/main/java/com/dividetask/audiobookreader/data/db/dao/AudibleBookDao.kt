package com.dividetask.audiobookreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudibleBookDao {
    @Query("SELECT * FROM audible_books ORDER BY purchaseDate DESC")
    fun getAll(): Flow<List<AudibleBookEntity>>

    @Query("SELECT * FROM audible_books ORDER BY purchaseDate DESC")
    suspend fun getAllOnce(): List<AudibleBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<AudibleBookEntity>)

    @Query("DELETE FROM audible_books WHERE asin NOT IN (:asins)")
    suspend fun deleteNotIn(asins: List<String>)

    @Query("SELECT COUNT(*) FROM audible_books")
    suspend fun count(): Int
}
