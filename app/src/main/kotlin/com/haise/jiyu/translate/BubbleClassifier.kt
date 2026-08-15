package com.haise.jiyu.translate

/**
 * Klasifikuje OCR bloky lokálně (bez API volání) na velikost/typ/SFX, aby:
 *  1) zvukové efekty vůbec nešly na Gemini API (ušetří volání a nezničí "PING!"→"CINK!"),
 *  2) prompt (viz [GeminiUltraPrompt]) věděl, kolik znaků se do bubliny vejde, ještě než
 *     model něco přeloží - jinak se limit dá vynutit jen post-hoc ořezáním, které vypadá
 *     hůř než když model rovnou cílí na správnou délku.
 *
 * OCR nedává tvar/barvu/obrys bubliny, jen text a bounding box - THOUGHT/WHISPER/SHOUT
 * jsou tedy jen odhad z obsahu textu, ne rozpoznání kresby bubliny.
 */
object BubbleClassifier {

    /**
     * Výslovný seznam zvuků. Je to UZAVŘENÁ množina - onomatopoií je konečně mnoho a dají se
     * vyjmenovat - na rozdíl od dřívějšího seznamu "běžných slov, která nejsou zvuk", který
     * byl otevřený, a proto vždycky neúplný (viz [detectSfx]).
     *
     * Druhá půlka seznamu je doplněná právě při zrušení toho pravidla: tyhle zvuky se do té
     * doby chytaly jen jako vedlejší efekt "krátký text verzálkami = zvuk", takže by po jeho
     * odstranění propadly na překlad.
     */
    private val sfxWords = setOf(
        "PING", "BOOM", "BAM", "CLICK", "TAP", "KNOCK", "SLAM", "BANG", "CRASH", "POP",
        "SNAP", "ZIP", "POW", "THUD", "CLANG", "DING", "GASP", "SIGH", "COUGH", "SNEEZE",
        "HICCUP", "GULP", "CHOMP", "WHAM", "CRACK", "SPLASH", "BUZZ", "RING", "HONK",
        "SWOOSH", "WHOOSH", "THUMP", "CREAK", "RATTLE", "ZAP", "BOING", "DOKIDOKI",
        "SOB", "SNIF", "SNIFF", "HUF", "HUFF", "HAK", "PANT", "ARGH", "AARGH", "UGH",
        "PSST", "SHH", "GRR", "WHACK", "SMACK", "SPLAT", "CLINK", "HISS", "SIZZLE",
        "VROOM", "BEEP", "PLOP", "THWACK", "TWANG", "CLANK", "WHEEZE", "BONK", "POOF",
        "SWISH", "FWOOSH", "RUMBLE", "WHIRR", "SCREECH", "SLURP", "MUNCH", "GRUNT",
    )

    /**
     * [sfxWords] se stlačenými zdvojenými písmeny - lettering zvuky protahuje ("SOBB",
     * "BOOOM", "CRASHH") a porovnání na přesnou shodu je proto míjelo. Skutečné slovo
     * zdvojením písmene svůj význam nemění, takže tímhle nic nepřibude, co by tam nepatřilo.
     */
    private val collapsedSfxWords = sfxWords.map { collapseRepeats(it) }.toSet()

    /** "SOBB" -> "SOB", "BOOOM" -> "BOM" - viz [collapsedSfxWords]. */
    private fun collapseRepeats(text: String): String = buildString {
        for (c in text) if (lastOrNull() != c) append(c)
    }

    /**
     * Samohlásky latinky včetně diakritiky (čeština, polština, španělština, vietnamština...) -
     * viz pravidlo "zvuk nemá samohlásku" v [detectSfx].
     */
    private const val LATIN_VOWELS = "AEIOUYÁÄÀÂÃÅÆÉËÈÊÍÏÌÎÓÖÒÔÕØŌÚÜÙÛŮÝŸĚĘĄŁ"

    /**
     * Nejvyšší kód písmene, které ještě považujeme za latinku (Latin Extended-B končí 0x024F).
     * Nad tím začíná řečtina, cyrilice a dál CJK - tam pravidlo o samohláskách neplatí, protože
     * ta písma latinské samohlásky nemají vůbec a spolklo by úplně obyčejný dialog.
     */
    private const val MAX_LATIN_CODE = 0x024F

    private val systemKeywords = listOf(
        "LEVEL UP", "SKILL", "STATUS", " HP", " MP", " EXP", "QUEST", "ACHIEVEMENT", "DUNGEON",
    )

    /**
     * Klasifikuje VŠECHNY bloky jedné stránky najednou - na rozdíl od [classify] (jeden blok
     * bez kontextu okolních) umí navíc odhalit opakovaný dlaždicovaný vodoznak napříč
     * stránkou (viz [detectTiledWatermarkIndices]) a takové bloky označit jako SFX, i když
     * žádný z nich sám o sobě nesplňuje [looksLikeWatermark] - to je jediné místo, odkud má
     * smysl volat [detectTiledWatermarkIndices], protože potřebuje vidět VŠECHNY bloky
     * stránky najednou, ne jeden po druhém.
     */
    fun classifyPage(rawBlocks: List<RawTextBlock>): List<ClassifiedBubble> {
        val watermarkIndices = detectTiledWatermarkIndices(rawBlocks)
        return rawBlocks.mapIndexed { i, raw ->
            val classified = classify(raw, raw.lineCount)
            if (i in watermarkIndices && !classified.isSfx) {
                classified.copy(isSfx = true, sizeTag = SizeTag.SFX, bubbleType = BubbleType.SFX)
            } else {
                classified
            }
        }
    }

    fun classify(raw: RawTextBlock, lineCount: Int): ClassifiedBubble {
        val trimmed = raw.text.trim()
        val letters = trimmed.filter { it.isLetter() }
        val isSfx = detectSfx(raw, trimmed, letters)

        val sizeTag = when {
            isSfx -> SizeTag.SFX
            else -> classifySize(raw, trimmed)
        }

        val bubbleType = when {
            isSfx -> BubbleType.SFX
            systemKeywords.any { trimmed.uppercase().contains(it) } -> BubbleType.SYSTEM
            // Text (VELKÁ PÍSMENA + "!") NEBO skutečný detekovaný tvar bubliny (trsovitý/
            // hvězdicovitý obrys, viz isJaggedShape) - dřív se SHOUT hádal jen z textu, i
            // když appka od nedávna zná skutečný obrys bubliny (BubbleShapeDetector).
            (letters.isNotEmpty() && letters.all { it.isUpperCase() } && trimmed.endsWith("!")) ||
                raw.shape?.let { isJaggedShape(it) } == true -> BubbleType.SHOUT
            trimmed.startsWith("(") && trimmed.endsWith(")") -> BubbleType.WHISPER
            trimmed.endsWith("...") || trimmed.startsWith("...") -> BubbleType.THOUGHT
            lineCount >= 3 && letters.length > 60 -> BubbleType.NARRATION
            else -> BubbleType.SPEECH
        }

        return ClassifiedBubble(raw = raw, sizeTag = sizeTag, bubbleType = bubbleType, isSfx = isSfx, lineCount = lineCount)
    }

    private fun classifySize(raw: RawTextBlock, trimmed: String): SizeTag {
        val width = raw.rightF - raw.leftF
        val height = raw.bottomF - raw.topF
        val aspectRatio = if (height > 0f) width / height else 1f
        return when {
            aspectRatio > 3.0f -> SizeTag.WIDE
            aspectRatio < 0.5f -> SizeTag.TALL
            trimmed.length <= SizeTag.TINY.maxChars -> SizeTag.TINY
            trimmed.length <= SizeTag.SMALL.maxChars -> SizeTag.SMALL
            trimmed.length <= SizeTag.MEDIUM.maxChars -> SizeTag.MEDIUM
            else -> SizeTag.LARGE
        }
    }

    /** "SIRENSCANS.COM", "ENSCANS.COM" apod. - viz [looksLikeWatermark]. */
    private val domainPattern = Regex("[A-Z0-9]{2,}\\.(COM|NET|ORG|INFO|IO|TO|CC|ME)")

    /**
     * Rozhoduje, jestli je blok textu zvukový efekt (SFX), ne replika - SFX bublina se
     * nikdy nepřekládá ani nevykresluje překladovou vrstvou, takže omyl tímhle směrem
     * nechá na stránce originál.
     *
     * Žádné z pravidel níže nestojí na seznamu slov konkrétního jazyka - fungují nad PÍSMEM
     * (latinka/CJK), ne nad nastaveným jazykem, takže platí stejně pro angličtinu,
     * španělštinu, francouzštinu i češtinu (viz "zvuk nemá samohlásku" a [LATIN_VOWELS]
     * níže - tohle dřív bývalo pravidlo "krátký text velkými písmeny = zvuk" pojištěné
     * čistě anglickým seznamem běžných slov, který byl otevřená množina a vždycky
     * neúplný). Výjimka: písma mimo latinku i CJK (cyrilice, řečtina, ...) tahle pravidla
     * přeskočí - viz [MAX_LATIN_CODE] - takže tam krátké SFX bez uzavřeného seznamu
     * [sfxWords] neodhalí, ale ani nehrozí spolknutí běžné repliky.
     */
    private fun detectSfx(raw: RawTextBlock, trimmed: String, letters: String): Boolean {
        if (trimmed.isEmpty()) return false

        // Čistě symboly/interpunkce - "!!!", "???", "*gasp*" bez písmen kolem
        if (letters.isEmpty() && trimmed.any { it == '!' || it == '?' || it == '*' }) return true

        // Odstranit se musí VŠECHNA okrajová interpunkce, ne jen ta koncověvětná. Dřív tu
        // chyběla čárka (a dvojtečka, středník, vlnovka, uvozovky) a tím se rozbila jediná
        // pojistka celého pravidla o krátkém ALL CAPS textu: porovnání se [shortWordsNotSfx]
        // dostávalo "WAIT," místo "WAIT", nikdy netrefilo, a tak i slova, která seznam
        // výslovně chrání, propadla mezi zvuky - "DAMN," i "WAIT," se klasifikovaly jako SFX,
        // tedy se vůbec neposlaly na překlad a v bublině zůstala angličtina.
        val core = trimmed.trim(*EDGE_PUNCTUATION)
        if (core.isEmpty()) return false

        if (looksLikeWatermark(raw, core)) return true

        // Holé číslo bez jediného písmene - typicky číslo panelu/stránky vypálené do skenu
        // (běžné u starších scanlation releasů jako MangaStream), ne replika. Skutečný dialog
        // se nikdy nezúží na samotnou číslici bez okolního textu. Bez tohohle OCR box kolem
        // takového čísla prochází i shape detekcí, kde floodfill z okolního bílého pozadí
        // často "uteče" do sousední skutečné bubliny a vytvoří tvar mimo obě.
        if (letters.isEmpty() && core.all { it.isDigit() }) return true

        // Zvuk psaný latinkou často NEMÁ SAMOHLÁSKU ("KRRR", "SHNK", "TSK", "GRR") - skutečné
        // slovo v jakémkoli jazyce psaném latinkou ji má vždycky. Tohle nahradilo dřívější
        // pravidlo "krátký text velkými písmeny bez mezer = zvuk", které v komiksu nerozlišovalo
        // vůbec nic: lettering sází VŠECHNO verzálkami, takže se z něj fakticky stalo "krátké
        // slovo = zvuk" a jedinou pojistkou byl ruční seznam běžných slov. Ten seznam je ale
        // otevřená množina a třikrát po sobě neúplný - polkl "DAMN", pak "...SAY," a nakonec
        // "I"/"TOO..."/"TAKEZŌ." z nahlášené stránky. SFX bublina se nepřekládá ani nekreslí,
        // takže každý takový omyl nechá na stránce anglický originál.
        //
        // Omezení na latinku je podstatné: japonská ani korejská replika latinskou samohlásku
        // nemá vůbec, takže bez něj by pravidlo spolklo běžný dialog.
        if (letters.isNotEmpty() && letters.length <= 6 && !core.contains(' ') &&
            letters.all { it.code <= MAX_LATIN_CODE } &&
            letters.none { it.uppercaseChar() in LATIN_VOWELS }
        ) return true

        // Zvuk se sází PŘES KRESBU, replika do bubliny (viz [RawTextBlock.bgUniform]) - druhý
        // nezávislý signál, který nestojí na žádném seznamu. Chytá i protažené/vymyšlené zvuky,
        // které samohlásku mají a v seznamu nejsou. Na dlouhý text se schválně neuplatní: caption
        // vysázená rovnou do kresby je běžná a věta zvuk nikdy není.
        if (!raw.bgUniform && letters.isNotEmpty() && letters.length <= 6 && !core.contains(' ')) return true

        // Stlačení zdvojených písmen kvůli protaženému letteringu - "SOBB"/"BOOOM" je pořád
        // tentýž zvuk (viz [collapsedSfxWords]).
        val upperCore = core.uppercase()
        if (sfxWords.contains(upperCore)) return true
        if (collapsedSfxWords.contains(collapseRepeats(upperCore))) return true

        // CJK zvuky bývají krátký text složený z opakující se znakové sekvence (např. "ドドド"),
        // na rozdíl od běžné repliky, kde se znaky neopakují takhle mechanicky.
        if (core.length in 2..6 && core.any { it.code > 0x3000 } && isRepeatingPattern(core)) return true

        return false
    }

    /** Interpunkce, která může obalovat text zvenčí, aniž by patřila k samotnému slovu. */
    private val EDGE_PUNCTUATION = charArrayOf(
        '*', '!', '?', '.', ' ', ',', ';', ':', '~', '-', '\n', '"', '\'', '…',
        '，', '、', '；', '：', '。', '」', '』', '”', '’',
    )

    /**
     * Vodoznak/tag scanlation skupiny přes kresbu (např. "SirenScans.com" diagonálně přes
     * panel) se chová jako normální OCR blok a dřív se přeložil a překryl plnou barevnou
     * plochou přes půl obrázku (viz uživatelská zpětná vazba - černá skvrna přes obličej
     * postavy). Dvě nezávislé stopy:
     *  1) Text obsahuje doménový vzor (".com"/".net"/...) - vodoznaky jsou skoro vždy
     *     web adresa skenlační skupiny, normální replika takhle nikdy nevypadá.
     *  2) Vodoznak čtený OCR "po písmenkách" (svisle otočený text) sloučí spoustu OCR
     *     řádků do jednoho hodně úzkého a hodně vysokého bloku - normální dialogová
     *     bublina takhle nevypadá ani u dlouhé replity.
     */
    private fun looksLikeWatermark(raw: RawTextBlock, core: String): Boolean {
        val collapsed = core.replace(" ", "").replace("\n", "").uppercase()
        if (domainPattern.containsMatchIn(collapsed)) return true

        val width = raw.rightF - raw.leftF
        val height = raw.bottomF - raw.topF
        val aspectRatio = if (height > 0f) width / height else 1f
        return raw.lineCount >= 8 && aspectRatio < 0.15f
    }

    private fun isRepeatingPattern(text: String): Boolean {
        for (unitLen in 1..2) {
            if (text.length % unitLen != 0 || text.length / unitLen < 2) continue
            val unit = text.substring(0, unitLen)
            if (text.chunked(unitLen).all { it == unit }) return true
        }
        return false
    }

    private const val WATERMARK_MIN_OVERLAP_CHARS = 4
    private const val WATERMARK_MAX_NORMALIZED_LENGTH = 24
    private const val WATERMARK_CLUSTER_MIN_SIZE = 3

    /**
     * Indexy bloků, které jsou součástí OPAKOVANÉHO DLAŽDICOVANÉHO VODOZNAKU - stejný krátký
     * text (typicky název/adresa skenlační skupiny) nastampovaný vícekrát po stránce, každý
     * výskyt jinak zkomolený OCR. Žádný JEDNOTLIVÝ výskyt sám o sobě nemusí vypadat podezřele
     * (na to je [looksLikeWatermark]), ale napříč stránkou tvoří jasný vzorec - viz uživatelská
     * zpětná vazba: "MADRASCANS MADRASCANS"/"MAD ANS"/"4ANS"/"MADRASCANS"/"MADRASCANS" jako pět
     * samostatných bloků na jedné stránce, žádný z nich sám o sobě nesplňoval existující
     * pravidla (moc dlouhý na krátké-ALL-CAPS pravidlo, nebo obsahuje mezeru).
     *
     * Union-find nad krátkými bloky (stejný vzor jako [mergeNearbyLines] v BubbleMerge.kt):
     * dva krátké bloky patří do stejného shluku, když kratší z jejich normalizovaných textů
     * (jen písmena/číslice, velká písmena, časté OCR záměny číslice->písmeno srovnané na
     * společný tvar) je PŘIBLIŽNÁ PODPOSLOUPNOST toho delšího - to zachytí i vypadlá/zaměněná
     * písmena, ne jen přesné podřetězce.
     *
     * Shluk se považuje za vodoznak, jen když má aspoň [WATERMARK_CLUSTER_MIN_SIZE] členů A
     * ZÁROVEŇ mezi nimi existuje aspoň jedna SKUTEČNÁ odchylka (ne všichni členové jsou
     * byte-po-bytu stejní) - jinak by stejné krátké slovo řečené vícekrát v dialogu (např.
     * jméno postavy) mohlo dopadnout stejně jako vodoznak. Vodoznak se pozná právě podle toho,
     * že se OPAKOVANĚ ČTE JINAK (různé zkomoleniny téhož), ne podle toho, že se opakuje.
     */
    internal fun detectTiledWatermarkIndices(blocks: List<RawTextBlock>): Set<Int> {
        val normalized = blocks.map { normalizeForWatermarkMatch(it.text) }

        val eligible = normalized.indices.filter {
            normalized[it].length in WATERMARK_MIN_OVERLAP_CHARS..WATERMARK_MAX_NORMALIZED_LENGTH
        }
        if (eligible.size < WATERMARK_CLUSTER_MIN_SIZE) return emptySet()

        val parent = IntArray(blocks.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[ra] = rb
        }

        for (i in eligible.indices) {
            for (j in i + 1 until eligible.size) {
                val a = eligible[i]
                val b = eligible[j]
                val (shorter, longer) = if (normalized[a].length <= normalized[b].length) {
                    normalized[a] to normalized[b]
                } else {
                    normalized[b] to normalized[a]
                }
                if (looksLikeGarbledRepeat(shorter, longer)) union(a, b)
            }
        }

        val result = mutableSetOf<Int>()
        for (members in eligible.groupBy { find(it) }.values) {
            if (members.size < WATERMARK_CLUSTER_MIN_SIZE) continue
            val distinctTexts = members.map { normalized[it] }.toSet()
            if (distinctTexts.size < 2) continue // vsichni bajt-po-bajtu stejni - moznadopakovana replika, ne vodoznak
            result += members
        }
        return result
    }

    /** Písmena+číslice, velká písmena, běžné OCR záměny číslice->písmeno srovnané na společný tvar. */
    private fun normalizeForWatermarkMatch(text: String): String {
        val ocrConfusions = mapOf('0' to 'O', '1' to 'I', '4' to 'A', '5' to 'S', '8' to 'B', '3' to 'E')
        return text.uppercase()
            .filter { it.isLetterOrDigit() }
            .map { ocrConfusions[it] ?: it }
            .joinToString("")
    }

    /**
     * Jsou tyhle dva texty dvěma ČTENÍMI TÉHOŽ nápisu, každé jinak zkomolené?
     *
     * Samotná "je podposloupnost" nestačí a dělala falešné poplachy: tři repliky, kde každá
     * jen prodlužuje předchozí ("HELP" / "HELP ME" / "HELP ME NOW", nebo jméno s různými
     * příponami), tuhle podmínku splňují taky - shlukly se do "vodoznaku", označily jako SFX
     * a tím pádem se vůbec nepřeložily; na stránce zůstal originál.
     *
     * Rozdíl je v tom, JAK se kratší text v delším nachází:
     *  - souvislý úsek ("HELP" v "HELPME") = jeden text prostě pokračuje, běžný dialog
     *  - podposloupnost s dírami ("MADANS" v "MADRASCANS") = uprostřed vypadla nebo se
     *    zaměnila písmena, což je přesně otisk OCR čtoucího tentýž nápis pokaždé jinak
     *
     * Skutečný nahlášený případ (MADRASCANS / MAD ANS / 4ANS / ...) tímhle prochází dál,
     * protože jeho varianty mají díry uvnitř, ne jen useknutý konec.
     */
    private fun looksLikeGarbledRepeat(shorter: String, longer: String): Boolean =
        isApproxSubsequence(shorter, longer) && !longer.contains(shorter)

    /** True, když se [needle] dá najít jako podposloupnost (ne nutně souvislá) v [haystack]. */
    private fun isApproxSubsequence(needle: String, haystack: String): Boolean {
        if (needle.isEmpty()) return false
        var hIdx = 0
        for (c in needle) {
            while (hIdx < haystack.length && haystack[hIdx] != c) hIdx++
            if (hIdx == haystack.length) return false
            hIdx++
        }
        return true
    }
}
