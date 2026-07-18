package com.haise.jiyu.ui.library

import com.haise.jiyu.ui.components.JiyuLoadingIndicator

import compose.icons.TablerIcons
import compose.icons.tablericons.*

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.data.db.ContinueReadingItem
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.Warning
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import com.haise.jiyu.ui.theme.violetGlow

/** Dashboard "Knihovna" - karusely (Pokračovat/Nedávno přidané/Dokončené). Celá filtrovaná knihovna viz [MyListScreen]. */
@Composable
fun LibraryScreen(
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChapter: (String) -> Unit = {},
    onOpenStats: () -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val library         by viewModel.library.collectAsState()
    val searchQuery      by viewModel.searchQuery.collectAsState()
    val continueReading   by viewModel.continueReading.collectAsState()
    val recentlyAdded      by viewModel.recentlyAdded.collectAsState()
    val completed           by viewModel.completed.collectAsState()
    val unreadCounts         by viewModel.unreadCounts.collectAsState()
    val totalCounts           by viewModel.totalCounts.collectAsState()
    val libraryCount            by viewModel.libraryCount.collectAsState()
    val favoriteCount             by viewModel.favoriteCount.collectAsState()
    val todayReadingMinutes        by viewModel.todayReadingMinutes.collectAsState()
    val contentTypeFilter            by viewModel.contentTypeFilter.collectAsState()

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
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "JIYU",
                    style = TextStyle(brush = titleGradient, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onOpenSettings) {
                    Icon(TablerIcons.Settings, contentDescription = stringResource(R.string.settings_title), tint = TextSecondary)
                }
            }

            // Vyhledávací pole + filtr
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                        .height(42.dp)
                        .clip(RoundedCornerShape(50.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (searchQuery.isNotEmpty()) GlowViolet.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(50.dp))
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TablerIcons.Search, contentDescription = null, tint = if (searchQuery.isNotEmpty()) GlowViolet else TextSecondary.copy(alpha = 0.6f), modifier = Modifier.size(17.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {}),
                        decorationBox = { inner ->
                            Box(modifier = Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                if (searchQuery.isEmpty()) Text(stringResource(R.string.library_search_placeholder), color = TextSecondary.copy(alpha = 0.5f), fontSize = 14.sp)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }, modifier = Modifier.size(28.dp)) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_clear), tint = TextSecondary, modifier = Modifier.size(15.dp))
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                ContentTypeFilterButton(
                    selected = contentTypeFilter,
                    onSelect = { viewModel.setContentTypeFilter(it) },
                )
            }
        }

        if (searchQuery.isNotEmpty()) {
            // ── Výsledky hledání (plochý grid) ──────────────────────────────
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            if (library.isEmpty()) {
                DashboardEmptyState(text = stringResource(R.string.library_nothing_found), subtitle = stringResource(R.string.library_try_different_term))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 16.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(library, key = { it.id }) { manga ->
                        SearchResultCard(manga = manga, onClick = { onOpenManga(manga.id) })
                    }
                }
            }
        } else if (continueReading.isEmpty() && recentlyAdded.isEmpty() && completed.isEmpty()) {
            // ── Prázdný stav ─────────────────────────────────────────────────
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
                    Box(
                        modifier = Modifier.size(96.dp).background(NightBlue, CircleShape).border(1.dp, CardBorder, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(TablerIcons.Book, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(44.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(stringResource(R.string.library_empty_title), style = MaterialTheme.typography.titleLarge, color = TextPrimary, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text(
                        stringResource(R.string.library_empty_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                    )
                    Box(
                        modifier = Modifier
                            .violetGlow()
                            .background(Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))), RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onOpenBrowse)
                            .padding(horizontal = 28.dp, vertical = 14.dp),
                    ) {
                        Text(stringResource(R.string.library_browse_manga_button), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    }
                }
            }
        } else {
            // ── Karusely ─────────────────────────────────────────────────────
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                val heroItem = continueReading.firstOrNull()
                if (heroItem != null) {
                    HeroContinueReadingCard(
                        item = heroItem,
                        progressPercent = progressPercentFor(heroItem.manga.id, unreadCounts, totalCounts),
                        onFavoriteToggle = { viewModel.toggleFavorite(heroItem.manga.id, heroItem.manga.isFavorite) },
                        onClick = {
                            val chapterId = heroItem.manga.lastReadChapterId
                            if (chapterId != null) onOpenChapter(chapterId) else onOpenManga(heroItem.manga.id)
                        },
                    )
                }

                LibraryStatsRow(
                    libraryCount = libraryCount,
                    favoriteCount = favoriteCount,
                    todayReadingMinutes = todayReadingMinutes,
                    modifier = Modifier.padding(top = 14.dp),
                )

                if (continueReading.isNotEmpty()) {
                    CarouselSection(title = stringResource(R.string.library_continue_reading), count = continueReading.size) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(continueReading, key = { it.manga.id }) { item ->
                                ContinueReadingCard(
                                    item = item,
                                    progressPercent = progressPercentFor(item.manga.id, unreadCounts, totalCounts),
                                    onClick = {
                                        val chapterId = item.manga.lastReadChapterId
                                        if (chapterId != null) onOpenChapter(chapterId) else onOpenManga(item.manga.id)
                                    },
                                )
                            }
                        }
                    }
                }
                if (recentlyAdded.isNotEmpty()) {
                    CarouselSection(title = stringResource(R.string.library_recently_added), count = recentlyAdded.size) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recentlyAdded, key = { it.id }) { manga ->
                                SimpleMangaCard(manga = manga, showNewBadge = true, onClick = { onOpenManga(manga.id) })
                            }
                        }
                    }
                }
                if (completed.isNotEmpty()) {
                    CarouselSection(title = stringResource(R.string.library_completed), count = completed.size) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(completed, key = { it.id }) { manga ->
                                SimpleMangaCard(manga = manga, showNewBadge = false, onClick = { onOpenManga(manga.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun progressPercentFor(mangaId: String, unreadCounts: Map<String, Int>, totalCounts: Map<String, Int>): Int {
    val total = totalCounts[mangaId] ?: 0
    if (total <= 0) return 0
    val unread = unreadCounts[mangaId] ?: 0
    val read = (total - unread).coerceAtLeast(0)
    return ((read.toFloat() / total.toFloat()) * 100f).toInt().coerceIn(0, 100)
}

@Composable
private fun contentTypeLabel(type: String): String = when (type) {
    "MANHWA" -> stringResource(R.string.mylist_content_manhwa)
    "MANHUA" -> stringResource(R.string.mylist_content_manhua)
    "NOVEL"  -> stringResource(R.string.mylist_content_novel)
    "COMIC"  -> stringResource(R.string.mylist_content_comic)
    else     -> stringResource(R.string.browse_filter_manga)
}

@Composable
private fun ContentTypeFilterButton(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val isActive = selected != "ALL"
    Box {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, if (isActive) GlowViolet.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp)),
        ) {
            Icon(TablerIcons.AdjustmentsHorizontal, contentDescription = stringResource(R.string.library_filter), tint = if (isActive) GlowViolet else TextSecondary, modifier = Modifier.size(19.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf("ALL" to stringResource(R.string.common_all), "MANGA" to stringResource(R.string.browse_filter_manga),
                "MANHWA" to stringResource(R.string.mylist_content_manhwa), "MANHUA" to stringResource(R.string.mylist_content_manhua),
                "NOVEL" to stringResource(R.string.mylist_content_novel), "COMIC" to stringResource(R.string.mylist_content_comic),
            ).forEach { (key, label) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (selected == key) GlowViolet else TextPrimary) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun DashboardEmptyState(text: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(48.dp))
            Spacer(Modifier.height(12.dp))
            Text(text, style = MaterialTheme.typography.titleMedium, color = TextPrimary, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun HeroContinueReadingCard(
    item: ContinueReadingItem,
    progressPercent: Int,
    onFavoriteToggle: () -> Unit,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TablerIcons.Flame, contentDescription = null, tint = Violet, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.library_continue_reading),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = Violet,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = TablerIcons.Bookmark,
                    contentDescription = if (item.manga.isFavorite) stringResource(R.string.detail_remove_favorite) else stringResource(R.string.detail_add_favorite),
                    tint = if (item.manga.isFavorite) Pink else TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(NightBlue)
                .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
                .clickable(onClick = onClick)
                .padding(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(100.dp)
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
            ) {
                AsyncImage(model = item.manga.coverUrl, contentDescription = item.manga.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .background(GlowViolet.copy(alpha = 0.18f), RoundedCornerShape(50))
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(contentTypeLabel(item.manga.contentType), color = GlowViolet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(8.dp))
                Text(item.manga.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Kapitola ${formatChapterNumber(item.lastChapterNumber)}",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(CardBorder)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressPercent / 100f)
                            .fillMaxHeight()
                            .background(Brush.horizontalGradient(listOf(GlowViolet, GlowCyan)), RoundedCornerShape(50)),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.library_percent_read, progressPercent), color = TextSecondary, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Brush.horizontalGradient(listOf(GlowViolet, GlowCyan)))
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(TablerIcons.PlayerPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.detail_continue_short), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryStatsRow(libraryCount: Int, favoriteCount: Int, todayReadingMinutes: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox(
            icon = TablerIcons.Book,
            tint = Violet,
            value = "$libraryCount",
            label = stringResource(R.string.library_stats_books),
            modifier = Modifier.weight(1f),
        )
        StatBox(
            icon = TablerIcons.Heart,
            tint = Pink,
            value = "$favoriteCount",
            label = stringResource(R.string.library_stats_favorites),
            modifier = Modifier.weight(1f),
        )
        StatBox(
            icon = TablerIcons.Clock,
            tint = Warning,
            value = "$todayReadingMinutes",
            unit = stringResource(R.string.library_minutes_short),
            label = stringResource(R.string.library_stats_read_today),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StatBox(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    value: String,
    label: String,
    unit: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(NightBlue)
            .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 1.dp))
            }
        }
        Text(label, color = TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CarouselSection(title: String, count: Int, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = Violet,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .background(GlowViolet, RoundedCornerShape(50))
                    .padding(horizontal = 7.dp, vertical = 1.dp),
            ) {
                Text("$count", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(stringResource(R.string.library_show_all), color = TextSecondary, fontSize = 12.sp)
            Icon(TablerIcons.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
        }
        content()
    }
}

@Composable
private fun ContinueReadingCard(item: ContinueReadingItem, progressPercent: Int, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(130.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .violetGlow(radius = 12f, alpha = 0.12f)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        ) {
            AsyncImage(
                model = item.manga.coverUrl,
                contentDescription = item.manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEA070B14)))),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(GlowViolet, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            ) {
                Text(formatChapterNumber(item.lastChapterNumber), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                text = "$progressPercent %",
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 11.dp),
            )
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.15f))) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progressPercent / 100f)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(GlowViolet, GlowCyan))),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.manga.title,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Kapitola ${formatChapterNumber(item.lastChapterNumber)}",
            color = TextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun formatChapterNumber(number: Float?): String {
    if (number == null) return "?"
    return if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()
}

@Composable
private fun SimpleMangaCard(manga: MangaEntity, showNewBadge: Boolean, onClick: () -> Unit) {
    Column(modifier = Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .violetGlow(radius = 10f, alpha = 0.1f)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, GlowViolet.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
        ) {
            AsyncImage(
                model = manga.coverUrl,
                contentDescription = manga.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (showNewBadge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(5.dp)
                        .background(GlowViolet, RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(stringResource(R.string.library_new_badge), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = manga.title,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp,
        )
    }
}

@Composable
private fun SearchResultCard(manga: MangaEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(0.68f)
            .violetGlow(radius = 12f, alpha = 0.12f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(model = manga.coverUrl, contentDescription = manga.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp).align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE5070B14)))),
        )
        Text(
            text = manga.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 7.dp, vertical = 6.dp),
        )
    }
}
