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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
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
    val stats by viewModel.stats.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StatCard(label = stringResource(R.string.stats_chapters_label), value = "${stats.chaptersRead}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(R.string.stats_pages_label), value = "${stats.pagesRead}", modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(R.string.stats_reading_time_label), value = formatTime(stats.readingTimeMs), modifier = Modifier.weight(1f))
                    StatCard(label = stringResource(R.string.stats_streak_label), value = "${stats.readingStreak}🔥", modifier = Modifier.weight(1f))
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
                        Column {
                            BarChart(
                                data = stats.dailyCounts,
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                val labels = stats.dailyCounts
                                val step = (labels.size / 4).coerceAtLeast(1)
                                listOf(0, step, step * 2, step * 3, labels.lastIndex.coerceAtLeast(0))
                                    .distinct()
                                    .forEach { i ->
                                        Text(
                                            text = labels.getOrNull(i)?.first ?: "",
                                            color = TextSecondary,
                                            fontSize = 9.sp,
                                        )
                                    }
                            }
                        }
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
                        "COMPLETED"    to androidx.compose.ui.graphics.Color(0xFF4FC3F7),
                        "ON_HOLD"      to androidx.compose.ui.graphics.Color(0xFFFFB74D),
                        "DROPPED"      to androidx.compose.ui.graphics.Color(0xFFEF5350),
                        "PLAN_TO_READ" to GlowViolet,
                        "UNSET"        to TextSecondary,
                    )
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
                        val total = stats.totalInLibrary.coerceAtLeast(1)
                        stats.statusBreakdown.entries
                            .sortedByDescending { it.value }
                            .forEach { (key, count) ->
                                val color = statusColors[key] ?: TextSecondary
                                HorizontalBar(
                                    label = "${statusLabels[key] ?: key} ($count)",
                                    value = count,
                                    fraction = count.toFloat() / total,
                                    color = Brush.horizontalGradient(listOf(color, color.copy(alpha = 0.6f))),
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

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
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

@Composable
private fun BarChart(data: List<Pair<String, Int>>, modifier: Modifier = Modifier) {
    val maxVal = data.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Canvas(modifier = modifier) {
        val count = data.size.coerceAtLeast(1)
        val barWidth = size.width / count
        val gap = 2.dp.toPx()
        val chartHeight = size.height

        data.forEachIndexed { i, (_, dayCount) ->
            if (dayCount <= 0) return@forEachIndexed
            val barH = (dayCount.toFloat() / maxVal) * chartHeight
            val left = i * barWidth + gap
            val top = chartHeight - barH
            val w = (barWidth - gap * 2).coerceAtLeast(1f)

            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(GlowCyan.copy(alpha = 0.9f), GlowViolet.copy(alpha = 0.8f)),
                    startY = top,
                    endY = chartHeight,
                ),
                topLeft = Offset(left, top),
                size = Size(w, barH),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }
    }
}

@Composable
private fun HorizontalBar(label: String, value: Int, fraction: Float, color: Brush) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
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
