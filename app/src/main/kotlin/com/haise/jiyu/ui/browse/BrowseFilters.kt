package com.haise.jiyu.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import compose.icons.TablerIcons
import compose.icons.tablericons.Book
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronDown
import compose.icons.tablericons.Search
import compose.icons.tablericons.World
import compose.icons.tablericons.X
import java.util.Locale

/** Klíč filtru "všechny typy" / "všechny jazyky" - stejná hodnota, jakou drží [BrowseViewModel]. */
private const val ALL = "ALL"

private val SheetContainer = Color(0xFF111B35)

/**
 * Kompaktní filtry pod vyhledávacím polem na obrazovce Procházet: dvě rozbalovací tlačítka (typ obsahu, jazyk)
 * a pod nimi - jen když je nějaký filtr aktivní - řádek s aktivními filtry. Stav drží [BrowseViewModel]
 * ([contentTypeFilter], [languageFilter]); tenhle composable ho jen zobrazuje a předává změny výš.
 */
@Composable
fun BrowseFilterBar(
    contentTypeFilter: String,
    languageFilter: String,
    availableLanguages: List<String>,
    onContentTypeChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showTypeSheet by rememberSaveable { mutableStateOf(false) }
    var showLanguageSheet by rememberSaveable { mutableStateOf(false) }

    val typeOptions = contentTypeOptions()
    val typeLabel = typeOptions.firstOrNull { it.first == contentTypeFilter }?.second ?: stringResource(R.string.common_all)
    val languageLabel = if (languageFilter == ALL) stringResource(R.string.common_all) else languageShortLabel(languageFilter)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterDropdownButton(
                text = stringResource(R.string.browse_filter_type_label, typeLabel),
                icon = TablerIcons.Book,
                active = contentTypeFilter != ALL,
                onClick = { showTypeSheet = true },
                modifier = Modifier.weight(1f),
            )
            FilterDropdownButton(
                text = stringResource(R.string.browse_filter_language_label, languageLabel),
                icon = TablerIcons.World,
                active = languageFilter != ALL,
                onClick = { showLanguageSheet = true },
                modifier = Modifier.weight(1f),
            )
        }

        ActiveFiltersRow(
            contentTypeLabel = typeLabel.takeIf { contentTypeFilter != ALL },
            languageLabel = languageFilter.takeIf { it != ALL }?.let { languageFlag(it) + " " + languageShortLabel(it) },
            onClearContentType = { onContentTypeChange(ALL) },
            onClearLanguage = { onLanguageChange(ALL) },
            onClearAll = {
                onContentTypeChange(ALL)
                onLanguageChange(ALL)
            },
        )
    }

    if (showTypeSheet) {
        ContentTypeBottomSheet(
            options = typeOptions,
            selected = contentTypeFilter,
            onSelect = {
                onContentTypeChange(it)
                showTypeSheet = false
            },
            onDismiss = { showTypeSheet = false },
        )
    }
    if (showLanguageSheet) {
        LanguageBottomSheet(
            languages = availableLanguages,
            selected = languageFilter,
            onSelect = {
                onLanguageChange(it)
                showLanguageSheet = false
            },
            onDismiss = { showLanguageSheet = false },
        )
    }
}

/** Typy obsahu ve stejném pořadí a s toutéž klíčovou hodnotou, jakou dřív měl řádek chipů. */
@Composable
private fun contentTypeOptions(): List<Pair<String, String>> = listOf(
    ALL to stringResource(R.string.common_all),
    BrowseViewModel.MANGA_GROUP to stringResource(R.string.browse_filter_manga),
    "NOVEL" to stringResource(R.string.browse_filter_novels),
    "COMIC" to stringResource(R.string.browse_filter_comics),
)

/** Kompaktní tlačítko filtru: ikona, text (jedna řádka, zkrátí se) a šipka dolů. Aktivní filtr má fialový okraj. */
@Composable
private fun FilterDropdownButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(shape)
            .background(NightBlue.copy(alpha = 0.7f))
            .border(1.dp, if (active) Violet.copy(alpha = 0.7f) else CardBorder, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Violet, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = if (active) TextPrimary else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Icon(TablerIcons.ChevronDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
    }
}

/** Řádek aktivních filtrů s možností zrušit každý zvlášť nebo všechny; bez aktivního filtru se vůbec nezobrazí. */
@Composable
private fun ActiveFiltersRow(
    contentTypeLabel: String?,
    languageLabel: String?,
    onClearContentType: () -> Unit,
    onClearLanguage: () -> Unit,
    onClearAll: () -> Unit,
) {
    AnimatedVisibility(
        visible = contentTypeLabel != null || languageLabel != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // Při odchodu animace se labely už vynulují - zapamatujeme poslední neprázdné, aby chip během animace nezmizel dřív.
        var lastType by remember { mutableStateOf(contentTypeLabel) }
        var lastLanguage by remember { mutableStateOf(languageLabel) }
        if (contentTypeLabel != null) lastType = contentTypeLabel
        if (languageLabel != null) lastLanguage = languageLabel
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            (contentTypeLabel ?: lastType)?.let { ActiveFilterChip(it, onClearContentType) }
            (languageLabel ?: lastLanguage)?.let { ActiveFilterChip(it, onClearLanguage) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClearAll) {
                Text(stringResource(R.string.browse_filters_clear_all), color = Violet, fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(label: String, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(50.dp)
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(Violet.copy(alpha = 0.18f))
            .border(1.dp, Violet.copy(alpha = 0.6f), shape)
            .clickable(onClick = onRemove)
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(6.dp))
        Icon(
            TablerIcons.X,
            contentDescription = stringResource(R.string.browse_filter_remove),
            tint = Violet,
            modifier = Modifier.size(14.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentTypeBottomSheet(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetTitle(stringResource(R.string.browse_filter_type_title))
            options.forEach { (key, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(key) }
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selected == key,
                        onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = Violet, unselectedColor = TextSecondary),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = label,
                        color = if (selected == key) TextPrimary else TextSecondary,
                        fontSize = 16.sp,
                        fontWeight = if (selected == key) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageBottomSheet(
    languages: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    // Filtr podle názvu (v jazyce UI) i podle kódu; názvy se počítají jednou pro seznam, ne při každém tlačítku.
    val entries = remember(languages) { languages.map { LanguageEntry(it, languageName(it), languageShortLabel(it)) } }
    val filtered = remember(entries, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) entries else entries.filter {
            it.name.lowercase().contains(q) || it.label.lowercase().contains(q) || it.code.lowercase().contains(q)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SheetContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
            SheetTitle(stringResource(R.string.browse_filter_language_title))

            Row(
                modifier = Modifier
                    .padding(horizontal = 20.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(NightBlue.copy(alpha = 0.7f))
                    .border(1.dp, CardBorder, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.browse_filter_language_search), color = TextSecondary, fontSize = 15.sp)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                        cursorBrush = SolidColor(Violet),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                if (query.isBlank()) {
                    item(key = ALL) {
                        LanguageRow(
                            flag = "🌐",
                            name = stringResource(R.string.browse_filter_language_all),
                            code = "",
                            selected = selected == ALL,
                            onClick = { onSelect(ALL) },
                        )
                    }
                }
                items(filtered, key = { it.code }) { e ->
                    LanguageRow(
                        flag = languageFlag(e.code),
                        name = e.name,
                        code = e.label,
                        selected = selected.equals(e.code, ignoreCase = true),
                        onClick = { onSelect(e.code) },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private data class LanguageEntry(val code: String, val name: String, val label: String)

@Composable
private fun LanguageRow(flag: String, name: String, code: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 20.sp, modifier = Modifier.width(32.dp))
        Text(
            text = name,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (code.isNotEmpty()) {
            Text(code, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(TablerIcons.Check, contentDescription = null, tint = Violet, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(28.dp))
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = TextPrimary,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
    )
}

/** Krátký štítek jazyka: kód velkými písmeny, japonština se u zdrojů značí "RAW" (stejně jako dřív v chipech). */
internal fun languageShortLabel(code: String): String = if (code.equals("ja", ignoreCase = true)) "RAW" else code.uppercase()

/** Název jazyka v jazyce rozhraní ("Türkçe" -> "Turkish"/"Turečtina"...); když ho systém nezná, vrátí se kód. */
internal fun languageName(code: String): String {
    val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) code.uppercase()
    else name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
}
