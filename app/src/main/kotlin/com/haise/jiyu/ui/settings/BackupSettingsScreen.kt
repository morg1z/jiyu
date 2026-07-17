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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
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
    val errorPrefixTemplate = stringResource(R.string.settings_backup_error_prefix)

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
            is BackupUiState.Error   -> { snackbarHost.showSnackbar(errorPrefixTemplate.format(s.message)); viewModel.clearBackupState() }
            else -> Unit
        }
    }

    LaunchedEffect(settingsBackupState) {
        when (val s = settingsBackupState) {
            is BackupUiState.Success -> { snackbarHost.showSnackbar(s.message); viewModel.clearSettingsBackupState() }
            is BackupUiState.Error   -> { snackbarHost.showSnackbar(errorPrefixTemplate.format(s.message)); viewModel.clearSettingsBackupState() }
            else -> Unit
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_backup_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(R.string.settings_backup_library_section)) {
                    val isWorking = backupState is BackupUiState.Working

                    Text(
                        text = stringResource(R.string.settings_backup_library_export_desc),
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
                            Text(stringResource(R.string.settings_backup_auto_title), color = TextPrimary, fontSize = 14.sp)
                            Text(stringResource(R.string.settings_backup_auto_desc), color = TextSecondary, fontSize = 11.sp)
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
                                    stringResource(R.string.settings_backup_folder_target, Uri.parse(backupFolderUri).lastPathSegment ?: backupFolderUri!!)
                                else
                                    stringResource(R.string.settings_backup_folder_target_default),
                                color = TextSecondary,
                                fontSize = 11.sp,
                            )
                            Text(
                                stringResource(R.string.settings_backup_folder_hint),
                                color = TextSecondary.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { backupFolderPicker.launch(null) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                                    modifier = Modifier.weight(1f),
                                ) { Text(stringResource(R.string.settings_backup_pick_folder), fontSize = 12.sp) }
                                if (backupFolderUri != null) {
                                    OutlinedButton(
                                        onClick = { viewModel.setBackupFolderUri(null) },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    ) { Text(stringResource(R.string.settings_backup_reset_folder), fontSize = 12.sp) }
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
                            Text(stringResource(R.string.settings_backup_export_button))
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            enabled = !isWorking,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                        ) {
                            Text(stringResource(R.string.settings_backup_import_button))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(R.string.settings_backup_settings_section)) {
                    val isSettingsWorking = settingsBackupState is BackupUiState.Working
                    Text(
                        text = stringResource(R.string.settings_backup_settings_export_desc),
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
                            Text(stringResource(R.string.settings_backup_export_button))
                        }
                        OutlinedButton(
                            onClick = { settingsImportLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            enabled = !isSettingsWorking,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                        ) {
                            Text(stringResource(R.string.settings_backup_import_button))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(12.dp))

                // ── Tachiyomi/Mihon import ────────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_backup_mihon_section)) {
                    Text(
                        text = stringResource(R.string.settings_backup_mihon_desc),
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
                        Text(if (tachyImportInProgress) stringResource(R.string.settings_backup_importing) else stringResource(R.string.settings_backup_pick_mihon_file))
                    }
                    Spacer(Modifier.height(4.dp))
                }

                // Dialog výsledku Tachiyomi importu
                tachyImportResult?.let { result ->
                    AlertDialog(
                        onDismissRequest = { viewModel.clearTachyImportResult() },
                        containerColor = NightBlue,
                        title = { Text(stringResource(R.string.settings_backup_import_done_title), color = TextPrimary) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.settings_backup_import_done_imported, result.imported), color = Cyan)
                                Text(stringResource(R.string.settings_backup_import_done_skipped, result.skipped), color = TextSecondary)
                                if (result.errors.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(stringResource(R.string.settings_backup_import_done_errors_label), color = Color(0xFFEF4444))
                                    result.errors.take(3).forEach {
                                        Text("• $it", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { viewModel.clearTachyImportResult() }) {
                                Text(stringResource(R.string.common_ok), color = Violet)
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
