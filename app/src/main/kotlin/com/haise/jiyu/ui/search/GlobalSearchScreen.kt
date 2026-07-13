package com.haise.jiyu.ui.search

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.source.SManga
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@Composable
fun GlobalSearchScreen(
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit,
    viewModel: GlobalSearchViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val query   by viewModel.query.collectAsState()
    val savedSearches by viewModel.savedSearches.collectAsState()
    val pendingDuplicateAdd by viewModel.pendingDuplicateAdd.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxSize().background(screenGradient)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, NightBlue.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
            }
            Text(
                text = stringResource(R.string.search_title),
                style = TextStyle(brush = titleGradient, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            val isSaved = savedSearches.contains(inputText.trim())
            if (inputText.isNotBlank()) {
                IconButton(onClick = {
                    if (isSaved) viewModel.removeSavedSearch(inputText.trim())
                    else viewModel.saveSearch(inputText.trim())
                }) {
                    Icon(
                        if (isSaved) TablerIcons.Bookmark else TablerIcons.Bookmark,
                        contentDescription = if (isSaved) stringResource(R.string.search_remove_bookmark) else stringResource(R.string.search_save_search),
                        tint = if (isSaved) Violet else TextSecondary,
                    )
                }
            }
        }

        TextField(
            value = inputText,
            onValueChange = { inputText = it },
            placeholder = { Text(stringResource(R.string.browse_search_placeholder), color = TextSecondary) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                viewModel.search(inputText)
                focusManager.clearFocus()
            }),
            leadingIcon = { Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary) },
            textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = NightBlue,
                unfocusedContainerColor = NightBlue.copy(alpha = 0.7f),
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Cyan,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .glassBorder(14.dp),
        )

        if (results.isEmpty() && query.isBlank()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (savedSearches.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.search_saved_searches),
                        color = Violet,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(savedSearches) { saved ->
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        inputText = saved
                                        viewModel.search(saved)
                                        focusManager.clearFocus()
                                    }
                                    .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                                    .background(NightBlue, RoundedCornerShape(20.dp))
                                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(saved, color = TextPrimary, fontSize = 13.sp)
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { viewModel.removeSavedSearch(saved) }, modifier = Modifier.size(20.dp)) {
                                    Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_remove), tint = TextSecondary, modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.search_empty_hint),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 40.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp
                ),
            ) {
                items(results, key = { it.source.id }) { sourceResult ->
                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = sourceResult.source.name.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = Violet,
                                modifier = Modifier.weight(1f),
                            )
                            when {
                                sourceResult.loading ->
                                    JiyuLoadingIndicator(size = 14.dp, strokeWidth = 2.dp)
                                sourceResult.error != null ->
                                    Text(stringResource(R.string.search_error), color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                else ->
                                    Text(stringResource(R.string.search_result_count, sourceResult.results.size), color = TextSecondary, fontSize = 11.sp)
                            }
                        }

                        if (sourceResult.results.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(sourceResult.results) { manga ->
                                    MiniMangaCard(
                                        manga = manga,
                                        onClick = { viewModel.addToLibrary(manga) { id -> onOpenManga(id) } },
                                    )
                                }
                            }
                        } else if (!sourceResult.loading) {
                            Text(
                                text = if (sourceResult.error != null) stringResource(R.string.search_source_error) else stringResource(R.string.source_browse_no_results),
                                color = TextSecondary.copy(alpha = 0.5f),
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        HorizontalDivider(color = GlowViolet.copy(alpha = 0.1f), modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }

    if (pendingDuplicateAdd != null) {
        GlobalSearchDuplicateDialog(
            pending = pendingDuplicateAdd!!,
            onConfirm = { viewModel.confirmAddDespiteDuplicate() },
            onDismiss = { viewModel.cancelDuplicateAdd() },
        )
    }
}

@Composable
private fun GlobalSearchDuplicateDialog(
    pending: GlobalSearchViewModel.PendingAdd,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
        title = { Text(stringResource(R.string.source_browse_dup_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    stringResource(R.string.source_browse_dup_desc, pending.manga.title),
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
                pending.matches.forEach { match ->
                    Text(
                        stringResource(R.string.source_browse_dup_existing, match.sourceName, match.chapterCount),
                        color = TextPrimary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                val newCountText = pending.newChapterCount?.let { stringResource(R.string.source_browse_chapters_count, it) } ?: stringResource(R.string.source_browse_checking)
                Text(
                    stringResource(R.string.source_browse_dup_new, pending.newSourceName, newCountText),
                    color = GlowViolet,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.source_browse_add_anyway), color = GlowViolet) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = TextSecondary) }
        },
    )
}

@Composable
private fun MiniMangaCard(manga: SManga, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(90.dp)
            .clickable(onClick = onClick),
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
            color = TextPrimary,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
