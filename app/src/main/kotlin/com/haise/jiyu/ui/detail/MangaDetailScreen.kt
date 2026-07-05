package com.haise.jiyu.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus

@Composable
fun MangaDetailScreen(
    onOpenChapter: (String) -> Unit,
    viewModel: MangaDetailViewModel = hiltViewModel(),
) {
    val manga by viewModel.manga.collectAsState()
    val chapters by viewModel.chapters.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(manga?.title ?: "") })
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(padding)) {
            item {
                Text(
                    text = manga?.description ?: "",
                    modifier = Modifier.padding(16.dp),
                )
            }
            items(chapters, key = { it.id }) { chapter ->
                ChapterRow(
                    chapter = chapter,
                    onOpen = { onOpenChapter(chapter.id) },
                    onDownload = { viewModel.downloadChapter(chapter) },
                )
            }
        }
    }
}

@Composable
private fun ChapterRow(
    chapter: ChapterEntity,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = chapter.name, modifier = Modifier.weight(1f))
        when (chapter.downloadStatus) {
            DownloadStatus.DOWNLOADED -> Icon(Icons.Filled.CheckCircle, contentDescription = "Staženo")
            DownloadStatus.NOT_DOWNLOADED, DownloadStatus.ERROR -> IconButton(onClick = onDownload) {
                Icon(Icons.Filled.Download, contentDescription = "Stáhnout offline")
            }
            else -> Text("...")
        }
    }
}
