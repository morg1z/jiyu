package com.haise.jiyu.translate

/** Obdélník v normalizovaných (0..1) souřadnicích stránky. */
data class PatchRect(
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val bottomF: Float,
)

/**
 * Obdélník, přes který se bublina fyzicky vykreslí - a tedy i jaký kus stránky musí zakrýt.
 *
 * Existuje kvůli JEDINÉMU pravidlu: záplata (viz [TextPatchProvider]) se kreslí přesně přes
 * tenhle obdélník, takže se musí přes něj i POČÍTAT. Dokud si obojí odvozovalo souřadnice
 * samo, rozešlo se to - a rozdíl nebyl neškodný posun, ale roztažení (viz [patchPlan]).
 */
fun renderBoxRect(pos: PositionedTranslationBlock): PatchRect =
    PatchRect(
        leftF = pos.leftF,
        topF = pos.minTopF,
        rightF = pos.rightF,
        bottomF = pos.maxBottomF,
    )

/**
 * Které bloky chtějí záplatu pozadí a přes jaký obdélník stránky se má spočítat.
 * Klíčem je pozice bloku v [positioned] (ne v původním seznamu bloků).
 *
 * ## Proč se obdélník bere z rozvržení, ne z OCR boxu
 * Záplata se vykresluje přes celý box bubliny, ale počítala se z OCR boxu samotného textu.
 * Ten je vždycky menší - u bubliny s detekovaným tvarem je box celý obrys balónku, u textu
 * na kresbě ho [layoutTranslationBlocks] roztahuje do stran - takže se malá záplata natáhla
 * přes velkou plochu. Zbytky tahů, které inpainting nedočistil, se tím ZVĚTŠILY a posunuly
 * mimo své místo: uživatel je hlásil jako rozmazaný cizí text prosvítající uprostřed
 * přeložené bubliny. Naměřeno na nahlášené stránce: řádkování zbytku 88 px proti 62 px
 * v originále, tedy zvětšení 1,4x.
 *
 * ## Proč o záplatě rozhoduje jednolitost pozadí, a NE to, jestli se našel obrys
 * Nejdřív tady stála podmínka `shape == null`: našel-li flood-fill obrys, brala se bublina za
 * skutečný nakreslený balónek a místo záplaty dostala jednolitou výplň oříznutou tvarem.
 * Úvaha byla, že uvnitř obrysu je stejně jedna barva, takže je výplň od originálu
 * k nerozeznání.
 *
 * Neplatí to. Existují bubliny s VZOROVANÝM vnitřkem - třeba jemnou vlnitou texturou. Ty pro
 * flood-fill nejsou jednolitá plocha: texturní čáry se chovají jako stěna a vylévání se
 * o ně zastaví. Vyjde z toho obrys, který je menší než balónek, a jednolitá výplň přes něj
 * pak vzorek zakryje jen uprostřed - po okrajích prosvítá původní textura. Uživatel to
 * hlásil jako bílou nálepku nalepenou přes kresbu, a je to přesně ono.
 *
 * Rozhoduje proto [TranslatedBlock.bgUniform]: ptá se, jestli JE pozadí jedné barvy, kdežto
 * obrys se ptá jen, jestli se nějaký našel. Když jednolité není, kreslí se záplata - ta
 * zakryje jen tahy písmen a každý zakrytý pixel dopočítá z okolí, takže vzorek přežije.
 *
 * ## Záplata je u lettering-na-kresbě povinná, ne volitelná
 * Text bez obrysu bubliny na pestré kresbě (titulkové captiony, ručně kreslené nápisy)
 * se SMÍ překreslit - ale výhradně záplatou: originální tahy se smažou dopočítáním z okolí
 * a překlad se napíše přes ně barvou původního písma (viz meanTextArgbOut v
 * [buildTextPatch]). Pevná výplň by tam udělala viditelnou placku přes malbu (nahlášené
 * "THE BATTLE OF SEKIGAHARA" na akvarelu sněhu), proto render takový blok bez záplaty
 * přeskočí a nechá prosvítat originál - viz pravidlo `art_lettering` v BubbleOverlayLayer.
 *
 * Bez opravy geometrie záplaty (viz odstavec výše) by tohle nešlo: roztažené zbytky tahů
 * byly původní důvod, proč záplata u bublin s obrysem skončila.
 */
fun patchPlan(positioned: List<PositionedTranslationBlock>): Map<Int, PatchRect> =
    positioned.withIndex()
        .filter { (_, pos) -> pos.block.needsPatch() }
        .associate { (index, pos) -> index to renderBoxRect(pos) }

private fun TranslatedBlock.needsPatch(): Boolean =
    !isSfx && !isUntranslated && !isArtText && !bgUniform
