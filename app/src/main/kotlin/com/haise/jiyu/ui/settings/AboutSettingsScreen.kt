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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    val updateCheckLoading by viewModel.updateCheckLoading.collectAsState()
    val updateInfo by viewModel.updateInfo.collectAsState()
    val updateCheckedNone by viewModel.updateCheckedAndNoneFound.collectAsState()
    val downloadState by viewModel.updateDownloadState.collectAsState()
    val updateCtx = LocalContext.current

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
                                UpdateDownloadState.Failed -> {
                                    Text(
                                        stringResource(R.string.settings_about_download_failed),
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

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
