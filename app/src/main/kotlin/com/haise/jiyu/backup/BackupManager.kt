package com.haise.jiyu.backup

import com.haise.jiyu.R
import android.content.Context
import android.net.Uri
import android.util.JsonWriter
import androidx.room.withTransaction
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.MangaNoteDao
import com.haise.jiyu.data.db.ManualTranslationDao
import com.haise.jiyu.data.db.MangaTagDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.db.entity.ManualTranslationEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.db.entity.ReadHistoryEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val glossaryDao: GlossaryDao,
    private val manualTranslationDao: ManualTranslationDao,
    private val db: AppDatabase,
) {

    companion object {
        /**
         * Verze formátu, kterou zapisuje export. Zvedej ji, jakmile se změní tvar dat tak,
         * že by ho starší appka přečetla špatně - a přidej k tomu čtení té starší podoby
         * v [restoreFromJson].
         */
        const val BACKUP_VERSION = 5
    }

    // ── Export ────────────────────────────────────────────────────────────────

    // withContext(Dispatchers.IO) kolem celeho tela - export cte cely obsah knihovny (mangy,
    // kapitoly, historie...), volane primo z viewModelScope.launch (Dispatchers.Main.immediate)
    // v SettingsViewModel - bez tohohle bezelo cele na Main threadu (audit nalez).
    //
    // Data se nacitaji z DB PRED otevrenim vystupu a JSON se pise rovnou do streamu (JsonWriter)
    // - drive se stavel cely org.json strom v pameti, pretty-printoval a kopiroval do pole bajtu.
    suspend fun exportToUri(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val data = loadBackupData()
            val out = context.contentResolver.openOutputStream(uri) ?: error(context.getString(R.string.backup_error_open_output))
            out.use { writeBackup(it, data) }
        }
    }

    suspend fun exportToFile(file: java.io.File): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val data = loadBackupData()
            // Přes dočasný soubor a přejmenování - přerušený zápis nesmí nechat v cílové cestě
            // půlku zálohy místo předchozí funkční.
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            try {
                tmp.outputStream().use { writeBackup(it, data) }
                if (!tmp.renameTo(file)) {
                    file.delete()
                    check(tmp.renameTo(file)) { "Nelze přejmenovat dočasný soubor zálohy" }
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
        }
    }

    /** Všechno, co záloha obsahuje, načtené z DB (bez JSONu). */
    private class BackupData(
        val manga: List<MangaEntity>,
        val categories: List<CategoryEntity>,
        val chapters: List<ChapterEntity>,
        val customSources: List<CustomSourceEntity>,
        val notes: List<MangaNoteEntity>,
        val tags: List<MangaTagEntity>,
        val history: List<ReadHistoryEntity>,
        val glossary: List<GlossaryEntity>,
        val manualTranslations: List<ManualTranslationEntity>,
        val categoryIdsByManga: Map<String, List<String>>,
    )

    private suspend fun loadBackupData(): BackupData {
        val mangaList     = repository.getAllLibraryManga()
        val categories    = repository.getAllCategories()
        val allChapters   = repository.getAllLibraryChapters()
        val customSources = repository.getAllCustomSourcesOnce()
        val exportedMangaIds = mangaList.mapTo(HashSet()) { it.id }
        val notes         = mangaNoteDao.getAll().filter { it.mangaId in exportedMangaIds }
        val tags          = mangaTagDao.getAll().filter { it.mangaId in exportedMangaIds }
        val history       = readHistoryDao.getAll().filter { it.mangaId in exportedMangaIds }
        val glossary      = glossaryDao.getAll().filter { it.mangaId in exportedMangaIds }
        val exportedChapterIds = allChapters.mapTo(HashSet()) { it.id }
        val manualTranslations = manualTranslationDao.getAll().filter { it.chapterId in exportedChapterIds }

        // Batch fetch all category mappings in one query instead of N per-manga queries
        val catMappings = repository.getAllCategoryMappings()
            .groupBy({ it.mangaId }, { it.categoryId })

        return BackupData(
            manga = mangaList, categories = categories, chapters = allChapters, customSources = customSources,
            notes = notes, tags = tags, history = history, glossary = glossary,
            manualTranslations = manualTranslations, categoryIdsByManga = catMappings,
        )
    }

    private fun writeBackup(out: java.io.OutputStream, d: BackupData) {
        JsonWriter(java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8))).use { w ->
            w.beginObject()
            w.f("version", BACKUP_VERSION)
            w.f("exportedAt", java.time.Instant.now().toString())

            w.array("categories", d.categories) { cat ->
                f("id",       cat.id)
                f("name",     cat.name)
                f("colorHex", cat.colorHex)
            }

            w.array("customSources", d.customSources) { s ->
                f("id",                  s.id)
                f("name",                s.name)
                f("baseUrl",             s.baseUrl)
                f("listItemSelector",    s.listItemSelector ?: "")
                f("titleLinkSelector",   s.titleLinkSelector ?: "")
                f("descriptionSelector", s.descriptionSelector ?: "")
                f("statusSelector",      s.statusSelector ?: "")
                f("chapterListSelector", s.chapterListSelector ?: "")
                f("pageImageSelector",   s.pageImageSelector ?: "")
                f("contentType",         s.contentType)
            }

            w.array("manga", d.manga) { m ->
                f("id",                  m.id)
                f("sourceId",            m.sourceId)
                f("url",                 m.url)
                f("title",               m.title)
                f("coverUrl",            m.coverUrl ?: "")
                f("description",         m.description ?: "")
                f("status",              m.status ?: "")
                f("author",              m.author ?: "")
                f("artist",              m.artist ?: "")
                f("genres",              m.genres)
                f("year",                m.year ?: 0)
                f("contentType",         m.contentType)
                f("autoDownload",        m.autoDownload)
                f("userRating",          m.userRating ?: -1)
                f("excludeFromUpdates",  m.excludeFromUpdates)
                f("malId",               m.malId ?: 0)
                f("malScore",            (m.malScore ?: 0f).toDouble())
                f("malStatus",           m.malStatus ?: "")
                f("readerDirectionOverride", m.readerDirectionOverride ?: "")
                f("addedAt",             m.addedAt)
                f("lastReadChapterId",   m.lastReadChapterId ?: "")
                f("lastReadAt",          m.lastReadAt)
                f("readingStatus",       m.readingStatus ?: "")
                f("inLibrary",           m.inLibrary)
                f("lastUpdated",         m.lastUpdated)
                f("kitsuId",             m.kitsuId ?: "")
                f("kitsuScore",          (m.kitsuScore ?: 0f).toDouble())
                f("mangaUpdatesId",      m.mangaUpdatesId ?: 0L)
                f("readingTimeMs",       m.readingTimeMs)
                f("isFavorite",          m.isFavorite)
                f("demographic",         m.demographic ?: "")
                f("translationCompleted", m.translationCompleted?.let { if (it) 1 else 0 } ?: -1)
                f("hasAnime",            m.hasAnime?.let { if (it) 1 else 0 } ?: -1)
                f("finalChapter",        m.finalChapter ?: "")
                f("rating",              m.rating ?: 0.0)
                f("followCount",         m.followCount ?: -1)
                f("rank",                m.rank ?: -1)
                f("alternateTitles",     m.alternateTitles)
                f("translationContextNote", m.translationContextNote ?: "")
                name("categoryIds").beginArray()
                (d.categoryIdsByManga[m.id] ?: emptyList()).forEach { value(it) }
                endArray()
            }

            w.array("chapters", d.chapters) { c ->
                f("id",            c.id)
                f("mangaId",       c.mangaId)
                f("sourceId",      c.sourceId)
                f("url",           c.url)
                f("name",          c.name)
                f("chapterNumber", c.chapterNumber.toDouble())
                f("dateUpload",    c.dateUpload)
                f("read",          c.read)
                f("lastPageRead",  c.lastPageRead)
                f("lastReadAt",       c.lastReadAt)
                f("lastScrollOffset", c.lastScrollOffset)
                f("downloadStatus",   c.downloadStatus.name)
                f("localPath",        c.localPath ?: "")
                f("pageCount",        c.pageCount)
                f("scanlationGroup",  c.scanlationGroup ?: "")
                f("volume",           c.volume ?: "")
                f("groupsJson",       c.groupsJson ?: "")
                f("discoveredAt",     c.discoveredAt)
                f("verifiedPageCount", c.verifiedPageCount ?: -1)
                f("isFallbackSource", c.isFallbackSource)
                f("fallbackChapterId", c.fallbackChapterId ?: "")
            }

            w.array("notes", d.notes) { n ->
                f("mangaId",   n.mangaId)
                f("content",   n.content)
                f("updatedAt", n.updatedAt)
            }

            w.array("tags", d.tags) { t ->
                f("mangaId", t.mangaId)
                f("tag",     t.tag)
            }

            w.array("glossary", d.glossary) { g ->
                f("id",             g.id)
                f("mangaId",        g.mangaId)
                f("sourceTerm",     g.sourceTerm)
                f("targetTerm",     g.targetTerm)
                f("targetLanguage", g.targetLanguage)
                f("protectExact",   g.protectExact)
            }

            w.array("manualTranslations", d.manualTranslations) { t ->
                f("id",           t.id)
                f("chapterId",    t.chapterId)
                f("pageIndex",    t.pageIndex)
                f("originalText", t.originalText)
                f("text",         t.text)
                f("updatedAt",    t.updatedAt)
                t.offsetXDp?.let { f("offsetXDp", it.toDouble()) }
                t.offsetYDp?.let { f("offsetYDp", it.toDouble()) }
            }

            w.array("readHistory", d.history) { h ->
                f("chapterId",   h.chapterId)
                f("mangaId",     h.mangaId)
                f("mangaTitle",  h.mangaTitle)
                f("coverUrl",    h.coverUrl ?: "")
                f("chapterName", h.chapterName)
                f("readAt",      h.readAt)
            }

            w.endObject()
        }
    }

    // ── Import ────────────────────────────────────────────────────────────────

    suspend fun importFromUri(uri: Uri): Result<ImportStats> = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                ?: error(context.getString(R.string.backup_error_open_input))
            restoreFromJson(json)
        }
    }

    /** Oddělené od [importFromUri], aby šla obnova otestovat bez SAF a bez Uri. */
    internal suspend fun importFromJson(json: String): Result<ImportStats> = withContext(Dispatchers.IO) {
        runCatching { restoreFromJson(json) }
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
            // Kategorie se páruje podle názvu - appka při prvním spuštění sama vytvoří výchozí
            // kategorie s náhodným id, takže by je obnova zdvojila místo sloučení.
            val (categories, assignments) = mergeCategoriesByName(
                parsed.categories, parsed.categoryAssignments, repository.getAllCategories(),
            )
            // Novější lokální pokrok ve čtení nesmí obnova přepsat starším ze zálohy.
            val localChapters = repository.getChaptersByIds(validatedChapters.map { it.id }).associateBy { it.id }
            val chapters = keepNewerLocalProgress(validatedChapters, localChapters)

            repository.upsertAllCategories(categories)
            repository.upsertAllCustomSources(parsed.customSources)
            repository.upsertAllManga(parsed.manga)
            repository.upsertAllMangaCategories(assignments)
            repository.upsertAllChapters(chapters)
            if (parsed.notes.isNotEmpty()) mangaNoteDao.upsertAll(parsed.notes)
            if (parsed.tags.isNotEmpty()) mangaTagDao.insertAll(parsed.tags)
            if (parsed.readHistory.isNotEmpty()) readHistoryDao.upsertAll(parsed.readHistory)
            if (parsed.glossary.isNotEmpty()) glossaryDao.upsertAll(parsed.glossary)
            if (parsed.manualTranslations.isNotEmpty()) manualTranslationDao.upsertAll(parsed.manualTranslations)

            // Bez `return` - jsme uvnitř lambdy withTransaction, hodnota se vrací jako výraz.
            ImportStats(parsed.manga.size, parsed.chapters.size, parsed.categories.size, parsed.skippedCount)
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

    data class ImportStats(val mangaCount: Int, val chapterCount: Int, val categoryCount: Int, val skippedCount: Int = 0)
}

/**
 * Kategorie ze zálohy se stejným názvem (bez ohledu na velikost písmen) jako už existující lokální
 * se nevkládají znovu - jejich přiřazení manga se přemapují na lokální id. Vrací kategorie k zápisu
 * a upravená přiřazení.
 */
internal fun mergeCategoriesByName(
    backupCategories: List<CategoryEntity>,
    backupAssignments: List<Pair<String, String>>,
    localCategories: List<CategoryEntity>,
): Pair<List<CategoryEntity>, List<Pair<String, String>>> {
    val localById = localCategories.associateBy { it.id }
    val localByName = localCategories.associateBy { it.name.trim().lowercase() }
    val idRemap = HashMap<String, String>()
    val toWrite = backupCategories.filter { c ->
        if (c.id in localById) return@filter true
        val sameName = localByName[c.name.trim().lowercase()]
        if (sameName != null) {
            idRemap[c.id] = sameName.id
            false
        } else {
            true
        }
    }
    val assignments = backupAssignments.map { (mangaId, categoryId) -> mangaId to (idRemap[categoryId] ?: categoryId) }.distinct()
    return toWrite to assignments
}

/**
 * Když má lokální kapitola novější `lastReadAt` než ta ze zálohy, ponechá se lokální stav čtení
 * (`read`, `lastPageRead`, `lastReadAt`, `lastScrollOffset`) - záloha se obnovuje typicky starší
 * než to, co uživatel mezitím přečetl.
 */
internal fun keepNewerLocalProgress(
    backupChapters: List<ChapterEntity>,
    localById: Map<String, ChapterEntity>,
): List<ChapterEntity> = backupChapters.map { b ->
    val local = localById[b.id]
    if (local != null && local.lastReadAt > b.lastReadAt) {
        b.copy(
            read = local.read,
            lastPageRead = local.lastPageRead,
            lastReadAt = local.lastReadAt,
            lastScrollOffset = local.lastScrollOffset,
        )
    } else {
        b
    }
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
    val skippedCount: Int = 0,
    val glossary: List<GlossaryEntity> = emptyList(),
    val manualTranslations: List<ManualTranslationEntity> = emptyList(),
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

    var skipped = 0
    val skip: () -> Unit = { skipped++ }

    val catsArr = root.optJSONArray("categories") ?: JSONArray()
    val categories = catsArr.mapObjectsOrSkip(skip) { c ->
        CategoryEntity(
            id       = c.getString("id"),
            name     = c.getString("name"),
            colorHex = c.optString("colorHex", "#8B5CF6"),
        )
    }

    val customSourcesArr = root.optJSONArray("customSources") ?: JSONArray()
    val customSources = customSourcesArr.mapObjectsOrSkip(skip) { s ->
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
            contentType         = s.optString("contentType").ifBlank { "MANGA" },
        )
    }

    val mangaArr = root.optJSONArray("manga") ?: JSONArray()
    val parsedManga = mangaArr.mapObjectsOrSkip(skip) { m ->
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
        val entity =
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
        val ids = m.optJSONArray("categoryIds") ?: JSONArray()
        entity to (0 until ids.length()).map { j -> entity.id to ids.getString(j) }
    }
    val mangaList = parsedManga.map { it.first }
    val catAssignments = parsedManga.flatMap { it.second }

    val chapArr = root.optJSONArray("chapters") ?: JSONArray()
    val chapters = chapArr.mapObjectsOrSkip(skip) { c ->
        val downloadStatus = c.optString("downloadStatus").ifBlank { null }
            ?.let { runCatching { com.haise.jiyu.data.db.entity.DownloadStatus.valueOf(it) }.getOrNull() }
            ?: com.haise.jiyu.data.db.entity.DownloadStatus.NOT_DOWNLOADED
        ChapterEntity(
            id            = c.getString("id"),
            mangaId       = c.getString("mangaId"),
            sourceId      = c.getString("sourceId"),
            url           = c.getString("url"),
            name          = c.getString("name"),
            chapterNumber = c.optDouble("chapterNumber", 0.0).toFloat(),
            dateUpload    = c.optLong("dateUpload", 0L),
            read          = c.optBoolean("read", false),
            lastPageRead  = c.optInt("lastPageRead", 0),
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
    val notes = notesArr.mapObjectsOrSkip(skip) { n ->
        MangaNoteEntity(
            mangaId   = n.getString("mangaId"),
            content   = n.getString("content"),
            updatedAt = n.optLong("updatedAt", System.currentTimeMillis()),
        )
    }

    val tagsArr = root.optJSONArray("tags") ?: JSONArray()
    val tags = tagsArr.mapObjectsOrSkip(skip) { t ->
        MangaTagEntity(mangaId = t.getString("mangaId"), tag = t.getString("tag"))
    }

    val histArr = root.optJSONArray("readHistory") ?: JSONArray()
    val history = histArr.mapObjectsOrSkip(skip) { h ->
        ReadHistoryEntity(
            chapterId   = h.getString("chapterId"),
            mangaId     = h.getString("mangaId"),
            mangaTitle  = h.getString("mangaTitle"),
            coverUrl    = h.optString("coverUrl").ifBlank { null },
            chapterName = h.getString("chapterName"),
            readAt      = h.optLong("readAt", 0L),
        )
    }

    val glossaryArr = root.optJSONArray("glossary") ?: JSONArray()
    val glossary = glossaryArr.mapObjectsOrSkip(skip) { g ->
        GlossaryEntity(
            id             = g.getString("id"),
            mangaId        = g.getString("mangaId"),
            sourceTerm     = g.getString("sourceTerm"),
            targetTerm     = g.getString("targetTerm"),
            targetLanguage = g.getString("targetLanguage"),
            protectExact   = g.optBoolean("protectExact", false),
        )
    }

    val manualArr = root.optJSONArray("manualTranslations") ?: JSONArray()
    val manualTranslations = manualArr.mapObjectsOrSkip(skip) { t ->
        ManualTranslationEntity(
            id           = t.getString("id"),
            chapterId    = t.getString("chapterId"),
            pageIndex    = t.getInt("pageIndex"),
            originalText = t.getString("originalText"),
            text         = t.getString("text"),
            updatedAt    = t.optLong("updatedAt", 0L),
            offsetXDp    = if (t.has("offsetXDp")) t.getDouble("offsetXDp").toFloat() else null,
            offsetYDp    = if (t.has("offsetYDp")) t.getDouble("offsetYDp").toFloat() else null,
        )
    }

    return ParsedBackup(
        categories, customSources, mangaList, catAssignments, chapters, notes, tags, history,
        skippedCount = skipped,
        glossary = glossary,
        manualTranslations = manualTranslations,
    )
}

/**
 * Jeden vadný záznam (chybějící povinné pole, špatný typ) nesmí shodit obnovu celé knihovny -
 * přeskočí se a započítá do [ParsedBackup.skippedCount]. Sám JSON (kořen) zůstává striktní.
 */
private inline fun <T : Any> JSONArray.mapObjectsOrSkip(skip: () -> Unit, block: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        try {
            out.add(block(getJSONObject(i)))
        } catch (e: org.json.JSONException) {
            skip()
        }
    }
    return out
}

/** Zapíše jedno pole objektu; hodnoty stejných typů jako dřívější `JSONObject.put` (null se nezapisuje). */
private fun JsonWriter.f(key: String, value: Any?): JsonWriter {
    if (value == null) return this
    name(key)
    when (value) {
        is Boolean -> value(value)
        is Int -> value(value.toLong())
        is Long -> value(value)
        is Float -> value(value.toDouble())
        is Double -> value(value)
        else -> value(value.toString())
    }
    return this
}

/** `"name": [ {...}, {...} ]` - každý prvek se zapisuje ihned, bez mezipaměti celého pole. */
private fun <T> JsonWriter.array(key: String, items: List<T>, write: JsonWriter.(T) -> Unit) {
    name(key).beginArray()
    items.forEach { item ->
        beginObject()
        write(item)
        endObject()
    }
    endArray()
}
