package com.haise.jiyu.ui.settings

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.ui.reader.TapZoneAction
import com.haise.jiyu.ui.reader.TapZoneGrid
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet

private val ACTION_ORANGE = Color(0xFFFFB74D)
private val ACTION_CYAN   = Color(0xFF4FC3F7)

private fun TapZoneAction.label() = when (this) {
    TapZoneAction.NONE         -> "Nic"
    TapZoneAction.SHOW_PANEL   -> "Zobrazit\novládání"
    TapZoneAction.PREV_PAGE    -> "Předchozí\nstránka"
    TapZoneAction.NEXT_PAGE    -> "Další\nstránka"
    TapZoneAction.PREV_CHAPTER -> "Předchozí\nkapitola"
    TapZoneAction.NEXT_CHAPTER -> "Další\nkapitola"
}

private fun TapZoneAction.shortLabel() = when (this) {
    TapZoneAction.NONE         -> "—"
    TapZoneAction.SHOW_PANEL   -> "☰"
    TapZoneAction.PREV_PAGE    -> "◀"
    TapZoneAction.NEXT_PAGE    -> "▶"
    TapZoneAction.PREV_CHAPTER -> "⏮"
    TapZoneAction.NEXT_CHAPTER -> "⏭"
}

private fun TapZoneAction.color() = when (this) {
    TapZoneAction.NONE         -> Color.White.copy(alpha = 0.15f)
    TapZoneAction.SHOW_PANEL   -> GlowViolet
    TapZoneAction.PREV_PAGE    -> ACTION_CYAN
    TapZoneAction.NEXT_PAGE    -> ACTION_CYAN
    TapZoneAction.PREV_CHAPTER -> ACTION_ORANGE
    TapZoneAction.NEXT_CHAPTER -> ACTION_ORANGE
}

private fun TapZoneAction.description() = when (this) {
    TapZoneAction.NONE         -> "Tap v této zóně nebude dělat nic"
    TapZoneAction.SHOW_PANEL   -> "Zobrazí / skryje ovládací panel"
    TapZoneAction.PREV_PAGE    -> "Přejde na předchozí stránku"
    TapZoneAction.NEXT_PAGE    -> "Přejde na další stránku"
    TapZoneAction.PREV_CHAPTER -> "Přejde na předchozí kapitolu"
    TapZoneAction.NEXT_CHAPTER -> "Přejde na další kapitolu"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TapZoneSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val grid by viewModel.tapZoneGrid.collectAsState()

    // Která buňka je aktuálně otevřena pro editaci (row * 3 + col, nebo null)
    var editingCell by remember { mutableStateOf<Int?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepSpace)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowBack, null, tint = TextPrimary)
                }
                Text(
                    "Tapové zóny",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.setTapZoneGrid(TapZoneGrid()) }) {
                    Icon(TablerIcons.Refresh, "Výchozí nastavení", tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(4.dp))

            Text(
                "Každá ze 9 zón obrazovky může provést jinou akci. Klepni na zónu a vyber akci.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
                lineHeight = 18.sp,
            )

            Spacer(Modifier.height(24.dp))

            // ── Vizuální grid — "obrazovka" ──────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .aspectRatio(9f / 16f)
                    .clip(RoundedCornerShape(20.dp))
                    .border(2.dp, GlowViolet.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .background(Color(0xFF0D1226)),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    for (row in 0..2) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(1.dp),
                        ) {
                            for (col in 0..2) {
                                val action = grid[row, col]
                                val cellIdx = row * 3 + col
                                val isEditing = editingCell == cellIdx
                                val baseColor = action.color()
                                val bgColor by animateColorAsState(
                                    if (isEditing) baseColor.copy(alpha = 0.35f)
                                    else baseColor.copy(alpha = if (action == TapZoneAction.NONE) 0.04f else 0.15f),
                                    animationSpec = tween(150),
                                    label = "cell_bg_$cellIdx",
                                )
                                val borderColor by animateColorAsState(
                                    if (isEditing) baseColor.copy(alpha = 0.8f)
                                    else baseColor.copy(alpha = if (action == TapZoneAction.NONE) 0.12f else 0.45f),
                                    animationSpec = tween(150),
                                    label = "cell_border_$cellIdx",
                                )

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                        .background(bgColor)
                                        .border(
                                            0.5.dp,
                                            borderColor,
                                        )
                                        .clickable { editingCell = cellIdx },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = action.shortLabel(),
                                            color = if (action == TapZoneAction.NONE)
                                                TextSecondary.copy(alpha = 0.4f)
                                            else baseColor,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        if (action != TapZoneAction.NONE) {
                                            Text(
                                                text = action.label(),
                                                color = baseColor.copy(alpha = 0.8f),
                                                fontSize = 7.sp,
                                                textAlign = TextAlign.Center,
                                                lineHeight = 9.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Legenda akcí ─────────────────────────────────────────────────
            Text(
                "Dostupné akce",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, GlowViolet.copy(alpha = 0.15f), RoundedCornerShape(16.dp)),
            ) {
                TapZoneAction.values().forEach { action ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(action.color().copy(alpha = 0.15f))
                                .border(1.dp, action.color().copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(action.shortLabel(), color = action.color(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(action.label().replace("\n", " "), color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(action.description(), color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Bottom sheet pro výběr akce ──────────────────────────────────────────
    if (editingCell != null) {
        val cellIdx   = editingCell!!
        val cellRow   = cellIdx / 3
        val cellCol   = cellIdx % 3
        val rowName   = listOf("Horní", "Střední", "Dolní")[cellRow]
        val colName   = listOf("levá", "střední", "pravá")[cellCol]
        val current   = grid[cellRow, cellCol]

        ModalBottomSheet(
            onDismissRequest = { editingCell = null },
            sheetState = sheetState,
            containerColor = Color(0xFF111B35),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text(
                    "$rowName $colName zóna",
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    "Vyber akci pro tuto oblast obrazovky",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 16.dp),
                )

                TapZoneAction.values().forEach { action ->
                    val selected = action == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (selected) action.color().copy(alpha = 0.15f)
                                else Color.White.copy(alpha = 0.04f)
                            )
                            .border(
                                1.dp,
                                if (selected) action.color().copy(alpha = 0.6f)
                                else Color.White.copy(alpha = 0.08f),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable {
                                viewModel.setTapZoneGrid(grid.withAction(cellRow, cellCol, action))
                                editingCell = null
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(action.color().copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(action.shortLabel(), color = action.color(), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                action.label().replace("\n", " "),
                                color = if (selected) TextPrimary else TextSecondary,
                                fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(action.description(), color = TextSecondary, fontSize = 12.sp)
                        }
                        if (selected) {
                            Icon(TablerIcons.Check, null, tint = action.color(), modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
