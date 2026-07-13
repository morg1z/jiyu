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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    val cropBorders        by viewModel.cropBorders.collectAsState()
    val pageScale          by viewModel.pageScale.collectAsState()
    val keepScreenOn       by viewModel.keepScreenOn.collectAsState()
    val volumeKeysNav      by viewModel.volumeKeysNav.collectAsState()
    val skipReadChapters   by viewModel.skipReadChapters.collectAsState()

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
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

                // ── Čtečka ───────────────────────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_reader_section_title)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = tapZonesEnabled, role = Role.Switch, onValueChange = { viewModel.setTapZonesEnabled(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_tap_zones_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_tap_zones_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = tapZonesEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = doublePageSpread, role = Role.Switch, onValueChange = { viewModel.setDoublePageSpread(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_double_page_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_double_page_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = doublePageSpread,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = fullscreenEnabled, role = Role.Switch, onValueChange = { viewModel.setFullscreenEnabled(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_fullscreen_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_fullscreen_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = fullscreenEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = oledMode, role = Role.Switch, onValueChange = { viewModel.setOledMode(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_oled_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_oled_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = oledMode,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = autoNextChapter, role = Role.Switch, onValueChange = { viewModel.setAutoNextChapter(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_auto_next_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_auto_next_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoNextChapter,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = cropBorders, role = Role.Switch, onValueChange = { viewModel.setCropBorders(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_crop_borders_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_crop_borders_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = cropBorders,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = keepScreenOn, role = Role.Switch, onValueChange = { viewModel.setKeepScreenOn(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_keep_screen_on_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_keep_screen_on_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = keepScreenOn,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = volumeKeysNav, role = Role.Switch, onValueChange = { viewModel.setVolumeKeysNav(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_volume_keys_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_volume_keys_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = volumeKeysNav,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = skipReadChapters, role = Role.Switch, onValueChange = { viewModel.setSkipReadChapters(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_reader_skip_read_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_reader_skip_read_desc), color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = skipReadChapters,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
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
