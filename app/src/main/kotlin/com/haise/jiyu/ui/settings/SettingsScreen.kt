package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.settings.ReadingDirection
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

private val LANGUAGES = listOf(
    "Czech"   to "Čeština",
    "Slovak"  to "Slovenčina",
    "English" to "English",
    "Polish"  to "Polski",
    "German"  to "Deutsch",
    "Spanish" to "Español",
    "French"  to "Français",
)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val language   by viewModel.targetLanguage.collectAsState()
    val theme      by viewModel.theme.collectAsState()
    val direction  by viewModel.readingDirection.collectAsState()
    val cacheCount by viewModel.cacheCount.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient),
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zpět", tint = TextSecondary)
            }
            Text(
                text = "Nastavení",
                style = TextStyle(
                    brush = titleGradient,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                ),
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            SettingsSection(title = "Překlad") {
                LANGUAGES.forEach { (value, label) ->
                    GlassRadioRow(
                        label = label,
                        selected = language == value,
                        onClick = { viewModel.setTargetLanguage(value) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SettingsSection(title = "Téma") {
                listOf(
                    ThemeOption.SYSTEM to "Systémové",
                    ThemeOption.DARK   to "Tmavé",
                    ThemeOption.LIGHT  to "Světlé",
                ).forEach { (value, label) ->
                    GlassRadioRow(
                        label = label,
                        selected = theme == value,
                        onClick = { viewModel.setTheme(value) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SettingsSection(title = "Směr čtení") {
                listOf(
                    ReadingDirection.LTR to "← Zleva doprava (manhwa, western)",
                    ReadingDirection.RTL to "→ Zprava doleva (japonská manga)",
                ).forEach { (value, label) ->
                    GlassRadioRow(
                        label = label,
                        selected = direction == value,
                        onClick = { viewModel.setReadingDirection(value) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            SettingsSection(title = "Cache překladů") {
                Text(
                    text = "Uložené překlady: $cacheCount stránek",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                OutlinedButton(
                    onClick = { viewModel.clearTranslationCache() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text("Vymazat cache")
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
        color = Violet,
        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(glassGradient)
            .border(1.dp, GlowViolet.copy(alpha = 0.2f), RoundedCornerShape(16.dp)),
    ) {
        content()
    }
}

@Composable
private fun GlassRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = Violet,
                unselectedColor = TextSecondary,
            ),
        )
        Text(
            text = label,
            color = if (selected) TextPrimary else TextSecondary,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
