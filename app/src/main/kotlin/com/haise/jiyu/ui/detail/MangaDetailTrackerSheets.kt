package com.haise.jiyu.ui.detail

import compose.icons.TablerIcons
import compose.icons.tablericons.*

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.source.SManga
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import kotlin.math.roundToInt

/**
 * Vyhledavaci panely pro propojeni titulu s trackery (AniList, Kitsu, MangaUpdates, MAL).
 * Vytazeno z MangaDetailInfoScreen.kt, kde tvorily ~290 radku uvnitr jedine, pres tisic
 * radku dlouhe composable funkce. Chovani se nemeni - jen se stav, ktery panely mutuji
 * (dotaz a viditelnost), predava jako parametry misto zachytavani z okolniho scope.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AniListSearchSheet(
    viewModel: MangaDetailViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val aniListSearchLoading by viewModel.aniListSearchLoading.collectAsStateWithLifecycle()
    val aniListSearchResults by viewModel.aniListSearchResults.collectAsStateWithLifecycle()
    val aniListSheetColor = Color(0xFF2E51A2)
    ModalBottomSheet(
        onDismissRequest = { onDismiss(); onQueryChange("") },
        containerColor = NightBlue,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.detail_info_search_anilist_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onQueryChange(it) },
                    placeholder = { Text(stringResource(R.string.detail_info_manga_title_placeholder), color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = aniListSheetColor,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                IconButton(onClick = { viewModel.searchAniList(query) }) {
                    Icon(TablerIcons.Search, contentDescription = stringResource(R.string.common_search), tint = aniListSheetColor)
                }
            }
            if (aniListSearchLoading) {
                JiyuLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    aniListSearchResults.forEach { am ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable { viewModel.linkAniList(am); onDismiss() }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = am.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Text(am.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        }
                    }
                    if (aniListSearchResults.isEmpty()) Text(stringResource(R.string.detail_info_no_search_results), color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitsuSearchSheet(
    viewModel: MangaDetailViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val kitsuSearchLoading by viewModel.kitsuSearchLoading.collectAsStateWithLifecycle()
    val kitsuSearchResults by viewModel.kitsuSearchResults.collectAsStateWithLifecycle()
    val kitsuSheetColor = Color(0xFF51A351)
    ModalBottomSheet(
        onDismissRequest = { onDismiss(); onQueryChange("") },
        containerColor = NightBlue,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.detail_info_search_kitsu_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onQueryChange(it) },
                    placeholder = { Text(stringResource(R.string.detail_info_manga_title_placeholder), color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = kitsuSheetColor,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                IconButton(onClick = { viewModel.searchKitsu(query) }) {
                    Icon(TablerIcons.Search, contentDescription = stringResource(R.string.common_search), tint = kitsuSheetColor)
                }
            }
            if (kitsuSearchLoading) {
                JiyuLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kitsuSearchResults.forEach { km ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable { viewModel.linkKitsu(km); onDismiss() }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = km.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(km.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (km.score != null) Text("⭐ ${String.format("%.2f", km.score)}", color = Color(0xFFFFD700), fontSize = 12.sp)
                            }
                        }
                    }
                    if (kitsuSearchResults.isEmpty()) Text(stringResource(R.string.detail_info_no_search_results), color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MangaUpdatesSearchSheet(
    viewModel: MangaDetailViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val muSearchLoading by viewModel.muSearchLoading.collectAsStateWithLifecycle()
    val muSearchResults by viewModel.muSearchResults.collectAsStateWithLifecycle()
    val muSheetColor = Color(0xFF3B82F6)
    ModalBottomSheet(
        onDismissRequest = { onDismiss(); onQueryChange("") },
        containerColor = NightBlue,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.detail_info_search_mu_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { onQueryChange(it) },
                    placeholder = { Text(stringResource(R.string.detail_info_manga_title_placeholder), color = TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = muSheetColor,
                        unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                )
                IconButton(onClick = { viewModel.searchMu(query) }) {
                    Icon(TablerIcons.Search, contentDescription = stringResource(R.string.common_search), tint = muSheetColor)
                }
            }
            if (muSearchLoading) {
                JiyuLoadingIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    muSearchResults.forEach { mu ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .clickable { viewModel.linkMu(mu); onDismiss() }
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = mu.coverUrl,
                                contentDescription = null,
                                modifier = Modifier.size(50.dp, 70.dp).clip(RoundedCornerShape(6.dp)),
                                contentScale = ContentScale.Crop,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(mu.title, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (mu.year != null) Text(stringResource(R.string.detail_info_search_result_year, mu.year), color = TextSecondary, fontSize = 12.sp)
                            }
                        }
                    }
                    if (muSearchResults.isEmpty()) Text(stringResource(R.string.detail_info_no_search_results), color = TextSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MalSearchSheet(
    viewModel: MangaDetailViewModel,
    query: String,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val malSearchLoading by viewModel.malSearchLoading.collectAsStateWithLifecycle()
    val malSearchResults by viewModel.malSearchResults.collectAsStateWithLifecycle()
    ModalBottomSheet(
        onDismissRequest = { onDismiss(); onQueryChange("") },
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
            Text(stringResource(R.string.detail_info_search_mal_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (!viewModel.malHasClientId) {
                Text(
                    stringResource(R.string.detail_info_mal_no_client_id),
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
                        value = query,
                        onValueChange = { onQueryChange(it) },
                        placeholder = { Text(stringResource(R.string.detail_info_manga_title_placeholder), color = TextSecondary) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF2E51A2),
                            unfocusedBorderColor = TextSecondary.copy(alpha = 0.3f),
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                        ),
                    )
                    IconButton(onClick = { viewModel.searchMal(query) }) {
                        Icon(TablerIcons.Search, contentDescription = stringResource(R.string.common_search), tint = Color(0xFF2E51A2))
                    }
                }
                if (malSearchLoading) {
                    JiyuLoadingIndicator(
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
                                        onDismiss()
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
                            Text(stringResource(R.string.detail_info_no_search_results), color = TextSecondary, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
