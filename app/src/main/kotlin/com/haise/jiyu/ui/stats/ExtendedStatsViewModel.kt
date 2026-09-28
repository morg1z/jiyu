package com.haise.jiyu.ui.stats

import com.haise.jiyu.data.repository.HistoryRepository
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.haise.jiyu.R
import com.haise.jiyu.data.repository.MangaRepository
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.util.report
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
    val totalChaptersInLibrary: Int = 0,
    val pagesRead: Long = 0L,
    val readingTimeMs: Long = 0L,
    val readingStreak: Int = 0,
    val ratedCount: Int = 0,
    /** MAX(manga.lastReadAt) - 0 = ještě se nic nečetlo. */
    val lastReadAt: Long = 0L,
    /** MIN(manga.addedAt) - obdoba "member since" ze souhrnu účtu; 0 = neznámé. */
    val memberSince: Long = 0L,
    val dailyCounts: List<Pair<String, Int>> = emptyList(),
    val topGenres: List<Pair<String, Int>> = emptyList(),
    val statusBreakdown: Map<String, Int> = emptyMap(),
    /** Rozpad knihovny podle [com.haise.jiyu.data.db.entity.MangaEntity.contentType]
     * (MANGA/MANHWA/MANHUA/NOVEL/Ostatní) - viz souhrn účtu na ComicK. */
    val typeBreakdown: Map<String, Int> = emptyMap(),
    val totalInLibrary: Int = 0,
)

sealed interface StatsExportState {
    data object Idle : StatsExportState
    data class Success(val message: String) : StatsExportState
    data class Error(val message: String) : StatsExportState
}

/** Typy obsahu, které mají ve Statistikách vlastní řádek - cokoliv jiného (COMIC, neznámé)
 * spadá pod "OTHER" (viz ComicK souhrn "Others"). */
internal val KNOWN_TYPES = setOf("MANGA", "MANHWA", "MANHUA", "NOVEL")

@HiltViewModel
class ExtendedStatsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val historyRepository: HistoryRepository,
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
        val dailyMap = historyRepository.dailyReadCounts(since).associate { it.day to it.count }
        val allDays = (0..29).map { offset ->
            cal.time = today
            cal.add(Calendar.DAY_OF_YEAR, -29 + offset)
            val key = dbFmt.format(cal.time)
            val label = key.substring(5).replace("-", ".")
            label to (dailyMap[key] ?: 0)
        }

        val genreMap = mutableMapOf<String, Int>()
        repository.getAllLibraryGenres().forEach { raw ->
            raw.split(",").forEach { g ->
                val genre = g.trim()
                if (genre.isNotBlank()) genreMap[genre] = (genreMap[genre] ?: 0) + 1
            }
        }
        val topGenres = genreMap.entries.sortedByDescending { it.value }.take(12).map { it.key to it.value }

        val library = repository.getAllLibraryManga()
        val statusBreakdown = library
            .groupBy { it.readingStatus ?: "UNSET" }
            .mapValues { it.value.size }
        val typeBreakdown = library
            .groupBy { it.contentType.takeIf { t -> t in KNOWN_TYPES } ?: "OTHER" }
            .mapValues { it.value.size }

        _stats.value = ExtendedStats(
            chaptersRead = repository.countLibraryReadChaptersDistinct(),
            totalChaptersInLibrary = repository.countLibraryChaptersDistinct(),
            pagesRead = settings.totalPagesRead.first(),
            readingTimeMs = settings.totalReadingTimeMs.first(),
            readingStreak = settings.readingStreak.first(),
            ratedCount = repository.countLibraryRated(),
            lastReadAt = repository.latestLibraryReadAt() ?: 0L,
            memberSince = repository.earliestLibraryAddedAt() ?: 0L,
            dailyCounts = allDays,
            topGenres = topGenres,
            statusBreakdown = statusBreakdown,
            typeBreakdown = typeBreakdown,
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
                put("totalChaptersInLibrary", s.totalChaptersInLibrary)
                put("ratedCount", s.ratedCount)
                put("lastReadAt", s.lastReadAt)
                put("memberSince", s.memberSince)
                put("topGenres", JSONArray().also { arr ->
                    s.topGenres.forEach { (genre, count) -> arr.put(JSONObject().put("genre", genre).put("count", count)) }
                })
                put("statusBreakdown", JSONObject().also { obj ->
                    s.statusBreakdown.forEach { (status, count) -> obj.put(status, count) }
                })
                put("typeBreakdown", JSONObject().also { obj ->
                    s.typeBreakdown.forEach { (type, count) -> obj.put(type, count) }
                })
            }
            context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) }
                ?: error(context.getString(R.string.stats_export_open_file_error))
            _exportState.value = StatsExportState.Success(context.getString(R.string.stats_export_success_json))
        } catch (e: Exception) {
            // Vzdy prijatelna hlaska, i kdyz e.message je treba raw SecurityException ze
            // Storage Access Frameworku - surova vyjimka do UI nepatri, e.report() ji
            // zaznamena pro pripadne dalsi zkoumani.
            e.report("stats:export:json")
            _exportState.value = StatsExportState.Error(context.getString(R.string.stats_export_generic_error))
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
            sb.append("total_chapters_in_library,${s.totalChaptersInLibrary}\n")
            sb.append("rated_count,${s.ratedCount}\n")
            sb.append("\nday,chapters_read\n")
            s.dailyCounts.forEach { (day, count) -> sb.append("$day,$count\n") }
            sb.append("\ngenre,manga_count\n")
            s.topGenres.forEach { (genre, count) -> sb.append("\"${genre.replace("\"", "\"\"")}\",$count\n") }
            sb.append("\nreading_status,count\n")
            s.statusBreakdown.forEach { (status, count) -> sb.append("$status,$count\n") }
            sb.append("\ncontent_type,count\n")
            s.typeBreakdown.forEach { (type, count) -> sb.append("$type,$count\n") }

            context.contentResolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray()) }
                ?: error(context.getString(R.string.stats_export_open_file_error))
            _exportState.value = StatsExportState.Success(context.getString(R.string.stats_export_success_csv))
        } catch (e: Exception) {
            e.report("stats:export:csv")
            _exportState.value = StatsExportState.Error(context.getString(R.string.stats_export_generic_error))
        }
    }
}
