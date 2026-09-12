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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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

/** Hlavička podstránky Detailů - zpět + gradientní titulek, stejný styl jako zbytek appky. */
@Composable
private fun DetailSubScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
        }
        Text(
            text = title,
            style = TextStyle(brush = titleGradient, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun MangaDetailInfoScreen(
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit = {},
    viewModel: MangaDetailViewModel = hiltViewModel(),
) {
    val manga            by viewModel.manga.collectAsStateWithLifecycle()
    val relatedManga     by viewModel.relatedManga.collectAsStateWithLifecycle()
    val allCategories    by viewModel.allCategories.collectAsStateWithLifecycle()
    val categoryIds      by viewModel.mangaCategoryIds.collectAsStateWithLifecycle()
    val autoDownload     by viewModel.autoDownload.collectAsStateWithLifecycle()
    val mangaNote        by viewModel.mangaNote.collectAsStateWithLifecycle()
    val mangaTags        by viewModel.mangaTags.collectAsStateWithLifecycle()
    val glossary         by viewModel.glossary.collectAsStateWithLifecycle()
    val defaultTargetLanguage by viewModel.defaultTargetLanguage.collectAsStateWithLifecycle()
    val userRating       by viewModel.userRating.collectAsStateWithLifecycle()
    val excludeFromUpdates  by viewModel.excludeFromUpdates.collectAsStateWithLifecycle()
    val malId               by viewModel.malId.collectAsStateWithLifecycle()
    val malScore            by viewModel.malScore.collectAsStateWithLifecycle()
    var showMalSheet        by remember { mutableStateOf(false) }
    var malSearchQuery      by remember { mutableStateOf("") }
    val aniListIsLoggedIn   by viewModel.aniListIsLoggedIn.collectAsStateWithLifecycle()
    val aniListId           by viewModel.aniListId.collectAsStateWithLifecycle()
    val kitsuId             by viewModel.kitsuId.collectAsStateWithLifecycle()
    val kitsuScore          by viewModel.kitsuScore.collectAsStateWithLifecycle()
    val kitsuIsLoggedIn     by viewModel.kitsuIsLoggedIn.collectAsStateWithLifecycle()
    var showAniListSheet    by remember { mutableStateOf(false) }
    var aniListSearchQuery  by remember { mutableStateOf("") }
    var showKitsuSheet      by remember { mutableStateOf(false) }
    var kitsuSearchQuery    by remember { mutableStateOf("") }
    val muId                by viewModel.muId.collectAsStateWithLifecycle()
    val muIsLoggedIn        by viewModel.muIsLoggedIn.collectAsStateWithLifecycle()
    var showMuSheet         by remember { mutableStateOf(false) }
    var muSearchQuery       by remember { mutableStateOf("") }
    val context             = androidx.compose.ui.platform.LocalContext.current
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var noteText by remember(mangaNote) { mutableStateOf(mangaNote?.content ?: "") }
    var contextNoteText by remember(manga) { mutableStateOf(manga?.translationContextNote ?: "") }
    var addTagText by remember { mutableStateOf("") }
    var showAddTagField by remember { mutableStateOf(false) }
    var showAddGlossaryField by remember { mutableStateOf(false) }
    var glossarySourceText by remember { mutableStateOf("") }
    var glossaryTargetText by remember { mutableStateOf("") }
    var glossaryProtectExact by remember { mutableStateOf(false) }

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                DetailSubScreenHeader(title = manga?.title ?: stringResource(R.string.detail_info_title_fallback), onBack = onBack)

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = navBottom + 24.dp),
                ) {

                    // ── INFO ──────────────────────────────────────────────────
                    item {
                        val m = manga
                        if (m != null && (m.author != null || m.year != null || m.genres.isNotBlank())) {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(stringResource(R.string.detail_info_section_info), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 8.dp))
                                if (m.author != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                        Text(stringResource(R.string.detail_info_author_label), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                                        Text(m.author, color = TextPrimary, fontSize = 13.sp)
                                    }
                                }
                                if (m.artist != null && m.artist != m.author) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                        Text(stringResource(R.string.detail_info_artist_label), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                                        Text(m.artist, color = TextPrimary, fontSize = 13.sp)
                                    }
                                }
                                if (m.year != null) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                                        Text(stringResource(R.string.detail_info_year_label), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                                        Text(m.year.toString(), color = TextPrimary, fontSize = 13.sp)
                                    }
                                }
                                if (m.genres.isNotBlank()) {
                                    val genreList = m.genres.split(",").filter { it.isNotBlank() }
                                    if (genreList.isNotEmpty()) {
                                        Text(stringResource(R.string.detail_info_genres_label), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp, top = 2.dp))
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

                    // ── Směr čtení ────────────────────────────────────────────
                    item {
                        var dirDropdownExpanded by remember { mutableStateOf(false) }
                        val currentDir = manga?.readerDirectionOverride
                        val dirLabel = when (currentDir) {
                            "LTR"     -> stringResource(R.string.detail_info_dir_ltr)
                            "RTL"     -> stringResource(R.string.detail_info_dir_rtl)
                            "WEBTOON" -> stringResource(R.string.detail_info_dir_webtoon)
                            else      -> stringResource(R.string.detail_info_dir_default)
                        }
                        val dirDefaultShort = stringResource(R.string.detail_info_dir_default_short)
                        val dirWebtoonShort = stringResource(R.string.detail_info_dir_webtoon_short)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(stringResource(R.string.detail_info_reader_direction_label), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.width(100.dp))
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
                                    listOf(null to dirDefaultShort, "LTR" to "LTR", "RTL" to "RTL", "WEBTOON" to dirWebtoonShort).forEach { (value, label) ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = { viewModel.setReaderDirection(value); dirDropdownExpanded = false },
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Kategorie ─────────────────────────────────────────────
                    if (allCategories.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(text = stringResource(R.string.detail_info_section_categories), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 8.dp))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    allCategories.forEach { cat ->
                                        CategoryToggleChip(category = cat, selected = cat.id in categoryIds, onClick = { viewModel.toggleCategory(cat.id) })
                                    }
                                }
                            }
                        }
                    }

                    // ── Podobné manga ─────────────────────────────────────────
                    if (relatedManga.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                                Text(
                                    text = stringResource(R.string.detail_info_section_related),
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

                    // ── Hodnocení ─────────────────────────────────────────────
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.detail_info_section_rating),
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                                color = Violet,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
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
                                        text = stringResource(R.string.detail_info_rating_out_of_10),
                                        fontSize = 12.sp,
                                        color = TextSecondary,
                                    )
                                }
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
                                if (userRating != null) {
                                    IconButton(
                                        onClick = { viewModel.clearRating() },
                                    ) {
                                        Icon(
                                            TablerIcons.X,
                                            contentDescription = stringResource(R.string.detail_info_clear_rating),
                                            tint = TextSecondary.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Sledování (MAL/AniList/Kitsu/MangaUpdates sjednoceno) ──
                    item {
                        Text(
                            text = stringResource(R.string.detail_info_section_tracking),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                            color = Violet,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).padding(top = 4.dp),
                        )
                    }

                    // MAL
                    item {
                        val malBlue = Color(0xFF2E51A2)
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "MyAnimeList",
                                style = MaterialTheme.typography.labelSmall,
                                color = malBlue,
                                modifier = Modifier.padding(bottom = 6.dp),
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
                                        Text(stringResource(R.string.detail_info_mal_id, malId!!), color = TextPrimary, fontSize = 14.sp)
                                        if (malScore != null) {
                                            Text(stringResource(R.string.detail_info_score, String.format("%.2f", malScore)), color = TextSecondary, fontSize = 12.sp)
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            malSearchQuery = manga?.title ?: ""
                                            viewModel.searchMal(malSearchQuery)
                                            showMalSheet = true
                                        }) { Text(stringResource(R.string.common_change), color = malBlue, fontSize = 12.sp) }
                                        IconButton(onClick = { viewModel.syncFromMal() }) {
                                            Icon(TablerIcons.Refresh, contentDescription = stringResource(R.string.detail_info_sync_from_mal), tint = malBlue, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = { viewModel.unlinkMal() }) {
                                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_disconnect), tint = TextSecondary, modifier = Modifier.size(16.dp))
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
                                    Text(stringResource(R.string.detail_info_link_mal))
                                }
                                if (!viewModel.malHasClientId) {
                                    Text(
                                        stringResource(R.string.detail_info_mal_no_client_id),
                                        color = TextSecondary.copy(alpha = 0.6f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                }
                            }
                        }
                    }

                    // AniList
                    item {
                        val aniListColor = Color(0xFF2E51A2)
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "AniList",
                                style = MaterialTheme.typography.labelSmall,
                                color = aniListColor,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (!aniListIsLoggedIn) {
                                Text(
                                    stringResource(R.string.detail_info_anilist_login_hint),
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            } else if (aniListId != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(aniListColor.copy(alpha = 0.08f))
                                        .border(1.dp, aniListColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .clickable { viewModel.openAniListPage(context) }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(stringResource(R.string.detail_info_anilist_id, aniListId!!), color = TextPrimary, fontSize = 14.sp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            aniListSearchQuery = manga?.title ?: ""
                                            viewModel.searchAniList(aniListSearchQuery)
                                            showAniListSheet = true
                                        }) { Text(stringResource(R.string.common_change), color = aniListColor, fontSize = 12.sp) }
                                        IconButton(onClick = { viewModel.unlinkAniList() }) {
                                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_disconnect), tint = TextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        aniListSearchQuery = manga?.title ?: ""
                                        viewModel.searchAniList(aniListSearchQuery)
                                        showAniListSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, aniListColor.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = aniListColor),
                                ) { Text(stringResource(R.string.detail_info_link_anilist)) }
                            }
                        }
                    }

                    // Kitsu
                    item {
                        val kitsuColor = Color(0xFF51A351)
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "Kitsu",
                                style = MaterialTheme.typography.labelSmall,
                                color = kitsuColor,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (!kitsuIsLoggedIn) {
                                Text(
                                    stringResource(R.string.detail_info_kitsu_login_hint),
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            } else if (kitsuId != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(kitsuColor.copy(alpha = 0.08f))
                                        .border(1.dp, kitsuColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .clickable { viewModel.openKitsuPage(context) }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(stringResource(R.string.detail_info_kitsu_id, kitsuId!!), color = TextPrimary, fontSize = 14.sp)
                                        if (kitsuScore != null) Text(stringResource(R.string.detail_info_score, String.format("%.2f", kitsuScore)), color = TextSecondary, fontSize = 12.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            kitsuSearchQuery = manga?.title ?: ""
                                            viewModel.searchKitsu(kitsuSearchQuery)
                                            showKitsuSheet = true
                                        }) { Text(stringResource(R.string.common_change), color = kitsuColor, fontSize = 12.sp) }
                                        IconButton(onClick = { viewModel.syncFromKitsu() }) {
                                            Icon(TablerIcons.Refresh, contentDescription = stringResource(R.string.detail_info_sync_from_kitsu), tint = kitsuColor, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = { viewModel.unlinkKitsu() }) {
                                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_disconnect), tint = TextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        kitsuSearchQuery = manga?.title ?: ""
                                        viewModel.searchKitsu(kitsuSearchQuery)
                                        showKitsuSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, kitsuColor.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = kitsuColor),
                                ) { Text(stringResource(R.string.detail_info_link_kitsu)) }
                            }
                        }
                    }

                    // MangaUpdates
                    item {
                        val muColor = Color(0xFF3B82F6)
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = "MangaUpdates",
                                style = MaterialTheme.typography.labelSmall,
                                color = muColor,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (!muIsLoggedIn) {
                                Text(
                                    stringResource(R.string.detail_info_mu_login_hint),
                                    color = TextSecondary.copy(alpha = 0.6f),
                                    fontSize = 12.sp,
                                )
                            } else if (muId != null) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(muColor.copy(alpha = 0.08f))
                                        .border(1.dp, muColor.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                                        .clickable { viewModel.openMuPage(context) }
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column {
                                        Text(stringResource(R.string.detail_info_mu_id, muId!!), color = TextPrimary, fontSize = 14.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = {
                                            muSearchQuery = manga?.title ?: ""
                                            viewModel.searchMu(muSearchQuery)
                                            showMuSheet = true
                                        }) { Text(stringResource(R.string.common_change), color = muColor, fontSize = 12.sp) }
                                        IconButton(onClick = { viewModel.syncFromMu() }) {
                                            Icon(TablerIcons.Refresh, contentDescription = stringResource(R.string.detail_info_sync_from_mu), tint = muColor, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(onClick = { viewModel.unlinkMu() }) {
                                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_disconnect), tint = TextSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        muSearchQuery = manga?.title ?: ""
                                        viewModel.searchMu(muSearchQuery)
                                        showMuSheet = true
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    border = BorderStroke(1.dp, muColor.copy(alpha = 0.4f)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = muColor),
                                ) { Text(stringResource(R.string.detail_info_link_mu)) }
                            }
                        }
                    }

                    // ── Tagy ──────────────────────────────────────────────────
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.detail_info_section_tags), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showAddTagField = !showAddTagField }) {
                                    Icon(TablerIcons.Plus, contentDescription = stringResource(R.string.detail_info_add_tag), tint = TextSecondary, modifier = Modifier.size(16.dp))
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
                                                if (addTagText.isEmpty()) Text(stringResource(R.string.detail_info_new_tag_placeholder), color = TextSecondary, fontSize = 13.sp)
                                                inner()
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = {
                                        viewModel.addTag(addTagText)
                                        addTagText = ""
                                        showAddTagField = false
                                    }) { Text(stringResource(R.string.common_ok), color = GlowCyan) }
                                }
                            }
                            if (mangaTags.isNotEmpty()) {
                                FlowRow(
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
                                                Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_remove), tint = GlowCyan.copy(alpha = 0.7f), modifier = Modifier.size(11.dp).padding(start = 3.dp))
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(stringResource(R.string.detail_info_no_tags), color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }

                    // ── Slovník AI překladu ───────────────────────────────────
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = stringResource(R.string.detail_info_section_glossary), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.weight(1f))
                                IconButton(onClick = { showAddGlossaryField = !showAddGlossaryField }) {
                                    Icon(TablerIcons.Plus, contentDescription = stringResource(R.string.detail_info_add_glossary_entry), tint = TextSecondary, modifier = Modifier.size(16.dp))
                                }
                            }
                            Text(
                                stringResource(R.string.detail_info_glossary_hint),
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            if (showAddGlossaryField) {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        BasicTextField(
                                            value = glossarySourceText,
                                            onValueChange = { glossarySourceText = it },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                                            decorationBox = { inner ->
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color.White.copy(alpha = 0.06f))
                                                        .border(1.dp, GlowCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                ) {
                                                    if (glossarySourceText.isEmpty()) Text(stringResource(R.string.detail_info_glossary_source_placeholder), color = TextSecondary, fontSize = 12.sp)
                                                    inner()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                        Text("→", color = TextSecondary, fontSize = 13.sp)
                                        BasicTextField(
                                            value = glossaryTargetText,
                                            onValueChange = { glossaryTargetText = it },
                                            singleLine = true,
                                            textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                                            decorationBox = { inner ->
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(Color.White.copy(alpha = 0.06f))
                                                        .border(1.dp, GlowCyan.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                                ) {
                                                    if (glossaryTargetText.isEmpty()) Text(stringResource(R.string.detail_info_glossary_target_placeholder), color = TextSecondary, fontSize = 12.sp)
                                                    inner()
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                                        Checkbox(
                                            checked = glossaryProtectExact,
                                            onCheckedChange = { glossaryProtectExact = it },
                                            modifier = Modifier.size(28.dp),
                                            colors = CheckboxDefaults.colors(checkedColor = GlowCyan, uncheckedColor = TextSecondary),
                                        )
                                        Text(
                                            stringResource(R.string.reader_glossary_protect_label),
                                            color = TextSecondary,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(start = 2.dp, end = 8.dp),
                                        )
                                        TextButton(onClick = {
                                            viewModel.addGlossaryEntry(glossarySourceText, glossaryTargetText, defaultTargetLanguage, glossaryProtectExact)
                                            glossarySourceText = ""
                                            glossaryTargetText = ""
                                            glossaryProtectExact = false
                                            showAddGlossaryField = false
                                        }) { Text(stringResource(R.string.detail_info_add_to_glossary, defaultTargetLanguage), color = GlowCyan, fontSize = 12.sp) }
                                    }
                                }
                            }
                            if (glossary.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    glossary.forEach { entry ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color.White.copy(alpha = 0.04f))
                                                .padding(horizontal = 10.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "${entry.sourceTerm} → ${entry.targetTerm}",
                                                color = TextPrimary,
                                                fontSize = 12.sp,
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(entry.targetLanguage, color = TextSecondary.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(end = 6.dp))
                                            IconButton(onClick = { viewModel.toggleGlossaryProtectExact(entry) }) {
                                                Icon(
                                                    if (entry.protectExact) TablerIcons.Lock else TablerIcons.LockOpen,
                                                    contentDescription = stringResource(
                                                        if (entry.protectExact) R.string.reader_glossary_protect_on_description else R.string.reader_glossary_protect_off_description,
                                                    ),
                                                    tint = if (entry.protectExact) GlowCyan else TextSecondary,
                                                    modifier = Modifier.size(13.dp),
                                                )
                                            }
                                            IconButton(onClick = { viewModel.removeGlossaryEntry(entry) }) {
                                                Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_remove), tint = TextSecondary, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                }
                            } else if (!showAddGlossaryField) {
                                Text(stringResource(R.string.detail_info_no_glossary), color = TextSecondary.copy(alpha = 0.5f), fontSize = 12.sp)
                            }
                        }
                    }

                    // ── Kontext pro AI překladač ─────────────────────────────────
                    // Na rozdíl od Poznámek níže (čistě soukromé, nikam se neposílají) tenhle
                    // text jde modelu při KAŽDÉM překladu (viz GeminiUltraPrompt.buildMangaContext)
                    // - proto samostatná sekce s jasným popiskem, ne recyklace soukromé poznámky.
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(text = stringResource(R.string.detail_info_section_translation_context), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 6.dp))
                            Text(
                                stringResource(R.string.detail_info_translation_context_hint),
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            BasicTextField(
                                value = contextNoteText,
                                onValueChange = { contextNoteText = it },
                                textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 13.sp),
                                decorationBox = { inner ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color.White.copy(alpha = 0.04f))
                                            .border(1.dp, GlowCyan.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                                            .padding(12.dp),
                                    ) {
                                        if (contextNoteText.isEmpty()) Text(stringResource(R.string.detail_info_translation_context_placeholder), color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
                                        inner()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (contextNoteText != (manga?.translationContextNote ?: "")) {
                                TextButton(
                                    onClick = { viewModel.setTranslationContextNote(contextNoteText) },
                                    modifier = Modifier.align(Alignment.End),
                                ) { Text(stringResource(R.string.common_save), color = GlowCyan) }
                            }
                        }
                    }

                    // ── Poznámky ──────────────────────────────────────────────
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(text = stringResource(R.string.detail_info_section_notes), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 6.dp))
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
                                        if (noteText.isEmpty()) Text(stringResource(R.string.detail_info_note_placeholder), color = TextSecondary.copy(alpha = 0.5f), fontSize = 13.sp)
                                        inner()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (noteText != (mangaNote?.content ?: "")) {
                                TextButton(
                                    onClick = { viewModel.saveNote(noteText) },
                                    modifier = Modifier.align(Alignment.End),
                                ) { Text(stringResource(R.string.common_save), color = GlowViolet) }
                            }
                        }
                    }

                    // ── Stahování ─────────────────────────────────────────────
                    item {
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(text = stringResource(R.string.detail_info_section_downloads), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = Violet, modifier = Modifier.padding(bottom = 8.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(1.dp, GlowViolet.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .clickable { viewModel.toggleAutoDownload() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.detail_info_auto_download), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = autoDownload,
                                    onCheckedChange = { viewModel.toggleAutoDownload() },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.White.copy(alpha = 0.04f))
                                    .border(1.dp, GlowViolet.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                                    .clickable { viewModel.toggleExcludeFromUpdates() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.detail_info_exclude_updates), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = excludeFromUpdates,
                                    onCheckedChange = { viewModel.toggleExcludeFromUpdates() },
                                    colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Vyhledávací panely trackerů (viz MangaDetailTrackerSheets.kt) ─────────
    if (showAniListSheet) {
        AniListSearchSheet(
            viewModel = viewModel,
            query = aniListSearchQuery,
            onQueryChange = { aniListSearchQuery = it },
            onDismiss = { showAniListSheet = false },
        )
    }
    if (showKitsuSheet) {
        KitsuSearchSheet(
            viewModel = viewModel,
            query = kitsuSearchQuery,
            onQueryChange = { kitsuSearchQuery = it },
            onDismiss = { showKitsuSheet = false },
        )
    }
    if (showMuSheet) {
        MangaUpdatesSearchSheet(
            viewModel = viewModel,
            query = muSearchQuery,
            onQueryChange = { muSearchQuery = it },
            onDismiss = { showMuSheet = false },
        )
    }
    if (showMalSheet) {
        MalSearchSheet(
            viewModel = viewModel,
            query = malSearchQuery,
            onQueryChange = { malSearchQuery = it },
            onDismiss = { showMalSheet = false },
        )
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
            if (selected) Icon(TablerIcons.CircleCheck, contentDescription = null, tint = color, modifier = Modifier.size(13.dp).padding(end = 4.dp))
            Text(text = category.name, color = if (selected) color else TextSecondary, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
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
                .aspectRatio(0.74f)
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
