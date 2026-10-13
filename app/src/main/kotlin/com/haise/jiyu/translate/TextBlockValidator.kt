package com.haise.jiyu.translate

/**
 * Validace "je tenhle OCR blok opravdu TEXT?" - levný procedurální filtr proti
 * halucinacím ML Kit detekce na čisté kresbě.
 *
 * Proč to existuje: ML Kit recognizer detekuje i čte zároveň - na kresbě bez
 * lettering (šrafování, rastrové pole, náplň obličeje) může vrátit blok s textem
 * jako "::新::.:這鲜重" (audit Vagabond ch.6 p17). Script-aware autodetekce
 * ([scriptCharCount]) řeší jen KTERÝ jazyk šum přečte, ne ŽE šum vůbec je - po
 * opravě jazyka pořád vznikne patch přes kresbu, jen s jiným nesmyslným textem.
 *
 * Koharu tenhle problém nemá, protože text regiony dává ML DETEKTOR a OCR jen čte
 * uvnitř nich. Tohle je levná procedurální náhrada: skutečný lettering má
 * opakovanou strukturu malých komponent (glyphy), zatímco
 * - šrafování/hatching = několik dlouhých tenkých paralelních tahů,
 * - rastrové pole = obrovská hustota maskovaných pixelů,
 * - náplň kresby = pár velkých blobů.
 *
 * Filtr je KONZERVATIVNÍ (raději propustit podezřelý blok než zahodit skutečný
 * text): odmítá jen kombinace, které lettering nedávají. Malé boxy se vůbec
 * nehodnotí - "!", "?", "…" nemají analyzovatelnou strukturu.
 */

/** Komponenty pod tuhle velikost boxu se vůbec nehodnotí ("!", "…"). */
private const val MIN_BOX_DIM_PX = 24
private const val MIN_BOX_AREA_PX = 1200

/** Maska pokrývající víc než tolik % boxu = pole textury, ne řada glyphů. */
private const val MAX_MASK_COVERAGE_PERCENT = 60

/** Aspekt komponenty, od kterého se počítá jako "dlouhý tah" (hatching). */
private const val ELONGATED_ASPECT = 8

/** Tolik dlouhých tahů s tolik% pokrytí = paralelní šrafování, ne text. */
private const val MIN_ELONGATED_COMPONENTS = 4
private const val MAX_ELONGATED_COVERAGE_PERCENT = 55

/** Skoro prázdný box: málo komponent a skoro žádný inkoust. */
private const val MIN_COMPONENTS_FOR_SPARSE = 3
private const val MAX_SPARSE_COVERAGE_PERCENT = 2

/**
 * Pozn.: jediný velký blob v boxu (náplň kresby, obličej) se ZÁMĚRNĚ nezamítá -
 * markTextPixels označí jen jeho okrajový prstenec (interiér nemá lokální kontrast),
 * takže vzniklá maska je malá a patch škodí minimálně. A velký plný glyph
 * (dekorativní SFX "!", velké kanji) je legitimní lettering - zamítání blobů by
 * ho zabíjelo taky.
 */

private class Component(
    var size: Int = 0,
    var minX: Int = Int.MAX_VALUE,
    var minY: Int = Int.MAX_VALUE,
    var maxX: Int = Int.MIN_VALUE,
    var maxY: Int = Int.MIN_VALUE,
) {
    val aspect: Float
        get() {
            val cw = (maxX - minX + 1).toFloat()
            val ch = (maxY - minY + 1).toFloat()
            return maxOf(cw / ch, ch / cw)
        }
}

/**
 * @return true = blok vypadá jako text; false = struktura inkoustu v boxu je
 *   jiná než lettering (volající má blok zahodit před merge/překladem).
 */
internal fun isPlausibleTextBlock(
    source: PixelSource,
    pageW: Int,
    pageH: Int,
    leftF: Float,
    topF: Float,
    rightF: Float,
    bottomF: Float,
    onReject: (String) -> Unit = {},
): Boolean {
    val l = (leftF * pageW).toInt().coerceIn(0, pageW - 1)
    val t = (topF * pageH).toInt().coerceIn(0, pageH - 1)
    val r = (rightF * pageW).toInt().coerceIn(l + 1, pageW)
    val b = (bottomF * pageH).toInt().coerceIn(t + 1, pageH)
    val bw = r - l
    val bh = b - t
    // Malé boxy nejdou spolehlivě analyzovat - raději propustit.
    if (bw < MIN_BOX_DIM_PX || bh < MIN_BOX_DIM_PX || bw * bh < MIN_BOX_AREA_PX) return true

    val lum = IntArray(bw * bh)
    for (y in 0 until bh) {
        for (x in 0 until bw) {
            lum[y * bw + x] = luminanceOf(source.colorAt(l + x, t + y))
        }
    }
    val mask = markTextPixels(lum, bw, bh)

    // Souvislé komponenty (4-conn) se statistikami.
    val compId = IntArray(bw * bh) { -1 }
    val comps = ArrayList<Component>()
    val queue = IntArray(bw * bh)
    for (start in 0 until bw * bh) {
        if (!mask[start] || compId[start] >= 0) continue
        val comp = Component()
        val id = comps.size
        comps += comp
        var head = 0
        var tail = 0
        queue[tail++] = start
        compId[start] = id
        while (head < tail) {
            val i = queue[head++]
            val x = i % bw
            val y = i / bw
            comp.size++
            if (x < comp.minX) comp.minX = x
            if (x > comp.maxX) comp.maxX = x
            if (y < comp.minY) comp.minY = y
            if (y > comp.maxY) comp.maxY = y
            if (x > 0 && mask[i - 1] && compId[i - 1] < 0) { compId[i - 1] = id; queue[tail++] = i - 1 }
            if (x < bw - 1 && mask[i + 1] && compId[i + 1] < 0) { compId[i + 1] = id; queue[tail++] = i + 1 }
            if (y > 0 && mask[i - bw] && compId[i - bw] < 0) { compId[i - bw] = id; queue[tail++] = i - bw }
            if (y < bh - 1 && mask[i + bw] && compId[i + bw] < 0) { compId[i + bw] = id; queue[tail++] = i + bw }
        }
    }
    if (comps.isEmpty()) {
        onReject("no_ink")
        return false
    }

    var masked = 0
    var elongatedCount = 0
    var elongatedPx = 0
    for (c in comps) {
        masked += c.size
        if (c.aspect >= ELONGATED_ASPECT) {
            elongatedCount++
            elongatedPx += c.size
        }
    }
    val area = bw * bh
    val coveragePct = masked * 100 / area
    val elongatedPct = elongatedPx * 100 / masked.coerceAtLeast(1)

    // 1) Maska žere skoro celý box = adaptivní práh označil pole textury za
    //    "text" (šrafování, rastr, slunce) - lettering nikdy nenasytí tolik %.
    if (coveragePct > MAX_MASK_COVERAGE_PERCENT) {
        onReject("texture_field coverage=$coveragePct%")
        return false
    }
    // 2) Několik dlouhých tenkých tahů nesoucích většinu masky = paralelní
    //    šrafování / vrásčky tkaniny / meč. Glyphy jsou kompaktní.
    if (elongatedCount >= MIN_ELONGATED_COMPONENTS && elongatedPct > MAX_ELONGATED_COVERAGE_PERCENT) {
        onReject("parallel_hatching n=$elongatedCount cover=$elongatedPct%")
        return false
    }
    // 3) Skoro žádný inkoust - pár šumových fleků.
    if (comps.size < MIN_COMPONENTS_FOR_SPARSE && coveragePct < MAX_SPARSE_COVERAGE_PERCENT) {
        onReject("near_empty n=${comps.size} coverage=$coveragePct%")
        return false
    }
    return true
}
