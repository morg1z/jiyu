package com.haise.jiyu.ui.comickhome

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImagePainter
import kotlinx.coroutines.delay
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import com.haise.jiyu.R
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.comick.ChapterUpdate
import com.haise.jiyu.source.comick.ReviewItem
import com.haise.jiyu.source.comick.TopFeed
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.components.JiyuWordmark
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Book
import compose.icons.tablericons.Flame
import compose.icons.tablericons.Search
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Sun
import compose.icons.tablericons.X
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ComicKHomeScreen(
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenSection: (section: String, window: String?, title: String) -> Unit,
    viewModel: ComicKHomeViewModel = hiltViewModel(),
    // Hledani primo na Domu (uzivatelsky pozadavek - lupa driv otevirala Prochazet
    // se vsemi filtry, coz uz dela samostatne tlacitko "Procházet" vedle "Domů";
    // lupa ma zustat na miste a jen pustit rychle hledani nazvu). Znovupouziva
    // ComicKBrowseViewModelu presne stejnou query/results/openManga logiku, jen
    // se vykresluje tady inline misto na vlastni obrazovce.
    searchViewModel: ComicKBrowseViewModel = hiltViewModel(),
) {
    val topFeed by viewModel.topFeed.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val showCompleted by viewModel.showCompleted.collectAsState()
    val popularNewWindow by viewModel.popularNewWindow.collectAsState()
    val mostRecentPopularWindow by viewModel.mostRecentPopularWindow.collectAsState()
    val openingManga by viewModel.openingManga.collectAsState()
    val openError by viewModel.openError.collectAsState()
    var showPreferences by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }

    val searchQuery by searchViewModel.query.collectAsState()
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotBlank()) {
            delay(450)
            searchViewModel.search()
        }
    }
    val searchResults by searchViewModel.results.collectAsState()
    val searchLoading by searchViewModel.loading.collectAsState()
    val searchError by searchViewModel.error.collectAsState()
    val searchOpeningManga by searchViewModel.openingManga.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openError) {
        openError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearOpenError()
        }
    }

    val searchOpenError by searchViewModel.openError.collectAsState()
    LaunchedEffect(searchOpenError) {
        searchOpenError?.let {
            snackbarHostState.showSnackbar(it)
            searchViewModel.clearOpenError()
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) searchFocusRequester.requestFocus()
    }

    fun openManga(manga: SManga) {
        viewModel.openManga(manga, onOpenManga)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().background(screenGradient).padding(innerPadding),
        ) {
            if (searchActive) {
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                val searchListState = rememberLazyListState()
                val headerContent: @Composable () -> Unit = {
                    ComicKHomeHeader(
                        searchActive = true,
                        searchQuery = searchQuery,
                        searchFocusRequester = searchFocusRequester,
                        onBackFromSearch = {
                            searchActive = false
                            searchViewModel.setQuery("")
                            searchViewModel.search()
                        },
                        onQueryChange = { searchViewModel.setQuery(it) },
                        onSearchSubmit = { searchViewModel.search() },
                        onClearQuery = { searchViewModel.setQuery(""); searchViewModel.search() },
                        onSearchIconClick = {},
                        onTitleLongPress = {},
                        onOpenBrowse = onOpenBrowse,
                    )
                }
                // Vysledky hledani jsou kompaktni radky (nahled + nazev + pocet kapitol),
                // stejne jako na ComicK Prochazet (viz ComicKSearchResultRow) - ne mrizka
                // velkych karet, aby se dalo rychle prochazet vic shod najednou, stejne
                // jako to dela vyhledavaci napoveda primo na comick.io.
                LazyColumn(
                    state = searchListState,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp + navBottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item { headerContent() }
                    when {
                        searchLoading && searchResults.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) { JiyuLoadingIndicator() }
                        }
                        searchError != null && searchResults.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                                    Text(stringResource(R.string.comick_home_load_failed), color = TextSecondary, fontSize = 14.sp)
                                    Text(searchError ?: "", color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                                    OutlinedButton(onClick = { searchViewModel.search() }) { Text(stringResource(R.string.common_retry), color = Violet) }
                                }
                            }
                        }
                        searchQuery.isBlank() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.comick_home_search_hint), color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        searchResults.isEmpty() -> item {
                            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.comick_home_empty), color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                        else -> items(searchResults, key = { it.sourceId + it.url }) { manga ->
                            ComicKSearchResultRow(manga = manga, onClick = { searchViewModel.openManga(manga, onOpenManga) })
                        }
                    }
                }
            } else {
            val headerContent: @Composable () -> Unit = {
                ComicKHomeHeader(
                    searchActive = false,
                    searchQuery = searchQuery,
                    searchFocusRequester = searchFocusRequester,
                    onBackFromSearch = {},
                    onQueryChange = {},
                    onSearchSubmit = {},
                    onClearQuery = {},
                    onSearchIconClick = { searchActive = true },
                    onTitleLongPress = { showPreferences = true },
                    onOpenBrowse = onOpenBrowse,
                )
            }
            when {
                loading -> Column(Modifier.fillMaxSize()) {
                    headerContent()
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            JiyuLoadingIndicator()
                            Text(stringResource(R.string.comick_home_loading), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
                error != null -> Column(Modifier.fillMaxSize()) {
                    headerContent()
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                            Text(stringResource(R.string.comick_home_load_failed), color = TextSecondary, fontSize = 14.sp)
                            Text(error ?: "", color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                            OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.common_retry), color = Violet) }
                        }
                    }
                }
                else -> {
                    val feed = topFeed
                    if (feed == null) {
                        Column(Modifier.fillMaxSize()) {
                            headerContent()
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(stringResource(R.string.comick_home_empty), color = TextSecondary, fontSize = 14.sp)
                            }
                        }
                    } else {
                        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        val updates by viewModel.updates.collectAsState()
                        val updatesOrder by viewModel.updatesOrder.collectAsState()
                        val updatesLoading by viewModel.updatesLoading.collectAsState()
                        val updatesError by viewModel.updatesError.collectAsState()
                        val homeListState = rememberLazyListState()

                        // Aktualizace na Domu uz nejsou oreznuty nahled s "Zobrazit vse" -
                        // uzivatelsky pozadavek: maji se nacitat vsechny, rovnou tady dole,
                        // stejne jako na vlastni zalozce Aktualizace.
                        val shouldLoadMoreUpdates by remember {
                            derivedStateOf {
                                val lastVisible = homeListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                                val totalItems = homeListState.layoutInfo.totalItemsCount
                                lastVisible >= totalItems - 4 && totalItems > 0
                            }
                        }
                        LaunchedEffect(shouldLoadMoreUpdates) {
                            if (shouldLoadMoreUpdates && !updatesLoading) viewModel.loadMoreUpdates()
                        }

                        LazyColumn(state = homeListState, contentPadding = PaddingValues(bottom = 16.dp + navBottom)) {
                            item { headerContent() }
                            item {
                                val recentlyAddedLabel = stringResource(R.string.comick_home_recently_added)
                                val completedLabel = stringResource(R.string.comick_home_completed)
                                ToggleSection(
                                    leftLabel = recentlyAddedLabel,
                                    rightLabel = completedLabel,
                                    rightSelected = showCompleted,
                                    onToggle = { viewModel.setShowCompleted(it) },
                                    comics = (if (showCompleted) feed.completed else feed.recentlyAdded).take(15),
                                    onOpenManga = ::openManga,
                                    onViewAll = { onOpenSection(if (showCompleted) "completed" else "recently_added", null, if (showCompleted) completedLabel else recentlyAddedLabel) },
                                )
                            }
                            item {
                                val label = stringResource(R.string.comick_home_popular_new)
                                WindowSection(
                                    title = label,
                                    window = popularNewWindow,
                                    onWindowChange = { viewModel.setPopularNewWindow(it) },
                                    comics = feed.popularNew[popularNewWindow].orEmpty().take(15),
                                    onOpenManga = ::openManga,
                                    onViewAll = { onOpenSection("popular_new", popularNewWindow, label) },
                                )
                            }
                            item {
                                val label = stringResource(R.string.comick_home_most_recent_popular)
                                WindowSection(
                                    title = label,
                                    window = mostRecentPopularWindow,
                                    onWindowChange = { viewModel.setMostRecentPopularWindow(it) },
                                    comics = feed.mostRecentPopular[mostRecentPopularWindow].orEmpty().take(15),
                                    onOpenManga = ::openManga,
                                    onViewAll = { onOpenSection("most_recent_popular", mostRecentPopularWindow, label) },
                                )
                            }
                            item {
                                val label = stringResource(R.string.comick_home_recent_reviews)
                                ReviewSection(
                                    reviews = feed.recentReviews.take(15),
                                    onOpenManga = ::openManga,
                                    onViewAll = { onOpenSection("recent_reviews", null, label) },
                                )
                            }
                            item {
                                UpdatesFeedHeader(
                                    order = updatesOrder,
                                    onOrderChange = { viewModel.setUpdatesOrder(it) },
                                    onOpenPreferences = { showPreferences = true },
                                )
                            }
                            if (updatesError != null) {
                                item {
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                        Text(stringResource(R.string.comick_home_load_failed), color = TextSecondary, fontSize = 13.sp)
                                        Text(updatesError ?: "", color = TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                                        OutlinedButton(onClick = { viewModel.retryUpdates() }) { Text(stringResource(R.string.common_retry), color = Violet) }
                                    }
                                }
                            } else if (updates.isEmpty() && !updatesLoading) {
                                item {
                                    Text(
                                        stringResource(R.string.comick_home_empty),
                                        color = TextSecondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    )
                                }
                            } else {
                                items(
                                    updates.chunked(2),
                                    key = { pair -> pair.joinToString("|") { it.chapter.sourceId + it.chapter.url } },
                                ) { rowItems ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        rowItems.forEach { update ->
                                            UpdateGridCard(update = update, onClick = { openManga(update.comic) }, modifier = Modifier.weight(1f))
                                        }
                                        if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                                    }
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
            }
            }
            if (openingManga != null || searchOpeningManga != null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    JiyuLoadingIndicator()
                }
            }
        }
    }

    if (showPreferences) {
        val updatesCountries by viewModel.updatesCountries.collectAsState()
        val updatesDemographics by viewModel.updatesDemographics.collectAsState()
        val updatesMatureFlags by viewModel.updatesMatureFlags.collectAsState()
        val showAdultContent by viewModel.showAdultContent.collectAsState()
        ComicKUpdatesPreferencesSheet(
            initialCountries = updatesCountries,
            initialDemographics = updatesDemographics,
            initialMatureFlags = updatesMatureFlags,
            showAdultContent = showAdultContent,
            onDismiss = { showPreferences = false },
            onApply = { countries, demographics, matureFlags ->
                viewModel.setUpdatesPreferences(countries, demographics, matureFlags)
                showPreferences = false
            },
        )
    }
}

@Composable
private fun ComicKHomeHeader(
    searchActive: Boolean,
    searchQuery: String,
    searchFocusRequester: FocusRequester,
    onBackFromSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onClearQuery: () -> Unit,
    onSearchIconClick: () -> Unit,
    onTitleLongPress: () -> Unit,
    onOpenBrowse: () -> Unit,
) {
    Column(modifier = Modifier.statusBarsPadding()) {
        if (searchActive) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBackFromSearch) {
                    Icon(TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back), tint = TextPrimary)
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (searchQuery.isNotEmpty()) Violet.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearchSubmit() }),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                if (searchQuery.isEmpty()) {
                                    Text(stringResource(R.string.comick_browse_search_placeholder), color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).focusRequester(searchFocusRequester),
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = onClearQuery, modifier = Modifier.size(28.dp)) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                JiyuWordmark(
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) { detectTapGestures(onLongPress = { onTitleLongPress() }) },
                )
                IconButton(onClick = onSearchIconClick) {
                    Icon(TablerIcons.Search, contentDescription = stringResource(R.string.common_search), tint = TextSecondary)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // "Domu" uz neni prepinatelna zalozka (Aktualizace je od teď rovnou
                // soucasti Domu, viz uzivatelsky pozadavek) - zustava jako vizualni
                // "jsi tady" indikator. Misto Aktualizace je tlacitko na Prochazet
                // (search+filtry obrazovka, viz ComicKBrowseScreen).
                Button(
                    onClick = {},
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Violet.copy(alpha = 0.2f),
                        contentColor = Violet,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.5f)),
                    elevation = null,
                ) { Text(stringResource(R.string.comick_home_tab_home), fontSize = 13.sp) }
                Button(
                    onClick = onOpenBrowse,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = TextSecondary,
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TextSecondary.copy(alpha = 0.15f)),
                    elevation = null,
                ) { Text(stringResource(R.string.main_screen_tab_browse), fontSize = 13.sp) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onViewAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.comick_home_view_all),
            color = Violet,
            fontSize = 12.sp,
            modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onViewAll() }) },
        )
    }
}

@Composable
private fun ToggleSection(
    leftLabel: String,
    rightLabel: String,
    rightSelected: Boolean,
    onToggle: (Boolean) -> Unit,
    comics: List<SManga>,
    onOpenManga: (SManga) -> Unit,
    onViewAll: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                Text(
                    leftLabel, color = if (!rightSelected) TextPrimary else TextSecondary.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggle(false) }) },
                )
                Text("/", color = TextSecondary, fontSize = 16.sp)
                Text(
                    rightLabel, color = if (rightSelected) TextPrimary else TextSecondary.copy(alpha = 0.5f),
                    fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onToggle(true) }) },
                )
            }
            Text(
                stringResource(R.string.comick_home_view_all), color = Violet, fontSize = 12.sp,
                modifier = Modifier.pointerInput(Unit) { detectTapGestures(onTap = { onViewAll() }) },
            )
        }
        MangaRow(comics, onOpenManga)
    }
}

@Composable
private fun WindowSection(
    title: String,
    window: String,
    onWindowChange: (String) -> Unit,
    comics: List<SManga>,
    onOpenManga: (SManga) -> Unit,
    onViewAll: () -> Unit,
) {
    Column {
        SectionHeader(title, onViewAll)
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf("7" to R.string.comick_home_window_7d, "30" to R.string.comick_home_window_30d, "90" to R.string.comick_home_window_90d).forEach { (value, labelRes) ->
                val selected = window == value
                Text(
                    stringResource(labelRes),
                    color = if (selected) Violet else TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Violet.copy(alpha = 0.15f) else Color.Transparent)
                        .pointerInput(value) { detectTapGestures(onTap = { onWindowChange(value) }) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        MangaRow(comics, onOpenManga)
    }
}

@Composable
private fun MangaRow(comics: List<SManga>, onOpenManga: (SManga) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(comics, key = { it.sourceId + it.url }) { manga ->
            ComicKMangaCard(manga = manga, onClick = { onOpenManga(manga) })
        }
    }
}

@Composable
private fun ReviewSection(reviews: List<ReviewItem>, onOpenManga: (SManga) -> Unit, onViewAll: () -> Unit) {
    Column {
        SectionHeader(stringResource(R.string.comick_home_recent_reviews), onViewAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(reviews, key = { it.comic.sourceId + it.comic.url + it.content.hashCode() }) { review ->
                ReviewCard(review = review, onClick = { onOpenManga(review.comic) })
            }
        }
    }
}

/**
 * Nadpis + Hot/New přepínač pro Aktualizace přímo na Domů. Feed pod ním se
 * teď načítá celý (nekonečné scrollování ve stejném LazyColumn jako zbytek
 * Domů, viz [ComicKHomeScreen]) - uživatelský požadavek, žádné "Zobrazit vše"
 * omezení, protože už nic dalšího není kam zobrazit.
 */
@Composable
private fun UpdatesFeedHeader(order: String, onOrderChange: (String) -> Unit, onOpenPreferences: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.comick_home_tab_updates),
            color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            UpdatesOrderChip(
                label = stringResource(R.string.source_browse_popular),
                icon = TablerIcons.Flame,
                selectedColor = Color(0xFFF97316),
                selected = order == "hot",
                onClick = { onOrderChange("hot") },
            )
            UpdatesOrderChip(
                label = stringResource(R.string.source_browse_latest),
                icon = TablerIcons.Sun,
                selectedColor = Violet,
                selected = order == "new",
                onClick = { onOrderChange("new") },
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenPreferences, modifier = Modifier.size(32.dp)) {
                Icon(TablerIcons.Settings, contentDescription = stringResource(R.string.comick_prefs_button), tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun UpdatesOrderChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selectedColor: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(if (selected) selectedColor.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) selectedColor.copy(alpha = 0.5f) else TextSecondary.copy(alpha = 0.15f), RoundedCornerShape(50.dp))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) selectedColor else TextSecondary, modifier = Modifier.size(14.dp))
        Text(
            label,
            color = if (selected) selectedColor else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
internal fun ReviewCard(review: ReviewItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowCyan.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(12.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
                ) {
                    SubcomposeAsyncImage(
                        model = review.comic.coverUrl,
                        contentDescription = review.comic.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val state = painter.state
                        if (review.comic.coverUrl.isNullOrBlank() || state is AsyncImagePainter.State.Error) {
                            Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1526)))
                        } else {
                            SubcomposeAsyncImageContent()
                        }
                    }
                }
                Text(
                    review.comic.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (review.title != null) {
                Text(review.title, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
            Text(review.content, color = TextSecondary, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (review.authorName != null) {
                Text(stringResource(R.string.comick_home_review_by, review.authorName), color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Stejný vizuální střih jako `GroupScreen.GroupTitleCard`/`SourceBrowseScreen.BrowseMangaCard`, jen fixní šířka pro LazyRow místo mřížky (karty se v kódu nesdílí mezi soubory, zavedená konvence). */
@Composable
internal fun ComicKMangaCard(manga: SManga, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "comick_manga_card_scale",
    )

    Box(
        modifier = Modifier
            .width(110.dp)
            .aspectRatio(0.74f)
            .scale(scale)
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
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1526)), contentAlignment = Alignment.Center) {
                    Icon(TablerIcons.Book, contentDescription = null, tint = TextSecondary.copy(alpha = 0.3f), modifier = Modifier.size(32.dp))
                }
            } else {
                SubcomposeAsyncImageContent()
            }
        }
        Box(
            modifier = Modifier.fillMaxWidth().height(56.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(colors = listOf(Color.Transparent, Color(0xEA070B14)))),
        )
        Text(
            text = manga.title, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 12.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 5.dp),
        )
    }
}

/** "před 2 h", "před 3 dny" apod. - ComicK styl relativniho casu misto data. */
private fun relativeTimeLabel(uploadMs: Long): String {
    if (uploadMs <= 0L) return ""
    val diffMin = (System.currentTimeMillis() - uploadMs) / 60_000L
    return when {
        diffMin < 1     -> "teď"
        diffMin < 60    -> "před ${diffMin} min"
        diffMin < 1440  -> "před ${diffMin / 60} h"
        diffMin < 43200 -> "před ${diffMin / 1440} dny"
        else            -> SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(Date(uploadMs))
    }
}

/** "318.0" -> "318", "318.5" -> "318.5" - stejny vzor jako na detailu titulu. */
private fun chapterNumLabel(n: Float): String =
    if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()

/**
 * Aktualizace jako mřížka obálek (ComicK styl - uživatelský požadavek "abych
 * viděl lépe ty covery"), místo dřívějších úzkých textových řádků.
 */
@Composable
private fun UpdateGridCard(update: ChapterUpdate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.74f)
                .clip(RoundedCornerShape(10.dp)),
        ) {
            SubcomposeAsyncImage(
                model = update.comic.coverUrl,
                contentDescription = update.comic.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            ) {
                val state = painter.state
                if (update.comic.coverUrl.isNullOrBlank() || state is AsyncImagePainter.State.Error) {
                    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF0D1526)))
                } else {
                    SubcomposeAsyncImageContent()
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Ch.${chapterNumLabel(update.chapter.chapterNumber)}",
                color = Violet, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (update.upCount > 0) Text("${update.upCount}▲", color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp)
                if (update.commentCount > 0) Text("${update.commentCount}💬", color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp)
            }
        }
        Text(relativeTimeLabel(update.chapter.dateUpload), color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
        val groupName = update.chapter.scanlationGroup
        if (!groupName.isNullOrBlank()) {
            Text(groupName, color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            update.comic.title, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 14.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
