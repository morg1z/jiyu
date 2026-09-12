package com.haise.jiyu.ui.updates

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.data.db.UpdateItem
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(
    onOpenChapter: (chapterId: String) -> Unit,
    onOpenManga: (mangaId: String) -> Unit,
    viewModel: UpdatesViewModel = hiltViewModel(),
) {
    val updates      by viewModel.updates.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val refreshError by viewModel.refreshError.collectAsStateWithLifecycle()
    var showOnlyUnread by remember { mutableStateOf(false) }
    val displayedUpdates = if (showOnlyUnread) updates.filter { !it.read } else updates

    val pullState      = rememberPullToRefreshState()
    val snackbarState  = remember { SnackbarHostState() }
    val scope          = rememberCoroutineScope()

    LaunchedEffect(refreshError) {
        val msg = refreshError ?: return@LaunchedEffect
        scope.launch { snackbarState.showSnackbar(msg) }
        viewModel.clearRefreshError()
    }

    // PullToRefreshBox si sám drží nested scroll i indikátor. Dřív to byl ruční Box +
    // PullToRefreshContainer a dvě LaunchedEffect, které si posílaly stav tam a zpět
    // (`pullState.isRefreshing` -> refresh(), a zpátky `endRefresh()`); od Material3 1.3
    // se stav předává rovnou z ViewModelu a ta smyčka odpadá.
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { viewModel.refresh() },
        modifier = Modifier.fillMaxSize(),
        state = pullState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding(),
                containerColor = NightBlue,
                color = Violet,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .statusBarsPadding(),
        ) {
            // Hlavicka je lambda, protoze se vklada DOVNITR scrollovaneho obsahu - jinak
            // zustava viset nahore a obsah jezdi pod ni. statusBarsPadding je proto na obalu
            // vys: na hlavicce by odscrolovalo pryc s ni a obsah by vjel pod stavovou listu.
            val header: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(NightBlue, Color.Transparent)))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.updates_title),
                    style = TextStyle(
                        brush = titleGradient,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 2.sp,
                    ),
                )
                val unreadCount = updates.count { !it.read }
                if (unreadCount > 0) {
                    Spacer(Modifier.width(12.dp))
                    Box(
                        modifier = Modifier
                            .background(Violet.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = pluralStringResource(R.plurals.updates_new_count, unreadCount, unreadCount),
                            color = Violet,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showOnlyUnread = !showOnlyUnread }) {
                    Icon(
                        TablerIcons.Filter,
                        contentDescription = if (showOnlyUnread) stringResource(R.string.updates_show_all) else stringResource(R.string.updates_unread_only),
                        tint = if (showOnlyUnread) Violet else TextSecondary,
                    )
                }
                if (unreadCount > 0) {
                    IconButton(onClick = { viewModel.markAllRead() }) {
                        Icon(
                            TablerIcons.Checks,
                            contentDescription = stringResource(R.string.updates_mark_all_read),
                            tint = Violet,
                        )
                    }
                }
            }

            }

            if (displayedUpdates.isEmpty()) {
                header()
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (showOnlyUnread) stringResource(R.string.updates_empty_unread) else stringResource(R.string.updates_empty),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                return@Column
            }

            // Group by date; items with dateUpload=0 go at the end by chapter number
            val noDateLabel = stringResource(R.string.updates_no_date)
            val (dated, undated) = displayedUpdates.partition { it.dateUpload > 0 }
            val grouped = dated
                .groupBy { SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(Date(it.dateUpload)) }
                .toMutableMap()
            if (undated.isNotEmpty()) {
                // Sort undated by chapterNumber descending so newest chapters appear first
                grouped[noDateLabel] = undated.sortedByDescending { it.chapterNumber }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { header() }
                grouped.forEach { (date, items) ->
                    item(key = "header_$date") {
                        Text(
                            text = date,
                            color = Violet,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    items(items, key = { it.chapterId }) { item ->
                        UpdateRow(
                            item = item,
                            onOpenChapter = { onOpenChapter(item.chapterId) },
                            onOpenManga = { onOpenManga(item.mangaId) },
                        )
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }

        SnackbarHost(
            hostState = snackbarState,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        )
    }
}

@Composable
private fun UpdateRow(
    item: UpdateItem,
    onOpenChapter: () -> Unit,
    onOpenManga: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenChapter)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onOpenManga),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.mangaTitle,
                color = if (item.read) TextSecondary else TextPrimary,
                fontSize = 14.sp,
                fontWeight = if (item.read) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.chapterName,
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!item.read) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(GlowViolet, RoundedCornerShape(4.dp)),
            )
        }
    }
}
