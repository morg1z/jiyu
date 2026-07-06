package com.haise.jiyu.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.entity.CATEGORY_COLORS
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import com.haise.jiyu.source.SManga
import dagger.hilt.android.lifecycle.HiltViewModel
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

enum class LibrarySortOption { TITLE, LAST_UPDATED, UNREAD_COUNT }

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
) : ViewModel() {

    val categories: StateFlow<List<CategoryEntity>> = repository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(LibrarySortOption.TITLE)
    val sortOption: StateFlow<LibrarySortOption> = _sortOption.asStateFlow()

    private val _sortAscending = MutableStateFlow(true)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

    // ── Počty kapitol ─────────────────────────────────────────────────────────
    val unreadCounts: StateFlow<Map<String, Int>> = repository.observeUnreadCounts()
        .map { list -> list.associate { it.mangaId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val library: StateFlow<List<MangaEntity>> = combine(
        _selectedCategoryId.flatMapLatest { categoryId ->
            if (categoryId == null) repository.observeLibrary()
            else repository.observeLibraryInCategory(categoryId)
        },
        _searchQuery,
        unreadCounts,
        combine(_sortOption, _sortAscending, ::Pair),
    ) { list, query, unread, (sort, ascending) ->
        val filtered = if (query.isBlank()) list
            else list.filter { it.title.contains(query, ignoreCase = true) }

        val sorted = when (sort) {
            LibrarySortOption.TITLE        -> filtered.sortedBy { it.title.lowercase() }
            LibrarySortOption.LAST_UPDATED -> filtered.sortedByDescending { it.lastUpdated }
            LibrarySortOption.UNREAD_COUNT -> filtered.sortedByDescending { unread[it.id] ?: 0 }
        }
        if (ascending) sorted else sorted.reversed()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Historie čtení ───────────────────────────────────────────────────────
    val recentlyRead: StateFlow<List<MangaEntity>> = repository.observeRecentlyRead()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalCounts: StateFlow<Map<String, Int>> = repository.observeTotalCounts()
        .map { list -> list.associate { it.mangaId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val downloadedPerManga: StateFlow<Map<String, Int>> = repository.observeDownloadedCountPerManga()
        .map { list -> list.associate { it.mangaId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    // ── Pull-to-refresh ───────────────────────────────────────────────────────
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshLibrary() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val allManga = repository.getAllLibraryManga()
            allManga.forEach { manga ->
                try {
                    val sManga = SManga(manga.sourceId, manga.url, manga.title, manga.coverUrl, manga.description, manga.status)
                    repository.refreshChapters(manga.id, sManga)
                } catch (_: Exception) { }
            }
            _isRefreshing.value = false
        }
    }

    // ── Kategorie ─────────────────────────────────────────────────────────────

    fun selectCategory(id: String?) { _selectedCategoryId.value = id }

    fun setSearchQuery(query: String) { _searchQuery.value = query }

    fun setSortOption(option: LibrarySortOption) {
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
}
