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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
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
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.VioletLight
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient

/** Celá filtrovaná knihovna (dřív hlavní Knihovna) - vlastní tab, dashboard Knihovna teď žije v LibraryScreen.kt. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyListScreen(
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
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

    // Od Material3 1.3 drží nested scroll i indikátor PullToRefreshBox sám a stav se předává
    // rovnou z ViewModelu - dvojice LaunchedEffect, která si stav posílala tam a zpět
    // (`pullToRefreshState.isRefreshing` -> refresh(), zpátky `endRefresh()`), odpadla.
    val pullToRefreshState = rememberPullToRefreshState()

    // Exit selection mode on back press
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(screenGradient).statusBarsPadding()) {

        // ── Header ───────────────────────────────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        // Hlavicka je lambda, protoze se vklada DOVNITR scrollovaneho obsahu - v kazde vetvi
        // jineho typu kontejneru. Driv stala mimo scroll a zustavala viset nahore, takze
        // obsah jezdil pod ni. statusBarsPadding je proto na obalu vys: na hlavicce by
        // odscrolovalo pryc s ni a obsah by vjel pod stavovou listu.
        // Cely obsah hlavicky MUSI byt zabaleny v JEDNOM korenovem Composable - LazyColumn
        // (list rezim) vice korenovych uzlu v jedne item{} sice poskladalo pod sebe, ale
        // LazyVerticalGrid (grid rezim) stejnou item(span=...){} misto skladani PREKRYVA
        // (kazdy koren se polozi na stejnou pozici) - overeno zive na emulatoru (nadpis,
        // filtry cteni i "Filtrovat a radit" se vykreslovaly pres sebe). Vnejsi Column tenhle
        // rozdil vyrusi - dovnitr jde jen jediny uzel bez ohledu na typ kontejneru.
        val header: @Composable () -> Unit = {
        Column {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, DeepSpace.copy(alpha = 0f))))
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
                        Text(text = pluralStringResource(R.plurals.mylist_title_count, library.size, library.size), style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1)
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
            // Obyčejný scrollovatelný Row misto LazyRow - jde jen o 6 pevnych polozek a
            // LazyRow vnořené uvnitř LazyVerticalGrid/LazyColumn span-header polozky (viz
            // `header` niz) zpusobovalo, ze se cast hlavicky (nadpis, tenhle radek) v grid
            // rezimu vubec nevykreslila/spatne zmerila - overeno zive na emulatoru.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                readingStatuses.forEach { (key, label) ->
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
        }

        // ── Grid / empty + pull-to-refresh ───────────────────────────────────

        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshLibrary() },
            modifier = Modifier.fillMaxSize(),
            state = pullToRefreshState,
        ) {
            if (library.isEmpty()) {
                Column {
                    header()
                    LibraryEmptyState(
                        hasSearch = searchQuery.isNotEmpty(),
                        onOpenBrowse = onOpenBrowse,
                    )
                }
            } else if (gridMode) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(gridColumns),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) { header() }
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
                    item { header() }
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
                hasCategories = categories.isNotEmpty(),
                onDownload = { viewModel.bulkDownload() },
                onMarkRead = { viewModel.bulkMarkRead() },
                onAddToCategory = { showBulkCategoryDialog = true },
                onDelete = { viewModel.bulkRemoveFromLibrary() },
            )
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
            text = { Text(pluralStringResource(R.plurals.mylist_mark_all_read_body, library.size, library.size), color = Color(0xFFB0BEC5)) },
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
