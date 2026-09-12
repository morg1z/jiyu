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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import androidx.compose.ui.res.pluralStringResource
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
 * Dialogy knihovny vytazene z MyListScreen.kt - prirazeni kategorii (hromadne i pro
 * jeden titul), sprava kategorii a prehled statistik.
 */
@Composable
internal fun BulkCategoryDialog(
    count: Int,
    categories: List<CategoryEntity>,
    onPickCategory: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(pluralStringResource(R.plurals.mylist_add_n_to_category, count, count), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                categories.forEach { cat ->
                    val color = remember(cat.colorHex) {
                        try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onPickCategory(cat.id); onDismiss() }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                        Spacer(Modifier.width(12.dp))
                        Text(cat.name, color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5)) } },
    )
}

@Composable
internal fun CategoryAssignDialog(manga: MangaEntity, allCategories: List<CategoryEntity>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    val catIds by viewModel.observeCategoryIdsForManga(manga.id).collectAsStateWithLifecycle()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(text = manga.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                allCategories.forEach { cat ->
                    val selected = cat.id in catIds
                    val color = remember(cat.colorHex) {
                        try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                if (selected) viewModel.removeMangaFromCategory(manga.id, cat.id)
                                else viewModel.addMangaToCategory(manga.id, cat.id)
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = {
                                if (selected) viewModel.removeMangaFromCategory(manga.id, cat.id)
                                else viewModel.addMangaToCategory(manga.id, cat.id)
                            },
                            colors = CheckboxDefaults.colors(checkedColor = color),
                        )
                        Text(cat.name, color = if (selected) color else Color(0xFFB0BEC5), modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done), color = GlowViolet) } },
    )
}

@Composable
internal fun ManageCategoriesDialog(categories: List<CategoryEntity>, viewModel: LibraryViewModel, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.mylist_categories_title), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                categories.forEach { cat ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val color = remember(cat.colorHex) { try { Color(android.graphics.Color.parseColor(cat.colorHex)) } catch (_: Exception) { Color(0xFF8B5CF6) } }
                        Box(modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
                        Text(text = cat.name, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        IconButton(onClick = { viewModel.deleteCategory(cat) }) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_delete), tint = Color(0xFFB0BEC5), modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text(stringResource(R.string.mylist_new_category_name), color = Color(0xFFB0BEC5), fontSize = 13.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (newName.isNotBlank()) { viewModel.createCategory(newName, viewModel.nextColor(categories)); newName = ""; focusManager.clearFocus() }
                    }),
                    textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = GlowViolet, unfocusedBorderColor = GlowViolet.copy(alpha = 0.3f), cursorColor = CyanLight),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (newName.isNotBlank()) { viewModel.createCategory(newName, viewModel.nextColor(categories)); newName = "" }
                onDismiss()
            }) { Text(stringResource(R.string.common_done), color = GlowViolet) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = Color(0xFFB0BEC5)) } },
    )
}

@Composable
internal fun StatsDialog(stats: ReadingStats, onDismiss: () -> Unit, onOpenExtended: () -> Unit = {}) {
    val totalMinutes = stats.readingTimeMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val timeLabel = when {
        hours > 0   -> stringResource(R.string.mylist_time_hours_minutes, hours, minutes)
        minutes > 0 -> stringResource(R.string.mylist_time_minutes, minutes)
        else        -> stringResource(R.string.mylist_time_less_than_minute)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.mylist_reading_stats_title), color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                StatRow(stringResource(R.string.mylist_chapters_read), "${stats.chaptersRead}")
                HorizontalDivider(color = GlowViolet.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
                StatRow(stringResource(R.string.mylist_pages_read), "${stats.pagesRead}")
                HorizontalDivider(color = GlowViolet.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 6.dp))
                StatRow(stringResource(R.string.stats_reading_time_label), timeLabel)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = GlowViolet) } },
        dismissButton = { TextButton(onClick = onOpenExtended) { Text(stringResource(R.string.mylist_detailed_stats), color = GlowViolet) } },
    )
}

/** Potvrzení dlouhého stisku na kartu v Knihovně - viz [LibraryScreen] a [LibrarySectionScreen]. */
@Composable
internal fun RemoveFromLibraryDialog(mangaTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.library_remove_confirm_title, mangaTitle), color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.library_remove_confirm_body), color = Color(0xFFB0BEC5)) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(stringResource(R.string.mylist_remove_from_library), color = Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5)) } },
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
