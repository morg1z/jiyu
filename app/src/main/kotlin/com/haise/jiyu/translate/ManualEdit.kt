package com.haise.jiyu.translate

/**
 * Identita ručně opravené bubliny napříč přepočty překladu.
 *
 * Ne pořadí bloku - OCR může příště najít jiný počet bublin a indexy se posunou, takže by se
 * oprava naparovala na cizí text. Identitou je PŮVODNÍ text bubliny: vzniká rozpoznáním pořád
 * stejného obrázku, takže je ze všeho dostupného nejstabilnější.
 *
 * Text se před porovnáním normalizuje - OCR mezi běhy kolísá v mezerách a zalomení řádků
 * (dvouřádková bublina se přečte jednou jako "AB\nCD", podruhé jako "AB CD"), což je rozdíl,
 * který o jinou bublinu nejde. Velikost písmen se ZACHOVÁVÁ: "NE." a "ne." můžou být dvě
 * různé bubliny na téže stránce.
 */
fun manualEditId(chapterId: String, pageIndex: Int, originalText: String): String =
    "$chapterId::$pageIndex::${normalizeOriginal(originalText)}"

/** Sjednotí mezery a zalomení - viz [manualEditId]. */
internal fun normalizeOriginal(text: String): String =
    text.trim().replace(Regex("\\s+"), " ")

/**
 * Naparuje ruční opravy na čerstvě přeložené bloky.
 *
 * Bloky, ke kterým oprava není, zůstávají beze změny. Opravy, ke kterým se nenašel blok
 * (OCR přečetlo stránku jinak), se tiše ignorují - nikdy se nesmí přiřadit "nejbližší" bloku,
 * to by přepsalo cizí bublinu.
 *
 * @param edits mapa `normalizovaný původní text -> ruční text`
 */
fun applyManualEdits(blocks: List<TranslatedBlock>, edits: Map<String, String>): List<TranslatedBlock> {
    if (edits.isEmpty()) return blocks
    return blocks.map { block ->
        val manual = edits[normalizeOriginal(block.originalText)] ?: return@map block
        // displayText nese měkké rozdělovníky ze strojového překladu; ruční text žádné nemá,
        // takže se nastavuje na tutéž hodnotu - jinak by se dál zalamoval podle staré verze.
        block.copy(translatedText = manual, displayText = manual, isUntranslated = false)
    }
}

/**
 * Novel varianta [applyManualEdits] - přeložená kapitola je prostý `String`, ne seznam bloků
 * s vlastním `originalText`, takže se identita (normalizovaný původní text) hledá podle POZICE
 * v [originalParagraphs] místo v datovém modelu bloku. Rozjetý počet odstavců (pipeline mezitím
 * rozdělila text jinak) znamená, že indexy už bezpečně neodpovídají - radši nic nepřepsat
 * naslepo, stejný princip jako u bublin.
 *
 * @param edits mapa `normalizovaný původní text odstavce -> ruční text`
 */
fun applyManualEditsToNovelParagraphs(
    translatedParagraphs: List<String>,
    originalParagraphs: List<String>,
    edits: Map<String, String>,
): List<String> {
    if (edits.isEmpty() || translatedParagraphs.size != originalParagraphs.size) return translatedParagraphs
    return translatedParagraphs.mapIndexed { i, translated ->
        edits[normalizeOriginal(originalParagraphs[i])] ?: translated
    }
}

/**
 * Naparuje uložené ruční posuny pozice (viz [com.haise.jiyu.data.db.entity.ManualTranslationEntity.offsetXDp]/
 * `offsetYDp`) na čerstvě přeložené bloky - stejná identita (normalizovaný původní text) jako
 * [applyManualEdits], ale SAMOSTATNÁ funkce/mapa, aby původní text-only cesta (a její testy)
 * zůstaly beze změny - posun a text jsou na sobě nezávislé (viz komentář u entity).
 */
internal fun applyManualPositionOffsets(
    blocks: List<TranslatedBlock>,
    offsets: Map<String, Pair<Float, Float>>,
): List<TranslatedBlock> {
    if (offsets.isEmpty()) return blocks
    return blocks.map { block ->
        val (offsetX, offsetY) = offsets[normalizeOriginal(block.originalText)] ?: return@map block
        block.copy(offsetXDp = offsetX, offsetYDp = offsetY)
    }
}
