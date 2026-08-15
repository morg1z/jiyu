package com.haise.jiyu.translate

/**
 * Abstrakce nad zdrojem pixelů - viz spec docs/superpowers/specs/2026-07-24-bubble-shape-and-font-design.md.
 * Odděluje algoritmus od android.graphics.Bitmap, aby šel testovat čistým JVM testem.
 */
fun interface PixelSource {
    /** ARGB pixel na (x, y); mimo hranice smí vrátit cokoliv, volající si hranice hlídá sám. */
    fun colorAt(x: Int, y: Int): Int
}

/** Jeden vzorkovaný bod obrysu bubliny - normalizované (0..1) souřadnice jako zbytek kódu (leftF/topF). */
data class BubbleShapePoint(val yF: Float, val leftF: Float, val rightF: Float)

/**
 * Najde skutečný obrys bubliny flood-fillem od bodů na jejím pozadí (NE od středu OCR
 * textu - ten často padne na tmavý pixel písma, ne na pozadí; volající by měl posílat
 * body, o kterých už ví, že leží na pozadí - viz OcrEngine.ringSeeds).
 */
object BubbleShapeDetector {

    private const val SAMPLE_COUNT = 24

    /**
     * Strop velikosti stránky, na které se detekce vůbec pokusí běžet (v pixelech).
     * Nad ním se rovnou vrátí null a použije se heuristický obdélník - u takhle obřích
     * obrázků (nekonečné webtoon pásy) nemá smysl alokovat ani bitovou mapu.
     */
    private const val MAX_PAGE_PIXELS = 64L * 1024 * 1024

    /** Počáteční kapacita fronty - roste zdvojnásobením, drží jen aktuální "čelo" vlny. */
    private const val INITIAL_QUEUE_CAPACITY = 4096

    /**
     * Kolikrát smí být obalový obdélník nalezeného obrysu větší než OCR box textu uvnitř.
     *
     * Plošný limit [detectShape] parametru `maxAreaFraction` je vztažený ke CELÉ STRÁNCE, a to
     * je u vysokých stránek obrovská rezerva: čtvrtina stránky 1440x3120 je přes milion pixelů.
     * Flood-fill, který unikl z bubliny do tmavé kresby, se do ní pohodlně vejde - a výplň pak
     * přemaluje půl panelu jednou barvou. Nahlášeno u vodoznaku skenlační skupiny na tmavém
     * pruhu: text zabíral kousek rohu, ale plocha kolem něj byla souvisle tmavá, takže se
     * vylitím spojila přes celý panel.
     *
     * Změřeno na nahlášené stránce (ML Kit na zařízení, obalový obdélník obrysu proti OCR boxu):
     *   "MOUNTAIN BEASTS..."        2,7x
     *   "GOOD HEAVENS, IT'S A TRAP!" 4,3x
     *   "DAMN..." (jedno slovo v kulaté bublině) 16,1x
     *   vodoznak "SIRENSCANS.COM"   54x až 216x podle rozlišení
     * Skutečné bubliny tedy končí u 16x, uniklé vylití začíná nad 54x. Práh 30x leží mezi nimi
     * s rezervou na obě strany. Když se překročí, vrátí se null a použije se heuristický
     * obdélník - horší odhad tvaru, ale nikdy ne placka přes kresbu.
     */
    private const val MAX_SHAPE_TO_TEXT_AREA_RATIO = 30L

    /**
     * BFS flood-fill (fronta, ne rekurze - kvůli velkým bublinám a JVM stack limitu).
     *
     * Navštívené pixely drží BITOVÁ MAPA (jeden bit na pixel) a fronta je primitivní IntArray
     * se zabalenou souřadnicí `y * width + x`. Původní `HashSet<Long>` + `ArrayDeque<Pair<Int, Int>>`
     * znamenaly na každý navštívený pixel zabalený `Long` a k němu uzel hashovací tabulky -
     * tedy zhruba padesát bajtů na pixel místo jednoho bitu.
     *
     * Změřeno na syntetické stránce bez uzavřeného obrysu (flood-fill běží až do plošného
     * limitu): 1440x3120 spotřebovalo 169 MB, 1440x9000 dokonce 245 MB - a to na JEDINOU
     * bublinu, přičemž stránky se zpracovávají po třech souběžně (viz OCR_PARALLELISM) a na
     * stránce je bublin víc. Na telefonu to appku spolehlivě položilo, a tiše: OutOfMemoryError
     * v tomhle režimu končí zabitím procesu systémem, ne hláškou (viz uživatelská zpětná vazba
     * "u překladu se appka normálně vypne"). Plošný limit [maxAreaFraction] přitom fungoval
     * správně - jen se kontroloval AŽ potom, co se všechna ta paměť naalokovala.
     *
     * @param textAreaPx plocha OCR boxu textu v pixelech; > 0 zapne kontrolu
     *   [MAX_SHAPE_TO_TEXT_AREA_RATIO] (viz tam), 0 ji vypne
     * @param onRatioMeasured pozorovací hák: zavolá se právě jednou, KDYŽ [textAreaPx] > 0
     *   a nalezený obrys má platný obalový obdélník - s reálným poměrem tvar/text a tím, jestli
     *   prošel [MAX_SHAPE_TO_TEXT_AREA_RATIO]. Slouží k nasbírání reálné distribuce poměru z
     *   běžného čtení (volající zaloguje), aby šel práh 30x časem doladit na datech, ne na
     *   dalším odhadu - viz komentář u [MAX_SHAPE_TO_TEXT_AREA_RATIO]. Nic v návratové hodnotě
     *   nemění, čistě observabilita.
     * @return null když detekce selhala/vypadá nedůvěryhodně (žádný platný seed,
     *   navštívená plocha přesáhla [maxAreaFraction] celé stránky - typicky text přímo
     *   na kresbě/SFX bez uzavřeného pozadí - nebo je nalezený obrys nesmyslně velký proti
     *   textu uvnitř) - volající pak použije starý heuristický obdélník.
     */
    fun detectShape(
        source: PixelSource,
        width: Int,
        height: Int,
        seeds: List<Pair<Int, Int>>,
        bgColorArgb: Int,
        colorDistanceThreshold: Int = 40,
        maxAreaFraction: Float = 0.25f,
        textAreaPx: Long = 0,
        onRatioMeasured: (ratio: Double, accepted: Boolean) -> Unit = { _, _ -> },
    ): List<BubbleShapePoint>? {
        if (width <= 0 || height <= 0) return null
        val totalPixels = width.toLong() * height.toLong()
        if (totalPixels > MAX_PAGE_PIXELS) return null
        val maxArea = (totalPixels * maxAreaFraction).toLong()

        val validSeeds = seeds.filter { (x, y) ->
            x in 0 until width && y in 0 until height &&
                colorDistance(source.colorAt(x, y), bgColorArgb) < colorDistanceThreshold
        }
        if (validSeeds.isEmpty()) return null

        // Jeden bit na pixel: u 1440x3120 je to 549 kB místo 169 MB.
        val visited = LongArray(((totalPixels + 63) / 64).toInt())
        var queue = IntArray(INITIAL_QUEUE_CAPACITY)
        var head = 0
        var tail = 0

        // Min/max x pro každý řádek v IntArray místo HashMap<Int, IntArray> - žádné boxování klíčů.
        val rowMin = IntArray(height) { Int.MAX_VALUE }
        val rowMax = IntArray(height) { Int.MIN_VALUE }

        for ((sx, sy) in validSeeds) {
            val index = sy * width + sx
            val word = index ushr 6
            val bit = 1L shl (index and 63)
            if (visited[word] and bit == 0L) {
                visited[word] = visited[word] or bit
                if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                queue[tail++] = index
            }
        }

        var area = 0L
        var topY = Int.MAX_VALUE
        var bottomY = Int.MIN_VALUE

        while (head < tail) {
            val index = queue[head++]
            val y = index / width
            val x = index - y * width
            area++
            if (area > maxArea) return null

            if (x < rowMin[y]) rowMin[y] = x
            if (x > rowMax[y]) rowMax[y] = x
            if (y < topY) topY = y
            if (y > bottomY) bottomY = y

            // Sousedé rozepsaní natvrdo - pole dvojic offsetů by na každý pixel alokovalo
            // destrukturované Pair objekty, což je přesně ta zbytečná zátěž, kvůli které
            // se tenhle algoritmus přepisoval.
            if (x > 0) {
                val n = index - 1
                if (visit(source, visited, x - 1, y, n, bgColorArgb, colorDistanceThreshold)) {
                    if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                    queue[tail++] = n
                }
            }
            if (x < width - 1) {
                val n = index + 1
                if (visit(source, visited, x + 1, y, n, bgColorArgb, colorDistanceThreshold)) {
                    if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                    queue[tail++] = n
                }
            }
            if (y > 0) {
                val n = index - width
                if (visit(source, visited, x, y - 1, n, bgColorArgb, colorDistanceThreshold)) {
                    if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                    queue[tail++] = n
                }
            }
            if (y < height - 1) {
                val n = index + width
                if (visit(source, visited, x, y + 1, n, bgColorArgb, colorDistanceThreshold)) {
                    if (tail == queue.size) queue = queue.copyOf(queue.size * 2)
                    queue[tail++] = n
                }
            }

            // Už zpracovaný začátek fronty se zahodí, jakmile je ho víc než polovina - bez
            // toho by pole rostlo podle CELKOVÉHO počtu vložených pixelů, ne podle čela vlny.
            if (head > INITIAL_QUEUE_CAPACITY && head * 2 > tail) {
                queue.copyInto(queue, 0, head, tail)
                tail -= head
                head = 0
            }
        }

        if (topY == Int.MAX_VALUE || bottomY <= topY) return null

        val sortedRows = ArrayList<Int>()
        for (y in topY..bottomY) if (rowMin[y] != Int.MAX_VALUE) sortedRows.add(y)
        if (sortedRows.isEmpty()) return null

        // Obrys nesmyslně velký proti textu, který má obepínat - viz [MAX_SHAPE_TO_TEXT_AREA_RATIO].
        if (textAreaPx > 0) {
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            for (y in sortedRows) {
                if (rowMin[y] < minX) minX = rowMin[y]
                if (rowMax[y] > maxX) maxX = rowMax[y]
            }
            val boundsArea = (maxX - minX + 1).toLong() * (bottomY - topY + 1).toLong()
            val accepted = boundsArea <= textAreaPx * MAX_SHAPE_TO_TEXT_AREA_RATIO
            onRatioMeasured(boundsArea.toDouble() / textAreaPx, accepted)
            if (!accepted) return null
        }

        return (0 until SAMPLE_COUNT).map { i ->
            val frac = i / (SAMPLE_COUNT - 1).toFloat()
            val targetY = (topY + frac * (bottomY - topY)).toInt().coerceIn(topY, bottomY)
            val nearestY = nearestRowWithData(sortedRows, targetY)
            BubbleShapePoint(
                yF = nearestY / height.toFloat(),
                leftF = rowMin[nearestY] / width.toFloat(),
                rightF = rowMax[nearestY] / width.toFloat(),
            )
        }
    }

    /** true = pixel je nový (dosud nenavštívený) A barevně patří k pozadí bubliny, takže se má zařadit do fronty. */
    private fun visit(
        source: PixelSource,
        visited: LongArray,
        x: Int,
        y: Int,
        index: Int,
        bgColorArgb: Int,
        colorDistanceThreshold: Int,
    ): Boolean {
        val word = index ushr 6
        val bit = 1L shl (index and 63)
        if (visited[word] and bit != 0L) return false
        // Označí se i pixel, který barvou neprojde - je to hranice bubliny a bez značky
        // by se na ni sahalo znovu z každého souseda.
        visited[word] = visited[word] or bit
        return colorDistance(source.colorAt(x, y), bgColorArgb) < colorDistanceThreshold
    }

    /** Binární hledání nejbližšího řádku s daty - flood-fill nemusí vyplnit úplně každý řádek u šikmých okrajů bubliny. */
    private fun nearestRowWithData(sortedRows: List<Int>, target: Int): Int {
        var lo = 0
        var hi = sortedRows.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sortedRows[mid] < target) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && Math.abs(sortedRows[lo - 1] - target) <= Math.abs(sortedRows[lo] - target)) return sortedRows[lo - 1]
        return sortedRows[lo]
    }

    private fun colorDistance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return Math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }
}
