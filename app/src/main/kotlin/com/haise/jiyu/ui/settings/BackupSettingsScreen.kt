package com.haise.jiyu.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import java.time.LocalDate

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val backupState         by viewModel.backupState.collectAsState()
    val settingsBackupState by viewModel.settingsBackupState.collectAsState()
    val autoBackupEnabled   by viewModel.autoBackupEnabled.collectAsState()
    val backupFolderUri     by viewModel.backupFolderUri.collectAsState()
    val tachyImportResult      by viewModel.tachyImportResult.collectAsState()
    val tachyImportInProgress  by viewModel.tachyImportInProgress.collectAsState()

    val snackbarHost = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importBackup(it) } }

    val tachyImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importTachiyomiBackup(it) } }

    val settingsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? -> uri?.let { viewModel.exportSettings(it) } }
    val settingsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importSettings(it) } }

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
            SettingsSubScreenHeader(title = "Zálohovat a obnovit", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
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

                    run {
                        val backupFolderCtx = LocalContext.current
                        val backupFolderPicker = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocumentTree()
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
                    AlertDialog(
                        onDismissRequest = { viewModel.clearTachyImportResult() },
                        containerColor = NightBlue,
                        title = { Text("Import dokončen", color = TextPrimary) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
