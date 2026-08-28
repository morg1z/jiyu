package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.source.comments.ChapterComment
import com.haise.jiyu.ui.components.JiyuLoadingIndicator

// ── Komentare ke KONKRETNI kapitole (ne k titulu - to resi ComicK v MangaDetailScreen) ──
// Vola se az na vyzadani (viz ReaderViewModel.loadChapterComments), otevira se tlacitkem
// vedle Slovniku v pokrocilych nastavenich ctecky (viz ReaderControls).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterCommentsBottomSheet(
    comments: List<ChapterComment>,
    loading: Boolean,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.reader_comments_button),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
            )
            when {
                loading && comments.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) { JiyuLoadingIndicator(size = 28.dp, strokeWidth = 2.dp) }

                comments.isEmpty() -> Text(
                    stringResource(R.string.reader_comments_empty),
                    color = Color(0xFFB0BEC5),
                    fontSize = 13.sp,
                )

                else -> comments.forEach { comment -> ChapterCommentRow(comment) }
            }
        }
    }
}

@Composable
private fun ChapterCommentRow(comment: ChapterComment) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        AsyncImage(
            model = comment.avatarUrl,
            contentDescription = comment.author,
            modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF1A2340)),
        )
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(comment.author, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(
                    text = chapterCommentRelativeTime(comment.createdAt),
                    color = Color(0xFFB0BEC5).copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(comment.content, color = Color(0xFFB0BEC5), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** "před 2 h", "před 3 dny" apod. - stejny vzor jako MangaDetailScreen.commentRelativeTime
 * (karty/pomocne funkce se v kodu nesdileji mezi soubory, zavedena konvence appky). */
private fun chapterCommentRelativeTime(createdAtMs: Long): String {
    if (createdAtMs <= 0L) return ""
    val diffMin = (System.currentTimeMillis() - createdAtMs) / 60_000L
    return when {
        diffMin < 1     -> "teď"
        diffMin < 60    -> "před ${diffMin} min"
        diffMin < 1440  -> "před ${diffMin / 60} h"
        diffMin < 43200 -> "před ${diffMin / 1440} dny"
        else            -> java.text.SimpleDateFormat("d. M. yyyy", java.util.Locale.getDefault()).format(java.util.Date(createdAtMs))
    }
}
