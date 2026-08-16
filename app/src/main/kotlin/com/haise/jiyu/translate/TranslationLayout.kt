package com.haise.jiyu.translate

/**
 * Vypočtená pozice přeloženého bloku po expanzi směrem k okolnímu volnému
 * prostoru - viz [layoutTranslationBlocks].
 *
 * [minTopF]: horní hranice vykreslovaného boxu. Rovna [topF] pro jednořádkové bloky
 * (bez expanze nahoru - dřívější pokus tohle dělal i pro jednořádkové bloky podle
 * nejbližšího souseda, což riskovalo zbytečně vysoký box bez skutečného důvodu).
 * U bloků sloučených z víc OCR řádků (viz [TranslatedBlock.lineCount]) se posune nahoru
 * o odhad výšky jednoho řádku, protože ML Kit u víceřádkového stylizovaného písma
 * občas nahlásí boundingBox kratší, než je skutečná výška bubliny (chybí horní řádek) -
 * expanze je navíc omezená nejbližším sousedem nad blokem, aby nikdy nezasáhla cizí text.
 */
data class PositionedTranslationBlock(
    val block: TranslatedBlock,
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val maxBottomF: Float,
    val minTopF: Float = topF,
)

/**
 * OCR bounding box je většinou příliš těsný na to, aby se do něj vešel český
 * překlad (bývá delší než originál) - bez úpravy text buď přeteče přes sousední
 * bublinu (žádný limit výšky), nebo se zalomí do zbytečně mnoha úzkých řádků
 * (šířka svázaná na originál). Tahle funkce každému bloku "půjčí" volný prostor
 * kolem sebe - vodorovně symetricky kolem středu původního textu až po půlku
 * mezery k nejbližšímu sousednímu bloku ve stejné "řadě", svisle dolů až k
 * nejbližšímu bloku pod sebou - aby renderer (viz AutoFitTranslatedText v
 * ReaderScreen.kt) měl s čím pracovat při volbě šířky/velikosti písma bez
 * kolize se sousedy.
 */
/**
 * Bloky se skutečně detekovaným tvarem bubliny (viz [BubbleShapeDetector]) použijí přímo
 * ohraničující obdélník tohohle tvaru - žádná heuristická expanze k sousedům/okrajům
 * stránky, protože už víme přesně, kde bublina končí. Bloky bez tvaru (detekce selhala,
 * nebo starý cache záznam ještě nedoběhl migrací) projdou beze změny starou heuristikou
 * ([layoutHeuristic]) - viz spec docs/superpowers/specs/2026-07-24-bubble-shape-and-font-design.md.
 */
fun layoutTranslationBlocks(blocks: List<TranslatedBlock>): List<PositionedTranslationBlock> {
    val shapeBased = blocks.filter { it.shape != null }
    val heuristicBased = blocks.filter { it.shape == null }

    val shapePositioned = shapeBased.map { b ->
        val shape = b.shape!!
        PositionedTranslationBlock(
            block = b,
            leftF = shape.minOf { it.leftF },
            topF = shape.first().yF,
            rightF = shape.maxOf { it.rightF },
            maxBottomF = shape.last().yF,
            minTopF = shape.first().yF,
        )
    }

    return shapePositioned + layoutHeuristic(heuristicBased, shapePositioned)
}

/** Ohraničující obdélník - společný tvar pro sousedy z [layoutHeuristic] i z pevných tvarových bublin. */
private data class NeighborRect(val leftF: Float, val topF: Float, val rightF: Float, val bottomF: Float)

private fun TranslatedBlock.toRect() = NeighborRect(leftF, topF, rightF, bottomF)

/** Vykreslovaný (ne obalový) obdélník tvarové bubliny - přesně to, co [layoutHeuristic] nesmí přejet. */
private fun PositionedTranslationBlock.toObstacleRect() = NeighborRect(leftF, minTopF, rightF, maxBottomF)

/**
 * @param shapeObstacles bubliny s detekovaným tvarem (viz [layoutTranslationBlocks]) - jejich box
 *   je přesný a NIKDY se nezmenšuje, jen heuristické bloky kolem nich musí "obcházet". Dřív o
 *   nich tahle funkce vůbec nevěděla (sousedství se hledalo jen mezi bloky BEZ tvaru), takže
 *   heuristický box klidně expandoval skrz sousední tvarovou bublinu i kresbu za ní - viz
 *   uživatelská zpětná vazba (bílý pruh z "NO TAK." přes sousední bublinu).
 */
private fun layoutHeuristic(
    blocks: List<TranslatedBlock>,
    shapeObstacles: List<PositionedTranslationBlock> = emptyList(),
): List<PositionedTranslationBlock> {
    fun verticallyOverlaps(a: NeighborRect, b: NeighborRect) = a.topF < b.bottomF && a.bottomF > b.topF

    val obstacleRects = shapeObstacles.map { it.toObstacleRect() }

    val positioned = blocks.map { b ->
        val bRect = b.toRect()
        val peerRects = blocks.filter { it !== b }.map { it.toRect() } + obstacleRects

        val leftNeighbor = peerRects.filter { verticallyOverlaps(it, bRect) && it.rightF <= b.leftF + 0.001f }
            .maxByOrNull { it.rightF }
        val rightNeighbor = peerRects.filter { verticallyOverlaps(it, bRect) && it.leftF >= b.rightF - 0.001f }
            .minByOrNull { it.leftF }

        // Bez souseda by expandLimit spadl na 0f/1f (okraj celé stránky) - box teď fyzicky
        // vyplňuje celý vypočtený prostor (viz ReaderScreen.kt .heightIn/.width), takže
        // "žádný soused = roztáhni se přes půl stránky" už není neškodné, ale viditelná chyba.
        // Strop 3x vlastní OCR rozměr dá dost místa na kompresi překladu - ALE jen u
        // rovnoměrného pozadí (skutečná bublina, jen se jí nepodařilo najít uzavřený tvar).
        // U nerovnoměrného pozadí (titulkový/dekorativní text přímo přes kresbu, viz
        // OcrEngine.isColorUniform) je box beztak jen barevná placka, co nikdy nesplyne s
        // pestrým okolím - roztahovat ji 3x by zbytečně zakrylo mnohem víc kresby, než kolik
        // zabíral původní text (viz uživatelská zpětná vazba - hnědá placka přes titulní stránku).
        // Platí i bez souseda (izolovaný odznak/praporek) - riziko "zakrytí barevné kresby"
        // je stejné, ať už má blok souseda, nebo ne (viz BubbleTextFit.DEFAULT_MAX_ITERATIONS
        // pro řešení namačkaného textu jinou, bezpečnější cestou - přes fitter, ne přes
        // rozšiřování boxu do kresby).
        val expandFactor = if (b.bgUniform) 3f else 1.15f
        val ownWidth = b.rightF - b.leftF
        val expandLimitLeft = leftNeighbor?.let { (b.leftF + it.rightF) / 2f } ?: (b.leftF - ownWidth * expandFactor).coerceAtLeast(0f)
        val expandLimitRight = rightNeighbor?.let { (b.rightF + it.leftF) / 2f } ?: (b.rightF + ownWidth * expandFactor).coerceAtMost(1f)

        // Symetrická expanze kolem středu originálu - vizuálně stabilnější než nezávislé
        // roztažení každou stranou zvlášť (bublina pak "nesedí" mimo střed originálu).
        val center = (b.leftF + b.rightF) / 2f
        val halfWidth = minOf(center - expandLimitLeft, expandLimitRight - center)
            .coerceAtLeast((b.rightF - b.leftF) / 2f)
        val finalLeft = (center - halfWidth).coerceIn(0f, b.leftF)
        val finalRight = (center + halfWidth).coerceIn(b.rightF, 1f)

        fun horizontallyOverlaps(o: NeighborRect) = o.leftF < finalRight && o.rightF > finalLeft
        val belowNeighbor = peerRects.filter { horizontallyOverlaps(it) && it.topF >= b.bottomF - 0.001f }
            .minByOrNull { it.topF }
        // Strop odvozený z výšky JEDNOHO řádku (ne z celé výšky bloku) - u bloku sloučeného
        // z 5 OCR řádků by "3x vlastní výška" znamenalo 15 řádků volného místa, což je
        // přesně to, co způsobilo box přetékající přes zbytek stránky až za sousední SFX.
        // Stejný důvod jako u expandFactor výše - nerovnoměrné pozadí dostává jen minimální
        // rezervu, ne plných 2 řádky navíc.
        val avgLineHeightForCap = (b.bottomF - b.topF) / b.lineCount.coerceAtLeast(1)
        val verticalExpandFactor = if (b.bgUniform) 2f else 0.5f
        val maxBottom = (belowNeighbor?.let { it.topF - 0.005f } ?: (b.bottomF + avgLineHeightForCap * verticalExpandFactor))
            .coerceAtLeast(b.bottomF).coerceIn(0f, 1f)

        // Jen víceřádkové bloky (viz doc komentář [PositionedTranslationBlock.minTopF]) -
        // jednořádkový box se nikdy neroztáhne nahoru, i kdyby měl nad sebou volný prostor.
        val minTop = if (b.lineCount > 1) {
            val aboveNeighbor = peerRects.filter { horizontallyOverlaps(it) && it.bottomF <= b.topF + 0.001f }
                .maxByOrNull { it.bottomF }
            val expandLimitTop = aboveNeighbor?.let { (b.topF + it.bottomF) / 2f } ?: 0f
            val avgLineHeight = (b.bottomF - b.topF) / b.lineCount
            (b.topF - avgLineHeight * 0.6f).coerceAtLeast(expandLimitTop).coerceIn(0f, b.topF)
        } else {
            b.topF
        }

        PositionedTranslationBlock(
            block = b,
            leftF = finalLeft,
            topF = b.topF,
            rightF = finalRight,
            maxBottomF = maxBottom,
            minTopF = minTop,
        )
    }.toMutableList()

    // Řádková heuristika výše nezachytí diagonálně sousedící bloky (jeden začíná výš,
    // ale je posunutý vpravo mimo "stejnou řadu") - po prvotní expanzi ještě jednou
    // projdeme všechny dvojice a případný přesah zmenšíme, přednostně svisle (zkrácením
    // maxBottomF horního bloku), a teprve když by to zmenšilo box pod jeho původní OCR
    // rozměr, vodorovně (posunutím sdílené hranice na střed přesahu).
    repeat(2) {
        for (i in positioned.indices) {
            for (j in positioned.indices) {
                if (i == j) continue
                val a = positioned[i]; val b = positioned[j]
                val overlapX = minOf(a.rightF, b.rightF) - maxOf(a.leftF, b.leftF)
                val overlapY = minOf(a.maxBottomF, b.maxBottomF) - maxOf(a.minTopF, b.minTopF)
                if (overlapX <= 0f || overlapY <= 0f) continue

                val upperIdx = if (a.minTopF <= b.minTopF) i else j
                val lowerIdx = if (a.minTopF <= b.minTopF) j else i
                val upper = positioned[upperIdx]
                val lower = positioned[lowerIdx]
                val shrunkBottom = lower.minTopF - 0.003f

                if (shrunkBottom >= upper.block.bottomF && shrunkBottom < upper.maxBottomF) {
                    positioned[upperIdx] = upper.copy(maxBottomF = shrunkBottom)
                } else {
                    val leftIdx = if (a.leftF <= b.leftF) i else j
                    val rightIdx = if (a.leftF <= b.leftF) j else i
                    val leftB = positioned[leftIdx]
                    val rightB = positioned[rightIdx]
                    val split = (leftB.rightF + rightB.leftF) / 2f
                    positioned[leftIdx] = leftB.copy(rightF = split.coerceAtLeast(leftB.block.rightF))
                    positioned[rightIdx] = rightB.copy(leftF = split.coerceAtMost(rightB.block.leftF))
                }
            }
        }
    }

    // Stejná oprava jako výše, ale proti tvarovým bublinám (viz [shapeObstacles]) - ty se
    // NIKDY nezmenšují (jejich obrys je přesný z flood-fillu), takže ustupuje jen heuristický
    // box, a jen na tolik, kolik dovolí jeho vlastní OCR rozsah (aby si nezakryl vlastní text).
    for (idx in positioned.indices) {
        var a = positioned[idx]
        for (obstacle in obstacleRects) {
            val overlapX = minOf(a.rightF, obstacle.rightF) - maxOf(a.leftF, obstacle.leftF)
            val overlapY = minOf(a.maxBottomF, obstacle.bottomF) - maxOf(a.minTopF, obstacle.topF)
            if (overlapX <= 0f || overlapY <= 0f) continue

            val aIsAbove = a.minTopF <= obstacle.topF
            val shrunkBottom = obstacle.topF - 0.003f
            val shrunkTop = obstacle.bottomF + 0.003f
            a = when {
                aIsAbove && shrunkBottom >= a.block.bottomF -> a.copy(maxBottomF = shrunkBottom)
                !aIsAbove && shrunkTop <= a.block.topF -> a.copy(minTopF = shrunkTop)
                a.leftF <= obstacle.leftF -> a.copy(rightF = ((a.rightF + obstacle.leftF) / 2f).coerceAtLeast(a.block.rightF).coerceAtMost(a.rightF))
                else -> a.copy(leftF = ((a.leftF + obstacle.rightF) / 2f).coerceAtMost(a.block.leftF).coerceAtLeast(a.leftF))
            }
        }
        positioned[idx] = a
    }

    return positioned
}
