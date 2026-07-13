package com.haise.jiyu.ui.browse

import compose.icons.TablerIcons
import compose.icons.tablericons.*

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

/** Hlavní obrazovka Procházet - mřížka zdrojů. Obsah konkrétního zdroje viz [SourceBrowseScreen]. */
@Composable
fun BrowseScreen(
    onOpenSource: (String) -> Unit,
    onGlobalSearch: () -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val sources           by viewModel.sources.collectAsState()
    val contentTypeFilter by viewModel.contentTypeFilter.collectAsState()
    val languageFilter    by viewModel.languageFilter.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(colors = listOf(NightBlue, DeepSpace.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.browse_title),
                style = TextStyle(brush = titleGradient, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp),
            )

            // Search bar → navigates to GlobalSearch on tap
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightBlue.copy(alpha = 0.7f))
                    .glassBorder(14.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onGlobalSearch,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.browse_search_placeholder), color = TextSecondary, fontSize = 15.sp)
                }
            }
        }

        // ── Typový filtr - kompaktní řádek chipů ─────────────────────────────
        val contentTypes = listOf(
            "ALL" to stringResource(R.string.common_all),
            BrowseViewModel.MANGA_GROUP to stringResource(R.string.browse_filter_manga),
            "NOVEL" to stringResource(R.string.browse_filter_novels),
            "COMIC" to stringResource(R.string.browse_filter_comics),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(contentTypes) { (type, label) ->
                FilterChip(label = label, selected = contentTypeFilter == type) { viewModel.setContentTypeFilter(type) }
            }
        }

        // ── Jazykový filtr - kompaktní řádek chipů ───────────────────────────
        val languages = listOf(
            "ALL" to stringResource(R.string.browse_lang_all),
            "en"  to "🇺🇸 EN",
            "fr"  to "🇫🇷 FR",
            "es"  to "🇪🇸 ES",
            "pt"  to "🇧🇷 PT",
            "ja"  to "🇯🇵 RAW",
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(languages) { (code, label) ->
                FilterChip(label = label, selected = languageFilter == code) { viewModel.setLanguageFilter(code) }
            }
        }

        // ── Mřížka zdrojů ─────────────────────────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        if (sources.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.browse_no_sources_match), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 16.dp + navBottom),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceTile(source = source, onClick = { onOpenSource(source.id) })
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) Violet.copy(alpha = 0.25f) else Color.Transparent,
        animationSpec = tween(200),
        label = "chip_bg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Violet else TextSecondary,
        animationSpec = tween(200),
        label = "chip_text",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(bgColor)
            .border(1.dp, if (selected) Violet else CardBorder, RoundedCornerShape(50.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Dlaždice zdroje - monogram (zdroje nemají bundlované logo), ne emoji. */
@Composable
private fun SourceTile(source: MangaSource, onClick: () -> Unit) {
    val initials = remember(source.name) {
        source.name.trim().split(" ")
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .take(2)
            .joinToString("")
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(NightBlue)
                .border(1.dp, CardBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = initials.ifBlank { "?" },
                color = Violet,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = source.name,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            lineHeight = 13.sp,
        )
    }
}
