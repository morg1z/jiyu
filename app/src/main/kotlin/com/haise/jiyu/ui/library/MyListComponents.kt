package com.haise.jiyu.ui.library

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
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
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.settings.ReadingStats
import com.haise.jiyu.ui.settings.SettingsViewModel
import com.haise.jiyu.ui.theme.CyanLight
import com.haise.jiyu.ui.theme.Danger
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.VioletLight
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.violetGlow

/**
 * Prvky seznamu knihovny vytazene z MyListScreen.kt - prazdny stav, lista hromadnych
 * akci, radek seznamu a spodni panel filtru/razeni vcetne jeho chipu.
 */
@Composable
internal fun LibraryEmptyState(hasSearch: Boolean, onOpenBrowse: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            if (hasSearch) {
                Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(52.dp).padding(bottom = 16.dp))
                Text(
                    stringResource(R.string.library_nothing_found),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.mylist_try_different_term_or_search),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .background(NightBlue, CircleShape)
                        .border(1.dp, CardBorder, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        TablerIcons.Book,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.mylist_no_titles_match_filter),
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.mylist_add_from_browse_or_change_filter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                )
                Box(
                    modifier = Modifier
                        .violetGlow()
                        .background(
                            Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))),
                            RoundedCornerShape(14.dp),
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onOpenBrowse)
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(
                        stringResource(R.string.library_browse_manga_button),
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

// ── Bulk action bar ───────────────────────────────────────────────────────────

@Composable
internal fun BulkActionBar(
    hasCategories: Boolean,
    onDownload: () -> Unit,
    onMarkRead: () -> Unit,
    onAddToCategory: () -> Unit,
    onDelete: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .background(
                Brush.linearGradient(listOf(NightBlue.copy(alpha = 0.95f), Color(0xFF0D1530).copy(alpha = 0.95f))),
                RoundedCornerShape(20.dp),
            )
            .border(1.dp, GlowViolet.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BulkAction(icon = TablerIcons.Download, label = stringResource(R.string.common_download), onClick = onDownload)
            BulkAction(icon = TablerIcons.Checks, label = stringResource(R.string.mylist_bulk_read), onClick = onMarkRead)
            if (hasCategories) {
                BulkAction(icon = TablerIcons.Folder, label = stringResource(R.string.mylist_bulk_category), onClick = onAddToCategory)
            }
            BulkAction(icon = TablerIcons.Trash, label = stringResource(R.string.common_remove), tint = Color(0xFFFF6B6B), onClick = onDelete)
        }
    }
}

@Composable
private fun BulkAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = TextSecondary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 11.sp)
    }
}

// ── Composables ───────────────────────────────────────────────────────────────

private fun contentTypeBadgeColor(contentType: String): Color = when (contentType) {
    "MANHWA" -> GlowViolet
    "MANHUA" -> GlowCyan
    "NOVEL"  -> Danger
    "COMIC"  -> Pink
    else     -> Color(0xFF6B7280) // MANGA a neznámé typy - neutrální šedá
}

@Composable
private fun readingStatusLabel(status: String?): String = when (status) {
    "READING"      -> stringResource(R.string.detail_reading_status_reading)
    "COMPLETED"    -> stringResource(R.string.detail_status_completed)
    "ON_HOLD"      -> stringResource(R.string.mylist_filter_on_hold)
    "PLAN_TO_READ" -> stringResource(R.string.mylist_filter_plan_to_read)
    "DROPPED"      -> stringResource(R.string.detail_reading_status_dropped)
    else           -> stringResource(R.string.mylist_status_unset)
}

private fun formatReadingHours(ms: Long): String {
    val hours = ms / 3_600_000L
    return if (hours < 1) "<1 h" else "$hours h"
}

@Composable
internal fun LibraryListRow(
    manga: MangaEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onMoreClick: () -> Unit,
    unreadCount: Int,
    totalCount: Int,
    hasDownloads: Boolean,
) {
    val readCount = (totalCount - unreadCount).coerceAtLeast(0)
    val progress = if (totalCount > 0) readCount.toFloat() / totalCount.toFloat() else 0f

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) GlowViolet.copy(alpha = 0.15f) else Color.Transparent)
                .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongPress() }) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(modifier = Modifier.width(64.dp).height(92.dp).clip(RoundedCornerShape(10.dp))) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .background(contentTypeBadgeColor(manga.contentType), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                ) {
                    Text(manga.contentType, color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold, lineHeight = 9.sp)
                }
                if (isSelected) {
                    Box(modifier = Modifier.fillMaxSize().background(GlowViolet.copy(alpha = 0.35f)))
                    Icon(
                        TablerIcons.CircleCheck, contentDescription = stringResource(R.string.mylist_selected_desc), tint = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(24.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(manga.title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(readingStatusLabel(manga.readingStatus), color = TextSecondary, fontSize = 12.sp)
                    if (manga.readingTimeMs > 0) {
                        Text(" • ${formatReadingHours(manga.readingTimeMs)}", color = TextSecondary, fontSize = 12.sp)
                    }
                    if (hasDownloads) {
                        Spacer(Modifier.width(6.dp))
                        Icon(TablerIcons.CloudDownload, contentDescription = stringResource(R.string.mylist_downloaded_offline), tint = GlowCyan, modifier = Modifier.size(12.dp))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Dual progress: reálný progress čtení (violet) + "finished" indikátor
                // (cyan checkmark) když uživatel označil titul jako COMPLETED. Progress bar
                // vždy ukazuje skutečný počet přečtených kapitol, ne 100 % jen kvůli statusu.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)).background(CardBorder)) {
                        Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().background(GlowViolet, RoundedCornerShape(50)))
                    }
                    if (manga.readingStatus == "COMPLETED") {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            TablerIcons.CircleCheck,
                            contentDescription = stringResource(R.string.detail_status_completed),
                            tint = GlowCyan,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
            if (totalCount > 0) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, GlowViolet.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row {
                        Text("$readCount", color = GlowViolet, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(" / $totalCount", color = TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            IconButton(onClick = onMoreClick, modifier = Modifier.size(28.dp)) {
                Icon(TablerIcons.DotsVertical, contentDescription = stringResource(R.string.detail_more_options), tint = TextSecondary, modifier = Modifier.size(18.dp))
            }
        }
        HorizontalDivider(color = CardBorder, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun FilterSortBottomSheet(
    categories: List<CategoryEntity>,
    selectedCategoryId: String?,
    contentTypeFilter: String,
    sortOption: LibrarySortOption,
    sortAscending: Boolean,
    onSelectCategory: (String?) -> Unit,
    onSelectContentType: (String) -> Unit,
    onSelectSort: (LibrarySortOption) -> Unit,
    onManageCategories: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.mylist_filter_and_sort), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))

            Text(stringResource(R.string.mylist_category_section), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategoryChip(label = stringResource(R.string.common_all), colorHex = "#8B5CF6", selected = selectedCategoryId == null, onClick = { onSelectCategory(null) })
                categories.forEach { cat ->
                    CategoryChip(label = cat.name, colorHex = cat.colorHex, selected = selectedCategoryId == cat.id, onClick = { onSelectCategory(cat.id) })
                }
                TextButton(onClick = onManageCategories) {
                    Icon(TablerIcons.Plus, contentDescription = stringResource(R.string.mylist_manage_categories), tint = GlowViolet, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(3.dp))
                    Text(stringResource(R.string.mylist_manage_categories), color = GlowViolet, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.mylist_content_type_section), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val types = listOf(
                "ALL" to stringResource(R.string.common_all),
                "MANGA" to stringResource(R.string.browse_filter_manga),
                "MANHWA" to stringResource(R.string.mylist_content_manhwa),
                "MANHUA" to stringResource(R.string.mylist_content_manhua),
                "NOVEL" to stringResource(R.string.mylist_content_novel),
                "COMIC" to stringResource(R.string.mylist_content_comic),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { (key, label) ->
                    ContentTypeChip(label = label, selected = contentTypeFilter == key, onClick = { onSelectContentType(key) })
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.mylist_sort_by), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            val sortOptions = listOf(
                LibrarySortOption.TITLE        to stringResource(R.string.source_browse_sort_title),
                LibrarySortOption.LAST_UPDATED to stringResource(R.string.mylist_sort_last_updated),
                LibrarySortOption.UNREAD_COUNT to stringResource(R.string.detail_filter_unread),
                LibrarySortOption.DATE_ADDED   to stringResource(R.string.mylist_sort_date_added),
                LibrarySortOption.RANDOM       to stringResource(R.string.mylist_sort_random),
            )
            sortOptions.forEach { (option, label) ->
                val selected = option == sortOption
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectSort(option) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (selected) GlowViolet else TextPrimary, fontSize = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    if (selected) {
                        Icon(
                            if (sortAscending) TablerIcons.ArrowUp else TablerIcons.ArrowDown,
                            contentDescription = if (sortAscending) stringResource(R.string.mylist_ascending) else stringResource(R.string.mylist_descending),
                            tint = GlowViolet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReadingStatusChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) GlowViolet else NightBlue)
            .border(1.dp, if (selected) GlowViolet else CardBorder, RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = if (selected) Color.White else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun ContentTypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) GlowCyan else TextSecondary
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) GlowCyan.copy(alpha = 0.18f) else Color.Transparent)
            .border(1.dp, if (selected) GlowCyan.copy(alpha = 0.7f) else TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = color, fontSize = 12.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun CategoryChip(label: String, colorHex: String, selected: Boolean, onClick: () -> Unit) {
    val color = remember(colorHex) {
        try { Color(android.graphics.Color.parseColor(colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
    }
    Box(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) color.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.dp, if (selected) color else color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = if (selected) color else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}
