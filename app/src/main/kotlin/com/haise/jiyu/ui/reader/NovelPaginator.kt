package com.haise.jiyu.ui.reader

import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp

/** Jedna stránka kapitoly - rozsah znaků do PŮVODNÍHO textu kapitoly, ne kopie textu. */
data class NovelPage(val startIndex: Int, val endIndex: Int)

/** Jeden zalomený řádek textu - kde končí (exclusive index) a jak je vysoký v px. */
data class LineInfo(val endIndex: Int, val heightPx: Float)

/**
 * Abstrakce nad zalomením textu na řádky. Produkční implementace ([ComposeTextLayoutProvider])
 * obalí Compose [TextMeasurer] (potřebuje reálný font-resolving engine, nejde spustit v čistém
 * JVM testu bez Robolectricu). V testech nahrazena deterministickou fake implementací.
 */
fun interface TextLayoutProvider {
    fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo>
}

/**
 * Rozseká předem zalomené řádky do stránek podle výškového rozpočtu [availableHeightPx].
 * Řádek, který sám o sobě přesahuje [availableHeightPx] (extrémně velký font), dostane
 * vlastní stránku místo nekonečného čekání na místo, které nikdy nepřijde.
 */
fun packLinesIntoPages(lines: List<LineInfo>, availableHeightPx: Float): List<NovelPage> {
    if (lines.isEmpty()) return listOf(NovelPage(0, 0))

    val pages = mutableListOf<NovelPage>()
    var pageStart = 0
    var heightUsed = 0f

    lines.forEachIndexed { i, line ->
        val lineStart = if (i == 0) 0 else lines[i - 1].endIndex
        val wouldExceed = heightUsed + line.heightPx > availableHeightPx
        if (wouldExceed && heightUsed > 0f) {
            pages += NovelPage(pageStart, lineStart)
            pageStart = lineStart
            heightUsed = 0f
        }
        heightUsed += line.heightPx
    }
    pages += NovelPage(pageStart, lines.last().endIndex)
    return pages
}

/**
 * Rozseká [text] na stránky, které se vejdou do [availableWidthPx] x [availableHeightPx]
 * při dané velikosti fontu. Jediné volání [TextLayoutProvider.layoutLines] - řádkování se
 * počítá jednou, ne opakovaně po stránkách.
 */
fun paginateNovelText(
    text: String,
    textLayoutProvider: TextLayoutProvider,
    availableWidthPx: Float,
    availableHeightPx: Float,
    fontSizeSp: Float,
): List<NovelPage> {
    if (text.isEmpty()) return listOf(NovelPage(0, 0))
    val lines = textLayoutProvider.layoutLines(text, availableWidthPx, fontSizeSp)
    return packLinesIntoPages(lines, availableHeightPx)
}

/**
 * Najde index stránky obsahující znak [offset] - použito pro zachování pozice čtenáře
 * při repaginaci (změna fontu, otočení obrazovky, ...).
 */
fun findPageIndexForOffset(pages: List<NovelPage>, offset: Int): Int {
    if (pages.isEmpty()) return 0
    val idx = pages.indexOfFirst { offset >= it.startIndex && offset < it.endIndex }
    if (idx >= 0) return idx
    return pages.lastIndex
}

/** Reálná implementace [TextLayoutProvider] přes Compose [TextMeasurer] - běží jen na
 * zařízení/emulátoru (potřebuje reálný font-resolving engine Androidu). */
class ComposeTextLayoutProvider(
    private val textMeasurer: TextMeasurer,
    private val baseStyle: TextStyle,
) : TextLayoutProvider {
    override fun layoutLines(text: String, availableWidthPx: Float, fontSizeSp: Float): List<LineInfo> {
        if (text.isEmpty()) return emptyList()
        val style = baseStyle.copy(fontSize = fontSizeSp.sp)
        val result: TextLayoutResult = textMeasurer.measure(
            text = text,
            style = style,
            constraints = Constraints(maxWidth = availableWidthPx.toInt().coerceAtLeast(1)),
        )
        return (0 until result.lineCount).map { i ->
            LineInfo(
                endIndex = result.getLineEnd(i),
                heightPx = result.getLineBottom(i) - result.getLineTop(i),
            )
        }
    }
}
