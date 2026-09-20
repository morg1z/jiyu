package com.haise.jiyu.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Migrační test nad exportovanými schématy (`app/schemas`), doplněk k [AppDatabaseMigrationTest]:
 *  - řetěz migrací je souvislý a končí na nejnovějším exportovaném schématu (zapomenutá nebo přeskočená
 *    migrace prasklá tady, ne až u uživatele po aktualizaci),
 *  - z KAŽDÉ verze, pro kterou existuje schéma, se dá databáze zmigrovat na aktuální (Room při otevření
 *    ověří sloupce, indexy i cizí klíče proti cílovému schématu). Verze bez schématu (8, 9, 12, 16, ...) jsou
 *    pokryté průchodem z nižší verze.
 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseSchemaMigrationTest {

    private val dbName = "schema-migration-test.db"

    private fun schemaDir(): File =
        listOf("schemas", "app/schemas").map { File(it, "com.haise.jiyu.data.db.AppDatabase") }
            .first { it.isDirectory }

    private fun schemaVersions(): List<Int> =
        schemaDir().listFiles { f -> f.name.endsWith(".json") }!!
            .map { it.name.removeSuffix(".json").toInt() }.sorted()

    private val migrations get() = AppDatabase.ALL_MIGRATIONS

    @Test
    fun `migrations form one continuous chain that ends at the newest exported schema`() {
        val sorted = migrations.sortedBy { it.startVersion }
        sorted.zipWithNext().forEach { (a, b) ->
            assertEquals("mezi migracemi ${a.startVersion}->${a.endVersion} a ${b.startVersion}->${b.endVersion} je díra", a.endVersion, b.startVersion)
        }
        sorted.forEach { assertEquals("migrace ${it.startVersion}->${it.endVersion} nesmí přeskakovat verzi", it.startVersion + 1, it.endVersion)}
        assertEquals("nejnovější migrace musí končit na nejnovějším exportovaném schématu", schemaVersions().max(), sorted.last().endVersion)
    }

    @Test
    fun `every exported schema can be migrated to the current version`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val latest = schemaVersions().max()
        val failures = mutableListOf<String>()

        for (version in schemaVersions().filter { it < latest }) {
            context.deleteDatabase(dbName)
            createFromSchema(context, version)
            try {
                val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
                    .addMigrations(*migrations)
                    .build()
                try {
                    // Otevření spustí migrace a Room ověří výsledek proti cílovému schématu.
                    val open = db.openHelper.writableDatabase
                    open.query("PRAGMA integrity_check").use {
                        assertTrue(it.moveToFirst())
                        assertEquals("ok", it.getString(0))
                    }
                } finally {
                    db.close()
                }
            } catch (e: Throwable) {
                failures += "v$version -> v$latest: ${e.message?.lineSequence()?.first()}"
            }
        }
        context.deleteDatabase(dbName)

        assertTrue("migrace selhaly pro: $failures", failures.isEmpty())
    }

    /** Vytvoří prázdnou databázi přesně podle `createSql` z exportovaného schématu dané verze. */
    private fun createFromSchema(context: Context, version: Int) {
        val root = JSONObject(File(schemaDir(), "$version.json").readText()).getJSONObject("database")
        val entities = root.getJSONArray("entities")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (i in 0 until entities.length()) {
                            val entity = entities.getJSONObject(i)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            if (indices != null) {
                                for (j in 0 until indices.length()) {
                                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                                }
                            }
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        helper.writableDatabase
        helper.close()
    }
}
