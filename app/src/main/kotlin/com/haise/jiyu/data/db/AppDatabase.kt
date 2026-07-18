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
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.db.entity.TranslatedNovelEntity
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
        TranslatedNovelEntity::class,
        GlossaryEntity::class,
    ],
    version = 28,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mangaDao(): MangaDao
    abstract fun chapterDao(): ChapterDao
    abstract fun translatedPageDao(): TranslatedPageDao
    abstract fun translatedNovelDao(): TranslatedNovelDao
    abstract fun categoryDao(): CategoryDao
    abstract fun customSourceDao(): CustomSourceDao
    abstract fun readHistoryDao(): ReadHistoryDao
    abstract fun mangaNoteDao(): MangaNoteDao
    abstract fun mangaTagDao(): MangaTagDao
    abstract fun glossaryDao(): GlossaryDao

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
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapter ADD COLUMN volume TEXT")
            }
        }
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN excludeFromUpdates INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN malId INTEGER")
                db.execSQL("ALTER TABLE manga ADD COLUMN malScore REAL")
                db.execSQL("ALTER TABLE manga ADD COLUMN malStatus TEXT")
            }
        }
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE custom_source ADD COLUMN contentType TEXT NOT NULL DEFAULT 'MANGA'")
            }
        }
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN addedAt INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN readingStatus TEXT")
            }
        }
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Indices that were declared on ReadHistoryEntity but missing in MIGRATION_7_8 SQL
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_read_history_readAt` ON `read_history` (`readAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_read_history_mangaId` ON `read_history` (`mangaId`)")
                // Index for observeUpdates ORDER BY c.dateUpload DESC
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_dateUpload` ON `chapter` (`dateUpload`)")
            }
        }
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN kitsuId TEXT")
                db.execSQL("ALTER TABLE manga ADD COLUMN kitsuScore REAL")
                db.execSQL("ALTER TABLE manga ADD COLUMN mangaUpdatesId INTEGER")
            }
        }
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // manga — chybějící indexy z entit (sourceId, composite, nové sort indexy)
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_sourceId` ON `manga` (`sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_inLibrary_sourceId` ON `manga` (`inLibrary`, `sourceId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_title` ON `manga` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_addedAt` ON `manga` (`addedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_lastUpdated` ON `manga` (`lastUpdated`)")
                // chapter — chybějící composite a chapterNumber index
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_mangaId_read` ON `chapter` (`mangaId`, `read`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_mangaId_chapterNumber` ON `chapter` (`mangaId`, `chapterNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_chapter_chapterNumber` ON `chapter` (`chapterNumber`)")
            }
        }
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN readingTimeMs INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `translated_novel` (
                        `id` TEXT NOT NULL,
                        `translatedText` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
            }
        }
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `glossary_entry` (
                        `id` TEXT NOT NULL,
                        `mangaId` TEXT NOT NULL,
                        `sourceTerm` TEXT NOT NULL,
                        `targetTerm` TEXT NOT NULL,
                        `targetLanguage` TEXT NOT NULL,
                        PRIMARY KEY(`id`)
                    )"""
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_glossary_entry_mangaId_targetLanguage` ON `glossary_entry` (`mangaId`, `targetLanguage`)")
            }
        }
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE manga ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_manga_isFavorite` ON `manga` (`isFavorite`)")
            }
        }
    }
}
