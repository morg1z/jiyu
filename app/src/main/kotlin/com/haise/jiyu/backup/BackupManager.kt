package com.haise.jiyu.backup

import android.content.Context
import android.net.Uri
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MangaRepository,
) {

    // ── Export ────────────────────────────────────────────────────────────────

    suspend fun exportToUri(uri: Uri): Result<Unit> = runCatching {
        val json = buildBackupJson()
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            ?: error("Nelze otevřít výstupní soubor")
    }

    private suspend fun buildBackupJson(): String {
        val mangaList     = repository.getAllLibraryManga()
        val categories    = repository.getAllCategories()
        val allChapters   = repository.getAllLibraryChapters()
        val customSources = repository.getAllCustomSourcesOnce()

        val root = JSONObject().apply {
            put("version", 2)
            put("exportedAt", java.time.Instant.now().toString())

            put("categories", JSONArray().also { arr ->
                categories.forEach { cat ->
                    arr.put(JSONObject().apply {
                        put("id",       cat.id)
                        put("name",     cat.name)
                        put("colorHex", cat.colorHex)
                    })
                }
            })

            // Vlastní Madara zdroje - bez nich by po obnově zálohy nešlo dohledat
            // zdroj pro mangu přidanou přes ně (sourceId = "madara:{id}" by odkazoval na nic).
            put("customSources", JSONArray().also { arr ->
                customSources.forEach { s ->
                    arr.put(JSONObject().apply {
                        put("id",                  s.id)
                        put("name",                s.name)
                        put("baseUrl",              s.baseUrl)
                        put("listItemSelector",     s.listItemSelector ?: "")
                        put("titleLinkSelector",    s.titleLinkSelector ?: "")
                        put("descriptionSelector",  s.descriptionSelector ?: "")
                        put("statusSelector",       s.statusSelector ?: "")
                        put("chapterListSelector",  s.chapterListSelector ?: "")
                        put("pageImageSelector",    s.pageImageSelector ?: "")
                    })
                }
            })

            put("manga", JSONArray().also { arr ->
                mangaList.forEach { m ->
                    val catIds = repository.getCategoryIdsForManga(m.id)
                    arr.put(JSONObject().apply {
                        put("id",                m.id)
                        put("sourceId",          m.sourceId)
                        put("url",               m.url)
                        put("title",             m.title)
                        put("coverUrl",          m.coverUrl ?: "")
                        put("description",       m.description ?: "")
                        put("status",            m.status ?: "")
                        put("lastReadChapterId", m.lastReadChapterId ?: "")
                        put("lastReadAt",        m.lastReadAt)
                        put("categoryIds",       JSONArray(catIds))
                    })
                }
            })

            put("chapters", JSONArray().also { arr ->
                allChapters.forEach { c ->
                    arr.put(JSONObject().apply {
                        put("id",            c.id)
                        put("mangaId",       c.mangaId)
                        put("sourceId",      c.sourceId)
                        put("url",           c.url)
                        put("name",          c.name)
                        put("chapterNumber", c.chapterNumber)
                        put("dateUpload",    c.dateUpload)
                        put("read",          c.read)
                        put("lastPageRead",  c.lastPageRead)
                    })
                }
            })
        }
        return root.toString(2)
    }

    // ── Import ────────────────────────────────────────────────────────────────

    suspend fun importFromUri(uri: Uri): Result<ImportStats> = runCatching {
        val json = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Nelze otevřít soubor zálohy")
        restoreFromJson(json)
    }

    private suspend fun restoreFromJson(json: String): ImportStats {
        val root = JSONObject(json)

        // Kategorie
        val catsArr = root.optJSONArray("categories") ?: JSONArray()
        val categories = (0 until catsArr.length()).map { i ->
            val c = catsArr.getJSONObject(i)
            CategoryEntity(
                id       = c.getString("id"),
                name     = c.getString("name"),
                colorHex = c.optString("colorHex", "#8B5CF6"),
            )
        }
        repository.upsertAllCategories(categories)

        // Vlastní Madara zdroje - MUSÍ se obnovit před mangou, jinak manga přidaná
        // přes ně odkazují na sourceId, který ještě v DB neexistuje.
        val customSourcesArr = root.optJSONArray("customSources") ?: JSONArray()
        val customSources = (0 until customSourcesArr.length()).map { i ->
            val s = customSourcesArr.getJSONObject(i)
            CustomSourceEntity(
                id                  = s.getString("id"),
                name                = s.getString("name"),
                baseUrl             = s.getString("baseUrl"),
                listItemSelector    = s.optString("listItemSelector").ifBlank { null },
                titleLinkSelector   = s.optString("titleLinkSelector").ifBlank { null },
                descriptionSelector = s.optString("descriptionSelector").ifBlank { null },
                statusSelector      = s.optString("statusSelector").ifBlank { null },
                chapterListSelector = s.optString("chapterListSelector").ifBlank { null },
                pageImageSelector   = s.optString("pageImageSelector").ifBlank { null },
            )
        }
        repository.upsertAllCustomSources(customSources)

        // Manga
        val mangaArr = root.optJSONArray("manga") ?: JSONArray()
        val mangaList = mutableListOf<MangaEntity>()
        val catAssignments = mutableListOf<Pair<String, String>>()

        for (i in 0 until mangaArr.length()) {
            val m = mangaArr.getJSONObject(i)
            mangaList.add(
                MangaEntity(
                    id                = m.getString("id"),
                    sourceId          = m.getString("sourceId"),
                    url               = m.getString("url"),
                    title             = m.getString("title"),
                    coverUrl          = m.optString("coverUrl").ifBlank { null },
                    description       = m.optString("description").ifBlank { null },
                    status            = m.optString("status").ifBlank { null },
                    inLibrary         = true,
                    lastReadChapterId = m.optString("lastReadChapterId").ifBlank { null },
                    lastReadAt        = m.optLong("lastReadAt", 0L),
                )
            )
            val ids = m.optJSONArray("categoryIds") ?: JSONArray()
            for (j in 0 until ids.length()) catAssignments.add(m.getString("id") to ids.getString(j))
        }
        repository.upsertAllManga(mangaList)
        repository.upsertAllMangaCategories(catAssignments)

        // Kapitoly
        val chapArr = root.optJSONArray("chapters") ?: JSONArray()
        val chapters = (0 until chapArr.length()).map { i ->
            val c = chapArr.getJSONObject(i)
            ChapterEntity(
                id            = c.getString("id"),
                mangaId       = c.getString("mangaId"),
                sourceId      = c.getString("sourceId"),
                url           = c.getString("url"),
                name          = c.getString("name"),
                chapterNumber = c.getDouble("chapterNumber").toFloat(),
                dateUpload    = c.getLong("dateUpload"),
                read          = c.getBoolean("read"),
                lastPageRead  = c.getInt("lastPageRead"),
            )
        }
        repository.upsertAllChapters(chapters)

        return ImportStats(mangaList.size, chapters.size, categories.size)
    }

    data class ImportStats(val mangaCount: Int, val chapterCount: Int, val categoryCount: Int)
}
