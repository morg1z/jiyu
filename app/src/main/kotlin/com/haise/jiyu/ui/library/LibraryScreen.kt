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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
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

            // Vyhledávací pole
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, start = 4.dp, end = 4.dp)
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
                if (continueReading.isNotEmpty()) {
                    CarouselSection(title = stringResource(R.string.library_continue_reading)) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(continueReading, key = { it.manga.id }) { item ->
                                ContinueReadingCard(
                                    item = item,
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
                    CarouselSection(title = stringResource(R.string.library_recently_added)) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(recentlyAdded, key = { it.id }) { manga ->
                                SimpleMangaCard(manga = manga, onClick = { onOpenManga(manga.id) })
                            }
                        }
                    }
                }
                if (completed.isNotEmpty()) {
                    CarouselSection(title = stringResource(R.string.library_completed)) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(completed, key = { it.id }) { manga ->
                                SimpleMangaCard(manga = manga, onClick = { onOpenManga(manga.id) })
                            }
                        }
                    }
                }
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
private fun CarouselSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = Violet,
                modifier = Modifier.weight(1f),
            )
            Icon(TablerIcons.ChevronRight, contentDescription = stringResource(R.string.library_show_all), tint = TextSecondary, modifier = Modifier.size(18.dp))
        }
        content()
    }
}

@Composable
private fun ContinueReadingCard(item: ContinueReadingItem, onClick: () -> Unit) {
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
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(GlowViolet, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TablerIcons.PlayerPlay, contentDescription = null, tint = Color.White, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.detail_continue_short), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
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
        if (item.lastChapterName != null) {
            Text(
                text = "Kapitola ${formatChapterNumber(item.lastChapterNumber)} · ${item.lastChapterName}",
                color = TextSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun formatChapterNumber(number: Float?): String {
    if (number == null) return "?"
    return if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()
}

@Composable
private fun SimpleMangaCard(manga: MangaEntity, onClick: () -> Unit) {
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
