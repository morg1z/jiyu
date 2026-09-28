package com.haise.jiyu.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.haise.jiyu.util.AggregateSection
import compose.icons.TablerIcons
import compose.icons.tablericons.Bolt
import compose.icons.tablericons.CircleCheck
import compose.icons.tablericons.Clock
import compose.icons.tablericons.Crown
import compose.icons.tablericons.Flame
import compose.icons.tablericons.Stars
import compose.icons.tablericons.Tag
import compose.icons.tablericons.TrendingUp

// ── Gradientni nadpisy sekci agregovanych Domu obrazovek ─────────────────────
// Vizualni vzor ComicK webu: zelena "Recently Added", modra "Completed",
// oranzova "Popular New", fialova "Most Recent Popular". Kazdy typ sekce ma
// vlastni dvoubarevny horizontalni gradient + malou ikonku ve startovni barve.
// Sdilene mezi ComicK/Novel/Komiks Domů, at se paleta nerozjede.

/** Styl nadpisu sekce: dvojice barev pro gradient textu + ikonka tónovaná první barvou. */
internal data class SectionTitleStyle(val colors: List<Color>, val icon: ImageVector?) {
    val brush: Brush get() = Brush.horizontalGradient(colors)
    val iconTint: Color get() = colors.first()
}

internal val PopularTitleStyle   = SectionTitleStyle(listOf(Color(0xFFFB923C), Color(0xFFEF4444)), TablerIcons.Flame)
internal val LatestTitleStyle    = SectionTitleStyle(listOf(Color(0xFF4ADE80), Color(0xFF14B8A6)), TablerIcons.Clock)
internal val UpdatesTitleStyle   = SectionTitleStyle(listOf(Color(0xFF22D3EE), Color(0xFF8B5CF6)), TablerIcons.Bolt)
internal val CompletedTitleStyle = SectionTitleStyle(listOf(Color(0xFF64B5F6), Color(0xFF2563EB)), TablerIcons.CircleCheck)
internal val LongestTitleStyle   = SectionTitleStyle(listOf(Color(0xFFFBBF24), Color(0xFFF59E0B)), TablerIcons.Crown)
internal val TrendingTitleStyle  = SectionTitleStyle(listOf(Color(0xFFC084FC), Color(0xFF8B5CF6)), TablerIcons.TrendingUp)
internal val ReviewsTitleStyle   = SectionTitleStyle(listOf(Color(0xFFF472B6), Color(0xFFDB2777)), TablerIcons.Stars)

/** Žánrové řady dostávají gradient podle hashu názvu - stabilní barva pro stejný
 * žánr, různorodé napříč sekcemi. */
private val GenreGradientColors = listOf(
    listOf(Color(0xFFC084FC), Color(0xFF8B5CF6)),   // fialová
    listOf(Color(0xFFF472B6), Color(0xFFDB2777)),   // růžová
    listOf(Color(0xFF2DD4BF), Color(0xFF0D9488)),   // teal
    listOf(Color(0xFF818CF8), Color(0xFF4F46E5)),   // indigo
)

internal fun genreTitleStyle(genre: String): SectionTitleStyle =
    SectionTitleStyle(GenreGradientColors[kotlin.math.abs(genre.lowercase().hashCode()) % GenreGradientColors.size], TablerIcons.Tag)

/** Styl odvozené sekce (Dokončené / Nejdelší / žánr) sdílený Novel+Komiks Domů. */
internal fun aggregateSectionTitleStyle(section: AggregateSection<*>): SectionTitleStyle = when (section.kind) {
    AggregateSection.Kind.COMPLETED -> CompletedTitleStyle
    AggregateSection.Kind.LONGEST -> LongestTitleStyle
    AggregateSection.Kind.GENRE -> genreTitleStyle(section.genre.orEmpty())
}
