package com.haise.jiyu.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.TranslatedPageEntity

class Converters {
    @TypeConverter
    fun fromDownloadStatus(status: DownloadStatus): String = status.name

    @TypeConverter
    fun toDownloadStatus(value: String): DownloadStatus = DownloadStatus.valueOf(value)
}

@Database(
    entities = [
        MangaEntity::class,
        ChapterEntity::class,
        TranslatedPageEntity::class,
        CategoryEntity::class,
        MangaCategoryEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun translatedPageDao(): TranslatedPageDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapter ADD COLUMN scanlationGroup TEXT")
            }
        }
    }
}
