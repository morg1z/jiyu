package com.haise.jiyu.ui.comickhome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Velocity
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import com.haise.jiyu.R
import com.haise.jiyu.source.SManga
import com.haise.jiyu.source.comick.ComicKGenreOption
import com.haise.jiyu.source.comick.ComicKSearchFilters
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Filter
import compose.icons.tablericons.Search
import compose.icons.tablericons.X

/**
 * ComicK "Procházet" se všemi filtry, které má web (žánry, tagy, demografie,
 * typ/původ, status, content rating, min. počet kapitol, rok vydání, řazení) -
 * viz [ComicKBrowseViewModel]/[com.haise.jiyu.source.comick.ComicKSource.searchAdvanced].
 * Nahrazuje pro ComicK mód GlobalSearchScreen (cross-source hledání tam pro
 * jediný aktivní zdroj nedávalo smysl).
 */
@Composable
fun ComicKBrowseScreen(
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit,
    viewModel: ComicKBrowseViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsState()
    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(450)
            viewModel.search()
        }
    }
    val filters by viewModel.filters.collectAsState()
    val genreOptions by viewModel.genreOptions.collectAsState()
    val results by viewModel.results.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val error by viewModel.error.collectAsState()
    val openingManga by viewModel.openingManga.collectAsState()
    val openError by viewModel.openError.collectAsState()
    val showAdultContent by viewModel.showAdultContent.collectAsState()

    var showFilterSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openError) {
        openError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearOpenError()
        }
    }

    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 6 && totalItems > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !loading) viewModel.loadMore()
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowLeft, contentDescription = null, tint = TextPrimary)
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
                        onValueChange = { viewModel.setQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                if (query.isEmpty()) {
                                    Text(stringResource(R.string.comick_browse_search_placeholder), color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                                }
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery(""); viewModel.search() }, modifier = Modifier.size(28.dp)) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                IconButton(onClick = { showFilterSheet = true }) {
                    Box {
                        Icon(
                            TablerIcons.Filter,
                            contentDescription = stringResource(R.string.comick_browse_filters),
                            tint = if (filters.isActive) Violet else TextSecondary,
                        )
                        if (filters.isActive) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .align(Alignment.TopEnd)
                                    .clip(CircleShape)
                                    .background(Pink),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().background(screenGradient).padding(innerPadding)) {
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            when {
                loading && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { JiyuLoadingIndicator() }
                error != null && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(stringResource(R.string.comick_home_load_failed), color = TextSecondary, fontSize = 14.sp)
                        Text(error ?: "", color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                        OutlinedButton(onClick = { viewModel.search() }) { Text(stringResource(R.string.common_retry), color = Violet) }
                    }
                }
                results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.comick_home_empty), color = TextSecondary, fontSize = 14.sp)
                }
                // Seznam misto mrizky obalek (uzivatelsky pozadavek "jak to ma ComicK
                // Search" - foto/nazev/pocet kapitol na radek, bez cisla poradi a bez
                // popisku, co ma jejich verze navic) - viz ComicKSearchResultRow nize.
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp + navBottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(results, key = { it.sourceId + it.url }) { manga ->
                        ComicKSearchResultRow(manga = manga, onClick = { viewModel.openManga(manga, onOpenManga) })
                    }
                }
            }
            if (openingManga != null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    JiyuLoadingIndicator()
                }
            }
        }
    }

    if (showFilterSheet) {
        ComicKFilterSheet(
            initial = filters,
            genreOptions = genreOptions,
            showAdultContent = showAdultContent,
            onDismiss = { showFilterSheet = false },
            onApply = { newFilters ->
                viewModel.applyFilters(newFilters)
                showFilterSheet = false
            },
            onClear = {
                viewModel.clearFilters()
                showFilterSheet = false
            },
        )
    }
}

/** Jeden řádek výsledku hledání - foto/název/počet kapitol, jak to má ComicK Search,
 * jen bez pořadového čísla a bez úryvku popisu (uživatelský požadavek). */
@Composable
internal fun ComicKSearchResultRow(manga: SManga, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        AsyncImage(
            model = manga.coverUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(64.dp).height(90.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                text = manga.title,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            manga.lastChapter?.let { last ->
                val count = if (last == last.toInt().toFloat()) last.toInt() else kotlin.math.ceil(last).toInt()
                Text(
                    text = pluralStringResource(R.plurals.detail_chapter_count, count, count),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ComicKFilterSheet(
    initial: ComicKSearchFilters,
    genreOptions: List<ComicKGenreOption>,
    showAdultContent: Boolean,
    onDismiss: () -> Unit,
    onApply: (ComicKSearchFilters) -> Unit,
    onClear: () -> Unit,
) {
    var sortBy by remember { mutableStateOf(initial.sortBy) }
    var countries by remember { mutableStateOf(initial.countries.toSet()) }
    var demographics by remember { mutableStateOf(initial.demographics.toSet()) }
    var status by remember { mutableStateOf(initial.status) }
    var contentRating by remember { mutableStateOf(initial.contentRating) }
    var minChapters by remember { mutableStateOf(initial.minChapters?.toString() ?: "") }
    var yearFrom by remember { mutableStateOf(initial.yearFrom?.toString() ?: "") }
    var yearTo by remember { mutableStateOf(initial.yearTo?.toString() ?: "") }
    var genres by remember { mutableStateOf(initial.genres.toSet()) }
    var tags by remember { mutableStateOf(initial.tags.toSet()) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.comick_browse_filters),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClear) {
                    Text(stringResource(R.string.comick_browse_clear_filters), color = TextSecondary, fontSize = 13.sp)
                }
            }
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 20.dp)
                    // Zabrani tomu, aby zbytkovy scroll/fling z okraje seznamu (dojel jsem
                    // uplne dolu/nahoru a hned smyknu opacne) propadl az na ModalBottomSheet
                    // a zavrel ho - znamy problem Compose Material3, uzivatelsky report.
                    .nestedScroll(sheetContentNestedScrollConnection),
            ) {
                item { SheetSectionLabel(stringResource(R.string.comick_browse_sort)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "follow" to stringResource(R.string.source_browse_popular),
                            "latest" to stringResource(R.string.source_browse_latest),
                            "rating" to stringResource(R.string.comick_browse_sort_rating),
                            "average_rating" to stringResource(R.string.comick_browse_sort_average),
                            "title" to stringResource(R.string.comick_browse_sort_title),
                        ).forEach { (value, label) ->
                            SheetChip(label, sortBy == value) { sortBy = value }
                        }
                    }
                    // Popisky u razeni - stejne vysvetlivky jako ma ComicKuv vlastni
                    // filtr u "Popular" (Bayesian vs Average rating), uzivatelsky pozadavek.
                    val sortCaption = when (sortBy) {
                        "rating"         -> stringResource(R.string.comick_browse_sort_rating_desc)
                        "average_rating" -> stringResource(R.string.comick_browse_sort_average_desc)
                        "latest"         -> stringResource(R.string.comick_browse_sort_latest_desc)
                        "follow"         -> stringResource(R.string.comick_browse_sort_follow_desc)
                        else             -> null
                    }
                    if (sortCaption != null) {
                        Text(sortCaption, color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item { SheetDivider() }
                item { SheetSectionLabel(stringResource(R.string.comick_browse_type)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "jp" to stringResource(R.string.browse_source_type_manga),
                            "kr" to stringResource(R.string.browse_source_type_manhwa),
                            "cn" to stringResource(R.string.browse_source_type_manhua),
                            "others" to stringResource(R.string.comick_browse_type_others),
                        ).forEach { (value, label) ->
                            SheetChip(label, value in countries) {
                                countries = if (value in countries) countries - value else countries + value
                            }
                        }
                    }
                }
                item { SheetDivider() }
                item { SheetSectionLabel(stringResource(R.string.comick_browse_demographic)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1 to "Shounen", 2 to "Josei", 3 to "Seinen", 4 to "Shoujo").forEach { (value, label) ->
                            SheetChip(label, value in demographics) {
                                demographics = if (value in demographics) demographics - value else demographics + value
                            }
                        }
                    }
                }
                item { SheetDivider() }
                item { SheetSectionLabel(stringResource(R.string.comick_browse_status)) }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            1 to stringResource(R.string.detail_status_ongoing),
                            2 to stringResource(R.string.detail_status_completed),
                            3 to stringResource(R.string.detail_status_cancelled),
                            4 to stringResource(R.string.detail_status_hiatus),
                        ).forEach { (value, label) ->
                            SheetChip(label, status == value) { status = if (status == value) null else value }
                        }
                    }
                }
                if (showAdultContent) {
                    item { SheetDivider() }
                    item { SheetSectionLabel(stringResource(R.string.comick_browse_content_rating)) }
                    item {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "safe" to stringResource(R.string.comick_browse_content_rating_safe),
                                "suggestive" to stringResource(R.string.comick_browse_content_rating_suggestive),
                                "erotica" to stringResource(R.string.comick_browse_content_rating_erotica),
                            ).forEach { (value, label) ->
                                SheetChip(label, contentRating == value) { contentRating = if (contentRating == value) null else value }
                            }
                        }
                    }
                }
                item { SheetDivider() }
                item { SheetSectionLabel(stringResource(R.string.comick_browse_min_chapters)) }
                item {
                    SheetNumberField(
                        value = minChapters,
                        onValueChange = { minChapters = it.filter(Char::isDigit) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item { SheetDivider() }
                item { SheetSectionLabel("${stringResource(R.string.comick_browse_year_from)} / ${stringResource(R.string.comick_browse_year_to)}") }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SheetNumberField(
                            value = yearFrom,
                            onValueChange = { yearFrom = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier.weight(1f),
                        )
                        SheetNumberField(
                            value = yearTo,
                            onValueChange = { yearTo = it.filter(Char::isDigit).take(4) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { SheetDivider() }
                item {
                    SheetSectionLabel(
                        "${stringResource(R.string.detail_info_genres)}" + if (genres.isNotEmpty()) " (${genres.size})" else "",
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        genreOptions.filter { it.group == "Genre" }.forEach { g ->
                            SheetChip(g.name, g.slug in genres) {
                                genres = if (g.slug in genres) genres - g.slug else genres + g.slug
                            }
                        }
                    }
                }
                item { SheetDivider() }
                item {
                    SheetSectionLabel(
                        "${stringResource(R.string.comick_browse_tags)}" + if (tags.isNotEmpty()) " (${tags.size})" else "",
                    )
                }
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                        genreOptions.filter { it.group != "Genre" }.forEach { g ->
                            SheetChip(g.name, g.slug in tags) {
                                tags = if (g.slug in tags) tags - g.slug else tags + g.slug
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(72.dp)) }
            }
            Button(
                onClick = {
                    onApply(
                        ComicKSearchFilters(
                            genres = genres.toList(),
                            tags = tags.toList(),
                            demographics = demographics.toList(),
                            countries = countries.toList(),
                            status = status,
                            contentRating = contentRating,
                            minChapters = minChapters.toIntOrNull(),
                            yearFrom = yearFrom.toIntOrNull(),
                            yearTo = yearTo.toIntOrNull(),
                            sortBy = sortBy,
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(stringResource(R.string.comick_browse_apply))
            }
        }
    }
}

/** Zabrani tomu, aby zbytkovy scroll/fling z okraje seznamu propadl az na
 * ModalBottomSheet a zavrel ho misto aby jen dosel na konec obsahu - viz
 * pouziti u [LazyColumn] v [ComicKFilterSheet]. Bezstavovy singleton, staci
 * jeden pro celý soubor. */
private val sheetContentNestedScrollConnection = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

@Composable
private fun SheetSectionLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}

/** Tenka oddelovaci cara mezi sekcemi filtru - dřív jednotlivé sekce (Typ,
 * Demografie, Žánry, Tagy...) plynule přecházely jedna do druhé jen mezerou,
 * takže dlouhá zeď stejně vypadajících pilulek (viz Žánry/Tagy) byla těžko
 * čitelná (uživatelský report se screenshotem "graficky to vypadá blbě"). */
@Composable
private fun SheetDivider() {
    HorizontalDivider(color = CardBorder.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 16.dp))
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(if (selected) Violet else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (selected) Violet else CardBorder, RoundedCornerShape(50.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun SheetNumberField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, CardBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
