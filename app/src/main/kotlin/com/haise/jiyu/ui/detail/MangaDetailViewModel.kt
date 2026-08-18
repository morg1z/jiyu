package com.haise.jiyu.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.haise.jiyu.R
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.GlossaryDao
import com.haise.jiyu.data.db.MangaNoteDao
import com.haise.jiyu.data.db.MangaTagDao
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.data.tracking.KitsuAuthManager
import com.haise.jiyu.data.tracking.KitsuManga
import com.haise.jiyu.data.tracking.KitsuRepository
import com.haise.jiyu.data.tracking.MalManga
import com.haise.jiyu.data.tracking.MalRepository
import com.haise.jiyu.data.tracking.MangaUpdatesRepository
import com.haise.jiyu.data.tracking.MuManga
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.repository.DuplicateMatch
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.comick.ComicKComment
import com.haise.jiyu.source.comick.ComicKRecommendation
import com.haise.jiyu.source.comick.SCover
import com.haise.jiyu.util.ChapterStorage
import com.haise.jiyu.util.NetworkMonitor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.haise.jiyu.util.report

@HiltViewModel
class MangaDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val appContext: Context,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
    private val translateQueue: com.haise.jiyu.translate.TranslateQueue,
    private val networkMonitor: NetworkMonitor,
    private val mangaNoteDao: MangaNoteDao,
    private val mangaTagDao: MangaTagDao,
    private val glossaryDao: GlossaryDao,
    private val settings: com.haise.jiyu.settings.SettingsRepository,
    private val aniListRepository: AniListRepository,
    private val malRepository: MalRepository,
    private val kitsuAuthManager: KitsuAuthManager,
    private val kitsuRepository: KitsuRepository,
    private val muRepository: MangaUpdatesRepository,
    private val sourceManager: SourceManager,
    private val comicKSource: ComicKSource,
) : ViewModel() {

    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])

    init {
        // Auto-retry chapter load when connectivity is restored after an error
        viewModelScope.launch {
            networkMonitor.networkState.drop(1).collect { online ->
                if (online && _errorMessage.value != null && !_isRefreshing.value) refreshChapters()
            }
        }
        // ComicK detail endpoint doplňuje popis/stav/žánry/demographic/anime/final chapter,
        // které se v rychlém výpisu (getPopular/search) nikdy neposílají - dotáhneme je jednou
        // potichu na pozadí hned při prvním otevření titulu, aby uživatel neplatil "cenu"
        // ručního pull-to-refreshe jen proto, aby viděl základní metadata. Fire-and-forget:
        // refreshMangaDetails() sám ignoruje síťové chyby, žádný loading indikátor se tu
        // nezobrazuje - ale DB čtení/zápis (observeMangaById().first{}, mangaDao uvnitř
        // repository) tím pokryté nejsou, proto try/catch + e.report() i tady.
        // description.isNullOrBlank() slouží jako "ještě neobohaceno" signál, aby se tohle
        // nespouštělo znovu při každém otevření detailu, jen dokud titul opravdu čeká na obohacení.
        viewModelScope.launch {
            try {
                val current = repository.observeMangaById(mangaId).first { it != null }
                if (current?.sourceId == "comick" && current.description.isNullOrBlank()) {
                    val sManga = SManga(current.sourceId, current.url, current.title, current.coverUrl, current.description, current.status, contentType = current.contentType)
                    repository.refreshMangaDetails(mangaId, sManga)
                }
            } catch (e: Exception) {
                e.report("detail:autoRefreshComick")
            }
        }
    }

    val manga: StateFlow<MangaEntity?> = repository.observeMangaById(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Citelny nazev zdroje (napr. "ComicK") - viz detail_info_source, pomaha dohledat
     * odkud konkretni titul je, kdyz je potreba ladit chybu specifickou pro jeden zdroj. */
    val sourceName: StateFlow<String?> = manga
        .map { it?.sourceId }
        .distinctUntilChanged()
        .map { id -> id?.let { sourceManager.getById(it)?.name ?: it } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Galerie vsech historickych obalek - jen ComicK (viz ComicKSource.getCoverGallery
    // proc jinde nejde). null = jeste nenacteno, prazdny seznam = nacteno a fakt nic neni.
    private val _coverGallery = MutableStateFlow<List<SCover>?>(null)
    val coverGallery: StateFlow<List<SCover>?> = _coverGallery.asStateFlow()
    private var coverGalleryLoading = false

    fun loadCoverGallery() {
        val current = manga.value ?: return
        if (current.sourceId != "comick" || coverGalleryLoading || _coverGallery.value != null) return
        coverGalleryLoading = true
        viewModelScope.launch {
            try {
                _coverGallery.value = comicKSource.getCoverGallery(current.url)
            } catch (e: Exception) {
                e.report("detail:loadCoverGallery")
                _coverGallery.value = emptyList()
            } finally {
                coverGalleryLoading = false
            }
        }
    }

    // Komentare pod titulem (jen ComicK, viz ComicKSource.getComments) - jen ke cteni,
    // appka nema napojeny ComicK ucet potrebny pro psani novych. Stranky se hromadi do
    // jednoho seznamu (stejny vzor jako ComicKBrowseViewModel.loadMore), commentsHasMore
    // rika UI, jestli jeste ma smysl zobrazit "Nacist dalsi".
    private val _comments = MutableStateFlow<List<ComicKComment>>(emptyList())
    val comments: StateFlow<List<ComicKComment>> = _comments.asStateFlow()
    private val _commentsTotal = MutableStateFlow(0)
    val commentsTotal: StateFlow<Int> = _commentsTotal.asStateFlow()
    private val _commentsLoading = MutableStateFlow(false)
    val commentsLoading: StateFlow<Boolean> = _commentsLoading.asStateFlow()
    private val _commentsError = MutableStateFlow<String?>(null)
    val commentsError: StateFlow<String?> = _commentsError.asStateFlow()
    private var commentsHasMore = true
    private var commentsPage = 1

    fun loadMoreComments() {
        val current = manga.value ?: return
        if (current.sourceId != "comick" || _commentsLoading.value || !commentsHasMore) return
        _commentsLoading.value = true
        viewModelScope.launch {
            try {
                val result = comicKSource.getComments(current.url, commentsPage)
                _comments.value = _comments.value + result.comments
                _commentsTotal.value = result.total
                commentsHasMore = result.hasMore
                commentsPage++
                _commentsError.value = null
            } catch (e: Exception) {
                e.report("detail:loadComments")
                _commentsError.value = e.toFriendlyMessage()
            } finally {
                _commentsLoading.value = false
            }
        }
    }

    // "Doporučené" (ComicK Recommendations) - jen ComicK, viz ComicKSource.getRecommendations.
    // null = jeste nenacteno (lazy, az na klepnuti - stejny vzor jako coverGallery vyse),
    // prazdny seznam = nacteno a titul zadna doporuceni nema.
    private val _recommendations = MutableStateFlow<List<ComicKRecommendation>?>(null)
    val recommendations: StateFlow<List<ComicKRecommendation>?> = _recommendations.asStateFlow()
    private var recommendationsLoading = false

    fun loadRecommendations() {
        val current = manga.value ?: return
        if (current.sourceId != "comick" || recommendationsLoading || _recommendations.value != null) return
        recommendationsLoading = true
        viewModelScope.launch {
            try {
                _recommendations.value = comicKSource.getRecommendations(current.url)
            } catch (e: Exception) {
                e.report("detail:loadRecommendations")
                _recommendations.value = emptyList()
            } finally {
                recommendationsLoading = false
            }
        }
    }

    private val _openingRecommendation = MutableStateFlow(false)
    val openingRecommendation: StateFlow<Boolean> = _openingRecommendation.asStateFlow()

    fun openRecommendation(target: SManga, onOpened: (String) -> Unit) {
        if (_openingRecommendation.value) return
        _openingRecommendation.value = true
        viewModelScope.launch {
            try {
                val id = repository.openPreview(target)
                onOpened(id)
            } catch (e: Exception) {
                e.report("detail:openRecommendation")
                _errorMessage.value = e.toFriendlyMessage()
            } finally {
                _openingRecommendation.value = false
            }
        }
    }

    val relatedManga: StateFlow<List<SManga>> = flow {
        emit(emptyList())
        try { emit(repository.getRelatedManga(mangaId)) } catch (e: Exception) { e.report("detail:relatedManga") }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Řazení + filtrování kapitol ───────────────────────────────────────────
    private val _sortAscending = MutableStateFlow(true)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    // Řazení od aktuální pozice čtení — aktuální/následující kapitola nahoře,
    // pak zbytek vzestupně. Vypíná se, když uživatel ručně přepne sort.
    private val _sortByProgress = MutableStateFlow(true)
    val sortByProgress: StateFlow<Boolean> = _sortByProgress.asStateFlow()
    fun disableProgressSort() { _sortByProgress.value = false }

    private val _chapterFilter = MutableStateFlow("")
    val chapterFilter: StateFlow<String> = _chapterFilter.asStateFlow()

    private val _statusFilter = MutableStateFlow("ALL")
    val statusFilter: StateFlow<String> = _statusFilter.asStateFlow()
    fun setStatusFilter(filter: String) { _statusFilter.value = filter }

    private val _selectedScanlator = MutableStateFlow<String?>(null)
    val selectedScanlator: StateFlow<String?> = _selectedScanlator.asStateFlow()
    fun setScanlator(s: String?) { _selectedScanlator.value = s }

    private val _rawChapters: Flow<List<ChapterEntity>> = repository.observeChapters(mangaId)

    val availableScanlators: StateFlow<List<String>> = _rawChapters.map { chs ->
        chs.mapNotNull { it.scanlationGroup }.distinct().sorted()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chapters: StateFlow<List<ChapterEntity>> = combine(
        _rawChapters,
        _sortAscending,
        _chapterFilter,
        _statusFilter,
        _selectedScanlator,
        manga,
        _sortByProgress,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val list = args[0] as List<ChapterEntity>
        val asc = args[1] as Boolean
        val textFilter = args[2] as String
        val statusFilter = args[3] as String
        val scanlator = args[4] as String?
        @Suppress("UNCHECKED_CAST")
        val m = args[5] as MangaEntity?
        val byProgress = args[6] as Boolean
        var result = list
        if (textFilter.isNotBlank()) {
            result = result.filter { it.name.contains(textFilter, ignoreCase = true) }
        }
        result = when (statusFilter) {
            "UNREAD"     -> result.filter { !it.read }
            "READ"       -> result.filter { it.read }
            "DOWNLOADED" -> result.filter { it.downloadStatus == DownloadStatus.DOWNLOADED }
            else         -> result
        }
        if (scanlator != null) {
            result = result.filter { it.scanlationGroup == scanlator }
        }
        // Progress-based sort: najdi aktuální kapitolu (lastReadChapterId nebo
        // první nepřečtená) a seřaď tak, aby byla nahoře, pak následující vzestupně.
        if (byProgress && m != null) {
            val anchorId = m.lastReadChapterId
            val anchor = anchorId?.let { id -> result.find { it.id == id } }
                ?: result.filter { !it.read }.minByOrNull { it.chapterNumber }
            if (anchor != null) {
                val anchorNum = anchor.chapterNumber
                // Kapitoly >= anchorNum vzestupně (anchor, anchor+1, ...), pak zbytek.
                val fromAnchor = result.filter { it.chapterNumber >= anchorNum }.sortedBy { it.chapterNumber }
                val before = result.filter { it.chapterNumber < anchorNum }.sortedByDescending { it.chapterNumber }
                fromAnchor + before
            } else if (asc) result.sortedBy { it.chapterNumber }
            else result.sortedByDescending { it.chapterNumber }
        } else if (asc) result.sortedBy { it.chapterNumber }
        else result.sortedByDescending { it.chapterNumber }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Continue tlačítko ─────────────────────────────────────────────────────
    val continueChapter: StateFlow<ChapterEntity?> = combine(manga, chapters) { m, chs ->
        if (chs.isEmpty()) return@combine null
        val lastId = m?.lastReadChapterId
        if (lastId != null) {
            chs.find { it.id == lastId }
        } else {
            chs.minByOrNull { it.chapterNumber }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── První nepřečtená kapitola (#33) ───────────────────────────────────────
    val firstUnreadChapter: StateFlow<ChapterEntity?> = chapters.map { chs ->
        chs.filter { !it.read }.minByOrNull { it.chapterNumber }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Auto-stahování (#32) ──────────────────────────────────────────────────
    val autoDownload: StateFlow<Boolean> = manga.map { it?.autoDownload ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // ── Vyloučit z aktualizací ────────────────────────────────────────────────
    val excludeFromUpdates: StateFlow<Boolean> = manga.map { it?.excludeFromUpdates ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleExcludeFromUpdates() = viewModelScope.launch {
        repository.setExcludeFromUpdates(mangaId, !excludeFromUpdates.value)
    }

    // ── Oblíbené ──────────────────────────────────────────────────────────────
    val isFavorite: StateFlow<Boolean> = manga.map { it?.isFavorite ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleFavorite() = viewModelScope.launch {
        repository.setFavorite(mangaId, !isFavorite.value)
    }

    // ── Poznámky (#27) ────────────────────────────────────────────────────────
    val mangaNote: StateFlow<MangaNoteEntity?> = mangaNoteDao.observeForManga(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Tagy (#26) ────────────────────────────────────────────────────────────
    val mangaTags: StateFlow<List<MangaTagEntity>> = mangaTagDao.observeForManga(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Slovník AI překladu (konzistentní jména/techniky napříč kapitolami) ────
    val glossary: StateFlow<List<GlossaryEntity>> = glossaryDao.observeForManga(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val defaultTargetLanguage: StateFlow<String> = settings.targetLanguage
        .stateIn(viewModelScope, SharingStarted.Eagerly, "Czech")

    fun addGlossaryEntry(sourceTerm: String, targetTerm: String, targetLanguage: String) {
        val source = sourceTerm.trim()
        val target = targetTerm.trim()
        if (source.isBlank() || target.isBlank()) return
        viewModelScope.launch {
            glossaryDao.upsert(
                GlossaryEntity(
                    id = "$mangaId::${source.lowercase()}::$targetLanguage",
                    mangaId = mangaId,
                    sourceTerm = source,
                    targetTerm = target,
                    targetLanguage = targetLanguage,
                )
            )
        }
    }

    fun removeGlossaryEntry(entry: GlossaryEntity) = viewModelScope.launch { glossaryDao.delete(entry) }

    // ── Čtecí status ──────────────────────────────────────────────────────────
    val readingStatus: StateFlow<String?> = manga.map { it?.readingStatus }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Nastavení statusu (Čtu/Dokončeno/...) samo o sobě NEpřidává mangu do knihovny -
     * [MangaDao.setReadingStatus] jen zapíše sloupec `readingStatus`, zatímco Knihovna i
     * Seznam (viz [MangaDao.observeLibrary]/[MangaDao.observeByReadingStatus]) filtrují
     * na `inLibrary = 1`. Bez tohohle volání by manga se zvoleným statusem, na kterou
     * uživatel nikdy neťukl bookmark ikonu, zůstala navždy neviditelná v obou seznamech -
     * přesně nahlášený bug (status nastaven, historie čtení existuje, ale 0 titulů všude).
     */
    fun setReadingStatus(status: String?) = viewModelScope.launch {
        repository.setReadingStatus(mangaId, status)
        if (status != null && manga.value?.inLibrary != true) addToLibrary()
    }

    // ── Hodnocení (#41) ───────────────────────────────────────────────────────
    val userRating: StateFlow<Int?> = manga.map { it?.userRating }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Čas čtení této mangy ──────────────────────────────────────────────────
    val readingTimeMs: StateFlow<Long> = manga.map { it?.readingTimeMs ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // ── MAL tracking ──────────────────────────────────────────────────────────
    val malId: StateFlow<Int?> = manga.map { it?.malId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val malScore: StateFlow<Float?> = manga.map { it?.malScore }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val malHasClientId get() = malRepository.hasClientId

    private val _malSearchResults = MutableStateFlow<List<MalManga>>(emptyList())
    val malSearchResults: StateFlow<List<MalManga>> = _malSearchResults.asStateFlow()

    private val _malSearchLoading = MutableStateFlow(false)
    val malSearchLoading: StateFlow<Boolean> = _malSearchLoading.asStateFlow()

    fun searchMal(query: String) {
        viewModelScope.launch {
            _malSearchLoading.value = true
            _malSearchResults.value = malRepository.searchManga(query)
            _malSearchLoading.value = false
        }
    }

    fun linkMalId(malManga: MalManga) = viewModelScope.launch {
        repository.setMalId(mangaId, malManga.id)
        repository.setMalScore(mangaId, malManga.score)
    }

    fun unlinkMal() = viewModelScope.launch {
        repository.setMalId(mangaId, null)
        repository.setMalScore(mangaId, null)
    }

    fun openMalPage(context: Context) {
        malId.value?.let { malRepository.openMalPage(context, it) }
    }

    /** Stáhne uživatelův status/skóre z MAL webu zpět do appky (obousměrná synchronizace). */
    fun syncFromMal() = viewModelScope.launch {
        val id = malId.value ?: return@launch
        val remote = malRepository.getMyStatus(id) ?: return@launch
        remote.status?.let { status ->
            val mapped = when (status) {
                "reading"      -> "READING"
                "completed"    -> "COMPLETED"
                "on_hold"      -> "ON_HOLD"
                "dropped"      -> "DROPPED"
                "plan_to_read" -> "PLAN_TO_READ"
                else           -> null
            }
            if (mapped != null) repository.setReadingStatus(mangaId, mapped)
        }
        remote.score?.let { score -> repository.setRating(mangaId, score * 10) }
    }

    // ── AniList tracking ──────────────────────────────────────────────────────
    val aniListIsLoggedIn: StateFlow<Boolean> = aniListRepository.isAuthenticated
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val aniListId: StateFlow<Int?> = settings.aniListIdMap
        .map { json ->
            try { org.json.JSONObject(json).optInt(mangaId, 0).takeIf { it > 0 } } catch (_: Exception) { null }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _aniListSearchResults = MutableStateFlow<List<AniListRepository.AniListManga>>(emptyList())
    val aniListSearchResults: StateFlow<List<AniListRepository.AniListManga>> = _aniListSearchResults.asStateFlow()

    private val _aniListSearchLoading = MutableStateFlow(false)
    val aniListSearchLoading: StateFlow<Boolean> = _aniListSearchLoading.asStateFlow()

    fun searchAniList(query: String) {
        viewModelScope.launch {
            _aniListSearchLoading.value = true
            _aniListSearchResults.value = aniListRepository.searchManga(query)
            _aniListSearchLoading.value = false
        }
    }

    fun linkAniList(result: AniListRepository.AniListManga) = viewModelScope.launch {
        aniListRepository.linkManually(mangaId, result.id)
    }

    fun unlinkAniList() = viewModelScope.launch {
        aniListRepository.unlink(mangaId)
    }

    fun openAniListPage(context: Context) {
        aniListId.value?.let { id ->
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://anilist.co/manga/$id")))
        }
    }

    // ── Kitsu tracking ────────────────────────────────────────────────────────
    val kitsuId: StateFlow<String?> = manga.map { it?.kitsuId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val kitsuScore: StateFlow<Float?> = manga.map { it?.kitsuScore }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val kitsuIsLoggedIn: StateFlow<Boolean> = kitsuAuthManager.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _kitsuSearchResults = MutableStateFlow<List<KitsuManga>>(emptyList())
    val kitsuSearchResults: StateFlow<List<KitsuManga>> = _kitsuSearchResults.asStateFlow()

    private val _kitsuSearchLoading = MutableStateFlow(false)
    val kitsuSearchLoading: StateFlow<Boolean> = _kitsuSearchLoading.asStateFlow()

    fun searchKitsu(query: String) {
        viewModelScope.launch {
            _kitsuSearchLoading.value = true
            _kitsuSearchResults.value = kitsuRepository.searchManga(query)
            _kitsuSearchLoading.value = false
        }
    }

    fun linkKitsu(kitsuManga: KitsuManga) = viewModelScope.launch {
        repository.setKitsuId(mangaId, kitsuManga.id)
        repository.setKitsuScore(mangaId, kitsuManga.score)
        val userId = kitsuRepository.fetchUserId()
        if (userId != null) kitsuAuthManager.saveUserId(userId)
    }

    fun unlinkKitsu() = viewModelScope.launch {
        repository.setKitsuId(mangaId, null)
        repository.setKitsuScore(mangaId, null)
    }

    fun openKitsuPage(context: Context) {
        kitsuId.value?.let { kitsuRepository.openKitsuPage(context, it) }
    }

    /** Stáhne uživatelův status/skóre z Kitsu webu zpět do appky (obousměrná synchronizace). */
    fun syncFromKitsu() = viewModelScope.launch {
        val id = kitsuId.value ?: return@launch
        val remote = kitsuRepository.getMyLibraryEntry(id) ?: return@launch
        remote.status?.let { status ->
            val mapped = when (status) {
                "current"   -> "READING"
                "completed" -> "COMPLETED"
                "on_hold"   -> "ON_HOLD"
                "dropped"   -> "DROPPED"
                "planned"   -> "PLAN_TO_READ"
                else        -> null
            }
            if (mapped != null) repository.setReadingStatus(mangaId, mapped)
        }
        remote.ratingTwenty?.let { rt -> repository.setRating(mangaId, rt * 5) }
    }

    // ── MangaUpdates tracking ─────────────────────────────────────────────────
    val muId: StateFlow<Long?> = manga.map { it?.mangaUpdatesId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val muIsLoggedIn: StateFlow<Boolean> = muRepository.isLoggedIn
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _muSearchResults = MutableStateFlow<List<MuManga>>(emptyList())
    val muSearchResults: StateFlow<List<MuManga>> = _muSearchResults.asStateFlow()

    private val _muSearchLoading = MutableStateFlow(false)
    val muSearchLoading: StateFlow<Boolean> = _muSearchLoading.asStateFlow()

    fun searchMu(query: String) {
        viewModelScope.launch {
            _muSearchLoading.value = true
            _muSearchResults.value = muRepository.searchManga(query)
            _muSearchLoading.value = false
        }
    }

    fun linkMu(muManga: MuManga) = viewModelScope.launch {
        repository.setMangaUpdatesId(mangaId, muManga.id)
    }

    fun unlinkMu() = viewModelScope.launch {
        repository.setMangaUpdatesId(mangaId, null)
    }

    fun openMuPage(context: Context) {
        muId.value?.let { muRepository.openMuPage(context, it) }
    }

    /** Stáhne uživatelův status/skóre z MangaUpdates webu zpět do appky (obousměrná synchronizace). */
    fun syncFromMu() = viewModelScope.launch {
        val id = muId.value ?: return@launch
        val remote = muRepository.getMyStatus(id) ?: return@launch
        remote.listId?.let { listId ->
            val mapped = when (listId) {
                0    -> "READING"
                1    -> "PLAN_TO_READ"
                2    -> "COMPLETED"
                3    -> "DROPPED"
                4    -> "ON_HOLD"
                else -> null
            }
            if (mapped != null) repository.setReadingStatus(mangaId, mapped)
        }
        remote.rating?.let { r -> repository.setRating(mangaId, (r * 10).toInt()) }
    }

    // ── Kategorie ─────────────────────────────────────────────────────────────
    val allCategories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val mangaCategoryIds: StateFlow<List<String>> = repository.observeCategoryIdsForManga(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Pull-to-refresh & error ───────────────────────────────────────────────
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ── Akce ──────────────────────────────────────────────────────────────────

    fun toggleSort() { _sortByProgress.value = false; _sortAscending.value = !_sortAscending.value }

    fun setChapterFilter(query: String) { _chapterFilter.value = query }

    fun markAllRead() {
        viewModelScope.launch {
            repository.getAllChapters(mangaId).forEach { chapter ->
                repository.updateReadProgress(chapter.id, read = true, lastPageRead = 0)
            }
        }
    }

    fun toggleCategory(categoryId: String) {
        viewModelScope.launch {
            if (categoryId in mangaCategoryIds.value) repository.removeMangaFromCategory(mangaId, categoryId)
            else repository.addMangaToCategory(mangaId, categoryId)
        }
    }

    fun removeFromLibrary() {
        viewModelScope.launch {
            chapters.value.forEach { chapter ->
                chapter.localPath?.let { path -> ChapterStorage.deleteRecursively(appContext, path) }
            }
            repository.removeFromLibrary(mangaId)
        }
    }

    // ── Přidání do knihovny (z náhledu mangy ze zdroje, co ještě není přidaná) ──
    data class PendingLibraryAdd(
        val sourceName: String,
        val matches: List<DuplicateMatch>,
        val newChapterCount: Int,
    )

    private val _pendingLibraryAdd = MutableStateFlow<PendingLibraryAdd?>(null)
    val pendingLibraryAdd: StateFlow<PendingLibraryAdd?> = _pendingLibraryAdd.asStateFlow()

    fun addToLibrary() {
        val m = manga.value ?: return
        viewModelScope.launch {
            val matches = repository.findLibraryMatchesByTitle(m.title, m.sourceId)
            if (matches.isNotEmpty()) {
                val sourceName = sourceManager.getById(m.sourceId)?.name ?: m.sourceId
                _pendingLibraryAdd.value = PendingLibraryAdd(sourceName, matches, chapters.value.size)
                return@launch
            }
            performAddToLibrary()
        }
    }

    private fun performAddToLibrary() {
        viewModelScope.launch {
            repository.addExistingToLibrary(mangaId)
            val catId = settings.defaultCategoryId.first()
            if (catId != null) repository.addMangaToCategory(mangaId, catId)
        }
    }

    fun confirmAddDespiteDuplicate() {
        _pendingLibraryAdd.value = null
        performAddToLibrary()
    }

    fun cancelDuplicateAdd() { _pendingLibraryAdd.value = null }

    fun refreshChapters() {
        val current = manga.value ?: return
        if (!networkMonitor.isOnline) {
            _errorMessage.value = appContext.getString(R.string.detail_error_no_internet)
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                val sManga = SManga(current.sourceId, current.url, current.title, current.coverUrl, current.description, current.status, contentType = current.contentType)
                repository.refreshChapters(mangaId, sManga)
                repository.refreshMangaDetails(mangaId, sManga)
            } catch (e: Exception) {
                _errorMessage.value = appContext.getString(R.string.detail_error_refresh_failed, e.toFriendlyMessage())
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun downloadChapter(chapter: ChapterEntity) {
        if (chapter.sourceId == "comick") return
        val mangaUrl = manga.value?.url ?: return
        viewModelScope.launch {
            repository.setDownloadStatus(chapter.id, DownloadStatus.QUEUED)
            downloadQueue.enqueue(chapter, mangaUrl)
        }
    }

    fun downloadAll() {
        val mangaUrl = manga.value?.url ?: return
        viewModelScope.launch {
            chapters.value
                .filter { it.downloadStatus == DownloadStatus.NOT_DOWNLOADED || it.downloadStatus == DownloadStatus.ERROR }
                .filter { it.sourceId != "comick" }
                .forEach { chapter ->
                    repository.setDownloadStatus(chapter.id, DownloadStatus.QUEUED)
                    downloadQueue.enqueue(chapter, mangaUrl)
                }
        }
    }

    fun downloadUnread() {
        val mangaUrl = manga.value?.url ?: return
        viewModelScope.launch {
            chapters.value
                .filter { !it.read && (it.downloadStatus == DownloadStatus.NOT_DOWNLOADED || it.downloadStatus == DownloadStatus.ERROR) }
                .filter { it.sourceId != "comick" }
                .forEach { chapter ->
                    repository.setDownloadStatus(chapter.id, DownloadStatus.QUEUED)
                    downloadQueue.enqueue(chapter, mangaUrl)
                }
        }
    }

    /**
     * Zaradi dalsich [n] neprectenych kapitol k prekladu na pozadi (viz TranslateChapterWorker).
     *
     * Bere se od nejnizsiho cisla kapitoly z NEPRECTENYCH - to je "kam se ctenar dostal".
     * Prelozit dopredu uz precetene kapitoly nedava smysl a jen by to sezralo znakovou kvotu.
     */
    fun translateNextN(n: Int) {
        viewModelScope.launch {
            val ids = com.haise.jiyu.translate.chaptersToTranslateAhead(
                chapters.value.map {
                    com.haise.jiyu.translate.TranslatableChapter(
                        id = it.id,
                        number = it.chapterNumber,
                        read = it.read,
                    )
                },
                count = n,
            )
            translateQueue.enqueue(ids)
        }
    }

    fun downloadFirstN(n: Int) {
        val mangaUrl = manga.value?.url ?: return
        viewModelScope.launch {
            chapters.value
                .filter { !it.read && (it.downloadStatus == DownloadStatus.NOT_DOWNLOADED || it.downloadStatus == DownloadStatus.ERROR) }
                .filter { it.sourceId != "comick" }
                .sortedBy { it.chapterNumber }
                .take(n)
                .forEach { chapter ->
                    repository.setDownloadStatus(chapter.id, DownloadStatus.QUEUED)
                    downloadQueue.enqueue(chapter, mangaUrl)
                }
        }
    }

    fun markReadUpTo(chapterId: String) {
        viewModelScope.launch {
            val target = repository.getChapter(chapterId) ?: return@launch
            repository.getAllChapters(mangaId)
                .filter { it.chapterNumber <= target.chapterNumber }
                .forEach { repository.updateReadProgress(it.id, read = true, lastPageRead = 0) }
        }
    }

    fun markChapterRead(chapterId: String, read: Boolean) {
        viewModelScope.launch {
            repository.updateReadProgress(chapterId, read = read, lastPageRead = 0)
        }
    }

    fun clearError() { _errorMessage.value = null }

    fun setReaderDirection(direction: String?) {
        viewModelScope.launch { repository.setMangaReaderDirection(mangaId, direction) }
    }

    // ── Auto-stahování (#32) ──────────────────────────────────────────────────
    fun toggleAutoDownload() {
        viewModelScope.launch { repository.setAutoDownload(mangaId, !autoDownload.value) }
    }

    // ── Hromadné označení rozsahu ─────────────────────────────────────────────
    fun markAllOlderAsRead(chapter: ChapterEntity) = viewModelScope.launch {
        repository.getAllChapters(mangaId)
            .filter { it.chapterNumber <= chapter.chapterNumber }
            .forEach { repository.updateReadProgress(it.id, read = true, lastPageRead = 0) }
    }

    fun markAllNewerAsUnread(chapter: ChapterEntity) = viewModelScope.launch {
        repository.getAllChapters(mangaId)
            .filter { it.chapterNumber >= chapter.chapterNumber }
            .forEach { repository.updateReadProgress(it.id, read = false, lastPageRead = 0) }
    }

    // ── Poznámky (#27) ────────────────────────────────────────────────────────
    fun saveNote(content: String) {
        viewModelScope.launch {
            if (content.isBlank()) {
                mangaNoteDao.deleteForManga(mangaId)
            } else {
                mangaNoteDao.upsert(MangaNoteEntity(mangaId = mangaId, content = content))
            }
        }
    }

    // ── Hodnocení (#41) ───────────────────────────────────────────────────────
    fun setRating(rating: Int) {
        viewModelScope.launch {
            repository.setRating(mangaId, rating)
            val m = manga.value ?: return@launch
            try { aniListRepository.updateScore(mangaId, m.title, rating * 20) } catch (e: Exception) { e.report("detail:anilist:updateScore") }
        }
    }

    fun clearRating() {
        viewModelScope.launch { repository.setRating(mangaId, null) }
    }


    // ── Tagy (#26) ────────────────────────────────────────────────────────────
    fun addTag(tag: String) {
        val trimmed = tag.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { mangaTagDao.insert(MangaTagEntity(mangaId = mangaId, tag = trimmed)) }
    }

    fun removeTag(tag: String) {
        viewModelScope.launch { mangaTagDao.delete(MangaTagEntity(mangaId = mangaId, tag = tag)) }
    }
}
