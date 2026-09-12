package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.data.db.entity.GlossaryEntity
import compose.icons.TablerIcons
import compose.icons.tablericons.Lock
import compose.icons.tablericons.LockOpen
import compose.icons.tablericons.X

// ── Slovník AI překladu - rychlý přístup přímo z čtečky ─────────────────────
// Použito jak z ReaderControls.kt (manga/webtoon mód), tak z NovelContent.kt (novely).

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossaryBottomSheet(
    glossary: List<GlossaryEntity>,
    targetLanguage: String,
    onAdd: (String, String, Boolean) -> Unit,
    onRemove: (GlossaryEntity) -> Unit,
    onToggleProtectExact: (GlossaryEntity) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var sourceText by remember { mutableStateOf("") }
    var targetText by remember { mutableStateOf("") }
    var protectExact by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF111B35),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.reader_glossary_title), color = Color.White, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontSize = 18.sp)
            Text(
                stringResource(R.string.reader_glossary_desc),
                color = Color(0xFFB0BEC5),
                fontSize = 12.sp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextField(
                    value = sourceText,
                    onValueChange = { sourceText = it },
                    placeholder = { Text(stringResource(R.string.reader_original_toggle), color = Color(0xFFB0BEC5), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    ),
                )
                Text("→", color = Color(0xFFB0BEC5))
                TextField(
                    value = targetText,
                    onValueChange = { targetText = it },
                    placeholder = { Text(stringResource(R.string.reader_glossary_translation_placeholder), color = Color(0xFFB0BEC5), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.06f),
                    ),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = protectExact,
                    onCheckedChange = { protectExact = it },
                    modifier = Modifier.size(32.dp),
                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF8B5CF6), uncheckedColor = Color(0xFFB0BEC5)),
                )
                Text(
                    stringResource(R.string.reader_glossary_protect_label),
                    color = Color(0xFFB0BEC5),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            TextButton(onClick = {
                if (sourceText.isNotBlank() && targetText.isNotBlank()) {
                    onAdd(sourceText, targetText, protectExact)
                    sourceText = ""
                    targetText = ""
                    protectExact = false
                }
            }) { Text(stringResource(R.string.reader_glossary_add_button, targetLanguage), color = Color(0xFF8B5CF6)) }

            if (glossary.isEmpty()) {
                Text(stringResource(R.string.reader_glossary_empty), color = Color(0xFFB0BEC5), fontSize = 13.sp)
            } else {
                glossary.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${entry.sourceTerm} → ${entry.targetTerm}", color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onToggleProtectExact(entry) }) {
                            Icon(
                                if (entry.protectExact) TablerIcons.Lock else TablerIcons.LockOpen,
                                contentDescription = stringResource(
                                    if (entry.protectExact) R.string.reader_glossary_protect_on_description else R.string.reader_glossary_protect_off_description,
                                ),
                                tint = if (entry.protectExact) Color(0xFF8B5CF6) else Color(0xFFB0BEC5),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        IconButton(onClick = { onRemove(entry) }) {
                            Icon(TablerIcons.X, contentDescription = stringResource(R.string.common_remove), tint = Color(0xFFB0BEC5), modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        }
    }
}
