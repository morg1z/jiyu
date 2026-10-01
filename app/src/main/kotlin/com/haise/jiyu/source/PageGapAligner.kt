package com.haise.jiyu.source

/**
 * Čisté (pure JVM) operace pro cross-source gap fill - oddělené od
 * [PageGapFiller], aby šly testovat bez DI/Android.
 */
object PageGapAligner {

    /**
     * Donor používá stejné číslování souborů: pro každou mezeru musí obsahovat
     * stránky s chybějícími čísly A oba straně sousedy s jejich čísly (jinak by
     * "145" mohlo být náhodné číslo jiné řady). Vrací mapu
     * insertIndex → indexy donor stránek k vložení, nebo null když kterákoli
     * mezera nejde ověřit (částečné číselné doplnění nechceme - donor se pak
     * zkouší kontentově, nebo se vzdá).
     */
    fun alignByNumbers(
        gaps: List<PageGapDetector.PageGap>,
        sourceUrls: List<String>,
        donorUrls: List<String>,
    ): Map<Int, List<Int>>? {
        val srcNums = sourceUrls.map { PageGapDetector.stemNumber(it) }
        val donorNums = donorUrls.map { PageGapDetector.stemNumber(it) }
        val donorIndexByNumber = HashMap<Int, Int>()
        donorNums.forEachIndexed { i, n -> if (n != null) donorIndexByNumber.putIfAbsent(n, i) }

        val result = HashMap<Int, List<Int>>()
        for (gap in gaps) {
            val prevNum = srcNums.getOrNull(gap.insertIndex - 1) ?: return null
            val nextNum = srcNums.getOrNull(gap.insertIndex) ?: return null
            val prevIdx = donorIndexByNumber[prevNum] ?: return null
            val nextIdx = donorIndexByNumber[nextNum] ?: return null
            val between = nextIdx - prevIdx - 1
            if (between <= 0 || between != gap.missingCount) return null
            // Skutečně sedí na chybějící čísla (ne náhodný interval stejné délky).
            val nums = (prevIdx + 1 until nextIdx).map { donorNums[it] }
            if (nums.any { it == null } || nums.first() != gap.missingNumbers.first ||
                nums.last() != gap.missingNumbers.last) return null
            result[gap.insertIndex] = (prevIdx + 1 until nextIdx).toList()
        }
        return result
    }

    /**
     * Vsune [insertions] (klíč = index v původním seznamu, před který se vkládá)
     * do [pages] a přečísluje `Page.index` pozičně (LazyPageFetcher ho předává
     * `getImageUrl`, takže musí sedět na pořadí ve VÝSLEDNÉM seznamu).
     */
    fun splice(pages: List<Page>, insertions: Map<Int, List<Page>>): List<Page> {
        if (insertions.isEmpty()) return pages
        val out = ArrayList<Page>(pages.size + insertions.values.sumOf { it.size })
        pages.forEachIndexed { i, p ->
            insertions[i]?.let { out.addAll(it) }
            out.add(p)
        }
        return out.mapIndexed { i, p -> Page(index = i, url = p.url, imageUrl = p.imageUrl) }
    }
}
