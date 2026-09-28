package com.haise.jiyu.ui.stats

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextMuted
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import com.haise.jiyu.util.relativeTimeLabel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/** Jeden řádek breakdownu ve statistikách - popisek, počet, barva tečky/segmentu. */
private data class BreakdownItem(val label: String, val count: Int, val color: Color)

@Composable
fun ExtendedStatsScreen(
    onBack: () -> Unit,
    viewModel: ExtendedStatsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    var exportMenuExpanded by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportStatsJson(it) } }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? -> uri?.let { viewModel.exportStatsCsv(it) } }

    val exportErrorTemplate = stringResource(R.string.stats_export_error)
    LaunchedEffect(exportState) {
        when (val s = exportState) {
            is StatsExportState.Success -> { snackbarHostState.showSnackbar(s.message); viewModel.clearExportState() }
            is StatsExportState.Error   -> { snackbarHostState.showSnackbar(exportErrorTemplate.format(s.message)); viewModel.clearExportState() }
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, NightBlue.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
            }
            Text(
                text = stringResource(R.string.stats_title),
                style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Box {
                IconButton(onClick = { exportMenuExpanded = true }) {
                    Icon(TablerIcons.DotsVertical, contentDescription = stringResource(R.string.stats_export_desc), tint = TextSecondary)
                }
                DropdownMenu(expanded = exportMenuExpanded, onDismissRequest = { exportMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats_export_json)) },
                        onClick = {
                            exportMenuExpanded = false
                            jsonExportLauncher.launch("jiyu_stats_${LocalDate.now()}.json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.stats_export_csv)) },
                        onClick = {
                            exportMenuExpanded = false
                            csvExportLauncher.launch("jiyu_stats_${LocalDate.now()}.csv")
                        },
                    )
                }
            }
        }

        val typeItems = breakdownItems(
            stats.typeBreakdown,
            listOf(
                "MANGA"   to (stringResource(R.string.stats_type_manga) to Color(0xFF6B7280)),
                "MANHWA"  to (stringResource(R.string.stats_type_manhwa) to Color(0xFF3B82F6)),
                "MANHUA"  to (stringResource(R.string.stats_type_manhua) to Color(0xFFD4A017)),
                "NOVEL"   to (stringResource(R.string.stats_type_novel) to Color(0xFFEF4444)),
                "OTHER"   to (stringResource(R.string.stats_type_other) to TextMuted),
            ),
        )
        val statusItems = breakdownItems(
            stats.statusBreakdown,
            listOf(
                "READING"      to (stringResource(R.string.stats_status_reading) to Violet),
                "COMPLETED"    to (stringResource(R.string.stats_status_completed) to Color(0xFF34D399)),
                "ON_HOLD"      to (stringResource(R.string.stats_status_on_hold) to Color(0xFFF59E0B)),
                "DROPPED"      to (stringResource(R.string.stats_status_dropped) to Color(0xFFEF4444)),
                "PLAN_TO_READ" to (stringResource(R.string.stats_status_plan_to_read) to Color(0xFF60A5FA)),
                "UNSET"        to (stringResource(R.string.stats_status_unset) to TextMuted),
            ),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Souhrn - dva sloupce s vertikálním skládaným pruhem + legendou,
            // styl souhrnu účtu na ComicK (typ obsahu | stav čtení). ──────────
            if (typeItems.isNotEmpty() || statusItems.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.stats_summary_title), modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassGradient)
                            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                            .padding(14.dp),
                    ) {
                        BreakdownColumn(items = typeItems, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(16.dp))
                        BreakdownColumn(items = statusItems, modifier = Modifier.weight(1f))
                    }
                }
            }

            // ── Celkové součty - prostý seznam "popisek: hodnota" jako na ComicK. ──
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TotalRow(R.string.stats_total_titles, formatCount(stats.totalInLibrary.toLong()))
                    TotalRow(R.string.stats_total_chapters, formatCount(stats.totalChaptersInLibrary.toLong()))
                    TotalRow(R.string.stats_total_read_chapters, formatCount(stats.chaptersRead.toLong()))
                    TotalRow(R.string.stats_total_pages, formatCount(stats.pagesRead))
                    TotalRow(R.string.stats_total_rated, formatCount(stats.ratedCount.toLong()))
                    TotalRow(R.string.stats_reading_time_label, formatTime(stats.readingTimeMs))
                    TotalRow(R.string.stats_streak_label, formatCount(stats.readingStreak.toLong()))
                    if (stats.lastReadAt > 0L) {
                        TotalRow(R.string.stats_last_read, relativeTimeLabel(stats.lastReadAt))
                    }
                    if (stats.memberSince > 0L) {
                        TotalRow(R.string.stats_member_since, SimpleDateFormat("d. MMMM yyyy", Locale.getDefault()).format(Date(stats.memberSince)))
                    }
                }
            }

            // ── Aktivita za 30 dnů (heatmapa) ────────────────────────────────
            item {
                SectionHeader(title = stringResource(R.string.stats_chapters_30days_title), modifier = Modifier.padding(horizontal = 16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(glassGradient)
                        .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                ) {
                    if (stats.dailyCounts.all { it.second == 0 }) {
                        Text(
                            stringResource(R.string.stats_no_reading_30days),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 20.dp),
                        )
                    } else {
                        CalendarHeatmap(data = stats.dailyCounts, modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // ── Oblíbené žánry - centrovaný barevný sloupec jako na ComicK ────
            if (stats.topGenres.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.stats_top_genres_title), modifier = Modifier.padding(horizontal = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassGradient)
                            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                            .padding(vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        stats.topGenres.forEachIndexed { index, (genre, count) ->
                            Text(
                                text = "$genre (${formatCount(count.toLong())})",
                                color = genreColor(index),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data -> Snackbar(snackbarData = data) }
    }
}

/** Mapuje DB klíče na popisky v pevném pořadí + připojí klíče, co nejsou v [order], nakonec. */
@Composable
private fun breakdownItems(
    breakdown: Map<String, Int>,
    order: List<Pair<String, Pair<String, Color>>>,
): List<BreakdownItem> {
    val ordered = order.mapNotNull { (key, labelColor) ->
        val count = breakdown[key] ?: return@mapNotNull null
        if (count <= 0) return@mapNotNull null
        BreakdownItem(labelColor.first, count, labelColor.second)
    }
    val knownKeys = order.map { it.first }.toSet()
    val rest = breakdown.entries
        .filter { it.key !in knownKeys && it.value > 0 }
        .sortedByDescending { it.value }
        .map { BreakdownItem(it.key, it.value, TextMuted) }
    return ordered + rest
}

/** Rozpad do vertikálního skládaného pruhu + legendy (tečka, popisek, počet) - viz ComicK souhrn. */
@Composable
private fun BreakdownColumn(items: List<BreakdownItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Row(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(8.dp)
                .height((items.size * 22).dp)
                .clip(RoundedCornerShape(4.dp)),
        ) {
            items.forEach { item ->
                Box(
                    modifier = Modifier
                        .weight(item.count.toFloat())
                        .fillMaxWidth()
                        .background(item.color),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(16.dp)) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(item.color))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        item.label,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(formatCount(item.count.toLong()), color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun TotalRow(labelRes: Int, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(labelRes), color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = Violet,
        modifier = modifier.padding(bottom = 8.dp),
    )
}

/**
 * Kalendářní mřížka (styl GitHub kontribučního grafu) - 10 sloupců × 3 řádky pro
 * 30 dní, sytost barvy podle počtu přečtených kapitol ten den. Nahrazuje původní
 * sloupcový graf, který bez os/popisků působil prázdně a nepřehledně.
 */
@Composable
private fun CalendarHeatmap(data: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val maxVal = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    val columns = 10
    Column(modifier = modifier) {
        data.chunked(columns).forEach { rowData ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rowData.forEach { (_, count) ->
                    val alpha = if (count == 0) 0.06f else 0.25f + 0.65f * (count.toFloat() / maxVal)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Violet.copy(alpha = alpha)),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(data.firstOrNull()?.first.orEmpty(), color = TextSecondary, fontSize = 9.sp)
            Text(data.lastOrNull()?.first.orEmpty(), color = TextSecondary, fontSize = 9.sp)
        }
    }
}

/** Barvy žánrů - cyklická kategoriální paleta (stejný princip jako ComicK "Favorite Genres",
 * kde má každý žánr vlastní barvu). Indexované pozicí, takže stejný žánr má vždy stejnou barvu. */
private val GENRE_COLORS = listOf(
    Color(0xFF8B5CF6), Color(0xFF84CC16), Color(0xFFFACC15), Color(0xFFEF4444),
    Color(0xFF2DD4BF), Color(0xFF4ADE80), Color(0xFF60A5FA), Color(0xFFF472B6),
    Color(0xFFFB923C), Color(0xFFA78BFA), Color(0xFF34D399), Color(0xFFF87171),
)

private fun genreColor(index: Int): Color = GENRE_COLORS[index % GENRE_COLORS.size]

private fun formatCount(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

private fun formatTime(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0   -> "${h}h ${m}m"
        m > 0   -> "${m}m"
        else    -> "<1m"
    }
}
