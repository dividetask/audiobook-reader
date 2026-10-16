package com.dividetask.audiobookreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dividetask.audiobookreader.data.db.dao.AudibleBookDao
import com.dividetask.audiobookreader.data.db.dao.BookDao
import com.dividetask.audiobookreader.data.db.dao.ChapterDao
import com.dividetask.audiobookreader.data.db.dao.HistoryDao
import com.dividetask.audiobookreader.data.db.entity.AudibleBookEntity
import com.dividetask.audiobookreader.data.db.entity.BookEntity
import com.dividetask.audiobookreader.data.db.entity.ChapterEntity
import com.dividetask.audiobookreader.data.db.entity.ListenSessionEntity
import com.dividetask.audiobookreader.data.db.entity.PositionJumpEntity

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS audible_books (
                asin TEXT NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                author TEXT NOT NULL,
                narrator TEXT,
                runtimeMinutes INTEGER,
                coverUrl TEXT,
                seriesTitle TEXT,
                seriesSequence TEXT,
                purchaseDate TEXT,
                lastSynced INTEGER NOT NULL DEFAULT 0
            )
        """)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE books ADD COLUMN chaptersChecked INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS listen_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                startedAt INTEGER NOT NULL,
                endedAt INTEGER NOT NULL,
                startPositionMs INTEGER NOT NULL,
                endPositionMs INTEGER NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_listen_sessions_bookId ON listen_sessions (bookId)")
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS position_jumps (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                bookId INTEGER NOT NULL,
                timestamp INTEGER NOT NULL,
                fromPositionMs INTEGER NOT NULL,
                toPositionMs INTEGER NOT NULL,
                reason TEXT NOT NULL,
                FOREIGN KEY(bookId) REFERENCES books(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )
        """)
        db.execSQL("CREATE INDEX IF NOT EXISTS index_position_jumps_bookId ON position_jumps (bookId)")
    }
}

@Database(
    entities = [
        BookEntity::class, ChapterEntity::class, AudibleBookEntity::class,
        ListenSessionEntity::class, PositionJumpEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun audibleBookDao(): AudibleBookDao
    abstract fun historyDao(): HistoryDao
}
