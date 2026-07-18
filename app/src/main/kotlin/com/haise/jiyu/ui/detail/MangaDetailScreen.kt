package com.haise.jiyu.ui.detail

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    onBack: () -> Unit = {},
    onOpenChapter: (String) -> Unit,
    onOpenChapterIncognito: (String) -> Unit = {},
    onOpenQr: (mangaId: String, mangaTitle: String) -> Unit = { _, _ -> },
    onOpenDetails: () -> Unit = {},
    viewModel: MangaDetailViewModel = hiltViewModel(),
) {
    val manga            by viewModel.manga.collectAsState()
    val chapters         by viewModel.chapters.collectAsState()
    val continueChapter  by viewModel.continueChapter.collectAsState()
    val firstUnread      by viewModel.firstUnreadChapter.collectAsState()
    val sortAscending    by viewModel.sortAscending.collectAsState()
    val isRefreshing     by viewModel.isRefreshing.collectAsState()
    val errorMessage     by viewModel.errorMessage.collectAsState()
    val readingTimeMs    by viewModel.readingTimeMs.collectAsState()
    val readingStatus    by viewModel.readingStatus.collectAsState()
    val isFavorite       by viewModel.isFavorite.collectAsState()
    val chapterFilter       by viewModel.chapterFilter.collectAsState()
    val statusFilter        by viewModel.statusFilter.collectAsState()
    val selectedScanlator   by viewModel.selectedScanlator.collectAsState()
    val availableScanlators by viewModel.availableScanlators.collectAsState()
    val context             = androidx.compose.ui.platform.LocalContext.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    var showChapterOverflowMenu by remember { mutableStateOf(false) }
    var showDownloadNDialog by remember { mutableStateOf(false) }
    var chapterSearchActive by remember { mutableStateOf(false) }
    var chapterGridView by remember { mutableStateOf(false) }
    var groupByVolume by remember { mutableStateOf(false) }
    var descriptionExpanded by remember { mutableStateOf(false) }
    var showCoverFullscreen by remember { mutableStateOf(false) }
    var statusDropdownExpanded by remember { mutableStateOf(false) }

    // Koordinace pull-to-refresh se stavem ViewModel
    LaunchedEffect(pullToRefreshState.isRefreshing) {
        if (pullToRefreshState.isRefreshing) viewModel.refreshChapters()
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullToRefreshState.endRefresh()
    }

    // Chyba refreshe → snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding)
                .nestedScroll(pullToRefreshState.nestedScrollConnection),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom),
            ) {

                // ── Top bar (zpět, detaily, sdílet, QR, knihovna) ──────────────
                item {
                    val inLibrary = manga?.inLibrary == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onOpenDetails) {
                            Icon(TablerIcons.InfoCircle, contentDescription = stringResource(R.string.detail_open_details), tint = TextSecondary, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = {
                            manga?.let { m -> onOpenQr(m.id, m.title) }
                        }) {
                            Icon(TablerIcons.Qrcode, contentDescription = stringResource(R.string.detail_qr_code), tint = TextSecondary, modifier = Modifier.size(22.dp))
                        }
                        val shareLabel = stringResource(R.string.common_share)
                        IconButton(onClick = {
                            manga?.let { m ->
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${m.title}\n${m.url}")
                                }
                                context.startActivity(Intent.createChooser(i, shareLabel))
                            }
                        }) {
                            Icon(TablerIcons.Share, contentDescription = shareLabel, tint = TextSecondary, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = TablerIcons.Heart,
                                contentDescription = if (isFavorite) stringResource(R.string.detail_remove_favorite) else stringResource(R.string.detail_add_favorite),
                                tint = if (isFavorite) Pink else TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(onClick = { if (inLibrary) viewModel.removeFromLibrary() }) {
                            Icon(
                                imageVector = TablerIcons.Bookmark,
                                contentDescription = if (inLibrary) stringResource(R.string.detail_remove_from_library) else stringResource(R.string.detail_in_library),
                                tint = if (inLibrary) GlowViolet else TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // ── Kompaktní hlavička (cover + info) ───────────────────────────
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Box(
                            modifier = Modifier
                                .width(110.dp)
                                .height(160.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { showCoverFullscreen = true },
                        ) {
                            AsyncImage(
                                model = manga?.coverUrl,
                                contentDescription = manga?.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (showCoverFullscreen) {
                            Dialog(
                                onDismissRequest = { showCoverFullscreen = false },
                                properties = DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                        .clickable { showCoverFullscreen = false },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        model = manga?.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(
                                text = manga?.title ?: "",
                                style = TextStyle(brush = titleGradient, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 22.sp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            manga?.author?.let { author ->
                                Text(text = author, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            }
                            manga?.status?.let { status ->
                                val (label, statusColor) = when (status.lowercase()) {
                                    "ongoing"   -> stringResource(R.string.detail_status_ongoing)   to Color(0xFF4CAF50)
                                    "completed" -> stringResource(R.string.detail_status_completed) to Color(0xFF4FC3F7)
                                    "hiatus"    -> stringResource(R.string.detail_status_hiatus)     to Color(0xFFFFB74D)
                                    "cancelled" -> stringResource(R.string.detail_status_cancelled)  to Color(0xFFEF5350)
                                    else        -> status      to TextSecondary
                                }
                                Box(
                                    modifier = Modifier
                                        .padding(top = 6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(statusColor.copy(alpha = 0.15f))
                                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                ) {
                                    Text(text = label, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            val genres = manga?.genres?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
                            if (genres.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(top = 6.dp),
                                ) {
                                    genres.take(4).forEach { genre ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(Violet.copy(alpha = 0.15f))
                                                .border(1.dp, Violet.copy(alpha = 0.4f), RoundedCornerShape(50))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        ) {
                                            Text(genre, color = Violet, fontSize = 10.sp)
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            val readCount = chapters.count { it.read }
                            val totalCount = chapters.size
                            Text(text = stringResource(R.string.detail_chapter_count, totalCount), color = TextSecondary, fontSize = 11.sp)
                            if (totalCount > 0) {
                                val progress = readCount.toFloat() / totalCount
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = GlowViolet,
                                        trackColor = TextSecondary.copy(alpha = 0.18f),
                                    )
                                    Text(
                                        text = " $readCount/$totalCount",
                                        color = TextSecondary.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            if (readingTimeMs > 0) {
                                Text(
                                    text = stringResource(R.string.detail_reading_time, formatReadingTime(readingTimeMs)),
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }

                // ── Description (collapsible) ───────────────────────────────────
                item {
                    if (!manga?.description.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(glassGradient)
                                .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                .clickable { descriptionExpanded = !descriptionExpanded }
                                .padding(14.dp),
                        ) {
                            Column {
                                Text(
                                    text = manga?.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (descriptionExpanded) stringResource(R.string.detail_show_less) else stringResource(R.string.detail_show_more),
                                    color = GlowCyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                            }
                        }
                    }
                }

                // ── Akční řádek: Pokračovat + čtecí status ──────────────────────
                item {
                    val statusOptions = listOf(
                        "READING"      to stringResource(R.string.detail_reading_status_reading),
                        "COMPLETED"    to stringResource(R.string.detail_status_completed),
                        "ON_HOLD"      to stringResource(R.string.detail_reading_status_on_hold),
                        "DROPPED"      to stringResource(R.string.detail_reading_status_dropped),
                        "PLAN_TO_READ" to stringResource(R.string.detail_reading_status_plan),
                    )
                    val statusColors = mapOf(
                        "READING"      to Color(0xFF4CAF50),
                        "COMPLETED"    to Color(0xFF4FC3F7),
                        "ON_HOLD"      to Color(0xFFFFB74D),
                        "DROPPED"      to Color(0xFFEF5350),
                        "PLAN_TO_READ" to Color(0xFF9C27B0),
                    )
                    val statusLabel = statusOptions.firstOrNull { it.first == readingStatus }?.second ?: stringResource(R.string.detail_status_placeholder)
                    val statusColor = statusColors[readingStatus] ?: TextSecondary

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        continueChapter?.let { chapter ->
                            val hasHistory = manga?.lastReadChapterId != null
                            var showReadMenu by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Brush.linearGradient(listOf(GlowViolet.copy(alpha = 0.85f), GlowCyan.copy(alpha = 0.6f)))),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onOpenChapter(chapter.id) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(TablerIcons.PlayerPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    Column(modifier = Modifier.padding(start = 10.dp)) {
                                        Text(text = if (hasHistory) stringResource(R.string.detail_continue_short) else stringResource(R.string.action_start_reading), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(
                                            text = if (hasHistory) "${chapter.name} · str. ${chapter.lastPageRead + 1}" else chapter.name,
                                            color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Box(modifier = Modifier.width(1.dp).height(36.dp).background(Color.White.copy(alpha = 0.25f)))
                                Box {
                                    IconButton(onClick = { showReadMenu = true }, modifier = Modifier.padding(horizontal = 2.dp)) {
                                        Icon(TablerIcons.ChevronDown, contentDescription = stringResource(R.string.detail_read_options), tint = Color.White)
                                    }
                                    DropdownMenu(expanded = showReadMenu, onDismissRequest = { showReadMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_read_normal)) },
                                            leadingIcon = { Icon(TablerIcons.PlayerPlay, contentDescription = null) },
                                            onClick = { showReadMenu = false; onOpenChapter(chapter.id) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_read_incognito)) },
                                            leadingIcon = { Icon(TablerIcons.EyeOff, contentDescription = null) },
                                            onClick = { showReadMenu = false; onOpenChapterIncognito(chapter.id) },
                                        )
                                    }
                                }
                            }
                        }

                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(statusColor.copy(alpha = 0.15f))
                                    .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                    .clickable { statusDropdownExpanded = true }
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(TablerIcons.ChevronDown, contentDescription = null, tint = statusColor, modifier = Modifier.size(16.dp).padding(start = 2.dp))
                            }
                            DropdownMenu(expanded = statusDropdownExpanded, onDismissRequest = { statusDropdownExpanded = false }) {
                                statusOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.setReadingStatus(if (readingStatus == key) null else key)
                                            statusDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Chapters header se sort + bulk download ───────────────────
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.detail_chapters_header),
                            style = MaterialTheme.typography.labelSmall,
                            color = Violet,
                            letterSpacing = 2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // Chapter search toggle
                        IconButton(
                            onClick = {
                                chapterSearchActive = !chapterSearchActive
                                if (!chapterSearchActive) viewModel.setChapterFilter("")
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(TablerIcons.Search, contentDescription = stringResource(R.string.detail_search_chapter), tint = if (chapterSearchActive) GlowCyan else TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        // Sort toggle
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlowViolet.copy(alpha = if (sortAscending) 0.18f else 0.08f))
                                .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.toggleSort() }) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TablerIcons.ArrowsSort, contentDescription = stringResource(R.string.detail_sort), tint = if (sortAscending) GlowViolet else TextSecondary, modifier = Modifier.size(14.dp))
                            Text(text = if (sortAscending) stringResource(R.string.detail_sort_oldest) else stringResource(R.string.detail_sort_newest), color = if (sortAscending) GlowViolet else TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                        }

                        // Přetečené menu — méně používané akce nad kapitolami
                        Box {
                            IconButton(onClick = { showChapterOverflowMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(TablerIcons.DotsVertical, contentDescription = stringResource(R.string.detail_more_options), tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = showChapterOverflowMenu, onDismissRequest = { showChapterOverflowMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_mark_all_read)) },
                                    leadingIcon = { Icon(TablerIcons.Checks, contentDescription = null) },
                                    onClick = { viewModel.markAllRead(); showChapterOverflowMenu = false },
                                )
                                if (firstUnread != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_jump_first_unread)) },
                                        leadingIcon = { Icon(TablerIcons.PlayerSkipForward, contentDescription = null) },
                                        onClick = { showChapterOverflowMenu = false; firstUnread?.let { onOpenChapter(it.id) } },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (chapterGridView) stringResource(R.string.detail_view_as_list) else stringResource(R.string.detail_view_as_grid)) },
                                    leadingIcon = { Icon(if (chapterGridView) TablerIcons.List else TablerIcons.LayoutGrid, contentDescription = null) },
                                    onClick = { chapterGridView = !chapterGridView; showChapterOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (groupByVolume) stringResource(R.string.detail_ungroup_volume) else stringResource(R.string.detail_group_volume)) },
                                    leadingIcon = { Icon(TablerIcons.List, contentDescription = null, tint = if (groupByVolume) GlowCyan else TextSecondary) },
                                    onClick = { groupByVolume = !groupByVolume; showChapterOverflowMenu = false },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_download_all)) },
                                    leadingIcon = { Icon(TablerIcons.Download, contentDescription = null) },
                                    onClick = { viewModel.downloadAll(); showChapterOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_download_unread)) },
                                    leadingIcon = { Icon(TablerIcons.Download, contentDescription = null) },
                                    onClick = { viewModel.downloadUnread(); showChapterOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_download_first_n)) },
                                    leadingIcon = { Icon(TablerIcons.Download, contentDescription = null) },
                                    onClick = { showDownloadNDialog = true; showChapterOverflowMenu = false },
                                )
                            }
                            if (showDownloadNDialog) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showDownloadNDialog = false },
                                    title = { Text(stringResource(R.string.detail_download_first_n_title)) },
                                    text = {
                                        androidx.compose.foundation.layout.Column {
                                            listOf(5, 10, 25, 50).forEach { n ->
                                                androidx.compose.material3.TextButton(
                                                    onClick = { viewModel.downloadFirstN(n); showDownloadNDialog = false },
                                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                                ) { Text(stringResource(R.string.detail_n_chapters, n)) }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(onClick = { showDownloadNDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                                    },
                                )
                            }
                        }
                    }
                    } // konec Column wrapperu
                }

                // ── Chapter search bar ────────────────────────────────────────
                if (chapterSearchActive) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, GlowCyan.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            BasicTextField(
                                value = chapterFilter,
                                onValueChange = { viewModel.setChapterFilter(it) },
                                singleLine = true,
                                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                        if (chapterFilter.isEmpty()) Text(stringResource(R.string.detail_search_chapter_placeholder), color = TextSecondary, fontSize = 14.sp)
                                        inner()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (chapterFilter.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setChapterFilter("") }, modifier = Modifier.size(24.dp)) {
                                    Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                // ── Status filter chips ───────────────────────────────────────
                item {
                    val filters = listOf(
                        "ALL" to stringResource(R.string.common_all),
                        "UNREAD" to stringResource(R.string.detail_filter_unread),
                        "READ" to stringResource(R.string.detail_filter_read),
                        "DOWNLOADED" to stringResource(R.string.detail_filter_downloaded),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filters) { (key, label) ->
                            val isSelected = statusFilter == key
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isSelected) GlowViolet.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(1.dp, if (isSelected) GlowViolet else TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(50))
                                    .clickable { viewModel.setStatusFilter(key) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(label, color = if (isSelected) GlowViolet else TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // ── Scanlation filter ─────────────────────────────────────────
                if (availableScanlators.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selectedScanlator == null) Cyan.copy(alpha = 0.18f) else Color.Transparent)
                                        .border(1.dp, if (selectedScanlator == null) Cyan else TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(50))
                                        .clickable { viewModel.setScanlator(null) }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text(stringResource(R.string.detail_all_groups), color = if (selectedScanlator == null) Cyan else TextSecondary, fontSize = 10.sp)
                                }
                            }
                            items(availableScanlators) { group ->
                                val isSelected = selectedScanlator == group
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isSelected) Cyan.copy(alpha = 0.18f) else Color.Transparent)
                                        .border(1.dp, if (isSelected) Cyan else TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(50))
                                        .clickable { viewModel.setScanlator(if (isSelected) null else group) }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text(group, color = if (isSelected) Cyan else TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // ── Chapter list / grid (#34) ─────────────────────────────────
                if (chapterGridView) {
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().height(((chapters.size / 4 + 1) * 60).dp.coerceAtMost(400.dp)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(chapters) { chapter ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (chapter.read) GlowCyan.copy(alpha = 0.08f) else GlowViolet.copy(alpha = 0.15f))
                                        .border(1.dp, if (chapter.read) GlowCyan.copy(alpha = 0.2f) else GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { onOpenChapter(chapter.id) }
                                        .padding(6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = chapter.chapterNumber.let { if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString() },
                                        color = if (chapter.read) TextSecondary else TextPrimary,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                } else if (groupByVolume) {
                    val grouped = chapters.groupBy { it.volume ?: "?" }
                        .entries.sortedWith(compareByDescending {
                            val v = it.key; if (v == "?") -1f else v.toFloatOrNull() ?: 0f
                        })
                    grouped.forEach { (volume, chs) ->
                        stickyHeader(key = "vol_$volume") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NightBlue)
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    if (volume == "?") stringResource(R.string.detail_no_volume) else stringResource(R.string.detail_volume_label, volume),
                                    color = Violet,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        items(chs, key = { it.id }) { chapter ->
                            GlassChapterRow(
                                chapter = chapter,
                                onOpen = { onOpenChapter(chapter.id) },
                                onDownload = { viewModel.downloadChapter(chapter) },
                                onMarkReadUpTo = { viewModel.markReadUpTo(chapter.id) },
                                onMarkAllOlderRead = { viewModel.markAllOlderAsRead(chapter) },
                                onMarkAllNewerUnread = { viewModel.markAllNewerAsUnread(chapter) },
                                onToggleRead = { viewModel.markChapterRead(chapter.id, !chapter.read) },
                            )
                        }
                    }
                } else {
                    items(chapters, key = { it.id }) { chapter ->
                        GlassChapterRow(
                            chapter = chapter,
                            onOpen = { onOpenChapter(chapter.id) },
                            onDownload = { viewModel.downloadChapter(chapter) },
                            onMarkReadUpTo = { viewModel.markReadUpTo(chapter.id) },
                            onMarkAllOlderRead = { viewModel.markAllOlderAsRead(chapter) },
                            onMarkAllNewerUnread = { viewModel.markAllNewerAsUnread(chapter) },
                            onToggleRead = { viewModel.markChapterRead(chapter.id, !chapter.read) },
                        )
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }

            if (pullToRefreshState.verticalOffset > 0f) {
                PullToRefreshContainer(state = pullToRefreshState, modifier = Modifier.align(Alignment.TopCenter))
            }
        }
    }
}

// ── Chapter row ───────────────────────────────────────────────────────────────

@Composable
internal fun GlassChapterRow(
    chapter: ChapterEntity,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onMarkReadUpTo: () -> Unit,
    onMarkAllOlderRead: () -> Unit = {},
    onMarkAllNewerUnread: () -> Unit = {},
    onToggleRead: () -> Unit = {},
) {
    val isRead = chapter.read
    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(glassGradient)
            .border(1.dp, if (isRead) GlowCyan.copy(alpha = 0.1f) else GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { showMenu = true },
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).background(if (isRead) GlowCyan.copy(alpha = 0.4f) else GlowViolet, RoundedCornerShape(50)))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(text = chapter.name, color = if (isRead) TextSecondary else TextPrimary, fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!chapter.scanlationGroup.isNullOrBlank()) {
                    Text(text = chapter.scanlationGroup, color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chapter.dateUpload > 0L) {
                    val date = java.text.SimpleDateFormat("d. M. yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(chapter.dateUpload))
                    Text(text = date, color = TextSecondary.copy(alpha = 0.45f), fontSize = 10.sp)
                }
            }
            when (chapter.downloadStatus) {
                DownloadStatus.DOWNLOADED  -> Icon(TablerIcons.CircleCheck, contentDescription = stringResource(R.string.detail_chapter_downloaded), tint = Cyan, modifier = Modifier.size(18.dp))
                DownloadStatus.DOWNLOADING -> Text("↓", color = Violet, fontSize = 16.sp)
                DownloadStatus.QUEUED      -> Text("⏳", fontSize = 14.sp)
                DownloadStatus.ERROR       -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) { Icon(TablerIcons.Download, contentDescription = stringResource(R.string.common_retry), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                else                       -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) { Icon(TablerIcons.Download, contentDescription = stringResource(R.string.common_download), tint = TextSecondary, modifier = Modifier.size(18.dp)) }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_mark_read_up_to)) },
                onClick = { onMarkReadUpTo(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_mark_all_older_read)) },
                onClick = { onMarkAllOlderRead(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.detail_mark_all_newer_unread)) },
                onClick = { onMarkAllNewerUnread(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (isRead) stringResource(R.string.detail_mark_as_unread) else stringResource(R.string.detail_mark_as_read)) },
                onClick = { onToggleRead(); showMenu = false },
            )
        }
    }
}

internal fun formatReadingTime(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else  -> "<1m"
    }
}
