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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.haise.jiyu.R
import com.haise.jiyu.source.MangaSource
import com.haise.jiyu.ui.components.JiyuWordmark
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
                    JiyuWordmark()

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
    return "https://www.google.com/s2/favicons?domain=$domain&sz=64"
}

/**
 * Zvýrazněná karta pro karusel oblíbených zdrojů nad hlavní mřížkou - stejné
 * vizuální prvky jako [SourceCard] (monogram/favicon, typ obsahu, jazyk), ale
 * větší a s odznakem srdíčka v rohu, aby oblíbené zdroje šly najít na první
 * pohled bez scrollování celé mřížky.
 */
@Composable
private fun FeaturedSourceCard(source: MangaSource, onClick: () -> Unit) {
    val initials = remember(source.name) {
        source.name.trim().split(" ")
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .take(2)
            .joinToString("")
    }
    val accent = remember(source.id) { accentFor(source.id) }

    Box(
        modifier = Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(NightBlue)
            .border(1.dp, Pink.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(12.dp),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = initials.ifBlank { "?" }, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                source.homepageUrl?.let { homepage ->
                    var showFavicon by remember(source.id) { mutableStateOf(false) }
                    AsyncImage(
                        model = faviconUrlFor(homepage),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .matchParentSize()
                            .padding(4.dp)
                            .alpha(if (showFavicon) 1f else 0f),
                        onState = { state -> showFavicon = state is AsyncImagePainter.State.Success },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = source.name,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 15.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                Text(text = contentTypeLabel(source.contentType), color = TextSecondary, fontSize = 10.sp, maxLines = 1)
                Text(text = " · ${languageFlag(source.language)} ${source.language.uppercase()}", color = TextSecondary, fontSize = 10.sp, maxLines = 1)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(RoundedCornerShape(50))
                .background(Pink.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TablerIcons.Heart, contentDescription = null, tint = Pink, modifier = Modifier.size(12.dp))
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
    val initials = remember(source.name) {
        source.name.trim().split(" ")
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() }
            .take(2)
            .joinToString("")
    }
    val accent = remember(source.id) { accentFor(source.id) }
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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials.ifBlank { "?" },
                    color = accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                // Favicona webu zdroje přes veřejnou službu (zdroje nemají bundlované
                // logo) - dokud se nenačte (nebo web faviconu nemá/blokuje), zůstává
                // vidět barevný monogram pod ní, žádné bliknutí prázdného místa.
                source.homepageUrl?.let { homepage ->
                    var showFavicon by remember(source.id) { mutableStateOf(false) }
                    AsyncImage(
                        model = faviconUrlFor(homepage),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .matchParentSize()
                            .padding(3.dp)
                            .alpha(if (showFavicon) 1f else 0f),
                        onState = { state -> showFavicon = state is AsyncImagePainter.State.Success },
                    )
                }
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
