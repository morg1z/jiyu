package com.haise.jiyu.ui.comickhome

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft

@Composable
fun ComicKSectionScreen(
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit,
    viewModel: ComicKSectionViewModel = hiltViewModel(),
) {
    val comics by viewModel.comics.collectAsStateWithLifecycle()
    val reviews by viewModel.reviews.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val openingManga by viewModel.openingManga.collectAsStateWithLifecycle()
    val openError by viewModel.openError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(openError) {
        openError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearOpenError()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back), tint = TextPrimary)
                }
                Text(viewModel.title, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(start = 8.dp))
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().background(screenGradient).padding(innerPadding)) {
            val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { JiyuLoadingIndicator() }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Text(stringResource(R.string.comick_home_load_failed), color = TextSecondary, fontSize = 14.sp)
                        Text(error ?: "", color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                        OutlinedButton(onClick = { viewModel.retry() }) { Text(stringResource(R.string.common_retry), color = Violet) }
                    }
                }
                reviews.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + navBottom)) {
                    items(reviews) { review ->
                        Box(modifier = Modifier.padding(vertical = 6.dp)) {
                            ReviewCard(review = review, onClick = { viewModel.openManga(review.comic, onOpenManga) }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                comics.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.comick_home_empty), color = TextSecondary, fontSize = 14.sp)
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp + navBottom),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(comics, key = { it.sourceId + it.url }) { manga ->
                        ComicKMangaCard(manga = manga, onClick = { viewModel.openManga(manga, onOpenManga) })
                    }
                }
            }
            if (openingManga != null) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    JiyuLoadingIndicator()
                }
            }
        }
    }
}
