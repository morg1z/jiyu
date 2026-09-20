package com.haise.jiyu.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import compose.icons.TablerIcons
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Trash
import kotlinx.coroutines.launch

/**
 * Náhradní domény zdrojů ("zrcadla"): když web změní adresu, uživatel zadá novou a zdroj ji použije bez nové
 * verze appky (viz `DomainOverrideInterceptor`).
 */
@Composable
fun SourceMirrorSection(viewModel: SettingsViewModel) {
    val rows by viewModel.sourceDomainRows.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    SettingsSection(title = stringResource(R.string.settings_sources_mirror_section_title)) {
        Text(
            text = stringResource(R.string.settings_sources_mirror_description),
            color = TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        rows.filter { it.override != null }.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(row.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                    Text("${row.originalHost.orEmpty()} → ${row.override}", color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                IconButton(onClick = { viewModel.clearSourceDomain(row.id) }) {
                    Icon(TablerIcons.Trash, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        OutlinedButton(
            onClick = { showDialog = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
        ) {
            Icon(TablerIcons.Plus, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text(stringResource(R.string.settings_sources_mirror_add_button))
        }
    }

    if (showDialog) {
        var filter by remember { mutableStateOf("") }
        var selectedId by remember { mutableStateOf<String?>(null) }
        var host by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val matches = rows.filter { filter.isBlank() || it.name.contains(filter, ignoreCase = true) }.take(8)
        val selected = rows.firstOrNull { it.id == selectedId }

        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_sources_mirror_dialog_title), color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp)) {
                    if (selected == null) {
                        OutlinedTextField(
                            value = filter,
                            onValueChange = { filter = it },
                            label = { Text(stringResource(R.string.settings_sources_mirror_field_source)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        matches.forEach { row ->
                            Text(
                                row.name,
                                color = TextPrimary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedId = row.id; host = row.override.orEmpty() }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    } else {
                        Text(selected.name, color = TextPrimary, fontWeight = FontWeight.Medium)
                        Text(selected.originalHost.orEmpty(), color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it; invalid = false },
                            label = { Text(stringResource(R.string.settings_sources_mirror_field_host)) },
                            singleLine = true,
                            isError = invalid,
                            supportingText = if (invalid) ({ Text(stringResource(R.string.settings_sources_mirror_invalid)) }) else null,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (selected != null) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                if (viewModel.setSourceDomain(selected.id, host)) showDialog = false else invalid = true
                            }
                        },
                    ) { Text(stringResource(R.string.common_save), color = GlowViolet) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = Color(0xFFB0BEC5))
                }
            },
        )
    }
}
