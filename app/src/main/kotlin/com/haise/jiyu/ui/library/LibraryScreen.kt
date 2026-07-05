package com.haise.jiyu.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.data.db.entity.MangaEntity

@Composable
fun LibraryScreen(
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val library by viewModel.library.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenBrowse) {
                Icon(Icons.Filled.Search, contentDescription = "Hledat nová manga")
            }
        }
    ) { padding ->
        if (library.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Knihovna je prázdná. Přidej si první mangu přes vyhledávání.")
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                items(library, key = { it.id }) { manga ->
                    MangaCover(manga = manga, onClick = { onOpenManga(manga.id) })
                }
            }
        }
    }
}

@Composable
private fun MangaCover(manga: MangaEntity, onClick: () -> Unit) {
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
