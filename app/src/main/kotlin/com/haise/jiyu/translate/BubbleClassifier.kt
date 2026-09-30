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
        // Vizbig/VIZ lettering i další manga SFX - audit Vagabond kap. 1 (v31): tyhle
        // zvuky sedí v BÍLÝCH bublinách (bgUniform), mají samohlásky a v seznamu nebyly,
        // takže šly na překlad (TROMP->"DUP", HLUF->"Chach") a render česky překryl
        // originální lettering. Uživatel: SFX se nepřekládají vůbec.
        "TROMP", "PLISH", "SPLISH", "SPLOSH", "SPLORSH", "SPLORCH", "THOOSH", "BWOOSH",
        "SHOOM", "WHOOM", "FWISH", "FWUP", "FWOK", "FWUMP", "FWHUMP", "WHUMP", "THOOM", "SHIFF",
        "DOOM", "KRAK", "KRAKK", "KLANK", "KRRK", "KSHK", "KRICH", "SHUNK", "SCHINK", "SHING",
        "SNIKT", "ZWING", "FWING", "KAPOW", "BLAM", "WHAP", "THAP", "BIFF", "SOCK",
        "PAK", "POK", "BOK", "KLAK", "KLIK", "CLAK", "TOK", "TOCK", "KTOK", "PAD",
        "PATTER", "BADUMP", "BADUM", "DOKI", "SHLURP", "GLOOP", "GLOP", "GLUP",
        "GURGLE", "GAK", "GLK", "GUH", "BRAAR", "GRAAR", "RAAAR", "SKREEE", "KREEE",
        "KREE", "SHEEE", "SHIEE", "ESHHH", "SHHH", "KONK", "DONK", "PLUNK",
        "PLONK", "KERPLUNK", "FWIP", "ZWIP", "FWOOM", "VWOOM", "SKRR", "BZZT", "WRAK",
        // Zvukovité výkřiky/dech - ne slova, ale vocalizace; nechat v originále.
        "URR", "URGH", "GAAH", "HAAH", "HYAH", "HYA", "KIYA", "HMPH", "NGH", "MNGH",
        "HNN", "HNNGH", "WHEW", "PHEW", "EEP", "YIPE", "GYAA", "UWAH", "WAAH", "AAH",
        "AHH", "EHH", "OHH", "UHH", "OOF", "OOP", "HAH",
        // Samostatné citoslovčité vokalizace ("OH…", "HAHA", "HUH?") - nesou emoce, ne
        // větu; uživatel chce zvuky/vokalizace netknuté (audit: "OH…" se přepsalo na
        // "Ach…"). V osamoceném bloku = zvuk; ve větě multi-token cesta stejně vyžaduje,
        // aby byly zvuky VŠECHNY tokeny, takže "OH NO, HE'S HERE" dál přeložíme.
        "OH", "AH", "UH", "ER", "EH", "UM", "OW", "OWW", "HA", "HAHA", "HEH", "HUH",
        // Pití/polykání, déšť a další prostředí - uživatel výslovně: GLP/déšť/pití zůstává
        // originál. ("GLP" chytá i pravidlo bez samohlásky; tyhle tvary samohlásku mají.)
        "GLUG", "SWIG", "GUZZLE", "BURP", "BELCH", "SIP", "HIC", "PITTER", "PITPAT",
        "PLIP", "GLOMP", "SPLUTTER", "WOOF", "ARF", "RUFF", "MOO", "COO",
        // OCR varianty pozorované na zařízení (font U->I/L záměna) - "HLUF"/"HLIF"/"IHUF"
        // jsou "HUF" z (HLIF HLUF) auditu; fuzzy lev<=1 v [isSfxToken] pokryje další.
        // "HIJE"/"HIJA" jsou další deformované čtení "HUE"/"HUF" z v33 auditu - přeložily
        // se jako "Hija" přes originál.
        "HLUF", "HLIF", "IHUF", "HIJE", "HIJA", "HIJF", "HLJE", "DNG",
        // Korejské transliterované zvuky do latinky (manhwa SFX) - audit RWS ch.215:
        // "DADUN" (더덩 tlumený zadunění) se přeložilo na "BUM" - porušení pravidla.
        // Kvůli fuzzy lev<=1 v [isSfxToken] se kryjí i OCR deformace ("DADUN"~"DADUM").
        "DUN", "DDUN", "DUDUN", "DUDUNG", "DADUN", "DADUNG", "KUNG", "KWANG",
        "KWAANG", "KWAGWANG", "PUK", "PEOK", "TAK", "TTAK", "DEOK", "HWIK",
        "SYUT", "JJEOK", "GEUK", "KKEOK", "NANANA", "NANAN",
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
     * Je [upperCore] (verzálky, bez mezer) známý zvuk? Potřebuje ho slovníkový lint
     * v OcrTextCleanup - bez něj by "THUD" (není v EN slovníku) „opravil" na "THUS"
     * a zvuk by došel k modelu jako replika. Dřív privátní sada se protlačuje
     * pouze přes tohle rozhraní, ať zůstane uzavřená.
     */
    internal fun isKnownSfx(upperCore: String): Boolean =
        sfxWords.contains(upperCore) || collapsedSfxWords.contains(collapseRepeats(upperCore))

    /**
     * Běžná krátká anglická citoslovce/repliky, které se NIKDY nemají klasifikovat jako SFX,
     * i když je lettering vysází přímo přes kresbu (mimo bublinu) - viz pravidlo o `bgUniform`
     * v [detectSfx]. To pravidlo záměrně nekontroluje samohlásky (chytá i "BOOM"/"CRASH"), takže
     * bez týhle pojistky by pohltilo i krátkou legitimní repliku vysázenou pro důraz mimo
     * bublinu.
     */
    private val commonShortWordsNotSfx = setOf(
        "HEY", "WAIT", "STOP", "HELP", "RUN", "GO", "NOW", "YES", "NO", "OK", "OKAY",
        "WHAT", "WHO", "WHY", "HOW", "COME", "LOOK", "WATCH", "LISTEN", "DAMN",
        // Kontrakce a krátké repliky ≤4 písmen - po zúžení over-art pravidla na
        // "samohláska ⇒ ≤4" pořád potřebují ochranu; delší slova se samohláskou
        // chrání samotné pravidlo (viz audit Vagabondu - "THAT'S…"/"SWORDS?").
        "I'M", "I'LL", "I'VE", "IT'S", "HE'S", "SHE'S", "DON'T", "CAN'T", "WON'T",
        "AIN'T", "WHO'S", "LET'S", "ISN'T", "DIDN'T", "THAT'S", "WHAT'S", "THERE'S",
        "HERE'S", "MA'AM", "SURE", "RIGHT", "WRONG", "SORRY", "THANKS", "PLEASE",
        "WELL", "FINE", "GEEZ", "JEEZ", "GOSH", "OOPS",
        "FOOL", "FOOLS", "LIAR", "LIES",
        "GOD", "GODS", "LORD", "KING", "SIR", "HERO", "FOE", "FOES", "MEN", "MAN",
        "SON", "KID", "KIDS", "DOG", "DOGS", "RAT", "PIG", "HELL", "DIE", "DEAD",
        "KILL", "FEAR", "MINE", "OURS", "YOURS", "DEAR", "GIRL", "GIRLS", "BOY",
        "BOYS", "NAME", "TOO", "BOTH", "EACH", "HERE", "THERE", "GONE", "LOST",
        "BACK", "HOME", "ALONE", "SWORDS", "LETHAL", "ALIVE",
    )

    /**
     * Jsou VŠECHNY tokeny bloku zvuky ("GULP GULP", "HUF HUF", "(HLIF HLUF)", "BOOM CRASH")?
     * Lettering kreslí zvuky jako víc oddělených nápisů a OCR/spojování řádků je sloučí do
     * JEDNOHO bloku s mezerou uvnitř - ostatní pravidla vyžadují `!core.contains(' ')`,
     * takže by je takový blok obešel všechna najednou.
     *
     * Dřív se vyžadovala stejná instance slova (případně s OCR literovkou) - "(HLIF HLUF)"
     * jako OCR varianty "(HUF HUF)" propadly na překlad a česky překryly originál
     * (audit Vagabond kap. 1: uživatel nechce překládat SFX vůbec). Teď stačí, když je
     * každý token sám zvuk - viz [isSfxToken].
     */
    private fun isAllSfxTokens(core: String, englishWords: Set<String>): Boolean {
        // Dělící znaky vedle mezer - "GULP-GULP"/"GULP - GULP" nese stejný vzor jako
        // "GULP GULP" a bez nich by uniklo (audit: "GULP GULP" přeložené na "GUP").
        val tokens = core.split(Regex("[\\s\\-–—]+"))
            .map { it.trim(*EDGE_PUNCTUATION) }
            .filter { it.isNotBlank() }
        if (tokens.size < 2) return false
        return tokens.all { isSfxToken(it.uppercase(), englishWords) }
    }

    /**
     * Je jeden token zvuk? Přesná shoda / stlačené zdvojení ("SOBB"~"SOB") / bezesamohláskový
     * tvar <=6 písmen ("KSH") / OCR literovku o jeden znak proti seznamu ("HLUF"~"HUF").
     * [commonShortWordsNotSfx] má přednost před vším - skutečné krátké repliky ("HEY", "HUH")
     * se jako zvuk nikdy neoznačí, ani kdyby ležely o znak od položky slovníku.
     * Fuzzy shoda se navíc spouští jen s EN slovníkem - skutečné slovo ("MISS") není
     * zkomolenina, i když sedí o znak na zvuk ("HISS"); bez slovníku by pravidlo
     * polklo běžné repliky (viz komentář u hlavní cesty v [detectSfx]).
     */
    private fun isSfxToken(upperToken: String, englishWords: Set<String> = emptySet()): Boolean {
        if (upperToken in commonShortWordsNotSfx) return false
        if (sfxWords.contains(upperToken) || collapsedSfxWords.contains(collapseRepeats(upperToken))) return true
        val letters = upperToken.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.length <= 6 &&
            letters.all { it.code <= MAX_LATIN_CODE } &&
            letters.none { it in LATIN_VOWELS }
        ) return true
        return englishWords.isNotEmpty() && upperToken.length >= 3 &&
            upperToken.lowercase() !in englishWords &&
            sfxWords.any { levenshteinAtMost(upperToken, it, 1) }
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
    fun classifyPage(rawBlocks: List<RawTextBlock>, englishWords: Set<String> = emptySet()): List<ClassifiedBubble> {
        val watermarkIndices = detectTiledWatermarkIndices(rawBlocks)
        return rawBlocks.mapIndexed { i, raw ->
            val classified = classify(raw, raw.lineCount, englishWords)
            if (i in watermarkIndices && !classified.isSfx) {
                classified.copy(isSfx = true, sizeTag = SizeTag.SFX, bubbleType = BubbleType.SFX)
            } else {
                classified
            }
        }
    }

    fun classify(raw: RawTextBlock, lineCount: Int, englishWords: Set<String> = emptySet()): ClassifiedBubble {
        val trimmed = raw.text.trim()
        val letters = trimmed.filter { it.isLetter() }
        val isSfx = detectSfx(raw, trimmed, letters, englishWords)

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

    /**
     * "SIRENSCANS.COM", "ENSCANS.COM" apod. - viz [looksLikeWatermark]. Lookahead za TLD
     * je nutný: bez něj by doménu simulovalo i "SCANS.COMICS" nebo "READCOMICS.ORGANIC",
     * kde je TLD jen začátek delšího slova.
     */
    private val domainPattern = Regex("[A-Z0-9]{2,}\\.(COM|NET|ORG|INFO|IO|TO|CC|ME)(?![A-Z0-9])")

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
    private fun detectSfx(raw: RawTextBlock, trimmed: String, letters: String, englishWords: Set<String> = emptySet()): Boolean {
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

        // Víceslovný blok složený jen ze zvuků ("GULP GULP", "HUF HUF", "(HLIF HLUF)",
        // "BOOM CRASH") - lettering je kreslí jako víc oddělených nápisů, ale
        // OCR/spojování řádků je sloučí do JEDNOHO bloku s mezerou uvnitř. Ostatní
        // pravidla níž (na seznam i na "bez samohlásky") vyžadují `!core.contains(' ')`,
        // takže tahle mezera je obejde VŠECHNY najednou - živý nález: "GULP GULP"
        // (v `sfxWords`, ale s mezerou) prošlo jako obyčejný text a přeložilo se na
        // nesmysl, "(HLIF HLUF)" se přeložilo na "(Huf huf)" a překrylo originál.
        if (isAllSfxTokens(core, englishWords)) return true

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
        //
        // commonShortWordsNotSfx: na rozdíl od pravidla výš tohle NEKONTROLUJE samohlásky
        // schválně (viz komentář výš), takže by bez pojistky pohltilo i krátkou legitimní
        // repliku vysázenou mimo bublinu ("HEY", "WAIT", "NO") - běžné u komiksového zdůraznění
        // (nahlášeno v auditu).
        //
        // Slovo se samohláskou delší než 4 písmena je přes kresbu spíš zdůrazněná replika
        // než vymyšlený zvuk - audit Vagabondu: "THAT'S…", "SWORDS?", "LETHAL…" se tímhle
        // pravidlem nikdy nedostaly na překlad. Zvuky se samohláskou delší než 4 písmena
        // ("CRASH", "FWOOSH", "RUMBLE") chytá pořád seznam sfxWords/collapsedSfxWords níž;
        // krátké zvuky ("THUD", "POW", "BANG") a všechny bezesamohláskové (viz pravidlo výš)
        // zůstávají chycené tady.
        if (!raw.bgUniform && letters.isNotEmpty() && letters.length <= 6 && !core.contains(' ') &&
            core.uppercase() !in commonShortWordsNotSfx &&
            (letters.length <= 4 || letters.none { it.uppercaseChar() in LATIN_VOWELS })
        ) return true

        // Stlačení zdvojených písmen kvůli protaženému letteringu - "SOBB"/"BOOOM" je pořád
        // tentýž zvuk (viz [collapsedSfxWords]). Collapsed shoda ale nesmí přebít chráněné
        // krátké repliky: protažený slovníkový zvuk ("AHH", "OHH") se stlačí na "AH"/"OH",
        // což je běžná interjekce - její ochrana má přednost (v31).
        val upperCore = core.uppercase()
        if (sfxWords.contains(upperCore)) return true
        if (upperCore !in commonShortWordsNotSfx && upperCore !in TINY_REPLICA_ALLOWLIST &&
            collapsedSfxWords.contains(collapseRepeats(upperCore))
        ) return true

        // OCR literovka o jeden znak od známého zvuku - "HUIF"~"HUF", "HWIF"~"HUF". Stejná
        // tolerance, jakou má jeden token v [isSfxToken], jen pro celý blok: bez ní šel
        // "HUIF" na překlad, vrátil se jako "Huf" a překryl originální lettering (audit
        // Vagabondu - uživatel: SFX vůbec nepřekládat).
        //
        // Pravidlo běží JEN s EN slovníkem v ruce: zvukové zkomoleniny se poznají právě
        // tím, že nejsou skutečné slovo. Bez slovníku by lev<=1 pohltilo stovky běžných
        // slov sedících o znak od zvuku ("SHOOT"~"SHOOM", "MISS"~"HISS", "WISH"~"FWISH",
        // "DOOR"~"DOOM", "BEING"~"BOING", "BREAK"~"CREAK") a repliky by zůstaly anglicky.
        if (englishWords.isNotEmpty() && upperCore.length >= 3 &&
            upperCore !in commonShortWordsNotSfx && upperCore.lowercase() !in englishWords &&
            sfxWords.any { levenshteinAtMost(upperCore, it, 1) }
        ) return true

        // CJK interpunkce UVNITŘ latinského bloku ("LIR..、R") - pozůstatek OCR šumu z
        // japonského letteringu; model nad ním halucinoval český text ("Ugh… uh."), který
        // pak ležel přes kresbu. Samotná CJK interpunkce na kraji se už ořízla (viz
        // EDGE_PUNCTUATION), uvnitř latinky nemá co dělat.
        if (letters.any { it.code <= MAX_LATIN_CODE } && core.any { it in CJK_PUNCTUATION }) return true

        // CJK zvuky bývají krátký text složený z opakující se znakové sekvence (např. "ドドド"),
        // na rozdíl od běžné repliky, kde se znaky neopakují takhle mechanicky.
        if (core.length in 2..6 && core.any { it.code > 0x3000 } && isRepeatingPattern(core)) return true

        // --- Šumové brány: mikro-bloky, které nikdy nemají co překládat ---

        // Blok bez jediného písmene, co přežil všechna pravidla výš (osamocená
        // pomlčka/tečka apod.) - render by ho stejně přeskočil (hasTranslatableLetters)
        // a v dávce pro model je navíc. SFX = ponechat originál, neposílat nikam.
        if (letters.isEmpty()) return true

        // Jedno/dvoupísmenný útržek, který není známá krátká replika - audit Vagabondu:
        // "ś", "S", "F" odtržená písmena ze sazby dostala vlastní "překlad" a model nad
        // šumem halucinoval slova ("HLUPAKI"). Skutečné krátké repliky chrání oba
        // seznamy; cokoliv jiného je bezpečnější nechat jako SFX (originál zůstane).
        // Jen latinka - CJK replika ("はい", "네") má taky <=2 "písmen" a není útržek.
        if (letters.length <= 2 && letters.all { it.code <= MAX_LATIN_CODE } &&
            upperCore !in commonShortWordsNotSfx &&
            upperCore !in TINY_REPLICA_ALLOWLIST
        ) return true

        // Trojpísmenný útržek nad pestrou kresbou bez detekovaného tvaru bubliny -
        // s největší pravděpodobností zbytek sazby ("PAI"). Skutečné krátké repliky
        // chrání commonShortWordsNotSfx; uvnitř bílé bubliny (bgUniform) se brána
        // nespouští, aby nezabila ojedinělý krátký dialog. Jen latinka ("そうか" je
        // taky tříznaková a přesto je to replika).
        if (letters.length == 3 && letters.all { it.code <= MAX_LATIN_CODE } &&
            upperCore !in commonShortWordsNotSfx &&
            !raw.bgUniform && raw.shape == null
        ) return true

        return false
    }

    /** Jedno/dvoupísmenné bloky, které můžou být skutečný text (zájmena, předložky). */
    private val TINY_REPLICA_ALLOWLIST = setOf(
        "I", "A", "O", "AN", "AM", "AS", "AT", "BE", "BY", "DO", "EX", "GO", "HE",
        "HI", "ID", "IF", "IN", "IS", "IT", "MA", "ME", "MY", "NO", "OH", "OK",
        "ON", "OR", "OW", "OX", "PA", "SO", "TO", "UH", "UM", "UP", "US", "WE", "YO",
    )

    /** CJK interpunkce - uvnitř latinského bloku je to důkaz OCR šumu (viz [detectSfx]). */
    private val CJK_PUNCTUATION = charArrayOf('、', '。', '「', '」', '『', '』', '【', '】', '〜')

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
        // Doména jako souvislý token v textu SE mezerami ("SIRENSCANS.COM" samotnou bublinu
        // tvoří). Zřetězený tvar sem nesmí jako první: vznikla by z něj falešná doména tam,
        // kde věta končí tečkou a další začíná slovem shodným s TLD - "FORGET THIS. I OWE
        // YOU" -> "THIS.IOWE" ≈ "this.io" (živý nález z telefonu - celá věta dialogu se
        // označila za vodoznak=SFX a nikdy se nepřeložila).
        if (domainPattern.containsMatchIn(core.uppercase())) return true

        // OCR čtení vodoznaku "po písmenkách" ("E N S C A N S . C O M") - tady zřetězení
        // potřeba je. Poznává se podle rozmezerníčeného tvaru bloku (spousta 1-2znakových
        // tokenů) - běžná věta tolik jedno/dvoupísmenných slov nemá, takže falešný poplach
        // z předchozího odstavce se nestane.
        val tokens = core.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.count { it.length <= 2 } >= 5) {
            val collapsed = core.replace(" ", "").replace("\n", "").uppercase()
            if (domainPattern.containsMatchIn(collapsed)) return true
        }

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
