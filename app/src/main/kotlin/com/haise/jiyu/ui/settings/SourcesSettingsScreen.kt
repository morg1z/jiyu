package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.R
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Trash

@Composable
fun SourcesSettingsScreen(
    onBack: () -> Unit,
    onOpenSourceCatalog: () -> Unit,
    onOpenCustomCss: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val customSources by viewModel.customSources.collectAsState()

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(R.string.settings_main_sources_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(R.string.settings_sources_section_title)) {
                    OutlinedButton(
                        onClick = onOpenSourceCatalog,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text(stringResource(R.string.settings_sources_catalog_button, viewModel.getCatalog().size))
                    }
                    OutlinedButton(
                        onClick = onOpenCustomCss,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text(stringResource(R.string.settings_sources_custom_css_button))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Vlastní zdroje (Madara) ───────────────────────────────────
                SettingsSection(title = stringResource(R.string.settings_sources_madara_section_title)) {
                    var showAddDialog by remember { mutableStateOf(false) }

                    Text(
                        text = stringResource(R.string.settings_sources_madara_description),
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
                                Icon(TablerIcons.Trash, contentDescription = stringResource(R.string.common_delete), tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                    ) {
                        Icon(TablerIcons.Plus, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.settings_sources_add_button))
                    }

                    if (showAddDialog) {
                        var name by remember { mutableStateOf("") }
                        var url by remember { mutableStateOf("") }
                        var selectedContentType by remember { mutableStateOf("MANGA") }
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
                            containerColor = Color(0xFF111B35),
                            title = { Text(stringResource(R.string.settings_sources_add_dialog_title), color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 420.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    TextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        label = { Text(stringResource(R.string.settings_sources_field_name)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    TextField(
                                        value = url,
                                        onValueChange = { url = it },
                                        label = { Text(stringResource(R.string.settings_sources_field_url)) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(stringResource(R.string.settings_sources_content_type_label), color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf(
                                            "MANGA" to stringResource(R.string.settings_sources_type_manga),
                                            "MANHWA" to stringResource(R.string.settings_sources_type_manhwa),
                                            "MANHUA" to stringResource(R.string.settings_sources_type_manhua),
                                            "NOVEL" to stringResource(R.string.settings_sources_type_novel),
                                            "COMIC" to stringResource(R.string.settings_sources_type_comic),
                                        ).forEach { (type, label) ->
                                            val sel = selectedContentType == type
                                            Box(
                                                modifier = Modifier
                                                    .border(1.dp, if (sel) GlowViolet else TextSecondary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                                    .background(if (sel) Violet.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp))
                                                    .clickable { selectedContentType = type }
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                            ) {
                                                Text(label, color = if (sel) Color.White else TextSecondary, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                                        Text(
                                            if (showAdvanced) stringResource(R.string.settings_sources_advanced_hide) else stringResource(R.string.settings_sources_advanced_show),
                                            color = Cyan,
                                        )
                                    }
                                    if (showAdvanced) {
                                        Text(
                                            text = stringResource(R.string.settings_sources_advanced_description),
                                            color = TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        SelectorField(stringResource(R.string.settings_sources_selector_list), listItemSel) { listItemSel = it }
                                        SelectorField(stringResource(R.string.settings_sources_selector_title_link), titleLinkSel) { titleLinkSel = it }
                                        SelectorField(stringResource(R.string.settings_sources_selector_description), descriptionSel) { descriptionSel = it }
                                        SelectorField(stringResource(R.string.settings_sources_selector_status), statusSel) { statusSel = it }
                                        SelectorField(stringResource(R.string.settings_sources_selector_chapter_list), chapterListSel) { chapterListSel = it }
                                        SelectorField(stringResource(R.string.settings_sources_selector_page_image), pageImageSel) { pageImageSel = it }
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
                                            JiyuLoadingIndicator(modifier = Modifier.padding(end = 8.dp), size = 16.dp, strokeWidth = 2.dp)
                                        }
                                        Text(stringResource(R.string.settings_sources_test_connection))
                                    }
                                    when (val s = testState) {
                                        is SourceTestState.Success -> Text(
                                            stringResource(R.string.settings_sources_test_success, s.count),
                                            color = Color(0xFF81C784),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                        is SourceTestState.Failure -> Text(
                                            stringResource(R.string.settings_sources_test_failure, s.message),
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
                                                contentType = selectedContentType,
                                            )
                                            showAddDialog = false
                                        }
                                    },
                                ) { Text(stringResource(R.string.common_add), color = GlowViolet) }
                            },
                            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.common_cancel), color = TextSecondary) } },
                        )
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
