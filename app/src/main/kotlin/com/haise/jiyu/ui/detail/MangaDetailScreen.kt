package com.haise.jiyu.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Note
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import kotlin.math.roundToInt
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import android.content.Intent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.source.SManga
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailScreen(
    onOpenChapter: (String) -> Unit,
    onOpenChapterIncognito: (String) -> Unit = {},
    onOpenManga: (String) -> Unit = {},
    onOpenQr: (mangaId: String, mangaTitle: String) -> Unit = { _, _ -> },
    viewModel: MangaDetailViewModel = hiltViewModel(),
) {
    val manga            by viewModel.manga.collectAsState()
    val chapters         by viewModel.chapters.collectAsState()
    val continueChapter  by viewModel.continueChapter.collectAsState()
    val firstUnread      by viewModel.firstUnreadChapter.collectAsState()
    val relatedManga     by viewModel.relatedManga.collectAsState()
    val sortAscending    by viewModel.sortAscending.collectAsState()
    val allCategories    by viewModel.allCategories.collectAsState()
    val categoryIds      by viewModel.mangaCategoryIds.collectAsState()
    val isRefreshing     by viewModel.isRefreshing.collectAsState()
    val errorMessage     by viewModel.errorMessage.collectAsState()
    val autoDownload     by viewModel.autoDownload.collectAsState()
    val mangaNote        by viewModel.mangaNote.collectAsState()
    val mangaTags        by viewModel.mangaTags.collectAsState()
    val userRating       by viewModel.userRating.collectAsState()
    val readingStatus    by viewModel.readingStatus.collectAsState()
    val aiInsight        by viewModel.aiInsight.collectAsState()
    val aiInsightLoading by viewModel.aiInsightLoading.collectAsState()

    val chapterFilter       by viewModel.chapterFilter.collectAsState()
    val statusFilter        by viewModel.statusFilter.collectAsState()
    val selectedScanlator   by viewModel.selectedScanlator.collectAsState()
    val availableScanlators by viewModel.availableScanlators.collectAsState()
    val excludeFromUpdates  by viewModel.excludeFromUpdates.collectAsState()
    val malId               by viewModel.malId.collectAsState()
    val malScore            by viewModel.malScore.collectAsState()
    val malSearchResults    by viewModel.malSearchResults.collectAsState()
    val malSearchLoading    by viewModel.malSearchLoading.collectAsState()
    var showMalSheet        by remember { mutableStateOf(false) }
    var malSearchQuery      by remember { mutableStateOf("") }
    val context             = androidx.compose.ui.platform.LocalContext.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    var showBulkMenu by remember { mutableStateOf(false) }
    var chapterSearchActive by remember { mutableStateOf(false) }
    var chapterGridView by remember { mutableStateOf(false) }
    var groupByVolume by remember { mutableStateOf(false) }
    var noteText by remember(mangaNote) { mutableStateOf(mangaNote?.content ?: "") }
    var addTagText by remember { mutableStateOf("") }
    var showAddTagField by remember { mutableStateOf(false) }

    // Koordinace pull-to-refresh se stavem ViewModel
    LaunchedEffect(pullToRefreshState.isRefreshing) {
        if (pullToRefreshState.isRefreshing) viewModel.refreshChapters()
    }
    LaunchedEffect(isRefreshing) {
        if (!isRefreshing) pullToRefreshState.endRefresh()
    }

    // Chyba refreshe → snackbar
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding)
                .nestedScroll(pullToRefreshState.nestedScrollConnection),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = navBottom),
            ) {

                // ── Hero image ────────────────────────────────────────────────
                item {
                    var showCoverFullscreen by remember { mutableStateOf(false) }
                    Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                        AsyncImage(
                            model = manga?.coverUrl,
                            contentDescription = manga?.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clickable { showCoverFullscreen = true },
                        )
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
                                    model = manga?.coverUrl,
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.verticalGradient(listOf(Color.Transparent, DeepSpace.copy(alpha = 0.7f), DeepSpace), startY = 80f)
                            )
                        )
                        val inLibrary = manga?.inLibrary == true
                        val context = LocalContext.current
                        Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                            IconButton(onClick = {
                                manga?.let { m -> onOpenQr(m.id, m.title) }
                            }) {
                                Icon(Icons.Filled.QrCode2, contentDescription = "QR kód", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
                            }
                            IconButton(onClick = {
                                manga?.let { m ->
                                    val i = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "${m.title}\n${m.url}")
                                    }
                                    context.startActivity(Intent.createChooser(i, "Sdílet"))
                                }
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = "Sdílet", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(22.dp))
                            }
                            IconButton(onClick = { if (inLibrary) viewModel.removeFromLibrary() }) {
                                Icon(
                                    imageVector = if (inLibrary) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                                    contentDescription = if (inLibrary) "Odebrat z knihovny" else "V knihovně",
                                    tint = if (inLibrary) GlowViolet else Color.White.copy(alpha = 0.6f),
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                        }
                        Column(modifier = Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                            Text(
                                text = manga?.title ?: "",
                                style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 28.sp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(text = "${chapters.size} kapitol", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.padding(top = 2.dp))
                            manga?.status?.let { status ->
                                val (label, statusColor) = when (status.lowercase()) {
                                    "ongoing"   -> "Vychází"   to Color(0xFF4CAF50)
                                    "completed" -> "Dokončeno" to Color(0xFF4FC3F7)
                                    "hiatus"    -> "Pauza"     to Color(0xFFFFB74D)
                                    "cancelled" -> "Zrušeno"   to Color(0xFFEF5350)
                                    else        -> status      to TextSecondary
                                }
                                Box(
                                    modifier = Modifier
                                        .padding(top = 4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(statusColor.copy(alpha = 0.15f))
                                        .border(1.dp, statusColor.copy(alpha = 0.4f), RoundedCornerShape(50))
                                        .padding(horizontal = 10.dp, vertical = 3.dp),
                                ) {
                                    Text(text = label, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }

                // ── Description ───────────────────────────────────────────────
                item {
                    if (!manga?.description.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(glassGradient)
                                .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                                .padding(14.dp),
                        ) {
                            Text(text = manga?.description ?: "", style = MaterialTheme.typography.bodyMedium, color = TextSecondary, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // ── Metadata (author, year, genres) ──────────────────────────
                item {
                    val m = manga
                    if (m != null && (m.author != null || m.year != null || m.genres.isNotBlank())) {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text("INFO", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 8.dp))
                            if (m.author != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                    Text("Autor:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                                    Text(m.author, color = TextPrimary, fontSize = 13.sp)
                                }
                            }
                            if (m.artist != null && m.artist != m.author) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                    Text("Kreslíř:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                                    Text(m.artist, color = TextPrimary, fontSize = 13.sp)
                                }
                            }
                            if (m.year != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                    Text("Rok:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                                    Text(m.year.toString(), color = TextPrimary, fontSize = 13.sp)
                                }
                            }
                            if (m.genres.isNotBlank()) {
                                val genreList = m.genres.split(",").filter { it.isNotBlank() }
                                if (genreList.isNotEmpty()) {
                                    Text("Žánry:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, top = 2.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        genreList.forEach { genre ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(Violet.copy(alpha = 0.15f))
                                                    .border(1.dp, Violet.copy(alpha = 0.4f), RoundedCornerShape(50))
                                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                                            ) {
                                                Text(genre.trim(), color = Violet, fontSize = 11.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Per-manga reader direction ────────────────────────────────
                item {
                    var dirDropdownExpanded by remember { mutableStateOf(false) }
                    val currentDir = manga?.readerDirectionOverride
                    val dirLabel = when (currentDir) {
                        "LTR"     -> "LTR (Vlevo → Vpravo)"
                        "RTL"     -> "RTL (Vpravo → Vlevo)"
                        "WEBTOON" -> "Webtoon (Scroll)"
                        else      -> "Výchozí (z nastavení)"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Směr čtení:", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(100.dp))
                        Box {
                            Text(
                                text = dirLabel,
                                color = Cyan,
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clickable { dirDropdownExpanded = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .border(1.dp, Cyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = dirDropdownExpanded, onDismissRequest = { dirDropdownExpanded = false }) {
                                listOf(null to "Výchozí", "LTR" to "LTR", "RTL" to "RTL", "WEBTOON" to "Webtoon").forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = { viewModel.setReaderDirection(value); dirDropdownExpanded = false },
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Čtecí status ──────────────────────────────────────────────
                item {
                    val statusOptions = listOf(
                        "READING"      to "Čtu",
                        "COMPLETED"    to "Dokončeno",
                        "ON_HOLD"      to "Pozastaveno",
                        "DROPPED"      to "Opuštěno",
                        "PLAN_TO_READ" to "Plánuji číst",
                    )
                    val statusColors = mapOf(
                        "READING"      to Color(0xFF4CAF50),
                        "COMPLETED"    to Color(0xFF4FC3F7),
                        "ON_HOLD"      to Color(0xFFFFB74D),
                        "DROPPED"      to Color(0xFFEF5350),
                        "PLAN_TO_READ" to Color(0xFF9C27B0),
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            text = "ČTECÍ STATUS",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                            color = Violet,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        androidx.compose.foundation.layout.FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            statusOptions.forEach { (key, label) ->
                                val isSelected = readingStatus == key
                                val chipColor = statusColors[key] ?: Violet
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (isSelected) chipColor.copy(alpha = 0.22f) else Color.Transparent)
                                        .border(1.dp, if (isSelected) chipColor else chipColor.copy(alpha = 0.35f), RoundedCornerShape(50))
                                        .clickable { viewModel.setReadingStatus(if (isSelected) null else key) }
                                        .padding(horizontal = 14.dp, vertical = 6.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isSelected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = chipColor, modifier = Modifier.size(13.dp).padding(end = 4.dp))
                                        Text(label, color = if (isSelected) chipColor else TextSecondary, fontSize = 13.sp, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Kategorie ─────────────────────────────────────────────────
                if (allCategories.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(text = "KATEGORIE", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                allCategories.forEach { cat ->
                                    CategoryToggleChip(category = cat, selected = cat.id in categoryIds, onClick = { viewModel.toggleCategory(cat.id) })
                                }
                            }
                        }
                    }
                }

                // ── Podobné manga ─────────────────────────────────────────────
                if (relatedManga.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "PODOBNÉ",
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = Violet,
                                modifier = Modifier.padding(bottom = 10.dp),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(relatedManga) { related ->
                                RelatedMangaCard(
                                    manga = related,
                                    onClick = { onOpenManga("${related.sourceId}::${related.url}") },
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                // ── Hodnocení (#41) ────────────────────────────────────────────
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = "HODNOCENÍ",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                            color = Violet,
                            modifier = Modifier.padding(bottom = 12.dp),
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            // Velké skóre
                            val scoreText = if (userRating != null)
                                String.format("%.1f", userRating!! / 10.0) else "—"
                            val scoreColor = when {
                                userRating == null -> TextSecondary
                                userRating!! >= 85 -> GlowCyan
                                userRating!! >= 70 -> Color(0xFF10B981)
                                userRating!! >= 50 -> Color(0xFFF59E0B)
                                else              -> Color(0xFFEF4444)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = scoreText,
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = scoreColor,
                                    lineHeight = 36.sp,
                                )
                                Text(
                                    text = "/ 10",
                                    fontSize = 12.sp,
                                    color = TextSecondary,
                                )
                            }
                            // Slider
                            Column(modifier = Modifier.weight(1f)) {
                                Slider(
                                    value = (userRating ?: 0).toFloat(),
                                    onValueChange = { viewModel.setRating(it.roundToInt()) },
                                    valueRange = 0f..100f,
                                    steps = 19,
                                    colors = SliderDefaults.colors(
                                        thumbColor = scoreColor,
                                        activeTrackColor = scoreColor,
                                        inactiveTrackColor = TextSecondary.copy(alpha = 0.2f),
                                        activeTickColor = Color.Transparent,
                                        inactiveTickColor = Color.Transparent,
                                    ),
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("0", color = TextSecondary, fontSize = 10.sp)
                                    Text("5", color = TextSecondary, fontSize = 10.sp)
                                    Text("10", color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                            // Smazat hodnocení
                            if (userRating != null) {
                                IconButton(
                                    onClick = { viewModel.clearRating() },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Smazat hodnocení",
                                        tint = TextSecondary.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // ── MAL tracking ──────────────────────────────────────────────
                item {
                    val malBlue = Color(0xFF2E51A2)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(
                            text = "MYANIMELIST",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                            color = malBlue,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        if (malId != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(malBlue.copy(alpha = 0.08f))
                                    .border(1.dp, malBlue.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                    .clickable { viewModel.openMalPage(context) }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text("MAL ID: $malId", color = TextPrimary, fontSize = 14.sp)
                                    if (malScore != null) {
                                        Text("Skóre: ${String.format("%.2f", malScore)}", color = TextSecondary, fontSize = 12.sp)
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(onClick = {
                                        malSearchQuery = manga?.title ?: ""
                                        viewModel.searchMal(malSearchQuery)
                                        showMalSheet = true
                                    }) { Text("Změnit", color = malBlue, fontSize = 12.sp) }
                                    IconButton(onClick = { viewModel.unlinkMal() }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Close, contentDescription = "Odpojit", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        } else {
                            OutlinedButton(
                                onClick = {
                                    malSearchQuery = manga?.title ?: ""
                                    viewModel.searchMal(malSearchQuery)
                                    showMalSheet = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                border = BorderStroke(1.dp, malBlue.copy(alpha = 0.4f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = malBlue),
                            ) {
                                Text("Propojit s MyAnimeList")
                            }
                            if (!viewModel.malHasClientId) {
                                Text(
                                    "Pro vyhledávání nastav MAL_CLIENT_ID v local.properties (myanimelist.net/apiconfig)",
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }

                // ── AI doporučení (#37) ────────────────────────────────────────
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(GlowCyan.copy(alpha = 0.06f))
                            .border(1.dp, GlowCyan.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = GlowCyan, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("AI ANALÝZA", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = GlowCyan)
                        }
                        Spacer(Modifier.height(8.dp))
                        when {
                            aiInsight != null -> Text(aiInsight!!, color = TextPrimary, fontSize = 13.sp)
                            aiInsightLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = GlowCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Analyzuji…", color = TextSecondary, fontSize = 12.sp)
                            }
                            else -> TextButton(onClick = { viewModel.loadAiInsight() }, contentPadding = PaddingValues(0.dp)) {
                                Text("Zobrazit AI analýzu ✨", color = GlowCyan, fontSize = 13.sp)
                            }
                        }
                    }
                }

                // ── Tagy (#26) ────────────────────────────────────────────────
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "TAGY", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.weight(1f))
                            IconButton(onClick = { showAddTagField = !showAddTagField }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Add, contentDescription = "Přidat tag", tint = TextSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (showAddTagField) {
                            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                BasicTextField(
                                    value = addTagText,
                                    onValueChange = { addTagText = it },
                                    singleLine = true,
                                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                                    decorationBox = { inner ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White.copy(alpha = 0.06f))
                                                .border(1.dp, GlowCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                        ) {
                                            if (addTagText.isEmpty()) Text("Nový tag…", color = TextSecondary, fontSize = 13.sp)
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    viewModel.addTag(addTagText)
                                    addTagText = ""
                                    showAddTagField = false
                                }) { Text("OK", color = GlowCyan) }
                            }
                        }
                        if (mangaTags.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                mangaTags.forEach { tagEntity ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(GlowCyan.copy(alpha = 0.12f))
                                            .border(1.dp, GlowCyan.copy(alpha = 0.4f), RoundedCornerShape(50))
                                            .clickable { viewModel.removeTag(tagEntity.tag) }
                                            .padding(horizontal = 10.dp, vertical = 3.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(tagEntity.tag, color = GlowCyan, fontSize = 11.sp)
                                            Icon(Icons.Filled.Close, contentDescription = "Odebrat", tint = GlowCyan.copy(alpha = 0.7f), modifier = Modifier.size(11.dp).padding(start = 3.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            Text("Žádné tagy", color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }

                // ── Poznámky (#27) ────────────────────────────────────────────
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(text = "POZNÁMKY", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 6.dp))
                        BasicTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                            decorationBox = { inner ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                        .padding(12.dp),
                                ) {
                                    if (noteText.isEmpty()) Text("Přidej poznámku k tomuto mangu…", color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
                                    inner()
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (noteText != (mangaNote?.content ?: "")) {
                            TextButton(
                                onClick = { viewModel.saveNote(noteText) },
                                modifier = Modifier.align(Alignment.End),
                            ) { Text("Uložit", color = GlowViolet) }
                        }
                    }
                }

                // ── Continue / Start reading ──────────────────────────────────
                continueChapter?.let { chapter ->
                    item {
                        val hasHistory = manga?.lastReadChapterId != null
                        var showReadMenu by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Brush.linearGradient(listOf(GlowViolet.copy(alpha = 0.85f), GlowCyan.copy(alpha = 0.6f)))),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Hlavní oblast — normální čtení
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenChapter(chapter.id) }
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                Column(modifier = Modifier.padding(start = 12.dp)) {
                                    Text(text = if (hasHistory) "Pokračovat ve čtení" else "Začít číst", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(
                                        text = if (hasHistory) "${chapter.name} · str. ${chapter.lastPageRead + 1}" else chapter.name,
                                        color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // Oddělovač
                            Box(modifier = Modifier.width(1.dp).height(44.dp).background(Color.White.copy(alpha = 0.25f)))
                            // Šipka — dropdown s volbami
                            Box {
                                IconButton(
                                    onClick = { showReadMenu = true },
                                    modifier = Modifier.padding(horizontal = 4.dp),
                                ) {
                                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Možnosti čtení", tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded = showReadMenu,
                                    onDismissRequest = { showReadMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Číst normálně") },
                                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                        onClick = { showReadMenu = false; onOpenChapter(chapter.id) },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Číst anonymně") },
                                        leadingIcon = { Icon(Icons.Filled.VisibilityOff, contentDescription = null) },
                                        onClick = { showReadMenu = false; onOpenChapterIncognito(chapter.id) },
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Chapters header se sort + bulk download ───────────────────
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                    // Auto-download toggle (#32)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, GlowViolet.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .clickable { viewModel.toggleAutoDownload() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Auto-stahování nových kapitol", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = autoDownload,
                            onCheckedChange = { viewModel.toggleAutoDownload() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    // Vyloučit z aktualizací
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .border(1.dp, GlowViolet.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                            .clickable { viewModel.toggleExcludeFromUpdates() }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Vyloučit z automatických aktualizací", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Switch(
                            checked = excludeFromUpdates,
                            onCheckedChange = { viewModel.toggleExcludeFromUpdates() },
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "KAPITOLY", style = MaterialTheme.typography.labelSmall, color = Violet, letterSpacing = 2.sp, modifier = Modifier.weight(1f))

                        // Mark all read
                        IconButton(onClick = { viewModel.markAllRead() }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.DoneAll, contentDescription = "Označit vše přečtené", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        // First unread jump (#33)
                        if (firstUnread != null) {
                            IconButton(
                                onClick = { firstUnread?.let { onOpenChapter(it.id) } },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Filled.SkipNext, contentDescription = "Přejít na první nepřečtenou", tint = GlowCyan, modifier = Modifier.size(18.dp))
                            }
                        }

                        // Grid/List toggle (#34)
                        IconButton(
                            onClick = { chapterGridView = !chapterGridView },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(if (chapterGridView) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, contentDescription = "Přepnout zobrazení", tint = if (chapterGridView) GlowViolet else TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        // Volume grouping toggle
                        IconButton(
                            onClick = { groupByVolume = !groupByVolume },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Seskupit po volumech", tint = if (groupByVolume) GlowCyan else TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        // Chapter search toggle
                        IconButton(
                            onClick = {
                                chapterSearchActive = !chapterSearchActive
                                if (!chapterSearchActive) viewModel.setChapterFilter("")
                            },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = "Hledat kapitolu", tint = if (chapterSearchActive) GlowCyan else TextSecondary, modifier = Modifier.size(18.dp))
                        }

                        // Bulk download dropdown
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(GlowCyan.copy(alpha = 0.08f))
                                    .pointerInput(Unit) { detectTapGestures(onTap = { showBulkMenu = true }) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = "Stáhnout", tint = TextSecondary, modifier = Modifier.size(14.dp))
                                Text(text = "Stáhnout", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
                            }
                            DropdownMenu(expanded = showBulkMenu, onDismissRequest = { showBulkMenu = false }) {
                                DropdownMenuItem(text = { Text("Stáhnout vše") }, onClick = { viewModel.downloadAll(); showBulkMenu = false })
                                DropdownMenuItem(text = { Text("Stáhnout nepřečtené") }, onClick = { viewModel.downloadUnread(); showBulkMenu = false })
                            }
                        }

                        // Sort toggle
                        Row(
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlowViolet.copy(alpha = if (sortAscending) 0.18f else 0.08f))
                                .pointerInput(Unit) { detectTapGestures(onTap = { viewModel.toggleSort() }) }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Seřadit", tint = if (sortAscending) GlowViolet else TextSecondary, modifier = Modifier.size(14.dp))
                            Text(text = if (sortAscending) "Nejstarší" else "Nejnovější", color = if (sortAscending) GlowViolet else TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
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
                            Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                            BasicTextField(
                                value = chapterFilter,
                                onValueChange = { viewModel.setChapterFilter(it) },
                                singleLine = true,
                                textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                                decorationBox = { inner ->
                                    Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                                        if (chapterFilter.isEmpty()) Text("Hledat kapitolu…", color = TextSecondary, fontSize = 14.sp)
                                        inner()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                            if (chapterFilter.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setChapterFilter("") }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Filled.Close, contentDescription = "Vymazat", tint = TextSecondary, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                }

                // ── Status filter chips ───────────────────────────────────────
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val filters = listOf("ALL" to "Vše", "UNREAD" to "Nepřečtené", "READ" to "Přečtené", "DOWNLOADED" to "Stažené")
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
                                    Text("Všechny skupiny", color = if (selectedScanlator == null) Cyan else TextSecondary, fontSize = 10.sp)
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
                    item {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            modifier = Modifier.fillMaxWidth().height(((chapters.size / 4 + 1) * 60).dp.coerceAtMost(400.dp)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(chapters) { chapter ->
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (chapter.read) GlowCyan.copy(alpha = 0.08f) else GlowViolet.copy(alpha = 0.15f))
                                        .border(1.dp, if (chapter.read) GlowCyan.copy(alpha = 0.2f) else GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                        .clickable { onOpenChapter(chapter.id) }
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
                                    .background(com.haise.jiyu.ui.theme.NightBlue)
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    if (volume == "?") "Bez volumu" else "Volume $volume",
                                    color = Violet,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        items(chs, key = { it.id }) { chapter ->
                            GlassChapterRow(
                                chapter = chapter,
                                onOpen = { onOpenChapter(chapter.id) },
                                onDownload = { viewModel.downloadChapter(chapter) },
                                onMarkReadUpTo = { viewModel.markReadUpTo(chapter.id) },
                                onMarkAllOlderRead = { viewModel.markAllOlderAsRead(chapter) },
                                onMarkAllNewerUnread = { viewModel.markAllNewerAsUnread(chapter) },
                                onToggleRead = { viewModel.markChapterRead(chapter.id, !chapter.read) },
                                onAiSummary = { cb -> viewModel.getChapterSummary(chapter, cb) },
                            )
                        }
                    }
                } else {
                    items(chapters, key = { it.id }) { chapter ->
                        GlassChapterRow(
                            chapter = chapter,
                            onOpen = { onOpenChapter(chapter.id) },
                            onDownload = { viewModel.downloadChapter(chapter) },
                            onMarkReadUpTo = { viewModel.markReadUpTo(chapter.id) },
                            onMarkAllOlderRead = { viewModel.markAllOlderAsRead(chapter) },
                            onMarkAllNewerUnread = { viewModel.markAllNewerAsUnread(chapter) },
                            onToggleRead = { viewModel.markChapterRead(chapter.id, !chapter.read) },
                            onAiSummary = { cb -> viewModel.getChapterSummary(chapter, cb) },
                        )
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }

            PullToRefreshContainer(state = pullToRefreshState, modifier = Modifier.align(Alignment.TopCenter))
        }
    }

    // ── MAL search bottom sheet ────────────────────────────────────────────────
    if (showMalSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMalSheet = false; malSearchQuery = "" },
            containerColor = NightBlue,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Hledat na MyAnimeList", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                if (!viewModel.malHasClientId) {
                    Text(
                        "MAL_CLIENT_ID není nastaven. Zaregistruj appku na myanimelist.net/apiconfig a vlož CLIENT ID do local.properties.",
                        color = Color(0xFFF59E0B),
                        fontSize = 13.sp,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = malSearchQuery,
                            onValueChange = { malSearchQuery = it },
                            placeholder = { Text("Název mangy...", color = TextSecondary) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF2E51A2),
                                unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                            ),
                        )
                        IconButton(onClick = { viewModel.searchMal(malSearchQuery) }) {
                            Icon(Icons.Filled.Search, contentDescription = "Hledat", tint = Color(0xFF2E51A2))
                        }
                    }
                    if (malSearchLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFF2E51A2),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            malSearchResults.forEach { malManga ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.05f))
                                        .clickable {
                                            viewModel.linkMalId(malManga)
                                            showMalSheet = false
                                        }
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = malManga.coverUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(malManga.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (malManga.score != null) {
                                            Text("⭐ ${String.format("%.2f", malManga.score)}", color = Color(0xFFFFD700), fontSize = 12.sp)
                                        }
                                        malManga.status?.let { Text(it.replace("_", " "), color = TextSecondary, fontSize = 11.sp) }
                                    }
                                }
                            }
                            if (malSearchResults.isEmpty()) {
                                Text("Žádné výsledky. Zadej název mangy.", color = TextSecondary, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Category chip ─────────────────────────────────────────────────────────────

@Composable
private fun CategoryToggleChip(category: CategoryEntity, selected: Boolean, onClick: () -> Unit) {
    val color = remember(category.colorHex) {
        try { Color(android.graphics.Color.parseColor(category.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (selected) color else color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = color, modifier = Modifier.size(13.dp).padding(end = 4.dp))
            Text(text = category.name, color = if (selected) color else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        }
    }
}

// ── Chapter row ───────────────────────────────────────────────────────────────

@Composable
private fun GlassChapterRow(
    chapter: ChapterEntity,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onMarkReadUpTo: () -> Unit,
    onMarkAllOlderRead: () -> Unit = {},
    onMarkAllNewerUnread: () -> Unit = {},
    onToggleRead: () -> Unit = {},
    onAiSummary: ((String?) -> Unit) -> Unit = {},
) {
    val isRead = chapter.read
    var showMenu by remember { mutableStateOf(false) }
    var showAiDialog by remember { mutableStateOf(false) }
    var aiSummaryText by remember { mutableStateOf<String?>(null) }
    var aiSummaryLoading by remember { mutableStateOf(false) }

    if (showAiDialog) {
        AlertDialog(
            onDismissRequest = { showAiDialog = false },
            title = { Text(chapter.name, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) },
            text = {
                when {
                    aiSummaryLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = GlowCyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Generuji shrnutí…", color = TextSecondary, fontSize = 13.sp)
                    }
                    aiSummaryText != null -> Text(aiSummaryText!!, color = TextPrimary, fontSize = 13.sp)
                    else -> Text("Nepodařilo se získat shrnutí.", color = TextSecondary, fontSize = 13.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAiDialog = false }) { Text("Zavřít") }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(glassGradient)
            .border(1.dp, if (isRead) GlowCyan.copy(alpha = 0.1f) else GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { showMenu = true },
                )
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(6.dp).background(if (isRead) GlowCyan.copy(alpha = 0.4f) else GlowViolet, RoundedCornerShape(50)))
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(text = chapter.name, color = if (isRead) TextSecondary else TextPrimary, fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!chapter.scanlationGroup.isNullOrBlank()) {
                    Text(text = chapter.scanlationGroup, color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (chapter.dateUpload > 0L) {
                    val date = java.text.SimpleDateFormat("d. M. yyyy", java.util.Locale.getDefault())
                        .format(java.util.Date(chapter.dateUpload))
                    Text(text = date, color = TextSecondary.copy(alpha = 0.45f), fontSize = 10.sp)
                }
            }
            when (chapter.downloadStatus) {
                DownloadStatus.DOWNLOADED  -> Icon(Icons.Filled.CheckCircle, contentDescription = "Staženo", tint = Cyan, modifier = Modifier.size(18.dp))
                DownloadStatus.DOWNLOADING -> Text("↓", color = Violet, fontSize = 16.sp)
                DownloadStatus.QUEUED      -> Text("⏳", fontSize = 14.sp)
                DownloadStatus.ERROR       -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Download, contentDescription = "Zkusit znovu", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
                else                       -> IconButton(onClick = onDownload, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Download, contentDescription = "Stáhnout", tint = TextSecondary, modifier = Modifier.size(18.dp)) }
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            DropdownMenuItem(
                text = { Text("Označit toto i vše starší přečtené") },
                onClick = { onMarkReadUpTo(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Označit vše starší jako přečtené") },
                onClick = { onMarkAllOlderRead(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("Označit vše novější jako nepřečtené") },
                onClick = { onMarkAllNewerUnread(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text(if (isRead) "Označit jako nepřečtené" else "Označit jako přečtené") },
                onClick = { onToggleRead(); showMenu = false },
            )
            DropdownMenuItem(
                text = { Text("AI shrnutí ✨") },
                onClick = {
                    showMenu = false
                    showAiDialog = true
                    aiSummaryLoading = true
                    aiSummaryText = null
                    onAiSummary { result -> aiSummaryText = result; aiSummaryLoading = false }
                },
            )
        }
    }
}

@Composable
private fun RelatedMangaCard(manga: SManga, onClick: () -> Unit) {
    Column(
        modifier = Modifier.width(80.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, GlowViolet.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
        ) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = manga.title,
            color = TextSecondary,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
