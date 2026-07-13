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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            SettingsSubScreenHeader(title = "Nastavení čtečky", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = "Směr čtení") {
                    listOf(
                        ReadingDirection.LTR to "← Zleva doprava (manhwa, western)",
                        ReadingDirection.RTL to "→ Zprava doleva (japonská manga)",
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = direction == value, onClick = { viewModel.setReadingDirection(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Režim čtení") {
                    listOf(
                        ReadingMode.MANGA   to "Manga  (horizontální stránky)",
                        ReadingMode.WEBTOON to "Webtoon  (plynulé rolování)",
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = readingMode == value, onClick = { viewModel.setReadingMode(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Čtečka ───────────────────────────────────────────────────
                SettingsSection(title = "Čtečka") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = tapZonesEnabled, role = Role.Switch, onValueChange = { viewModel.setTapZonesEnabled(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Zóny pro tapnutí", color = TextPrimary, fontSize = 14.sp)
                            Text("Okraje listují stránky, střed zobrazí/skryje ovládání", color = TextSecondary, fontSize = 11.sp)
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
                                Text("Konfigurace zón", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
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
                        Text("Rychlost scrollování (Webtoon) · ${String.format("%.1f", webtoonScrollSpeed)}×", color = TextPrimary, fontSize = 14.sp)
                        Slider(
                            value = webtoonScrollSpeed,
                            onValueChange = { viewModel.setWebtoonScrollSpeed(it) },
                            valueRange = 0.5f..3.0f,
                            modifier = Modifier.semantics { contentDescription = "Rychlost scrollování Webtoon" },
                            colors = SliderDefaults.colors(thumbColor = GlowViolet, activeTrackColor = GlowViolet, inactiveTrackColor = GlowViolet.copy(alpha = 0.2f)),
                        )
                    }

                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Velikost textu překladu · ${String.format("%.1f", readerTextScale)}×", color = TextPrimary, fontSize = 14.sp)
                        Slider(
                            value = readerTextScale,
                            onValueChange = { viewModel.setReaderTextScale(it) },
                            valueRange = 0.7f..1.6f,
                            modifier = Modifier.semantics { contentDescription = "Velikost textu překladu" },
                            colors = SliderDefaults.colors(thumbColor = GlowViolet, activeTrackColor = GlowViolet, inactiveTrackColor = GlowViolet.copy(alpha = 0.2f)),
                        )
                    }

                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Přiblížení stránky", color = TextPrimary, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        listOf(
                            "fit_width"  to "Na šířku (výchozí)",
                            "fit_height" to "Na výšku",
                            "fit_screen" to "Na obrazovku",
                            "stretch"    to "Roztáhnout",
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
                            Text("Dvoustránkové zobrazení", color = TextPrimary, fontSize = 14.sp)
                            Text("Dvě stránky vedle sebe při otočení na šířku (manga mód)", color = TextSecondary, fontSize = 11.sp)
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
                            Text("Celá obrazovka", color = TextPrimary, fontSize = 14.sp)
                            Text("Skryje systémové lišty při čtení", color = TextSecondary, fontSize = 11.sp)
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
                            Text("OLED Black Mode", color = TextPrimary, fontSize = 14.sp)
                            Text("Čistě černé pozadí mezi stránkami — šetří baterii OLED displejů", color = TextSecondary, fontSize = 11.sp)
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
                            Text("Po dočtení přejít na další kapitolu", color = TextPrimary, fontSize = 14.sp)
                            Text("Po dosažení poslední stránky se automaticky přejde na další kapitolu", color = TextSecondary, fontSize = 11.sp)
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
                            Text("Ořez bílých okrajů", color = TextPrimary, fontSize = 14.sp)
                            Text("Automaticky odstraní prázdné okraje stránek", color = TextSecondary, fontSize = 11.sp)
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
                            Text("Udržet obrazovku zapnutou", color = TextPrimary, fontSize = 14.sp)
                            Text("Obrazovka nezhasne při čtení", color = TextSecondary, fontSize = 11.sp)
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
                            Text("Navigace hlasitostními tlačítky", color = TextPrimary, fontSize = 14.sp)
                            Text("Tlačítka hl. + / hl. − přepínají stránky", color = TextSecondary, fontSize = 11.sp)
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
                            Text("Přeskakovat přečtené kapitoly", color = TextPrimary, fontSize = 14.sp)
                            Text("Navigace přeskočí kapitoly označené jako přečtené", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = skipReadChapters,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Téma čtečky") {
                    listOf(
                        "dark"  to "Tmavé (výchozí)",
                        "sepia" to "Sépia",
                        "paper" to "Papír",
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = readerTheme == value, onClick = { viewModel.setReaderTheme(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Překlad") {
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
