package com.haise.jiyu.ui.browse

import compose.icons.TablerIcons
import compose.icons.tablericons.*

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.haise.jiyu.R
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.ui.theme.Accent
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.Danger
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.Pink
import com.haise.jiyu.ui.theme.Success
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.Warning
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient

/** Hlavní obrazovka Procházet - mřížka zdrojů. Obsah konkrétního zdroje viz [SourceBrowseScreen]. */
@Composable
fun BrowseScreen(
    onOpenSource: (String) -> Unit,
    onGlobalSearch: () -> Unit = {},
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val sources           by viewModel.sources.collectAsState()
    val contentTypeFilter by viewModel.contentTypeFilter.collectAsState()
    val languageFilter    by viewModel.languageFilter.collectAsState()
    val sourceNameFilter  by viewModel.sourceNameFilter.collectAsState()
    val favoriteSourceIds by viewModel.favoriteSourceIds.collectAsState()

    // false = hledat TITUL napříč všemi zdroji (otevře GlobalSearch, beze změny chování),
    // true = hledat přímo podle NÁZVU ZDROJE (jen lokálně filtruje mřížku níže) - pro
    // uživatele, co chtějí číst na jednom konkrétním zdroji a nechtějí kvůli tomu
    // prohledávat všechny zdroje najednou.
    var searchBySourceName by rememberSaveable { mutableStateOf(false) }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // Hlavička (nadpis, přepínač hledání, vyhledávací pole, oba řádky filtrů i oblíbené
    // zdroje) je SOUČÁSTÍ mřížky, ne pruh nad ní. Dřív stála mimo a zůstávala přilepená
    // nahoře - zabírala skoro třetinu obrazovky pořád, i když uživatel scroloval hluboko
    // v seznamu, a přesně na to si stěžoval ("zavazí to, když hledám zdroje"). Jako
    // položky mřížky přes celou šířku odjedou s obsahem a seznam dostane celou plochu.
    //
    // statusBarsPadding je proto tady, na obalu: kdyby zůstalo na hlavičce, odscrolovalo
    // by pryč s ní a obsah by se dostal pod stavovou lištu.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
            .statusBarsPadding()
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp + navBottom),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ── Header ──────────────────────────────────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(colors = listOf(NightBlue, DeepSpace.copy(alpha = 0f))))
                        // Vodorovné odsazení je 4.dp, ne 16.dp: mřížka přidává svých 12.dp
                        // v contentPadding, takže výsledek zůstává na původních 16.dp.
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.browse_title),
                        style = TextStyle(brush = titleGradient, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp),
                    )

                    // Přepínač režimu hledání - viz komentář u [searchBySourceName].
                    Row(
                        modifier = Modifier.padding(top = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(label = stringResource(R.string.browse_search_mode_title), selected = !searchBySourceName) {
                            searchBySourceName = false
                            viewModel.setSourceNameFilter("")
                        }
                        FilterChip(label = stringResource(R.string.browse_search_mode_source), selected = searchBySourceName) {
                            searchBySourceName = true
                        }
                    }

                    // Titul: klik naviguje na GlobalSearch (beze změny). Zdroj: skutečné textové
                    // pole, které jen lokálně filtruje mřížku zdrojů podle jejich názvu.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(NightBlue.copy(alpha = 0.7f))
                            .glassBorder(14.dp)
                            .then(
                                if (searchBySourceName) Modifier else Modifier.clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = onGlobalSearch,
                                )
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(TablerIcons.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            if (searchBySourceName) {
                                Box(modifier = Modifier.weight(1f)) {
                                    if (sourceNameFilter.isEmpty()) {
                                        Text(stringResource(R.string.browse_search_by_source_placeholder), color = TextSecondary, fontSize = 15.sp)
                                    }
                                    BasicTextField(
                                        value = sourceNameFilter,
                                        onValueChange = viewModel::setSourceNameFilter,
                                        singleLine = true,
                                        textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                                        cursorBrush = SolidColor(Violet),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            } else {
                                Text(stringResource(R.string.browse_search_placeholder), color = TextSecondary, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }

            // ── Typový filtr - kompaktní řádek chipů ─────────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                val contentTypes = listOf(
                    "ALL" to stringResource(R.string.common_all),
                    BrowseViewModel.MANGA_GROUP to stringResource(R.string.browse_filter_manga),
                    "NOVEL" to stringResource(R.string.browse_filter_novels),
                    "COMIC" to stringResource(R.string.browse_filter_comics),
                )
                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(contentTypes) { (type, label) ->
                        FilterChip(label = label, selected = contentTypeFilter == type) { viewModel.setContentTypeFilter(type) }
                    }
                }
            }

            // ── Jazykový filtr - kompaktní řádek chipů ───────────────────────────
            item(span = { GridItemSpan(maxLineSpan) }) {
                val languages = listOf(
                    "ALL" to stringResource(R.string.browse_lang_all),
                    "en"  to "EN",
                    "fr"  to "FR",
                    "es"  to "ES",
                    "pt"  to "PT",
                    "ja"  to "RAW",
                )
                LazyRow(
                    contentPadding = PaddingValues(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(languages) { (code, label) ->
                        FilterChip(label = label, selected = languageFilter == code) { viewModel.setLanguageFilter(code) }
                    }
                }
            }

            // ── Oblíbené zdroje - horizontální karusel zvýrazněných karet ────────
            val favoriteSources = sources.filter { it.id in favoriteSourceIds }
            if (favoriteSources.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(TablerIcons.Heart, contentDescription = null, tint = Pink, modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(R.string.browse_favorites_section),
                                color = TextPrimary,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(favoriteSources, key = { "fav_${it.id}" }) { source ->
                                FeaturedSourceCard(source = source, onClick = { onOpenSource(source.id) })
                            }
                        }
                    }
                }
            }

            // ── Mřížka zdrojů ─────────────────────────────────────────────────────
            if (sources.isEmpty()) {
                // Hláška je POLOŽKA mřížky, ne náhrada za celou mřížku. Dřív nahrazovala i to,
                // co je nad ní - což bylo neškodné, dokud hlavička stála mimo. Teď by s ní
                // zmizely i filtry, takže by uživatel, který si vyfiltroval prázdno, neměl čím
                // filtr vrátit zpátky.
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(stringResource(R.string.browse_no_sources_match), color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        onClick = { onOpenSource(source.id) },
                        isFavorite = source.id in favoriteSourceIds,
                        onToggleFavorite = { viewModel.toggleFavoriteSource(source.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) Violet.copy(alpha = 0.25f) else Color.Transparent,
        animationSpec = tween(200),
        label = "chip_bg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Violet else TextSecondary,
        animationSpec = tween(200),
        label = "chip_text",
    )
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50.dp))
            .background(bgColor)
            .border(1.dp, if (selected) Violet else CardBorder, RoundedCornerShape(50.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 13.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Paleta pro monogramy zdrojů - zdroje nemají bundlované logo, takže barvu
 * odvozujeme stabilně z `source.id` (hash), aby stejný zdroj měl vždy stejnou
 * barvu napříč rekompozicemi/relaunchi. Zámerná výjimka z "jedna akcentní
 * barva" pravidla appky (viz Color.kt) - tady jde o rozlišení desítek zdrojů
 * v mřížce, ne o soupeřící duotone dekoraci.
 */
private val sourceAccentPalette = listOf(
    Accent, Pink, Success, Warning, Danger,
    Color(0xFF3B9EFF), // modrá
    Color(0xFFFF8A3B), // oranžová
    Color(0xFF2DD4BF), // tyrkysová
    Color(0xFFE05CFF), // fuchsiová
    Color(0xFFEAB308), // žlutá
)

private fun accentFor(sourceId: String): Color =
    sourceAccentPalette[(sourceId.hashCode().let { if (it < 0) -it else it }) % sourceAccentPalette.size]

@Composable
private fun contentTypeLabel(contentType: String): String = when (contentType) {
    "MANHWA" -> stringResource(R.string.browse_source_type_manhwa)
    "MANHUA" -> stringResource(R.string.browse_source_type_manhua)
    "NOVEL"  -> stringResource(R.string.browse_source_type_novel)
    "COMIC"  -> stringResource(R.string.browse_source_type_comic)
    else     -> stringResource(R.string.browse_source_type_manga)
}

/** Vlaječka podle BCP-47 kódu jazyka zdroje; neznámý kód -> 🌐 + kód velkými písmeny. */
private fun languageFlag(code: String): String = when (code.lowercase()) {
    "en" -> "🇺🇸"
    "ja" -> "🇯🇵"
    "ko" -> "🇰🇷"
    "zh", "zh-hk" -> "🇨🇳"
    "fr" -> "🇫🇷"
    "es" -> "🇪🇸"
    "pt", "pt-br" -> "🇧🇷"
    "de" -> "🇩🇪"
    "it" -> "🇮🇹"
    "tr" -> "🇹🇷"
    "ar" -> "🇸🇦"
    "id" -> "🇮🇩"
    "ru" -> "🇷🇺"
    "pl" -> "🇵🇱"
    "cs" -> "🇨🇿"
    else -> "🌐"
}

/** URL veřejné favicon služby pro doménu webu zdroje - appka žádná loga zdrojů nebundluje. */
private fun faviconUrlFor(homepageUrl: String): String {
    val domain = homepageUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")
    return "https://www.google.com/s2/favicons?domain=$domain&sz=128"
}

/** Druhá šance, když Google favicon službě chybí záznam pro doménu nebo selže. */
private fun faviconFallbackUrlFor(homepageUrl: String): String {
    val domain = homepageUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")
    return "https://icons.duckduckgo.com/ip3/$domain.ico"
}

/**
 * Ikona zdroje - skutečné logo webu (favicon), pokud se podaří stáhnout; jinak
 * barevný monogram z iniciál názvu. Google's s2 favicon služba pro spoustu domén
 * nemá záznam vůbec, proto se jako druhá šance zkouší DuckDuckGo, než se appka
 * vzdá na monogram. Monogram se schová hned, jak se podaří načíst SKUTEČNÉ logo -
 * dřív zůstával vykreslený POD faviconem porád, takže u průhledných/malých ikon
 * prosvítal kolem okrajů (nahlášeno jako "písmenka za logem").
 */
@Composable
private fun SourceIcon(source: MangaSource, size: Dp, cornerRadius: Dp, fontSize: TextUnit) {
    val initials = remember(source.name) {
        source.name.trim().split(" ")
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .take(2)
            .joinToString("")
    }
    val accent = remember(source.id) { accentFor(source.id) }
    val homepage = source.homepageUrl
    var attempt by remember(source.id) { mutableStateOf(0) }
    var loaded by remember(source.id) { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(accent.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(text = initials.ifBlank { "?" }, color = accent, fontSize = fontSize, fontWeight = FontWeight.Bold)
        }
        if (homepage != null && attempt < 2) {
            AsyncImage(
                model = if (attempt == 0) faviconUrlFor(homepage) else faviconFallbackUrlFor(homepage),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .matchParentSize()
                    .padding(size / 12)
                    .alpha(if (loaded) 1f else 0f),
                onState = { state ->
                    when (state) {
                        is AsyncImagePainter.State.Success -> loaded = true
                        is AsyncImagePainter.State.Error -> attempt = if (attempt == 0) 1 else 2
                        else -> {}
                    }
                },
            )
        }
    }
}

/**
 * Malý odznak "18+" přilepený na roh ikony zdroje - dřív stál v textovém řádku
 * s typem/jazykem, kde ho u delších názvů useklo ellipsis a u úzkých 3-sloupcových
 * karet nebyl vidět vůbec. Na ikoně je vidět vždy, bez ohledu na délku textu.
 */
@Composable
private fun BoxScope.AdultBadgeOverlay() {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .offset(x = 4.dp, y = (-4).dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Danger)
            .padding(horizontal = 3.dp, vertical = 1.dp),
    ) {
        Text(
            text = stringResource(R.string.browse_source_adult_badge),
            color = Color.White,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/**
 * Zvýrazněná karta pro karusel oblíbených zdrojů nad hlavní mřížkou - stejné
 * vizuální prvky jako [SourceCard] (monogram/favicon, typ obsahu, jazyk), ale
 * s odznakem srdíčka v rohu, aby oblíbené zdroje šly najít na první pohled
 * bez scrollování celé mřížky. Zmenšeno oproti původní verzi (150dp/48dp ikona) -
 * působilo to v karuselu nad mřížkou zbytečně objemně.
 */
@Composable
private fun FeaturedSourceCard(source: MangaSource, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(120.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NightBlue)
            .border(1.dp, Pink.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(10.dp),
    ) {
        Column {
            Box {
                SourceIcon(source = source, size = 38.dp, cornerRadius = 10.dp, fontSize = 13.sp)
                if (source.isAdult) AdultBadgeOverlay()
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = source.name,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 14.sp,
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = contentTypeLabel(source.contentType), color = TextSecondary, fontSize = 9.sp, maxLines = 1)
                Text(text = " · ${languageFlag(source.language)} ${source.language.uppercase()}", color = TextSecondary, fontSize = 9.sp, maxLines = 1)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clip(RoundedCornerShape(50))
                .background(Pink.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TablerIcons.Heart, contentDescription = null, tint = Pink, modifier = Modifier.size(11.dp))
        }
    }
}

/** Karta zdroje - monogram (barevně odlišený, viz [accentFor]), název, typ obsahu a jazyk. */
@Composable
private fun SourceCard(
    source: MangaSource,
    onClick: () -> Unit,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(NightBlue)
            .border(1.dp, if (isFavorite) Pink.copy(alpha = 0.35f) else CardBorder, RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(10.dp),
    ) {
        // Ikona a srdíčko/menu na společném řádku (samostatně, ne vedle názvu) - u
        // úzkého 3-sloupcového gridu by název vedle ikony a 48dp dotykového cíle
        // menu tlačítka neměl vůbec místo (ověřeno naživo - useklo by ho na 1 znak).
        // Typ obsahu + jazyk jsou ale spojené do jednoho řádku pod názvem místo
        // dřívějších dvou zvlášť + samostatného chip-boxu - čistší bez ztráty místa.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box {
                SourceIcon(source = source, size = 36.dp, cornerRadius = 10.dp, fontSize = 14.sp)
                if (source.isAdult) AdultBadgeOverlay()
            }
            Spacer(Modifier.weight(1f))
            if (isFavorite) {
                Icon(
                    TablerIcons.Heart,
                    contentDescription = null,
                    tint = Pink,
                    modifier = Modifier.size(12.dp).padding(end = 4.dp),
                )
            }
            Box {
                // IconButton (ne holý Icon) - vlastní clickable zastaví tap dřív, než se
                // dostane ke klikatelnému Column celé karty (jinak by klik na tři tečky
                // rovnou otevřel zdroj místo menu).
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(20.dp)) {
                    Icon(
                        TablerIcons.DotsVertical,
                        contentDescription = stringResource(R.string.browse_source_menu_desc),
                        tint = TextSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    modifier = Modifier.background(NightBlue),
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(if (isFavorite) R.string.browse_source_unfavorite else R.string.browse_source_favorite),
                                color = TextPrimary,
                            )
                        },
                        leadingIcon = { Icon(TablerIcons.Heart, contentDescription = null, tint = if (isFavorite) Pink else TextSecondary) },
                        onClick = { onToggleFavorite(); showMenu = false },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.browse_source_report), color = TextPrimary) },
                        leadingIcon = { Icon(TablerIcons.AlertCircle, contentDescription = null, tint = TextSecondary) },
                        onClick = { showMenu = false; showReportDialog = true },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = source.name,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            // minLines = maxLines = 2: nazev vzdy rezervuje misto na DVA radky bez ohledu
            // na skutecnou delku - jinak karta s jednoradkovym nazvem ("Comics Kingdom")
            // vyjde niz nez soused se dvouradkovym ("ReadFreeComicsOnline") a rady v gridu
            // nedosedaji na stejnou vysku.
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            // 18+ odznak presunut na ikonu (viz AdultBadgeOverlay) - tady drive useklo
            // ellipsis u delsich nazvu/typu a v uzkem 3-sloupcovem gridu nebyl videt vubec.
            text = "${contentTypeLabel(source.contentType)} · ${languageFlag(source.language)} ${source.language.uppercase()}",
            color = TextSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (showReportDialog) {
        val chooserTitle = stringResource(R.string.browse_report_chooser_title)
        val problemTitlesLabel = stringResource(R.string.browse_report_problem_titles)
        val problemChaptersLabel = stringResource(R.string.browse_report_problem_chapters)
        val problemErrorLabel = stringResource(R.string.browse_report_problem_error)
        val problemOtherLabel = stringResource(R.string.browse_report_problem_other)
        com.haise.jiyu.ui.components.ReportDialog(
            title = stringResource(R.string.browse_report_title, source.name),
            problems = listOf(
                "titles" to problemTitlesLabel,
                "chapters" to problemChaptersLabel,
                "error" to problemErrorLabel,
                com.haise.jiyu.ui.components.REPORT_PROBLEM_OTHER_KEY to problemOtherLabel,
            ),
            onDismiss = { showReportDialog = false },
            onSend = { problemKey, details ->
                val problemLabel = when (problemKey) {
                    "titles" -> problemTitlesLabel
                    "chapters" -> problemChaptersLabel
                    "error" -> problemErrorLabel
                    else -> problemOtherLabel
                }
                val body = buildString {
                    append("Zdroj: ${source.name} (${source.id})\n")
                    append("Problém: $problemLabel\n")
                    if (details.isNotBlank()) append("\nPopis:\n$details")
                }
                val intent = com.haise.jiyu.ui.components.buildReportEmailIntent("[Jiyu] Problém se zdrojem: ${source.name}", body)
                context.startActivity(Intent.createChooser(intent, chooserTitle))
                showReportDialog = false
            },
        )
    }
}
