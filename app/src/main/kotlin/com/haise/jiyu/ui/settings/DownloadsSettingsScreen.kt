package com.haise.jiyu.ui.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.Trash

@Composable
fun DownloadsSettingsScreen(
    onBack: () -> Unit,
    onOpenDownloadManager: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val downloadFolderUri  by viewModel.downloadFolderUri.collectAsState()
    val downloadOnlyWifi   by viewModel.downloadOnlyWifi.collectAsState()
    val autoDeleteRead     by viewModel.autoDeleteRead.collectAsState()
    val autoDeleteDelayDays by viewModel.autoDeleteDelayDays.collectAsState()
    val saveAsCbz          by viewModel.saveAsCbz.collectAsState()
    val parallelDownloads  by viewModel.parallelDownloads.collectAsState()
    val downloadedCount    by viewModel.downloadedCount.collectAsState()

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(com.haise.jiyu.R.string.settings_main_downloads_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_download_folder)) {
                    val ctx = LocalContext.current
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
                                stringResource(com.haise.jiyu.R.string.settings_download_folder_default),
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
                            ) { Text(stringResource(com.haise.jiyu.R.string.settings_download_folder_change), fontSize = 13.sp) }
                            if (downloadFolderUri != null) {
                                OutlinedButton(
                                    onClick = { viewModel.setDownloadFolderUri(null) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                                ) { Text(stringResource(com.haise.jiyu.R.string.settings_downloads_folder_reset)) }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_downloads_section_title)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = downloadOnlyWifi, onValueChange = { viewModel.setDownloadOnlyWifi(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(com.haise.jiyu.R.string.settings_wifi_only), color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(stringResource(com.haise.jiyu.R.string.settings_wifi_only_desc), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(com.haise.jiyu.R.string.settings_downloads_auto_delete_title), color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(stringResource(com.haise.jiyu.R.string.settings_downloads_auto_delete_desc), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = autoDeleteRead,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                    if (autoDeleteRead) {
                        Text(
                            text = stringResource(com.haise.jiyu.R.string.settings_downloads_auto_delete_delay_title),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        )
                        listOf(
                            0 to stringResource(com.haise.jiyu.R.string.settings_downloads_delay_immediately),
                            1 to stringResource(com.haise.jiyu.R.string.settings_downloads_delay_1_day),
                            3 to stringResource(com.haise.jiyu.R.string.settings_downloads_delay_3_days),
                            7 to stringResource(com.haise.jiyu.R.string.settings_downloads_delay_7_days),
                        ).forEach { (days, label) ->
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
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(com.haise.jiyu.R.string.settings_downloads_export_cbz_title), color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(stringResource(com.haise.jiyu.R.string.settings_downloads_export_cbz_desc), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = saveAsCbz,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }

                    Text(
                        text = stringResource(com.haise.jiyu.R.string.settings_downloads_parallel_title),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                    listOf(
                        1 to stringResource(com.haise.jiyu.R.string.settings_downloads_parallel_1),
                        2 to stringResource(com.haise.jiyu.R.string.settings_downloads_parallel_2),
                        3 to stringResource(com.haise.jiyu.R.string.settings_downloads_parallel_3_default),
                        5 to stringResource(com.haise.jiyu.R.string.settings_downloads_parallel_5),
                    ).forEach { (n, label) ->
                        GlassRadioRow(
                            label = label,
                            selected = parallelDownloads == n,
                            onClick = { viewModel.setParallelDownloads(n) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_downloads_chapters_section_title)) {
                    var showDeleteConfirm by remember { mutableStateOf(false) }
                    Text(
                        text = stringResource(com.haise.jiyu.R.string.settings_downloads_downloaded_count, downloadedCount),
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        OutlinedButton(
                            onClick = onOpenDownloadManager,
                            modifier = Modifier.weight(1f).padding(end = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                        ) { Text(stringResource(com.haise.jiyu.R.string.settings_downloads_manage_button)) }
                        OutlinedButton(
                            onClick = { showDeleteConfirm = true },
                            enabled = downloadedCount > 0,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        ) {
                            Icon(TablerIcons.Trash, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                            Text(stringResource(com.haise.jiyu.R.string.settings_downloads_delete_all_button))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    if (showDeleteConfirm) {
                        AlertDialog(
                            onDismissRequest = { showDeleteConfirm = false },
                            containerColor = Color(0xFF111B35),
                            title = { Text(stringResource(com.haise.jiyu.R.string.settings_downloads_delete_all_dialog_title), color = Color.White, fontWeight = FontWeight.Bold) },
                            text = { Text(stringResource(com.haise.jiyu.R.string.settings_downloads_delete_all_dialog_text, downloadedCount), color = Color(0xFFB0BEC5)) },
                            confirmButton = {
                                TextButton(onClick = { viewModel.deleteAllDownloads(); showDeleteConfirm = false }) {
                                    Text(stringResource(com.haise.jiyu.R.string.common_delete), color = MaterialTheme.colorScheme.error)
                                }
                            },
                            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(com.haise.jiyu.R.string.common_cancel), color = Color(0xFFB0BEC5)) } },
                        )
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
