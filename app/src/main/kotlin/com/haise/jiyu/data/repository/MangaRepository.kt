package com.haise.jiyu.data.repository

import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.data.db.MangaCategoryMapping
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.MangaDownloadedCount
import com.haise.jiyu.data.db.MangaTotalCount
import com.haise.jiyu.data.db.MangaUnreadCount
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SChapter
import com.haise.jiyu.source.SGroup
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.mangadex.MangaDexSource
import com.haise.jiyu.util.normalizeMangaTitle
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Manga entita v knihovně, o které appka usoudila, že je stejná jako nově přidávaná (podle názvu). */
data class DuplicateMatch(val manga: MangaEntity, val sourceName: String, val chapterCount: Int)

@Singleton
class MangaRepository @Inject constructor(
    private val sourceManager: SourceManager,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val categoryDao: CategoryDao,
    private val customSourceDao: CustomSourceDao,
    private val mangaDexSource: MangaDexSource,
) {
    // ── Library ──────────────────────────────────────────────────────────────

    fun observeLibrary(): Flow<List<MangaEntity>> = mangaDao.observeLibrary()
    fun observeLibraryInCategory(categoryId: String) = categoryDao.observeMangaInCategory(categoryId)
    fun observeMangaById(mangaId: String): Flow<MangaEntity?> = mangaDao.observeById(mangaId)
    suspend fun getAllLibraryManga(): List<MangaEntity> = mangaDao.getAllLibrary()
    fun observeRecentlyRead(): Flow<List<MangaEntity>> = mangaDao.observeRecentlyRead()
    fun observeContinueReading(): Flow<List<com.haise.jiyu.data.db.ContinueReadingItem>> = mangaDao.observeContinueReading()
    fun observeRecentlyAdded(): Flow<List<MangaEntity>> = mangaDao.observeRecentlyAdded()
    fun observeCompleted(): Flow<List<MangaEntity>> = mangaDao.observeCompleted()

    // ── Chapters ─────────────────────────────────────────────────────────────

    fun observeChapters(mangaId: String): Flow<List<ChapterEntity>> = chapterDao.observeForManga(mangaId)
    suspend fun countChapters(mangaId: String): Int = chapterDao.countForManga(mangaId)
    suspend fun getAllChapters(mangaId: String): List<ChapterEntity> = chapterDao.getAllForManga(mangaId)
    suspend fun markAllChaptersRead(mangaIds: List<String>) = chapterDao.markAllReadForMangas(mangaIds)
    suspend fun resetActiveDownloads() = chapterDao.resetActiveDownloads()
    suspend fun countReadChapters(): Int = chapterDao.countRead()
    fun observeReadChaptersCount(): Flow<Int> = chapterDao.observeReadCount()
    suspend fun getAllLibraryChapters(): List<ChapterEntity> = chapterDao.getAllForLibrary()
    fun observeUnreadCounts(): Flow<List<MangaUnreadCount>> = chapterDao.observeUnreadCounts()
    fun observeTotalCounts(): Flow<List<MangaTotalCount>> = chapterDao.observeTotalCounts()
    fun observeDownloadedCountPerManga(): Flow<List<MangaDownloadedCount>> = chapterDao.observeDownloadedCountPerManga()
    fun observeNonEmptyDownloads(): Flow<List<ChapterEntity>> = chapterDao.observeNonEmptyDownloads()
    fun observeDownloadedCount(): Flow<Int> = chapterDao.observeDownloadedCount()
    suspend fun clearAllDownloaded() = chapterDao.clearAllDownloaded()
    suspend fun resetDownloadForChapter(chapterId: String) = chapterDao.resetDownloadForChapter(chapterId)
    suspend fun upsertAllManga(manga: List<com.haise.jiyu.data.db.entity.MangaEntity>) = mangaDao.upsertAll(manga)
    suspend fun setFavorite(mangaId: String, favorite: Boolean) = mangaDao.setFavorite(mangaId, favorite)
    fun observeFavoriteCount(): Flow<Int> = mangaDao.observeFavoriteCount()
    fun observeLibraryCount(): Flow<Int> = mangaDao.observeLibraryCount()
    suspend fun upsertAllChapters(chapters: List<ChapterEntity>) = chapterDao.upsertAll(chapters)
    suspend fun getAllCategories(): List<com.haise.jiyu.data.db.entity.CategoryEntity> = categoryDao.getAllOnce()
    suspend fun seedDefaultCategoriesIfEmpty(defaults: List<com.haise.jiyu.data.db.entity.CategoryEntity>) = categoryDao.seedDefaultsIfEmpty(defaults)
    suspend fun getCategoryIdsForManga(mangaId: String): List<String> = categoryDao.getCategoryIdsForManga(mangaId)
    suspend fun getAllCategoryMappings(): List<MangaCategoryMapping> = categoryDao.getAllMappings()
    suspend fun upsertAllCategories(categories: List<com.haise.jiyu.data.db.entity.CategoryEntity>) = categoryDao.upsertAll(categories)

    // ── Browse / Search ──────────────────────────────────────────────────────

    suspend fun search(sourceId: String, query: String, page: Int = 1, filter: MangaFilter = MangaFilter()): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.search(query, page, filter)
    }

    suspend fun getPopular(sourceId: String, page: Int = 1, filter: MangaFilter = MangaFilter()): List<SManga> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getPopular(page, filter)
    }

    // ── Manga CRUD ───────────────────────────────────────────────────────────

    suspend fun addToLibrary(manga: SManga) {
        val id = upsertMangaMetadata(manga, forceInLibrary = true)
        refreshChapters(id, manga)
    }

    /**
     * Vytvoří/aktualizuje mangu a její kapitoly v DB, ale NEPŘIDÁ ji do knihovny
     * (inLibrary zůstává false u nové entity) - pro prohlížení/čtení detailu
     * zdrojové manga bez závazku ji sledovat. Uživatel ji pak může přidat do
     * knihovny samostatně tlačítkem v detailu.
     */
    suspend fun openPreview(manga: SManga): String {
        val id = upsertMangaMetadata(manga, forceInLibrary = false)
        refreshChapters(id, manga)
        return id
    }

    /** Přepne již existující (např. dříve jen prohlíženou) mangu do knihovny. */
    suspend fun addExistingToLibrary(mangaId: String) {
        val existing = mangaDao.getById(mangaId) ?: return
        mangaDao.upsert(
            existing.copy(
                inLibrary = true,
                addedAt = if (existing.addedAt == 0L) System.currentTimeMillis() else existing.addedAt,
            )
        )
    }

    private suspend fun upsertMangaMetadata(manga: SManga, forceInLibrary: Boolean): String {
        val id = mangaId(manga.sourceId, manga.url)
        val existing = mangaDao.getById(id)
        if (existing != null) {
            // Preserve all user-set fields: malId, userRating, readerDirectionOverride,
            // autoDownload, excludeFromUpdates, readingStatus, addedAt, etc.
            mangaDao.upsert(
                existing.copy(
                    inLibrary = existing.inLibrary || forceInLibrary,
                    title = manga.title,
                    coverUrl = manga.coverUrl,
                    description = manga.description,
                    status = manga.status,
                    author = manga.author,
                    artist = manga.artist,
                    genres = manga.genres.joinToString(","),
                    year = manga.year,
                    contentType = manga.contentType,
                    addedAt = if (forceInLibrary && existing.addedAt == 0L) System.currentTimeMillis() else existing.addedAt,
                )
            )
        } else {
            mangaDao.upsert(
                MangaEntity(
                    id = id,
                    sourceId = manga.sourceId,
                    url = manga.url,
                    title = manga.title,
                    coverUrl = manga.coverUrl,
                    description = manga.description,
                    status = manga.status,
                    inLibrary = forceInLibrary,
                    author = manga.author,
                    artist = manga.artist,
                    genres = manga.genres.joinToString(","),
                    year = manga.year,
                    contentType = manga.contentType,
                    addedAt = if (forceInLibrary) System.currentTimeMillis() else 0L,
                )
            )
        }
        return id
    }

    /**
     * Hledá manga v knihovně se stejným (normalizovaným) názvem, ale JINÝM zdrojem -
     * uživatel na tohle přechodně narazí, když stejnou sérii najde na dvou webech.
     * Různé zdroje mívají odlišný počet přeložených kapitol / kvalitu překladu,
     * takže vracíme i countChapters pro každou shodu, aby šlo porovnat.
     */
    suspend fun findLibraryMatchesByTitle(title: String, excludeSourceId: String): List<DuplicateMatch> {
        val normalized = normalizeMangaTitle(title)
        if (normalized.isBlank()) return emptyList()
        return getAllLibraryManga()
            .filter { it.sourceId != excludeSourceId && normalizeMangaTitle(it.title) == normalized }
            .map { entity ->
                DuplicateMatch(
                    manga = entity,
                    sourceName = sourceManager.getById(entity.sourceId)?.name ?: entity.sourceId,
                    chapterCount = chapterDao.countForManga(entity.id),
                )
            }
    }

    /** Načte počet kapitol pro mangu, která JEŠTĚ NENÍ v knihovně (bez zápisu do DB) - pro porovnání při možné duplicitě. */
    suspend fun previewChapterCount(manga: SManga): Int {
        val source = sourceManager.getById(manga.sourceId) ?: return 0
        return try { source.getChapterList(manga).size } catch (_: Exception) { 0 }
    }

    suspend fun setMangaReaderDirection(mangaId: String, direction: String?) =
        mangaDao.setReaderDirection(mangaId, direction)

    suspend fun setAutoDownload(mangaId: String, enabled: Boolean) =
        mangaDao.setAutoDownload(mangaId, enabled)

    suspend fun setRating(mangaId: String, rating: Int?) =
        mangaDao.setRating(mangaId, rating)

    suspend fun setExcludeFromUpdates(mangaId: String, exclude: Boolean) =
        mangaDao.setExcludeFromUpdates(mangaId, exclude)

    suspend fun getMangaByUrl(url: String): MangaEntity? = mangaDao.getMangaByUrl(url)
    suspend fun upsertManga(manga: MangaEntity) = mangaDao.upsert(manga)

    suspend fun setMalId(mangaId: String, malId: Int?) = mangaDao.setMalId(mangaId, malId)
    suspend fun setMalScore(mangaId: String, score: Float?) = mangaDao.setMalScore(mangaId, score)
    suspend fun setMalStatus(mangaId: String, status: String?) = mangaDao.setMalStatus(mangaId, status)
    suspend fun setKitsuId(mangaId: String, kitsuId: String?) = mangaDao.setKitsuId(mangaId, kitsuId)
    suspend fun setKitsuScore(mangaId: String, score: Float?) = mangaDao.setKitsuScore(mangaId, score)
    suspend fun setMangaUpdatesId(mangaId: String, seriesId: Long?) = mangaDao.setMangaUpdatesId(mangaId, seriesId)
    suspend fun addMangaReadingTime(mangaId: String, deltaMs: Long) = mangaDao.addReadingTime(mangaId, deltaMs)
    suspend fun setReadingStatus(mangaId: String, status: String?) = mangaDao.setReadingStatus(mangaId, status)
    fun observeByReadingStatus(status: String): Flow<List<MangaEntity>> = mangaDao.observeByReadingStatus(status)
    suspend fun getAllLibraryForExport(): List<MangaEntity> = mangaDao.getAllLibrary()

    suspend fun updateMangaMetadata(mangaId: String, manga: SManga) =
        mangaDao.updateMetadata(
            mangaId = mangaId,
            author = manga.author,
            artist = manga.artist,
            genres = manga.genres.joinToString(","),
            year = manga.year,
        )

    /**
     * Odebrani z knihovny mangu ani kapitoly nemaze (jen inLibrary = false) - zaroven ale
     * resetuje stav cteni (precteno/pozice/cas), aby pripadne pozdejsi znovu-pridani te
     * same mangy (mangaId/chapterId jsou deterministicke ze zdroje+URL, viz mangaId()/
     * chapterId() nize) nezdedilo stary stav z doby pred odebranim - viz nahlaseny bug,
     * kdy cerstve pridany titul vypadal jako uz kompletne precteny.
     */
    suspend fun removeFromLibrary(mangaId: String) {
        mangaDao.setInLibrary(mangaId, false)
        mangaDao.resetReadProgress(mangaId)
        chapterDao.resetProgressForManga(mangaId)
    }

    /**
     * Znovu dotáhne kompletní detail mangy ze zdroje (popis, stav, autor, žánry,
     * typ obsahu, a u ComicK i demographic/translationCompleted/hasAnime/finalChapter)
     * a uloží ho - viz [MadaraSource.getMangaDetails], kde weby jako mangaread.org
     * hostí smíchaný obsah a uvádí přesný typ u každého titulu zvlášť.
     *
     * Dřív se jmenovala refreshContentType a ukládala jen contentType - zbytek dat
     * ze source.getMangaDetails() se tiše zahazoval, takže např. ComicK tituly
     * nikdy nezobrazily popis/stav/žánry ani po ručním refreshi.
     *
     * Volá se jen z ručního refreshe (Knihovna pull-to-refresh, refresh na detailu,
     * a od teď i jednorázově při prvním otevření ComicK titulu - viz
     * MangaDetailViewModel.init), NE z [com.haise.jiyu.work.ChapterUpdateWorker] -
     * ten běží tiše na pozadí nad celou knihovnou a stahovat kvůli kosmetickým
     * detailům navíc denně by nebylo úměrné.
     */
    suspend fun refreshMangaDetails(mangaId: String, manga: SManga) {
        val source = sourceManager.getById(manga.sourceId) ?: return
        val detail = try { source.getMangaDetails(manga) } catch (_: Exception) { return }
        val existing = mangaDao.getById(mangaId) ?: return
        val updated = existing.copy(
            description = detail.description ?: existing.description,
            status = detail.status ?: existing.status,
            author = detail.author ?: existing.author,
            artist = detail.artist ?: existing.artist,
            genres = detail.genres.takeIf { it.isNotEmpty() }?.joinToString(",") ?: existing.genres,
            year = detail.year ?: existing.year,
            contentType = detail.contentType,
            demographic = detail.demographic ?: existing.demographic,
            translationCompleted = detail.translationCompleted ?: existing.translationCompleted,
            hasAnime = detail.hasAnime ?: existing.hasAnime,
            finalChapter = detail.finalChapter ?: existing.finalChapter,
            rating = detail.rating ?: existing.rating,
            followCount = detail.followCount ?: existing.followCount,
            rank = detail.rank ?: existing.rank,
            alternateTitles = detail.alternateTitles.takeIf { it.isNotEmpty() }?.let { serializeAltTitles(it) } ?: existing.alternateTitles,
        )
        // LibraryViewModel vola tohle pro kazdy titul v knihovne pri kazdem pull-to-refresh -
        // bez tehle podminky by to byl plny row-write (+ Room invalidace/Flow re-emit) pro
        // kazdy titul pri kazdem refreshi, i kdyz se ze zdroje nic nezmenilo.
        if (updated != existing) mangaDao.upsert(updated)
    }

    /**
     * Dotáhne obálku z detailu mangy - pro zdroje, které ji nemají v rychlém výpisu
     * (Novelhall, Comics Kingdom, Dynasty Scans - viz komentáře u jejich `getPopular`/
     * `parseFeatures`), jen na detailní stránce jednoho titulu. Volá [SourceBrowseViewModel]
     * líně, jen pro karty, které se skutečně dostanou do viewportu Procházet (viz
     * `LaunchedEffect` v `BrowseMangaCard`) - ne pro celou stránku výsledků najednou.
     */
    suspend fun fetchCover(manga: SManga): String? {
        val source = sourceManager.getById(manga.sourceId) ?: return null
        return try { source.getMangaDetails(manga).coverUrl } catch (_: Exception) { null }
    }

    /** Vrací seznam nově přidaných kapitol (existující kapitoly jsou přeskočeny). */
    suspend fun refreshChapters(mangaId: String, manga: SManga): List<ChapterEntity> {
        val source = sourceManager.getById(manga.sourceId) ?: return emptyList()
        val chapters = source.getChapterList(manga)
        // discoveredAt se pouziva jen pri SKUTECNEM prvnim vlozeni radku (insertNewOnly nize
        // duplicity ignoruje) - proto staci jedno "ted" pro celou davku, ne per-kapitola cas.
        val now = System.currentTimeMillis()
        val entities = chapters.map { chapter ->
            ChapterEntity(
                id = chapterId(chapter),
                mangaId = mangaId,
                sourceId = chapter.sourceId,
                url = chapter.url,
                name = chapter.name,
                chapterNumber = chapter.chapterNumber,
                dateUpload = chapter.dateUpload,
                scanlationGroup = chapter.scanlationGroup,
                volume = chapter.volume,
                groupsJson = serializeChapterGroups(chapter.groups),
                discoveredAt = now,
            )
        }
        val rowIds = chapterDao.insertNewOnly(entities)
        return entities.filterIndexed { index, _ -> rowIds[index] != -1L }
    }

    suspend fun getChapterPages(sourceId: String, chapterUrl: String, mangaUrl: String): List<com.haise.jiyu.source.Page> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        val chapter = SChapter(sourceId, mangaUrl, chapterUrl, "", 0f, 0L)
        return source.getPageList(chapter)
    }

    suspend fun setDownloadStatus(chapterEntityId: String, status: DownloadStatus) =
        chapterDao.setDownloadStatus(chapterEntityId, status)

    suspend fun markDownloaded(chapterEntityId: String, localPath: String, pageCount: Int) =
        chapterDao.markDownloaded(chapterEntityId, DownloadStatus.DOWNLOADED, localPath, pageCount)

    suspend fun updateReadProgress(chapterEntityId: String, read: Boolean, lastPageRead: Int, lastReadAt: Long = 0L) =
        chapterDao.updateProgress(chapterEntityId, read, lastPageRead, lastReadAt)

    suspend fun updateScrollOffset(chapterEntityId: String, offset: Int, lastReadAt: Long) =
        chapterDao.updateScrollOffset(chapterEntityId, offset, lastReadAt)

    suspend fun updateLastReadChapter(mangaId: String, chapterId: String) =
        mangaDao.updateLastReadChapterAndTime(mangaId, chapterId, System.currentTimeMillis())

    suspend fun getChapter(chapterEntityId: String) = chapterDao.getById(chapterEntityId)
    suspend fun getManga(mangaId: String) = mangaDao.getById(mangaId)

    // ── Categories ───────────────────────────────────────────────────────────

    fun observeCategories(): Flow<List<CategoryEntity>> = categoryDao.observeAll()
    fun observeCategoryIdsForManga(mangaId: String) = categoryDao.observeCategoryIdsForManga(mangaId)
    suspend fun createCategory(category: CategoryEntity) = categoryDao.upsert(category)
    suspend fun deleteCategory(category: CategoryEntity) = categoryDao.delete(category)
    suspend fun addMangaToCategory(mangaId: String, categoryId: String) =
        categoryDao.addMangaToCategory(MangaCategoryEntity(mangaId, categoryId))
    suspend fun upsertAllMangaCategories(pairs: List<Pair<String, String>>) =
        categoryDao.addAllMangaToCategories(pairs.map { (mId, cId) -> MangaCategoryEntity(mId, cId) })
    suspend fun removeMangaFromCategory(mangaId: String, categoryId: String) =
        categoryDao.removeMangaFromCategory(mangaId, categoryId)

    // ── Vlastní zdroje (Madara) ──────────────────────────────────────────────

    fun observeCustomSources(): Flow<List<CustomSourceEntity>> = customSourceDao.observeAll()
    suspend fun addCustomSource(
        name: String,
        baseUrl: String,
        listItemSelector: String? = null,
        titleLinkSelector: String? = null,
        descriptionSelector: String? = null,
        statusSelector: String? = null,
        chapterListSelector: String? = null,
        pageImageSelector: String? = null,
        contentType: String = "MANGA",
    ) = customSourceDao.upsert(
        CustomSourceEntity(
            name = name,
            baseUrl = baseUrl,
            listItemSelector = listItemSelector,
            titleLinkSelector = titleLinkSelector,
            descriptionSelector = descriptionSelector,
            statusSelector = statusSelector,
            chapterListSelector = chapterListSelector,
            pageImageSelector = pageImageSelector,
            contentType = contentType,
        )
    )
    suspend fun deleteCustomSource(source: CustomSourceEntity) = customSourceDao.delete(source)
    suspend fun getAllCustomSourcesOnce(): List<CustomSourceEntity> = customSourceDao.getAllOnce()
    /** Zachová původní id (na rozdíl od addCustomSource) - potřeba pro obnovu zálohy, kde na id ukazují sourceId manga. */
    suspend fun upsertAllCustomSources(sources: List<CustomSourceEntity>) = customSourceDao.upsertAll(sources)

    // ── Related manga (MangaDex) ──────────────────────────────────────────────

    suspend fun getRelatedManga(mangaId: String): List<SManga> {
        val mdMangaId = mangaId.substringAfterLast("/")
        return mangaDexSource.getRelatedManga(mdMangaId)
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    fun mangaId(sourceId: String, url: String) = "$sourceId::$url"
    fun chapterId(chapter: SChapter) = "${chapter.sourceId}::${chapter.url}"
}

/** JSON pole [{"name":...,"slug":...}] pro uložení SChapter.groups do ChapterEntity.groupsJson. */
internal fun serializeChapterGroups(groups: List<SGroup>): String? =
    groups.takeIf { it.isNotEmpty() }?.let { list ->
        // put(String, Object) s null hodnotou klic ODEBERE misto zapisu JSON null
        // (org.json.JSONObject chovani) - proto explicitni JSONObject.NULL misto it.slug.
        JSONArray(list.map { JSONObject().apply { put("name", it.name); put("slug", it.slug ?: JSONObject.NULL) } }).toString()
    }

/** JSON pole řetězců pro uložení SManga.alternateTitles do MangaEntity.alternateTitles. */
internal fun serializeAltTitles(titles: List<String>): String =
    JSONArray(titles).toString()

/** Protějšek [serializeAltTitles] - přečte `MangaEntity.alternateTitles` zpátky do seznamu. */
internal fun deserializeAltTitles(json: String?): List<String> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Protějšek [serializeChapterGroups] - přečte `ChapterEntity.groupsJson` zpátky do [SGroup] seznamu. */
internal fun deserializeChapterGroups(json: String?): List<SGroup> {
    if (json.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            SGroup(
                name = obj.optString("name"),
                slug = if (obj.isNull("slug")) null else obj.optString("slug").ifBlank { null },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}
