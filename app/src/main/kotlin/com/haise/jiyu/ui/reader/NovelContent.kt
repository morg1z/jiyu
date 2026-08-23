package com.haise.jiyu.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.GlossaryEntity
import com.haise.jiyu.source.LanguageMap
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import compose.icons.TablerIcons
import compose.icons.tablericons.Language
import compose.icons.tablericons.Sun

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelContent(
    text: String,
    chapterTitle: String,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    translateMode: Boolean = false,
    translatedText: String? = null,
    translating: Boolean = false,
    onToggleTranslate: () -> Unit = {},
    sourceLanguage: String = "Auto",
    targetLanguage: String = "Czech",
    onSourceLanguageChange: (String) -> Unit = {},
    onTargetLanguageChange: (String) -> Unit = {},
    glossary: List<GlossaryEntity> = emptyList(),
    onAddGlossaryEntry: (String, String) -> Unit = { _, _ -> },
    onRemoveGlossaryEntry: (GlossaryEntity) -> Unit = {},
    pageCurlEnabled: Boolean = false,
    curlStyle: String = com.haise.jiyu.settings.CurlStyleSetting.CLASSIC,
) {
    var fontSize by remember { mutableStateOf(16f) }
    var lineSpacing by remember { mutableStateOf(1.6f) }
    var bgColorIndex by remember { mutableStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var showLangSettings by remember { mutableStateOf(false) }
    var showGlossarySheet by remember { mutableStateOf(false) }

    val bgOptions = listOf(
        Color(0xFF0A0A14) to Color(0xFFE8E8E8),
        Color(0xFF1A110A) to Color(0xFFE8D8C0),
        Color(0xFFF5F0E8) to Color(0xFF1A1A1A),
        Color.White to Color.Black,
    )
    val (bgColor, textColor) = bgOptions[bgColorIndex.coerceIn(0, bgOptions.lastIndex)]
    val displayText = if (translateMode && translatedText != null) translatedText else text
    val paragraphs = remember(displayText) { displayText.split("\n").filter { it.isNotBlank() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
    ) {
        TopAppBar(
            title = { Text(chapterTitle, color = Color(0xFFE8E8E8), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
            actions = {
                IconButton(onClick = { showLangSettings = !showLangSettings }) {
                    if (translating) {
                        JiyuLoadingIndicator(size = 20.dp, strokeWidth = 2.dp)
                    } else {
                        Icon(
                            TablerIcons.Language,
                            stringResource(R.string.reader_translate_chapter_desc),
                            tint = if (translateMode) Color(0xFF8B5CF6) else Color(0xFFB0BEC5),
                        )
                    }
                }
                IconButton(onClick = { showSettings = !showSettings }) {
                    Icon(TablerIcons.Sun, stringResource(R.string.settings_title), tint = Color(0xFFB0BEC5))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D0D1A)),
        )

        AnimatedVisibility(visible = showLangSettings, enter = slideInVertically(), exit = slideOutVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1B35)),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        var showSourceMenu by remember { mutableStateOf(false) }
                        var showTargetMenu by remember { mutableStateOf(false) }
                        Box {
                            Text(
                                sourceLanguage,
                                color = Color(0xFF4FC3F7),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showSourceMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = showSourceMenu, onDismissRequest = { showSourceMenu = false }, modifier = Modifier.background(Color(0xFF1A2340))) {
                                LanguageMap.displayNames.forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang, color = Color.White, fontSize = 13.sp) }, onClick = { onSourceLanguageChange(lang); showSourceMenu = false })
                                }
                            }
                        }
                        Text(" → ", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                        Box {
                            Text(
                                targetLanguage,
                                color = Color(0xFF8B5CF6),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.1f))
                                    .clickable { showTargetMenu = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                            DropdownMenu(expanded = showTargetMenu, onDismissRequest = { showTargetMenu = false }, modifier = Modifier.background(Color(0xFF1A2340))) {
                                LanguageMap.displayNames.filter { it != "Auto" }.forEach { lang ->
                                    DropdownMenuItem(text = { Text(lang, color = Color.White, fontSize = 13.sp) }, onClick = { onTargetLanguageChange(lang); showTargetMenu = false })
                                }
                            }
                        }
                        TextButton(onClick = { showGlossarySheet = true }) {
                            Text(stringResource(R.string.reader_glossary_button), color = Color(0xFF8B5CF6))
                        }
                        TextButton(onClick = { onToggleTranslate(); showLangSettings = false }) {
                            Text(stringResource(if (translateMode) R.string.reader_original_toggle else R.string.reader_translate_toggle), color = Color(0xFF34D1BF))
                        }
                    }
                }
            }
        }

        if (showGlossarySheet) {
            GlossaryBottomSheet(
                glossary = glossary,
                targetLanguage = targetLanguage,
                onAdd = onAddGlossaryEntry,
                onRemove = onRemoveGlossaryEntry,
                onDismiss = { showGlossarySheet = false },
            )
        }

        AnimatedVisibility(visible = showSettings, enter = slideInVertically(), exit = slideOutVertically()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1B35)),
                shape = RoundedCornerShape(0.dp),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Text(stringResource(R.string.reader_font_size_label, fontSize.toInt()), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            IconButton(onClick = { if (fontSize > 10f) fontSize -= 1f }, modifier = Modifier.size(36.dp)) {
                                Text(stringResource(R.string.reader_font_decrease), color = Color(0xFFE8E8E8), fontSize = 13.sp)
                            }
                            IconButton(onClick = { if (fontSize < 30f) fontSize += 1f }, modifier = Modifier.size(36.dp)) {
                                Text(stringResource(R.string.reader_font_increase), color = Color(0xFFE8E8E8), fontSize = 17.sp)
                            }
                        }
                    }
                    Text(stringResource(R.string.reader_line_spacing_label, String.format("%.1f", lineSpacing)), color = Color(0xFFB0BEC5), fontSize = 13.sp)
                    Slider(
                        value = lineSpacing, onValueChange = { lineSpacing = it },
                        valueRange = 1.0f..2.5f,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF8B5CF6), activeTrackColor = Color(0xFF8B5CF6)),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            stringResource(R.string.reader_theme_dark),
                            stringResource(R.string.reader_theme_sepia),
                            stringResource(R.string.reader_theme_paper),
                            stringResource(R.string.reader_theme_white),
                        ).forEachIndexed { i, label ->
                            TextButton(
                                onClick = { bgColorIndex = i },
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (bgColorIndex == i) Color(0xFF8B5CF6) else Color(0xFFB0BEC5),
                                ),
                            ) { Text(label, fontSize = 11.sp) }
                        }
                    }
                }
            }
        }

        if (pageCurlEnabled) {
            PageCurlNovelReader(
                text = displayText,
                fontSize = fontSize,
                lineSpacing = lineSpacing,
                textColor = textColor,
                bgColor = bgColor,
                onChapterBoundary = { direction ->
                    if (direction == TurnDirection.NEXT) onNext() else onPrev()
                },
                curlStyle = curlStyle,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                items(paragraphs) { paragraph: String ->
                    Text(
                        text = paragraph,
                        color = textColor,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp,
                        modifier = Modifier.padding(bottom = (fontSize * 0.75f).dp),
                    )
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        Arrangement.SpaceBetween,
                    ) {
                        if (hasPrev) {
                            TextButton(onClick = onPrev) { Text(stringResource(R.string.reader_prev_novel), color = Color(0xFF34D1BF)) }
                        } else { Spacer(Modifier) }
                        if (hasNext) {
                            TextButton(onClick = onNext) { Text(stringResource(R.string.reader_next_novel), color = Color(0xFF34D1BF)) }
                        }
                    }
                }
            }
        }
    }
}
