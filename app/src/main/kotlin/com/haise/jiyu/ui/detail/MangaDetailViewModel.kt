package com.haise.jiyu.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.anilist.AniListRepository
import com.haise.jiyu.data.db.MangaNoteDao
import com.haise.jiyu.data.db.MangaTagDao
import com.haise.jiyu.groq.GroqRepository
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.data.db.entity.MangaNoteEntity
import com.haise.jiyu.data.db.entity.MangaTagEntity
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.download.DownloadQueue
import com.haise.jiyu.source.SManga
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
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MangaDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MangaRepository,
    private val downloadQueue: DownloadQueue,
    private val networkMonitor: NetworkMonitor,
    private val mangaNoteDao: MangaNoteDao,
    private val mangaTagDao: MangaTagDao,
    private val groqRepository: GroqRepository,
    private val aniListRepository: AniListRepository,
) : ViewModel() {

    private val mangaId: String = checkNotNull(savedStateHandle["mangaId"])

    init {
        // Auto-retry chapter load when connectivity is restored after an error
        viewModelScope.launch {
            networkMonitor.networkState.drop(1).collect { online ->
                if (online && _errorMessage.value != null && !_isRefreshing.value) refreshChapters()
            }
        }
    }

    val manga: StateFlow<MangaEntity?> = repository.observeMangaById(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val relatedManga: StateFlow<List<SManga>> = flow {
        emit(emptyList())
        try { emit(repository.getRelatedManga(mangaId)) } catch (_: Exception) {}
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Řazení + filtrování kapitol ───────────────────────────────────────────
    private val _sortAscending = MutableStateFlow(false)
    val sortAscending: StateFlow<Boolean> = _sortAscending.asStateFlow()

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
    ) { list, asc, textFilter, statusFilter, scanlator ->
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
        if (asc) result.sortedBy { it.chapterNumber }
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

    // ── Poznámky (#27) ────────────────────────────────────────────────────────
    val mangaNote: StateFlow<MangaNoteEntity?> = mangaNoteDao.observeForManga(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Tagy (#26) ────────────────────────────────────────────────────────────
    val mangaTags: StateFlow<List<MangaTagEntity>> = mangaTagDao.observeForManga(mangaId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Hodnocení (#41) ───────────────────────────────────────────────────────
    val userRating: StateFlow<Int?> = manga.map { it?.userRating }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── AI doporučení (#37) ───────────────────────────────────────────────────
    private val _aiInsight = MutableStateFlow<String?>(null)
    val aiInsight: StateFlow<String?> = _aiInsight.asStateFlow()

    private val _aiInsightLoading = MutableStateFlow(false)
    val aiInsightLoading: StateFlow<Boolean> = _aiInsightLoading.asStateFlow()

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

    fun toggleSort() { _sortAscending.value = !_sortAscending.value }

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
                chapter.localPath?.let { path ->
                    try { java.io.File(path).deleteRecursively() } catch (_: Exception) {}
                }
            }
            repository.removeFromLibrary(mangaId)
        }
    }

    fun refreshChapters() {
        val current = manga.value ?: return
        if (!networkMonitor.isOnline) {
            _errorMessage.value = "Nejsi připojen k internetu"
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            _errorMessage.value = null
            try {
                val sManga = SManga(current.sourceId, current.url, current.title, current.coverUrl, current.description, current.status)
                repository.refreshChapters(mangaId, sManga)
            } catch (e: Exception) {
                _errorMessage.value = "Aktualizace selhala: ${e.toFriendlyMessage()}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun downloadChapter(chapter: ChapterEntity) {
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
            try { aniListRepository.updateScore(mangaId, m.title, rating * 20) } catch (_: Exception) {}
        }
    }

    fun clearRating() {
        viewModelScope.launch { repository.setRating(mangaId, null) }
    }

    // ── AI doporučení (#37) ───────────────────────────────────────────────────
    fun loadAiInsight() {
        if (_aiInsight.value != null || _aiInsightLoading.value) return
        val m = manga.value ?: return
        viewModelScope.launch {
            _aiInsightLoading.value = true
            _aiInsight.value = groqRepository.getMangaInsight(
                m.title,
                m.description,
                m.genres.split(",").map { it.trim() }.filter { it.isNotBlank() },
            )
            _aiInsightLoading.value = false
        }
    }

    // ── AI shrnutí kapitoly (#36) ─────────────────────────────────────────────
    fun getChapterSummary(chapter: ChapterEntity, onResult: (String?) -> Unit) {
        val m = manga.value ?: return
        val prevChapter = chapters.value
            .filter { it.chapterNumber < chapter.chapterNumber }
            .maxByOrNull { it.chapterNumber }
        viewModelScope.launch {
            val summary = groqRepository.getChapterSummary(m.title, chapter.name, prevChapter?.name)
            onResult(summary)
        }
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
