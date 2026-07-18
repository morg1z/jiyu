package com.haise.jiyu.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.CATEGORY_COLORS
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import com.haise.jiyu.local.LocalMangaImporter
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.SManga
import com.haise.jiyu.util.ChapterStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import kotlin.random.Random

enum class LibrarySortOption { TITLE, LAST_UPDATED, UNREAD_COUNT, DATE_ADDED, RANDOM }

sealed interface LocalImportState {
    data object Idle      : LocalImportState
    data object Importing : LocalImportState
    data class  Done(val chapterId: String) : LocalImportState
    data class  Error(val message: String)  : LocalImportState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
    private val localMangaImporter: LocalMangaImporter,
    private val settings: SettingsRepository,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    private val _contentTypeFilter = MutableStateFlow("ALL")
    val contentTypeFilter: StateFlow<String> = _contentTypeFilter.asStateFlow()
    fun setContentTypeFilter(type: String) { _contentTypeFilter.value = type }

    private val _readingStatusFilter = MutableStateFlow("ALL")
    val readingStatusFilter: StateFlow<String> = _readingStatusFilter.asStateFlow()
    fun setReadingStatusFilter(status: String) { _readingStatusFilter.value = status }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(LibrarySortOption.TITLE)
    val sortOption: StateFlow<LibrarySortOption> = _sortOption.asStateFlow()

    private val _sortAscending = MutableStateFlow(true)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    private var randomSeed = System.currentTimeMillis()

    // ── Počty kapitol ─────────────────────────────────────────────────────────
    val unreadCounts: StateFlow<Map<String, Int>> = repository.observeUnreadCounts()
        .map { list -> list.associate { it.mangaId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val library: StateFlow<List<MangaEntity>> = combine(
        combine(
            _selectedCategoryId.flatMapLatest { categoryId ->
                if (categoryId == null) repository.observeLibrary()
                else repository.observeLibraryInCategory(categoryId)
            },
            _contentTypeFilter,
            _readingStatusFilter,
        ) { list, typeFilter, statusFilter ->
            var result = if (typeFilter == "ALL") list else list.filter { it.contentType == typeFilter }
            if (statusFilter != "ALL") result = result.filter { it.readingStatus == statusFilter }
            result
        },
        _searchQuery,
        unreadCounts,
        combine(_sortOption, _sortAscending, ::Pair),
    ) { list, query, unread, (sort, ascending) ->
        val filtered = if (query.isBlank()) list
            else list.filter { m ->
                m.title.contains(query, ignoreCase = true) ||
                m.author?.contains(query, ignoreCase = true) == true ||
                m.artist?.contains(query, ignoreCase = true) == true ||
                m.genres.contains(query, ignoreCase = true)
            }

        val sorted = when (sort) {
            LibrarySortOption.TITLE        -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.LAST_UPDATED -> filtered.sortedByDescending { it.lastUpdated }
            LibrarySortOption.UNREAD_COUNT -> filtered.sortedByDescending { unread[it.id] ?: 0 }
            LibrarySortOption.DATE_ADDED   -> filtered.sortedByDescending { it.addedAt }
            LibrarySortOption.RANDOM       -> filtered.shuffled(Random(randomSeed))
        }
        if (ascending) sorted else sorted.reversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Historie čtení ───────────────────────────────────────────────────────
    val recentlyRead: StateFlow<List<MangaEntity>> = repository.observeRecentlyRead()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Dashboard karusely (hlavní Knihovna) ────────────────────────────────────
    val continueReading: StateFlow<List<com.haise.jiyu.data.db.ContinueReadingItem>> = repository.observeContinueReading()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentlyAdded: StateFlow<List<MangaEntity>> = repository.observeRecentlyAdded()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completed: StateFlow<List<MangaEntity>> = repository.observeCompleted()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalCounts: StateFlow<Map<String, Int>> = repository.observeTotalCounts()
        .map { list -> list.associate { it.mangaId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val downloadedPerManga: StateFlow<Map<String, Int>> = repository.observeDownloadedCountPerManga()
        .map { list -> list.associate { it.mangaId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ── Statistiky pro dashboard hlavičku ────────────────────────────────────
    val libraryCount: StateFlow<Int> = repository.observeLibraryCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val favoriteCount: StateFlow<Int> = repository.observeFavoriteCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val todayReadingMinutes: StateFlow<Int> = settings.todayReadingTimeMs
        .map { ms -> (ms / 60_000L).toInt() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun toggleFavorite(mangaId: String, current: Boolean) = viewModelScope.launch {
        repository.setFavorite(mangaId, !current)
    }

    init {
        viewModelScope.launch {
            // Kontrola prázdnosti a insert běží atomicky v jedné DB transakci (viz
            // CategoryDao.seedDefaultsIfEmpty) - kdyby se LibraryViewModel vytvořil
            // dvakrát rychle po sobě (např. při navigaci mezi taby), oddělené
            // getAllCategories()+createCategory() volání by mezi kontrolou a insertem
            // nechalo prostor pro souběžný běh druhé instance a vzniku duplicitních
            // výchozích kategorií.
            repository.seedDefaultCategoriesIfEmpty(
                listOf(
                    CategoryEntity(id = UUID.randomUUID().toString(), name = "Čtu",           colorHex = "#8B5CF6"),
                    CategoryEntity(id = UUID.randomUUID().toString(), name = "Dokončené",     colorHex = "#10B981"),
                    CategoryEntity(id = UUID.randomUUID().toString(), name = "Plánuji číst", colorHex = "#22D3EE"),
                    CategoryEntity(id = UUID.randomUUID().toString(), name = "Pozastaveno",  colorHex = "#F59E0B"),
                    CategoryEntity(id = UUID.randomUUID().toString(), name = "Odloženo",     colorHex = "#EF4444"),
                ),
            )
        }
    }

    // ── Bulk selection ────────────────────────────────────────────────────────
    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds: StateFlow<Set<String>> = _selectedIds.asStateFlow()

    fun enterSelectionMode(firstId: String) {
        _selectionMode.value = true
        _selectedIds.value = setOf(firstId)
    }

    fun toggleSelection(id: String) {
        val next = _selectedIds.value.let { if (id in it) it - id else it + id }
        _selectedIds.value = next
        if (next.isEmpty()) _selectionMode.value = false
    }

    fun selectAll() { _selectedIds.value = library.value.map { it.id }.toSet() }

    fun clearSelection() {
        _selectionMode.value = false
        _selectedIds.value = emptySet()
    }

    fun bulkDownload() {
        val ids = _selectedIds.value.toList(); clearSelection()
        ids.forEach { downloadAllChapters(it) }
    }

    fun bulkMarkRead() {
        val ids = _selectedIds.value.toList(); clearSelection()
        viewModelScope.launch { repository.markAllChaptersRead(ids) }
    }

    fun bulkRemoveFromLibrary() {
        val ids = _selectedIds.value.toList(); clearSelection()
        ids.forEach { removeFromLibrary(it) }
    }

    fun bulkAddToCategory(categoryId: String) {
        val ids = _selectedIds.value.toList(); clearSelection()
        viewModelScope.launch { repository.upsertAllMangaCategories(ids.map { it to categoryId }) }
    }

    /** Označí celou knihovnu (bez ohledu na aktuální filtr/kategorii) jako přečtenou. */
    fun markEntireLibraryAsRead() = viewModelScope.launch {
        val ids = repository.getAllLibraryManga().map { it.id }
        repository.markAllChaptersRead(ids)
    }

    // ── Pull-to-refresh ───────────────────────────────────────────────────────
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _refreshError = MutableStateFlow<String?>(null)
    val refreshError: StateFlow<String?> = _refreshError.asStateFlow()

    fun clearRefreshError() { _refreshError.value = null }

    fun refreshLibrary() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _refreshError.value = null
            val errors = mutableListOf<String>()
            val allManga = repository.getAllLibraryManga()
            allManga.forEach { manga ->
                try {
                    val sManga = SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, manga.description, manga.status)
                    repository.refreshChapters(manga.id, sManga)
                } catch (e: Exception) {
                    errors += manga.title
                }
            }
            if (errors.isNotEmpty()) {
                val suffix = if (errors.size > 3) context.getString(R.string.library_refresh_error_and_more) else ""
                _refreshError.value = context.getString(R.string.library_refresh_error, errors.take(3).joinToString(), suffix)
            }
            _isRefreshing.value = false
        }
    }

    // ── Kategorie ─────────────────────────────────────────────────────────────

    fun selectCategory(id: String?) { _selectedCategoryId.value = id }

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setSortOption(option: LibrarySortOption) {
        if (option == LibrarySortOption.RANDOM) randomSeed = System.currentTimeMillis()
        if (_sortOption.value == option) _sortAscending.value = !_sortAscending.value
        else { _sortOption.value = option; _sortAscending.value = true }
    }

    fun createCategory(name: String, colorHex: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            repository.createCategory(CategoryEntity(id = UUID.randomUUID().toString(), name = name.trim(), colorHex = colorHex))
        }
    }

    fun deleteCategory(category: CategoryEntity) = viewModelScope.launch {
        if (_selectedCategoryId.value == category.id) _selectedCategoryId.value = null
        repository.deleteCategory(category)
    }

    fun addMangaToCategory(mangaId: String, categoryId: String) = viewModelScope.launch {
        repository.addMangaToCategory(mangaId, categoryId)
    }

    fun removeMangaFromCategory(mangaId: String, categoryId: String) = viewModelScope.launch {
        repository.removeMangaFromCategory(mangaId, categoryId)
    }

    fun observeCategoryIdsForManga(mangaId: String) =
        repository.observeCategoryIdsForManga(mangaId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun nextColor(categories: List<CategoryEntity>): String {
        val usedColors = categories.map { it.colorHex }.toSet()
        return CATEGORY_COLORS.firstOrNull { it !in usedColors } ?: CATEGORY_COLORS[0]
    }

    // ── Long-press akce ───────────────────────────────────────────────────────

    fun removeFromLibrary(mangaId: String) = viewModelScope.launch {
        // Delete downloaded chapter files so they don't linger on disk
        repository.getAllChapters(mangaId)
            .filter { it.downloadStatus == DownloadStatus.DOWNLOADED }
            .forEach { ch ->
                ch.localPath?.let { path -> ChapterStorage.deleteRecursively(context, path) }
            }
        repository.removeFromLibrary(mangaId)
    }

    fun downloadAllChapters(mangaId: String) {
        viewModelScope.launch {
            val manga = repository.getManga(mangaId) ?: return@launch
            val chapters = repository.getAllChapters(mangaId)
            chapters
                .filter { it.downloadStatus == DownloadStatus.NOT_DOWNLOADED || it.downloadStatus == DownloadStatus.ERROR }
                .forEach { chapter ->
                    repository.setDownloadStatus(chapter.id, DownloadStatus.QUEUED)
                    downloadQueue.enqueue(chapter, manga.url)
                }
        }
    }

    // ── Zobrazení ─────────────────────────────────────────────────────────────
    val gridMode: StateFlow<Boolean> = settings.libraryGridMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    fun toggleGridMode() { viewModelScope.launch { settings.setLibraryGridMode(!gridMode.value) } }

    val gridColumns: StateFlow<Int> = settings.libraryGridColumns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 3)
    fun setGridColumns(n: Int) { viewModelScope.launch { settings.setLibraryGridColumns(n) } }
    fun cycleGridColumns() {
        val next = when (gridColumns.value) { 2 -> 3; 3 -> 4; else -> 2 }
        setGridColumns(next)
    }

    // ── Lokální CBZ/ZIP import ────────────────────────────────────────────────
    private val _localImportState = MutableStateFlow<LocalImportState>(LocalImportState.Idle)
    val localImportState: StateFlow<LocalImportState> = _localImportState.asStateFlow()

    fun importLocalFile(uri: Uri) {
        viewModelScope.launch {
            _localImportState.value = LocalImportState.Importing
            localMangaImporter.import(uri).fold(
                onSuccess  = { chapterId -> _localImportState.value = LocalImportState.Done(chapterId) },
                onFailure  = { _localImportState.value = LocalImportState.Error(it.message ?: "Chyba importu") },
            )
        }
    }

    fun clearLocalImportState() { _localImportState.value = LocalImportState.Idle }
}
