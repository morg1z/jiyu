package com.haise.jiyu.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val language          by viewModel.targetLanguage.collectAsState()
    val theme             by viewModel.theme.collectAsState()
    val direction         by viewModel.readingDirection.collectAsState()
    val readingMode       by viewModel.readingMode.collectAsState()
    val cacheCount        by viewModel.cacheCount.collectAsState()
    val backupState       by viewModel.backupState.collectAsState()
    val downloadedCount   by viewModel.downloadedCount.collectAsState()
    val updateInterval    by viewModel.updateIntervalHours.collectAsState()
    val customSources     by viewModel.customSources.collectAsState()
    val tapZonesEnabled   by viewModel.tapZonesEnabled.collectAsState()
    val readerTextScale   by viewModel.readerTextScale.collectAsState()
    val doublePageSpread  by viewModel.doublePageSpread.collectAsState()
    val autoDeleteRead    by viewModel.autoDeleteRead.collectAsState()
    val autoDeleteDelayDays by viewModel.autoDeleteDelayDays.collectAsState()
    val fullscreenEnabled by viewModel.fullscreenEnabled.collectAsState()
    val readerTheme       by viewModel.readerTheme.collectAsState()
    val oledMode          by viewModel.oledMode.collectAsState()
    val tachyImportResult by viewModel.tachyImportResult.collectAsState()
    val tachyImportInProgress by viewModel.tachyImportInProgress.collectAsState()

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

    // Snackbar pro výsledek zálohy
    LaunchedEffect(backupState) {
        when (val s = backupState) {
            is BackupUiState.Success -> { snackbarHost.showSnackbar(s.message); viewModel.clearBackupState() }
            is BackupUiState.Error   -> { snackbarHost.showSnackbar("Chyba: ${s.message}"); viewModel.clearBackupState() }
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
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
                SettingsSection(title = "Překlad") {
                    LANGUAGES.forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = language == value, onClick = { viewModel.setTargetLanguage(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Téma") {
                    listOf(ThemeOption.SYSTEM to "Systémové", ThemeOption.DARK to "Tmavé", ThemeOption.LIGHT to "Světlé")
                        .forEach { (value, label) ->
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
                            if (isWorking) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = GlowViolet)
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
                            CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 8.dp), strokeWidth = 2.dp, color = GlowViolet)
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
                            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
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
                                Icon(Icons.Filled.Delete, contentDescription = "Smazat", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Přidat zdroj")
                    }

                    if (showAddDialog) {
                        var name by remember { mutableStateOf("") }
                        var url by remember { mutableStateOf("") }
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
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp).padding(end = 8.dp), strokeWidth = 2.dp, color = Cyan)
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
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Vymazat cache")
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
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) {
                        Text("Community manga listy")
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
