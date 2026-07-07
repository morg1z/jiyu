package com.haise.jiyu.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
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
        CustomSourceEntity::class,
        ReadHistoryEntity::class,
        MangaNoteEntity::class,
        MangaTagEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun translatedPageDao(): TranslatedPageDao
    abstract fun categoryDao(): CategoryDao
    abstract fun customSourceDao(): CustomSourceDao
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun mangaNoteDao(): MangaNoteDao
    abstract fun mangaTagDao(): MangaTagDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN lastReadAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapter ADD COLUMN scanlationGroup TEXT")
                // Verze 5 zavedla kategorie (CategoryEntity/MangaCategoryEntity) - chybely
                // tu CREATE TABLE prikazy, takze migrace z realneho v4 DB by spadla na
                // chybejicich tabulkach i pres "uspesnou" migraci scanlationGroup sloupce.
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `category` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL,
                        `colorHex` TEXT NOT NULL DEFAULT '#8B5CF6', PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `manga_category` (
                        `mangaId` TEXT NOT NULL, `categoryId` TEXT NOT NULL,
                        PRIMARY KEY(`mangaId`, `categoryId`),
                        FOREIGN KEY(`mangaId`) REFERENCES `manga`(`id`) ON DELETE CASCADE,
                        FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON DELETE CASCADE
                    )"""
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_manga_category_categoryId` ON `manga_category` (`categoryId`)"
                )
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `custom_source` (
                        `id` TEXT NOT NULL, `name` TEXT NOT NULL, `baseUrl` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_source ADD COLUMN listItemSelector TEXT")
                db.execSQL("ALTER TABLE custom_source ADD COLUMN titleLinkSelector TEXT")
                db.execSQL("ALTER TABLE custom_source ADD COLUMN descriptionSelector TEXT")
                db.execSQL("ALTER TABLE custom_source ADD COLUMN statusSelector TEXT")
                db.execSQL("ALTER TABLE custom_source ADD COLUMN chapterListSelector TEXT")
                db.execSQL("ALTER TABLE custom_source ADD COLUMN pageImageSelector TEXT")
            }
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `read_history` (
                        `chapterId` TEXT NOT NULL PRIMARY KEY,
                        `mangaId` TEXT NOT NULL,
                        `mangaTitle` TEXT NOT NULL,
                        `coverUrl` TEXT,
                        `chapterName` TEXT NOT NULL,
                        `readAt` INTEGER NOT NULL
                    )"""
                )
            }
        }
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN readerDirectionOverride TEXT")
            }
        }
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN author TEXT")
                db.execSQL("ALTER TABLE manga ADD COLUMN artist TEXT")
                db.execSQL("ALTER TABLE manga ADD COLUMN genres TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE manga ADD COLUMN year INTEGER")
            }
        }
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_inLibrary` ON `manga` (`inLibrary`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_lastReadAt` ON `manga` (`lastReadAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_mangaId` ON `chapter` (`mangaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_read` ON `chapter` (`read`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_downloadStatus` ON `chapter` (`downloadStatus`)")
            }
        }
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN autoDownload INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `manga_note` (
                        `mangaId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`mangaId`)
                    )"""
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `manga_tag` (
                        `mangaId` TEXT NOT NULL,
                        `tag` TEXT NOT NULL,
                        PRIMARY KEY(`mangaId`, `tag`)
                    )"""
                )
            }
        }
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN userRating INTEGER")
            }
        }
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN contentType TEXT NOT NULL DEFAULT 'MANGA'")
            }
        }
    }
}
