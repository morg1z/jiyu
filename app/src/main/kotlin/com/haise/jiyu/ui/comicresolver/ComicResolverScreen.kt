package com.haise.jiyu.ui.comicresolver

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haise.jiyu.R
import com.haise.jiyu.source.comick.ResolvedCandidate
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Search
import compose.icons.tablericons.Star

@Composable
fun ComicResolverScreen(
    onBack: () -> Unit,
    onOpenManga: (mangaId: String) -> Unit,
    onSearchManually: (query: String) -> Unit,
    viewModel: ComicResolverViewModel = hiltViewModel(),
) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val searchingMore by viewModel.searchingMore.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val openedMangaId by viewModel.openedMangaId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openedMangaId) {
        openedMangaId?.let { onOpenManga(it) }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back), tint = TextPrimary)
                }
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.comic_resolver_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (viewModel.title.isNotBlank()) {
                        Text(viewModel.title, color = TextSecondary, fontSize = 13.sp)
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        JiyuLoadingIndicator()
                        Text(stringResource(R.string.resolver_loading), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                candidates.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.resolver_no_candidates), color = TextSecondary, fontSize = 14.sp)
                        Button(
                            onClick = { onSearchManually(viewModel.title) },
                            modifier = Modifier.padding(top = 16.dp),
                        ) {
                            Icon(TablerIcons.Search, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.resolver_search_manually))
                        }
                    }
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 0.dp,
                        end = 16.dp,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
                    ),
                ) {
                    item {
                        Button(
                            onClick = { onSearchManually(viewModel.title) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        ) {
                            Icon(TablerIcons.Search, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.resolver_search_manually))
                        }
                    }
                    items(candidates, key = { it.source.id }) { candidate ->
                        ComicCandidateCard(
                            candidate = candidate,
                            enabled = !resolving,
                            onClick = { viewModel.selectCandidate(candidate) },
                        )
                    }
                    if (searchingMore) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                JiyuLoadingIndicator(size = 16.dp)
                                Text(
                                    stringResource(R.string.resolver_loading_more),
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (resolving) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        JiyuLoadingIndicator()
                        Text(stringResource(R.string.resolver_opening), color = TextPrimary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ComicCandidateCard(candidate: ResolvedCandidate, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NightBlue.copy(alpha = 0.6f))
            .border(1.dp, if (candidate.isFavorite) Violet.copy(alpha = 0.6f) else GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(candidate.source.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                    if (candidate.isFavorite) {
                        Icon(
                            TablerIcons.Star,
                            contentDescription = stringResource(R.string.resolver_favorite_badge),
                            tint = Violet,
                            modifier = Modifier.padding(start = 6.dp).size(14.dp),
                        )
                    }
                }
                Text(
                    pluralStringResource(
                        R.plurals.detail_n_chapters,
                        candidate.matchedChapterCount,
                        candidate.matchedChapterCount,
                    ),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }
}
