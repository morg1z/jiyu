package com.haise.jiyu.ui.library

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.settings.ReadingStats
import com.haise.jiyu.ui.settings.SettingsViewModel
import com.haise.jiyu.ui.theme.CyanLight
import com.haise.jiyu.ui.theme.Danger
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.VioletLight
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.violetGlow

/** Celá filtrovaná knihovna (dřív hlavní Knihovna) - vlastní tab, dashboard Knihovna teď žije v LibraryScreen.kt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChapter: (String) -> Unit = {},
    onOpenStats: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val library            by viewModel.library.collectAsState()
    val categories         by viewModel.categories.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val contentTypeFilter      by viewModel.contentTypeFilter.collectAsState()
    val readingStatusFilter    by viewModel.readingStatusFilter.collectAsState()
    val searchQuery        by viewModel.searchQuery.collectAsState()
    val sortOption         by viewModel.sortOption.collectAsState()
    val sortAscending      by viewModel.sortAscending.collectAsState()
    val isRefreshing       by viewModel.isRefreshing.collectAsState()
    val refreshError       by viewModel.refreshError.collectAsState()
    val readingStats       by settingsViewModel.readingStats.collectAsState()
    val selectionMode      by viewModel.selectionMode.collectAsState()
    val selectedIds        by viewModel.selectedIds.collectAsState()

    val localImportState   by viewModel.localImportState.collectAsState()
    val gridMode           by viewModel.gridMode.collectAsState()
    val gridColumns        by viewModel.gridColumns.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importLocalFile(it) } }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope    = rememberCoroutineScope()
    val errorPrefix = stringResource(R.string.mylist_error_prefix)

    LaunchedEffect(refreshError) {
        val msg = refreshError ?: return@LaunchedEffect
        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
        viewModel.clearRefreshError()
    }
    LaunchedEffect(localImportState) {
        when (val s = localImportState) {
            is LocalImportState.Done  -> { onOpenChapter(s.chapterId); viewModel.clearLocalImportState() }
            is LocalImportState.Error -> { snackbarHostState.showSnackbar(errorPrefix + s.message); viewModel.clearLocalImportState() }
            else -> {}
        }
    }
    val unreadCounts       by viewModel.unreadCounts.collectAsState()
    val totalCounts        by viewModel.totalCounts.collectAsState()
    val downloadedPerManga by viewModel.downloadedPerManga.collectAsState()

    var showManageDialog          by remember { mutableStateOf(false) }
    var showStatsDialog           by remember { mutableStateOf(false) }
    var headerMenuExpanded        by remember { mutableStateOf(false) }
    var contextMenuManga          by remember { mutableStateOf<MangaEntity?>(null) }
    var showCategoryAssignDialog  by remember { mutableStateOf(false) }
    var showBulkCategoryDialog    by remember { mutableStateOf(false) }
    var showMarkAllReadDialog     by remember { mutableStateOf(false) }
    var showFilterSheet           by remember { mutableStateOf(false) }
    var searchExpanded            by remember { mutableStateOf(false) }

    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(pullToRefreshState.isRefreshing) {
        if (pullToRefreshState.isRefreshing) viewModel.refreshLibrary()
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullToRefreshState.endRefresh()
    }

    // Exit selection mode on back press
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(screenGradient)) {

        // ── Header ───────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, DeepSpace.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(top = 10.dp, bottom = 8.dp),
        ) {
            if (selectionMode) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { viewModel.clearSelection() }) {
                        Icon(TablerIcons.X, contentDescription = stringResource(R.string.mylist_clear_selection), tint = TextSecondary)
                    }
                    Text(
                        text = stringResource(R.string.mylist_selected_count, selectedIds.size),
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.selectAll() }) {
                        Icon(TablerIcons.Checks, contentDescription = null, tint = GlowViolet, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.common_all), color = GlowViolet, fontSize = 14.sp)
                    }
                }
            } else {
                // Title row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(text = stringResource(R.string.mylist_title), color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                        Text(text = stringResource(R.string.mylist_title_count, library.size), style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1)
                    }
                    IconButton(onClick = { searchExpanded = !searchExpanded }) {
                        Icon(TablerIcons.Search, contentDescription = stringResource(R.string.mylist_search), tint = if (searchExpanded) GlowViolet else TextSecondary)
                    }
                    Box {
                        IconButton(onClick = { headerMenuExpanded = true }) {
                            Icon(TablerIcons.DotsVertical, contentDescription = stringResource(R.string.detail_more_options), tint = TextSecondary)
                        }
                        DropdownMenu(expanded = headerMenuExpanded, onDismissRequest = { headerMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mylist_manage_categories)) },
                                leadingIcon = { Icon(TablerIcons.Folder, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    showManageDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mylist_open_cbz)) },
                                leadingIcon = { Icon(TablerIcons.Folder, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    filePickerLauncher.launch(arrayOf("application/zip", "application/x-cbz", "application/octet-stream", "*/*"))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mylist_mark_library_read)) },
                                leadingIcon = { Icon(TablerIcons.Checks, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    showMarkAllReadDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.stats_title)) },
                                leadingIcon = { Icon(TablerIcons.Book, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    showStatsDialog = true
                                },
                            )
                        }
                    }
                }

                // Search bar - jen když je rozbalený (ikonka lupy v title row)
                if (searchExpanded) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                            .height(42.dp)
                            .clip(RoundedCornerShape(50.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, if (searchQuery.isNotEmpty()) GlowViolet.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50.dp))
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(TablerIcons.Search, contentDescription = null, tint = if (searchQuery.isNotEmpty()) GlowViolet else TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(17.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.setSearchQuery(it) },
                            singleLine = true,
                            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = {}),
                            decorationBox = { inner ->
                                Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    if (searchQuery.isEmpty()) Text(stringResource(R.string.library_search_placeholder), color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }, modifier = Modifier.size(28.dp)) {
                                Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(15.dp))
                            }
                        }
                    }
                }
            }
        }

        // ── Stav čtení filter ────────────────────────────────────────────────
        if (!selectionMode) {
            val readingStatuses = listOf(
                "ALL" to stringResource(R.string.common_all),
                "READING" to stringResource(R.string.detail_reading_status_reading),
                "COMPLETED" to stringResource(R.string.detail_status_completed),
                "ON_HOLD" to stringResource(R.string.mylist_filter_on_hold),
                "PLAN_TO_READ" to stringResource(R.string.mylist_filter_plan_to_read),
                "DROPPED" to stringResource(R.string.detail_reading_status_dropped),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(readingStatuses) { (key, label) ->
                    ReadingStatusChip(label = label, selected = readingStatusFilter == key, onClick = { viewModel.setReadingStatusFilter(key) })
                }
            }
        }

        // ── Filtrovat a řadit bar ────────────────────────────────────────────
        if (!selectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NightBlue)
                    .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
                    .clickable { showFilterSheet = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TablerIcons.Filter, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.mylist_filter_and_sort), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.05f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!gridMode) GlowViolet else Color.Transparent)
                            .clickable { if (gridMode) viewModel.toggleGridMode() }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TablerIcons.List, contentDescription = stringResource(R.string.mylist_switch_to_list), tint = if (!gridMode) Color.White else TextSecondary, modifier = Modifier.size(15.dp))
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (gridMode) GlowViolet else Color.Transparent)
                            .clickable { if (!gridMode) viewModel.toggleGridMode() }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TablerIcons.LayoutGrid, contentDescription = stringResource(R.string.mylist_switch_to_grid), tint = if (gridMode) Color.White else TextSecondary, modifier = Modifier.size(15.dp))
                    }
                }
            }
        }

        // ── Grid / empty + pull-to-refresh ───────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(modifier = Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
            if (library.isEmpty()) {
                LibraryEmptyState(
                    hasSearch = searchQuery.isNotEmpty(),
                    onOpenBrowse = onOpenBrowse,
                )
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(library, key = { it.id }) { manga ->
                        val isSelected = manga.id in selectedIds
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        Box {
                            AnimeMangaCard(
                                manga = manga,
                                isSelected = isSelected,
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelection(manga.id)
                                    else onOpenManga(manga.id)
                                },
                                onLongPress = {
                                    if (selectionMode) viewModel.selectAll()
                                    else viewModel.enterSelectionMode(manga.id)
                                },
                                unreadCount = unreadCounts[manga.id] ?: 0,
                                totalCount = totalCounts[manga.id] ?: 0,
                                hasDownloads = (downloadedPerManga[manga.id] ?: 0) > 0,
                            )
                            if (!selectionMode) {
                                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                                    manga.lastReadChapterId?.let { chapterId ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_continue_reading)) },
                                            onClick = { onOpenChapter(chapterId); dropdownExpanded = false },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_download_all)) },
                                        onClick = { viewModel.downloadAllChapters(manga.id); dropdownExpanded = false },
                                    )
                                    if (categories.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.mylist_add_to_category)) },
                                            onClick = { contextMenuManga = manga; showCategoryAssignDialog = true; dropdownExpanded = false },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.mylist_remove_from_library), color = MaterialTheme.colorScheme.error) },
                                        onClick = { viewModel.removeFromLibrary(manga.id); dropdownExpanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp + navBottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(library, key = { it.id }) { manga ->
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        Box {
                            LibraryListRow(
                                manga = manga,
                                isSelected = manga.id in selectedIds,
                                onClick = {
                                    if (selectionMode) viewModel.toggleSelection(manga.id)
                                    else onOpenManga(manga.id)
                                },
                                onLongPress = {
                                    if (selectionMode) viewModel.selectAll()
                                    else viewModel.enterSelectionMode(manga.id)
                                },
                                onMoreClick = { dropdownExpanded = true },
                                unreadCount = unreadCounts[manga.id] ?: 0,
                                totalCount = totalCounts[manga.id] ?: 0,
                                hasDownloads = (downloadedPerManga[manga.id] ?: 0) > 0,
                            )
                            if (!selectionMode) {
                                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                                    manga.lastReadChapterId?.let { chapterId ->
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.action_continue_reading)) },
                                            onClick = { onOpenChapter(chapterId); dropdownExpanded = false },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_download_all)) },
                                        onClick = { viewModel.downloadAllChapters(manga.id); dropdownExpanded = false },
                                    )
                                    if (categories.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.mylist_add_to_category)) },
                                            onClick = { contextMenuManga = manga; showCategoryAssignDialog = true; dropdownExpanded = false },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.mylist_remove_from_library), color = MaterialTheme.colorScheme.error) },
                                        onClick = { viewModel.removeFromLibrary(manga.id); dropdownExpanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (pullToRefreshState.verticalOffset > 0f) {
                PullToRefreshContainer(state = pullToRefreshState, modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        if (localImportState is LocalImportState.Importing) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    com.haise.jiyu.ui.components.JiyuLoadingIndicator()
                    androidx.compose.material3.Text(stringResource(R.string.mylist_importing_file), color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }

    // ── FAB "+Přidat" nebo Bulk action bar ───────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
        ) {
            BulkActionBar(
                count = selectedIds.size,
                hasCategories = categories.isNotEmpty(),
                onDownload = { viewModel.bulkDownload() },
                onMarkRead = { viewModel.bulkMarkRead() },
                onAddToCategory = { showBulkCategoryDialog = true },
                onDelete = { viewModel.bulkRemoveFromLibrary() },
            )
        }
        AnimatedVisibility(
            visible = !selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .size(56.dp)
                    .violetGlow()
                    .background(Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))), CircleShape)
                    .clip(CircleShape)
                    .pointerInput(Unit) { detectTapGestures(onTap = { onOpenBrowse() }) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    TablerIcons.Plus,
                    contentDescription = stringResource(R.string.mylist_add_fab),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }

    // ── Dialogy ──────────────────────────────────────────────────────────────
    if (showStatsDialog) StatsDialog(
        stats = readingStats,
        onDismiss = { showStatsDialog = false },
        onOpenExtended = { showStatsDialog = false; onOpenStats() },
    )
    if (showFilterSheet) {
        FilterSortBottomSheet(
            categories = categories,
            selectedCategoryId = selectedCategoryId,
            contentTypeFilter = contentTypeFilter,
            sortOption = sortOption,
            sortAscending = sortAscending,
            onSelectCategory = { viewModel.selectCategory(it) },
            onSelectContentType = { viewModel.setContentTypeFilter(it) },
            onSelectSort = { viewModel.setSortOption(it) },
            onManageCategories = { showFilterSheet = false; showManageDialog = true },
            onDismiss = { showFilterSheet = false },
        )
    }
    if (showManageDialog) ManageCategoriesDialog(categories = categories, viewModel = viewModel, onDismiss = { showManageDialog = false })
    if (showCategoryAssignDialog) {
        contextMenuManga?.let { manga ->
            CategoryAssignDialog(manga = manga, allCategories = categories, viewModel = viewModel,
                onDismiss = { showCategoryAssignDialog = false; contextMenuManga = null })
        }
    }
    if (showBulkCategoryDialog) {
        BulkCategoryDialog(
            count = selectedIds.size,
            categories = categories,
            onPickCategory = { viewModel.bulkAddToCategory(it) },
            onDismiss = { showBulkCategoryDialog = false },
        )
    }
    if (showMarkAllReadDialog) {
        AlertDialog(
            onDismissRequest = { showMarkAllReadDialog = false },
            containerColor = Color(0xFF111B35),
            title = { Text(stringResource(R.string.mylist_mark_all_read_title), color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.mylist_mark_all_read_body, library.size), color = Color(0xFFB0BEC5)) },
            confirmButton = {
                TextButton(onClick = { viewModel.markEntireLibraryAsRead(); showMarkAllReadDialog = false }) {
                    Text(stringResource(R.string.mylist_mark_all), color = GlowViolet)
                }
            },
            dismissButton = { TextButton(onClick = { showMarkAllReadDialog = false }) { Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5)) } },
        )
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
    ) { data -> Snackbar(snackbarData = data) }
    } // end Box
}

// ── Empty state ───────────────────────────────────────────────────────────────

@Composable
private fun LibraryEmptyState(hasSearch: Boolean, onOpenBrowse: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            if (hasSearch) {
                Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(52.dp).padding(bottom = 16.dp))
                Text(
                    stringResource(R.string.library_nothing_found),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.mylist_try_different_term_or_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(NightBlue, CircleShape)
                        .border(1.dp, CardBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TablerIcons.Book,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.mylist_no_titles_match_filter),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.mylist_add_from_browse_or_change_filter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                )
                Box(
                    modifier = Modifier
                        .violetGlow()
                        .background(
                            Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))),
                            RoundedCornerShape(14.dp),
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenBrowse)
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(
                        stringResource(R.string.library_browse_manga_button),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

// ── Bulk action bar ───────────────────────────────────────────────────────────

@Composable
private fun BulkActionBar(
    count: Int,
    hasCategories: Boolean,
    onDownload: () -> Unit,
    onMarkRead: () -> Unit,
    onAddToCategory: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(
                Brush.linearGradient(listOf(NightBlue.copy(alpha = 0.95f), Color(0xFF0D1530).copy(alpha = 0.95f))),
                RoundedCornerShape(20.dp),
            )
            .border(1.dp, GlowViolet.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BulkAction(icon = TablerIcons.Download, label = stringResource(R.string.common_download), onClick = onDownload)
            BulkAction(icon = TablerIcons.Checks, label = stringResource(R.string.mylist_bulk_read), onClick = onMarkRead)
            if (hasCategories) {
                BulkAction(icon = TablerIcons.Folder, label = stringResource(R.string.mylist_bulk_category), onClick = onAddToCategory)
            }
            BulkAction(icon = TablerIcons.Trash, label = stringResource(R.string.common_remove), tint = Color(0xFFFF6B6B), onClick = onDelete)
        }
    }
}

@Composable
private fun BulkAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = TextSecondary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

private fun contentTypeBadgeColor(contentType: String): Color = when (contentType) {
    "MANHWA" -> GlowViolet
    "MANHUA" -> GlowCyan
    "NOVEL"  -> Danger
    "COMIC"  -> Pink
    else     -> Color(0xFF6B7280) // MANGA a neznámé typy - neutrální šedá
}

@Composable
private fun readingStatusLabel(status: String?): String = when (status) {
    "READING"      -> stringResource(R.string.detail_reading_status_reading)
    "COMPLETED"    -> stringResource(R.string.detail_status_completed)
    "ON_HOLD"      -> stringResource(R.string.mylist_filter_on_hold)
    "PLAN_TO_READ" -> stringResource(R.string.mylist_filter_plan_to_read)
    "DROPPED"      -> stringResource(R.string.detail_reading_status_dropped)
    else           -> stringResource(R.string.mylist_status_unset)
}

private fun formatReadingHours(ms: Long): String {
    val hours = ms / 3_600_000L
    return if (hours < 1) "<1 h" else "$hours h"
}

@Composable
private fun LibraryListRow(
    manga: MangaEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onMoreClick: () -> Unit,
    unreadCount: Int,
    totalCount: Int,
    hasDownloads: Boolean,
) {
    val readCount = (totalCount - unreadCount).coerceAtLeast(0)
    val progress = if (totalCount > 0) readCount.toFloat() / totalCount.toFloat() else 0f

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) GlowViolet.copy(alpha = 0.15f) else Color.Transparent)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() }) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.width(64.dp).height(92.dp).clip(RoundedCornerShape(10.dp))) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(contentTypeBadgeColor(manga.contentType), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(manga.contentType, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                }
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(GlowViolet.copy(alpha = 0.35f)))
                    Icon(
                        TablerIcons.CircleCheck, contentDescription = stringResource(R.string.mylist_selected_desc), tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(24.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(manga.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(readingStatusLabel(manga.readingStatus), color = TextSecondary, fontSize = 12.sp)
                    if (manga.readingTimeMs > 0) {
                        Text(" • ${formatReadingHours(manga.readingTimeMs)}", color = TextSecondary, fontSize = 12.sp)
                    }
                    if (hasDownloads) {
                        Spacer(Modifier.width(6.dp))
                        Icon(TablerIcons.CloudDownload, contentDescription = stringResource(R.string.mylist_downloaded_offline), tint = GlowCyan, modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(CardBorder)) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(GlowViolet, RoundedCornerShape(50)))
                }
            }
            if (totalCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, GlowViolet.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row {
                        Text("$readCount", color = GlowViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(" / $totalCount", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            IconButton(onClick = onMoreClick, modifier = Modifier.size(28.dp)) {
                Icon(TablerIcons.DotsVertical, contentDescription = stringResource(R.string.detail_more_options), tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = CardBorder, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FilterSortBottomSheet(
    categories: List<CategoryEntity>,
    selectedCategoryId: String?,
    contentTypeFilter: String,
    sortOption: LibrarySortOption,
    sortAscending: Boolean,
    onSelectCategory: (String?) -> Unit,
    onSelectContentType: (String) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.mylist_filter_and_sort), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.mylist_category_section), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(label = stringResource(R.string.common_all), colorHex = "#8B5CF6", selected = selectedCategoryId == null, onClick = { onSelectCategory(null) })
                categories.forEach { cat ->
                    CategoryChip(label = cat.name, colorHex = cat.colorHex, selected = selectedCategoryId == cat.id, onClick = { onSelectCategory(cat.id) })
                }
                TextButton(onClick = onManageCategories) {
                    Icon(TablerIcons.Plus, contentDescription = stringResource(R.string.mylist_manage_categories), tint = GlowViolet, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.mylist_manage_categories), color = GlowViolet, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.mylist_content_type_section), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val types = listOf(
                "ALL" to stringResource(R.string.common_all),
                "MANGA" to stringResource(R.string.browse_filter_manga),
                "MANHWA" to stringResource(R.string.mylist_content_manhwa),
                "MANHUA" to stringResource(R.string.mylist_content_manhua),
                "NOVEL" to stringResource(R.string.mylist_content_novel),
                "COMIC" to stringResource(R.string.mylist_content_comic),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { (key, label) ->
                    ContentTypeChip(label = label, selected = contentTypeFilter == key, onClick = { onSelectContentType(key) })
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.mylist_sort_by), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val sortOptions = listOf(
                LibrarySortOption.TITLE        to stringResource(R.string.source_browse_sort_title),
                LibrarySortOption.LAST_UPDATED to stringResource(R.string.mylist_sort_last_updated),
                LibrarySortOption.UNREAD_COUNT to stringResource(R.string.detail_filter_unread),
                LibrarySortOption.DATE_ADDED   to stringResource(R.string.mylist_sort_date_added),
                LibrarySortOption.RANDOM       to stringResource(R.string.mylist_sort_random),
            )
            sortOptions.forEach { (option, label) ->
                val selected = option == sortOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectSort(option) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (selected) GlowViolet else TextPrimary, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (selected) {
                        Icon(
                            if (sortAscending) TablerIcons.ArrowUp else TablerIcons.ArrowDown,
                            contentDescription = if (sortAscending) stringResource(R.string.mylist_ascending) else stringResource(R.string.mylist_descending),
                            tint = GlowViolet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingStatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) GlowViolet else NightBlue)
            .border(1.dp, if (selected) GlowViolet else CardBorder, RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = if (selected) Color.White else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ContentTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) GlowCyan else TextSecondary
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) GlowCyan.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) GlowCyan.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CategoryChip(label: String, colorHex: String, selected: Boolean, onClick: () -> Unit) {
    val color = remember(colorHex) {
        try { Color(android.graphics.Color.parseColor(colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
    }
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected) color else color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = if (selected) color else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun AnimeMangaCard(
    manga: MangaEntity,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    isSelected: Boolean = false,
    unreadCount: Int = 0,
    totalCount: Int = 0,
    hasDownloads: Boolean = false,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "card_scale",
    )
    Box(
        modifier = Modifier
            .aspectRatio(0.68f)
            .scale(scale)
            .violetGlow(radius = 16f, alpha = if (isSelected) 0.4f else 0.15f)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) GlowViolet else GlowViolet.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        AsyncImage(model = manga.coverUrl, contentDescription = manga.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        // Dimming overlay when selected
        if (isSelected) {
            Box(modifier = Modifier.fillMaxSize().background(GlowViolet.copy(alpha = 0.35f)))
        }
        Box(modifier = Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE5070B14)))))
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 7.dp, vertical = 6.dp)) {
            Text(text = manga.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            if (totalCount > 0) {
                val readCount = totalCount - unreadCount
                Text(text = stringResource(R.string.mylist_read_total_count, readCount, totalCount), color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, lineHeight = 11.sp)
            }
        }
        // Selection checkmark — top-left when selected
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(GlowViolet, CircleShape)
                    .size(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TablerIcons.CircleCheck, contentDescription = stringResource(R.string.mylist_selected_desc), tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        // Unread badge — top-right (hide when selected)
        if (unreadCount > 0 && !isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(5.dp)
                    .background(GlowViolet, RoundedCornerShape(50))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = if (unreadCount > 99) "99+" else "$unreadCount", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold, lineHeight = 11.sp)
            }
        }
        // Content type badge (MANHWA / MANHUA) — skip for MANGA (default)
        if (!isSelected && manga.contentType != "MANGA") {
            val badgeColor = if (manga.contentType == "MANHWA") GlowCyan else Color(0xFFEC4899)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = if (unreadCount > 0) 28.dp else 5.dp, end = 5.dp)
                    .background(badgeColor.copy(alpha = 0.90f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            ) {
                Text(text = manga.contentType, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 10.sp)
            }
        }
        // Offline icon — top-left (only when not selected)
        if (hasDownloads && !isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp)
                    .background(GlowCyan.copy(alpha = 0.85f), RoundedCornerShape(50))
                    .padding(3.dp),
            ) {
                Icon(TablerIcons.CloudDownload, contentDescription = stringResource(R.string.mylist_downloaded_offline), tint = Color.White, modifier = Modifier.size(10.dp))
            }
        }
    }
}

@Composable
private fun BulkCategoryDialog(
    count: Int,
    categories: List<CategoryEntity>,
    onPickCategory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.mylist_add_n_to_category, count), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                categories.forEach { cat ->
                    val color = remember(cat.colorHex) {
                        try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPickCategory(cat.id); onDismiss() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                        Spacer(Modifier.width(12.dp))
                        Text(cat.name, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5)) } },
    )
}

@Composable
private fun CategoryAssignDialog(manga: MangaEntity, allCategories: List<CategoryEntity>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val catIds by viewModel.observeCategoryIdsForManga(manga.id).collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(text = manga.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                allCategories.forEach { cat ->
                    val selected = cat.id in catIds
                    val color = remember(cat.colorHex) {
                        try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (selected) viewModel.removeMangaFromCategory(manga.id, cat.id)
                                else viewModel.addMangaToCategory(manga.id, cat.id)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                if (selected) viewModel.removeMangaFromCategory(manga.id, cat.id)
                                else viewModel.addMangaToCategory(manga.id, cat.id)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = color),
                        )
                        Text(cat.name, color = if (selected) color else Color(0xFFB0BEC5), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = GlowViolet) } },
    )
}

@Composable
private fun ManageCategoriesDialog(categories: List<CategoryEntity>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.mylist_categories_title), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                categories.forEach { cat ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val color = remember(cat.colorHex) { try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) } }
                        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                        Text(text = cat.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        IconButton(onClick = { viewModel.deleteCategory(cat) }, modifier = Modifier.size(32.dp)) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_delete), tint = Color(0xFFB0BEC5), modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.mylist_new_category_name), color = Color(0xFFB0BEC5), fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) { viewModel.createCategory(newName, viewModel.nextColor(categories)); newName = ""; focusManager.clearFocus() }
                    }),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GlowViolet, unfocusedBorderColor = GlowViolet.copy(alpha = 0.3f), cursorColor = CyanLight),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) { viewModel.createCategory(newName, viewModel.nextColor(categories)); newName = "" }
                onDismiss()
            }) { Text(stringResource(R.string.common_done), color = GlowViolet) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = Color(0xFFB0BEC5)) } },
    )
}

@Composable
private fun StatsDialog(stats: ReadingStats, onDismiss: () -> Unit, onOpenExtended: () -> Unit = {}) {
    val totalMinutes = stats.readingTimeMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val timeLabel = when {
        hours > 0   -> stringResource(R.string.mylist_time_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.mylist_time_minutes, minutes)
        else        -> stringResource(R.string.mylist_time_less_than_minute)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.mylist_reading_stats_title), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                StatRow(stringResource(R.string.mylist_chapters_read), "${stats.chaptersRead}")
                HorizontalDivider(color = GlowViolet.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
                StatRow(stringResource(R.string.mylist_pages_read), "${stats.pagesRead}")
                HorizontalDivider(color = GlowViolet.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
                StatRow(stringResource(R.string.stats_reading_time_label), timeLabel)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = GlowViolet) } },
        dismissButton = { TextButton(onClick = onOpenExtended) { Text(stringResource(R.string.mylist_detailed_stats), color = GlowViolet) } },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
