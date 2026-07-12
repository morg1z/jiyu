package com.haise.jiyu.ui.settings

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ReadingMode
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.ui.reader.TapZoneAction
import com.haise.jiyu.ui.reader.TapZoneGrid
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import java.time.LocalDate

private val LANGUAGES = listOf(
    "Czech"   to "Čeština",
    "Slovak"  to "Slovenčina",
    "English" to "English",
    "Polish"  to "Polski",
    "German"  to "Deutsch",
    "Spanish" to "Español",
    "French"  to "Français",
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDownloadManager: () -> Unit = {},
    onOpenSourceCatalog: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenCustomCss: () -> Unit = {},
    onOpenGoals: () -> Unit = {},
    onOpenCommunity: () -> Unit = {},
    onOpenDuplicates: () -> Unit = {},
    onOpenTapZones: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val language          by viewModel.targetLanguage.collectAsState()
    val theme             by viewModel.theme.collectAsState()
    val direction         by viewModel.readingDirection.collectAsState()
    val readingMode       by viewModel.readingMode.collectAsState()
    val cacheCount        by viewModel.cacheCount.collectAsState()
    val backupState       by viewModel.backupState.collectAsState()
    val settingsBackupState by viewModel.settingsBackupState.collectAsState()
    val downloadedCount   by viewModel.downloadedCount.collectAsState()
    val updateInterval    by viewModel.updateIntervalHours.collectAsState()
    val customSources     by viewModel.customSources.collectAsState()
    val tapZonesEnabled    by viewModel.tapZonesEnabled.collectAsState()
    val tapZoneGrid        by viewModel.tapZoneGrid.collectAsState()
    val webtoonScrollSpeed by viewModel.webtoonScrollSpeed.collectAsState()
    val readerTextScale    by viewModel.readerTextScale.collectAsState()
    val doublePageSpread  by viewModel.doublePageSpread.collectAsState()
    val autoDeleteRead    by viewModel.autoDeleteRead.collectAsState()
    val autoDeleteDelayDays by viewModel.autoDeleteDelayDays.collectAsState()
    val downloadOnlyWifi  by viewModel.downloadOnlyWifi.collectAsState()
    val downloadFolderUri by viewModel.downloadFolderUri.collectAsState()
    val fullscreenEnabled by viewModel.fullscreenEnabled.collectAsState()
    val readerTheme       by viewModel.readerTheme.collectAsState()
    val oledMode          by viewModel.oledMode.collectAsState()
    val autoNextChapter   by viewModel.autoNextChapter.collectAsState()
    val cropBorders       by viewModel.cropBorders.collectAsState()
    val tachyImportResult by viewModel.tachyImportResult.collectAsState()
    val tachyImportInProgress by viewModel.tachyImportInProgress.collectAsState()
    val malIsLoggedIn by viewModel.malIsLoggedIn.collectAsState()
    val malUsername by viewModel.malUsername.collectAsState()
    val kitsuIsLoggedIn    by viewModel.kitsuIsLoggedIn.collectAsState()
    val kitsuUsername      by viewModel.kitsuUsername.collectAsState()
    val kitsuLoginLoading  by viewModel.kitsuLoginLoading.collectAsState()
    val kitsuLoginError    by viewModel.kitsuLoginError.collectAsState()
    val muIsLoggedIn       by viewModel.muIsLoggedIn.collectAsState()
    val muUsername         by viewModel.muUsername.collectAsState()
    val muLoginLoading     by viewModel.muLoginLoading.collectAsState()
    val muLoginError       by viewModel.muLoginError.collectAsState()
    val pageScale by viewModel.pageScale.collectAsState()
    val autoBackupEnabled  by viewModel.autoBackupEnabled.collectAsState()
    val keepScreenOn       by viewModel.keepScreenOn.collectAsState()
    val volumeKeysNav      by viewModel.volumeKeysNav.collectAsState()
    val skipReadChapters   by viewModel.skipReadChapters.collectAsState()
    val parallelDownloads  by viewModel.parallelDownloads.collectAsState()
    val saveAsCbz          by viewModel.saveAsCbz.collectAsState()
    val libraryGridColumns by viewModel.libraryGridColumns.collectAsState()
    val defaultCategoryId  by viewModel.defaultCategoryId.collectAsState()
    val allCategories      by viewModel.categories.collectAsState()
    val notifyNewChapters  by viewModel.notifyNewChapters.collectAsState()
    val notifyDownloads    by viewModel.notifyDownloads.collectAsState()
    val backupFolderUri    by viewModel.backupFolderUri.collectAsState()

    val snackbarHost = remember { SnackbarHostState() }

    // Exportní launcher — uživatel vybere kam uložit soubor
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportBackup(it) } }

    // Importní launcher — uživatel vybere zálohu (Jiyu formát)
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importBackup(it) } }

    // Importní launcher — Tachiyomi/Mihon JSON záloha
    val tachyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importTachiyomiBackup(it) } }

    // Export/import launcher — nastavení appky (téma, tap zóny, parallel downloads...)
    val settingsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportSettings(it) } }
    val settingsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importSettings(it) } }

    // Snackbar pro výsledek zálohy
    LaunchedEffect(backupState) {
        when (val s = backupState) {
            is BackupUiState.Success -> { snackbarHost.showSnackbar(s.message); viewModel.clearBackupState() }
            is BackupUiState.Error   -> { snackbarHost.showSnackbar("Chyba: ${s.message}"); viewModel.clearBackupState() }
            else -> Unit
        }
    }

    LaunchedEffect(settingsBackupState) {
        when (val s = settingsBackupState) {
            is BackupUiState.Success -> { snackbarHost.showSnackbar(s.message); viewModel.clearSettingsBackupState() }
            is BackupUiState.Error   -> { snackbarHost.showSnackbar("Chyba: ${s.message}"); viewModel.clearSettingsBackupState() }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
                }
                Text(
                    text = "Nastavení",
                    style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                // ── Jazyk aplikace ────────────────────────────────────────────
                SettingsSection(title = androidx.compose.ui.res.stringResource(com.haise.jiyu.R.string.settings_language)) {
                    val appLanguages = listOf(
                        "cs" to "🇨🇿  Čeština",
                        "en" to "🇬🇧  English",
                        "fr" to "🇫🇷  Français",
                        "es" to "🇪🇸  Español",
                    )
                    appLanguages.forEach { (tag, label) ->
                        val currentTag = androidx.appcompat.app.AppCompatDelegate
                            .getApplicationLocales().toLanguageTags()
                            .split(",").firstOrNull()?.take(2) ?: "cs"
                        GlassRadioRow(
                            label = label,
                            selected = currentTag == tag,
                            onClick = { viewModel.setLanguage(tag) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = androidx.compose.ui.res.stringResource(com.haise.jiyu.R.string.settings_download_folder)) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val folderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                    ) { uri ->
                        if (uri != null) {
                            ctx.contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            viewModel.setDownloadFolderUri(uri.toString())
                        }
                    }
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(
                            text = if (downloadFolderUri != null)
                                android.net.Uri.parse(downloadFolderUri).lastPathSegment ?: downloadFolderUri!!
                            else
                                androidx.compose.ui.res.stringResource(com.haise.jiyu.R.string.settings_download_folder_default),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { folderPicker.launch(null) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                                border = BorderStroke(1.dp, GlowViolet.copy(alpha = 0.4f)),
                                modifier = Modifier.weight(1f),
                            ) { Text(androidx.compose.ui.res.stringResource(com.haise.jiyu.R.string.settings_download_folder_change), fontSize = 13.sp) }
                            if (downloadFolderUri != null) {
                                OutlinedButton(
                                    onClick = { viewModel.setDownloadFolderUri(null) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                ) { Text("Reset") }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Překlad") {
                    LANGUAGES.forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = language == value, onClick = { viewModel.setTargetLanguage(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Téma") {
                    listOf(
                        ThemeOption.SYSTEM to "Systémové",
                        ThemeOption.LIGHT to "Světlé",
                        ThemeOption.DARK to "Tmavé",
                        ThemeOption.TRUE_BLACK to "Čistě černé (OLED)",
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = theme == value, onClick = { viewModel.setTheme(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

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

                // ── Záloha ───────────────────────────────────────────────────
                SettingsSection(title = "Záloha knihovny") {
                    val isWorking = backupState is BackupUiState.Working

                    Text(
                        text = "Export uloží všechnu mangu, kapitoly, progress a kategorie do JSON souboru.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = autoBackupEnabled, role = Role.Switch, onValueChange = { viewModel.setAutoBackupEnabled(it) })
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Automatická záloha", color = TextPrimary, fontSize = 14.sp)
                            Text("Denně ukládá zálohu do úložiště zařízení (3 zálohy)", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = autoBackupEnabled,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = GlowViolet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    // ── Cíl automatické zálohy — libovolná SAF složka, klidně lokálně
                    //    synchronizovaná Google Drive / Dropbox / OneDrive appkou ──
                    run {
                        val backupFolderCtx = androidx.compose.ui.platform.LocalContext.current
                        val backupFolderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                            androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
                        ) { uri ->
                            if (uri != null) {
                                backupFolderCtx.contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                                viewModel.setBackupFolderUri(uri.toString())
                            }
                        }
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                text = if (backupFolderUri != null)
                                    "Cíl auto-zálohy: ${Uri.parse(backupFolderUri).lastPathSegment ?: backupFolderUri}"
                                else
                                    "Cíl auto-zálohy: úložiště zařízení (výchozí)",
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                            Text(
                                "Vyber složku synchronizovanou appkou Google Drive/Dropbox pro cloudovou zálohu bez nastavování API.",
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { backupFolderPicker.launch(null) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                                    modifier = Modifier.weight(1f),
                                ) { Text("Vybrat složku", fontSize = 12.sp) }
                                if (backupFolderUri != null) {
                                    OutlinedButton(
                                        onClick = { viewModel.setBackupFolderUri(null) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) { Text("Reset", fontSize = 12.sp) }
                                }
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        OutlinedButton(
                            onClick = {
                                val date = LocalDate.now().toString()
                                exportLauncher.launch("jiyu_backup_$date.json")
                            },
                            enabled = !isWorking,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                        ) {
                            if (isWorking) JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                            Text("Exportovat")
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            enabled = !isWorking,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                        ) {
                            Text("Importovat")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(12.dp))

                // ── Záloha nastavení ───────────────────────────────────────────
                SettingsSection(title = "Záloha nastavení") {
                    val isSettingsWorking = settingsBackupState is BackupUiState.Working
                    Text(
                        text = "Uloží nastavení appky (téma, tap zóny, čtečka...) do JSON. Přihlašovací tokeny se neexportují.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        OutlinedButton(
                            onClick = {
                                val date = LocalDate.now().toString()
                                settingsExportLauncher.launch("jiyu_settings_$date.json")
                            },
                            enabled = !isSettingsWorking,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                        ) {
                            if (isSettingsWorking) JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                            Text("Exportovat")
                        }
                        OutlinedButton(
                            onClick = { settingsImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            enabled = !isSettingsWorking,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                        ) {
                            Text("Importovat")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(12.dp))

                // ── Tachiyomi/Mihon import ────────────────────────────────────
                SettingsSection(title = "Import z Mihon / Tachiyomi") {
                    Text(
                        text = "Importuj knihovnu z Mihon nebo Tachiyomi. V Mihon: Settings → Backup → Create backup → JSON. Přečtené kapitoly se zachovají.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    OutlinedButton(
                        onClick = { tachyImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                        enabled = !tachyImportInProgress,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                    ) {
                        if (tachyImportInProgress) {
                            JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), size = 16.dp, strokeWidth = 2.dp)
                        }
                        Text(if (tachyImportInProgress) "Importuji..." else "Vybrat zálohu Mihon (.json)")
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Dialog výsledku Tachiyomi importu
                tachyImportResult?.let { result ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { viewModel.clearTachyImportResult() },
                        containerColor = NightBlue,
                        title = { Text("Import dokončen", color = TextPrimary) },
                        text = {
                            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
                                Text("Importováno: ${result.imported}", color = Cyan)
                                Text("Přeskočeno (již v knihovně): ${result.skipped}", color = TextSecondary)
                                if (result.errors.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Chyby:", color = Color(0xFFEF4444))
                                    result.errors.take(3).forEach {
                                        Text("• $it", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.clearTachyImportResult() }) {
                                Text("OK", color = Violet)
                            }
                        },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // ── Interval aktualizací ──────────────────────────────────────
                SettingsSection(title = "Interval aktualizací") {
                    listOf(6L to "6 hodin", 12L to "12 hodin", 24L to "1 den", 48L to "2 dny").forEach { (hours, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = updateInterval == hours, role = Role.RadioButton, onClick = { viewModel.setUpdateInterval(hours) })
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = updateInterval == hours, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = GlowViolet, unselectedColor = TextSecondary))
                            Text(text = label, color = if (updateInterval == hours) TextPrimary else TextSecondary, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Stažené kapitoly ─────────────────────────────────────────
                SettingsSection(title = "Stažené kapitoly") {
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    Text(
                        text = "Staženo: $downloadedCount kapitol",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        OutlinedButton(
                            onClick = onOpenDownloadManager,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                        ) { Text("Správa") }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = downloadedCount > 0,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(TablerIcons.Trash, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text("Smazat vše")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (showDeleteConfirm) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            containerColor = androidx.compose.ui.graphics.Color(0xFF111B35),
                            title = { Text("Smazat vše?", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = { Text("Smaže $downloadedCount stažených kapitol z disku.", color = TextSecondary) },
                            confirmButton = {
                                TextButton(onClick = { viewModel.deleteAllDownloads(); showDeleteConfirm = false }) {
                                    Text("Smazat", color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Zrušit", color = TextSecondary) } },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Účet / Cloud sync ─────────────────────────────────────────
                SettingsSection(title = "Účet") {
                    OutlinedButton(
                        onClick = onOpenAccount,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        Text("Přihlášení & Cloud sync")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Katalog zdrojů ────────────────────────────────────────────
                SettingsSection(title = "Zdroje") {
                    OutlinedButton(
                        onClick = onOpenSourceCatalog,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text("Katalog zdrojů (${viewModel.getCatalog().size})")
                    }
                    OutlinedButton(
                        onClick = onOpenCustomCss,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text("Vlastní CSS pro web zdroje")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Vlastní zdroje (Madara) ───────────────────────────────────
                SettingsSection(title = "Vlastní zdroje (Madara)") {
                    var showAddDialog by remember { mutableStateOf(false) }

                    Text(
                        text = "Generický zdroj pro weby postavené na Madara šabloně. Zadej název a adresu webu - appka proti ní zkusí parsovat standardní Madara markup.",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    customSources.forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                                Text(source.baseUrl, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                            IconButton(onClick = { viewModel.deleteCustomSource(source) }) {
                                Icon(TablerIcons.Trash, contentDescription = "Smazat", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                    ) {
                        Icon(TablerIcons.Plus, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Přidat zdroj")
                    }

                    if (showAddDialog) {
                        var name by remember { mutableStateOf("") }
                        var url by remember { mutableStateOf("") }
                        var selectedContentType by remember { mutableStateOf("MANGA") }
                        var showAdvanced by remember { mutableStateOf(false) }
                        var listItemSel by remember { mutableStateOf("") }
                        var titleLinkSel by remember { mutableStateOf("") }
                        var descriptionSel by remember { mutableStateOf("") }
                        var statusSel by remember { mutableStateOf("") }
                        var chapterListSel by remember { mutableStateOf("") }
                        var pageImageSel by remember { mutableStateOf("") }
                        val testState by viewModel.sourceTestState.collectAsState()

                        DisposableEffect(Unit) { onDispose { viewModel.clearSourceTestState() } }

                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showAddDialog = false },
                            containerColor = androidx.compose.ui.graphics.Color(0xFF111B35),
                            title = { Text("Přidat Madara zdroj", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 420.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    TextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = { Text("Název") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    TextField(
                                        value = url,
                                        onValueChange = { url = it },
                                        label = { Text("Adresa webu (https://…)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text("Typ obsahu", color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "NOVEL" to "Novely", "COMIC" to "Komiksy").forEach { (type, label) ->
                                            val sel = selectedContentType == type
                                            Box(
                                                modifier = Modifier
                                                    .border(1.dp, if (sel) GlowViolet else TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                                    .background(if (sel) Violet.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp))
                                                    .clickable { selectedContentType = type }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                            ) {
                                                Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                        Text(
                                            if (showAdvanced) "Skrýt pokročilé selektory" else "Pokročilé selektory (volitelné)",
                                            color = Cyan,
                                        )
                                    }
                                    if (showAdvanced) {
                                        Text(
                                            text = "Vyplň jen pokud výchozí Madara selektory na tomto webu nesedí (téma bylo upravené). Prázdné pole = použije se výchozí.",
                                            color = TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        SelectorField("Seznam položek (list)", listItemSel) { listItemSel = it }
                                        SelectorField("Odkaz s názvem (title link)", titleLinkSel) { titleLinkSel = it }
                                        SelectorField("Popis (description)", descriptionSel) { descriptionSel = it }
                                        SelectorField("Stav vydávání (status)", statusSel) { statusSel = it }
                                        SelectorField("Seznam kapitol (chapter list)", chapterListSel) { chapterListSel = it }
                                        SelectorField("Obrázky stránky (page image)", pageImageSel) { pageImageSel = it }
                                    }

                                    Spacer(Modifier.height(8.dp))
                                    OutlinedButton(
                                        onClick = {
                                            viewModel.testCustomSource(
                                                baseUrl = url.trim(),
                                                listItemSelector = listItemSel.trim().ifBlank { null },
                                                titleLinkSelector = titleLinkSel.trim().ifBlank { null },
                                                descriptionSelector = descriptionSel.trim().ifBlank { null },
                                                statusSelector = statusSel.trim().ifBlank { null },
                                                chapterListSelector = chapterListSel.trim().ifBlank { null },
                                                pageImageSelector = pageImageSel.trim().ifBlank { null },
                                            )
                                        },
                                        enabled = url.isNotBlank() && testState != SourceTestState.Testing,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                                    ) {
                                        if (testState == SourceTestState.Testing) {
                                            JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), size = 16.dp, strokeWidth = 2.dp)
                                        }
                                        Text("Otestovat připojení")
                                    }
                                    when (val s = testState) {
                                        is SourceTestState.Success -> Text(
                                            "✓ Nalezeno ${s.count} položek",
                                            color = Color(0xFF81C784),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                        is SourceTestState.Failure -> Text(
                                            "✗ ${s.message}",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                        else -> Unit
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (name.isNotBlank() && url.isNotBlank()) {
                                            viewModel.addCustomSource(
                                                name = name.trim(),
                                                baseUrl = url.trim(),
                                                listItemSelector = listItemSel.trim().ifBlank { null },
                                                titleLinkSelector = titleLinkSel.trim().ifBlank { null },
                                                descriptionSelector = descriptionSel.trim().ifBlank { null },
                                                statusSelector = statusSel.trim().ifBlank { null },
                                                chapterListSelector = chapterListSel.trim().ifBlank { null },
                                                pageImageSelector = pageImageSel.trim().ifBlank { null },
                                                contentType = selectedContentType,
                                            )
                                            showAddDialog = false
                                        }
                                    },
                                ) { Text("Přidat", color = GlowViolet) }
                            },
                            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Zrušit", color = TextSecondary) } },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Stahování / Auto-mazání ───────────────────────────────────
                SettingsSection(title = "Stahování") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = downloadOnlyWifi, onValueChange = { viewModel.setDownloadOnlyWifi(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Stahovat pouze přes Wi-Fi", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Mobilní data se nepoužijí ke stahování kapitol", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = downloadOnlyWifi,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = autoDeleteRead, onValueChange = { viewModel.setAutoDeleteRead(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-smazat přečtené", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Stažené kapitoly se smažou po přečtení", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = autoDeleteRead,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                    if (autoDeleteRead) {
                        Text(
                            text = "Prodleva před smazáním",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        )
                        listOf(0 to "Okamžitě", 1 to "1 den", 3 to "3 dny", 7 to "7 dní").forEach { (days, label) ->
                            GlassRadioRow(
                                label = label,
                                selected = autoDeleteDelayDays == days,
                                onClick = { viewModel.setAutoDeleteDelayDays(days) },
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = saveAsCbz, onValueChange = { viewModel.setSaveAsCbz(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Exportovat jako CBZ", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Po stažení vytvoří .cbz soubor vedle složky s obrázky", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = saveAsCbz,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Text(
                        text = "Paralelní stahování",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                    listOf(1 to "1 (postupně)", 2 to "2 naráz", 3 to "3 naráz (výchozí)", 5 to "5 naráz").forEach { (n, label) ->
                        GlassRadioRow(
                            label = label,
                            selected = parallelDownloads == n,
                            onClick = { viewModel.setParallelDownloads(n) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Notifikace ────────────────────────────────────────────────
                SettingsSection(title = "Notifikace") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = notifyNewChapters, onValueChange = { viewModel.setNotifyNewChapters(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Nové kapitoly", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Upozornění při automatické kontrole nových kapitol", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = notifyNewChapters,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = notifyDownloads, onValueChange = { viewModel.setNotifyDownloads(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Stahování", color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text("Upozornění při dokončení nebo selhání stahování kapitoly", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = notifyDownloads,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Knihovna ──────────────────────────────────────────────────
                SettingsSection(title = "Knihovna") {
                    Text(
                        text = "Sloupce v mřížce",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                    listOf(2 to "2 sloupce", 3 to "3 sloupce (výchozí)", 4 to "4 sloupce").forEach { (n, label) ->
                        GlassRadioRow(
                            label = label,
                            selected = libraryGridColumns == n,
                            onClick = { viewModel.setLibraryGridColumns(n) },
                        )
                    }

                    if (allCategories.isNotEmpty()) {
                        Text(
                            text = "Výchozí kategorie pro nová manga",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                        GlassRadioRow(
                            label = "Žádná (nepřiřazovat)",
                            selected = defaultCategoryId == null,
                            onClick = { viewModel.setDefaultCategoryId(null) },
                        )
                        allCategories.forEach { cat ->
                            GlassRadioRow(
                                label = cat.name,
                                selected = defaultCategoryId == cat.id,
                                onClick = { viewModel.setDefaultCategoryId(cat.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Cache překladů") {
                    Text(
                        text = "Uložené překlady: $cacheCount stránek",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    OutlinedButton(
                        onClick = { viewModel.clearTranslationCache() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(TablerIcons.Trash, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Vymazat cache")
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Cache obrázků") {
                    val imgContext = androidx.compose.ui.platform.LocalContext.current
                    var diskCacheSize by remember { mutableStateOf(0L) }
                    LaunchedEffect(Unit) {
                        diskCacheSize = coil.Coil.imageLoader(imgContext).diskCache?.size ?: 0L
                    }
                    val sizeStr = when {
                        diskCacheSize >= 1_048_576L -> "%.1f MB".format(diskCacheSize / 1_048_576.0)
                        diskCacheSize >= 1_024L -> "%.0f KB".format(diskCacheSize / 1_024.0)
                        else -> "$diskCacheSize B"
                    }
                    Text(
                        text = "Disk cache: $sizeStr",
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    OutlinedButton(
                        onClick = {
                            coil.Coil.imageLoader(imgContext).diskCache?.clear()
                            diskCacheSize = 0L
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(TablerIcons.Trash, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Vymazat cache obrázků")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── MAL OAuth ────────────────────────────────────────────────
                SettingsSection(title = "MyAnimeList") {
                    if (malIsLoggedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Přihlášen", color = Cyan, fontWeight = FontWeight.Medium)
                                if (malUsername.isNotBlank()) Text(malUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.malLogout() }) {
                                Text("Odhlásit", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        Text(
                            "Přihlas se přes MAL OAuth pro synchronizaci sledování.",
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                        val context = androidx.compose.ui.platform.LocalContext.current
                        OutlinedButton(
                            onClick = {
                                viewModel.startMalOAuth { uri ->
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("Přihlásit se přes MyAnimeList")
                        }
                    }
                    Text(
                        "MAL Client ID: přidej do local.properties jako MAL_CLIENT_ID=xxx",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                Spacer(Modifier.height(12.dp))

                // ── Kitsu ─────────────────────────────────────────────────────
                SettingsSection(title = "Kitsu") {
                    if (kitsuIsLoggedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Přihlášen", color = Cyan, fontWeight = FontWeight.Medium)
                                if (kitsuUsername.isNotBlank()) Text(kitsuUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.kitsuLogout() }) {
                                Text("Odhlásit", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        var kitsuEmail by remember { mutableStateOf("") }
                        var kitsuPass  by remember { mutableStateOf("") }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(
                                "Přihlas se ke Kitsu pro párování mangy.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            TextField(
                                value = kitsuEmail,
                                onValueChange = { kitsuEmail = it; viewModel.clearKitsuLoginError() },
                                label = { Text("E-mail") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                            TextField(
                                value = kitsuPass,
                                onValueChange = { kitsuPass = it; viewModel.clearKitsuLoginError() },
                                label = { Text("Heslo") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            )
                            if (kitsuLoginError != null) {
                                Text(kitsuLoginError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.kitsuLogin(kitsuEmail, kitsuPass) },
                                enabled = !kitsuLoginLoading && kitsuEmail.isNotBlank() && kitsuPass.isNotBlank(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (kitsuLoginLoading) JiyuLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                                else Text("Přihlásit se ke Kitsu")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── MangaUpdates ──────────────────────────────────────────────
                SettingsSection(title = "MangaUpdates") {
                    if (muIsLoggedIn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                Text("Přihlášen", color = Cyan, fontWeight = FontWeight.Medium)
                                if (muUsername.isNotBlank()) Text(muUsername, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { viewModel.muLogout() }) {
                                Text("Odhlásit", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    } else {
                        var muUser by remember { mutableStateOf("") }
                        var muPass by remember { mutableStateOf("") }
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                            Text(
                                "Přihlas se k MangaUpdates pro párování mangy.",
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                            TextField(
                                value = muUser,
                                onValueChange = { muUser = it; viewModel.clearMuLoginError() },
                                label = { Text("Uživatelské jméno") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            )
                            TextField(
                                value = muPass,
                                onValueChange = { muPass = it; viewModel.clearMuLoginError() },
                                label = { Text("Heslo") },
                                singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            )
                            if (muLoginError != null) {
                                Text(muLoginError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.muLogin(muUser, muPass) },
                                enabled = !muLoginLoading && muUser.isNotBlank() && muPass.isNotBlank(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                if (muLoginLoading) JiyuLoadingIndicator(size = 18.dp, strokeWidth = 2.dp)
                                else Text("Přihlásit se k MangaUpdates")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Komunita & Cíle ───────────────────────────────────────────
                SettingsSection(title = "Čtení") {
                    OutlinedButton(
                        onClick = onOpenGoals,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        Text("Cíle čtení & Série dnů")
                    }
                    OutlinedButton(
                        onClick = onOpenCommunity,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        Text("Community manga listy")
                    }
                    OutlinedButton(
                        onClick = onOpenDuplicates,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        Text("Duplikát detektor")
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── O aplikaci / kontrola aktualizací ────────────────────────────
                SettingsSection(title = "O aplikaci") {
                    val updateCheckLoading by viewModel.updateCheckLoading.collectAsState()
                    val updateInfo by viewModel.updateInfo.collectAsState()
                    val updateCheckedNone by viewModel.updateCheckedAndNoneFound.collectAsState()
                    val updateCtx = androidx.compose.ui.platform.LocalContext.current

                    Text(
                        text = "Verze ${viewModel.appVersion}",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    if (updateInfo != null) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                "Nová verze ${updateInfo!!.version} je k dispozici",
                                color = GlowViolet,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                            )
                            if (updateInfo!!.notes.isNotBlank()) {
                                Text(
                                    updateInfo!!.notes,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 4,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                updateCtx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(updateInfo!!.releaseUrl))
                                )
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                        ) { Text("Otevřít stránku vydání") }
                    } else if (updateCheckedNone) {
                        Text(
                            "Máš nejnovější verzi",
                            color = TextSecondary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }

                    OutlinedButton(
                        onClick = { viewModel.checkForUpdate() },
                        enabled = !updateCheckLoading,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        if (updateCheckLoading) JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), size = 16.dp, strokeWidth = 2.dp)
                        Text("Zkontrolovat aktualizace")
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}

@Composable
private fun SelectorField(label: String, value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    )
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = Violet,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
    ) {
        content()
    }
}

private fun buildZoneGridSummary(grid: TapZoneGrid): String {
    fun short(a: TapZoneAction) = when (a) {
        TapZoneAction.NONE         -> "—"
        TapZoneAction.SHOW_PANEL   -> "Panel"
        TapZoneAction.PREV_PAGE    -> "◀"
        TapZoneAction.NEXT_PAGE    -> "▶"
        TapZoneAction.PREV_CHAPTER -> "⏮Kap"
        TapZoneAction.NEXT_CHAPTER -> "Kap⏭"
    }
    return (0..2).joinToString("  ") { col ->
        (0..2).joinToString("/") { row -> short(grid[row, col]) }
    }
}

@Composable
private fun GlassRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = Violet, unselectedColor = TextSecondary),
        )
        Text(
            text = label,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
