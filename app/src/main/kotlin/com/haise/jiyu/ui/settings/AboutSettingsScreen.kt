package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.update.UpdateDownloadState

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val updateCheckLoading by viewModel.updateCheckLoading.collectAsStateWithLifecycle()
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val updateCheckedNone by viewModel.updateCheckedAndNoneFound.collectAsStateWithLifecycle()
    val downloadState by viewModel.updateDownloadState.collectAsStateWithLifecycle()
    val updateCtx = LocalContext.current
    var showReportDialog by remember { mutableStateOf(false) }
    val isAdult by viewModel.isAdult.collectAsStateWithLifecycle()
    val crashReporting by viewModel.crashReporting.collectAsStateWithLifecycle()

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_about_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(R.string.settings_about_title)) {
                    Text(
                        text = stringResource(R.string.settings_about_version, viewModel.appVersion),
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )

                    if (updateInfo != null) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            Text(
                                stringResource(R.string.settings_about_new_version_available, updateInfo!!.version),
                                color = GlowViolet,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                                fontSize = 13.sp,
                            )
                            if (updateInfo!!.notes.isNotBlank()) {
                                Text(
                                    updateInfo!!.notes,
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    maxLines = 4,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                        if (updateInfo!!.apkUrl != null) {
                            when (val state = downloadState) {
                                is UpdateDownloadState.Downloading -> {
                                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(stringResource(R.string.settings_about_downloading), color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
                                        if (state.progress >= 0) {
                                            LinearProgressIndicator(
                                                progress = { state.progress / 100f },
                                                modifier = Modifier.fillMaxWidth(),
                                                color = GlowViolet,
                                            )
                                            Text("${state.progress} %", color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                                        } else {
                                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = GlowViolet)
                                        }
                                    }
                                }
                                UpdateDownloadState.ReadyToInstall -> {
                                    Text(
                                        stringResource(R.string.settings_about_ready_to_install),
                                        color = GlowViolet,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                    )
                                }
                                is UpdateDownloadState.Failed -> {
                                    // DownloadManager.ERROR_INSUFFICIENT_SPACE = 1009 - jediny
                                    // castý duvod, u ktereho ma smysl rict uzivateli KONKRETNE
                                    // co s tim (misto obecneho "nepovedlo se"). -1 je nas vlastni
                                    // sentinel (viz ApkUpdateInstaller.REASON_INTEGRITY_CHECK_FAILED)
                                    // pro neshodu SHA-256 - nekoliduje se skutecnymi DownloadManager
                                    // kody (ty jsou vsechny kladne).
                                    val message = when (state.reason) {
                                        1009 -> stringResource(R.string.settings_about_download_failed_space)
                                        com.haise.jiyu.update.ApkUpdateInstaller.REASON_INTEGRITY_CHECK_FAILED ->
                                            stringResource(R.string.settings_about_download_failed_integrity)
                                        else -> stringResource(R.string.settings_about_download_failed)
                                    }
                                    Text(
                                        message,
                                        color = MaterialTheme.colorScheme.error,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    )
                                    Button(
                                        onClick = { viewModel.downloadUpdate() },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = GlowViolet),
                                    ) { Text(stringResource(R.string.settings_about_download_install)) }
                                }
                                UpdateDownloadState.Idle -> {
                                    Button(
                                        onClick = { viewModel.downloadUpdate() },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = GlowViolet),
                                    ) { Text(stringResource(R.string.settings_about_download_install)) }
                                }
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
                        ) { Text(stringResource(R.string.settings_about_open_release_page)) }
                    } else if (updateCheckedNone) {
                        Text(
                            stringResource(R.string.settings_about_up_to_date),
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
                        Text(stringResource(R.string.settings_about_check_updates))
                    }
                }

                // ── Soukromí ──────────────────────────────────────────────────
                // Obojí se ptá už v onboardingu; tady se to dá kdykoli změnit. Souhlas, který
                // jde dát jen jednou při instalaci a nikdy odvolat, by byl k ničemu.
                SettingsSection(title = stringResource(R.string.settings_privacy_title)) {
                    PrivacyToggle(
                        title = stringResource(R.string.settings_privacy_adult),
                        description = stringResource(R.string.settings_privacy_adult_desc),
                        checked = isAdult,
                        onCheckedChange = viewModel::setIsAdult,
                    )
                    PrivacyToggle(
                        title = stringResource(R.string.settings_privacy_crash),
                        description = stringResource(R.string.settings_privacy_crash_desc),
                        checked = crashReporting,
                        onCheckedChange = viewModel::setCrashReporting,
                    )
                }

                SettingsSection(title = stringResource(R.string.settings_report_section_title)) {
                    OutlinedButton(
                        onClick = { showReportDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet),
                    ) { Text(stringResource(R.string.settings_report_button)) }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }

    if (showReportDialog) {
        val chooserTitle = stringResource(R.string.browse_report_chooser_title)
        val problemCrashLabel = stringResource(R.string.settings_report_problem_crash)
        val problemTranslationLabel = stringResource(R.string.settings_report_problem_translation)
        val problemDownloadLabel = stringResource(R.string.settings_report_problem_download)
        val problemOtherLabel = stringResource(R.string.settings_report_problem_other)
        com.haise.jiyu.ui.components.ReportDialog(
            title = stringResource(R.string.settings_report_title),
            problems = listOf(
                "crash" to problemCrashLabel,
                "translation" to problemTranslationLabel,
                "download" to problemDownloadLabel,
                com.haise.jiyu.ui.components.REPORT_PROBLEM_OTHER_KEY to problemOtherLabel,
            ),
            onDismiss = { showReportDialog = false },
            onSend = { problemKey, details ->
                val problemLabel = when (problemKey) {
                    "crash" -> problemCrashLabel
                    "translation" -> problemTranslationLabel
                    "download" -> problemDownloadLabel
                    else -> problemOtherLabel
                }
                val body = buildString {
                    append("Verze appky: ${viewModel.appVersion}\n")
                    append("Problém: $problemLabel\n")
                    if (details.isNotBlank()) append("\nPopis:\n$details")
                }
                val intent = com.haise.jiyu.ui.components.buildReportEmailIntent("[Jiyu] Nahlášení problému", body)
                updateCtx.startActivity(android.content.Intent.createChooser(intent, chooserTitle))
                showReportDialog = false
            },
        )
    }
}

/**
 * Radek s prepinacem pro sekci Soukromi.
 *
 * Cely radek je klikatelny (`toggleable` + `Role.Switch`), Switch sam ma `onCheckedChange = null` -
 * jinak by na nem TalkBack ohlasil dva nezavisle ovladatelne prvky nad sebou. Stejny vzor
 * pouziva prepinac zdroju pro dospele v SourcesSettingsScreen.
 */
@Composable
private fun PrivacyToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = TextPrimary, fontSize = 14.sp)
            Text(description, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = GlowViolet,
                checkedTrackColor = GlowViolet.copy(alpha = 0.5f),
            ),
        )
    }
}
