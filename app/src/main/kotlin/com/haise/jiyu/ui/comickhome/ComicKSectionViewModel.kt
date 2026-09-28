package com.haise.jiyu.ui.comickhome

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.comick.ComicKSource
import com.haise.jiyu.source.comick.ReviewItem
import com.haise.jiyu.util.report
import com.haise.jiyu.util.toFriendlyMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "Zobrazit vše" na jednu sekci ComicK domovské obrazovky - znovupoužívá [ComicKSource.getTop]'s cache, žádný nový network request. */
@HiltViewModel
class ComicKSectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val comicKSource: ComicKSource,
    private val repository: MangaRepository,
) : ViewModel() {

    private val section: String = checkNotNull(savedStateHandle["section"])
    private val window: String = savedStateHandle.get<String>("window")?.ifBlank { "7" } ?: "7"

    val title: String = savedStateHandle.get<String>("title").orEmpty()

    private val _comics = MutableStateFlow<List<SManga>>(emptyList())
    val comics: StateFlow<List<SManga>> = _comics.asStateFlow()

    private val _reviews = MutableStateFlow<List<ReviewItem>>(emptyList())
    val reviews: StateFlow<List<ReviewItem>> = _reviews.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _openingManga = MutableStateFlow<SManga?>(null)
    val openingManga: StateFlow<SManga?> = _openingManga.asStateFlow()

    private val _openError = MutableStateFlow<String?>(null)
    val openError: StateFlow<String?> = _openError.asStateFlow()

    init {
        load()
    }

    fun retry() = load()

    private fun load() {
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            try {
                val feed = comicKSource.getTop()
                when (section) {
                    "recently_added"      -> _comics.value = feed.recentlyAdded
                    "completed"           -> _comics.value = feed.completed
                    "popular_new"         -> _comics.value = feed.popularNew[window].orEmpty()
                    "most_recent_popular" -> _comics.value = feed.mostRecentPopular[window].orEmpty()
                    "recent_reviews"      -> _reviews.value = feed.recentReviews
                }
            } catch (e: Exception) {
                e.report("comicksection:$section")
                _error.value = e.toFriendlyMessage()
            } finally {
                _loading.value = false
            }
        }
    }

    fun openManga(manga: SManga, onOpened: (String) -> Unit) {
        if (_openingManga.value != null) return
        _openingManga.value = manga
        viewModelScope.launch {
            try {
                val id = repository.registerPreview(manga)
                onOpened(id)
            } catch (e: Exception) {
                e.report("comicksection:openManga")
                _openError.value = e.toFriendlyMessage()
            } finally {
                _openingManga.value = null
            }
        }
    }

    fun clearOpenError() { _openError.value = null }
}
