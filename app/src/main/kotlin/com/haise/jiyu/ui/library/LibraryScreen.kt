package com.haise.jiyu.ui.library

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Sort
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.settings.ReadingStats
import com.haise.jiyu.ui.settings.SettingsViewModel
import androidx.compose.material3.CircularProgressIndicator
import com.haise.jiyu.ui.theme.CyanLight
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.VioletLight
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import com.haise.jiyu.ui.theme.violetGlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
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
    val contentTypeFilter  by viewModel.contentTypeFilter.collectAsState()
    val searchQuery        by viewModel.searchQuery.collectAsState()
    val sortOption         by viewModel.sortOption.collectAsState()
    val sortAscending      by viewModel.sortAscending.collectAsState()
    val isRefreshing       by viewModel.isRefreshing.collectAsState()
    val refreshError       by viewModel.refreshError.collectAsState()
    val readingStats       by settingsViewModel.readingStats.collectAsState()
    val selectionMode      by viewModel.selectionMode.collectAsState()
    val selectedIds        by viewModel.selectedIds.collectAsState()

    val localImportState   by viewModel.localImportState.collectAsState()

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importLocalFile(it) } }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope    = rememberCoroutineScope()

    LaunchedEffect(refreshError) {
        val msg = refreshError ?: return@LaunchedEffect
        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
        viewModel.clearRefreshError()
    }
    LaunchedEffect(localImportState) {
        when (val s = localImportState) {
            is LocalImportState.Done  -> { onOpenChapter(s.chapterId); viewModel.clearLocalImportState() }
            is LocalImportState.Error -> { snackbarHostState.showSnackbar("Chyba: ${s.message}"); viewModel.clearLocalImportState() }
            else -> {}
        }
    }
    val recentlyRead       by viewModel.recentlyRead.collectAsState()
    val unreadCounts       by viewModel.unreadCounts.collectAsState()
    val totalCounts        by viewModel.totalCounts.collectAsState()
    val downloadedPerManga by viewModel.downloadedPerManga.collectAsState()

    var showManageDialog          by remember { mutableStateOf(false) }
    var showStatsDialog           by remember { mutableStateOf(false) }
    var sortMenuExpanded          by remember { mutableStateOf(false) }
    var contextMenuManga          by remember { mutableStateOf<MangaEntity?>(null) }
    var showCategoryAssignDialog  by remember { mutableStateOf(false) }
    var showBulkCategoryDialog    by remember { mutableStateOf(false) }

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
                        Icon(Icons.Filled.Close, contentDescription = "Zrušit výběr", tint = TextSecondary)
                    }
                    Text(
                        text = "${selectedIds.size} vybráno",
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.selectAll() }) {
                        Icon(Icons.Filled.SelectAll, contentDescription = null, tint = GlowViolet, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Vše", color = GlowViolet, fontSize = 14.sp)
                    }
                }
            } else {
                // Title row
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(text = "JIYU", style = TextStyle(brush = titleGradient, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp))
                        Text(text = "Knihovna · ${library.size} titulů", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("application/zip", "application/x-cbz", "application/octet-stream", "*/*")) },
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = "Otevřít CBZ/ZIP", tint = TextSecondary)
                    }
                    Box {
                        IconButton(onClick = { sortMenuExpanded = true }) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Řadit", tint = TextSecondary)
                        }
                        SortMenu(
                            expanded = sortMenuExpanded,
                            sortOption = sortOption,
                            ascending = sortAscending,
                            onSelect = { viewModel.setSortOption(it) },
                            onDismiss = { sortMenuExpanded = false },
                        )
                    }
                    IconButton(onClick = { showStatsDialog = true }) { Icon(Icons.Filled.AutoStories, contentDescription = "Statistiky", tint = TextSecondary) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Nastavení", tint = TextSecondary) }
                }

                // Always-visible search bar
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
                    Icon(Icons.Filled.Search, contentDescription = null, tint = if (searchQuery.isNotEmpty()) GlowViolet else TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(17.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                if (searchQuery.isEmpty()) Text("Hledat v knihovně…", color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Smazat", tint = TextSecondary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        }

        // ── Kategorie filter ─────────────────────────────────────────────────
        if (!selectionMode) {
            if (categories.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    item { CategoryChip(label = "Vše", colorHex = "#8B5CF6", selected = selectedCategoryId == null, onClick = { viewModel.selectCategory(null) }) }
                    items(categories, key = { it.id }) { cat ->
                        CategoryChip(label = cat.name, colorHex = cat.colorHex, selected = selectedCategoryId == cat.id, onClick = { viewModel.selectCategory(cat.id) })
                    }
                    item {
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(50))
                                .pointerInput(Unit) { detectTapGestures(onTap = { showManageDialog = true }) }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Spravovat kategorie", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            } else {
                TextButton(onClick = { showManageDialog = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = GlowViolet, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Přidat kategorii", color = GlowViolet, fontSize = 13.sp)
                }
            }
        }

        // ── Content type filter ──────────────────────────────────────────────
        if (!selectionMode) {
            val types = listOf("ALL" to "Vše", "MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(types) { (key, label) ->
                    ContentTypeChip(label = label, selected = contentTypeFilter == key, onClick = { viewModel.setContentTypeFilter(key) })
                }
            }
        }

        // ── Naposledy čteno ──────────────────────────────────────────────────
        if (recentlyRead.isNotEmpty() && !selectionMode && searchQuery.isEmpty()) {
            RecentlyReadRow(items = recentlyRead, onOpen = onOpenManga)
        }

        // ── Grid / empty + pull-to-refresh ───────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(modifier = Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
            if (library.isEmpty()) {
                LibraryEmptyState(
                    hasSearch = searchQuery.isNotEmpty(),
                    onOpenBrowse = onOpenBrowse,
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
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
                                            text = { Text("Pokračovat ve čtení") },
                                            onClick = { onOpenChapter(chapterId); dropdownExpanded = false },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Stáhnout vše") },
                                        onClick = { viewModel.downloadAllChapters(manga.id); dropdownExpanded = false },
                                    )
                                    if (categories.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Přidat do kategorie") },
                                            onClick = { contextMenuManga = manga; showCategoryAssignDialog = true; dropdownExpanded = false },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("Odebrat z knihovny", color = MaterialTheme.colorScheme.error) },
                                        onClick = { viewModel.removeFromLibrary(manga.id); dropdownExpanded = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            PullToRefreshContainer(state = pullToRefreshState, modifier = Modifier.align(Alignment.TopCenter))
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
                    CircularProgressIndicator(color = Color(0xFF8B5CF6))
                    androidx.compose.material3.Text("Importuji soubor…", color = Color.White, fontSize = 14.sp)
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
            modifier = Modifier.align(Alignment.BottomEnd),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(20.dp)
                    .violetGlow()
                    .background(Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))), RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .pointerInput(Unit) { detectTapGestures(onTap = { onOpenBrowse() }) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text("+ Přidat", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
    }

    // ── Dialogy ──────────────────────────────────────────────────────────────
    if (showStatsDialog) StatsDialog(
        stats = readingStats,
        onDismiss = { showStatsDialog = false },
        onOpenExtended = { showStatsDialog = false; onOpenStats() },
    )
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
                Text("🔍", fontSize = 52.sp, modifier = Modifier.padding(bottom = 16.dp))
                Text(
                    "Nic nenalezeno",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Zkus jiný výraz nebo vyhledej autora / žánr",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(
                            Brush.radialGradient(listOf(GlowViolet.copy(alpha = 0.2f), Color.Transparent)),
                            CircleShape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = GlowViolet.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "Tvá knihovna čeká",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Přidej svoji první mangu a začni číst",
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
                        "Procházet mangy",
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
            BulkAction(icon = Icons.Filled.Download, label = "Stáhnout", onClick = onDownload)
            BulkAction(icon = Icons.Filled.DoneAll, label = "Přečteno", onClick = onMarkRead)
            if (hasCategories) {
                BulkAction(icon = Icons.Filled.Folder, label = "Kategorie", onClick = onAddToCategory)
            }
            BulkAction(icon = Icons.Filled.Delete, label = "Odebrat", tint = Color(0xFFFF6B6B), onClick = onDelete)
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

@Composable
private fun SortMenu(
    expanded: Boolean,
    sortOption: LibrarySortOption,
    ascending: Boolean,
    onSelect: (LibrarySortOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        LibrarySortOption.TITLE        to "Název",
        LibrarySortOption.LAST_UPDATED to "Naposledy aktualizováno",
        LibrarySortOption.UNREAD_COUNT to "Nepřečtené",
        LibrarySortOption.DATE_ADDED   to "Datum přidání",
        LibrarySortOption.RANDOM       to "Náhodně",
    )
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        options.forEach { (option, label) ->
            val selected = option == sortOption
            DropdownMenuItem(
                text = { Text(label, color = if (selected) GlowViolet else TextPrimary) },
                leadingIcon = {
                    if (selected) {
                        Icon(
                            if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                            contentDescription = if (ascending) "Vzestupně" else "Sestupně",
                            tint = GlowViolet,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Spacer(Modifier.size(18.dp))
                    }
                },
                onClick = { onSelect(option); onDismiss() },
            )
        }
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
                Text(text = "$readCount / $totalCount", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, lineHeight = 11.sp)
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
                Icon(Icons.Filled.CheckCircle, contentDescription = "Vybráno", tint = Color.White, modifier = Modifier.size(16.dp))
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
                Icon(Icons.Filled.DownloadDone, contentDescription = "Staženo offline", tint = Color.White, modifier = Modifier.size(10.dp))
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
        title = { Text("Přidat $count mang do kategorie", color = TextPrimary, fontWeight = FontWeight.Bold) },
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
                        Text(cat.name, color = TextPrimary, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zrušit", color = TextSecondary) } },
    )
}

@Composable
private fun CategoryAssignDialog(manga: MangaEntity, allCategories: List<CategoryEntity>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val catIds by viewModel.observeCategoryIdsForManga(manga.id).collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(text = manga.title, color = TextPrimary, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                        Text(cat.name, color = if (selected) color else TextSecondary, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Hotovo", color = GlowViolet) } },
    )
}

@Composable
private fun ManageCategoriesDialog(categories: List<CategoryEntity>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text("Kategorie", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                categories.forEach { cat ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val color = remember(cat.colorHex) { try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) } }
                        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                        Text(text = cat.name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        IconButton(onClick = { viewModel.deleteCategory(cat) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Smazat", tint = TextSecondary, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Název nové kategorie", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) { viewModel.createCategory(newName, viewModel.nextColor(categories)); newName = ""; focusManager.clearFocus() }
                    }),
                    textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GlowViolet, unfocusedBorderColor = GlowViolet.copy(alpha = 0.3f), cursorColor = CyanLight),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) { viewModel.createCategory(newName, viewModel.nextColor(categories)); newName = "" }
                onDismiss()
            }) { Text("Hotovo", color = GlowViolet) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Zavřít", color = TextSecondary) } },
    )
}

@Composable
private fun StatsDialog(stats: ReadingStats, onDismiss: () -> Unit, onOpenExtended: () -> Unit = {}) {
    val totalMinutes = stats.readingTimeMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val timeLabel = when {
        hours > 0   -> "$hours h $minutes min"
        minutes > 0 -> "$minutes min"
        else        -> "méně než minutu"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text("Statistiky čtení", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                StatRow("Přečtené kapitoly", "${stats.chaptersRead}")
                HorizontalDivider(color = GlowViolet.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
                StatRow("Přečtené stránky", "${stats.pagesRead}")
                HorizontalDivider(color = GlowViolet.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
                StatRow("Čas čtení", timeLabel)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Zavřít", color = GlowViolet) } },
        dismissButton = { TextButton(onClick = onOpenExtended) { Text("Detailní →", color = GlowViolet) } },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecentlyReadRow(items: List<MangaEntity>, onOpen: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "NAPOSLEDY ČTENO",
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
            color = GlowViolet,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items, key = { it.id }) { manga ->
                RecentMangaCard(manga = manga, onClick = { onOpen(manga.id) })
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun RecentMangaCard(manga: MangaEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(72.dp)
            .aspectRatio(0.68f)
            .violetGlow(radius = 10f, alpha = 0.1f)
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, GlowViolet.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = manga.coverUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE5070B14)))),
        )
        Text(
            text = manga.title,
            color = Color.White,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 5.dp, vertical = 4.dp),
        )
    }
}
