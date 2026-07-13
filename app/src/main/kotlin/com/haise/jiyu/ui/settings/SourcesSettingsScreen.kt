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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            SettingsSubScreenHeader(title = "Zdroje mang", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = "Zdroje") {
                    OutlinedButton(
                        onClick = onOpenSourceCatalog,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text("Katalog zdrojů (${viewModel.getCatalog().size})")
                    }
                    OutlinedButton(
                        onClick = onOpenCustomCss,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).padding(bottom = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                    ) {
                        Text("Vlastní CSS pro web zdroje")
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
                                Icon(TablerIcons.Trash, contentDescription = "Smazat", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showAddDialog = true },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = GlowViolet),
                    ) {
                        Icon(TablerIcons.Plus, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Přidat zdroj")
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
                            title = { Text("Přidat Madara zdroj", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(
                                    modifier = Modifier
                                        .heightIn(max = 420.dp)
                                        .verticalScroll(rememberScrollState()),
                                ) {
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
                                    Spacer(Modifier.height(8.dp))
                                    Text("Typ obsahu", color = TextSecondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 4.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("MANGA" to "Manga", "MANHWA" to "Manhwa", "MANHUA" to "Manhua", "NOVEL" to "Novely", "COMIC" to "Komiksy").forEach { (type, label) ->
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
                                            if (showAdvanced) "Skrýt pokročilé selektory" else "Pokročilé selektory (volitelné)",
                                            color = Cyan,
                                        )
                                    }
                                    if (showAdvanced) {
                                        Text(
                                            text = "Vyplň jen pokud výchozí Madara selektory na tomto webu nesedí (téma bylo upravené). Prázdné pole = použije se výchozí.",
                                            color = TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(bottom = 8.dp),
                                        )
                                        SelectorField("Seznam položek (list)", listItemSel) { listItemSel = it }
                                        SelectorField("Odkaz s názvem (title link)", titleLinkSel) { titleLinkSel = it }
                                        SelectorField("Popis (description)", descriptionSel) { descriptionSel = it }
                                        SelectorField("Stav vydávání (status)", statusSel) { statusSel = it }
                                        SelectorField("Seznam kapitol (chapter list)", chapterListSel) { chapterListSel = it }
                                        SelectorField("Obrázky stránky (page image)", pageImageSel) { pageImageSel = it }
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
                                        Text("Otestovat připojení")
                                    }
                                    when (val s = testState) {
                                        is SourceTestState.Success -> Text(
                                            "✓ Nalezeno ${s.count} položek",
                                            color = Color(0xFF81C784),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(top = 6.dp),
                                        )
                                        is SourceTestState.Failure -> Text(
                                            "✗ ${s.message}",
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
                                ) { Text("Přidat", color = GlowViolet) }
                            },
                            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Zrušit", color = TextSecondary) } },
                        )
                    }
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
