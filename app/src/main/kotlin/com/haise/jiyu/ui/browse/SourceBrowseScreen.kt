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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import com.haise.jiyu.R
import com.haise.jiyu.source.FilterTag
import com.haise.jiyu.source.MangaFilter
import com.haise.jiyu.source.MangaSource
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
    onOpenSourceWeb: (String) -> Unit = {},
    viewModel: SourceBrowseViewModel = hiltViewModel(),
) {
    val source            by viewModel.source.collectAsStateWithLifecycle()
    val results           by viewModel.results.collectAsStateWithLifecycle()
    val loading           by viewModel.loading.collectAsStateWithLifecycle()
    val error             by viewModel.error.collectAsStateWithLifecycle()
    val errorAction       by viewModel.errorAction.collectAsStateWithLifecycle()
    // Po návratu z webu zdroje (přihlášení / akce) se načtení zopakuje.
    var openedSourceWeb by remember { mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (openedSourceWeb) {
            openedSourceWeb = false
            viewModel.retry()
        }
    }
    val openingManga      by viewModel.openingManga.collectAsStateWithLifecycle()
    val openError         by viewModel.openError.collectAsStateWithLifecycle()
    val hasMore           by viewModel.hasMore.collectAsStateWithLifecycle()
    val activeFilter      by viewModel.activeFilter.collectAsStateWithLifecycle()
    val showLatest        by viewModel.showLatest.collectAsStateWithLifecycle()
    // rememberSaveable - rotace by jinak smazala rozpsany dotaz (audit).
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyGridState()
    var showFilterSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

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
            // Zdroj bez jakehokoli podporovaneho filtru - ikona Filtrovat by
            // otevrela prazdny sheet (hlasene "filtry = proste nic").
            showFilter = source?.let {
                it.supportsTagFilter || it.supportsStatusFilter || it.supportsYearFilter ||
                    it.availableSorts.size > 1
            } ?: true,
            query = query,
            // Hledani se spousti JEN potvrzenim (IME Search) - live debounce predtim
            // odstartoval dotaz uz po 1-3 smazanych pismenech a pri preklopeni stavu
            // (vysledky -> prazdne/error) se field z kompozice ztratil: klavesnice se
            // zavrela a query se poslalo bez potvrzeni (hlaseny bug). Prazdne pole =
            // navrat na popularni vypis, proto se search("") vola i pri vymazani.
            onQueryChange = { query = it; if (it.isBlank()) viewModel.search("") },
            onSearchSubmit = {
                viewModel.search(query)
                focusManager.clearFocus()
            },
            showLatest = showLatest,
            onSetShowLatest = { viewModel.setShowLatest(it) },
            showSortToggle = source?.availableSorts?.containsAll(listOf("popular", "latest")) != false,
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

        // JEDEN grid pro vsechny stavy - header je vzdycky item(0) na stejne
        // pozici kompozice, takze search field prezije preklopeni loading/error/
        // empty <-> results. Drive byly stavy 4 ruzne vetve "when" (Column vs
        // grid): pri prechodu Compose TextField zahodil a znovu vytvoril jinde,
        // cimz ztratil fokus a klavesnice se zavrela uprostred psani (hlaseny bug).
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 110.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + navBottom),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
            state = listState,
        ) {
            // Header musí vizuálně vyrušit mřížkový contentPadding (12dp z každé strany) -
            // Modifier.padding() zápornou hodnotu odmítá (Compose to
            // shodí s "Padding must be non-negative"), proto vlastní layout: změří obsah o
            // 24dp širší, než mřížka nabízí, a posune ho o 12dp doleva.
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
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

            when {
                loading && results.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "state") {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp),
                            contentAlignment = Alignment.Center,
                        ) { JiyuLoadingIndicator() }
                    }
                }
                error != null -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "state") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp).padding(32.dp),
                            verticalArrangement = Arrangement.Center,
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
                            // Akce podle typu chyby (Vyřešit ověření Cloudflare, nová adresa zdroje...).
                            val action = errorAction
                            if (action != null) {
                                OutlinedButton(
                                    onClick = {
                                        if (action is com.haise.jiyu.util.ErrorAction.OpenSourceWeb) {
                                            openedSourceWeb = true
                                            onOpenSourceWeb(action.url)
                                        } else {
                                            viewModel.performErrorAction()
                                        }
                                    },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    Text(
                                        when (action) {
                                            is com.haise.jiyu.util.ErrorAction.SolveCloudflare ->
                                                stringResource(R.string.error_action_solve_cloudflare)
                                            is com.haise.jiyu.util.ErrorAction.UseNewDomain ->
                                                stringResource(R.string.error_action_use_new_domain, action.host)
                                            is com.haise.jiyu.util.ErrorAction.OpenSourceWeb ->
                                                stringResource(R.string.error_action_open_web)
                                        },
                                        color = GlowViolet,
                                    )
                                }
                            }
                        }
                    }
                }
                results.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "state") {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
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
                else -> {
                    items(results, key = { it.sourceId + it.url }) { manga ->
                        val isOpening = openingManga?.let { it.sourceId == manga.sourceId && it.url == manga.url } == true
                        BrowseMangaCard(manga = manga, isLoading = isOpening, referer = source?.homepageUrl, onClick = {
                            viewModel.openManga(manga, onOpenManga)
                        }, onCoverMissing = { viewModel.fetchCoverIfMissing(manga) })
                    }
                    if (hasMore || loading) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "footer") {
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
            source = source,
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
    showFilter: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    showLatest: Boolean,
    onSetShowLatest: (Boolean) -> Unit,
    showSortToggle: Boolean = true,
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
            if (showFilter) {
                IconButton(onClick = onOpenFilterSheet) {
                    Icon(
                        imageVector = TablerIcons.Filter,
                        contentDescription = stringResource(R.string.source_browse_filters),
                        tint = if (activeFilter != MangaFilter()) Violet else TextSecondary,
                    )
                }
            }
        }

        // ── Hledání v rámci zdroje ────────────────────────────────────────────
        // Search se spusti az IME Search akci (ne na kazde pismeno) - X smaze
        // dotaz a vrati popularni vypis (vola onQueryChange("")), stejne jako
        // u ComicK browse.
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text(stringResource(R.string.source_browse_search_placeholder, sourceName), color = TextSecondary) },
            singleLine = true,
            leadingIcon = { Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary)
                    }
                }
            },
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
        // U zdroje, který obě záložky neumí seřadit jinak, by přepínač jen ukazoval totéž (viz supportsSortOrder).
        if (query.isBlank() && showSortToggle) {
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
private fun BrowseMangaCard(manga: SManga, isLoading: Boolean = false, referer: String? = null, onClick: () -> Unit, onCoverMissing: () -> Unit) {
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
        val coverContext = LocalContext.current
        SubcomposeAsyncImage(
            model = remember(manga.coverUrl, referer) {
                ImageRequest.Builder(coverContext)
                    .data(manga.coverUrl)
                    .apply { if (!referer.isNullOrBlank()) addHeader("Referer", referer) }
                    .build()
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
    source: MangaSource?,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    onApply: (MangaFilter) -> Unit,
) {
    var selectedStatus by remember { mutableStateOf(current.status) }
    var yearText by remember { mutableStateOf(current.year?.toString() ?: "") }
    var selectedSort by remember { mutableStateOf(current.sortBy) }
    var sortAscending by remember { mutableStateOf(current.sortAscending) }
    var sortDropdownExpanded by remember { mutableStateOf(false) }
    var selectedGenres by remember { mutableStateOf(current.genres) }
    var excludedGenres by remember { mutableStateOf(current.excludeGenres) }
    var selectedTags by remember { mutableStateOf(current.tags) }
    var excludedTags by remember { mutableStateOf(current.excludeTags) }
    var selectedDemographic by remember { mutableStateOf(current.demographic) }
    var selectedTypes by remember { mutableStateOf(current.comicTypes) }
    var minChaptersText by remember { mutableStateOf(current.minChapters?.toString() ?: "") }
    var selectedCreatedRange by remember { mutableStateOf(current.createdRangeDays) }
    var createdRangeExpanded by remember { mutableStateOf(false) }
    var showTagPicker by remember { mutableStateOf(false) }
    // Labely vybranych tagu se dohledaji az kdyz uzivatel otevre picker (getAvailableTags
    // muze delat network) - do te doby se ve shrnuti zobrazi jen pocet, ne jmena.
    var selectedTagLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // Chipy jen pro stavy, ktere zdroj umí server-side aplikovat (viz
    // MangaSource.availableStatuses) - napr. web bez hiatus varianty chip
    // schova, misto aby tiše filtroval "vsechno".
    val statuses = listOf(
        null to stringResource(R.string.common_all),
        "ongoing" to stringResource(R.string.source_browse_status_ongoing),
        "completed" to stringResource(R.string.source_browse_status_completed),
        "hiatus" to stringResource(R.string.source_browse_status_hiatus),
        "cancelled" to stringResource(R.string.source_browse_status_cancelled),
    ).filter { it.first == null || source == null || it.first in source.availableStatuses }
    val demographics = source?.availableDemographics.orEmpty()
    val comicTypes = source?.availableComicTypes.orEmpty()
    val createdRanges = source?.availableCreatedRanges.orEmpty()
    val availableSorts = source?.availableSorts
    val sorts = listOf(
        "popular" to stringResource(R.string.source_browse_popular),
        "latest" to stringResource(R.string.source_browse_latest),
        "rating" to stringResource(R.string.source_browse_sort_rating),
        "title" to stringResource(R.string.source_browse_sort_title),
    ).filter { availableSorts == null || it.first in availableSorts }

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

            // Stav vydávání ukazujeme jen u zdroje, co filtr.status skutečně
            // aplikuje (server-side) - jinde by chipy nic nedělaly (hlášený
            // bug: filtry zobrazují možnosti, které zdroj ignoruje).
            if (source?.supportsStatusFilter == true) {
                Text(stringResource(R.string.source_browse_status_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }

            if (source?.supportsTagFilter == true) {
                Text(stringResource(R.string.source_browse_tags_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedButton(
                    onClick = { showTagPicker = true },
                    border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.5f)),
                ) {
                    val total = selectedGenres.size + excludedGenres.size + selectedTags.size + excludedTags.size
                    Text(
                        // "Žádné" čteno jako "žádné tagy neexistují" - ve skutečnosti
                        // picker nabízí stovky (hlášený bug). Label tedy říká AKCI.
                        if (total == 0) stringResource(R.string.source_browse_tags_pick)
                        else stringResource(R.string.source_browse_tags_selected_count, total),
                        color = Color.White,
                    )
                }
            }

            // Demografická skupina - jen zdroje s availableDemographics (ComicKArt).
            if (demographics.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.source_browse_demographic_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    demographics.forEach { tag ->
                        val selected = tag.id in selectedDemographic
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = {
                                selectedDemographic = if (selected) selectedDemographic - tag.id else selectedDemographic + tag.id
                            },
                            label = { Text(tag.label, fontSize = 12.sp, color = if (selected) Violet else Color(0xFFB0BEC5)) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                selectedContainerColor = Violet.copy(alpha = 0.3f),
                                selectedLabelColor = Violet,
                            ),
                        )
                    }
                }
            }

            // Typ komiksu (země původu) - jen zdroje s availableComicTypes.
            if (comicTypes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.source_browse_type_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    comicTypes.forEach { tag ->
                        val selected = tag.id in selectedTypes
                        androidx.compose.material3.FilterChip(
                            selected = selected,
                            onClick = {
                                selectedTypes = if (selected) selectedTypes - tag.id else selectedTypes + tag.id
                            },
                            label = { Text(tag.label, fontSize = 12.sp, color = if (selected) Violet else Color(0xFFB0BEC5)) },
                            colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                selectedContainerColor = Violet.copy(alpha = 0.3f),
                                selectedLabelColor = Violet,
                            ),
                        )
                    }
                }
            }

            // Minimální počet kapitol - jen zdroje s supportsMinChaptersFilter.
            if (source?.supportsMinChaptersFilter == true) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.source_browse_min_chapters_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = minChaptersText,
                    onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) minChaptersText = it },
                    placeholder = { Text(stringResource(R.string.source_browse_min_chapters_placeholder), color = Color(0xFFB0BEC5)) },
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
            }

            // "Přidáno před X dny" - jen zdroje s availableCreatedRanges.
            if (createdRanges.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.source_browse_created_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                Box {
                    OutlinedButton(
                        onClick = { createdRangeExpanded = true },
                        border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            createdRanges.firstOrNull { it.id.toIntOrNull() == selectedCreatedRange }?.label
                                ?: stringResource(R.string.common_all),
                            color = Color.White,
                        )
                    }
                    DropdownMenu(
                        expanded = createdRangeExpanded,
                        onDismissRequest = { createdRangeExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_all)) },
                            onClick = { selectedCreatedRange = null; createdRangeExpanded = false },
                        )
                        createdRanges.forEach { range ->
                            DropdownMenuItem(
                                text = { Text(range.label) },
                                onClick = { selectedCreatedRange = range.id.toIntOrNull(); createdRangeExpanded = false },
                            )
                        }
                    }
                }
            }

            if (source?.supportsYearFilter == true) {
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
            }

            Spacer(Modifier.height(16.dp))
            if (sorts.size > 1) {
                Text(stringResource(R.string.source_browse_sort_label), color = Color(0xFFB0BEC5), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    // Směr řazení - jen zdroje s podporou asc/desc (ComicKArt order_direction).
                    if (source?.supportsSortDirection == true) {
                        OutlinedButton(
                            onClick = { sortAscending = !sortAscending },
                            border = androidx.compose.foundation.BorderStroke(1.dp, Violet.copy(alpha = 0.5f)),
                        ) {
                            Text(
                                if (sortAscending) stringResource(R.string.source_browse_sort_asc)
                                else stringResource(R.string.source_browse_sort_desc),
                                color = Color.White,
                            )
                        }
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
                            genres = selectedGenres,
                            excludeGenres = excludedGenres,
                            tags = selectedTags,
                            excludeTags = excludedTags,
                            demographic = selectedDemographic,
                            comicTypes = selectedTypes,
                            minChapters = minChaptersText.toIntOrNull(),
                            createdRangeDays = selectedCreatedRange,
                            sortAscending = sortAscending,
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

    if (showTagPicker && source != null) {
        val tagSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        TagPickerSheet(
            source = source,
            selectedIds = selectedGenres + selectedTags,
            excludedIds = excludedGenres + excludedTags,
            knownLabels = selectedTagLabels,
            sheetState = tagSheetState,
            onDismiss = { showTagPicker = false },
            onApply = { genres, exclGenres, tags, exclTags, labels ->
                selectedGenres = genres
                excludedGenres = exclGenres
                selectedTags = tags
                excludedTags = exclTags
                selectedTagLabels = labels
                showTagPicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagPickerSheet(
    source: MangaSource,
    selectedIds: List<String>,
    excludedIds: List<String>,
    knownLabels: Map<String, String>,
    sheetState: androidx.compose.material3.SheetState,
    onDismiss: () -> Unit,
    // (genres, excludeGenres, tags, excludeTags, labels) - split podle FilterTag.kind
    // dela uz picker (zna allTags); u zdroju bez kindu vse spadne do "genres".
    onApply: (List<String>, List<String>, List<String>, List<String>, Map<String, String>) -> Unit,
) {
    var allTags by remember { mutableStateOf<List<FilterTag>?>(null) }
    var loadFailed by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(selectedIds.toSet()) }
    var excluded by remember { mutableStateOf(excludedIds.toSet()) }
    val canExclude = source.supportsExcludeTags

    LaunchedEffect(source.id) {
        try {
            allTags = source.getAvailableTags()
        } catch (_: Exception) {
            loadFailed = true
        }
    }

    val filteredTags by remember {
        derivedStateOf {
            val tags = allTags.orEmpty()
            if (query.isBlank()) tags else tags.filter { it.label.contains(query, ignoreCase = true) }
        }
    }
    // Sekce podle kind - zdroje bez kindu dostanou jednu plochou sekci (drivejsi chovani).
    val genreTags by remember { derivedStateOf { filteredTags.filter { it.kind != "tag" } } }
    val freeTags by remember { derivedStateOf { filteredTags.filter { it.kind == "tag" } } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.source_browse_tags_search_placeholder), color = Color(0xFFB0BEC5)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Violet,
                    unfocusedBorderColor = Color(0xFFB0BEC5).copy(alpha = 0.3f),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = Violet,
                ),
            )
            if (canExclude) {
                Text(
                    stringResource(R.string.source_browse_tags_exclude_hint),
                    color = Color(0xFFB0BEC5), fontSize = 11.sp,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            when {
                allTags == null && !loadFailed -> {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        JiyuLoadingIndicator()
                    }
                }
                loadFailed || filteredTags.isEmpty() && query.isBlank() && allTags?.isEmpty() == true -> {
                    Text(
                        if (loadFailed) stringResource(R.string.source_browse_tags_load_failed) else stringResource(R.string.source_browse_tags_empty),
                        color = Color(0xFFB0BEC5),
                        modifier = Modifier.padding(32.dp),
                    )
                }
                else -> {
                    // off -> zahrnout -> vyloucit -> off (vzor webu); bez exclude
                    // podpory klasicky jen toggle.
                    fun cycle(id: String) {
                        when {
                            !canExclude -> selected = if (id in selected) selected - id else selected + id
                            id in selected -> { selected -= id; excluded += id }
                            id in excluded -> excluded -= id
                            else -> selected += id
                        }
                    }
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (genreTags.isNotEmpty() && freeTags.isNotEmpty()) {
                            item(key = "hdr_genres") {
                                Text(stringResource(R.string.source_browse_section_genres),
                                    color = Color(0xFFB0BEC5), fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                            }
                        }
                        items(genreTags, key = { "g:${it.id}" }) { tag ->
                            TagPickerRow(tag, tag.id in selected, tag.id in excluded) { cycle(tag.id) }
                        }
                        if (freeTags.isNotEmpty()) {
                            if (genreTags.isNotEmpty()) {
                                item(key = "hdr_tags") {
                                    Text(stringResource(R.string.source_browse_section_tags),
                                        color = Color(0xFFB0BEC5), fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                                }
                            }
                            items(freeTags, key = { "t:${it.id}" }) { tag ->
                                TagPickerRow(tag, tag.id in selected, tag.id in excluded) { cycle(tag.id) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { selected = emptySet(); excluded = emptySet() },
                    modifier = Modifier.weight(1f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFB0BEC5).copy(alpha = 0.4f)),
                ) {
                    Text(stringResource(R.string.source_browse_reset), color = Color(0xFFB0BEC5))
                }
                Button(
                    onClick = {
                        val byId = allTags.orEmpty().associateBy { it.id }
                        fun isTag(id: String) = byId[id]?.kind == "tag"
                        val labels = (allTags.orEmpty().associate { it.id to it.label }) + knownLabels
                        onApply(
                            selected.filter { !isTag(it) },
                            excluded.filter { !isTag(it) },
                            selected.filter { isTag(it) },
                            excluded.filter { isTag(it) },
                            labels.filterKeys { it in selected || it in excluded },
                        )
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

/** Jeden radek tag pickeru - zahrnuty (fialove) / vylouceny (cervene) / neutralni. */
@Composable
private fun TagPickerRow(
    tag: FilterTag,
    isSelected: Boolean,
    isExcluded: Boolean,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(tag.id) { detectTapGestures { onTap() } }
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tag.label,
            color = when {
                isSelected -> Violet
                isExcluded -> Color(0xFFFF5252)
                else -> Color.White
            },
            fontWeight = if (isSelected || isExcluded) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (isExcluded) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
            modifier = Modifier.weight(1f),
        )
        if (isSelected || isExcluded) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(20.dp)
                    .background(if (isSelected) Violet else Color(0xFFFF5252), RoundedCornerShape(2.dp)),
            )
        }
    }
}
