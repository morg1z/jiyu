package com.haise.jiyu.translate

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lokální diagnostický výpis překladu: co OCR přečetlo, jak se bubliny klasifikovaly, který provider
 * odpověděl a co z toho vyšlo. Release build nevypisuje nic do logcatu, takže bez tohohle nešlo dohledat,
 * PROČ je bublina nepřeložená, rozseknutá nebo přeložená slabým modelem (viz uživatelské snímky Vagabonda).
 *
 * Soubor je v `getExternalFilesDir("translation-diagnostics")` (jde stáhnout `adb pull`), zůstává jen v telefonu
 * a je omezený: po překročení [MAX_FILE_BYTES] se starý soubor smaže a začne nový. Zápis nikdy nesmí shodit
 * překlad - každý vstup je obalený v `runCatching`.
 */
internal object TranslationDiagnostics {

    const val DIRECTORY_NAME = "translation-diagnostics"
    private const val FILE_NAME = "translation.jsonl"
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024

    private val lock = Any()

    /**
     * Který provider (a s jakým výsledkem) odpověděl na jednu dávku bublin.
     * @param model upstream model, který doopravdy odpověděl (proxy ho hlásí v poli "model") -
     *   pozná se tak degradace na záložní model; null u staré proxy nebo on-device cesty.
     */
    fun recordProvider(context: Context, provider: String, total: Int, untranslated: Int, targetLanguage: String, model: String? = null) {
        write(context, JSONObject().apply {
            put("kind", "provider")
            put("provider", provider)
            put("bubbles", total)
            put("untranslated", untranslated)
            put("target", targetLanguage)
            if (model != null) put("model", model)
        })
    }

    /**
     * Konečný stav jedné stránky po celém řetězci providerů a dopřeložení.
     * @param note poznámka pro stránky bez bublin - "ocr_timeout"/"ocr_bitmap_failed"/
     *   "ocr_no_text"/"ocr_empty" (viz [TranslateRepository]); bez ní se prázdná stránka
     *   od chybějícího záznamu (crash) nepozná.
     * @param ocrRawCount kolik textových regionů OCR celkem nahlásilo PŘED čištěním -
     *   "OCR text našel, ale výstup z něj žádný" (audit Vagabondu: vynechané bubliny
     *   jako "THAT'S…"/"STOP.") se jinak od stránky bez textu nepoznaly. Výchozí =
     *   `classified.size` (čištění řádků nemění počet, viz withCleanedOcrText).
     */
    fun recordPage(
        context: Context,
        chapterId: String,
        pageIndex: Int,
        targetLanguage: String,
        classified: List<ClassifiedBubble>,
        blocks: List<TranslatedBlock>,
        note: String? = null,
        ocrRawCount: Int = classified.size,
    ) {
        write(context, JSONObject().apply {
            put("kind", "page")
            put("chapterId", chapterId)
            put("pageIndex", pageIndex)
            put("target", targetLanguage)
            if (note != null) put("note", note)
            // Souhrn pokrytí stránky - odpoví na "kolik z nalezeného textu se doopravdy
            // přeložilo a proč zbytek ne", bez luštění jednotlivých bublin níž.
            put("coverage", JSONObject().apply {
                put("ocrRaw", ocrRawCount)
                put("detected", classified.size)
                put("output", blocks.size)
                put("translated", blocks.count { !it.isSfx && !it.isArtText && !it.isUntranslated })
                put("sfx", blocks.count { it.isSfx })
                put("artText", blocks.count { it.isArtText })
                put("untranslated", blocks.count { it.isUntranslated })
                put("noLetters", blocks.count { !it.isSfx && !it.isArtText && !it.isUntranslated && !hasTranslatableLetters(it.displayText) })
                // OCR regiony, ze kterých vubec zadny blok nevysel (dropnuto pred
                // klasifikaci / slouceno bez zbytku) - hlavni "ztraceny obsah" metrika.
                put("dropped", (ocrRawCount - blocks.size).coerceAtLeast(0))
            })
            put("bubbles", JSONArray().apply {
                blocks.forEachIndexed { i, block ->
                    val c = classified.getOrNull(i).takeIf { classified.size == blocks.size }
                    put(JSONObject().apply {
                        put("original", block.originalText)
                        put("translated", block.translatedText)
                        put("untranslated", block.isUntranslated)
                        put("sfx", block.isSfx)
                        // Proc se bublina nevykresli (sfx/untranslated/no_letters) - stejny
                        // predikat jako BubbleOverlayLayer, takze "chybejici bublina" se z
                        // logu pozna rovnou bez dohadovani (viz audit - vynechane repliky).
                        // art_lettering aproximace: render ještě zkusí recovery obrysu
                        // (viz recoverBubble) a záplatu pozadí - blok se vykreslí, když
                        // jedno z nich vyjde; skip znamená jen "bez obou je mimo hru".
                        // Tenhle záznam je o statických flag bloku, ne o výsledku recovery.
                        bubbleSkipReason(block.isSfx, block.isUntranslated, hasTranslatableLetters(block.displayText), block.isArtText, artLettering = block.shape == null && !block.bgUniform)
                            ?.let { put("skip", it) }
                        put("box", JSONArray(listOf(block.leftF, block.topF, block.rightF, block.bottomF)))
                        put("hasShape", block.shape != null)
                        put("bgUniform", block.bgUniform)
                        // Barva pozadí, kterou renderer pod bublinu maluje - pro dohledání
                        // černě vyplněných výbuchů a bílých titulků na černém panelu.
                        put("bgArgb", block.bgColorArgb)
                        put("bgArgbBottom", block.bgColorBottomArgb)
                        put("lineCount", block.lineCount)
                        put("nativeLineHeightF", block.nativeLineHeightF.toDouble())
                        if (c != null) {
                            put("sizeTag", c.sizeTag.name)
                            put("type", c.bubbleType.name)
                        }
                    })
                }
            })
        })
    }

    /**
     * Autoritativní záznam, jak se stránka DOOPRAVDY vykreslila (kind="render_page") -
     * psaný až po dořešení záplat a obnovy obrysů při zobrazení, takže na rozdíl od
     * "page" záznamu zná skutečný výsledek každé bubliny, ne statickou aproximaci.
     *
     * Vzniklo kvůli nahlášeným bílým plackám přes kresbu ("THE BATTLE OF SEKIGAHARA"):
     * ze statických flagů (`shape == null && !bgUniform`) nešlo poznat, jestli lettering
     * nakonec dostal záplatu, obnovený obrys, nebo se přeskočil - a proč (záplata může
     * chybět kvůli nenačtené bitmapě, obřímu boxu, degenerované masce...). Tady je každý
     * blok s přesným módem vykreslení a stavem patch/recovery včetně důvodů zamítnutí.
     *
     * Duplicitní záznamy (scroll tam-zpět po stejné stránce se stejným obsahem) se
     * vynechávají - klíč = pageUrl + obsah bloků.
     */
    fun recordRender(
        context: Context,
        pageUrl: String,
        pageIndex: Int,
        positioned: List<PositionedTranslationBlock>,
        fixes: Map<Int, BubbleOverlayFix>,
    ) {
        // Klíč = URL + obsah bloků. String.hashCode() tady NE - dvě různé bubliny
        // se stejným hashem by se sloučily a druhá stránka by se do logu
        // nedostala (není to hypotetické - dedupe key má jen ~32 bitů entropie).
        val dedupeKey = pageUrl + "#" + positioned.joinToString("|") {
            "${it.block.originalText}:${it.block.bgUniform}:${it.block.shape != null}"
        }
        synchronized(lock) {
            if (!recentRenderKeys.add(dedupeKey)) return
            while (recentRenderKeys.size > 64) recentRenderKeys.remove(recentRenderKeys.first())
        }
        write(context, JSONObject().apply {
            put("kind", "render_page")
            put("pageIndex", pageIndex)
            put("pageUrl", pageUrl)
            put("coverage", JSONObject().apply {
                // Pozor: iterace přes indexy, ne indexOf - dva shodné bloky by vrátily
                // stejný index (dataclass rovnost) a druhý by počítal s cizím fixem.
                put("rendered", positioned.indices.count { renderSkipReason(positioned[it].block, fixes[it]) == null })
                put("skipped", positioned.indices.count { renderSkipReason(positioned[it].block, fixes[it]) != null })
                put("patchesBuilt", fixes.values.count { it.patchState == PatchState.BUILT })
                put("patchesPlanned", fixes.values.count { it.patchState != PatchState.NOT_PLANNED })
                put("recoveryAttempted", fixes.values.count { it.recoveryState != RecoveryState.NOT_CANDIDATE })
                put("recovered", fixes.values.count { it.recoveryState == RecoveryState.RECOVERED })
            })
            put("bubbles", JSONArray().apply {
                positioned.forEachIndexed { i, pos ->
                    val b = pos.block
                    val fix = fixes[i]
                    val skip = renderSkipReason(b, fix)
                    put(JSONObject().apply {
                        put("i", i)
                        put("original", b.originalText)
                        put("display", b.displayText)
                        if (skip != null) put("skip", skip) else put(
                            "mode",
                            bubbleRenderMode(
                                hasShape = b.shape != null,
                                bgUniform = b.bgUniform,
                                hasPatch = fix?.patch != null,
                                hasRecoveredShape = fix?.recovered != null,
                                recoveredPatch = fix?.patch != null,
                            ),
                        )
                        put("hasShape", b.shape != null)
                        put("bgUniform", b.bgUniform)
                        if (fix != null) {
                            put("patchState", fix.patchState.name)
                            if (fix.patchWidthPx > 0) put("patchPx", "${fix.patchWidthPx}x${fix.patchHeightPx}")
                            if (fix.textArgb != null) put("textArgb", fix.textArgb)
                            if (fix.recoveryState != RecoveryState.NOT_CANDIDATE) put("recovery", fix.recoveryState.name)
                            if (fix.recoveryReject != null) put("recoveryReject", fix.recoveryReject)
                        }
                        put("box", JSONArray(listOf(pos.leftF, pos.minTopF, pos.rightF, pos.maxBottomF)))
                    })
                }
            })
        })
    }

    /**
     * Skutečný skip důvod PŘI VYKRESLOVÁNÍ - na rozdíl od aproximace v "page" záznamu zná
     * výsledek záplaty i recovery, takže art_lettering hlásí jen blok, co doopravdy
     * zůstal nepřekreslený (bez záplaty i bez obnoveného obrysu).
     */
    private fun renderSkipReason(block: TranslatedBlock, fix: BubbleOverlayFix?): String? {
        val artLettering = block.shape == null && !block.bgUniform &&
            fix?.recovered == null && fix?.patch == null
        return bubbleSkipReason(
            isSfx = block.isSfx,
            isUntranslated = block.isUntranslated,
            hasTranslatableLetters = hasTranslatableLetters(block.displayText),
            isArtText = block.isArtText,
            leakedToken = hasLeakedToken(block.displayText),
            artLettering = artLettering,
        )
    }

    /** Klíče nedávno zaznamenaných render stránek - dedup scroll tam-zpět (viz recordRender). */
    private val recentRenderKeys = LinkedHashSet<String>()

    /**
     * Souhrn po dokonceni CELE kapitoly (viz TranslateRepository.translateChapter) -
     * jeden radek na kapitolu pro rychly "kolik procent textu se prelozilo" pohled,
     * misto sčítání po jednotlivých "page" záznamech.
     */
    fun recordChapter(
        context: Context,
        chapterId: String,
        targetLanguage: String,
        pages: Int,
        bubbles: Int,
        translated: Int,
        sfx: Int,
        untranslated: Int,
    ) {
        write(context, JSONObject().apply {
            put("kind", "chapter")
            put("chapterId", chapterId)
            put("target", targetLanguage)
            put("pages", pages)
            put("bubbles", bubbles)
            put("translated", translated)
            put("sfx", sfx)
            put("untranslated", untranslated)
        })
    }

    private fun write(context: Context, entry: JSONObject) {
        runCatching {
            val dir = context.getExternalFilesDir(DIRECTORY_NAME) ?: return
            entry.put("t", System.currentTimeMillis())
            synchronized(lock) {
                dir.mkdirs()
                val file = File(dir, FILE_NAME)
                if (file.length() > MAX_FILE_BYTES) file.delete()
                file.appendText(entry.toString() + "\n")
            }
        }
    }
}
