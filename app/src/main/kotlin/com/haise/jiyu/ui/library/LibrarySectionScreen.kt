package com.haise.jiyu.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack
import java.util.Locale

/**
 * Sekce knihovny, kterou umí [LibrarySectionScreen] zobrazit celou. Název konstanty je i tím,
 * co se předává v navigační cestě, takže se nesmí přejmenovávat bez úpravy [Routes.librarySection].
 */
enum class LibrarySection(val titleRes: Int) {
    CONTINUE_READING(R.string.library_continue_reading),
    RECENTLY_ADDED(R.string.library_recently_added),
    COMPLETED(R.string.library_completed),
}

/**
 * Celá sekce knihovny jako seznam - to, co se otevře po klepnutí na "Zobrazit vše".
 *
 * Proč to vzniklo: "Zobrazit vše" na Knihovně byl obyčejný `Text` s šipkou BEZ jakéhokoli
 * `clickable`. Vypadalo to jako odkaz, chovalo se to jako výplň.
 *
 * Dřív mřížka po třech (jako zbytek Knihovny) - uživatelský požadavek: styl řádků jako má
 * ComicK na "Read History" (malá obálka vlevo, název, kapitola, relativní čas).
 */
@Composable
fun LibrarySectionScreen(
    section: LibrarySection,
    onBack: () -> Unit,
    onOpenManga: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val continueReading by viewModel.continueReading.collectAsStateWithLifecycle()
    val recentlyAdded   by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val completed       by viewModel.completed.collectAsStateWithLifecycle()

    // Kapitola/cas se lisi podle sekce - continueReading nese navic posledni kapitolu,
    // ostatni sekce jen holy MangaEntity.
    val chapterInfoByMangaId: Map<String, com.haise.jiyu.data.db.ContinueReadingItem> =
        if (section == LibrarySection.CONTINUE_READING) continueReading.associateBy { it.manga.id } else emptyMap()

    val items: List<MangaEntity> = when (section) {
        LibrarySection.CONTINUE_READING -> continueReading.map { it.manga }
        LibrarySection.RECENTLY_ADDED -> recentlyAdded
        LibrarySection.COMPLETED -> completed
    }

    // U rozečtených titulů vede klepnutí rovnou do poslední kapitoly, stejně jako v karuselu
    // na Knihovně - jinak by "pokračovat ve čtení" končilo na detailu a uživatel by musel
    // kapitolu hledat sám. Bez známé poslední kapitoly zbývá detail.
    val openItem: (MangaEntity) -> Unit = { manga ->
        val chapterId = manga.lastReadChapterId
        if (section == LibrarySection.CONTINUE_READING && chapterId != null) {
            onOpenChapter(chapterId)
        } else {
            onOpenManga(manga.id)
        }
    }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(modifier = Modifier.fillMaxSize().background(screenGradient)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(NightBlue, NightBlue.copy(alpha = 0f))))
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
            }
            Text(
                text = stringResource(section.titleRes),
                style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            Text(
                text = "${items.size}",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 12.dp),
            )
        }

        if (items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.library_section_empty),
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + navBottom),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { manga ->
                    val chapterInfo = chapterInfoByMangaId[manga.id]
                    val subtitle = when (section) {
                        LibrarySection.CONTINUE_READING -> chapterInfo?.lastChapterName
                            ?: chapterInfo?.lastChapterNumber?.let { stringResource(R.string.library_section_chapter_number, chapterLabel(it)) }
                        else -> null
                    }
                    val timeMs = if (section == LibrarySection.CONTINUE_READING) manga.lastReadAt else manga.addedAt
                    LibrarySectionRow(
                        manga = manga,
                        subtitle = subtitle,
                        timeLabel = timeMs.takeIf { it > 0 }?.let { relativeTimeLabel(it) },
                        onClick = { openItem(manga) },
                    )
                }
            }
        }
    }
}

/** ComicK styl řádku (Read History) - malá obálka, název, kapitola/čas. */
@Composable
private fun LibrarySectionRow(
    manga: MangaEntity,
    subtitle: String?,
    timeLabel: String?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(NightBlue.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = manga.coverUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp, 74.dp).clip(RoundedCornerShape(8.dp)),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = manga.title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!timeLabel.isNullOrBlank()) {
                Text(
                    text = timeLabel,
                    color = TextSecondary.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** "318.0" -> "318", "318.5" -> "318.5" - stejny vzor jako na detailu titulu/aktualizacich. */
private fun chapterLabel(n: Float): String =
    if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString()

/** "před 2 h", "před 3 dny" apod. - stejny vzor jako u Aktualizaci na ComicK Domu. */
private fun relativeTimeLabel(ms: Long): String {
    val diffMin = (System.currentTimeMillis() - ms) / 60_000L
    return when {
        diffMin < 1     -> "teď"
        diffMin < 60    -> "před ${diffMin} min"
        diffMin < 1440  -> "před ${diffMin / 60} h"
        diffMin < 43200 -> "před ${diffMin / 1440} dny"
        else            -> java.text.SimpleDateFormat("d. M. yyyy", Locale.getDefault()).format(java.util.Date(ms))
    }
}
