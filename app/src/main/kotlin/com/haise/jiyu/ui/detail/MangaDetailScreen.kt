package com.haise.jiyu.ui.detail

import java.util.Locale

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import kotlinx.coroutines.launch
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.data.repository.deserializeAltTitles
import com.haise.jiyu.data.repository.deserializeChapterGroups
import com.haise.jiyu.source.SGroup
import com.haise.jiyu.source.comick.ComicKComment
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MangaDetailScreen(
    onBack: () -> Unit = {},
    onOpenChapter: (String) -> Unit,
    onOpenChapterIncognito: (String) -> Unit = {},
    onOpenQr: (mangaId: String, mangaTitle: String) -> Unit = { _, _ -> },
    onOpenDetails: () -> Unit = {},
    onResolveChapter: (chapterId: String, incognito: Boolean) -> Unit = { _, _ -> },
    onOpenGroup: (slug: String, title: String) -> Unit = { _, _ -> },
    onOpenManga: (String) -> Unit = {},
    viewModel: MangaDetailViewModel = hiltViewModel(),
) {
    val manga            by viewModel.manga.collectAsState()
    val coverGallery     by viewModel.coverGallery.collectAsState()
    val comments         by viewModel.comments.collectAsState()
    val commentsTotal    by viewModel.commentsTotal.collectAsState()
    val commentsLoading  by viewModel.commentsLoading.collectAsState()
    val commentsError    by viewModel.commentsError.collectAsState()
    val recommendations  by viewModel.recommendations.collectAsState()
    val openingRecommendation by viewModel.openingRecommendation.collectAsState()
    val chapters         by viewModel.chapters.collectAsState()
    val continueChapter  by viewModel.continueChapter.collectAsState()
    val firstUnread      by viewModel.firstUnreadChapter.collectAsState()
    val sortAscending    by viewModel.sortAscending.collectAsState()
    val isRefreshing     by viewModel.isRefreshing.collectAsState()
    val errorMessage     by viewModel.errorMessage.collectAsState()
    val readingTimeMs    by viewModel.readingTimeMs.collectAsState()
    val readingStatus    by viewModel.readingStatus.collectAsState()
    val isFavorite       by viewModel.isFavorite.collectAsState()
    val pendingLibraryAdd by viewModel.pendingLibraryAdd.collectAsState()
    val chapterFilter       by viewModel.chapterFilter.collectAsState()
    val statusFilter        by viewModel.statusFilter.collectAsState()
    val selectedScanlator   by viewModel.selectedScanlator.collectAsState()
    val availableScanlators by viewModel.availableScanlators.collectAsState()
    val sourceName         by viewModel.sourceName.collectAsState()
    val context             = androidx.compose.ui.platform.LocalContext.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    fun openChapter(chapter: ChapterEntity, incognito: Boolean = false) {
        if (chapter.sourceId == "comick") {
            onResolveChapter(chapter.id, incognito)
        } else if (incognito) {
            onOpenChapterIncognito(chapter.id)
        } else {
            onOpenChapter(chapter.id)
        }
    }
    val pullToRefreshState = rememberPullToRefreshState()
    var showChapterOverflowMenu by remember { mutableStateOf(false) }
    var showDownloadNDialog by remember { mutableStateOf(false) }
    var showTranslateNDialog by remember { mutableStateOf(false) }
    var chapterSearchActive by remember { mutableStateOf(false) }
    var chapterGridView by remember { mutableStateOf(false) }
    var groupByVolume by remember { mutableStateOf(false) }
    var chapterPage by remember { mutableStateOf(0) }
    // Hromadne akce nad prectenim (oznacit vse starsi/vse jako prectene) meni stav
    // desitek kapitol najednou bez moznosti jednoho tlacitka na vraceni zpet - proto
    // se nespousti primo z menu, ale az po potvrzeni v dialogu nize.
    var pendingBulkReadConfirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val markReadUpToLabel = stringResource(R.string.detail_mark_read_up_to)
    val markAllOlderReadLabel = stringResource(R.string.detail_mark_all_older_read)
    var descriptionExpanded by remember { mutableStateOf(false) }
    var showCoverFullscreen by remember { mutableStateOf(false) }
    var showCoverGallery by remember { mutableStateOf(false) }
    var showRecommendations by remember { mutableStateOf(false) }
    // Kdyz je null, fullscreen dialog ukazuje puvodni manga.coverUrl - nastavi se jen kdyz
    // uzivatel v galerii klepne na jinou obalku.
    var selectedCoverUrl by remember { mutableStateOf<String?>(null) }
    var statusDropdownExpanded by remember { mutableStateOf(false) }

    // Koordinace pull-to-refresh se stavem ViewModelu řeší od Material3 1.3 přímo
    // PullToRefreshBox (viz níž) - dvojice LaunchedEffect, která si stav posílala tam a zpět,
    // odpadla.

    // Chyba refreshe → snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(manga?.id, manga?.sourceId) {
        if (manga?.sourceId == "comick" && comments.isEmpty()) viewModel.loadMoreComments()
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refreshChapters() },
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
            state = pullToRefreshState,
        ) {
            val altTitles = remember(manga?.alternateTitles) { deserializeAltTitles(manga?.alternateTitles) }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom),
            ) {

                // ── Top bar (zpět, detaily, sdílet, QR, knihovna) ──────────────
                item {
                    val inLibrary = manga?.inLibrary == true
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(onClick = onOpenDetails) {
                            Icon(TablerIcons.InfoCircle, contentDescription = stringResource(R.string.detail_open_details), tint = TextSecondary, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = {
                            manga?.let { m -> onOpenQr(m.id, m.title) }
                        }) {
                            Icon(TablerIcons.Qrcode, contentDescription = stringResource(R.string.detail_qr_code), tint = TextSecondary, modifier = Modifier.size(22.dp))
                        }
                        val shareLabel = stringResource(R.string.common_share)
                        IconButton(onClick = {
                            manga?.let { m ->
                                val i = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${m.title}\n${m.url}")
                                }
                                context.startActivity(Intent.createChooser(i, shareLabel))
                            }
                        }) {
                            Icon(TablerIcons.Share, contentDescription = shareLabel, tint = TextSecondary, modifier = Modifier.size(22.dp))
                        }
                        IconButton(onClick = { viewModel.toggleFavorite() }) {
                            Icon(
                                imageVector = TablerIcons.Heart,
                                contentDescription = if (isFavorite) stringResource(R.string.detail_remove_favorite) else stringResource(R.string.detail_add_favorite),
                                tint = if (isFavorite) Pink else TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        IconButton(onClick = { if (inLibrary) viewModel.removeFromLibrary() else viewModel.addToLibrary() }) {
                            Icon(
                                imageVector = TablerIcons.Bookmark,
                                contentDescription = if (inLibrary) stringResource(R.string.detail_remove_from_library) else stringResource(R.string.detail_in_library),
                                tint = if (inLibrary) GlowViolet else TextSecondary,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }

                // ── Název + kopírovat (ComicK styl) ──────────────────────────────
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
                    ) {
                        Text(
                            text = manga?.title ?: "",
                            style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp),
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val copyLabel = stringResource(R.string.detail_copy_title)
                        val copiedMessage = stringResource(R.string.detail_title_copied)
                        IconButton(onClick = {
                            manga?.title?.let {
                                clipboardManager.setText(AnnotatedString(it))
                                coroutineScope.launch { snackbarHostState.showSnackbar(copiedMessage) }
                            }
                        }) {
                            Icon(TablerIcons.Copy, contentDescription = copyLabel, tint = TextSecondary, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                // ── Alternativní názvy (ComicK) ──────────────────────────────────
                if (altTitles.isNotEmpty()) {
                    item {
                        Text(
                            text = altTitles.take(4).joinToString(" • "),
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp),
                        )
                    }
                }

                // ── Obálka + metadata ───────────────────────────────────────────
                item {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Column(modifier = Modifier.width(150.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.74f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        // ComicK: galerie vsech historickych obalek (uzivatelsky
                                        // pozadavek - viz ComicKSource.getCoverGallery). Jine zdroje:
                                        // puvodni chovani, jen zvetsit tu jednu obalku, co maji.
                                        if (manga?.sourceId == "comick") {
                                            viewModel.loadCoverGallery()
                                            showCoverGallery = true
                                        } else {
                                            showCoverFullscreen = true
                                        }
                                    },
                            ) {
                                AsyncImage(
                                    model = manga?.coverUrl,
                                    contentDescription = manga?.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            // "Doporučené" (ComicK Recommendations) - jen ComicK, uzivatelsky
                            // pozadavek "decentni tlacitko pod cover fotkou". Nacte se az na
                            // klepnuti (viz loadRecommendations - stejny lazy vzor jako galerie
                            // obalek), aby se pro kazdy titul netahal navic request automaticky.
                            if (manga?.sourceId == "comick") {
                                Text(
                                    text = stringResource(R.string.detail_recommendations_button),
                                    color = Violet,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp)
                                        .clip(RoundedCornerShape(50.dp))
                                        .border(1.dp, Violet.copy(alpha = 0.35f), RoundedCornerShape(50.dp))
                                        .clickable {
                                            viewModel.loadRecommendations()
                                            showRecommendations = true
                                        }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }
                        if (showCoverFullscreen) {
                            Dialog(
                                onDismissRequest = { showCoverFullscreen = false },
                                properties = DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                        .clickable { showCoverFullscreen = false },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    AsyncImage(
                                        model = selectedCoverUrl ?: manga?.coverUrl,
                                        contentDescription = null,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        if (showCoverGallery) {
                            Dialog(
                                onDismissRequest = { showCoverGallery = false },
                                properties = DialogProperties(usePlatformDefaultWidth = false),
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .statusBarsPadding()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.detail_cover_gallery_title),
                                            color = Color.White,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                        )
                                        IconButton(onClick = { showCoverGallery = false }) {
                                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_close), tint = Color.White)
                                        }
                                    }
                                    when {
                                        coverGallery == null -> {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                JiyuLoadingIndicator()
                                            }
                                        }
                                        coverGallery.isNullOrEmpty() -> {
                                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = stringResource(R.string.detail_cover_gallery_empty),
                                                    color = TextSecondary,
                                                    fontSize = 13.sp,
                                                )
                                            }
                                        }
                                        else -> {
                                            LazyVerticalGrid(
                                                columns = GridCells.Fixed(3),
                                                contentPadding = PaddingValues(12.dp),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                                modifier = Modifier.fillMaxSize(),
                                            ) {
                                                items(coverGallery.orEmpty()) { cover ->
                                                    Column {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .aspectRatio(0.74f)
                                                                .clip(RoundedCornerShape(8.dp))
                                                                .clickable {
                                                                    selectedCoverUrl = cover.imageUrl
                                                                    showCoverGallery = false
                                                                    showCoverFullscreen = true
                                                                },
                                                        ) {
                                                            AsyncImage(
                                                                model = cover.imageUrl,
                                                                contentDescription = cover.volume,
                                                                contentScale = ContentScale.Crop,
                                                                modifier = Modifier.fillMaxSize(),
                                                            )
                                                        }
                                                        cover.volume?.let { vol ->
                                                            Text(
                                                                text = stringResource(R.string.detail_cover_gallery_volume, vol),
                                                                color = TextSecondary,
                                                                fontSize = 10.sp,
                                                                modifier = Modifier.padding(top = 2.dp),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                            manga?.author?.let { author ->
                                Text(text = author, color = TextSecondary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            }
                            val allGenres = manga?.genres?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() && it.length <= 30 } ?: emptyList()
                            val demographicGenre = allGenres.firstOrNull { it.lowercase() in DEMOGRAPHIC_TAGS }
                            val genres = allGenres.filter { it != demographicGenre }
                            // Cely sloupec presne v poradi/stylu, jaky ma ComicK (uzivatelsky
                            // pozadavek "zkopirujete to") - Status uz neni samostatna barevna
                            // pilulka nad seznamem (to pusobilo rozhazene), ale radek uvnitr
                            // stejneho seznamu jako vsechno ostatni, se stejnymi emoji.
                            Column(modifier = Modifier.padding(top = 6.dp)) {
                                DetailInfoRow(stringResource(R.string.detail_info_origination), "${originationFlag(manga?.contentType)} ${originationLabel(manga?.contentType)}")
                                sourceName?.let { DetailInfoRow(stringResource(R.string.detail_info_source), it) }
                                (manga?.demographic ?: demographicGenre)?.let { DetailInfoRow(stringResource(R.string.detail_info_demographic), it) }
                                if (genres.isNotEmpty()) {
                                    DetailInfoRow(stringResource(R.string.detail_info_genres), genres.take(4).joinToString(", "))
                                }
                                manga?.year?.takeIf { it > 0 }?.let { DetailInfoRow(stringResource(R.string.detail_info_published), it.toString()) }
                                manga?.status?.let { status ->
                                    // Bez emoji pred textem (jak to ma ComicK) - barevny emoji
                                    // glyph mel jiny/vetsi radkovy box nez okolni text a rozhazel
                                    // tim mezery mezi radky, i kdyz mely stejne vertical padding.
                                    val label = when (status.lowercase()) {
                                        "ongoing"   -> stringResource(R.string.detail_status_ongoing)
                                        "completed" -> stringResource(R.string.detail_status_completed)
                                        "hiatus"    -> stringResource(R.string.detail_status_hiatus)
                                        "cancelled" -> stringResource(R.string.detail_status_cancelled)
                                        else        -> status
                                    }
                                    DetailInfoRow(stringResource(R.string.detail_status_placeholder), label)
                                }
                                manga?.translationCompleted?.let {
                                    DetailInfoRow(
                                        stringResource(R.string.detail_info_translation),
                                        if (it) stringResource(R.string.detail_info_translation_completed) else stringResource(R.string.detail_info_translation_ongoing),
                                    )
                                }
                                manga?.hasAnime?.let {
                                    DetailInfoRow(
                                        stringResource(R.string.detail_info_anime),
                                        if (it) stringResource(R.string.common_yes) else stringResource(R.string.common_no),
                                    )
                                }
                                manga?.finalChapter?.let { DetailInfoRow(stringResource(R.string.detail_info_final_chapter), it) }
                                manga?.rating?.let { DetailInfoRow(stringResource(R.string.detail_info_rating), "★ " + String.format(Locale.US, "%.1f", it)) }
                                manga?.rank?.let { DetailInfoRow(stringResource(R.string.detail_info_rank), "#$it") }
                                manga?.followCount?.let { DetailInfoRow(stringResource(R.string.detail_info_followers), formatCount(it)) }
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            // ComicK API vraci jednu kapitolu vicekrat - jednou za kazdou prekladatelskou
                            // skupinu, co ji prelozila (proto seznam kapitol nize ukazuje "Ch.5 Asura",
                            // "Ch.5 QUANTUM" jako samostatne radky). chapters.size by tak u ComicK titulu
                            // pocital kazdy tenhle duplicitni radek zvlast (napr. 434 misto realnych 156
                            // unikatnich kapitol) - stejny floor()+distinct() vzorec jako v
                            // ComicKChapterResolver/SourceResolverViewModel.totalComicKChapters.
                            val isComick = manga?.sourceId == "comick"
                            val totalCount = if (isComick) {
                                chapters.map { kotlin.math.floor(it.chapterNumber).toInt() }.distinct().size
                            } else {
                                chapters.size
                            }
                            val readCount = if (isComick) {
                                chapters.filter { it.read }
                                    .map { kotlin.math.floor(it.chapterNumber).toInt() }.distinct().size
                            } else {
                                chapters.count { it.read }
                            }
                            Text(text = pluralStringResource(R.plurals.detail_chapter_count, totalCount, totalCount), color = TextSecondary, fontSize = 11.sp)
                            if (totalCount > 0) {
                                val progress = readCount.toFloat() / totalCount
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)),
                                        color = GlowViolet,
                                        trackColor = TextSecondary.copy(alpha = 0.18f),
                                    )
                                    Text(
                                        text = " $readCount/$totalCount",
                                        color = TextSecondary.copy(alpha = 0.7f),
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                            }
                            if (readingTimeMs > 0) {
                                Text(
                                    text = stringResource(R.string.detail_reading_time, formatReadingTime(readingTimeMs)),
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }

                // ── Akční řádek: Pokračovat + čtecí status ──────────────────────
                item {
                    val statusOptions = listOf(
                        "READING"      to stringResource(R.string.detail_reading_status_reading),
                        "COMPLETED"    to stringResource(R.string.detail_status_completed),
                        "ON_HOLD"      to stringResource(R.string.detail_reading_status_on_hold),
                        "DROPPED"      to stringResource(R.string.detail_reading_status_dropped),
                        "PLAN_TO_READ" to stringResource(R.string.detail_reading_status_plan),
                    )
                    val statusColors = mapOf(
                        "READING"      to Color(0xFF4CAF50),
                        "COMPLETED"    to Color(0xFF4FC3F7),
                        "ON_HOLD"      to Color(0xFFFFB74D),
                        "DROPPED"      to Color(0xFFEF5350),
                        "PLAN_TO_READ" to Color(0xFF9C27B0),
                    )
                    val statusLabel = statusOptions.firstOrNull { it.first == readingStatus }?.second ?: stringResource(R.string.detail_status_placeholder)
                    val statusColor = statusColors[readingStatus] ?: TextSecondary

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        continueChapter?.let { chapter ->
                            val hasHistory = manga?.lastReadChapterId != null
                            var showReadMenu by remember { mutableStateOf(false) }
                            val chapterNumberLabel = chapter.chapterNumber.let { n ->
                                if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()
                            }
                            Box(modifier = Modifier.weight(3f)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.9f))))
                                        .combinedClickable(
                                            onClick = { openChapter(chapter) },
                                            onLongClick = { showReadMenu = true },
                                        )
                                        .padding(horizontal = 14.dp, vertical = 9.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(TablerIcons.PlayerPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                                    Text(
                                        text = if (hasHistory) "${stringResource(R.string.detail_continue_short)} $chapterNumberLabel" else stringResource(R.string.action_start_reading),
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(start = 6.dp),
                                    )
                                }
                                DropdownMenu(expanded = showReadMenu, onDismissRequest = { showReadMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_read_normal)) },
                                        leadingIcon = { Icon(TablerIcons.PlayerPlay, contentDescription = null) },
                                        onClick = { showReadMenu = false; openChapter(chapter) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_read_incognito)) },
                                        leadingIcon = { Icon(TablerIcons.EyeOff, contentDescription = null) },
                                        onClick = { showReadMenu = false; openChapter(chapter, incognito = true) },
                                    )
                                }
                            }
                        }

                        Box(modifier = Modifier.weight(2f)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(statusColor.copy(alpha = 0.15f))
                                    .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                    .clickable { statusDropdownExpanded = true }
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(TablerIcons.ChevronDown, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp).padding(start = 2.dp))
                            }
                            DropdownMenu(expanded = statusDropdownExpanded, onDismissRequest = { statusDropdownExpanded = false }) {
                                statusOptions.forEach { (key, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            viewModel.setReadingStatus(if (readingStatus == key) null else key)
                                            statusDropdownExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Description (collapsible) - ZAMERNE AZ PO akcnim radku (uzivatelsky
                // pozadavek podle ComicK layoutu: obalka+info -> tlacitka -> popis) ─────
                item {
                    if (!manga?.description.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(glassGradient)
                                .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                .clickable { descriptionExpanded = !descriptionExpanded }
                                .padding(14.dp),
                        ) {
                            Column {
                                Text(
                                    text = stringResource(R.string.detail_description_header),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Violet,
                                    letterSpacing = 2.sp,
                                    modifier = Modifier.padding(bottom = 6.dp),
                                )
                                Text(
                                    text = manga?.description ?: "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary,
                                    maxLines = if (descriptionExpanded) Int.MAX_VALUE else 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                                    Text(
                                        text = if (descriptionExpanded) stringResource(R.string.detail_show_less) else stringResource(R.string.detail_show_more),
                                        color = GlowCyan,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Icon(
                                        imageVector = if (descriptionExpanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                                        contentDescription = null,
                                        tint = GlowCyan,
                                        modifier = Modifier.size(16.dp).padding(start = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Chapters header se sort + bulk download ───────────────────
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.detail_chapters_header),
                            style = MaterialTheme.typography.labelSmall,
                            color = Violet,
                            letterSpacing = 2.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )

                        // Chapter search toggle
                        IconButton(
                            onClick = {
                                chapterSearchActive = !chapterSearchActive
                                if (!chapterSearchActive) viewModel.setChapterFilter("")
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(TablerIcons.Search, contentDescription = stringResource(R.string.detail_search_chapter), tint = if (chapterSearchActive) GlowCyan else TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        // Sort toggle
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlowViolet.copy(alpha = if (sortAscending) 0.18f else 0.08f))
                                .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.toggleSort() }) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TablerIcons.ArrowsSort, contentDescription = stringResource(R.string.detail_sort), tint = if (sortAscending) GlowViolet else TextSecondary, modifier = Modifier.size(14.dp))
                            Text(text = if (sortAscending) stringResource(R.string.detail_sort_oldest) else stringResource(R.string.detail_sort_newest), color = if (sortAscending) GlowViolet else TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                        }

                        // Přetečené menu — méně používané akce nad kapitolami
                        Box {
                            IconButton(onClick = { showChapterOverflowMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(TablerIcons.DotsVertical, contentDescription = stringResource(R.string.detail_more_options), tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                            DropdownMenu(expanded = showChapterOverflowMenu, onDismissRequest = { showChapterOverflowMenu = false }) {
                                val markAllReadLabel = stringResource(R.string.detail_mark_all_read)
                                DropdownMenuItem(
                                    text = { Text(markAllReadLabel) },
                                    leadingIcon = { Icon(TablerIcons.Checks, contentDescription = null) },
                                    onClick = {
                                        pendingBulkReadConfirm = markAllReadLabel to { viewModel.markAllRead() }
                                        showChapterOverflowMenu = false
                                    },
                                )
                                if (firstUnread != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.detail_jump_first_unread)) },
                                        leadingIcon = { Icon(TablerIcons.PlayerSkipForward, contentDescription = null) },
                                        onClick = { showChapterOverflowMenu = false; firstUnread?.let { openChapter(it) } },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(if (chapterGridView) stringResource(R.string.detail_view_as_list) else stringResource(R.string.detail_view_as_grid)) },
                                    leadingIcon = { Icon(if (chapterGridView) TablerIcons.List else TablerIcons.LayoutGrid, contentDescription = null) },
                                    onClick = { chapterGridView = !chapterGridView; showChapterOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (groupByVolume) stringResource(R.string.detail_ungroup_volume) else stringResource(R.string.detail_group_volume)) },
                                    leadingIcon = { Icon(TablerIcons.List, contentDescription = null, tint = if (groupByVolume) GlowCyan else TextSecondary) },
                                    onClick = { groupByVolume = !groupByVolume; showChapterOverflowMenu = false },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_download_all)) },
                                    leadingIcon = { Icon(TablerIcons.Download, contentDescription = null) },
                                    onClick = { viewModel.downloadAll(); showChapterOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_download_unread)) },
                                    leadingIcon = { Icon(TablerIcons.Download, contentDescription = null) },
                                    onClick = { viewModel.downloadUnread(); showChapterOverflowMenu = false },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_download_first_n)) },
                                    leadingIcon = { Icon(TablerIcons.Download, contentDescription = null) },
                                    onClick = { showDownloadNDialog = true; showChapterOverflowMenu = false },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.detail_translate_next_n)) },
                                    leadingIcon = { Icon(TablerIcons.Language, contentDescription = null) },
                                    onClick = { showTranslateNDialog = true; showChapterOverflowMenu = false },
                                )
                            }
                            if (showDownloadNDialog) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showDownloadNDialog = false },
                                    title = { Text(stringResource(R.string.detail_download_first_n_title)) },
                                    text = {
                                        androidx.compose.foundation.layout.Column {
                                            listOf(5, 10, 25, 50).forEach { n ->
                                                androidx.compose.material3.TextButton(
                                                    onClick = { viewModel.downloadFirstN(n); showDownloadNDialog = false },
                                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                                ) { Text(pluralStringResource(R.plurals.detail_n_chapters, n, n)) }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(onClick = { showDownloadNDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                                    },
                                )
                            }
                            if (showTranslateNDialog) {
                                androidx.compose.material3.AlertDialog(
                                    onDismissRequest = { showTranslateNDialog = false },
                                    title = { Text(stringResource(R.string.detail_translate_next_n_title)) },
                                    text = {
                                        androidx.compose.foundation.layout.Column {
                                            listOf(1, 3, 5, 10).forEach { n ->
                                                androidx.compose.material3.TextButton(
                                                    onClick = {
                                                        viewModel.translateNextN(n)
                                                        showTranslateNDialog = false
                                                    },
                                                    modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                                                ) { Text(pluralStringResource(R.plurals.detail_n_chapters, n, n)) }
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        androidx.compose.material3.TextButton(onClick = { showTranslateNDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                                    },
                                )
                            }
                        }
                    }
                    } // konec Column wrapperu
                }

                // ── Chapter search bar ────────────────────────────────────────
                if (chapterSearchActive) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.06f))
                                .border(1.dp, GlowCyan.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            BasicTextField(
                                value = chapterFilter,
                                onValueChange = { viewModel.setChapterFilter(it) },
                                singleLine = true,
                                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                        if (chapterFilter.isEmpty()) Text(stringResource(R.string.detail_search_chapter_placeholder), color = TextSecondary, fontSize = 14.sp)
                                        inner()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (chapterFilter.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setChapterFilter("") }, modifier = Modifier.size(24.dp)) {
                                    Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                // ── Status filter chips ───────────────────────────────────────
                item {
                    val filters = listOf(
                        "ALL" to stringResource(R.string.common_all),
                        "UNREAD" to stringResource(R.string.detail_filter_unread),
                        "READ" to stringResource(R.string.detail_filter_read),
                        "DOWNLOADED" to stringResource(R.string.detail_filter_downloaded),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(filters) { (key, label) ->
                            val isSelected = statusFilter == key
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(if (isSelected) GlowViolet.copy(alpha = 0.22f) else Color.Transparent)
                                    .border(1.dp, if (isSelected) GlowViolet else TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(50))
                                    .clickable { viewModel.setStatusFilter(key) }
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            ) {
                                Text(label, color = if (isSelected) GlowViolet else TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }

                // ── Scanlation filter ─────────────────────────────────────────
                if (availableScanlators.size > 1) {
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (selectedScanlator == null) Cyan.copy(alpha = 0.18f) else Color.Transparent)
                                        .border(1.dp, if (selectedScanlator == null) Cyan else TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(50))
                                        .clickable { viewModel.setScanlator(null) }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text(stringResource(R.string.detail_all_groups), color = if (selectedScanlator == null) Cyan else TextSecondary, fontSize = 10.sp)
                                }
                            }
                            items(availableScanlators) { group ->
                                val isSelected = selectedScanlator == group
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isSelected) Cyan.copy(alpha = 0.18f) else Color.Transparent)
                                        .border(1.dp, if (isSelected) Cyan else TextSecondary.copy(alpha = 0.35f), RoundedCornerShape(50))
                                        .clickable { viewModel.setScanlator(if (isSelected) null else group) }
                                        .padding(horizontal = 12.dp, vertical = 5.dp),
                                ) {
                                    Text(group, color = if (isSelected) Cyan else TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }

                // ── Chapter list / grid (#34) ─────────────────────────────────
                if (chapterGridView) {
                    val totalPages = ((chapters.size + CHAPTERS_PER_PAGE - 1) / CHAPTERS_PER_PAGE).coerceAtLeast(1)
                    val safePage = chapterPage.coerceIn(0, totalPages - 1)
                    val pageChapters = chapters.drop(safePage * CHAPTERS_PER_PAGE).take(CHAPTERS_PER_PAGE)
                    val rows = (pageChapters.size + 3) / 4
                    item {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            LazyVerticalGrid(
                                columns = GridCells.Fixed(4),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(((rows * 60 + (rows - 1) * 6 + 8).coerceAtLeast(60)).dp.coerceAtMost(540.dp)),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                userScrollEnabled = false,
                            ) {
                                items(pageChapters, key = { it.id }) { chapter ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (chapter.read) GlowCyan.copy(alpha = 0.08f) else GlowViolet.copy(alpha = 0.15f))
                                            .border(1.dp, if (chapter.read) GlowCyan.copy(alpha = 0.2f) else GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                            .clickable { openChapter(chapter) }
                                            .padding(6.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = chapter.chapterNumber.let { if (it == it.toLong().toFloat()) it.toLong().toString() else it.toString() },
                                            color = if (chapter.read) TextSecondary else TextPrimary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            if (totalPages > 1) {
                                ChapterPaginationBar(
                                    currentPage = safePage,
                                    totalPages = totalPages,
                                    onPageSelected = { chapterPage = it },
                                )
                            }
                        }
                    }
                } else if (groupByVolume) {
                    val grouped = chapters.groupBy { it.volume ?: "?" }
                        .entries.sortedWith(compareByDescending {
                            val v = it.key; if (v == "?") -1f else v.toFloatOrNull() ?: 0f
                        })
                    grouped.forEach { (volume, chs) ->
                        stickyHeader(key = "vol_$volume") {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NightBlue)
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    if (volume == "?") stringResource(R.string.detail_no_volume) else stringResource(R.string.detail_volume_label, volume),
                                    color = Violet,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        items(chs, key = { it.id }) { chapter ->
                            GlassChapterRow(
                                chapter = chapter,
                                onOpen = { openChapter(chapter) },
                                onDownload = { viewModel.downloadChapter(chapter) },
                                onMarkReadUpTo = {
                                    pendingBulkReadConfirm = markReadUpToLabel to { viewModel.markReadUpTo(chapter.id) }
                                },
                                onMarkAllOlderRead = {
                                    pendingBulkReadConfirm = markAllOlderReadLabel to { viewModel.markAllOlderAsRead(chapter) }
                                },
                                onMarkAllNewerUnread = { viewModel.markAllNewerAsUnread(chapter) },
                                onToggleRead = { viewModel.markChapterRead(chapter.id, !chapter.read) },
                                onGroupClick = { group -> group.slug?.let { onOpenGroup(it, group.name) } },
                            )
                        }
                    }
                } else {
                    // ComicK ma misto nekonecneho scrollu stránkovani po pár desítkách
                    // kapitol - tady stejny princip, jen bez vlajky a poctu hlasu se sipkou.
                    val totalPages = ((chapters.size + CHAPTERS_PER_PAGE - 1) / CHAPTERS_PER_PAGE).coerceAtLeast(1)
                    val safePage = chapterPage.coerceIn(0, totalPages - 1)
                    val pageChapters = chapters.drop(safePage * CHAPTERS_PER_PAGE).take(CHAPTERS_PER_PAGE)
                    items(pageChapters, key = { it.id }) { chapter ->
                        GlassChapterRow(
                            chapter = chapter,
                            onOpen = { openChapter(chapter) },
                            onDownload = { viewModel.downloadChapter(chapter) },
                            onMarkReadUpTo = { viewModel.markReadUpTo(chapter.id) },
                            onMarkAllOlderRead = { viewModel.markAllOlderAsRead(chapter) },
                            onMarkAllNewerUnread = { viewModel.markAllNewerAsUnread(chapter) },
                            onToggleRead = { viewModel.markChapterRead(chapter.id, !chapter.read) },
                            onGroupClick = { group -> group.slug?.let { onOpenGroup(it, group.name) } },
                        )
                    }
                    if (totalPages > 1) {
                        item(key = "chapter_pagination") {
                            ChapterPaginationBar(
                                currentPage = safePage,
                                totalPages = totalPages,
                                onPageSelected = { chapterPage = it },
                            )
                        }
                    }
                }

                // ── Komentáře (jen ComicK, viz ComicKSource.getComments) ────────
                if (manga?.sourceId == "comick") {
                    item(key = "comments_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.detail_comments_title, commentsTotal),
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                            )
                            // Appka nema napojeny ComicK ucet (viz ComicKSource.getComments -
                            // jen ke cteni) - kdo chce psat/lajkovat, musi na skutecny web.
                            val openOnComickLabel = stringResource(R.string.detail_comments_open_on_comick)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50.dp))
                                    .clickable {
                                        val slug = manga?.url?.substringAfterLast("/") ?: return@clickable
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://comick.io/comic/$slug")))
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(TablerIcons.ExternalLink, contentDescription = null, tint = Violet, modifier = Modifier.size(13.dp))
                                Text(openOnComickLabel, color = Violet, fontSize = 12.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }
                    when {
                        comments.isEmpty() && commentsLoading -> item(key = "comments_loading") {
                            Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                JiyuLoadingIndicator(size = 28.dp, strokeWidth = 2.dp)
                            }
                        }
                        comments.isEmpty() && commentsError != null -> item(key = "comments_error") {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Text(commentsError ?: "", color = TextSecondary, fontSize = 12.sp)
                                androidx.compose.material3.OutlinedButton(
                                    onClick = { viewModel.loadMoreComments() },
                                    modifier = Modifier.padding(top = 8.dp),
                                ) { Text(stringResource(R.string.common_retry)) }
                            }
                        }
                        comments.isEmpty() -> item(key = "comments_empty") {
                            Text(
                                stringResource(R.string.detail_comments_empty),
                                color = TextSecondary,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        else -> {
                            items(comments, key = { "comment_${it.id}" }) { comment ->
                                CommentCard(comment)
                            }
                            if (comments.size < commentsTotal) {
                                item(key = "comments_load_more") {
                                    if (commentsLoading) {
                                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                            JiyuLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                                        }
                                    } else {
                                        androidx.compose.material3.TextButton(
                                            onClick = { viewModel.loadMoreComments() },
                                            modifier = Modifier.fillMaxWidth(),
                                        ) { Text(stringResource(R.string.detail_comments_load_more), color = Violet) }
                                    }
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }

        }
    }

    pendingLibraryAdd?.let { pending ->
        LibraryDuplicateDialog(
            newMangaTitle = manga?.title.orEmpty(),
            pending = pending,
            onConfirm = { viewModel.confirmAddDespiteDuplicate() },
            onDismiss = { viewModel.cancelDuplicateAdd() },
        )
    }

    pendingBulkReadConfirm?.let { (label, action) ->
        AlertDialog(
            onDismissRequest = { pendingBulkReadConfirm = null },
            containerColor = Color(0xFF111B35),
            title = { Text(label, color = Color.White, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.detail_bulk_confirm_body), color = Color(0xFFB0BEC5)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { action(); pendingBulkReadConfirm = null }) {
                    Text(stringResource(R.string.common_confirm), color = GlowViolet)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingBulkReadConfirm = null }) {
                    Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5))
                }
            },
        )
    }

    if (showRecommendations) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showRecommendations = false },
            sheetState = sheetState,
            containerColor = Color(0xFF111B35),
        ) {
            // Vyska se drive vzdy natahla na 85% obrazovky bez ohledu na pocet
            // doporuceni - u dila s jen jednou/dvema polozkami zbyla pod mrizkou
            // velka prazdna tmava plocha (uzivatelsky report se screenshotem).
            // Ted se sheet prizpusobi obsahu az do stropu, mrizka samotna ma svuj
            // vlastni strop a odtud dal jen scrolluje, kdyby doporuceni bylo hodne.
            val maxSheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
            val maxGridHeight = (LocalConfiguration.current.screenHeightDp * 0.6f).dp
            Box(modifier = Modifier.fillMaxWidth().heightIn(max = maxSheetHeight)) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.detail_recommendations_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                when {
                    recommendations == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        JiyuLoadingIndicator()
                    }
                    recommendations.isNullOrEmpty() -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.detail_recommendations_empty), color = TextSecondary, fontSize = 14.sp)
                    }
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(max = maxGridHeight),
                    ) {
                        items(recommendations.orEmpty(), key = { it.manga.sourceId + it.manga.url }) { rec ->
                            Column(
                                modifier = Modifier.clickable {
                                    viewModel.openRecommendation(rec.manga) { id ->
                                        showRecommendations = false
                                        onOpenManga(id)
                                    }
                                },
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(0.74f)
                                        .clip(RoundedCornerShape(8.dp)),
                                ) {
                                    AsyncImage(
                                        model = rec.manga.coverUrl,
                                        contentDescription = rec.manga.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                    if (rec.upCount > 0) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .align(Alignment.BottomStart)
                                                .padding(4.dp)
                                                .clip(RoundedCornerShape(50.dp))
                                                .background(Color.Black.copy(alpha = 0.6f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        ) {
                                            Icon(TablerIcons.ThumbUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(10.dp))
                                            Text("${rec.upCount}", color = Color.White, fontSize = 10.sp, modifier = Modifier.padding(start = 3.dp))
                                        }
                                    }
                                }
                                Text(
                                    text = rec.manga.title,
                                    color = TextPrimary,
                                    fontSize = 11.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 13.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (openingRecommendation) {
                Box(modifier = Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    JiyuLoadingIndicator()
                }
            }
            }
        }
    }
}

@Composable
private fun LibraryDuplicateDialog(
    newMangaTitle: String,
    pending: MangaDetailViewModel.PendingLibraryAdd,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.source_browse_dup_title), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.source_browse_dup_desc, newMangaTitle),
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                pending.matches.forEach { match ->
                    Text(
                        pluralStringResource(R.plurals.source_browse_dup_existing, match.chapterCount, match.sourceName, match.chapterCount),
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                Text(
                    stringResource(
                        R.string.source_browse_dup_new,
                        pending.sourceName,
                        pluralStringResource(R.plurals.source_browse_chapters_count, pending.newChapterCount, pending.newChapterCount),
                    ),
                    color = GlowViolet,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) { Text(stringResource(R.string.source_browse_add_anyway), color = GlowViolet) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5)) }
        },
    )
}

// ── Chapter row ───────────────────────────────────────────────────────────────

/** ComickK ma misto jednoho nekonecneho seznamu stránky po tomhle poctu kapitol. */
private const val CHAPTERS_PER_PAGE = 30

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GlassChapterRow(
    chapter: ChapterEntity,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onMarkReadUpTo: () -> Unit,
    onMarkAllOlderRead: () -> Unit = {},
    onMarkAllNewerUnread: () -> Unit = {},
    onToggleRead: () -> Unit = {},
    onGroupClick: (SGroup) -> Unit = {},
) {
    val isRead = chapter.read
    var showMenu by remember { mutableStateOf(false) }

    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onOpen() },
                        onLongPress = { showMenu = true },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(if (isRead) GlowCyan.copy(alpha = 0.4f) else GlowViolet, RoundedCornerShape(50)))
                Text(
                    text = chapter.name,
                    color = if (isRead) TextSecondary else TextPrimary,
                    fontWeight = if (isRead) FontWeight.Normal else FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp),
                )
                if (chapter.dateUpload > 0L) {
                    val date = java.text.SimpleDateFormat("d. M. yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(chapter.dateUpload))
                    Text(text = date, color = TextSecondary.copy(alpha = 0.5f), fontSize = 11.sp, maxLines = 1)
                }
                val groups = remember(chapter.groupsJson) { deserializeChapterGroups(chapter.groupsJson).filter { it.name.isNotBlank() } }
                // Vsichni prekladatele te kapitoly (ComicK je u kazde kapitoly vsechny
                // uvadi), ne jen prvni - uzivatelsky pozadavek. Klepnuti porad otevre
                // stranku prvni skupiny se slugem, samostatny tap na kazde jmeno by
                // vyzadoval FlowRow a rozbil tenky jednoradkovy zaznam.
                val groupName = groups.takeIf { it.isNotEmpty() }?.joinToString(" • ") { it.name } ?: chapter.scanlationGroup
                if (!groupName.isNullOrBlank()) {
                    Text(
                        text = groupName,
                        color = if (groups.firstOrNull()?.slug != null) Violet else TextSecondary.copy(alpha = 0.6f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .widthIn(max = 120.dp)
                            .then(
                                groups.firstOrNull()?.slug?.let { Modifier.clickable { onGroupClick(groups.first()) } } ?: Modifier,
                            ),
                    )
                }
                // ComicK je jen katalog/metadata (viz design doc) - stahovani dava smysl
                // az po vyreseni skutecneho zdroje (Sub-projekt 3), do te doby se ikonka
                // u ComicK kapitol vubec nevykresluje.
                if (chapter.sourceId != "comick") {
                    when (chapter.downloadStatus) {
                        DownloadStatus.DOWNLOADED  -> Icon(TablerIcons.CircleCheck, contentDescription = stringResource(R.string.detail_chapter_downloaded), tint = Cyan, modifier = Modifier.padding(start = 8.dp).size(18.dp))
                        DownloadStatus.DOWNLOADING -> Text("↓", color = Violet, fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp))
                        DownloadStatus.QUEUED      -> Text("⏳", fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                        DownloadStatus.ERROR       -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) { Icon(TablerIcons.Download, contentDescription = stringResource(R.string.common_retry), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                        else                       -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) { Icon(TablerIcons.Download, contentDescription = stringResource(R.string.common_download), tint = TextSecondary, modifier = Modifier.size(18.dp)) }
                    }
                }
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_mark_read_up_to)) },
                    onClick = { onMarkReadUpTo(); showMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_mark_all_older_read)) },
                    onClick = { onMarkAllOlderRead(); showMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.detail_mark_all_newer_unread)) },
                    onClick = { onMarkAllNewerUnread(); showMenu = false },
                )
                DropdownMenuItem(
                    text = { Text(if (isRead) stringResource(R.string.detail_mark_as_unread) else stringResource(R.string.detail_mark_as_read)) },
                    onClick = { onToggleRead(); showMenu = false },
                )
            }
        }
        HorizontalDivider(color = TextSecondary.copy(alpha = 0.08f), thickness = 1.dp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** ComicK-styl stránkování (First / čísla / Last) - viz komentář u [CHAPTERS_PER_PAGE]. */
@Composable
private fun ChapterPaginationBar(currentPage: Int, totalPages: Int, onPageSelected: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onPageSelected(currentPage - 1) }, enabled = currentPage > 0, modifier = Modifier.size(32.dp)) {
            Icon(
                TablerIcons.ChevronLeft,
                contentDescription = stringResource(R.string.common_previous),
                tint = if (currentPage > 0) TextSecondary else TextSecondary.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp),
            )
        }
        chapterPaginationPages(currentPage, totalPages).forEach { page ->
            if (page == null) {
                Text("…", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp))
            } else {
                val selected = page == currentPage
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected) GlowViolet else Color.Transparent)
                        .clickable { onPageSelected(page) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "${page + 1}",
                        color = if (selected) Color.White else TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        IconButton(onClick = { onPageSelected(currentPage + 1) }, enabled = currentPage < totalPages - 1, modifier = Modifier.size(32.dp)) {
            Icon(
                TablerIcons.ChevronRight,
                contentDescription = stringResource(R.string.common_next),
                tint = if (currentPage < totalPages - 1) TextSecondary else TextSecondary.copy(alpha = 0.25f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Čísla stránek s "…" mezerami pro dlouhé seznamy - stejný vzor jako ComicK "First 1 2 … 20 Last". */
private fun chapterPaginationPages(current: Int, total: Int): List<Int?> {
    if (total <= 7) return (0 until total).toList()
    val pages = linkedSetOf<Int?>()
    pages.add(0)
    if (current > 2) pages.add(null)
    for (p in (current - 1).coerceAtLeast(1)..(current + 1).coerceAtMost(total - 2)) pages.add(p)
    if (current < total - 3) pages.add(null)
    pages.add(total - 1)
    return pages.toList()
}

/** Známé demografické štítky, které bývají zamíchané mezi žánry - vytáhneme je do vlastního řádku (jako ComicK "Demographic"). */
private val DEMOGRAPHIC_TAGS = setOf("shounen", "shonen", "shoujo", "shojo", "seinen", "josei", "kodomo")

@Composable
private fun originationLabel(contentType: String?): String = when (contentType) {
    "MANHWA" -> stringResource(R.string.mylist_content_manhwa)
    "MANHUA" -> stringResource(R.string.mylist_content_manhua)
    "NOVEL"  -> stringResource(R.string.mylist_content_novel)
    "COMIC"  -> stringResource(R.string.mylist_content_comic)
    else     -> stringResource(R.string.browse_source_type_manga)
}

/** Vlaječka podle typu obsahu, stejně jako to ComicK ukazuje u "Origination". */
private fun originationFlag(contentType: String?): String = when (contentType) {
    "MANHWA" -> "🇰🇷"
    "MANHUA" -> "🇨🇳"
    "COMIC"  -> "🇺🇸"
    "NOVEL"  -> "📖"
    else     -> "🇯🇵"
}

/** Odpovědi na odpovědi zplošťuje do jedné roviny pod rodičovským komentářem -
 * dřív se každá vnořená úroveň renderovala jako další [CommentCard] uvnitř už
 * odsazeného Column, takže se odsazení sčítalo (16+38dp na úroveň) a u delších
 * vláken vytlačilo text komentáře prakticky mimo obrazovku (uživatelský report
 * se screenshotem). ComicK nezachovává hlubší strom vláken ve svém vlastním UI
 * ani on - i tam se hlubší odpovědi zobrazí na stejné úrovni jako první odpověď.
 *
 * Aby se zploštěním neztratilo, KOMU kdo odpovídá (druhá uživatelská připomínka -
 * "musíme poznat, že jsou to odpovědi"), nese každá zplošťěná odpověď jméno
 * uživatele, na kterého přímo reaguje - u odpovědi přímo na kořenový komentář je
 * null (to je vidět z jediné úrovně odsazení), u odpovědi na odpověď je to jméno
 * rodičovské odpovědi, vykreslené jako "↳ Odpověď uživateli X" nad textem. */
private fun flattenReplies(replies: List<ComicKComment>, replyingTo: String? = null): List<Pair<ComicKComment, String?>> =
    replies.flatMap { reply -> listOf(reply to replyingTo) + flattenReplies(reply.replies, replyingTo = reply.username) }

/** Jeden komentář z [detail_comments_title] sekce - všechny odpovědi (i vnořené,
 * viz [flattenReplies]) se vykreslí na jedné pevné odsazené úrovni pod ním. */
@Composable
private fun CommentCard(comment: ComicKComment) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)) {
        CommentRow(comment)
        flattenReplies(comment.replies).forEach { (reply, replyingTo) ->
            CommentRow(reply, replyingTo = replyingTo, modifier = Modifier.padding(start = 38.dp, top = 8.dp))
        }
    }
}

@Composable
private fun CommentRow(comment: ComicKComment, replyingTo: String? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        AsyncImage(
            model = comment.avatarUrl,
            contentDescription = comment.username,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(NightBlue),
        )
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            if (replyingTo != null) {
                Text(
                    text = stringResource(R.string.detail_comments_replying_to, replyingTo),
                    color = Violet.copy(alpha = 0.8f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.username, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    text = commentRelativeTime(comment.createdAt),
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(comment.content, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            if (comment.upCount > 0 || comment.downCount > 0) {
                Row(modifier = Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (comment.upCount > 0) {
                        Icon(TablerIcons.ThumbUp, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
                        Text(" ${comment.upCount}", color = TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(end = 10.dp))
                    }
                    if (comment.downCount > 0) {
                        Icon(TablerIcons.ThumbDown, contentDescription = null, tint = TextSecondary.copy(alpha = 0.5f), modifier = Modifier.size(12.dp))
                        Text(" ${comment.downCount}", color = TextSecondary.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** "před 2 h", "před 3 dny" apod. - stejny vzor jako ComicKHomeScreen.relativeTimeLabel
 * (karty/pomocne funkce se v kodu nesdileji mezi soubory, zavedena konvence appky). */
private fun commentRelativeTime(createdAtMs: Long): String {
    if (createdAtMs <= 0L) return ""
    val diffMin = (System.currentTimeMillis() - createdAtMs) / 60_000L
    return when {
        diffMin < 1     -> "teď"
        diffMin < 60    -> "před ${diffMin} min"
        diffMin < 1440  -> "před ${diffMin / 60} h"
        diffMin < 43200 -> "před ${diffMin / 1440} dny"
        else            -> java.text.SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(java.util.Date(createdAtMs))
    }
}

@Composable
private fun DetailInfoRow(label: String, value: String) {
    // lineHeight napevno, ne jen fontSize: bez toho radek s emoji glyfem (vlajka u
    // Origination, hvezdicka u Rating) vychazi vyssi nez okolni cistě textove radky,
    // i pri stejnem vertical paddingu - vysledkem byly nerovnomerne mezery (ComicK
    // referencni sloupec tohle nema, protoze tam zadny radek nema jiny radkovy box).
    Row(modifier = Modifier.padding(vertical = 1.dp)) {
        Text(text = "$label: ", color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp)
        Text(text = value, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "81139" -> "81 139" - appka mezerou jako oddelovacem tisic, bez zavislosti na lokale zarizeni. */
private fun formatCount(n: Int): String {
    val s = n.toString()
    val sb = StringBuilder()
    for ((i, c) in s.withIndex()) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(' ')
        sb.append(c)
    }
    return sb.toString()
}

internal fun formatReadingTime(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else  -> "<1m"
    }
}
