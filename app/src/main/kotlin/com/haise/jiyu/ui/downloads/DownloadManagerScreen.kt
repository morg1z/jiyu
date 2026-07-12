package com.haise.jiyu.ui.downloads

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.data.db.entity.ChapterEntity
import com.haise.jiyu.data.db.entity.DownloadStatus
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@Composable
fun DownloadManagerScreen(
    onBack: () -> Unit,
    viewModel: DownloadManagerViewModel = hiltViewModel(),
) {
    val groups by viewModel.downloadGroups.collectAsState()
    val totalStorageBytes by viewModel.totalStorageBytes.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var showDeleteReadConfirm by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(screenGradient)) {

        // ── Header ───────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, NightBlue.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(TablerIcons.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
            }
            Text(
                text = "Stažené kapitoly",
                style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        // ── Statistiky úložiště ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Využito: ${formatBytes(totalStorageBytes)}",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            val hasQueued = groups.any { g -> g.chapters.any { it.downloadStatus == com.haise.jiyu.data.db.entity.DownloadStatus.DOWNLOADING } }
            if (hasQueued || isPaused) {
                IconButton(onClick = { if (isPaused) viewModel.resumeAll() else viewModel.pauseAll() }) {
                    Icon(
                        if (isPaused) TablerIcons.PlayerPlay else TablerIcons.PlayerPause,
                        contentDescription = if (isPaused) "Obnovit stahování" else "Pozastavit stahování",
                        tint = Violet,
                    )
                }
            }
            TextButton(onClick = { showDeleteReadConfirm = true }) {
                Icon(TablerIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Smazat přečtené", color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }

        if (showDeleteReadConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteReadConfirm = false },
                containerColor = Color(0xFF111B35),
                title = { Text("Smazat přečtené?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                text = { Text("Smaže všechny stažené kapitoly označené jako přečtené.", color = TextSecondary) },
                confirmButton = {
                    TextButton(onClick = { viewModel.deleteReadChapters(); showDeleteReadConfirm = false }) {
                        Text("Smazat", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { showDeleteReadConfirm = false }) { Text("Zrušit", color = TextSecondary) } },
            )
        }

        if (groups.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 40.dp),
                ) {
                    Icon(
                        TablerIcons.CloudDownload,
                        contentDescription = null,
                        tint = GlowCyan.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Nic nestaženo",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Stažené kapitoly najdeš tady — čti offline bez připojení",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp + navBottom,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(groups, key = { it.manga.id }) { group ->
                    DownloadGroupCard(
                        group = group,
                        downloadProgress = downloadProgress,
                        onDeleteChapter = { viewModel.deleteChapter(it) },
                        onDeleteManga = { viewModel.deleteManga(group.chapters) },
                        onCancelChapter = { viewModel.cancelChapter(it) },
                        onCancelManga = { viewModel.cancelAll(group.chapters) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadGroupCard(
    group: DownloadGroup,
    downloadProgress: Map<String, Float>,
    onDeleteChapter: (ChapterEntity) -> Unit,
    onDeleteManga: () -> Unit,
    onCancelChapter: (ChapterEntity) -> Unit,
    onCancelManga: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val downloaded = group.chapters.count { it.downloadStatus == DownloadStatus.DOWNLOADED }
    val queued = group.chapters.count { it.downloadStatus == DownloadStatus.QUEUED }
    val downloading = group.chapters.count { it.downloadStatus == DownloadStatus.DOWNLOADING }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp)),
    ) {
        // ── Manga row ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = group.manga.coverUrl,
                contentDescription = "Obálka: ${group.manga.title}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.manga.title,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        if (downloaded > 0) append("$downloaded staženo")
                        if (downloading > 0) append(" · $downloading stahuje se")
                        if (queued > 0) append(" · $queued ve frontě")
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            if (queued > 0 || downloading > 0) {
                IconButton(onClick = onCancelManga, modifier = Modifier.size(36.dp)) {
                    Icon(TablerIcons.X, contentDescription = "Zrušit stahování", tint = Violet, modifier = Modifier.size(20.dp))
                }
            } else if (downloaded > 0) {
                IconButton(onClick = { showConfirm = true }, modifier = Modifier.size(36.dp)) {
                    Icon(TablerIcons.Trash, contentDescription = "Smazat vše", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                }
            }
            Icon(
                imageVector = if (expanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                contentDescription = if (expanded) "Sbalit seznam kapitol" else "Rozbalit seznam kapitol",
                tint = TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        // ── Chapter list (when expanded) ──────────────────────────────────────
        if (expanded) {
            HorizontalDivider(color = GlowViolet.copy(alpha = 0.1f))
            group.chapters.forEach { chapter ->
                ChapterDownloadRow(
                    chapter = chapter,
                    progress = downloadProgress[chapter.id],
                    onDelete = { onDeleteChapter(chapter) },
                    onCancel = { onCancelChapter(chapter) },
                )
            }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = Color(0xFF111B35),
            title = { Text("Smazat stažené?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = { Text("Smaže $downloaded stažených kapitol od \"${group.manga.title}\".", color = TextSecondary) },
            confirmButton = {
                TextButton(onClick = { onDeleteManga(); showConfirm = false }) {
                    Text("Smazat", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showConfirm = false }) { Text("Zrušit", color = TextSecondary) } },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}

@Composable
private fun ChapterDownloadRow(
    chapter: ChapterEntity,
    progress: Float? = null,
    onDelete: () -> Unit,
    onCancel: () -> Unit = {},
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        when (chapter.downloadStatus) {
                            DownloadStatus.DOWNLOADED  -> GlowCyan
                            DownloadStatus.DOWNLOADING -> Violet
                            DownloadStatus.QUEUED      -> GlowViolet.copy(alpha = 0.5f)
                            else                       -> MaterialTheme.colorScheme.error
                        },
                        RoundedCornerShape(50),
                    ),
            )
            Text(
                text = chapter.name,
                color = TextPrimary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            when (chapter.downloadStatus) {
                DownloadStatus.DOWNLOADED -> {
                    Text("${chapter.pageCount}str.", color = TextSecondary, fontSize = 11.sp)
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(TablerIcons.Trash, contentDescription = "Smazat", tint = TextSecondary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
                DownloadStatus.DOWNLOADING -> {
                    Text("Stahuje se…", color = Violet, fontSize = 11.sp)
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(TablerIcons.X, contentDescription = "Zrušit", tint = Violet, modifier = Modifier.size(16.dp))
                    }
                }
                DownloadStatus.QUEUED -> {
                    Text("Ve frontě", color = TextSecondary, fontSize = 11.sp)
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(TablerIcons.X, contentDescription = "Zrušit", tint = TextSecondary.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
                    }
                }
                DownloadStatus.ERROR -> {
                    Text("Chyba", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(TablerIcons.AlertCircle, contentDescription = "Stahování selhalo, klepnutím odstranit", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
                DownloadStatus.NOT_DOWNLOADED -> Unit
            }
        }
        if (chapter.downloadStatus == DownloadStatus.DOWNLOADING) {
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                    color = Violet,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 6.dp),
                    color = Violet,
                )
            }
        }
    }
}
