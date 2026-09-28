package com.haise.jiyu.ui.novelhome

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.haise.jiyu.R
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.LatestTitleStyle
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.PopularTitleStyle
import com.haise.jiyu.ui.theme.SectionTitleStyle
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.UpdatesTitleStyle
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.aggregateSectionTitleStyle
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.violetGlow
import com.haise.jiyu.util.AggregateSection
import com.haise.jiyu.util.deriveAggregateSections
import com.haise.jiyu.util.isCompletedStatus
import com.haise.jiyu.util.relativeTimeLabel
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Bolt
import compose.icons.tablericons.Book
import compose.icons.tablericons.Clock
import compose.icons.tablericons.Flame
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Search
import compose.icons.tablericons.X

// Gradientni nadpisy sekci jsou sdilene pres ui/theme/SectionHeading.kt
// (vizualni vzor ComicK webu) - stejna paleta pro Novel/Komiks/ComicK Domu.

/**
 * Novela Domů - stejný layout jako ComicKHomeScreen (uživatelský požadavek):
 * hlavička s lupou (inline hledání místo permanentního pole), pilly
 * Domů/Katalog, na Domů horizontální sekce Populární/Nejnovější a pod ní
 * feed Aktualizace (nejnovější kapitoly ve 2sl. mřížce obálek). Katalog
 * je dosavadní sjednocená mřížka napříč zdroji.
 */
@Composable
fun NovelHomeScreen(
    onOpenTitle: (title: String) -> Unit,
    viewModel: NovelHomeViewModel = hiltViewModel(),
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val latestItems by viewModel.latestItems.collectAsStateWithLifecycle()
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val updatesLoading by viewModel.updatesLoading.collectAsStateWithLifecycle()
    val latestLoading by viewModel.latestLoading.collectAsStateWithLifecycle()
    val showLatest by viewModel.showLatest.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val failedSources by viewModel.failedSources.collectAsStateWithLifecycle()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var catalogMode by rememberSaveable { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf<NovelCatalogFilter?>(null) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            if (searchActive) {
                // Inline hledání - stejný vzor jako ComicK Home: šipka zpět +
                // pill pole, výsledky jako kompaktní řádky (ne velké karty).
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                    ),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item {
                        NovelSearchHeader(
                            query = searchText,
                            focusRequester = searchFocusRequester,
                            onBack = {
                                searchActive = false
                                searchText = ""
                                viewModel.clearSearch()
                            },
                            onQueryChange = { searchText = it },
                            onSubmit = { viewModel.search(searchText) },
                            onClear = {
                                searchText = ""
                                viewModel.clearSearch()
                            },
                        )
                    }
                    when {
                        searching && items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                JiyuLoadingIndicator()
                            }
                        }
                        searchText.isBlank() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.novel_home_search_hint), color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        items.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.novel_home_empty), color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        else -> {
                            if (searching) {
                                item {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                        color = GlowCyan,
                                        trackColor = NightBlue,
                                    )
                                }
                            }
                            items(items, key = { it.key }) { entry ->
                                NovelSearchResultRow(entry = entry, onClick = { onOpenTitle(entry.representative.title) })
                            }
                        }
                    }
                }
            } else {
                // Hlavicka je prvni polozka lazy obsahu - scrolluje pryc s feedem
                // (stejny vzor jako ComicK Home), nezustava pripnuta nahore.
                val headerContent: @Composable () -> Unit = {
                    NovelHomeHeader(
                        catalogMode = catalogMode,
                        onModeChange = { catalogMode = it },
                        onRefresh = { viewModel.loadFeed(force = true) },
                        onSearchClick = { searchActive = true },
                    )
                }
                if (catalogMode) {
                    NovelCatalog(
                        headerContent = headerContent,
                        items = if (showLatest) latestItems else items,
                        showLatest = showLatest,
                        onShowLatest = { viewModel.setShowLatest(it) },
                        loading = loading || latestLoading,
                        failedSources = failedSources,
                        filter = catalogFilter,
                        onClearFilter = { catalogFilter = null },
                        onOpenTitle = onOpenTitle,
                        onRetry = { viewModel.loadFeed(force = true) },
                    )
                } else {
                    NovelHomeFeed(
                        headerContent = headerContent,
                        popular = items,
                        latest = latestItems,
                        showLatest = showLatest,
                        onShowLatest = { viewModel.setShowLatest(it) },
                        popularLoading = loading,
                        latestLoading = latestLoading,
                        updates = updates,
                        updatesLoading = updatesLoading,
                        onOpenTitle = onOpenTitle,
                        onShowAll = { section ->
                            catalogFilter = when (section.kind) {
                                AggregateSection.Kind.COMPLETED -> NovelCatalogFilter.Completed
                                AggregateSection.Kind.GENRE -> section.genre?.let { NovelCatalogFilter.Genre(it.lowercase(), it) }
                                AggregateSection.Kind.LONGEST -> null
                            }
                            catalogMode = true
                        },
                    )
                }
            }
        }
    }
}

// ── Hlavička ────────────────────────────────────────────────────────────────

@Composable
private fun NovelHomeHeader(
    catalogMode: Boolean,
    onModeChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSearchClick: () -> Unit,
) {
    Column(modifier = Modifier.statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.novel_home_title),
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRefresh) {
                Icon(TablerIcons.Refresh, contentDescription = stringResource(R.string.novel_home_retry), tint = TextSecondary)
            }
            IconButton(onClick = onSearchClick) {
                Icon(TablerIcons.Search, contentDescription = stringResource(R.string.common_search), tint = TextSecondary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { onModeChange(false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (!catalogMode) Violet.copy(alpha = 0.2f) else Color.Transparent,
                    contentColor = if (!catalogMode) Violet else TextSecondary,
                ),
                border = BorderStroke(1.dp, if (!catalogMode) Violet.copy(alpha = 0.5f) else TextSecondary.copy(alpha = 0.15f)),
                elevation = null,
            ) { Text(stringResource(R.string.comick_home_tab_home), fontSize = 13.sp) }
            Button(
                onClick = { onModeChange(true) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (catalogMode) Violet.copy(alpha = 0.2f) else Color.Transparent,
                    contentColor = if (catalogMode) Violet else TextSecondary,
                ),
                border = BorderStroke(1.dp, if (catalogMode) Violet.copy(alpha = 0.5f) else TextSecondary.copy(alpha = 0.15f)),
                elevation = null,
            ) { Text(stringResource(R.string.novel_home_tab_catalog), fontSize = 13.sp) }
        }
    }
}

@Composable
private fun NovelSearchHeader(
    query: String,
    focusRequester: FocusRequester,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back), tint = TextPrimary)
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, if (query.isNotEmpty()) Violet.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                cursorBrush = SolidColor(Violet),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        if (query.isEmpty()) {
                            Text(stringResource(R.string.novel_home_search_hint), color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                        }
                        inner()
                    }
                },
                modifier = Modifier.weight(1f).focusRequester(focusRequester),
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(15.dp))
                }
            }
        }
    }
}

// ── Domů: sekce + Aktualizace ───────────────────────────────────────────────

/** Aktivní filtr Katalogu - nastavuje "Zobrazit vše" odvozených sekcí Domů. */
private sealed interface NovelCatalogFilter {
    data class Genre(val key: String, val label: String) : NovelCatalogFilter
    object Completed : NovelCatalogFilter
}

private fun NovelCatalogEntry.matchesFilter(filter: NovelCatalogFilter): Boolean = when (filter) {
    is NovelCatalogFilter.Genre -> representative.genres.any { it.trim().lowercase() == filter.key }
    NovelCatalogFilter.Completed -> representative.status?.let { isCompletedStatus(it) } == true
}

@Composable
private fun NovelHomeFeed(
    headerContent: @Composable () -> Unit,
    popular: List<NovelCatalogEntry>,
    latest: List<NovelCatalogEntry>,
    showLatest: Boolean,
    onShowLatest: (Boolean) -> Unit,
    popularLoading: Boolean,
    latestLoading: Boolean,
    updates: List<NovelChapterUpdate>,
    updatesLoading: Boolean,
    onOpenTitle: (String) -> Unit,
    onShowAll: (AggregateSection<NovelCatalogEntry>) -> Unit,
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Sekce stavíme z obou sweepů (popular je reprezentant s lepším rankem) -
    // bez dalších requestů: žánry/status nosí listingy zdrojů samy.
    // excludeKeys = tituly horní přepínačové řady, ať se neopakují v sekcích.
    val sections = remember(popular, latest, showLatest) {
        val union = (popular + latest).distinctBy { it.key }
        val heroKeys = (if (showLatest) latest else popular).take(15).map { it.key }.toSet()
        deriveAggregateSections(union, excludeKeys = heroKeys, key = { it.key }) { it.representative }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp + navBottom), modifier = Modifier.fillMaxSize()) {
        item { headerContent() }
        item {
            NovelToggleSection(
                leftLabel = stringResource(R.string.source_browse_popular),
                rightLabel = stringResource(R.string.source_browse_latest),
                rightSelected = showLatest,
                onToggle = onShowLatest,
                entries = (if (showLatest) latest else popular).take(15),
                loading = if (showLatest) latestLoading else popularLoading,
                onOpenTitle = onOpenTitle,
            )
        }
        sections.forEach { section ->
            item(key = "section_${section.kind}_${section.genre}") {
                NovelDerivedSection(
                    title = when (section.kind) {
                        AggregateSection.Kind.COMPLETED -> stringResource(R.string.comick_home_completed)
                        AggregateSection.Kind.LONGEST -> stringResource(R.string.aggregate_section_longest)
                        AggregateSection.Kind.GENRE -> section.genre.orEmpty()
                    },
                    titleStyle = aggregateSectionTitleStyle(section),
                    entries = section.items,
                    // LONGEST nema katalogovy protejsek (katalog neradi podle poctu
                    // kapitol) - "Zobrazit vse" tam schovame.
                    onShowAll = if (section.kind == AggregateSection.Kind.LONGEST) null else ({ onShowAll(section) }),
                    onOpenTitle = onOpenTitle,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(TablerIcons.Bolt, contentDescription = null, tint = UpdatesTitleStyle.iconTint, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(R.string.comick_home_tab_updates),
                    style = TextStyle(brush = UpdatesTitleStyle.brush, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                )
            }
        }
        if (updates.isEmpty() && updatesLoading) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    JiyuLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                    Text(
                        stringResource(R.string.novel_home_updates_loading),
                        color = TextSecondary, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
        } else if (updates.isEmpty() && !latestLoading && !popularLoading) {
            item {
                Text(
                    stringResource(R.string.novel_home_empty),
                    color = TextSecondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        } else {
            items(updates.chunked(2), key = { row -> row.joinToString("|") { it.key } }) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowItems.forEach { update ->
                        NovelUpdateCard(update = update, onClick = { onOpenTitle(update.manga.title) }, modifier = Modifier.weight(1f))
                    }
                    if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (updatesLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        JiyuLoadingIndicator(size = 24.dp, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/** Sekce s přepínačem Populární/Nejnovější - obdoba ToggleSection z ComicK Home
 * (Nově přidané/Dokončené), tady přepíná dva agregované sweepy. */
@Composable
private fun NovelToggleSection(
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    entries: List<NovelCatalogEntry>,
    loading: Boolean,
    onOpenTitle: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                // ComicK-styl: aktivni label nese gradient + ikonku, neaktivni ztlumi.
                Icon(
                    TablerIcons.Flame, contentDescription = null,
                    tint = if (!rightSelected) PopularTitleStyle.iconTint else TextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(16.dp),
                )
                if (!rightSelected) {
                    Text(
                        leftLabel,
                        style = TextStyle(brush = PopularTitleStyle.brush, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggle(false) }) },
                    )
                } else {
                    Text(
                        leftLabel, color = TextSecondary.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggle(false) }) },
                    )
                }
                Text("/", color = TextSecondary, fontSize = 16.sp)
                if (rightSelected) {
                    Text(
                        rightLabel,
                        style = TextStyle(brush = LatestTitleStyle.brush, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggle(true) }) },
                    )
                } else {
                    Text(
                        rightLabel, color = TextSecondary.copy(alpha = 0.5f),
                        fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggle(true) }) },
                    )
                }
                Icon(
                    TablerIcons.Clock, contentDescription = null,
                    tint = if (rightSelected) LatestTitleStyle.iconTint else TextSecondary.copy(alpha = 0.4f),
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        if (entries.isEmpty() && loading) {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                JiyuLoadingIndicator(size = 24.dp, strokeWidth = 2.dp)
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    NovelSectionCard(entry = entry, onClick = { onOpenTitle(entry.representative.title) })
                }
            }
        }
    }
}

/** Jednoduchá horizontální sekce bez přepínače - nadpis + "Zobrazit vše",
 * vizuálně stejná stopa jako ComicK Home sekce. */
@Composable
private fun NovelDerivedSection(
    title: String,
    entries: List<NovelCatalogEntry>,
    titleStyle: SectionTitleStyle,
    onShowAll: (() -> Unit)?,
    onOpenTitle: (String) -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (titleStyle.icon != null) {
                Icon(
                    titleStyle.icon, contentDescription = null,
                    tint = titleStyle.iconTint, modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = TextStyle(brush = titleStyle.brush, fontWeight = FontWeight.Bold, fontSize = 16.sp),
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (onShowAll != null) {
                Text(
                    stringResource(R.string.comick_home_view_all),
                    color = Violet, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .pointerInput(title) { detectTapGestures(onTap = { onShowAll() }) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { "${title}_${it.key}" }) { entry ->
                NovelSectionCard(entry = entry, onClick = { onOpenTitle(entry.representative.title) })
            }
        }
    }
}

// ── Katalog (dosavadní sjednocená mřížka) ────────────────────────────────────

@Composable
private fun NovelCatalog(
    headerContent: @Composable () -> Unit,
    items: List<NovelCatalogEntry>,
    showLatest: Boolean,
    onShowLatest: (Boolean) -> Unit,
    loading: Boolean,
    failedSources: Int,
    filter: NovelCatalogFilter?,
    onClearFilter: () -> Unit,
    onOpenTitle: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val visible = remember(items, filter) {
        if (filter == null) items else items.filter { it.matchesFilter(filter) }
    }
    when {
        visible.isEmpty() && loading -> Column(Modifier.fillMaxSize()) {
            headerContent()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    JiyuLoadingIndicator()
                    Text(
                        stringResource(R.string.novel_home_loading),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        visible.isEmpty() -> Column(Modifier.fillMaxSize()) {
            headerContent()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.novel_home_empty), color = TextSecondary, fontSize = 14.sp)
                    Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
                        Text(stringResource(R.string.novel_home_retry))
                    }
                }
            }
        }
        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(
                start = 12.dp,
                top = 4.dp,
                end = 12.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                headerContent()
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.source_browse_popular),
                        color = if (!showLatest) Violet else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (!showLatest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (!showLatest) Violet.copy(alpha = 0.15f) else Color.Transparent)
                            .pointerInput("pop") { detectTapGestures(onTap = { onShowLatest(false) }) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                    Text(
                        stringResource(R.string.source_browse_latest),
                        color = if (showLatest) Violet else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (showLatest) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (showLatest) Violet.copy(alpha = 0.15f) else Color.Transparent)
                            .pointerInput("lat") { detectTapGestures(onTap = { onShowLatest(true) }) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            if (filter != null) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Violet.copy(alpha = 0.12f))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = when (filter) {
                                is NovelCatalogFilter.Genre -> filter.label
                                NovelCatalogFilter.Completed -> stringResource(R.string.comick_home_completed)
                            },
                            color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            TablerIcons.X,
                            contentDescription = stringResource(R.string.common_clear),
                            tint = Violet,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .pointerInput(filter) { detectTapGestures(onTap = { onClearFilter() }) },
                        )
                    }
                }
            }
            if (loading) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        color = GlowCyan,
                        trackColor = NightBlue,
                    )
                }
            }
            if (failedSources > 0) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    Text(
                        pluralStringResource(R.plurals.novel_failed_sources, failedSources, failedSources),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            items(visible, key = { it.key }) { entry ->
                NovelCatalogCard(
                    entry = entry,
                    onClick = { onOpenTitle(entry.representative.title) },
                )
            }
        }
    }
}

// ── Karty ───────────────────────────────────────────────────────────────────

private fun novelCoverModel(context: android.content.Context, url: String?, referer: String?): ImageRequest =
    ImageRequest.Builder(context)
        .data(url)
        .apply { if (!referer.isNullOrBlank()) addHeader("Referer", referer) }
        .build()

/** "318.0" -> "318", "318.5" -> "318.5" - stejny vzor jako ComicK UpdateGridCard. */
private fun chapterNumLabel(n: Float): String =
    if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()

/** Fixní šířka pro horizontální sekce - jinak stejná karta jako NovelCatalogCard. */
@Composable
private fun NovelSectionCard(entry: NovelCatalogEntry, onClick: () -> Unit) {
    Box(modifier = Modifier.width(110.dp)) {
        NovelCatalogCard(entry = entry, onClick = onClick)
    }
}

/** Karta Aktualizace - vizuální obdoba UpdateGridCard z ComicK Home: velká
 * obálka, "Ch.N" fialově, "před X h", název zdroje a název titulu. */
@Composable
private fun NovelUpdateCard(update: NovelChapterUpdate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, GlowCyan.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
        ) {
            SubcomposeAsyncImage(
                model = remember(update.manga.coverUrl, update.coverReferer) {
                    novelCoverModel(context, update.manga.coverUrl, update.coverReferer)
                },
                contentDescription = update.manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                val state = painter.state
                if (update.manga.coverUrl.isNullOrBlank() || state is AsyncImagePainter.State.Error) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1526)), contentAlignment = Alignment.Center) {
                        Icon(TablerIcons.Book, contentDescription = null, tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(32.dp))
                    }
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        }
        Text(
            // U novel je spolehlivější zobrazit name ("Chapter 123" apod.) -
            // chapterNumber u části zdrojů zůstává 0.
            text = if (update.chapterNumber > 0f) "Ch.${chapterNumLabel(update.chapterNumber)}" else update.chapterName,
            color = Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (update.dateUpload > 0L) {
            Text(relativeTimeLabel(update.dateUpload), color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        }
        Text(
            update.sourceName, color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(
            update.manga.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** Jeden řádek výsledku hledání - foto/název/počet zdrojů, obdoba
 * ComicKSearchResultRow (thumb musí nést Referer reprezentanta - viz NovelCatalogCard). */
@Composable
private fun NovelSearchResultRow(entry: NovelCatalogEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        verticalAlignment = Alignment.Top,
    ) {
        SubcomposeAsyncImage(
            model = remember(entry.representative.coverUrl, entry.coverReferer) {
                novelCoverModel(context, entry.representative.coverUrl, entry.coverReferer)
            },
            contentDescription = entry.representative.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(64.dp).height(90.dp).clip(RoundedCornerShape(8.dp)),
        ) {
            val state = painter.state
            if (entry.representative.coverUrl.isNullOrBlank() || state is AsyncImagePainter.State.Error) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1526)), contentAlignment = Alignment.Center) {
                    Icon(TablerIcons.Book, contentDescription = null, tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(20.dp))
                }
            } else {
                SubcomposeAsyncImageContent()
            }
        }
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = entry.representative.title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = pluralStringResource(R.plurals.novel_on_n_sources, entry.sourceCount, entry.sourceCount),
                color = GlowCyan,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (entry.sourceNames.isNotEmpty()) {
                Text(
                    text = entry.sourceNames.joinToString(" · "),
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun NovelCatalogCard(entry: NovelCatalogEntry, onClick: () -> Unit) {
    val manga = entry.representative
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "novel_card_scale",
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
                    onTap = { onClick() },
                )
            },
    ) {
        val coverContext = LocalContext.current
        SubcomposeAsyncImage(
            model = remember(manga.coverUrl, entry.coverReferer) {
                // Referer zdroje reprezentanta - stejny vzor jako BrowseMangaCard; bez nej
                // hodne novel hostu (LNWP/Madara weby) hotlink obalky blokuje a karta pada
                // na placeholder ikdyz coverUrl existuje.
                novelCoverModel(coverContext, manga.coverUrl, entry.coverReferer)
            },
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
                .height(84.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEA070B14)))),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 7.dp, vertical = 6.dp),
        ) {
            Text(
                text = manga.title,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp,
            )
            if (entry.sourceCount > 1) {
                Text(
                    text = pluralStringResource(R.plurals.novel_on_n_sources, entry.sourceCount, entry.sourceCount),
                    color = GlowCyan,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
