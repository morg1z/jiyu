package com.haise.jiyu.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.MangaNoteDao
import com.haise.jiyu.data.db.MangaTagDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: MangaRepository,
    private val mangaNoteDao: MangaNoteDao,
    private val mangaTagDao: MangaTagDao,
    private val readHistoryDao: ReadHistoryDao,
    private val db: AppDatabase,
) {

    companion object {
        /**
         * Verze formátu, kterou zapisuje export. Zvedej ji, jakmile se změní tvar dat tak,
         * že by ho starší appka přečetla špatně - a přidej k tomu čtení té starší podoby
         * v [restoreFromJson].
         */
        const val BACKUP_VERSION = 4
    }

    // ── Export ────────────────────────────────────────────────────────────────

    suspend fun exportToUri(uri: Uri): Result<Unit> = runCatching {
        val json = buildBackupJson()
        context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            ?: error("Nelze otevřít výstupní soubor")
    }

    suspend fun exportToFile(file: java.io.File): Result<Unit> = runCatching {
        file.writeText(buildBackupJson())
    }

    private suspend fun buildBackupJson(): String {
        val mangaList     = repository.getAllLibraryManga()
        val categories    = repository.getAllCategories()
        val allChapters   = repository.getAllLibraryChapters()
        val customSources = repository.getAllCustomSourcesOnce()
        val notes         = mangaNoteDao.getAll()
        val tags          = mangaTagDao.getAll()
        val history       = readHistoryDao.getAll()

        // Batch fetch all category mappings in one query instead of N per-manga queries
        val catMappings = repository.getAllCategoryMappings()
            .groupBy({ it.mangaId }, { it.categoryId })

        val root = JSONObject().apply {
            put("version", BACKUP_VERSION)
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
                    arr.put(JSONObject().apply {
                        put("id",                  m.id)
                        put("sourceId",            m.sourceId)
                        put("url",                 m.url)
                        put("title",               m.title)
                        put("coverUrl",            m.coverUrl ?: "")
                        put("description",         m.description ?: "")
                        put("status",              m.status ?: "")
                        put("author",              m.author ?: "")
                        put("artist",              m.artist ?: "")
                        put("genres",              m.genres)
                        put("year",                m.year ?: 0)
                        put("contentType",         m.contentType)
                        put("autoDownload",        m.autoDownload)
                        put("userRating",          m.userRating ?: -1)
                        put("excludeFromUpdates",  m.excludeFromUpdates)
                        put("malId",               m.malId ?: 0)
                        put("malScore",            (m.malScore ?: 0f).toDouble())
                        put("malStatus",           m.malStatus ?: "")
                        put("readerDirectionOverride", m.readerDirectionOverride ?: "")
                        put("addedAt",             m.addedAt)
                        put("lastReadChapterId",   m.lastReadChapterId ?: "")
                        put("lastReadAt",          m.lastReadAt)
                        put("readingStatus",       m.readingStatus ?: "")
                        put("inLibrary",           m.inLibrary)
                        put("lastUpdated",         m.lastUpdated)
                        put("kitsuId",             m.kitsuId ?: "")
                        put("kitsuScore",          (m.kitsuScore ?: 0f).toDouble())
                        put("mangaUpdatesId",      m.mangaUpdatesId ?: 0L)
                        put("readingTimeMs",       m.readingTimeMs)
                        put("isFavorite",          m.isFavorite)
                        put("demographic",         m.demographic ?: "")
                        put("translationCompleted", m.translationCompleted?.let { if (it) 1 else 0 } ?: -1)
                        put("hasAnime",            m.hasAnime?.let { if (it) 1 else 0 } ?: -1)
                        put("finalChapter",        m.finalChapter ?: "")
                        put("rating",              m.rating ?: 0.0)
                        put("followCount",         m.followCount ?: -1)
                        put("rank",                m.rank ?: -1)
                        put("alternateTitles",     m.alternateTitles)
                        put("translationContextNote", m.translationContextNote ?: "")
                        put("categoryIds",         JSONArray(catMappings[m.id] ?: emptyList<String>()))
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
                        put("chapterNumber", c.chapterNumber.toDouble())
                        put("dateUpload",    c.dateUpload)
                        put("read",          c.read)
                        put("lastPageRead",  c.lastPageRead)
                        put("lastReadAt",       c.lastReadAt)
                        put("lastScrollOffset", c.lastScrollOffset)
                        put("downloadStatus",   c.downloadStatus.name)
                        put("localPath",        c.localPath ?: "")
                        put("pageCount",        c.pageCount)
                        put("scanlationGroup",  c.scanlationGroup ?: "")
                        put("volume",           c.volume ?: "")
                        put("groupsJson",       c.groupsJson ?: "")
                        put("discoveredAt",     c.discoveredAt)
                        put("verifiedPageCount", c.verifiedPageCount ?: -1)
                        put("isFallbackSource", c.isFallbackSource)
                        put("fallbackChapterId", c.fallbackChapterId ?: "")
                    })
                }
            })

            put("notes", JSONArray().also { arr ->
                notes.forEach { n ->
                    arr.put(JSONObject().apply {
                        put("mangaId",   n.mangaId)
                        put("content",   n.content)
                        put("updatedAt", n.updatedAt)
                    })
                }
            })

            put("tags", JSONArray().also { arr ->
                tags.forEach { t ->
                    arr.put(JSONObject().apply {
                        put("mangaId", t.mangaId)
                        put("tag",     t.tag)
                    })
                }
            })

            put("readHistory", JSONArray().also { arr ->
                history.forEach { h ->
                    arr.put(JSONObject().apply {
                        put("chapterId",   h.chapterId)
                        put("mangaId",     h.mangaId)
                        put("mangaTitle",  h.mangaTitle)
                        put("coverUrl",    h.coverUrl ?: "")
                        put("chapterName", h.chapterName)
                        put("readAt",      h.readAt)
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

    /** Oddělené od [importFromUri], aby šla obnova otestovat bez SAF a bez Uri. */
    internal suspend fun importFromJson(json: String): Result<ImportStats> = runCatching {
        restoreFromJson(json)
    }

    /**
     * Celá obnova běží v JEDNÉ transakci. Dřív to bylo sedm samostatných zápisů za sebou,
     * takže chyba uprostřed (poškozený JSON, chybějící povinné pole) nechala kategorie a
     * mangu zapsané, ale kapitoly už ne - `runCatching` sice ohlásilo neúspěch, jenže
     * knihovna zůstala v rozečteném stavu. Buď se obnoví všechno, nebo nic.
     */
    private suspend fun restoreFromJson(json: String): ImportStats {
        val parsed = parseBackupJson(json)
        // MIMO transakci - jde o (potenciálně pomalé, hlavně přes SAF) čtení souborového
        // systému, ne o zápis do DB, a nemá držet transakci otevřenou o nic déle, než musí.
        val validatedChapters = validateDownloadedChapters(parsed.chapters)

        return db.withTransaction {
            repository.upsertAllCategories(parsed.categories)
            repository.upsertAllCustomSources(parsed.customSources)
            repository.upsertAllManga(parsed.manga)
            repository.upsertAllMangaCategories(parsed.categoryAssignments)
            repository.upsertAllChapters(validatedChapters)
            if (parsed.notes.isNotEmpty()) mangaNoteDao.upsertAll(parsed.notes)
            if (parsed.tags.isNotEmpty()) mangaTagDao.insertAll(parsed.tags)
            if (parsed.readHistory.isNotEmpty()) readHistoryDao.upsertAll(parsed.readHistory)

            // Bez `return` - jsme uvnitř lambdy withTransaction, hodnota se vrací jako výraz.
            ImportStats(parsed.manga.size, parsed.chapters.size, parsed.categories.size)
        }
    }

    /**
     * Záloha z jiného telefonu (nebo po reinstalu bez SAF externí složky) klidně řekne
     * `downloadStatus=DOWNLOADED` s `localPath`, který na TOMHLE zařízení nikdy neexistoval -
     * appka by pak ukazovala "staženo" u kapitoly, která se ve skutečnosti musí stáhnout znovu,
     * a nikdy by to sama neopravila (viz audit nález "restore křísí duchy"). Existuje-li
     * složka opravdu se stránkami (typicky SAF externí složka, která reinstall přežije),
     * stav se ponechá - jde jen o odchycení mrtvého ukazatele, ne o plošné zneplatnění.
     */
    private fun validateDownloadedChapters(chapters: List<ChapterEntity>): List<ChapterEntity> = chapters.map { c ->
        val localPath = c.localPath
        if (c.downloadStatus == DownloadStatus.DOWNLOADED && localPath != null &&
            ChapterStorage.listPageUrls(context, localPath).isEmpty()
        ) {
            c.copy(downloadStatus = DownloadStatus.NOT_DOWNLOADED, localPath = null, pageCount = 0)
        } else {
            c
        }
    }

    data class ImportStats(val mangaCount: Int, val chapterCount: Int, val categoryCount: Int)
}

/** Výsledek [parseBackupJson] - vstup pro zápis v [BackupManager.restoreFromJson]. */
internal data class ParsedBackup(
    val categories: List<CategoryEntity>,
    val customSources: List<CustomSourceEntity>,
    val manga: List<MangaEntity>,
    val categoryAssignments: List<Pair<String, String>>,
    val chapters: List<ChapterEntity>,
    val notes: List<MangaNoteEntity>,
    val tags: List<MangaTagEntity>,
    val readHistory: List<ReadHistoryEntity>,
)

/**
 * Čisté parsování JSON→entity, vytažené z [BackupManager.restoreFromJson] mimo
 * `db.withTransaction`, aby šlo otestovat bez Room databáze (žádná změna chování).
 */
internal fun parseBackupJson(json: String): ParsedBackup {
    val root = JSONObject(json)

    // Verzi si export zapisoval odjakživa, ale import ji nikdy nečetl - novější formát
    // by se tedy naparsoval jako ten současný a tiše nadělal nesmysly. Nejstarší zálohy
    // pole nemají vůbec, ty bereme jako ten nejstarší formát a pouštíme dál.
    val version = root.optInt("version", 1)
    require(version <= BackupManager.BACKUP_VERSION) {
        "Záloha je z novější verze aplikace (formát $version, tahle appka umí ${BackupManager.BACKUP_VERSION}). " +
            "Aktualizuj Jiyu a zkus to znovu."
    }

    val catsArr = root.optJSONArray("categories") ?: JSONArray()
    val categories = (0 until catsArr.length()).map { i ->
        val c = catsArr.getJSONObject(i)
        CategoryEntity(
            id       = c.getString("id"),
            name     = c.getString("name"),
            colorHex = c.optString("colorHex", "#8B5CF6"),
        )
    }

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

    val mangaArr = root.optJSONArray("manga") ?: JSONArray()
    val mangaList = mutableListOf<MangaEntity>()
    val catAssignments = mutableListOf<Pair<String, String>>()

    for (i in 0 until mangaArr.length()) {
        val m = mangaArr.getJSONObject(i)
        val userRating = m.optInt("userRating", -1).takeIf { it >= 0 }
        val year = m.optInt("year", 0).takeIf { it > 0 }
        val malId = m.optInt("malId", 0).takeIf { it > 0 }
        val malScore = m.optDouble("malScore", 0.0).takeIf { it > 0 }?.toFloat()
        val kitsuScore = m.optDouble("kitsuScore", 0.0).takeIf { it > 0 }?.toFloat()
        val mangaUpdatesId = m.optLong("mangaUpdatesId", 0L).takeIf { it > 0 }
        val translationCompleted = m.optInt("translationCompleted", -1).let { if (it < 0) null else it == 1 }
        val hasAnime = m.optInt("hasAnime", -1).let { if (it < 0) null else it == 1 }
        val rating = m.optDouble("rating", 0.0).takeIf { it > 0 }
        val followCount = m.optInt("followCount", -1).takeIf { it >= 0 }
        val rank = m.optInt("rank", -1).takeIf { it >= 0 }
        mangaList.add(
            MangaEntity(
                id                      = m.getString("id"),
                sourceId                = m.getString("sourceId"),
                url                     = m.getString("url"),
                title                   = m.getString("title"),
                coverUrl                = m.optString("coverUrl").ifBlank { null },
                description             = m.optString("description").ifBlank { null },
                status                  = m.optString("status").ifBlank { null },
                author                  = m.optString("author").ifBlank { null },
                artist                  = m.optString("artist").ifBlank { null },
                genres                  = m.optString("genres", ""),
                year                    = year,
                contentType             = m.optString("contentType", "MANGA").ifBlank { "MANGA" },
                autoDownload            = m.optBoolean("autoDownload", false),
                userRating              = userRating,
                excludeFromUpdates      = m.optBoolean("excludeFromUpdates", false),
                malId                   = malId,
                malScore                = malScore,
                malStatus               = m.optString("malStatus").ifBlank { null },
                readerDirectionOverride = m.optString("readerDirectionOverride").ifBlank { null },
                addedAt                 = m.optLong("addedAt", 0L),
                // Verze <4 nemely tohle pole vubec - "true" je spravny default (stary export
                // obsahoval jen knihovnu, takze vsechno v nem uzivatel skutecne mel v knihovne).
                inLibrary               = if (m.has("inLibrary")) m.optBoolean("inLibrary", true) else true,
                lastUpdated             = m.optLong("lastUpdated", System.currentTimeMillis()),
                lastReadChapterId       = m.optString("lastReadChapterId").ifBlank { null },
                lastReadAt              = m.optLong("lastReadAt", 0L),
                readingStatus           = m.optString("readingStatus").ifBlank { null },
                kitsuId                 = m.optString("kitsuId").ifBlank { null },
                kitsuScore              = kitsuScore,
                mangaUpdatesId          = mangaUpdatesId,
                readingTimeMs           = m.optLong("readingTimeMs", 0L),
                isFavorite              = m.optBoolean("isFavorite", false),
                demographic             = m.optString("demographic").ifBlank { null },
                translationCompleted    = translationCompleted,
                hasAnime                = hasAnime,
                finalChapter            = m.optString("finalChapter").ifBlank { null },
                rating                  = rating,
                followCount             = followCount,
                rank                    = rank,
                alternateTitles         = m.optString("alternateTitles", ""),
                translationContextNote  = m.optString("translationContextNote").ifBlank { null },
            )
        )
        val ids = m.optJSONArray("categoryIds") ?: JSONArray()
        for (j in 0 until ids.length()) catAssignments.add(m.getString("id") to ids.getString(j))
    }

    val chapArr = root.optJSONArray("chapters") ?: JSONArray()
    val chapters = (0 until chapArr.length()).map { i ->
        val c = chapArr.getJSONObject(i)
        val downloadStatus = c.optString("downloadStatus").ifBlank { null }
            ?.let { runCatching { com.haise.jiyu.data.db.entity.DownloadStatus.valueOf(it) }.getOrNull() }
            ?: com.haise.jiyu.data.db.entity.DownloadStatus.NOT_DOWNLOADED
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
            lastReadAt       = c.optLong("lastReadAt", 0L),
            lastScrollOffset = c.optInt("lastScrollOffset", 0),
            downloadStatus   = downloadStatus,
            localPath        = c.optString("localPath").ifBlank { null },
            pageCount        = c.optInt("pageCount", 0),
            scanlationGroup  = c.optString("scanlationGroup").ifBlank { null },
            volume           = c.optString("volume").ifBlank { null },
            groupsJson       = c.optString("groupsJson").ifBlank { null },
            discoveredAt     = c.optLong("discoveredAt", 0L),
            verifiedPageCount = c.optInt("verifiedPageCount", -1).takeIf { it >= 0 },
            isFallbackSource = c.optBoolean("isFallbackSource", false),
            fallbackChapterId = c.optString("fallbackChapterId").ifBlank { null },
        )
    }

    val notesArr = root.optJSONArray("notes") ?: JSONArray()
    val notes = (0 until notesArr.length()).map { i ->
        val n = notesArr.getJSONObject(i)
        MangaNoteEntity(
            mangaId   = n.getString("mangaId"),
            content   = n.getString("content"),
            updatedAt = n.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    val tagsArr = root.optJSONArray("tags") ?: JSONArray()
    val tags = (0 until tagsArr.length()).map { i ->
        val t = tagsArr.getJSONObject(i)
        MangaTagEntity(mangaId = t.getString("mangaId"), tag = t.getString("tag"))
    }

    val histArr = root.optJSONArray("readHistory") ?: JSONArray()
    val history = (0 until histArr.length()).map { i ->
        val h = histArr.getJSONObject(i)
        ReadHistoryEntity(
            chapterId   = h.getString("chapterId"),
            mangaId     = h.getString("mangaId"),
            mangaTitle  = h.getString("mangaTitle"),
            coverUrl    = h.optString("coverUrl").ifBlank { null },
            chapterName = h.getString("chapterName"),
            readAt      = h.getLong("readAt"),
        )
    }

    return ParsedBackup(categories, customSources, mangaList, catAssignments, chapters, notes, tags, history)
}
