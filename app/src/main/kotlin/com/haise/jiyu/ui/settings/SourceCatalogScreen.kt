package com.haise.jiyu.ui.settings

import com.haise.jiyu.ui.components.JiyuLoadingIndicator


import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.data.db.entity.CustomSourceEntity
import com.haise.jiyu.source.catalog.CatalogSource
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

private val CONTENT_TYPES = listOf("MANGA", "MANHWA", "MANHUA", "NOVEL", "COMIC")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceCatalogScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val catalog = viewModel.getCatalog()
    val customSources by viewModel.customSources.collectAsState()
    val sourceTestState by viewModel.sourceTestState.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (selectedTab == 1) {
                ExtendedFloatingActionButton(
                    onClick = { showAddSheet = true },
                    icon = { Icon(TablerIcons.Plus, null) },
                    text = { Text("Přidat zdroj") },
                    containerColor = Violet,
                    contentColor = Color.White,
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
                }
                Text(
                    text = "Zdroje",
                    style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // ── Tab bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
            ) {
                listOf("Katalog", "Moje zdroje").forEachIndexed { index, label ->
                    val active = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) Violet.copy(alpha = 0.25f) else Color.Transparent)
                            .clickable { selectedTab = index }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val extra = if (index == 1 && customSources.isNotEmpty()) " (${customSources.size})" else ""
                        Text(
                            text = label + extra,
                            color = if (active) Violet else TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                0 -> CatalogTab(catalog = catalog, viewModel = viewModel)
                1 -> MySourcesTab(sources = customSources, onDelete = viewModel::deleteCustomSource)
            }
        }
    }

    // ── Bottom sheet: přidat vlastní zdroj ──────────────────────────────────
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showAddSheet = false
                viewModel.clearSourceTestState()
            },
            sheetState = sheetState,
            containerColor = Color(0xFF111B35),
        ) {
            AddCustomSourceForm(
                testState = sourceTestState,
                onTest = { _, url, _, adv ->
                    viewModel.testCustomSource(url, adv[0], adv[1], adv[2], adv[3], adv[4], adv[5])
                },
                onSave = { name, url, ct, adv ->
                    viewModel.addCustomSource(
                        name = name, baseUrl = url,
                        listItemSelector = adv[0], titleLinkSelector = adv[1],
                        descriptionSelector = adv[2], statusSelector = adv[3],
                        chapterListSelector = adv[4], pageImageSelector = adv[5],
                        contentType = ct,
                    )
                    showAddSheet = false
                    viewModel.clearSourceTestState()
                },
            )
        }
    }
}

@Composable
private fun CatalogTab(
    catalog: List<CatalogSource>,
    viewModel: SettingsViewModel,
) {
    val customSources by viewModel.customSources.collectAsState()

    Text(
        text = "Přednastavené Madara zdroje — jedním klepnutím přidat.",
        color = TextSecondary,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
    )
    Spacer(Modifier.height(4.dp))

    if (catalog.isEmpty()) {
        Text("Katalog je prázdný.", color = TextSecondary, modifier = Modifier.padding(horizontal = 20.dp))
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(catalog, key = { it.id }) { source ->
                val installed = customSources.any { it.baseUrl.trimEnd('/') == source.baseUrl.trimEnd('/') }
                CatalogSourceCard(
                    source = source,
                    installed = installed,
                    onInstall = { viewModel.installCatalogSource(source) },
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun MySourcesTab(
    sources: List<CustomSourceEntity>,
    onDelete: (CustomSourceEntity) -> Unit,
) {
    if (sources.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Zatím žádné vlastní zdroje", color = TextSecondary, fontSize = 15.sp)
                Spacer(Modifier.height(4.dp))
                Text("Klepni na + a přidej libovolný Madara web.", color = TextSecondary.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(sources, key = { it.id }) { source ->
                CustomSourceCard(source = source, onDelete = { onDelete(source) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun CustomSourceCard(
    source: CustomSourceEntity,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(source.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(source.contentType, color = Violet.copy(alpha = 0.8f), fontSize = 11.sp)
            Text(source.baseUrl, color = Cyan.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        IconButton(onClick = onDelete) {
            Icon(TablerIcons.Trash, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
        }
    }
}

@Composable
private fun CatalogSourceCard(
    source: CatalogSource,
    installed: Boolean,
    onInstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(source.name, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                if (source.nsfw) {
                    Text(" 18+", color = MaterialTheme.colorScheme.error, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(source.description, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            Text(source.baseUrl, color = Cyan.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }

        if (installed) {
            Icon(TablerIcons.Check, contentDescription = "Nainstalováno", tint = Color(0xFF66BB6A))
        } else {
            OutlinedButton(
                onClick = onInstall,
                modifier = Modifier.padding(start = 8.dp),
            ) {
                Icon(TablerIcons.Download, null, modifier = Modifier.padding(end = 4.dp), tint = Violet)
                Text("Přidat", color = Violet, fontSize = 13.sp)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddCustomSourceForm(
    testState: SourceTestState,
    onTest: (name: String, url: String, ct: String, adv: List<String?>) -> Unit,
    onSave: (name: String, url: String, ct: String, adv: List<String?>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var contentType by remember { mutableStateOf("MANGA") }
    var ctDropdownOpen by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }

    // Advanced CSS selectors (blank = use Madara defaults)
    var listItem by remember { mutableStateOf("") }
    var titleLink by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var chapterList by remember { mutableStateOf("") }
    var pageImage by remember { mutableStateOf("") }

    val advSelectors = listOf(
        listItem.ifBlank { null },
        titleLink.ifBlank { null },
        description.ifBlank { null },
        status.ifBlank { null },
        chapterList.ifBlank { null },
        pageImage.ifBlank { null },
    )

    val canSave = name.isNotBlank() && url.isNotBlank() &&
        (url.startsWith("http://") || url.startsWith("https://"))

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Violet,
        unfocusedBorderColor = GlowViolet.copy(alpha = 0.3f),
        focusedLabelColor = Violet,
        unfocusedLabelColor = TextSecondary,
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        cursorColor = Violet,
    )

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Přidat vlastní Madara zdroj",
            color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        )
        Text(
            "Funguje s libovolným webem postaveným na WordPress Madara tématu.",
            color = TextSecondary, fontSize = 13.sp,
        )

        Spacer(Modifier.height(4.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Název zdroje *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL webu * (https://...)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
            isError = url.isNotBlank() && !url.startsWith("http"),
            supportingText = if (url.isNotBlank() && !url.startsWith("http")) {
                { Text("URL musí začínat https://", color = MaterialTheme.colorScheme.error) }
            } else null,
        )

        // Content type dropdown
        ExposedDropdownMenuBox(
            expanded = ctDropdownOpen,
            onExpandedChange = { ctDropdownOpen = it },
        ) {
            OutlinedTextField(
                value = contentType,
                onValueChange = {},
                readOnly = true,
                label = { Text("Typ obsahu") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ctDropdownOpen) },
                modifier = Modifier.fillMaxWidth().menuAnchor(),
                colors = fieldColors,
            )
            ExposedDropdownMenu(
                expanded = ctDropdownOpen,
                onDismissRequest = { ctDropdownOpen = false },
            ) {
                CONTENT_TYPES.forEach { ct ->
                    DropdownMenuItem(
                        text = { Text(ct) },
                        onClick = { contentType = ct; ctDropdownOpen = false },
                    )
                }
            }
        }

        // Advanced selectors toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color.White.copy(alpha = 0.04f))
                .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .clickable { advancedExpanded = !advancedExpanded }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Vlastní CSS selektory (nepovinné)",
                color = TextSecondary, fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (advancedExpanded) TablerIcons.ChevronUp else TablerIcons.ChevronDown,
                contentDescription = null,
                tint = TextSecondary,
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = advancedExpanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Prázdné pole = použije se výchozí Madara selektor.",
                    color = TextSecondary.copy(alpha = 0.7f), fontSize = 11.sp,
                )
                listOf(
                    Triple("Položka v seznamu", listItem) { v: String -> listItem = v },
                    Triple("Odkaz s názvem", titleLink) { v: String -> titleLink = v },
                    Triple("Popis", description) { v: String -> description = v },
                    Triple("Status", status) { v: String -> status = v },
                    Triple("Seznam kapitol", chapterList) { v: String -> chapterList = v },
                    Triple("Obrázek stránky", pageImage) { v: String -> pageImage = v },
                ).forEach { (label, value, setter) ->
                    OutlinedTextField(
                        value = value,
                        onValueChange = setter,
                        label = { Text(label) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = fieldColors,
                        placeholder = { Text("výchozí", color = TextSecondary.copy(alpha = 0.4f), fontSize = 12.sp) },
                    )
                }
            }
        }

        // Test result feedback
        when (val state = testState) {
            is SourceTestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                JiyuLoadingIndicator(size = 16.dp, strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Testuji připojení...", color = TextSecondary, fontSize = 13.sp)
            }
            is SourceTestState.Success -> Text(
                "✓ Připojení OK — nalezeno ${state.count} položek",
                color = Color(0xFF66BB6A), fontSize = 13.sp,
            )
            is SourceTestState.Failure -> Text(
                "✗ ${state.message}",
                color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
            )
            else -> {}
        }

        // Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { onTest(name, url, contentType, advSelectors) },
                enabled = canSave && testState !is SourceTestState.Testing,
                modifier = Modifier.weight(1f),
            ) {
                Text("Otestovat", color = Violet)
            }
            Button(
                onClick = { onSave(name, url, contentType, advSelectors) },
                enabled = canSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
            ) {
                Text("Uložit")
            }
        }
    }
}
