package com.dividetask.audiobookreader.di

import android.content.Context
import androidx.room.Room
import com.dividetask.audiobookreader.data.db.AppDatabase
import com.dividetask.audiobookreader.data.db.MIGRATION_1_2
import com.dividetask.audiobookreader.data.db.MIGRATION_2_3
import com.dividetask.audiobookreader.data.db.MIGRATION_3_4
import com.dividetask.audiobookreader.data.db.MIGRATION_4_5
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.dao.BookDao
import com.dividetask.audiobookreader.data.db.dao.ChapterDao
import com.dividetask.audiobookreader.data.db.dao.HistoryDao
import com.dividetask.audiobookreader.data.db.dao.SeriesDao
import com.dividetask.audiobookreader.data.repository.BookRepository
import com.dividetask.audiobookreader.data.repository.BookRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "audiobook_reader.db"
        )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
    }

    @Provides
    fun provideBookDao(db: AppDatabase): BookDao = db.bookDao()

    @Provides
    fun provideChapterDao(db: AppDatabase): ChapterDao = db.chapterDao()

    @Provides
    fun provideAudibleBookDao(db: AppDatabase): AudibleBookDao = db.audibleBookDao()

    @Provides
    fun provideHistoryDao(db: AppDatabase): HistoryDao = db.historyDao()

    @Provides
    fun provideSeriesDao(db: AppDatabase): SeriesDao = db.seriesDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindBookRepository(impl: BookRepositoryImpl): BookRepository
}
