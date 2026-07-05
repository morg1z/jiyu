package com.haise.jiyu.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.source.SManga

@Composable
fun BrowseScreen(
    onMangaAdded: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Procházet") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Výběr zdroje
            val sources = viewModel.sources
            TabRow(selectedTabIndex = sources.indexOfFirst { it.id == selectedSource.id }.coerceAtLeast(0)) {
                sources.forEach { source ->
                    Tab(
                        selected = source.id == selectedSource.id,
                        onClick = {
                            query = ""
                            viewModel.selectSource(source)
                        },
                        text = { Text(source.name) },
                    )
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                label = { Text("Hledat…") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.sourceId + it.url }) { manga ->
                    SearchResultItem(manga = manga) {
                        viewModel.addToLibrary(manga, onMangaAdded)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(manga: SManga, onClick: () -> Unit) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = manga.coverUrl,
            contentDescription = manga.title,
            modifier = Modifier.fillMaxSize().aspectRatio(0.7f),
        )
        Text(
            text = manga.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
