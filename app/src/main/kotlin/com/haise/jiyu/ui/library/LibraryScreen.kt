package com.haise.jiyu.ui.library

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.runtime.setValue

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.settings.ReadingStats
import com.haise.jiyu.ui.settings.SettingsViewModel
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
    viewModel: LibraryViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val library            by viewModel.library.collectAsState()
    val categories         by viewModel.categories.collectAsState()
    val selectedCategoryId by viewModel.selectedCategoryId.collectAsState()
    val searchQuery        by viewModel.searchQuery.collectAsState()
    val isRefreshing       by viewModel.isRefreshing.collectAsState()
    val readingStats       by settingsViewModel.readingStats.collectAsState()
    val recentlyRead       by viewModel.recentlyRead.collectAsState()
    val unreadCounts       by viewModel.unreadCounts.collectAsState()
    val totalCounts        by viewModel.totalCounts.collectAsState()
    val downloadedPerManga by viewModel.downloadedPerManga.collectAsState()

    var showManageDialog         by remember { mutableStateOf(false) }
    var showStatsDialog          by remember { mutableStateOf(false) }
    var searchActive             by remember { mutableStateOf(false) }
    var contextMenuManga         by remember { mutableStateOf<MangaEntity?>(null) }
    var showCategoryAssignDialog by remember { mutableStateOf(false) }

    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(pullToRefreshState.isRefreshing) {
        if (pullToRefreshState.isRefreshing) viewModel.refreshLibrary()
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullToRefreshState.endRefresh()
    }

    Column(modifier = Modifier.fillMaxSize().background(screenGradient)) {

        // ── Header ───────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, DeepSpace.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (searchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth().height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                if (searchQuery.isEmpty()) Text("Hledat v knihovně…", color = TextSecondary, fontSize = 15.sp)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { searchActive = false; viewModel.setSearchQuery("") }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Zavřít", tint = TextSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(text = "JIYU", style = TextStyle(brush = titleGradient, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp))
                        Text(text = "Knihovna · ${library.size} manga", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                    IconButton(onClick = { searchActive = true }) { Icon(Icons.Filled.Search, contentDescription = "Hledat", tint = TextSecondary) }
                    IconButton(onClick = { showStatsDialog = true }) { Icon(Icons.Filled.AutoStories, contentDescription = "Statistiky", tint = TextSecondary) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Nastavení", tint = TextSecondary) }
                }
            }
        }

        // ── Kategorie filter ─────────────────────────────────────────────────
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

        // ── Naposledy čteno ──────────────────────────────────────────────────
        if (recentlyRead.isNotEmpty()) {
            RecentlyReadRow(items = recentlyRead, onOpen = onOpenManga)
        }

        // ── Grid / empty + pull-to-refresh ───────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        Box(modifier = Modifier.fillMaxSize().nestedScroll(pullToRefreshState.nestedScrollConnection)) {
            if (library.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("( ˘•ω•˘ )", fontSize = 36.sp, color = GlowViolet.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 12.dp))
                        Text("Knihovna je prázdná", style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                        Text("Tap + pro přidání mangy", style = MaterialTheme.typography.bodySmall, color = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 80.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(library, key = { it.id }) { manga ->
                        var dropdownExpanded by remember { mutableStateOf(false) }
                        Box {
                            AnimeMangaCard(
                                manga = manga,
                                onClick = { onOpenManga(manga.id) },
                                onLongPress = { dropdownExpanded = true },
                                unreadCount = unreadCounts[manga.id] ?: 0,
                                totalCount = totalCounts[manga.id] ?: 0,
                                hasDownloads = (downloadedPerManga[manga.id] ?: 0) > 0,
                            )
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

            PullToRefreshContainer(state = pullToRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    // ── FAB "+Přidat" ────────────────────────────────────────────────────────
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
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

    // ── Dialogy ──────────────────────────────────────────────────────────────
    if (showStatsDialog) StatsDialog(stats = readingStats, onDismiss = { showStatsDialog = false })
    if (showManageDialog) ManageCategoriesDialog(categories = categories, viewModel = viewModel, onDismiss = { showManageDialog = false })
    if (showCategoryAssignDialog) {
        contextMenuManga?.let { manga ->
            CategoryAssignDialog(manga = manga, allCategories = categories, viewModel = viewModel,
                onDismiss = { showCategoryAssignDialog = false; contextMenuManga = null })
        }
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

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
private fun AnimeMangaCard(manga: MangaEntity, onClick: () -> Unit, onLongPress: () -> Unit, unreadCount: Int = 0, totalCount: Int = 0, hasDownloads: Boolean = false) {
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
            .violetGlow(radius = 16f, alpha = 0.15f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() },
                    onLongPress = { onLongPress() },
                )
            },
    ) {
        AsyncImage(model = manga.coverUrl, contentDescription = manga.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(modifier = Modifier.fillMaxWidth().height(80.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE5070B14)))))
        // Název + progress v dolní části
        Column(modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 7.dp, vertical = 6.dp)) {
            Text(text = manga.title, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp)
            if (totalCount > 0) {
                val readCount = totalCount - (unreadCount)
                Text(text = "$readCount / $totalCount", color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp, lineHeight = 11.sp)
            }
        }
        // Badge s počtem nepřečtených — top-right
        if (unreadCount > 0) {
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
        // Offline ikona — top-left
        if (hasDownloads) {
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
private fun StatsDialog(stats: ReadingStats, onDismiss: () -> Unit) {
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
