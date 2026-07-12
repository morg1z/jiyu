package com.haise.jiyu.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.DuplicateMatch
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.SourceManager
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceResult(
    val source: MangaSource,
    val loading: Boolean = true,
    val results: List<SManga> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class GlobalSearchViewModel @Inject constructor(
    private val sourceManager: SourceManager,
    private val repository: MangaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val savedSearches: StateFlow<List<String>> = settings.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun saveSearch(q: String) = viewModelScope.launch { if (q.isNotBlank()) settings.addSavedSearch(q) }
    fun removeSavedSearch(q: String) = viewModelScope.launch { settings.removeSavedSearch(q) }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _results = MutableStateFlow<List<SourceResult>>(emptyList())
    val results: StateFlow<List<SourceResult>> = _results.asStateFlow()

    fun search(q: String) {
        if (q.isBlank()) return
        _query.value = q
        viewModelScope.launch {
            val sources = sourceManager.getAll()
            _results.value = sources.map { SourceResult(it) }
            sources.mapIndexed { i, source ->
                async {
                    val result = try {
                        val list = repository.search(source.id, q, 1, MangaFilter())
                        SourceResult(source, loading = false, results = list.take(10))
                    } catch (e: Exception) {
                        SourceResult(source, loading = false, error = e.toFriendlyMessage())
                    }
                    _results.update { current ->
                        current.map { if (it.source.id == source.id) result else it }
                            .sortedWith(compareBy {
                                when {
                                    it.results.isNotEmpty() -> 0
                                    it.loading -> 1
                                    it.error != null -> 2
                                    else -> 3
                                }
                            })
                    }
                }
            }.awaitAll()
        }
    }

    fun mangaId(manga: SManga): String = repository.mangaId(manga.sourceId, manga.url)

    fun addToLibrary(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val matches = repository.findLibraryMatchesByTitle(manga.title, manga.sourceId)
                if (matches.isNotEmpty()) {
                    val sourceName = _results.value.find { it.source.id == manga.sourceId }?.source?.name ?: manga.sourceId
                    _pendingDuplicateAdd.value = PendingAdd(manga, sourceName, matches, onAdded = onAdded)
                    launch {
                        val count = repository.previewChapterCount(manga)
                        _pendingDuplicateAdd.update { it?.copy(newChapterCount = count) }
                    }
                    return@launch
                }
                performAdd(manga, onAdded)
            } catch (_: Exception) {}
        }
    }

    private fun performAdd(manga: SManga, onAdded: (String) -> Unit) {
        viewModelScope.launch {
            try {
                repository.addToLibrary(manga)
                val id = repository.mangaId(manga.sourceId, manga.url)
                val catId = settings.defaultCategoryId.first()
                if (catId != null) repository.addMangaToCategory(id, catId)
                onAdded(id)
            } catch (_: Exception) {}
        }
    }

    // ── Detekce duplicit při přidávání ──────────────────────────────────────────
    data class PendingAdd(
        val manga: SManga,
        val newSourceName: String,
        val matches: List<DuplicateMatch>,
        val newChapterCount: Int? = null,
        val onAdded: (String) -> Unit,
    )

    private val _pendingDuplicateAdd = MutableStateFlow<PendingAdd?>(null)
    val pendingDuplicateAdd: StateFlow<PendingAdd?> = _pendingDuplicateAdd.asStateFlow()

    fun confirmAddDespiteDuplicate() {
        val pending = _pendingDuplicateAdd.value ?: return
        _pendingDuplicateAdd.value = null
        performAdd(pending.manga, pending.onAdded)
    }

    fun cancelDuplicateAdd() { _pendingDuplicateAdd.value = null }
}
