package com.haise.jiyu.ui.stats

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import java.time.LocalDate

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

        LazyColumn(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(icon = TablerIcons.Book, label = stringResource(R.string.stats_chapters_label), value = "${stats.chaptersRead}", modifier = Modifier.weight(1f))
                        StatCard(icon = TablerIcons.FileText, label = stringResource(R.string.stats_pages_label), value = "${stats.pagesRead}", modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatCard(icon = TablerIcons.Clock, label = stringResource(R.string.stats_reading_time_label), value = formatTime(stats.readingTimeMs), modifier = Modifier.weight(1f))
                        StatCard(icon = TablerIcons.Flame, label = stringResource(R.string.stats_streak_label), value = "${stats.readingStreak}", modifier = Modifier.weight(1f))
                    }
                }
            }

            item {
                SectionHeader(title = stringResource(R.string.stats_chapters_30days_title), modifier = Modifier.padding(horizontal = 16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(glassGradient)
                        .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
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

            if (stats.topGenres.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.stats_top_genres_title), modifier = Modifier.padding(horizontal = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassGradient)
                            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val maxGenre = stats.topGenres.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                        stats.topGenres.forEach { (genre, count) ->
                            HorizontalBar(
                                icon = TablerIcons.Tag,
                                label = genre,
                                value = count,
                                fraction = count.toFloat() / maxGenre,
                                color = Brush.horizontalGradient(listOf(GlowViolet, GlowCyan)),
                            )
                        }
                    }
                }
            }

            if (stats.topAuthors.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.stats_top_authors_title), modifier = Modifier.padding(horizontal = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassGradient)
                            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val maxAuthor = stats.topAuthors.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
                        stats.topAuthors.forEach { (author, count) ->
                            HorizontalBar(
                                icon = TablerIcons.User,
                                label = author,
                                value = count,
                                fraction = count.toFloat() / maxAuthor,
                                color = Brush.horizontalGradient(listOf(GlowCyan, GlowViolet)),
                            )
                        }
                    }
                }
            }

            if (stats.totalInLibrary > 0 && stats.statusBreakdown.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(R.string.stats_reading_status_title), modifier = Modifier.padding(horizontal = 16.dp))
                    val statusLabels = mapOf(
                        "READING"      to stringResource(R.string.stats_status_reading),
                        "COMPLETED"    to stringResource(R.string.stats_status_completed),
                        "ON_HOLD"      to stringResource(R.string.stats_status_on_hold),
                        "DROPPED"      to stringResource(R.string.stats_status_dropped),
                        "PLAN_TO_READ" to stringResource(R.string.stats_status_plan_to_read),
                        "UNSET"        to stringResource(R.string.stats_status_unset),
                    )
                    val statusColors = mapOf(
                        "READING"      to GlowCyan,
                        "COMPLETED"    to Color(0xFF4FC3F7),
                        "ON_HOLD"      to Color(0xFFFFB74D),
                        "DROPPED"      to Color(0xFFEF5350),
                        "PLAN_TO_READ" to GlowViolet,
                        "UNSET"        to TextSecondary,
                    )
                    val segments = stats.statusBreakdown.entries
                        .sortedByDescending { it.value }
                        .map { (key, count) -> Triple("${statusLabels[key] ?: key} ($count)", count, statusColors[key] ?: TextSecondary) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(glassGradient)
                            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                    ) {
                        DonutChart(segments = segments)
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

@Composable
private fun StatCard(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = GlowCyan, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(6.dp))
        Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Text(label, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
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
                            .background(GlowViolet.copy(alpha = alpha)),
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

/** Prstencový graf pro poměr stavů čtení - segmenty jako (popisek, hodnota, barva). */
@Composable
private fun DonutChart(segments: List<Triple<String, Int, Color>>, modifier: Modifier = Modifier) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(modifier = Modifier.size(64.dp)) {
            val strokeWidth = size.minDimension * 0.28f
            var startAngle = -90f
            segments.forEach { (_, value, color) ->
                val sweep = 360f * value / total
                if (value > 0) {
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = sweep,
                        useCenter = false,
                        topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - strokeWidth, size.height - strokeWidth),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                    )
                }
                startAngle += sweep
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            segments.filter { it.second > 0 }.forEach { (label, _, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun HorizontalBar(icon: ImageVector, label: String, value: Int, fraction: Float, color: Brush) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Icon(icon, contentDescription = null, tint = Violet, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, color = TextPrimary, fontSize = 13.sp)
            }
            Text("$value", color = TextSecondary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(50)),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(GlowViolet.copy(alpha = 0.1f)))
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(color),
            )
        }
    }
}

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
