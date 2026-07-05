package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val pages by viewModel.pages.collectAsState()
    val loading by viewModel.loading.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator()
            pages.isEmpty() -> Text("Kapitolu se nepodařilo načíst.", color = Color.White)
            else -> {
                val pagerState = rememberPagerState(pageCount = { pages.size })

                LaunchedEffect(pagerState) {
                    snapshotFlow { pagerState.currentPage }.collect { page ->
                        viewModel.onPageChanged(page)
                    }
                }

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                    AsyncImage(
                        model = pages[index],
                        contentDescription = "Stránka ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
