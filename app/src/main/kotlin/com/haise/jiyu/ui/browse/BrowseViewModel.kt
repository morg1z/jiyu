package com.haise.jiyu.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.source.SourceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drží jen seznam zdrojů (filtrovaný podle typu obsahu a jazyka) pro mřížku na
 * hlavní obrazovce Procházet. Výsledky/hledání/stránkování pro konkrétní zdroj
 * má na starosti [SourceBrowseViewModel], otevřený až po kliknutí na dlaždici.
 */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    sourceManager: SourceManager,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _allSources: StateFlow<List<MangaSource>> = sourceManager.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Jazyky, ve kterých je k dispozici aspoň jeden zdroj - nejčastější první, angličtina vždy na začátku. */
    val availableLanguages: StateFlow<List<String>> = _allSources
        .map { all ->
            all.groupingBy { it.language.lowercase() }.eachCount().entries
                .sortedWith(compareBy({ it.key != "en" }, { -it.value }, { it.key }))
                .map { it.key }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _contentTypeFilter = MutableStateFlow("ALL")
    val contentTypeFilter: StateFlow<String> = _contentTypeFilter.asStateFlow()

    private val _languageFilter = MutableStateFlow("ALL")
    val languageFilter: StateFlow<String> = _languageFilter.asStateFlow()

    // Lokální filtr podle NÁZVU ZDROJE (ne titulu mangy) - na rozdíl od GlobalSearch
    // nevolá žádný zdroj, jen filtruje už načtenou mřížku, pro uživatele co chtějí
    // najít konkrétní zdroj ("chci číst jen na MangaDexu"), ne prohledat všechny
    // zdroje kvůli jednomu titulu. Viz přepínač režimu hledání v BrowseScreen.
    private val _sourceNameFilter = MutableStateFlow("")
    val sourceNameFilter: StateFlow<String> = _sourceNameFilter.asStateFlow()

    val sources: StateFlow<List<MangaSource>> = combine(
        _allSources, _contentTypeFilter, _languageFilter, _sourceNameFilter,
    ) { all, type, lang, nameQuery ->
        all.filter { src ->
            matchesContentType(src.contentType, type) &&
            (lang == "ALL" || src.language.equals(lang, ignoreCase = true)) &&
            (nameQuery.isBlank() || src.name.contains(nameQuery, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setContentTypeFilter(type: String) { _contentTypeFilter.value = type }
    fun setLanguageFilter(lang: String) { _languageFilter.value = lang }
    fun setSourceNameFilter(query: String) { _sourceNameFilter.value = query }

    // ── Oblíbené zdroje (menu na kartě zdroje) ────────────────────────────────
    val favoriteSourceIds: StateFlow<Set<String>> = settings.favoriteSourceIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun toggleFavoriteSource(sourceId: String) {
        viewModelScope.launch { settings.toggleFavoriteSource(sourceId) }
    }

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
