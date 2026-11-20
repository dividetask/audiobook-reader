package com.dividetask.audiobookreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dividetask.audiobookreader.data.db.entity.LibroBookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LibroBookDao {
    @Query("SELECT * FROM libro_books ORDER BY addedAt DESC")
    fun getAll(): Flow<List<LibroBookEntity>>

    @Query("SELECT * FROM libro_books ORDER BY addedAt DESC")
    suspend fun getAllOnce(): List<LibroBookEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(books: List<LibroBookEntity>)

    @Query("DELETE FROM libro_books WHERE isbn NOT IN (:isbns)")
    suspend fun deleteNotIn(isbns: List<String>)

    @Query("DELETE FROM libro_books")
    suspend fun deleteAll()
}
