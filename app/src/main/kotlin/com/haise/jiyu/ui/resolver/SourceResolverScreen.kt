package com.haise.jiyu.ui.resolver

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Search
import compose.icons.tablericons.Star
import com.haise.jiyu.R
import com.haise.jiyu.source.comick.ResolvedCandidate
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient

@Composable
fun SourceResolverScreen(
    onBack: () -> Unit,
    onOpenChapter: (chapterId: String, incognito: Boolean) -> Unit,
    onSearchManually: (query: String) -> Unit,
    viewModel: SourceResolverViewModel = hiltViewModel(),
) {
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val searchingMore by viewModel.searchingMore.collectAsStateWithLifecycle()
    val comicKTitle by viewModel.comicKTitle.collectAsStateWithLifecycle()
    val candidates by viewModel.candidates.collectAsStateWithLifecycle()
    val totalComicKChapters by viewModel.totalComicKChapters.collectAsStateWithLifecycle()
    val resolving by viewModel.resolving.collectAsStateWithLifecycle()
    val openedChapterId by viewModel.openedChapterId.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openedChapterId) {
        openedChapterId?.let { onOpenChapter(it, viewModel.incognito) }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
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
                    Text(stringResource(R.string.resolver_title), color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    if (comicKTitle.isNotBlank()) {
                        Text(comicKTitle, color = TextSecondary, fontSize = 13.sp)
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
                            onClick = { onSearchManually(comicKTitle) },
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
                            onClick = { onSearchManually(comicKTitle) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        ) {
                            Icon(TablerIcons.Search, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(R.string.resolver_search_manually))
                        }
                    }
                    items(candidates, key = { it.source.id }) { candidate ->
                        CandidateCard(
                            candidate = candidate,
                            totalComicKChapters = totalComicKChapters,
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
                    modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.4f)),
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
private fun CandidateCard(candidate: ResolvedCandidate, totalComicKChapters: Int, enabled: Boolean, onClick: () -> Unit) {
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
                    stringResource(R.string.resolver_chapters_ratio, candidate.matchedChapterCount, totalComicKChapters),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                if (!candidate.hasRequestedChapter) {
                    Text(stringResource(R.string.resolver_missing_chapter), color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
        }
    }
}
