package com.haise.jiyu.ui.browse

import compose.icons.TablerIcons
import compose.icons.tablericons.*

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.SManga
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import com.haise.jiyu.ui.theme.violetGlow

/** Obsah jednoho zdroje - Populární/Nejnovější, hledání v rámci zdroje, mřížka výsledků. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceBrowseScreen(
    onBack: () -> Unit,
    onMangaAdded: (String) -> Unit,
    viewModel: SourceBrowseViewModel = hiltViewModel(),
) {
    val source            by viewModel.source.collectAsState()
    val results           by viewModel.results.collectAsState()
    val loading           by viewModel.loading.collectAsState()
    val error             by viewModel.error.collectAsState()
    val previewManga      by viewModel.previewManga.collectAsState()
    val hasMore           by viewModel.hasMore.collectAsState()
    val activeFilter      by viewModel.activeFilter.collectAsState()
    val showLatest        by viewModel.showLatest.collectAsState()
    val pendingDuplicateAdd by viewModel.pendingDuplicateAdd.collectAsState()
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyGridState()
    var showFilterSheet by remember { mutableStateOf(false) }

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 5 && totalItems > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore) viewModel.loadMore()
    }

    Column(modifier = Modifier.fillMaxSize().background(screenGradient)) {
        // ── Top bar ─────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(TablerIcons.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
            }
            Text(
                text = source?.name ?: "",
                style = TextStyle(brush = titleGradient, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            IconButton(onClick = { showFilterSheet = true }) {
                Icon(
                    imageVector = TablerIcons.Filter,
                    contentDescription = "Filtry",
                    tint = if (activeFilter != MangaFilter()) Violet else TextSecondary,
                )
            }
        }

        // ── Hledání v rámci zdroje ────────────────────────────────────────────
        TextField(
            value = query,
            onValueChange = { query = it; viewModel.search(it) },
            placeholder = { Text("Hledat v ${source?.name.orEmpty()}…", color = TextSecondary) },
            singleLine = true,
            leadingIcon = { Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.search(query) }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Violet,
                unfocusedIndicatorColor = TextSecondary.copy(alpha = 0.3f),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Violet,
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        )

        // ── Popular / Latest toggle ───────────────────────────────────────────
        if (query.isBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(false to "Populární", true to "Nejnovější").forEach { (isLatest, label) ->
                    val selected = showLatest == isLatest
                    Button(
                        onClick = { if (!selected) viewModel.setShowLatest(isLatest) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) Violet.copy(alpha = 0.2f) else Color.Transparent,
                            contentColor = if (selected) Violet else TextSecondary,
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) Violet.copy(alpha = 0.5f) else TextSecondary.copy(alpha = 0.15f)),
                        elevation = null,
                    ) { Text(label, fontSize = 13.sp) }
                }
            }
        }

        // ── Results area ─────────────────────────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        when {
            loading && results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    JiyuLoadingIndicator()
                }
            }
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("( ⚠ )", fontSize = 40.sp, color = GlowViolet.copy(alpha = 0.5f))
                        Text(
                            text = "Načtení selhalo",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        OutlinedButton(onClick = { viewModel.retry() }) {
                            Text("Zkusit znovu", color = Violet)
                        }
                    }
                }
            }
            results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("( ˘•ω•˘ )", fontSize = 36.sp, color = GlowViolet.copy(alpha = 0.5f))
                        Text(
                            text = "Žádné výsledky",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(results, key = { it.sourceId + it.url }) { manga ->
                        BrowseMangaCard(manga = manga) {
                            viewModel.showPreview(manga)
                        }
                    }
                    if (hasMore || loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                JiyuLoadingIndicator(size = 24.dp, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Filter bottom sheet ──────────────────────────────────────────────────
    if (showFilterSheet) {
        val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        BrowseFilterSheet(
            current = activeFilter,
            sheetState = filterSheetState,
            onDismiss = { showFilterSheet = false },
            onApply = { newFilter ->
                viewModel.setFilters(newFilter)
                showFilterSheet = false
            },
        )
    }

    // ── Preview bottom sheet ─────────────────────────────────────────────────
    previewManga?.let { manga ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissPreview() },
            sheetState = sheetState,
            containerColor = Color(0xFF111B35),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = "Obálka: ${manga.title}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        text = manga.title,
                        style = TextStyle(brush = titleGradient, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = source?.name.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!manga.description.isNullOrBlank()) {
                        Text(
                            text = manga.description.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    viewModel.addToLibrary(manga, onMangaAdded)
                                    viewModel.dismissPreview()
                                })
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+ Přidat do knihovny", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }

    if (pendingDuplicateAdd != null) {
        DuplicateWarningDialog(
            pending = pendingDuplicateAdd!!,
            onConfirm = { viewModel.confirmAddDespiteDuplicate(); viewModel.dismissPreview() },
            onDismiss = { viewModel.cancelDuplicateAdd() },
        )
    }
}

@Composable
private fun DuplicateWarningDialog(
    pending: SourceBrowseViewModel.PendingAdd,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text("Možná už tuhle mangu máš", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "\"${pending.manga.title}\" má stejný název jako titul, co už máš v knihovně z jiného zdroje. Zdroje se liší v počtu přeložených kapitol i kvalitě překladu:",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                pending.matches.forEach { match ->
                    Text(
                        "• ${match.sourceName} (v knihovně): ${match.chapterCount} kapitol",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                val newCountText = pending.newChapterCount?.let { "$it kapitol" } ?: "zjišťuji…"
                Text(
                    "• ${pending.newSourceName} (nový): $newCountText",
                    color = GlowViolet,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Přidat i tak", color = GlowViolet) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Zrušit", color = TextSecondary) }
        },
    )
}

@Composable
private fun BrowseMangaCard(manga: SManga, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "browse_card_scale",
    )

    Box(
        modifier = Modifier
            .aspectRatio(0.68f)
            .scale(scale)
            .violetGlow(radius = 14f, alpha = 0.12f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowCyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() },
                )
            },
    ) {
        SubcomposeAsyncImage(
            model = manga.coverUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        ) {
            val state = painter.state
            if (manga.coverUrl.isNullOrBlank() || state is AsyncImagePainter.State.Error) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF0D1526)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TablerIcons.Book,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.3f),
                        modifier = Modifier.size(40.dp),
                    )
                }
            } else {
                SubcomposeAsyncImageContent()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xEA070B14)),
                    )
                )
        )

        Text(
            text = manga.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 7.dp, vertical = 6.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseFilterSheet(
    current: MangaFilter,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onApply: (MangaFilter) -> Unit,
) {
    var selectedStatus by remember { mutableStateOf(current.status) }
    var yearText by remember { mutableStateOf(current.year?.toString() ?: "") }
    var selectedSort by remember { mutableStateOf(current.sortBy) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }

    val statuses = listOf(null to "Vše", "ongoing" to "Vychází", "completed" to "Dokončeno", "hiatus" to "Přerušeno")
    val sorts = listOf("popular" to "Populární", "latest" to "Nejnovější", "rating" to "Hodnocení", "title" to "Název")

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                "Filtry",
                style = TextStyle(brush = titleGradient, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Text("Stav vydávání", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                statuses.forEach { (value, label) ->
                    val selected = selectedStatus == value
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { selectedStatus = value },
                        label = { Text(label, fontSize = 12.sp) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Violet.copy(alpha = 0.3f),
                            selectedLabelColor = Violet,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Rok vydání", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = yearText,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearText = it },
                placeholder = { Text("např. 2020", color = TextSecondary) },
                singleLine = true,
                modifier = Modifier.width(140.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = Violet,
                ),
            )

            Spacer(Modifier.height(16.dp))
            Text("Řazení", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Box {
                OutlinedButton(
                    onClick = { sortDropdownExpanded = true },
                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.5f)),
                ) {
                    Text(sorts.firstOrNull { it.first == selectedSort }?.second ?: "Populární", color = TextPrimary)
                }
                DropdownMenu(
                    expanded = sortDropdownExpanded,
                    onDismissRequest = { sortDropdownExpanded = false },
                ) {
                    sorts.forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { selectedSort = value; sortDropdownExpanded = false },
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onApply(MangaFilter()) },
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.4f)),
                ) {
                    Text("Resetovat", color = TextSecondary)
                }
                Button(
                    onClick = {
                        onApply(MangaFilter(
                            status = selectedStatus,
                            year = yearText.toIntOrNull(),
                            sortBy = selectedSort,
                        ))
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Violet),
                ) {
                    Text("Použít")
                }
            }
        }
    }
}
