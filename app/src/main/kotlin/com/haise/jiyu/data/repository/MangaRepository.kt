package com.haise.jiyu.data.repository

import androidx.room.withTransaction
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.ChapterDao
import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.data.db.MangaCategoryMapping
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.MangaDownloadedCount
import com.haise.jiyu.data.db.MangaTotalCount
import com.haise.jiyu.data.db.MangaUnreadCount
import com.haise.jiyu.data.db.ManualTranslationDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.db.TranslatedNovelDao
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.util.ChapterStorage
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.haise.jiyu.data.db.entity.MangaCategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.settings.SettingsRepository
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

private const val PAGES_TTL_MS = 10L * 60 * 1000
private const val PAGES_MAX = 8
private const val DETAILS_TTL_MS = 5L * 60 * 1000
private const val DETAILS_MAX = 16

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
    private val manualTranslationDao: ManualTranslationDao,
    private val readHistoryDao: ReadHistoryDao,
    private val translatedPageDao: TranslatedPageDao,
    private val translatedNovelDao: TranslatedNovelDao,
    private val settings: SettingsRepository,
    private val db: AppDatabase,
    @param:ApplicationContext private val context: Context,
    // Sdílená paměťová cache výsledků ze zdrojů (seznam stránek, detail) - viz [SourceContentCache].
    private val contentCache: SourceContentCache = SourceContentCache(),
) {
    // ── Library ──────────────────────────────────────────────────────────────

    fun observeLibrary(): Flow<List<MangaEntity>> = mangaDao.observeLibrary()
    fun observeLibraryInCategory(categoryId: String) = categoryDao.observeMangaInCategory(categoryId)
    fun observeMangaById(mangaId: String): Flow<MangaEntity?> = mangaDao.observeById(mangaId)
    suspend fun getAllLibraryManga(): List<MangaEntity> = mangaDao.getAllLibrary()
    suspend fun getAllLibraryGenres(): List<String> = mangaDao.getAllLibraryGenres()
    suspend fun getAllLibraryAuthors(): List<String> = mangaDao.getAllLibraryAuthors()
    fun observeUpdates(): Flow<List<com.haise.jiyu.data.db.UpdateItem>> = chapterDao.observeUpdates()
    suspend fun markEverythingRead() = chapterDao.markAllRead()
    fun observeRecentlyRead(): Flow<List<MangaEntity>> = mangaDao.observeRecentlyRead()
    fun observeContinueReading(): Flow<List<com.haise.jiyu.data.db.ContinueReadingItem>> = mangaDao.observeContinueReading()
    fun observeRecentlyAdded(): Flow<List<MangaEntity>> = mangaDao.observeRecentlyAdded()
    fun observeCompleted(): Flow<List<MangaEntity>> = mangaDao.observeCompleted()

    // ── Chapters ─────────────────────────────────────────────────────────────

    fun observeChapters(mangaId: String): Flow<List<ChapterEntity>> = chapterDao.observeForManga(mangaId)
    suspend fun getAllChapters(mangaId: String): List<ChapterEntity> = chapterDao.getAllForManga(mangaId)
    suspend fun markAllChaptersRead(mangaIds: List<String>) = chapterDao.markAllReadForMangas(mangaIds)
    suspend fun resetActiveDownloads() = chapterDao.resetActiveDownloads()
    fun observeReadChaptersCount(): Flow<Int> = chapterDao.observeReadCount()
    suspend fun getAllLibraryChapters(): List<ChapterEntity> = chapterDao.getAllForLibrary()

    /** Dávkuje po 400, aby nenarazila na SQLite strop na počet parametrů (999). */
    suspend fun getChaptersByIds(ids: List<String>): List<ChapterEntity> =
        ids.chunked(400).flatMap { chapterDao.getByIds(it) }
    fun observeUnreadCounts(): Flow<List<MangaUnreadCount>> = chapterDao.observeUnreadCounts()
    fun observeTotalCounts(): Flow<List<MangaTotalCount>> = chapterDao.observeTotalCounts()
    fun observeDownloadedCountPerManga(): Flow<List<MangaDownloadedCount>> = chapterDao.observeDownloadedCountPerManga()
    fun observeNonEmptyDownloads(): Flow<List<ChapterEntity>> = chapterDao.observeNonEmptyDownloads()
    fun observeDownloadedCount(): Flow<Int> = chapterDao.observeDownloadedCount()
    suspend fun clearAllDownloaded() = chapterDao.clearAllDownloaded()
    suspend fun resetDownloadForChapter(chapterId: String) = chapterDao.resetDownloadForChapter(chapterId)
    suspend fun upsertAllManga(manga: List<com.haise.jiyu.data.db.entity.MangaEntity>) = mangaDao.upsertAll(manga)
    suspend fun setFavorite(mangaId: String, favorite: Boolean) = mangaDao.setFavorite(mangaId, favorite)
    suspend fun setTranslationContextNote(mangaId: String, note: String?) = mangaDao.setTranslationContextNote(mangaId, note?.trim()?.ifBlank { null })
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
                    // Data z listingu bývají chudší než ta z detailu (chybí popis, autor, žánry...) -
                    // prázdná hodnota nesmí přepsat to, co už detail jednou dodal (viz refreshMangaDetails).
                    title = manga.title.ifBlank { existing.title },
                    coverUrl = manga.coverUrl ?: existing.coverUrl,
                    description = manga.description ?: existing.description,
                    status = manga.status ?: existing.status,
                    author = manga.author ?: existing.author,
                    artist = manga.artist ?: existing.artist,
                    genres = manga.genres.joinToString(",").ifBlank { existing.genres },
                    year = manga.year ?: existing.year,
                    // "MANGA" je jen výchozí hodnota SManga - nesmí zpětně přepsat konkrétnější typ (MANHWA...).
                    contentType = if (manga.contentType.isBlank() || (manga.contentType == "MANGA" && existing.contentType != "MANGA")) existing.contentType else manga.contentType,
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
        val matches = getAllLibraryManga()
            .filter { it.sourceId != excludeSourceId && normalizeMangaTitle(it.title) == normalized }
        if (matches.isEmpty()) return emptyList()
        // Jeden batched count misto N+1 (countForManga volaneho zvlast pro kazdou shodu -
        // audit nalez), stejny GROUP BY vzor jako ChapterDao.observeTotalCounts().
        val counts = chapterDao.countForMangas(matches.map { it.id }).associateBy({ it.mangaId }, { it.count })
        return matches.map { entity ->
            DuplicateMatch(
                manga = entity,
                sourceName = sourceManager.getById(entity.sourceId)?.name ?: entity.sourceId,
                chapterCount = counts[entity.id] ?: 0,
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

    suspend fun getMangaBySourceAndUrl(sourceId: String, url: String): MangaEntity? = mangaDao.getMangaBySourceAndUrl(sourceId, url)
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
    /**
     * Odebrani z knihovny mangu ani kapitoly nemaze (jen inLibrary = false) - zaroven ale
     * resetuje stav cteni (precteno/pozice/cas), aby pripadne pozdejsi znovu-pridani te
     * same mangy (mangaId/chapterId jsou deterministicke ze zdroje+URL, viz mangaId()/
     * chapterId() nize) nezdedilo stary stav z doby pred odebranim - viz nahlaseny bug,
     * kdy cerstve pridany titul vypadal jako uz kompletne precteny.
     */
    // db.withTransaction - 3 nezavisle zapisy bez ni mohly pri pádu appky uprostred nechat
    // nekonzistentni stav (napr. inLibrary=false, ale stary read progress nesmazany -
    // nahlaseno v auditu).
    suspend fun removeFromLibrary(mangaId: String) {
        val downloadedPaths = db.withTransaction {
            val paths = chapterDao.getAllForManga(mangaId)
                .filter { it.downloadStatus == DownloadStatus.DOWNLOADED }
                .mapNotNull { it.localPath }
            mangaDao.setInLibrary(mangaId, false)
            mangaDao.resetReadProgress(mangaId)
            chapterDao.resetProgressForManga(mangaId)
            // Bez tohohle zůstaly kapitoly "stažené" s localPath na smazané soubory.
            chapterDao.resetDownloadsForManga(mangaId)
            paths
        }
        // Soubory až PO úspěšné transakci - když spadne zápis do DB, o stažené kapitoly nepřijdeme.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            downloadedPaths.forEach { ChapterStorage.deleteRecursively(context, it) }
        }
        // Pro sync: cloud o odebrani zatim nevi, pri pristim pushi se z toho stane "nahrobek"
        // (in_library=false) - jinak by pull odebrany titul zase vratil (audit nalez JIYU-SEC-1).
        settings.addPendingRemovedMangaId(mangaId)
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
        val detail = try {
            // force = true: tohle je ruční obnovení (pull-to-refresh, refresh na detailu) - má ukázat čerstvá data.
            contentCache.getOrLoad("details", "${manga.sourceId}|${manga.url}", DETAILS_TTL_MS, DETAILS_MAX, force = true) {
                source.getMangaDetails(manga)
            }
        } catch (_: Exception) { return }
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
        return try {
            contentCache.getOrLoad("details", "${manga.sourceId}|${manga.url}", DETAILS_TTL_MS, DETAILS_MAX) {
                source.getMangaDetails(manga)
            }.coverUrl
        } catch (_: Exception) { null }
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
        // Zdroj změnil schéma URL kapitol (nebo je uložené kapitoly z importu zálohy měly jinou
        // podobu URL): všechny by se vložily jako nové a staré řádky by zůstaly i se stavem čtení.
        // Přemapují se podle čísla kapitoly. ComicK (agregátor, víc skupin na jedno číslo) se přeskakuje.
        val migratedIds = if (manga.sourceId == "comick") emptySet() else migrateOrphanedChapters(mangaId, chapters)
        val toInsert = entities.filter { it.id !in migratedIds }
        val rowIds = chapterDao.insertNewOnly(toInsert)
        return toInsert.filterIndexed { index, _ -> rowIds[index] != -1L }
    }

    /**
     * Vrací id kapitol (nová id), které se přemapovaly ze starých řádků. Migruje se jen při silné shodě -
     * aspoň [MIN_ORPHAN_MIGRATION] kapitol a polovina osiřelých - jinak jde o běžný rozdíl (jedna kapitola
     * smazaná a jiná přidaná) a nic se nepřepojuje.
     */
    private suspend fun migrateOrphanedChapters(mangaId: String, chapters: List<SChapter>): Set<String> {
        val existing = chapterDao.getAllForManga(mangaId)
        if (existing.isEmpty()) return emptySet()
        val fetchedIds = chapters.mapTo(HashSet()) { chapterId(it) }
        val existingIds = existing.mapTo(HashSet()) { it.id }
        val orphans = existing.filter { it.id !in fetchedIds && !it.isFallbackSource }
        val fresh = chapters.filter { chapterId(it) !in existingIds }
        if (orphans.isEmpty() || fresh.isEmpty()) return emptySet()
        val plan = planChapterMigration(orphans, fresh)
        if (plan.relink.size < maxOf(MIN_ORPHAN_MIGRATION, orphans.size / 2)) return emptySet()
        db.withTransaction { applyChapterRelink(plan.relink) }
        return plan.relink.mapTo(HashSet()) { (_, new) -> chapterId(new) }
    }

    /**
     * Zkusí najít titul znovu NA STEJNÉM zdroji podle názvu, když jeho uložená URL přestala
     * fungovat (web se přestavěl, titul samotný pořád existuje) - viz [findBestTitleMatch]/
     * [planChapterMigration]. Nikdy nemění [MangaEntity.id] (stabilní identita napříč appkou -
     * skutečný SQL ForeignKey z MangaCategoryEntity na ni nemá ON UPDATE CASCADE), jen
     * url/detaily. Volá se jen jako fallback z MangaDetailViewModel.refreshChapters() po
     * selhání běžného obnovení, nikdy pro zdroj ComicK (metadatový katalog, nemá vlastní
     * "web" k opravě).
     *
     * Vrací true, pokud se povedlo najít a přemapovat.
     */
    suspend fun recoverMangaLink(mangaId: String): Boolean {
        val existing = mangaDao.getById(mangaId) ?: return false
        if (existing.sourceId == "comick") return false
        val source = sourceManager.getById(existing.sourceId) ?: return false

        val candidates = try {
            source.search(existing.title)
        } catch (_: Exception) {
            return false
        }
        val match = findBestTitleMatch(candidates, existing.title) ?: return false
        if (match.url == existing.url) return false

        val newChapters = try {
            source.getChapterList(match)
        } catch (_: Exception) {
            emptyList()
        }
        if (newChapters.isEmpty()) return false

        val oldChapters = chapterDao.getAllForManga(mangaId)
        val plan = planChapterMigration(oldChapters, newChapters)

        // Cela prestavba (relink + vlozeni novych kapitol + aktualizace mangy) v JEDNE
        // transakci - drive bezelo jako rada nezavislych zapisu, takze pad/preruseni uprostred
        // (napr. appka zabita na pozadi) nechal mangu napul premigrovanou (nektere kapitoly
        // uz relinknute na nove id, jine porad na starem).
        db.withTransaction {
            applyChapterRelink(plan.relink)
            if (plan.newOnly.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val entities = plan.newOnly.map { chapter ->
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
                chapterDao.insertNewOnly(entities)
            }

            // Cilene UPDATE tri sloupcu, ne upsert celeho snapshotu `existing` (cteneho pred sitovymi
            // volanimi): plny upsert vracel stare lastReadChapterId po relinkLastReadChapter
            // nahore a prepisoval i vse, co uzivatel mezitim zmenil (progres, inLibrary,
            // hodnoceni, MAL id) - audit nalez JIYU-DB-1.
            mangaDao.relinkManga(id = mangaId, url = match.url, title = match.title, coverUrl = match.coverUrl)
        }
        return true
    }

    /**
     * Přemapuje uložené kapitoly na nové id/URL (viz [planChapterMigration]) a přenese všechny
     * tabulky, které na kapitolu ukazují jen řetězcem. Musí běžet v transakci volajícího.
     */
    private suspend fun applyChapterRelink(relink: List<Pair<ChapterEntity, SChapter>>) {
        relink.forEach { (old, new) ->
            val newId = chapterId(new)
            chapterDao.relink(
                oldId = old.id,
                newId = newId,
                newUrl = new.url,
                newName = new.name,
                dateUpload = new.dateUpload,
                scanlationGroup = new.scanlationGroup,
                volume = new.volume,
                groupsJson = serializeChapterGroups(new.groups),
            )
            // relink() meni ChapterEntity.id - vsechny dalsi tabulky, ktere na kapitolu
            // ukazuji jen obycejnym string sloupcem/predponou (zadny FK/cascade), by na
            // stare id ukazovaly do prazdna (viz jednotlive relinkChapter/relinkXxx doc
            // komentare - audit nalez "relink sirotí lastReadChapterId/fallbackChapterId/
            // read_history/translated_*").
            manualTranslationDao.relinkChapter(oldChapterId = old.id, newChapterId = newId)
            mangaDao.relinkLastReadChapter(oldChapterId = old.id, newChapterId = newId)
            chapterDao.relinkFallbackChapterId(oldChapterId = old.id, newChapterId = newId)
            readHistoryDao.relinkChapter(oldChapterId = old.id, newChapterId = newId)
            translatedPageDao.relinkChapter(oldChapterId = old.id, newChapterId = newId)
            translatedNovelDao.relinkChapter(oldChapterId = old.id, newChapterId = newId)
        }
    }

    suspend fun getChapterPages(
        sourceId: String,
        chapterUrl: String,
        mangaUrl: String,
        force: Boolean = false,
    ): List<com.haise.jiyu.source.Page> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        val chapter = SChapter(sourceId, mangaUrl, chapterUrl, "", 0f, 0L)
        // Souběžná volání (čtečka, předstahování další kapitoly, překladové preloady, stahování) sdílí jedno načtení.
        // Vrací se KOPIE - `Page.imageUrl` je měnitelné a volající ho může doplnit (viz MangaSource.getImageUrl).
        return contentCache.getOrLoad("pages", "$sourceId|$chapterUrl", PAGES_TTL_MS, PAGES_MAX, force) {
            source.getPageList(chapter)
        }.map { it.copy() }
    }

    suspend fun getChapterComments(sourceId: String, chapterUrl: String): List<com.haise.jiyu.source.comments.ChapterComment> {
        val source = sourceManager.getById(sourceId) ?: return emptyList()
        return source.getChapterComments(SChapter(sourceId, "", chapterUrl, "", 0f, 0L))
    }

    suspend fun sourceSupportsChapterComments(sourceId: String): Boolean =
        sourceManager.getById(sourceId)?.supportsChapterComments ?: false

    /**
     * Domovska URL zdroje pro dany [sourceId] - pouziva se jako `Referer` hlavicka pri
     * stahovani obrazku stranek (viz ReaderViewModel.pageReferer). Rada webu ma
     * hotlink-protection na obrazkovem CDN (kontroluje, ze Referer sedi na jejich vlastni
     * domenu) - bez tehle hlavicky appka dosud stahovala obrazky stranek uplne bez
     * Refereru, coz se u takovych zdroju projevovalo jako cerne/nenactene stranky.
     */
    suspend fun sourceHomepage(sourceId: String): String? = sourceManager.getById(sourceId)?.homepageUrl

    suspend fun setDownloadStatus(chapterEntityId: String, status: DownloadStatus) =
        chapterDao.setDownloadStatus(chapterEntityId, status)

    suspend fun markDownloaded(chapterEntityId: String, localPath: String, pageCount: Int) =
        chapterDao.markDownloaded(chapterEntityId, DownloadStatus.DOWNLOADED, localPath, pageCount)

    suspend fun setVerifiedPageCount(chapterEntityId: String, count: Int, isFallback: Boolean, fallbackChapterId: String? = null) =
        chapterDao.setVerifiedPageCount(chapterEntityId, count, isFallback, fallbackChapterId)

    suspend fun updateReadProgress(chapterEntityId: String, read: Boolean, lastPageRead: Int, lastReadAt: Long = 0L) =
        chapterDao.updateProgress(chapterEntityId, read, lastPageRead, lastReadAt)

    /** Batched varianta [updateReadProgress] pro import historie ze zálohy (viz
     * TachiyomiBackupImporter) - jedno UPDATE místo jednoho volání na kapitolu.
     * Dávkuje po 400, aby nenarazila na SQLite strop na počet parametrů (999). */
    suspend fun markChaptersRead(chapterIds: List<String>) {
        chapterIds.chunked(400).forEach { chunk -> chapterDao.markReadByIds(chunk) }
    }

    /** Opak [markChaptersRead] - vrátí i pozici čtení (stejně jako dřívější `updateReadProgress(read = false)`). */
    suspend fun markChaptersUnread(chapterIds: List<String>) {
        chapterIds.chunked(400).forEach { chunk -> chapterDao.markUnreadByIds(chunk) }
    }

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
    ) {
        // Stejná adresa podruhé = úprava existujícího zdroje (nový nesmí vzniknout jako duplicita
        // se stejným katalogem a jiným id, na které by ukazovaly jen některé tituly).
        val normalized = baseUrl.trim().trimEnd('/').lowercase()
        val existing = customSourceDao.getAllOnce()
            .firstOrNull { it.baseUrl.trim().trimEnd('/').lowercase() == normalized }
        customSourceDao.upsert(
        (existing ?: CustomSourceEntity(name = name, baseUrl = baseUrl)).copy(
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
    }
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

    private companion object {
        /** Nejmenší počet spárovaných kapitol, aby se osiřelé řádky přemapovaly - viz [migrateOrphanedChapters]. */
        const val MIN_ORPHAN_MIGRATION = 3
    }

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
