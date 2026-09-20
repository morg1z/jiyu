package com.haise.jiyu.backup

import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Testy na [parseBackupJson] - čisté JSON→entity parsování vytažené z
 * [BackupManager.restoreFromJson] mimo `db.withTransaction`, aby šlo otestovat bez
 * Room databáze. Pokrývá hlavně zpětnou kompatibilitu se staršími formáty zálohy
 * (chybějící pole, `version` bez hodnoty) - přesně to, co by při chybě potichu
 * poškodilo obnovenou knihovnu, ne pád appky.
 */
class BackupManagerTest {

    private fun manga(
        id: String = "m1",
        extra: JSONObject.() -> Unit = {},
    ) = JSONObject().apply {
        put("id", id)
        put("sourceId", "src")
        put("url", "/m1")
        put("title", "Test Manga")
        extra()
    }

    private fun backupJson(build: JSONObject.() -> Unit) = JSONObject().apply(build).toString()

    @Test
    fun `a backup from a newer app version is rejected with a friendly message, not silently misparsed`() {
        val json = backupJson { put("version", BackupManager.BACKUP_VERSION + 1) }

        val ex = assertThrows(IllegalArgumentException::class.java) { parseBackupJson(json) }

        assertEquals(true, ex.message?.contains("novější verze"))
    }

    @Test
    fun `the oldest backups have no version field at all and are still accepted`() {
        // version chybelo odjakziva u nejstarsich zaloh - optInt("version", 1) je bere
        // jako format 1, ktery je <= BACKUP_VERSION, takze projdou.
        val json = backupJson { put("manga", JSONArray()) }

        val result = parseBackupJson(json)

        assertEquals(0, result.manga.size)
    }

    @Test
    fun `manga with only the required fields fills every optional field with its safe default`() {
        val json = backupJson { put("manga", JSONArray().put(manga())) }

        val result = parseBackupJson(json)
        val m = result.manga.single()

        assertNull("prazdny string se ma prevest na null, ne zustat prazdny", m.coverUrl)
        assertNull(m.description)
        assertNull(m.userRating)
        assertNull(m.year)
        assertNull(m.malId)
        assertNull(m.malScore)
        assertEquals("MANGA", m.contentType)
        assertEquals(true, m.inLibrary)
        assertEquals(false, m.autoDownload)
    }

    @Test
    fun `a userRating of exactly 0 is a real rating, not treated as missing`() {
        // optInt("userRating", -1).takeIf { it >= 0 } - regrese k chybe, kdyz by 0 (validni
        // hodnoceni) omylem splynulo s "chybi" (driv by na to bylo snadne sáhnout spatnym
        // defaultem/podminkou).
        val json = backupJson { put("manga", JSONArray().put(manga { put("userRating", 0) })) }

        val result = parseBackupJson(json)

        assertEquals(0, result.manga.single().userRating)
    }

    @Test
    fun `category assignments are flattened from each manga's categoryIds array`() {
        val json = backupJson {
            put("manga", JSONArray().put(
                manga { put("categoryIds", JSONArray().put("catA").put("catB")) }
            ))
        }

        val result = parseBackupJson(json)

        assertEquals(listOf("m1" to "catA", "m1" to "catB"), result.categoryAssignments)
    }

    @Test
    fun `chapterNumber survives a JSON round-trip as a float, including fractional chapters`() {
        val json = backupJson {
            put("chapters", JSONArray().put(JSONObject().apply {
                put("id", "c1"); put("mangaId", "m1"); put("sourceId", "src"); put("url", "/c1")
                put("name", "Ch 10.5"); put("chapterNumber", 10.5); put("dateUpload", 0L)
                put("read", true); put("lastPageRead", 3)
            }))
        }

        val result = parseBackupJson(json)

        assertEquals(10.5f, result.chapters.single().chapterNumber, 0.001f)
    }

    @Test
    fun `missing optional arrays (notes, tags, readHistory) parse as empty lists, not a crash`() {
        val json = backupJson { put("version", 1) }

        val result = parseBackupJson(json)

        assertEquals(0, result.notes.size)
        assertEquals(0, result.tags.size)
        assertEquals(0, result.readHistory.size)
        assertEquals(0, result.categories.size)
        assertEquals(0, result.customSources.size)
    }

    @Test
    fun `malformed JSON throws instead of silently returning an empty backup`() {
        assertThrows(org.json.JSONException::class.java) { parseBackupJson("{ not valid json") }
    }

    @Test
    fun `manga fields added in BACKUP_VERSION 4 survive a round-trip`() {
        val json = backupJson {
            put("manga", JSONArray().put(manga {
                put("inLibrary", false)
                put("lastUpdated", 111L)
                put("kitsuId", "k1")
                put("kitsuScore", 8.5)
                put("mangaUpdatesId", 222L)
                put("readingTimeMs", 333L)
                put("isFavorite", true)
                put("demographic", "Seinen")
                put("translationCompleted", 1)
                put("hasAnime", 0)
                put("finalChapter", "120")
                put("rating", 9.2)
                put("followCount", 42)
                put("rank", 7)
                put("alternateTitles", "[\"Alt Title\"]")
                put("translationContextNote", "hrdina je ve skutecnosti zena")
            }))
        }

        val m = parseBackupJson(json).manga.single()

        assertEquals(false, m.inLibrary)
        assertEquals(111L, m.lastUpdated)
        assertEquals("k1", m.kitsuId)
        assertEquals(8.5f, m.kitsuScore!!, 0.001f)
        assertEquals(222L, m.mangaUpdatesId)
        assertEquals(333L, m.readingTimeMs)
        assertEquals(true, m.isFavorite)
        assertEquals("Seinen", m.demographic)
        assertEquals(true, m.translationCompleted)
        assertEquals(false, m.hasAnime)
        assertEquals("120", m.finalChapter)
        assertEquals(9.2, m.rating!!, 0.001)
        assertEquals(42, m.followCount)
        assertEquals(7, m.rank)
        assertEquals("[\"Alt Title\"]", m.alternateTitles)
        assertEquals("hrdina je ve skutecnosti zena", m.translationContextNote)
    }

    @Test
    fun `a backup from before BACKUP_VERSION 4 without inLibrary still restores as in-library`() {
        // Stary export (verze nizsi nez 4) pole "inLibrary" vubec nemel - vsechno v nem byl
        // skutecny obsah knihovny uzivatele, takze chybejici pole musi znamenat true, ne false.
        val json = backupJson { put("manga", JSONArray().put(manga())) }

        val m = parseBackupJson(json).manga.single()

        assertEquals(true, m.inLibrary)
    }

    @Test
    fun `chapter fields added in BACKUP_VERSION 4 survive a round-trip`() {
        val json = backupJson {
            put("chapters", JSONArray().put(JSONObject().apply {
                put("id", "c1"); put("mangaId", "m1"); put("sourceId", "src"); put("url", "/c1")
                put("name", "Ch 1"); put("chapterNumber", 1.0); put("dateUpload", 0L)
                put("read", true); put("lastPageRead", 3)
                put("lastReadAt", 555L)
                put("lastScrollOffset", 777)
                put("downloadStatus", "DOWNLOADED")
                put("localPath", "/downloads/m1/c1")
                put("pageCount", 20)
                put("scanlationGroup", "Group X")
                put("volume", "2")
                put("groupsJson", "[{\"name\":\"Group X\"}]")
                put("discoveredAt", 999L)
                put("verifiedPageCount", 20)
                put("isFallbackSource", true)
                put("fallbackChapterId", "c2")
            }))
        }

        val c = parseBackupJson(json).chapters.single()

        assertEquals(555L, c.lastReadAt)
        assertEquals(777, c.lastScrollOffset)
        assertEquals(com.haise.jiyu.data.db.entity.DownloadStatus.DOWNLOADED, c.downloadStatus)
        assertEquals("/downloads/m1/c1", c.localPath)
        assertEquals(20, c.pageCount)
        assertEquals("Group X", c.scanlationGroup)
        assertEquals("2", c.volume)
        assertEquals("[{\"name\":\"Group X\"}]", c.groupsJson)
        assertEquals(999L, c.discoveredAt)
        assertEquals(20, c.verifiedPageCount)
        assertEquals(true, c.isFallbackSource)
        assertEquals("c2", c.fallbackChapterId)
    }

    @Test
    fun `a chapter with no downloadStatus in the backup defaults to NOT_DOWNLOADED, not a crash`() {
        val json = backupJson {
            put("chapters", JSONArray().put(JSONObject().apply {
                put("id", "c1"); put("mangaId", "m1"); put("sourceId", "src"); put("url", "/c1")
                put("name", "Ch 1"); put("chapterNumber", 1.0); put("dateUpload", 0L)
                put("read", false); put("lastPageRead", 0)
            }))
        }

        val c = parseBackupJson(json).chapters.single()

        assertEquals(com.haise.jiyu.data.db.entity.DownloadStatus.NOT_DOWNLOADED, c.downloadStatus)
    }
    @Test
    fun `one malformed chapter is skipped and counted instead of failing the whole restore`() {
        val json = backupJson {
            put("chapters", JSONArray()
                .put(JSONObject().apply { put("id", "broken") })
                .put(JSONObject().apply {
                    put("id", "c1"); put("mangaId", "m1"); put("sourceId", "src"); put("url", "/c1")
                    put("name", "Ch 1")
                }))
        }

        val parsed = parseBackupJson(json)

        assertEquals(listOf("c1"), parsed.chapters.map { it.id })
        assertEquals(1, parsed.skippedCount)
    }

    @Test
    fun `a chapter missing optional numeric fields falls back to defaults`() {
        val json = backupJson {
            put("chapters", JSONArray().put(JSONObject().apply {
                put("id", "c1"); put("mangaId", "m1"); put("sourceId", "src"); put("url", "/c1")
                put("name", "Ch 1")
            }))
        }

        val c = parseBackupJson(json).chapters.single()

        assertEquals(0f, c.chapterNumber, 0f)
        assertEquals(false, c.read)
        assertEquals(0, c.lastPageRead)
        assertEquals(0L, c.dateUpload)
    }

    @Test
    fun `a malformed manga is skipped together with its category assignments`() {
        val json = backupJson {
            put("manga", JSONArray()
                .put(JSONObject().apply { put("id", "broken") })
                .put(manga("m1") { put("categoryIds", JSONArray().put("cat1")) }))
        }

        val parsed = parseBackupJson(json)

        assertEquals(listOf("m1"), parsed.manga.map { it.id })
        assertEquals(listOf("m1" to "cat1"), parsed.categoryAssignments)
        assertEquals(1, parsed.skippedCount)
    }
    private fun chapter(id: String, read: Boolean, lastReadAt: Long, lastPageRead: Int = 0) = ChapterEntity(
        id = id, mangaId = "m1", sourceId = "src", url = "/$id", name = id, chapterNumber = 1f,
        dateUpload = 0L, read = read, lastPageRead = lastPageRead, lastReadAt = lastReadAt,
    )

    @Test
    fun `newer local reading progress survives a restore`() {
        val backup = listOf(chapter("c1", read = false, lastReadAt = 100L), chapter("c2", read = false, lastReadAt = 100L))
        val local = mapOf(
            "c1" to chapter("c1", read = true, lastReadAt = 500L, lastPageRead = 7),
            "c2" to chapter("c2", read = true, lastReadAt = 50L),
        )

        val merged = keepNewerLocalProgress(backup, local).associateBy { it.id }

        assertEquals(true, merged.getValue("c1").read)
        assertEquals(7, merged.getValue("c1").lastPageRead)
        assertEquals(false, merged.getValue("c2").read)
    }

    @Test
    fun `backup categories with the same name as a local one are mapped onto it, not duplicated`() {
        val backupCats = listOf(CategoryEntity("b1", "Favorites"), CategoryEntity("b2", "Nová"))
        val local = listOf(CategoryEntity("l1", "favorites"))
        val assignments = listOf("m1" to "b1", "m2" to "b2")

        val (toWrite, mapped) = mergeCategoriesByName(backupCats, assignments, local)

        assertEquals(listOf("b2"), toWrite.map { it.id })
        assertEquals(listOf("m1" to "l1", "m2" to "b2"), mapped)
    }
}
