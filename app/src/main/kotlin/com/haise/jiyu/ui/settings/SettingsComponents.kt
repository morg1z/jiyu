package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.ui.reader.TapZoneAction
import com.haise.jiyu.ui.reader.TapZoneGrid
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.titleGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowBack

/** Sdílená hlavička podstránek Nastavení - zpět + gradientní titulek, stejný styl jako hlavní obrazovka. */
@Composable
internal fun SettingsSubScreenHeader(title: String, onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(TablerIcons.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = TextSecondary)
        }
        Text(
            text = title,
            style = TextStyle(brush = titleGradient, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp),
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
internal fun SelectorField(label: String, value: String, onValueChange: (String) -> Unit) {
    // Použito jen uvnitř dialogu s napevno tmavým pozadím (SourcesSettingsScreen) - proto pevné světlé barvy.
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = TextFieldDefaults.colors(
            focusedLabelColor = Violet,
            unfocusedLabelColor = Color(0xFFB0BEC5),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Violet,
        ),
    )
}

@Composable
internal fun SettingsSection(title: String, content: @Composable () -> Unit) {
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

internal fun buildZoneGridSummary(grid: TapZoneGrid): String {
    fun short(a: TapZoneAction) = when (a) {
        TapZoneAction.NONE         -> "—"
        TapZoneAction.SHOW_PANEL   -> "Panel"
        TapZoneAction.PREV_PAGE    -> "◀"
        TapZoneAction.NEXT_PAGE    -> "▶"
        TapZoneAction.PREV_CHAPTER -> "⏮Kap"
        TapZoneAction.NEXT_CHAPTER -> "Kap⏭"
    }
    return (0..2).joinToString("  ") { col ->
        (0..2).joinToString("/") { row -> short(grid[row, col]) }
    }
}

@Composable
internal fun GlassRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
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
            colors = RadioButtonDefaults.colors(selectedColor = Violet, unselectedColor = TextSecondary),
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
