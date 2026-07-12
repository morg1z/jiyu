package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.data.db.entity.CategoryEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Simuluje realneho uzivatele s existujici v4 databazi (pred zavedenim kategorii)
 * a otevre ji pres Room s ostrymi migracemi - presne to, co udela appka po
 * aktualizaci z Google Play. Pokud migrace nevytvori vsechny tabulky ocekavane
 * aktualni verzi entit, Room pri otevirani sam vyhodi IllegalStateException
 * (identity hash mismatch) - test to overi bez nutnosti rucne parsovat schema.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private fun context() = ApplicationProvider.getApplicationContext<Context>()

    private fun createV4Database(context: Context) {
        context.deleteDatabase(dbName)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """CREATE TABLE `manga` (`id` TEXT NOT NULL, `sourceId` TEXT NOT NULL,
                                `url` TEXT NOT NULL, `title` TEXT NOT NULL, `coverUrl` TEXT,
                                `description` TEXT, `status` TEXT, `inLibrary` INTEGER NOT NULL,
                                `lastUpdated` INTEGER NOT NULL, `lastReadChapterId` TEXT,
                                `lastReadAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"""
                        )
                        db.execSQL(
                            """CREATE TABLE `chapter` (`id` TEXT NOT NULL, `mangaId` TEXT NOT NULL,
                                `sourceId` TEXT NOT NULL, `url` TEXT NOT NULL, `name` TEXT NOT NULL,
                                `chapterNumber` REAL NOT NULL, `dateUpload` INTEGER NOT NULL,
                                `read` INTEGER NOT NULL, `lastPageRead` INTEGER NOT NULL,
                                `downloadStatus` TEXT NOT NULL, `localPath` TEXT,
                                `pageCount` INTEGER NOT NULL, PRIMARY KEY(`id`))"""
                        )
                        db.execSQL(
                            """CREATE TABLE `translated_page` (`id` TEXT NOT NULL,
                                `blocksJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL,
                                PRIMARY KEY(`id`))"""
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase // vytvori soubor na disku s user_version = 4
        helper.close()
    }

    @Test
    fun `migrating a real v4 database all the way to current version creates category and custom_source tables`() = runTest {
        val context = context()
        createV4Database(context)

        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14,
                AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
                AppDatabase.MIGRATION_16_17,
                AppDatabase.MIGRATION_17_18,
                AppDatabase.MIGRATION_18_19,
                AppDatabase.MIGRATION_19_20,
                AppDatabase.MIGRATION_20_21,
                AppDatabase.MIGRATION_21_22,
                AppDatabase.MIGRATION_22_23,
                AppDatabase.MIGRATION_23_24,
                AppDatabase.MIGRATION_24_25,
            )
            .build()

        // Otevreni + Room identity-hash validace probehne tady; pokud migrace
        // nevytvori ocekavane tabulky/sloupce, spadne tu s IllegalStateException.
        db.openHelper.writableDatabase

        db.categoryDao().upsert(CategoryEntity(id = "c1", name = "Test"))
        assertEquals(1, db.categoryDao().getAllOnce().size)

        val chapters = db.chapterDao()
        // scanlationGroup pridany v ramci stejne migrace musi byt citelny (nullable, default null)
        assertEquals(0, chapters.countForManga("neexistujici"))

        // Sloupce pridane v MIGRATION_22_23/23_24/24_25 (Kitsu/MangaUpdates tracking,
        // DB indexy, per-manga cas cteni) - tyto migrace jeste nebyly overeny.
        val manga = com.haise.jiyu.data.db.entity.MangaEntity(
            id = "m1", sourceId = "mangadex", url = "https://example.com/m1", title = "Test",
            coverUrl = null, description = null, status = null, inLibrary = true,
        )
        db.mangaDao().upsert(manga)
        db.mangaDao().setKitsuId("m1", "kitsu-1")
        db.mangaDao().setMangaUpdatesId("m1", 42L)
        db.mangaDao().addReadingTime("m1", 5000L)

        val result = db.mangaDao().getById("m1")!!
        assertEquals("kitsu-1", result.kitsuId)
        assertEquals(42L, result.mangaUpdatesId)
        assertEquals(5000L, result.readingTimeMs)

        db.close()
        context.deleteDatabase(dbName)
    }
}
