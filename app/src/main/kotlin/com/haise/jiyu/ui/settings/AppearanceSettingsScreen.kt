package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.ui.theme.screenGradient

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme             by viewModel.theme.collectAsState()
    val libraryGridColumns by viewModel.libraryGridColumns.collectAsState()
    val defaultCategoryId  by viewModel.defaultCategoryId.collectAsState()
    val allCategories      by viewModel.categories.collectAsState()

    Scaffold(containerColor = Color.Transparent) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = "Vzhled", onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_language)) {
                    val appLanguages = listOf(
                        "cs" to "🇨🇿  Čeština",
                        "en" to "🇬🇧  English",
                        "fr" to "🇫🇷  Français",
                        "es" to "🇪🇸  Español",
                    )
                    appLanguages.forEach { (tag, label) ->
                        val currentTag = androidx.appcompat.app.AppCompatDelegate
                            .getApplicationLocales().toLanguageTags()
                            .split(",").firstOrNull()?.take(2) ?: "cs"
                        GlassRadioRow(
                            label = label,
                            selected = currentTag == tag,
                            onClick = { viewModel.setLanguage(tag) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Téma") {
                    listOf(
                        ThemeOption.SYSTEM to "Systémové",
                        ThemeOption.LIGHT to "Světlé",
                        ThemeOption.DARK to "Tmavé",
                        ThemeOption.TRUE_BLACK to "Čistě černé (OLED)",
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = theme == value, onClick = { viewModel.setTheme(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = "Knihovna") {
                    androidx.compose.material3.Text(
                        text = "Sloupce v mřížce",
                        color = com.haise.jiyu.ui.theme.TextSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                    listOf(2 to "2 sloupce", 3 to "3 sloupce (výchozí)", 4 to "4 sloupce").forEach { (n, label) ->
                        GlassRadioRow(
                            label = label,
                            selected = libraryGridColumns == n,
                            onClick = { viewModel.setLibraryGridColumns(n) },
                        )
                    }

                    if (allCategories.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = "Výchozí kategorie pro nová manga",
                            color = com.haise.jiyu.ui.theme.TextSecondary,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                        GlassRadioRow(
                            label = "Žádná (nepřiřazovat)",
                            selected = defaultCategoryId == null,
                            onClick = { viewModel.setDefaultCategoryId(null) },
                        )
                        allCategories.forEach { cat ->
                            GlassRadioRow(
                                label = cat.name,
                                selected = defaultCategoryId == cat.id,
                                onClick = { viewModel.setDefaultCategoryId(cat.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}
