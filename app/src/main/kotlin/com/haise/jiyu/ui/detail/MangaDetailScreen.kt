package com.haise.jiyu.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.CyanLight
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.VioletLight
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

@Composable
fun MangaDetailScreen(
    onOpenChapter: (String) -> Unit,
    viewModel: MangaDetailViewModel = hiltViewModel(),
) {
    val manga by viewModel.manga.collectAsState()
    val chapters by viewModel.chapters.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient),
    ) {
        // ── Hero image ───────────────────────────────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
            ) {
                AsyncImage(
                    model = manga?.coverUrl,
                    contentDescription = manga?.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // Gradient overlay bottom half
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    DeepSpace.copy(alpha = 0.7f),
                                    DeepSpace,
                                ),
                                startY = 80f,
                            )
                        )
                )
                // Manga title over hero
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp),
                ) {
                    Text(
                        text = manga?.title ?: "",
                        style = TextStyle(
                            brush = titleGradient,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.ExtraBold,
                            lineHeight = 28.sp,
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${chapters.size} kapitol",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }

        // ── Description ──────────────────────────────────────────────────────
        item {
            if (!manga?.description.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(glassGradient)
                        .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        text = manga?.description ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ── Section header ───────────────────────────────────────────────────
        item {
            Text(
                text = "KAPITOLY",
                style = MaterialTheme.typography.labelSmall,
                color = Violet,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        // ── Chapter list ─────────────────────────────────────────────────────
        items(chapters, key = { it.id }) { chapter ->
            GlassChapterRow(
                chapter = chapter,
                onOpen = { onOpenChapter(chapter.id) },
                onDownload = { viewModel.downloadChapter(chapter) },
            )
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun GlassChapterRow(
    chapter: ChapterEntity,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
) {
    val isRead = chapter.read

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(glassGradient)
            .border(
                1.dp,
                if (isRead) GlowCyan.copy(alpha = 0.1f) else GlowViolet.copy(alpha = 0.2f),
                RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Read indicator dot
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(
                        if (isRead) GlowCyan.copy(alpha = 0.4f) else GlowViolet,
                        RoundedCornerShape(50),
                    )
            )

            Text(
                text = chapter.name,
                color = if (isRead) TextSecondary else TextPrimary,
                fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            )

            when (chapter.downloadStatus) {
                DownloadStatus.DOWNLOADED -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Staženo",
                    tint = Cyan,
                    modifier = Modifier.size(18.dp),
                )
                DownloadStatus.DOWNLOADING -> Text(
                    "↓",
                    color = Violet,
                    fontSize = 16.sp,
                )
                else -> IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = "Stáhnout",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
