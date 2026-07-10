package com.haise.jiyu.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

data class ExtendedStats(
    val chaptersRead: Int = 0,
    val pagesRead: Long = 0L,
    val readingTimeMs: Long = 0L,
    val readingStreak: Int = 0,
    val dailyCounts: List<Pair<String, Int>> = emptyList(),
    val topGenres: List<Pair<String, Int>> = emptyList(),
    val topAuthors: List<Pair<String, Int>> = emptyList(),
    val statusBreakdown: Map<String, Int> = emptyMap(),
    val totalInLibrary: Int = 0,
)

@HiltViewModel
class ExtendedStatsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val historyDao: ReadHistoryDao,
    private val mangaDao: MangaDao,
    private val repository: MangaRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(ExtendedStats())
    val stats: StateFlow<ExtendedStats> = _stats.asStateFlow()

    init { loadStats() }

    fun loadStats() = viewModelScope.launch {
        val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000

        // Build full 30-day list filling gaps with zeros
        val cal = Calendar.getInstance()
        val dbFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = cal.time
        val dailyMap = historyDao.getDailyReadCounts(since).associate { it.day to it.count }
        val allDays = (0..29).map { offset ->
            cal.time = today
            cal.add(Calendar.DAY_OF_YEAR, -29 + offset)
            val key = dbFmt.format(cal.time)
            val label = key.substring(5).replace("-", ".")
            label to (dailyMap[key] ?: 0)
        }

        val genreMap = mutableMapOf<String, Int>()
        mangaDao.getAllLibraryGenres().forEach { raw ->
            raw.split(",").forEach { g ->
                val genre = g.trim()
                if (genre.isNotBlank()) genreMap[genre] = (genreMap[genre] ?: 0) + 1
            }
        }
        val topGenres = genreMap.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }

        val authorMap = mutableMapOf<String, Int>()
        mangaDao.getAllLibraryAuthors().forEach { a ->
            val author = (a ?: return@forEach).trim()
            if (author.isNotBlank()) authorMap[author] = (authorMap[author] ?: 0) + 1
        }
        val topAuthors = authorMap.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value }

        val library = mangaDao.getAllLibrary()
        val statusBreakdown = library
            .groupBy { it.readingStatus ?: "UNSET" }
            .mapValues { it.value.size }

        _stats.value = ExtendedStats(
            chaptersRead = repository.observeReadChaptersCount().first(),
            pagesRead = settings.totalPagesRead.first(),
            readingTimeMs = settings.totalReadingTimeMs.first(),
            readingStreak = settings.readingStreak.first(),
            dailyCounts = allDays,
            topGenres = topGenres,
            topAuthors = topAuthors,
            statusBreakdown = statusBreakdown,
            totalInLibrary = library.size,
        )
    }
}
