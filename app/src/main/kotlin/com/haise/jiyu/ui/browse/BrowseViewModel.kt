package com.haise.jiyu.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SourceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Drží jen seznam zdrojů (filtrovaný podle typu obsahu a jazyka) pro mřížku na
 * hlavní obrazovce Procházet. Výsledky/hledání/stránkování pro konkrétní zdroj
 * má na starosti [SourceBrowseViewModel], otevřený až po kliknutí na dlaždici.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    sourceManager: SourceManager,
) : ViewModel() {

    private val _allSources: StateFlow<List<MangaSource>> = sourceManager.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _contentTypeFilter = MutableStateFlow("ALL")
    val contentTypeFilter: StateFlow<String> = _contentTypeFilter.asStateFlow()

    private val _languageFilter = MutableStateFlow("ALL")
    val languageFilter: StateFlow<String> = _languageFilter.asStateFlow()

    val sources: StateFlow<List<MangaSource>> = combine(
        _allSources, _contentTypeFilter, _languageFilter,
    ) { all, type, lang ->
        all.filter { src ->
            matchesContentType(src.contentType, type) &&
            (lang == "ALL" || src.language == lang)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setContentTypeFilter(type: String) { _contentTypeFilter.value = type }
    fun setLanguageFilter(lang: String) { _languageFilter.value = lang }

    private fun matchesContentType(sourceType: String, filter: String): Boolean = when (filter) {
        "ALL" -> true
        // "Manga" tag v UI zahrnuje manga/manhwa/manhua dohromady - jde jen o
        // region asijského komiksu, uzivatele je zajima spis "je to asijske"
        // vs. "je to western komiks/novela", ne presny puvod.
        MANGA_GROUP -> sourceType == "MANGA" || sourceType == "MANHWA" || sourceType == "MANHUA"
        else -> sourceType == filter
    }

    companion object {
        const val MANGA_GROUP = "MANGA_GROUP"
    }
}
