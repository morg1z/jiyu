package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient

@Composable
fun UpdateCheckSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val updateInterval   by viewModel.updateIntervalHours.collectAsState()
    val notifyNewChapters by viewModel.notifyNewChapters.collectAsState()
    val notifyDownloads   by viewModel.notifyDownloads.collectAsState()

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_update_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(R.string.settings_update_interval_section)) {
                    listOf(
                        6L to stringResource(R.string.settings_update_interval_6h),
                        12L to stringResource(R.string.settings_update_interval_12h),
                        24L to stringResource(R.string.settings_update_interval_1d),
                        48L to stringResource(R.string.settings_update_interval_2d),
                    ).forEach { (hours, label) ->
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

                SettingsSection(title = stringResource(R.string.settings_update_notifications_section)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = notifyNewChapters, onValueChange = { viewModel.setNotifyNewChapters(it) }, role = Role.Switch)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_update_notify_new_chapters), color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.settings_update_notify_new_chapters_desc), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
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
                            Text(stringResource(R.string.settings_update_notify_downloads), color = TextPrimary, fontWeight = FontWeight.Medium)
                            Text(stringResource(R.string.settings_update_notify_downloads_desc), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = notifyDownloads,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Violet, checkedTrackColor = GlowViolet.copy(alpha = 0.5f)),
                        )
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
