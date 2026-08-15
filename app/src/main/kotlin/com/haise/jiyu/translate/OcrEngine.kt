package com.haise.jiyu.translate

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class RawTextBlock(
    val text: String,
    val leftF: Float,
    val topF: Float,
    val rightF: Float,
    val bottomF: Float,
    /** Kolik původních ML Kit "lines" bylo sloučeno do tohoto bloku - viz [mergeNearbyLines]. */
    val lineCount: Int = 1,
    /** Barva pozadí horní poloviny prstence kolem bubliny - viz [OcrEngine.sampleBackgroundColor]. */
    val bgColorTopArgb: Int = DEFAULT_BUBBLE_BG_ARGB,
    /** Barva pozadí dolní poloviny prstence - společně s [bgColorTopArgb] tvoří gradient výplně (viz TranslationOverlay). */
    val bgColorBottomArgb: Int = DEFAULT_BUBBLE_BG_ARGB,
    /** Skutečný obrys bubliny (flood-fill) - viz [BubbleShapeDetector]. Null = detekce selhala, render použije heuristický obdélník. */
    val shape: List<BubbleShapePoint>? = null,
    /** false = pozadí kolem textu je barevně nesourodé (text napsaný přímo přes kresbu) - viz [OcrEngine.isColorUniform]/[TranslatedBlock.bgUniform]. */
    val bgUniform: Boolean = true,
    /** Průměrná výška JEDNOHO řádku originálu (zlomek výšky stránky) - viz [TranslatedBlock.nativeLineHeightF]. */
    val nativeLineHeightF: Float = 0f,
    /**
     * Svisle sázený text (japonština). Bere se z úhlu, který ML Kit u řádku vrací - viz
     * [isVerticalAngle] - a rozhoduje o tom, jakým pravidlem se blok slučuje ([shouldMerge]).
     */
    val isVertical: Boolean = false,
)

/**
 * Je řádek s tímhle úhlem svisle sázený sloupec?
 *
 * ML Kit u svislé japonštiny vrací `Text.Line.angle` kolem 90° (naměřeno na zařízení:
 * 89,9 až 90,4) a appka ten údaj do teď vůbec nečetla. Je to spolehlivější signál než
 * hádat z poměru stran boxu: jednoznakový sloupec je skoro čtvercový, kdežto dlouhé
 * vodorovné slovo je taky "úzké a dlouhé", jen v druhé ose.
 *
 * Tolerance je široká, protože skeny bývají pootočené. 270° je tatáž svislice opačným
 * směrem - bere se taky, ať se pravidlo nechová jinak podle znaménka.
 */
internal fun isVerticalAngle(angle: Float): Boolean {
    val normalized = ((angle % 360f) + 360f) % 360f
    return normalized in 60f..120f || normalized in 240f..300f
}

/** Výsledek [OcrEngine.sampleBackgroundColor] - dvě barvy (gradient) + signál rovnoměrnosti pro [TranslatedBlock.bgUniform]. */
private data class BgSample(val topArgb: Int, val bottomArgb: Int, val uniform: Boolean)

/**
 * Čistá funkce (bez Bitmap) - body na obvodu OCR boxu s okrajem [margin], odkud je
 * bezpečné startovat flood-fill (jsou to body na pozadí bubliny, ne na textu). Testováno
 * v OcrRingSeedsTest.
 */
internal fun ringSeeds(leftF: Float, topF: Float, rightF: Float, bottomF: Float, w: Int, h: Int, margin: Int = 4): List<Pair<Int, Int>> {
    val left = (leftF * w).toInt()
    val top = (topF * h).toInt()
    val right = (rightF * w).toInt()
    val bottom = (bottomF * h).toInt()
    val midX = ((left + right) / 2).coerceIn(0, w - 1)
    val midY = ((top + bottom) / 2).coerceIn(0, h - 1)
    return listOf(
        midX to (top - margin).coerceIn(0, h - 1),
        midX to (bottom + margin).coerceIn(0, h - 1),
        (left - margin).coerceIn(0, w - 1) to midY,
        (right + margin).coerceIn(0, w - 1) to midY,
    )
}

/**
 * Plocha OCR boxu v pixelech - měřítko, proti kterému [BubbleShapeDetector.detectShape] pozná
 * obrys, který se vylil mimo bublinu (viz tam MAX_SHAPE_TO_TEXT_AREA_RATIO).
 */
internal fun textAreaPx(leftF: Float, topF: Float, rightF: Float, bottomF: Float, w: Int, h: Int): Long {
    val width = ((rightF - leftF) * w).toLong().coerceAtLeast(0)
    val height = ((bottomF - topF) * h).toLong().coerceAtLeast(0)
    return width * height
}

/**
 * Loguje reálný poměr tvar/text naměřený u [BubbleShapeDetector.detectShape] (viz tam
 * MAX_SHAPE_TO_TEXT_AREA_RATIO = 30x). Zatím čistě observabilita: `adb logcat -s BubbleShapeRatio`
 * při běžném čtení nasbírá reálnou distribuci, podle které se práh časem doladí na datech
 * misto dalšího odhadu.
 */
private fun logShapeRatio(ratio: Double, accepted: Boolean) {
    Log.d("BubbleShapeRatio", "ratio=%.1fx accepted=%s".format(ratio, accepted))
}

/**
 * Loguje, jaky podil bloku jedne stranky dostal skutecny tvar bubliny vs. spadl na
 * heuristicky fallback ([layoutHeuristic] v TranslationLayout.kt - nejkrehcejsi cast
 * vykreslovaciho pipeline, viz audit). Cistě observabilita: `adb logcat -s ShapeCoverage`
 * pri beznem cteni ukaze, jak casto se na fallback v realnem provozu vubec sahne.
 */
private fun logShapeCoverage(total: Int, withShape: Int) {
    if (total == 0) return
    val fallback = total - withShape
    Log.d("ShapeCoverage", "total=$total shape=$withShape fallback=$fallback (%.0f%%)".format(100.0 * fallback / total))
}

/** Hodnota zdrojového jazyka, která znamená "zjisti si to sám" - viz [resolveAutoLanguage]. */
internal const val AUTO_LANGUAGE = "Auto"

/**
 * Pořadí, ve kterém se pod [AUTO_LANGUAGE] zkoušejí rozpoznávače. Latinka je první schválně:
 * velká část zdrojů v appce jsou anglické skenlace, takže se to obvykle rozhodne hned prvním
 * průchodem. CJK modely mají každý vlastní ML Kit model, latinkový je nechytí.
 */
internal val AUTO_CANDIDATE_LANGUAGES = listOf("English", "Japanese", "Korean", "Chinese")

/**
 * Kolik nebílých znaků stačí, aby se průchod považoval za jistý a další se už nepouštěly.
 *
 * Číslo bylo původně odhad. Změřeno na zařízení (viz AutoLanguageOnDeviceTest; hodnoty jsou
 * počty nebílých znaků, které daný model našel):
 *
 *     japonská stránka   -> English=0,  Japanese=31, Korean=20, Chinese=0
 *     latinková stránka  -> English=57, Japanese=57, Korean=57, Chinese=57
 *     korejská stránka   -> English=0,  Japanese=7,  Korean=18, Chinese=10
 *
 * Dvě věci z toho plynou. Latinkový model na CJK stránce nenajde NIC, ne "pár znaků
 * nesmyslu" - propadnutí na další model má tedy velkou rezervu. A CJK modely čtou latinku
 * stejně dobře jako ten latinkový; že je English první, je proto to, co dělá běžnou
 * anglickou stránku levnou - vyřeší se jediným průchodem.
 *
 * Práh se schválně NESNIŽUJE, i když by to u krátkých CJK stránek ušetřilo průchody (viz
 * korejský řádek, kde se prahu nedosáhne a poběží všechny čtyři modely): reálné japonské
 * skeny běžně nesou i latinku navíc - zvuky, loga, vodoznaky typu "SIRENSCANS.COM" - a s
 * nízkým prahem by se výběr zasekl na latince a zbytek stránky by se ztratil. Raději občas
 * pustit víc modelů než tiše přijít o text.
 */
internal const val AUTO_CONFIDENT_CHARS = 20

/**
 * Vybere rozpoznávač pro [AUTO_LANGUAGE]: zkouší kandidáty popořadě a bere ten, který našel
 * nejvíc textu. Jakmile některý překročí [AUTO_CONFIDENT_CHARS], zbytek se už nespouští.
 *
 * Proč to vůbec je: "Auto" je v rozbalovátku zdrojového jazyka ve čtečce nabízené jako první
 * možnost, ale [OcrEngine.recognizerFor] pro něj neměl větev a spadl do `else`, tedy na
 * LATINKOVÝ model. Kdo si vybral "Auto" a otevřel japonskou mangu, dostal z OCR nesmysl nebo
 * nic - a k tomu se bubliny seřadily zleva doprava, takže model dostal repliky v obráceném
 * pořadí. Autodetekce v appce prostě žádná nebyla, jen se tak tvářila.
 *
 * Rozpoznávání se předává jako lambda, aby šlo tohle rozhodování otestovat bez ML Kitu.
 *
 * @return dvojice (rozpoznaný jazyk, jeho bloky); prázdné bloky = nenašlo se nic nikde.
 */
internal suspend fun resolveAutoLanguage(
    candidates: List<String> = AUTO_CANDIDATE_LANGUAGES,
    recognizeWith: suspend (String) -> List<RawTextBlock>,
): Pair<String, List<RawTextBlock>> {
    var best = candidates.first() to emptyList<RawTextBlock>()
    var bestChars = -1
    for (candidate in candidates) {
        val blocks = recognizeWith(candidate)
        val chars = blocks.sumOf { block -> block.text.count { !it.isWhitespace() } }
        if (chars > bestChars) {
            best = candidate to blocks
            bestChars = chars
        }
        if (chars >= AUTO_CONFIDENT_CHARS) break
    }
    return best
}

/**
 * Čte se tenhle jazyk zprava doleva (japonská řada panelů/bublin)?
 *
 * Dřív se testovala jen japonština. Tradiční čínština (Tchaj-wan, Hongkong) se ale čte stejně
 * zprava doleva - dostávala tedy bubliny seřazené obráceně a překladový model četl repliky
 * pozpátku, což kazí návaznost dialogu.
 *
 * Zjednodušená čínština schválně NE: manhua vychází typicky ve webtoonovém formátu, který se
 * čte zleva doprava, takže zobecnit to na "cokoliv čínského" by chybu jen přesunulo jinam.
 *
 * Omezení: pod [AUTO_LANGUAGE] se tradiční čínština rozpoznat nedá (viz
 * [AUTO_CANDIDATE_LANGUAGES] - oba čínské zápisy čte jeden model), takže tohle zabere jen
 * tehdy, když si uživatel zdrojový jazyk vybere ručně.
 */
internal fun isRightToLeftScript(language: String): Boolean =
    language == "Japanese" || language == "Chinese (Traditional)"

/** Obaluje Bitmap do [PixelSource] pro [BubbleShapeDetector] - jediné místo, kde algoritmus vidí Android typ. */
private class BitmapPixelSource(private val bitmap: Bitmap) : PixelSource {
    override fun colorAt(x: Int, y: Int): Int = bitmap.getPixel(x, y)
}

/**
 * Čistě on-device ML Kit OCR - nemá s HTTP nic společného, stahování/dekódování bitmapy
 * stránky je zodpovědnost volajícího (viz [PageBitmapLoader]), ne tohohle enginu.
 */
@Singleton
class OcrEngine @Inject constructor() {
    // Lazy recognizers: CJK jazyky mají vlastní ML Kit model, ostatní spadají na latinkový výchozí
    private val japaneseRecognizer by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chineseRecognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val koreanRecognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }
    private val latinRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    private fun recognizerFor(language: String) = when (language) {
        "Japanese" -> japaneseRecognizer
        "Chinese", "Chinese (Traditional)" -> chineseRecognizer
        "Korean" -> koreanRecognizer
        else -> latinRecognizer
    }

    suspend fun recognize(bitmap: Bitmap, language: String = "Japanese"): List<RawTextBlock> = withContext(Dispatchers.IO) {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w == 0f || h == 0f) return@withContext emptyList()

        val image = InputImage.fromBitmap(bitmap, 0)

        // Pod "Auto" se rozpoznávač vybírá podle toho, který na téhle stránce opravdu něco
        // našel (viz resolveAutoLanguage) - dřív pro "Auto" neexistovala větev a spadlo to
        // na latinku, takže japonská stránka nevrátila nic použitelného.
        val (resolvedLanguage, lines) = if (language == AUTO_LANGUAGE) {
            resolveAutoLanguage { candidate -> recognizeLines(candidate, image, w, h) }
        } else {
            language to recognizeLines(language, image, w, h)
        }

        // Sampling barvy pozadí i detekce tvaru bubliny potřebují ještě živou bitmapu,
        // proto běží tady a ne až v TranslateRepository, kam se bitmapa vůbec nedostane
        // (jen relativní souřadnice).
        val pixelSource = BitmapPixelSource(bitmap)
        // Pořadí, ve kterém tenhle seznam skončí, je i pořadí, ve kterém bubliny uvidí
        // překladový model (viz GeminiUltraPrompt.buildUserPrompt) - bez řazení do
        // skutečného pořadí čtení dostával model repliky v podstatě náhodně (podle
        // union-find indexu z mergeNearbyLines), což kazilo návaznost dialogu.
        //
        // noWallBetween: čistě geometrická blízkost (shouldMerge) nestačí - dvě RŮZNÉ
        // bubliny/captions vedle sebe můžou geometrii splňovat, ale mezi nimi je vždycky
        // vizuální hranice (obrys, jiná barva boxu). Bez týhle kontroly se sloučily do
        // jednoho bloku: jedna bublina zmizela beze zbytku (viz uživatelská zpětná vazba),
        // druhá na stránce s reklamou vytvořila jednu přebujelou barevnou plochu.
        val merged = sortIntoReadingOrder(
            mergeNearbyLines(lines) { a, b -> !hasWallBetween(pixelSource, bitmap.width, bitmap.height, a, b) },
            // Rozhoduje ROZPOZNANÝ jazyk, ne ten nastavený - pod "Auto" byl nastavený jazyk
            // doslova "Auto", takže japonská stránka dostala pořadí zleva doprava a model
            // četl repliky pozpátku.
            rightToLeft = isRightToLeftScript(resolvedLanguage),
        )
        val result = merged.mapIndexed { index, block ->
            val bgSample = sampleBackgroundColor(bitmap, block)
            val detected = BubbleShapeDetector.detectShape(
                source = pixelSource,
                width = bitmap.width,
                height = bitmap.height,
                seeds = ringSeeds(block.leftF, block.topF, block.rightF, block.bottomF, bitmap.width, bitmap.height),
                // Detektor tvaru (flood-fill) potřebuje JEDNU referenční barvu pozadí, ne
                // gradient - průměr obou polovin je pro tenhle účel dost přesný.
                bgColorArgb = averageArgb(bgSample.topArgb, bgSample.bottomArgb),
                textAreaPx = textAreaPx(block.leftF, block.topF, block.rightF, block.bottomF, bitmap.width, bitmap.height),
                onRatioMeasured = ::logShapeRatio,
            )
            // Kaskadova replika byva nakreslena jako dve PREKRYVAJICI SE bublinky, ktere tvori
            // jednu spojitou bilou plochu - flood-fill se pres ten pas prelije do sousedniho
            // laloku a vratil by tvar pokryvajici oba. Vypln by pak premalovala cizi text, a to
            // i text, ktery appka vubec neprelozila. Viz clampShapeToOwnLobe.
            val shape = detected?.let {
                clampShapeToOwnLobe(
                    shape = it,
                    own = block,
                    others = merged.filterIndexed { i, _ -> i != index },
                )
            }
            block.copy(
                bgColorTopArgb = bgSample.topArgb,
                bgColorBottomArgb = bgSample.bottomArgb,
                bgUniform = bgSample.uniform,
                shape = shape,
            )
        }
        logShapeCoverage(result.size, result.count { it.shape != null })
        result
    }

    /**
     * Jeden průchod ML Kitem daným rozpoznávačem, převedený na [RawTextBlock] s relativními
     * souřadnicemi.
     *
     * ML Kit "textBlocks" jsou odstavcová seskupení odladěná na fotky dokumentů/účtenek, ne
     * na manga bubliny - běžně buď slijí dvě sousední bubliny do jednoho bloku, nebo naopak
     * rozseknou jednu bublinu na víc bloků. Jdeme proto o úroveň níž na "lines" (řádky) a
     * slučujeme je vlastní geometrickou heuristikou ([mergeNearbyLines]), která lépe odpovídá
     * tomu, co člověk vnímá jako jednu bublinu.
     *
     * (Zkoušeno i slučování na úrovni slov/elements - u ručně psaného komiksového písma ML Kit
     * občas vrátí boundingBox jednoho "Line" objektu kratší, než je skutečná výška víceřádkového
     * textu, ale jednotlivá slova mají stejně chybné souřadnice, takže to problém neřešilo, a
     * navíc to rozbilo slučování slov na stejném řádku - viz [shouldMerge], jehož práh je
     * odvozený z výšky vstupu, a slova jsou o řád nižší než řádky. Opravu chybějící výšky řeší
     * [RawTextBlock.lineCount] - viz [PositionedTranslationBlock.minTopF] v TranslationLayout.kt.)
     */
    private suspend fun recognizeLines(language: String, image: InputImage, w: Float, h: Float): List<RawTextBlock> {
        val result = suspendCancellableCoroutine { cont ->
            recognizerFor(language).process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        return result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
            val box = line.boundingBox ?: return@mapNotNull null
            if (line.text.isBlank()) return@mapNotNull null
            RawTextBlock(
                text = line.text,
                leftF = (box.left / w).coerceIn(0f, 1f),
                topF = (box.top / h).coerceIn(0f, 1f),
                rightF = (box.right / w).coerceIn(0f, 1f),
                bottomF = (box.bottom / h).coerceIn(0f, 1f),
                isVertical = isVerticalAngle(line.angle),
            )
        }
    }

    /**
     * Dopočítá jen tvar bubliny pro už přeložené bloky ze starého cache formátu
     * (shape == null), bez nového OCR/ML Kit volání - viz TranslateRepository.getCachedPage
     * migrace. Blok, který už tvar má, nebo je SFX (nemá box vůbec), se přeskočí beze změny.
     */
    suspend fun detectShapesOnly(bitmap: Bitmap, blocks: List<TranslatedBlock>): List<TranslatedBlock> = withContext(Dispatchers.IO) {
        val w = bitmap.width
        val h = bitmap.height
        if (w == 0 || h == 0) return@withContext blocks
        val pixelSource = BitmapPixelSource(bitmap)
        blocks.map { tb ->
            if (tb.shape != null || tb.isSfx) return@map tb
            val shape = BubbleShapeDetector.detectShape(
                source = pixelSource,
                width = w,
                height = h,
                seeds = ringSeeds(tb.leftF, tb.topF, tb.rightF, tb.bottomF, w, h),
                bgColorArgb = tb.bgColorArgb,
                textAreaPx = textAreaPx(tb.leftF, tb.topF, tb.rightF, tb.bottomF, w, h),
                onRatioMeasured = ::logShapeRatio,
            )
            tb.copy(shape = shape)
        }
    }

    /**
     * Nasampluje průměrnou barvu tenkého prstence pixelů těsně kolem OCR boxu (mimo text,
     * ale typicky pořád uvnitř bubliny) - viz [TranslatedBlock.bgColorArgb]. Bez tohohle
     * je přeložený box vždy bílý, což na barevných/šrafovaných bublinách (shout efekty,
     * system boxy) nechává viditelně prosvítat okraj originálu kolem hran boxu.
     *
     * Vrací DVĚ barvy (horní/dolní polovina prstence podle svislé pozice vzorku) místo
     * jedné, pro gradient výplně - viz TranslationOverlay. Horní/dolní ŘÁDEK prstence jde
     * celý do své poloviny; levý/pravý SLOUPEC se rozpadne mezi obě poloviny sám podle
     * y-pozice každého vzorku ([sample] níž), žádná speciální logika navíc není potřeba.
     */
    private fun sampleBackgroundColor(bitmap: Bitmap, block: RawTextBlock): BgSample {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return BgSample(DEFAULT_BUBBLE_BG_ARGB, DEFAULT_BUBBLE_BG_ARGB, uniform = true)
        val margin = 4

        val left = (block.leftF * w).toInt()
        val top = (block.topF * h).toInt()
        val right = (block.rightF * w).toInt()
        val bottom = (block.bottomF * h).toInt()

        val ringLeft = (left - margin).coerceIn(0, w - 1)
        val ringTop = (top - margin).coerceIn(0, h - 1)
        val ringRight = (right + margin).coerceIn(0, w - 1)
        val ringBottom = (bottom + margin).coerceIn(0, h - 1)
        if (ringRight <= ringLeft || ringBottom <= ringTop) return BgSample(DEFAULT_BUBBLE_BG_ARGB, DEFAULT_BUBBLE_BG_ARGB, uniform = true)

        val topSamples = mutableListOf<IntArray>()
        val bottomSamples = mutableListOf<IntArray>()
        val midY = (ringTop + ringBottom) / 2

        fun sample(x: Int, y: Int) {
            if (x < 0 || x >= w || y < 0 || y >= h) return
            val px = bitmap.getPixel(x, y)
            val rgb = intArrayOf((px shr 16) and 0xFF, (px shr 8) and 0xFF, px and 0xFF)
            if (y <= midY) topSamples += rgb else bottomSamples += rgb
        }

        // Vzorkujeme jen obvod prstence (ne celou plochu) - max ~80 bodů, dost na stabilní
        // průměr a zanedbatelné vůči jednomu OCR volání na stránku.
        val maxSamplesPerSide = 20
        val stepX = ((ringRight - ringLeft).coerceAtLeast(1) / maxSamplesPerSide).coerceAtLeast(1)
        var x = ringLeft
        while (x <= ringRight) { sample(x, ringTop); sample(x, ringBottom); x += stepX }
        val stepY = ((ringBottom - ringTop).coerceAtLeast(1) / maxSamplesPerSide).coerceAtLeast(1)
        var y = ringTop
        while (y <= ringBottom) { sample(ringLeft, y); sample(ringRight, y); y += stepY }

        // Rozhodování o jednolitosti žije v [isBackgroundUniform] - je to čistá funkce, aby šla
        // testovat bez Bitmapy. Je ZÁMĚRNĚ přísná (stačí jediný vzorek mimo toleranci); pokus
        // odlehlé vzorky odfiltrovat rozlil plnou výplň přes kresbu, podrobnosti viz tam.
        val uniform = isBackgroundUniform(topSamples + bottomSamples)
        return BgSample(colorFor(topSamples, uniform), colorFor(bottomSamples, uniform), uniform)
    }

    /**
     * Rovnoměrné pozadí: prostý průměr (jako dřív - stabilní pro skutečné bubliny).
     * Nerovnoměrné (pestrá kresba): průměr jen z nejčastějšího barevného "kbelíku"
     * (kvantizace po [COLOR_BUCKET_SIZE] úrovních na kanál) místo průměru přes úplně
     * odlišné barvy - ten totiž skoro vždy vyjde jako neexistující "zabahněná" barva
     * (viz uživatelská zpětná vazba - hnědá placka přes barevnou titulní kresbu),
     * zatímco dominantní barva okolí aspoň vizuálně patří k té kresbě.
     */
    private fun colorFor(samples: List<IntArray>, uniform: Boolean): Int {
        if (samples.isEmpty()) return DEFAULT_BUBBLE_BG_ARGB
        val source = if (uniform) samples else {
            val buckets = HashMap<Triple<Int, Int, Int>, MutableList<IntArray>>()
            for (s in samples) {
                val key = Triple(s[0] / COLOR_BUCKET_SIZE, s[1] / COLOR_BUCKET_SIZE, s[2] / COLOR_BUCKET_SIZE)
                buckets.getOrPut(key) { mutableListOf() }.add(s)
            }
            buckets.values.maxByOrNull { it.size } ?: samples
        }
        val avgR = source.sumOf { it[0] } / source.size
        val avgG = source.sumOf { it[1] } / source.size
        val avgB = source.sumOf { it[2] } / source.size
        return android.graphics.Color.rgb(avgR, avgG, avgB)
    }

    private companion object {
        private const val COLOR_BUCKET_SIZE = 32
    }
}
