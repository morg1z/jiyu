package com.haise.jiyu.ui.comicresolver

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.comic.ComicResolver
import com.haise.jiyu.source.comick.ResolvedCandidate
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Řazení komiksových kandidátů - stejná logika jako rankNovelCandidates:
 * oblíbený zdroj → anglický zdroj s čísly → nejvíc čísel.
 */
internal fun rankComicCandidates(candidates: List<ResolvedCandidate>): List<ResolvedCandidate> =
    candidates.sortedWith(
        compareByDescending<ResolvedCandidate> { it.isFavorite && it.matchedChapterCount > 0 }
            .thenByDescending { it.source.language.startsWith("en", ignoreCase = true) && it.matchedChapterCount > 0 }
            .thenByDescending { it.matchedChapterCount }
    )

@HiltViewModel
class ComicResolverViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val resolver: ComicResolver,
    private val repository: MangaRepository,
) : ViewModel() {

    /** Název titulu ze sjednoceného katalogu (ComicHomeViewModel merge klíč). */
    val title: String = checkNotNull(savedStateHandle["title"])

    private var searchJob: Job? = null
    private var hasAutoResolved = false

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _candidates = MutableStateFlow<List<ResolvedCandidate>>(emptyList())
    val candidates: StateFlow<List<ResolvedCandidate>> = _candidates.asStateFlow()

    private val _searchingMore = MutableStateFlow(false)
    val searchingMore: StateFlow<Boolean> = _searchingMore.asStateFlow()

    private val _resolving = MutableStateFlow(false)
    val resolving: StateFlow<Boolean> = _resolving.asStateFlow()

    private val _openedMangaId = MutableStateFlow<String?>(null)
    val openedMangaId: StateFlow<String?> = _openedMangaId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun clearError() { _error.value = null }

    init {
        searchJob = viewModelScope.launch {
            try {
                _searchingMore.value = true
                resolver.findCandidatesFlow(title)
                    .onCompletion {
                        _searchingMore.value = false
                        if (hasAutoResolved) return@onCompletion
                        val sorted = rankComicCandidates(_candidates.value)
                        _candidates.value = sorted
                        sorted.firstOrNull()?.let { selectCandidate(it) }
                    }
                    .collect { candidate ->
                        _candidates.value = _candidates.value + candidate
                        _loading.value = false
                        if (!hasAutoResolved && candidate.isFavorite && candidate.matchedChapterCount > 0) {
                            hasAutoResolved = true
                            selectCandidate(candidate)
                            searchJob?.cancel()
                        }
                    }
            } catch (e: Exception) {
                e.report("comicresolver:findCandidates")
            } finally {
                _loading.value = false
                _searchingMore.value = false
            }
        }
    }

    fun selectCandidate(candidate: ResolvedCandidate) {
        if (_resolving.value) return
        _resolving.value = true
        viewModelScope.launch {
            try {
                _openedMangaId.value = repository.registerPreview(candidate.manga)
            } catch (e: Exception) {
                e.report("comicresolver:selectCandidate")
                _error.value = e.toFriendlyMessage()
            } finally {
                _resolving.value = false
            }
        }
    }
}
