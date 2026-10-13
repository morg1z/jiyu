package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.settings.CurlStyleSetting
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.ReaderTheme
import compose.icons.TablerIcons
import compose.icons.tablericons.Check

// ── Sheet nastavení čtečky ───────────────────────────────────────────────────
// Otevírá se z ikony ozubeného kola v horní liště (uživatelský požadavek - vše
// kolem čtení na jednom místě přímo v čtečce, bez odchodu do globálního
// Nastavení). Sekce kopírují ReaderSettingsScreen, ale kompaktněji - jen to, co
// dává smysl měnit uprostřed čtení. Per-manga přepínač režimu je nahoře, protože
// je to nejčastější důvod otevření (jiný titul = jiný ideální režim).
//
// Změny se promítají ŽIVĚ - zápisy jedou přes SettingsRepository (DataStore) a
// čtečka je čte zpátky přes StateFlow, takže např. přepnutí na Webtoon režim
// přeskládá stránky pod sheetem okamžitě.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    mangaDirectionOverride: String?,
    onMangaDirectionChange: (String?) -> Unit,
    readingMode: String,
    onReadingModeChange: (String) -> Unit,
    readingDirection: String,
    onReadingDirectionChange: (String) -> Unit,
    pageScale: String,
    onPageScaleChange: (String) -> Unit,
    doublePageSpread: Boolean,
    onDoublePageSpreadChange: (Boolean) -> Unit,
    cropBorders: Boolean,
    onCropBordersChange: (Boolean) -> Unit,
    oledMode: Boolean,
    onOledModeChange: (Boolean) -> Unit,
    fullscreenEnabled: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    webtoonScrollSpeed: Float,
    onWebtoonScrollSpeedChange: (Float) -> Unit,
    tapZonesEnabled: Boolean,
    onTapZonesChange: (Boolean) -> Unit,
    volumeKeysNav: Boolean,
    onVolumeKeysNavChange: (Boolean) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    skipReadChapters: Boolean,
    onSkipReadChaptersChange: (Boolean) -> Unit,
    autoNextChapter: Boolean,
    onAutoNextChapterChange: (Boolean) -> Unit,
    infiniteScrollEnabled: Boolean,
    onInfiniteScrollChange: (Boolean) -> Unit,
    pageCurlEnabled: Boolean,
    onPageCurlChange: (Boolean) -> Unit,
    curlStyle: String,
    onCurlStyleChange: (String) -> Unit,
    readerTheme: String,
    onReaderThemeChange: (String) -> Unit,
    textScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF111B35),
    ) {
        Text(
            text = stringResource(R.string.settings_main_reader_title),
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            // ── Režim čtení tohoto titulu (per-manga override) ─────────────
            // Přepíná kombinovaný override MangaEntity.readerDirectionOverride -
            // stejnou hodnotu nabízí detail titulu. "Výchozí" = držet se globálního
            // nastavení pod sebou.
            SheetSectionTitle(stringResource(R.string.settings_reader_mode_title))
            Text(
                text = stringResource(R.string.reader_settings_this_manga),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
            listOf<Pair<String?, String>>(
                null      to stringResource(R.string.detail_info_dir_default),
                "LTR"     to stringResource(R.string.detail_info_dir_ltr),
                "RTL"     to stringResource(R.string.detail_info_dir_rtl),
                "WEBTOON" to stringResource(R.string.detail_info_dir_webtoon),
            ).forEach { (value, label) ->
                SheetRadioRow(label = label, selected = mangaDirectionOverride == value, onClick = { onMangaDirectionChange(value) })
            }

            // ── Výchozí režim/směr (globální nastavení pro tituly bez override) ──
            SheetSectionTitle(stringResource(R.string.reader_settings_defaults_section))
            listOf(
                ReadingMode.MANGA   to stringResource(R.string.settings_reader_mode_manga),
                ReadingMode.WEBTOON to stringResource(R.string.settings_reader_mode_webtoon),
            ).forEach { (value, label) ->
                SheetRadioRow(label = label, selected = readingMode == value, onClick = { onReadingModeChange(value) })
            }
            listOf(
                ReadingDirection.LTR to stringResource(R.string.settings_reader_direction_ltr),
                ReadingDirection.RTL to stringResource(R.string.settings_reader_direction_rtl),
            ).forEach { (value, label) ->
                SheetRadioRow(label = label, selected = readingDirection == value, onClick = { onReadingDirectionChange(value) })
            }

            SheetDivider()
            SheetSectionTitle(stringResource(R.string.settings_reader_page_display_section_title))
            Text(
                text = stringResource(R.string.settings_reader_page_scale_title),
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
            )
            listOf(
                "fit_width"  to stringResource(R.string.settings_reader_page_scale_fit_width),
                "fit_height" to stringResource(R.string.settings_reader_page_scale_fit_height),
                "fit_screen" to stringResource(R.string.settings_reader_page_scale_fit_screen),
                "stretch"    to stringResource(R.string.settings_reader_page_scale_stretch),
            ).forEach { (value, label) ->
                SheetRadioRow(label = label, selected = pageScale == value, onClick = { onPageScaleChange(value) })
            }
            SheetToggleRow(stringResource(R.string.settings_reader_double_page_title), doublePageSpread, onDoublePageSpreadChange)
            SheetToggleRow(stringResource(R.string.settings_reader_crop_borders_title), cropBorders, onCropBordersChange)
            SheetToggleRow(stringResource(R.string.settings_reader_oled_title), oledMode, onOledModeChange)
            SheetToggleRow(stringResource(R.string.settings_reader_fullscreen_title), fullscreenEnabled, onFullscreenChange)

            // Rychlost scrollu - draft během tahu, persist až při puštění (stejný
            // vzor jako ReaderSettingsScreen, SET-3) - jinak každý frame tahu píše
            // do DataStore.
            var speedDraft by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(webtoonScrollSpeed) { speedDraft = null }
            val shownSpeed = speedDraft ?: webtoonScrollSpeed
            Text(
                text = stringResource(R.string.settings_reader_scroll_speed, String.format("%.1f", shownSpeed)),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp, top = 6.dp),
            )
            Slider(
                value = shownSpeed,
                onValueChange = { speedDraft = it },
                onValueChangeFinished = { speedDraft?.let(onWebtoonScrollSpeedChange) },
                valueRange = 0.5f..3.0f,
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF8B5CF6),
                    activeTrackColor = Color(0xFF8B5CF6),
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )

            SheetDivider()
            SheetSectionTitle(stringResource(R.string.settings_reader_navigation_section_title))
            SheetToggleRow(stringResource(R.string.settings_reader_tap_zones_title), tapZonesEnabled, onTapZonesChange)
            SheetToggleRow(stringResource(R.string.settings_reader_volume_keys_title), volumeKeysNav, onVolumeKeysNavChange)
            SheetToggleRow(stringResource(R.string.settings_reader_keep_screen_on_title), keepScreenOn, onKeepScreenOnChange)
            SheetToggleRow(stringResource(R.string.settings_reader_skip_read_title), skipReadChapters, onSkipReadChaptersChange)
            SheetToggleRow(stringResource(R.string.settings_reader_auto_next_title), autoNextChapter, onAutoNextChapterChange)
            SheetToggleRow(stringResource(R.string.settings_reader_infinite_scroll_title), infiniteScrollEnabled, onInfiniteScrollChange)

            SheetDivider()
            SheetSectionTitle(stringResource(R.string.settings_reader_page_curl_section_title))
            SheetToggleRow(stringResource(R.string.settings_reader_page_curl_title), pageCurlEnabled, onPageCurlChange)
            if (pageCurlEnabled) {
                listOf(
                    CurlStyleSetting.CLASSIC to stringResource(R.string.settings_reader_curl_style_classic),
                    CurlStyleSetting.ROLL    to stringResource(R.string.settings_reader_curl_style_roll),
                ).forEach { (value, label) ->
                    SheetRadioRow(label = label, selected = curlStyle == value, onClick = { onCurlStyleChange(value) })
                }
            }

            SheetDivider()
            SheetSectionTitle(stringResource(R.string.settings_reader_theme_title))
            listOf(
                ReaderTheme.DARK  to stringResource(R.string.settings_reader_theme_dark_default),
                ReaderTheme.SEPIA to stringResource(R.string.settings_reader_theme_sepia),
                ReaderTheme.PAPER to stringResource(R.string.settings_reader_theme_paper),
            ).forEach { (value, label) ->
                SheetRadioRow(label = label, selected = readerTheme == value, onClick = { onReaderThemeChange(value) })
            }

            SheetDivider()
            SheetSectionTitle(stringResource(R.string.settings_reader_translate_section_title))
            var scaleDraft by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(textScale) { scaleDraft = null }
            val shownScale = scaleDraft ?: textScale
            Text(
                text = stringResource(R.string.settings_reader_text_scale, String.format("%.1f", shownScale)),
                color = Color.White,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
            )
            Slider(
                value = shownScale,
                onValueChange = { scaleDraft = it },
                onValueChangeFinished = { scaleDraft?.let(onTextScaleChange) },
                valueRange = 0.7f..1.6f,
                modifier = Modifier.padding(horizontal = 8.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Color(0xFF8B5CF6),
                    activeTrackColor = Color(0xFF8B5CF6),
                    inactiveTrackColor = Color.White.copy(alpha = 0.2f),
                ),
            )
        }
    }
}

// ── Pomocné řádky ve stylu ostatních reader sheetů ───────────────────────────

@Composable
private fun SheetSectionTitle(title: String) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp),
    )
}

@Composable
private fun SheetDivider() {
    HorizontalDivider(
        color = Color.White.copy(alpha = 0.08f),
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** Radio řádek: kolečko + label, fialový akcent pro vybranou hodnotu. */
@Composable
private fun SheetRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(
                    if (selected) Color(0xFF8B5CF6)
                    else Color.White.copy(alpha = 0.15f),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    TablerIcons.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/** Přepínač: label vlevo, Switch vpravo (barvy jako v překladovém sheetu). */
@Composable
private fun SheetToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = Color.White,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF8B5CF6),
                checkedTrackColor = Color(0xFF8B5CF6).copy(alpha = 0.4f),
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
    }
}
