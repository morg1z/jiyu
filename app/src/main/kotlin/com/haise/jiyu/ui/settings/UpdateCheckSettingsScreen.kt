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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
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
            SettingsSubScreenHeader(title = "Zkontrolovat nové kapitoly", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
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

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
