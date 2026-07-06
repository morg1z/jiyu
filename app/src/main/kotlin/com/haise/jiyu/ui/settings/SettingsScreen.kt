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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
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

    val snackbarHost = remember { SnackbarHostState() }

    // Exportní launcher — uživatel vybere kam uložit soubor
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportBackup(it) } }

    // Importní launcher — uživatel vybere zálohu
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importBackup(it) } }

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
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showAddDialog = false },
                            containerColor = androidx.compose.ui.graphics.Color(0xFF111B35),
                            title = { Text("Přidat Madara zdroj", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Column {
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
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        if (name.isNotBlank() && url.isNotBlank()) {
                                            viewModel.addCustomSource(name.trim(), url.trim())
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

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
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
