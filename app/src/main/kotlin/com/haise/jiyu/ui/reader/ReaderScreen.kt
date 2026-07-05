package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.translate.TranslatedBlock

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel = hiltViewModel(),
) {
    val pages by viewModel.pages.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val translateMode by viewModel.translateMode.collectAsState()
    val translatingPage by viewModel.translatingPage.collectAsState()
    val translatedPages by viewModel.translatedPages.collectAsState()
    val reverseLayout by viewModel.reverseLayout.collectAsState()

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

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), reverseLayout = reverseLayout) { index ->
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = pages[index],
                            contentDescription = "Stránka ${index + 1}",
                            modifier = Modifier.fillMaxSize(),
                        )

                        if (translateMode) {
                            val blocks = translatedPages[index] ?: emptyList()
                            blocks.forEach { block ->
                                TranslationOverlay(block = block)
                            }

                            if (translatingPage == index) {
                                CircularProgressIndicator(
                                    modifier = Modifier.align(Alignment.Center),
                                    color = Color.White,
                                )
                            }
                        }
                    }
                }

                // Tlačítko překladu (vpravo nahoře)
                IconButton(
                    onClick = { viewModel.toggleTranslate() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = if (translateMode) "Vypnout překlad" else "Přeložit stránku",
                        tint = if (translateMode) Color(0xFF4FC3F7) else Color.White,
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.TranslationOverlay(block: TranslatedBlock) {
    val left = maxWidth * block.leftF
    val top = maxHeight * block.topF
    val w = maxWidth * (block.rightF - block.leftF)
    val h = maxHeight * (block.bottomF - block.topF)

    Box(
        modifier = Modifier
            .offset(x = left, y = top)
            .width(w)
            .height(h)
            .background(Color.Black.copy(alpha = 0.82f))
            .padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = block.translatedText,
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
