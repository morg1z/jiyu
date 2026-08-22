package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.screenGradient

private val TRANSLATE_LANGUAGES = listOf(
    "Czech"   to "Čeština",
    "Slovak"  to "Slovenčina",
    "English" to "English",
    "Polish"  to "Polski",
    "German"  to "Deutsch",
    "Spanish" to "Español",
    "French"  to "Français",
)

@Composable
fun ReaderSettingsScreen(
    onBack: () -> Unit,
    onOpenTapZones: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val language           by viewModel.targetLanguage.collectAsState()
    val direction          by viewModel.readingDirection.collectAsState()
    val readingMode        by viewModel.readingMode.collectAsState()
    val tapZonesEnabled    by viewModel.tapZonesEnabled.collectAsState()
    val tapZoneGrid        by viewModel.tapZoneGrid.collectAsState()
    val webtoonScrollSpeed by viewModel.webtoonScrollSpeed.collectAsState()
    val readerTextScale    by viewModel.readerTextScale.collectAsState()
    val doublePageSpread   by viewModel.doublePageSpread.collectAsState()
    val fullscreenEnabled  by viewModel.fullscreenEnabled.collectAsState()
    val readerTheme        by viewModel.readerTheme.collectAsState()
    val oledMode           by viewModel.oledMode.collectAsState()
    val autoNextChapter    by viewModel.autoNextChapter.collectAsState()
    val pageCurlEnabled    by viewModel.pageCurlEnabled.collectAsState()
    val preloadNextNovelChapter by viewModel.preloadNextNovelChapter.collectAsState()
    val preloadNextChapterManga by viewModel.preloadNextChapterManga.collectAsState()
    val preloadNextChapterWifiOnly by viewModel.preloadNextChapterWifiOnly.collectAsState()
    val cropBorders        by viewModel.cropBorders.collectAsState()
    val pageScale          by viewModel.pageScale.collectAsState()
    val keepScreenOn       by viewModel.keepScreenOn.collectAsState()
    val volumeKeysNav      by viewModel.volumeKeysNav.collectAsState()
    val skipReadChapters   by viewModel.skipReadChapters.collectAsState()

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_main_reader_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(R.string.settings_reader_direction_title)) {
                    listOf(
                        ReadingDirection.LTR to stringResource(R.string.settings_reader_direction_ltr),
                        ReadingDirection.RTL to stringResource(R.string.settings_reader_direction_rtl),
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = direction == value, onClick = { viewModel.setReadingDirection(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(R.string.settings_reader_mode_title)) {
                    listOf(
                        ReadingMode.MANGA   to stringResource(R.string.settings_reader_mode_manga),
                        ReadingMode.WEBTOON to stringResource(R.string.settings_reader_mode_webtoon),
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = readingMode == value, onClick = { viewModel.setReadingMode(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Zobrazení stránky ───────────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_page_display_section_title)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.settings_reader_page_scale_title), color = TextPrimary, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            "fit_width"  to stringResource(R.string.settings_reader_page_scale_fit_width),
                            "fit_height" to stringResource(R.string.settings_reader_page_scale_fit_height),
                            "fit_screen" to stringResource(R.string.settings_reader_page_scale_fit_screen),
                            "stretch"    to stringResource(R.string.settings_reader_page_scale_stretch),
                        ).forEach { (value, label) ->
                            GlassRadioRow(label = label, selected = pageScale == value, onClick = { viewModel.setPageScale(value) })
                        }
                    }

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_double_page_title),
                        description = stringResource(R.string.settings_reader_double_page_desc),
                        checked = doublePageSpread,
                        onCheckedChange = { viewModel.setDoublePageSpread(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_crop_borders_title),
                        description = stringResource(R.string.settings_reader_crop_borders_desc),
                        checked = cropBorders,
                        onCheckedChange = { viewModel.setCropBorders(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_oled_title),
                        description = stringResource(R.string.settings_reader_oled_desc),
                        checked = oledMode,
                        onCheckedChange = { viewModel.setOledMode(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_fullscreen_title),
                        description = stringResource(R.string.settings_reader_fullscreen_desc),
                        checked = fullscreenEnabled,
                        onCheckedChange = { viewModel.setFullscreenEnabled(it) },
                    )

                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val scrollSpeedDesc = stringResource(R.string.settings_reader_scroll_speed_desc)
                        Text(stringResource(R.string.settings_reader_scroll_speed, String.format("%.1f", webtoonScrollSpeed)), color = TextPrimary, fontSize = 14.sp)
                        Slider(
                            value = webtoonScrollSpeed,
                            onValueChange = { viewModel.setWebtoonScrollSpeed(it) },
                            valueRange = 0.5f..3.0f,
                            modifier = Modifier.semantics { contentDescription = scrollSpeedDesc },
                            colors = SliderDefaults.colors(thumbColor = GlowViolet, activeTrackColor = GlowViolet, inactiveTrackColor = GlowViolet.copy(alpha = 0.2f)),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Navigace a chování ──────────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_navigation_section_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_tap_zones_title),
                        description = stringResource(R.string.settings_reader_tap_zones_desc),
                        checked = tapZonesEnabled,
                        onCheckedChange = { viewModel.setTapZonesEnabled(it) },
                    )

                    if (tapZonesEnabled) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTapZones() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.settings_reader_tap_zones_config), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                Text(
                                    buildZoneGridSummary(tapZoneGrid),
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                )
                            }
                            Text("›", color = TextSecondary, fontSize = 20.sp, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_volume_keys_title),
                        description = stringResource(R.string.settings_reader_volume_keys_desc),
                        checked = volumeKeysNav,
                        onCheckedChange = { viewModel.setVolumeKeysNav(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_keep_screen_on_title),
                        description = stringResource(R.string.settings_reader_keep_screen_on_desc),
                        checked = keepScreenOn,
                        onCheckedChange = { viewModel.setKeepScreenOn(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_skip_read_title),
                        description = stringResource(R.string.settings_reader_skip_read_desc),
                        checked = skipReadChapters,
                        onCheckedChange = { viewModel.setSkipReadChapters(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_auto_next_title),
                        description = stringResource(R.string.settings_reader_auto_next_desc),
                        checked = autoNextChapter,
                        onCheckedChange = { viewModel.setAutoNextChapter(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Otáčení stránek (novel + manga/manhwa) ────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_page_curl_section_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_page_curl_title),
                        description = stringResource(R.string.settings_reader_page_curl_desc),
                        checked = pageCurlEnabled,
                        onCheckedChange = { viewModel.setPageCurlEnabled(it) },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Přednačítání překladu ───────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_preload_section_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_preload_novel_title),
                        description = stringResource(R.string.settings_reader_preload_novel_desc),
                        checked = preloadNextNovelChapter,
                        onCheckedChange = { viewModel.setPreloadNextNovelChapter(it) },
                    )

                    SettingsToggleRow(
                        title = stringResource(R.string.settings_reader_preload_manga_title),
                        description = stringResource(R.string.settings_reader_preload_manga_desc),
                        checked = preloadNextChapterManga,
                        onCheckedChange = { viewModel.setPreloadNextChapterManga(it) },
                    )

                    if (preloadNextChapterManga) {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_reader_preload_manga_wifi_title),
                            description = stringResource(R.string.settings_reader_preload_manga_wifi_desc),
                            checked = preloadNextChapterWifiOnly,
                            onCheckedChange = { viewModel.setPreloadNextChapterWifiOnly(it) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(R.string.settings_reader_theme_title)) {
                    listOf(
                        "dark"  to stringResource(R.string.settings_reader_theme_dark_default),
                        "sepia" to stringResource(R.string.settings_reader_theme_sepia),
                        "paper" to stringResource(R.string.settings_reader_theme_paper),
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = readerTheme == value, onClick = { viewModel.setReaderTheme(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(R.string.settings_reader_translate_section_title)) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val textScaleDesc = stringResource(R.string.settings_reader_text_scale_desc)
                        Text(stringResource(R.string.settings_reader_text_scale, String.format("%.1f", readerTextScale)), color = TextPrimary, fontSize = 14.sp)
                        Slider(
                            value = readerTextScale,
                            onValueChange = { viewModel.setReaderTextScale(it) },
                            valueRange = 0.7f..1.6f,
                            modifier = Modifier.semantics { contentDescription = textScaleDesc },
                            colors = SliderDefaults.colors(thumbColor = GlowViolet, activeTrackColor = GlowViolet, inactiveTrackColor = GlowViolet.copy(alpha = 0.2f)),
                        )
                    }
                    TRANSLATE_LANGUAGES.forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = language == value, onClick = { viewModel.setTargetLanguage(value) })
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
