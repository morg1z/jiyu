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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.haise.jiyu.R
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
    onOpenManga: (String) -> Unit,
    viewModel: SourceBrowseViewModel = hiltViewModel(),
) {
    val source            by viewModel.source.collectAsState()
    val results           by viewModel.results.collectAsState()
    val loading           by viewModel.loading.collectAsState()
    val error             by viewModel.error.collectAsState()
    val openingManga      by viewModel.openingManga.collectAsState()
    val openError         by viewModel.openError.collectAsState()
    val hasMore           by viewModel.hasMore.collectAsState()
    val activeFilter      by viewModel.activeFilter.collectAsState()
    val showLatest        by viewModel.showLatest.collectAsState()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            viewModel.search(query)
        } else {
            delay(450)
            viewModel.search(query)
        }
    }
    val listState = rememberLazyGridState()
    var showFilterSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

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

    LaunchedEffect(openError) {
        openError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearOpenError()
        }
    }

    // Header (zpet/nazev/filtr, hledani, Popularni/Nejnovejsi) uz neni ve fixnim
    // topBar slotu Scaffoldu - je to prvni polozka mrizky/sloupce, takze odjede
    // pryc se scrollem stejne jako obsah pod nim (viz ComicK Domu, stejna zmena).
    val headerContent: @Composable () -> Unit = {
        SourceBrowseHeader(
            sourceName = source?.name ?: "",
            onBack = onBack,
            activeFilter = activeFilter,
            onOpenFilterSheet = { showFilterSheet = true },
            query = query,
            onQueryChange = { query = it },
            onSearchSubmit = { viewModel.search(query) },
            showLatest = showLatest,
            onSetShowLatest = { viewModel.setShowLatest(it) },
        )
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
    Box(modifier = Modifier.fillMaxSize().background(screenGradient).padding(innerPadding)) {
        // ── Results area ─────────────────────────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        when {
            loading && results.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    headerContent()
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        JiyuLoadingIndicator()
                    }
                }
            }
            error != null -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    headerContent()
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(32.dp),
                        ) {
                            Text("( ⚠ )", fontSize = 40.sp, color = GlowViolet.copy(alpha = 0.5f))
                            Text(
                                text = stringResource(R.string.source_browse_load_failed),
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
                                Text(stringResource(R.string.common_retry), color = Violet)
                            }
                        }
                    }
                }
            }
            results.isEmpty() -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    headerContent()
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("( ˘•ω•˘ )", fontSize = 36.sp, color = GlowViolet.copy(alpha = 0.5f))
                            Text(
                                text = stringResource(R.string.source_browse_no_results),
                                style = MaterialTheme.typography.titleMedium,
                                color = TextSecondary,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    // Header musí vizuálně vyrušit mřížkový contentPadding (12dp z každé strany) -
                    // jinak by měl oproti loading/error/prázdnému stavu (headerContent() tam bez
                    // obalky přímo v Column) navíc i tenhle 12dp odsazení ze čtverečku a vypadal by
                    // "smrsklý" doprostred, i když obálky níže mají své vlastní odsazení schválně
                    // (viz BrowseMangaCard). Modifier.padding() zápornou hodnotu odmítá (Compose to
                    // shodí s "Padding must be non-negative"), proto vlastní layout: změří obsah o
                    // 24dp širší, než mřížka nabízí, a posune ho o 12dp doleva.
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            modifier = Modifier.layout { measurable, constraints ->
                                val extra = 24.dp.roundToPx()
                                val placeable = measurable.measure(constraints.copy(maxWidth = constraints.maxWidth + extra))
                                layout(placeable.width, placeable.height) {
                                    placeable.placeRelative(-12.dp.roundToPx(), 0)
                                }
                            },
                        ) { headerContent() }
                    }
                    items(results, key = { it.sourceId + it.url }) { manga ->
                        val isOpening = openingManga?.let { it.sourceId == manga.sourceId && it.url == manga.url } == true
                        BrowseMangaCard(manga = manga, isLoading = isOpening, onClick = {
                            viewModel.openManga(manga, onOpenManga)
                        }, onCoverMissing = { viewModel.fetchCoverIfMissing(manga) })
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
}

@Composable
private fun SourceBrowseHeader(
    sourceName: String,
    onBack: () -> Unit,
    activeFilter: MangaFilter,
    onOpenFilterSheet: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    showLatest: Boolean,
    onSetShowLatest: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.statusBarsPadding().background(screenGradient)) {
        // ── Top bar ─────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
            }
            Text(
                text = sourceName,
                style = TextStyle(brush = titleGradient, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            IconButton(onClick = onOpenFilterSheet) {
                Icon(
                    imageVector = TablerIcons.Filter,
                    contentDescription = stringResource(R.string.source_browse_filters),
                    tint = if (activeFilter != MangaFilter()) Violet else TextSecondary,
                )
            }
        }

        // ── Hledání v rámci zdroje ────────────────────────────────────────────
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.source_browse_search_placeholder, sourceName), color = TextSecondary) },
            singleLine = true,
            leadingIcon = { Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
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
                listOf(false to stringResource(R.string.source_browse_popular), true to stringResource(R.string.source_browse_latest)).forEach { (isLatest, label) ->
                    val selected = showLatest == isLatest
                    Button(
                        onClick = { if (!selected) onSetShowLatest(isLatest) },
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
    }
}

@Composable
private fun BrowseMangaCard(manga: SManga, isLoading: Boolean = false, onClick: () -> Unit, onCoverMissing: () -> Unit) {
    // Karta se zkomponuje jen kdyz je (blizko) ve viewportu LazyVerticalGrid - staci tedy
    // spustit dotazeni tady, zadne rucni sledovani scrollu netreba. Klic je sourceId+url,
    // aby se pri odscrollovani a navratu nespustilo znovu (fetchCoverIfMissing si navic
    // sam hlida rozpracovane pozadavky, tohle je jen prvni bariera).
    LaunchedEffect(manga.sourceId, manga.url) {
        if (manga.coverUrl.isNullOrBlank()) onCoverMissing()
    }

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
            .aspectRatio(0.74f)
            .scale(scale)
            .violetGlow(radius = 14f, alpha = 0.12f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowCyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { if (!isLoading) onClick() },
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

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                JiyuLoadingIndicator(size = 28.dp, strokeWidth = 3.dp)
            }
        }
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

    val statuses = listOf(
        null to stringResource(R.string.common_all),
        "ongoing" to stringResource(R.string.source_browse_status_ongoing),
        "completed" to stringResource(R.string.source_browse_status_completed),
        "hiatus" to stringResource(R.string.source_browse_status_hiatus),
    )
    val sorts = listOf(
        "popular" to stringResource(R.string.source_browse_popular),
        "latest" to stringResource(R.string.source_browse_latest),
        "rating" to stringResource(R.string.source_browse_sort_rating),
        "title" to stringResource(R.string.source_browse_sort_title),
    )

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
                stringResource(R.string.source_browse_filters),
                style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold),
                modifier = Modifier.padding(bottom = 16.dp),
            )

            Text(stringResource(R.string.source_browse_status_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                statuses.forEach { (value, label) ->
                    val selected = selectedStatus == value
                    androidx.compose.material3.FilterChip(
                        selected = selected,
                        onClick = { selectedStatus = value },
                        label = { Text(label, fontSize = 12.sp, color = if (selected) Violet else Color(0xFFB0BEC5)) },
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            selectedContainerColor = Violet.copy(alpha = 0.3f),
                            selectedLabelColor = Violet,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.source_browse_year_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = yearText,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) yearText = it },
                placeholder = { Text(stringResource(R.string.source_browse_year_placeholder), color = Color(0xFFB0BEC5)) },
                singleLine = true,
                modifier = Modifier.width(140.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = Color(0xFFB0BEC5).copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Violet,
                ),
            )

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.source_browse_sort_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Box {
                OutlinedButton(
                    onClick = { sortDropdownExpanded = true },
                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.5f)),
                ) {
                    Text(sorts.firstOrNull { it.first == selectedSort }?.second ?: stringResource(R.string.source_browse_popular), color = Color.White)
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
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB0BEC5).copy(alpha = 0.4f)),
                ) {
                    Text(stringResource(R.string.source_browse_reset), color = Color(0xFFB0BEC5))
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
                    Text(stringResource(R.string.source_browse_apply))
                }
            }
        }
    }
}
