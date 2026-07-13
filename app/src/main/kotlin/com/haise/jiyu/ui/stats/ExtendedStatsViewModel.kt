package com.haise.jiyu.ui.stats

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.ReadHistoryDao
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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

sealed interface StatsExportState {
    data object Idle : StatsExportState
    data class Success(val message: String) : StatsExportState
    data class Error(val message: String) : StatsExportState
}

@HiltViewModel
class ExtendedStatsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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

    // ── Export statistik ──────────────────────────────────────────────────────
    private val _exportState = MutableStateFlow<StatsExportState>(StatsExportState.Idle)
    val exportState: StateFlow<StatsExportState> = _exportState.asStateFlow()

    fun clearExportState() { _exportState.value = StatsExportState.Idle }

    fun exportStatsJson(uri: Uri) = viewModelScope.launch {
        try {
            val s = _stats.value
            val root = JSONObject().apply {
                put("exportedAt", java.time.Instant.now().toString())
                put("chaptersRead", s.chaptersRead)
                put("pagesRead", s.pagesRead)
                put("readingTimeMs", s.readingTimeMs)
                put("readingStreak", s.readingStreak)
                put("totalInLibrary", s.totalInLibrary)
                put("dailyCounts", JSONArray().also { arr ->
                    s.dailyCounts.forEach { (day, count) -> arr.put(JSONObject().put("day", day).put("count", count)) }
                })
                put("topGenres", JSONArray().also { arr ->
                    s.topGenres.forEach { (genre, count) -> arr.put(JSONObject().put("genre", genre).put("count", count)) }
                })
                put("topAuthors", JSONArray().also { arr ->
                    s.topAuthors.forEach { (author, count) -> arr.put(JSONObject().put("author", author).put("count", count)) }
                })
                put("statusBreakdown", JSONObject().also { obj ->
                    s.statusBreakdown.forEach { (status, count) -> obj.put(status, count) }
                })
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
                ?: error(context.getString(R.string.stats_export_open_file_error))
            _exportState.value = StatsExportState.Success(context.getString(R.string.stats_export_success_json))
        } catch (e: Exception) {
            _exportState.value = StatsExportState.Error(e.message ?: context.getString(R.string.stats_export_generic_error))
        }
    }

    fun exportStatsCsv(uri: Uri) = viewModelScope.launch {
        try {
            val s = _stats.value
            val sb = StringBuilder()
            sb.append("metric,value\n")
            sb.append("chapters_read,${s.chaptersRead}\n")
            sb.append("pages_read,${s.pagesRead}\n")
            sb.append("reading_time_ms,${s.readingTimeMs}\n")
            sb.append("reading_streak_days,${s.readingStreak}\n")
            sb.append("total_in_library,${s.totalInLibrary}\n")
            sb.append("\nday,chapters_read\n")
            s.dailyCounts.forEach { (day, count) -> sb.append("$day,$count\n") }
            sb.append("\ngenre,manga_count\n")
            s.topGenres.forEach { (genre, count) -> sb.append("\"${genre.replace("\"", "\"\"")}\",$count\n") }
            sb.append("\nauthor,manga_count\n")
            s.topAuthors.forEach { (author, count) -> sb.append("\"${author.replace("\"", "\"\"")}\",$count\n") }
            sb.append("\nreading_status,count\n")
            s.statusBreakdown.forEach { (status, count) -> sb.append("$status,$count\n") }

            context.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                ?: error(context.getString(R.string.stats_export_open_file_error))
            _exportState.value = StatsExportState.Success(context.getString(R.string.stats_export_success_csv))
        } catch (e: Exception) {
            _exportState.value = StatsExportState.Error(e.message ?: context.getString(R.string.stats_export_generic_error))
        }
    }
}
