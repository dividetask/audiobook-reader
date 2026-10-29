package com.dividetask.audiobookreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dividetask.audiobookreader.data.db.entity.SeriesBookEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesEntity
import com.dividetask.audiobookreader.data.db.entity.SeriesLookupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SeriesDao {
    @Query("SELECT * FROM series")
    fun getAllSeries(): Flow<List<SeriesEntity>>

    @Query("SELECT * FROM series")
    suspend fun getAllSeriesOnce(): List<SeriesEntity>

    @Query("SELECT * FROM series_books")
    fun getAllBooks(): Flow<List<SeriesBookEntity>>

    @Query("SELECT * FROM series_lookups")
    fun getAllLookups(): Flow<List<SeriesLookupEntity>>

    @Query("SELECT * FROM series_lookups")
    suspend fun getAllLookupsOnce(): List<SeriesLookupEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLookup(lookup: SeriesLookupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeries(series: SeriesEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBooks(books: List<SeriesBookEntity>)

    @Query("DELETE FROM series_books WHERE seriesAsin = :seriesAsin")
    suspend fun deleteBooks(seriesAsin: String)

    /** Swaps in a freshly fetched series, dropping titles Audible no longer lists. */
    @Transaction
    suspend fun replaceSeries(series: SeriesEntity, books: List<SeriesBookEntity>) {
        deleteBooks(series.seriesAsin)
        insertBooks(books)
        insertSeries(series)
    }
}
