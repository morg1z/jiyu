package com.haise.jiyu.translate

import com.haise.jiyu.data.db.entity.TranslatedPageEntity
import com.haise.jiyu.util.report
import org.json.JSONArray
import org.json.JSONObject

// Klíče a (de)serializace cache přeložených stránek - vytaženo z TranslateRepository (bez změny chování).

/** Klíč cache stránky - obsahuje [TranslateRepository.PIPELINE_VERSION], viz komentář tam. */
internal fun pageCacheKey(chapterId: String, pageIndex: Int, targetLanguage: String, sourceLanguage: String) =
    "$chapterId::$pageIndex::$sourceLanguage::$targetLanguage::v${TranslateRepository.PIPELINE_VERSION}"

/**
 * Klíč cache přeložených novel. [PIPELINE_VERSION] tu dřív CHYBĚL, i když ho klíč
 * stránek má odjakživa - překlady novel se proto po opravě promptu nikdy nepřepočítaly
 * a uživatel viděl starou, rozbitou verzi navždycky. Přesně tomu mělo verzování zabránit.
 */
internal fun novelCacheKey(chapterId: String, sourceLanguage: String, targetLanguage: String) =
    "$chapterId::$sourceLanguage::$targetLanguage::v${TranslateRepository.PIPELINE_VERSION}"

internal fun List<TranslatedBlock>.toCacheJson(): String = JSONArray().also { arr ->
    forEach { b ->
        arr.put(JSONObject().apply {
            put("orig", b.originalText)
            put("trans", b.translatedText)
            put("disp", b.displayText)
            put("bg", b.bgColorArgb)
            put("bgBottom", b.bgColorBottomArgb)
            put("sfx", b.isSfx)
            put("lc", b.lineCount)
            put("type", b.bubbleType.name)
            put("untrans", b.isUntranslated)
            put("bgUniform", b.bgUniform)
            put("nlh", b.nativeLineHeightF.toDouble())
            b.shape?.let { shape ->
                put("shape", JSONArray().apply {
                    shape.forEach { p ->
                        put(JSONArray().apply { put(p.yF.toDouble()); put(p.leftF.toDouble()); put(p.rightF.toDouble()) })
                    }
                })
            }
            // put(String, float) na Android org.json.JSONObject neexistuje (jen desktopová
            // verze knihovny) -> NoSuchMethodError za běhu. Double overload existuje vždy.
            put("l", b.leftF.toDouble())
            put("t", b.topF.toDouble())
            put("r", b.rightF.toDouble())
            put("b", b.bottomF.toDouble())
        })
    }
}.toString()

/** disp/bg/sfx/lc/shape/type chybí ve starších cache záznamech - optXxx s výchozí hodnotou stejnou jako [TranslatedBlock] defaults, ať se nic nerozbije. */
internal fun TranslatedPageEntity.toBlocks(): List<TranslatedBlock> = try {
    val arr = JSONArray(blocksJson)
    List(arr.length()) { i ->
        val o = arr.getJSONObject(i)
        val translated = o.getString("trans")
        val shapeArr = o.optJSONArray("shape")
        val shape = if (shapeArr != null) {
            List(shapeArr.length()) { j ->
                val p = shapeArr.getJSONArray(j)
                BubbleShapePoint(yF = p.getDouble(0).toFloat(), leftF = p.getDouble(1).toFloat(), rightF = p.getDouble(2).toFloat())
            }
        } else null
        TranslatedBlock(
            originalText = o.getString("orig"),
            translatedText = translated,
            leftF = o.getDouble("l").toFloat(),
            topF = o.getDouble("t").toFloat(),
            rightF = o.getDouble("r").toFloat(),
            bottomF = o.getDouble("b").toFloat(),
            displayText = o.optString("disp", translated),
            bgColorArgb = if (o.has("bg")) o.getInt("bg") else DEFAULT_BUBBLE_BG_ARGB,
            // Starší cache záznamy nemají "bgBottom" - fallback na horní barvu (stejné
            // chování jako TranslatedBlock default), takže degradují na plnou barvu bez
            // gradientu místo pádu, dokud se stránka znovu nepřeloží.
            bgColorBottomArgb = o.optInt("bgBottom", if (o.has("bg")) o.getInt("bg") else DEFAULT_BUBBLE_BG_ARGB),
            isSfx = o.optBoolean("sfx", false),
            lineCount = o.optInt("lc", 1),
            shape = shape,
            bubbleType = try { BubbleType.valueOf(o.optString("type", "SPEECH")) } catch (e: Exception) { BubbleType.SPEECH },
            isUntranslated = o.optBoolean("untrans", false),
            // Starší cache záznamy nemají "bgUniform" - default true (rovnoměrné pozadí)
            // odpovídá chování PŘED touhle změnou (heuristika roztahovala box stejně
            // štědře pro všechny bloky bez tvaru), takže staré záznamy vypadají stejně,
            // dokud se stránka znovu nepřeloží.
            bgUniform = o.optBoolean("bgUniform", true),
            // Starší cache záznamy nemají "nlh" - default 0f (neznámá nativní velikost)
            // znamená, že fitter spadne na dřívější chování (hledej rovnou největší
            // velikost, co se vejde), dokud se stránka znovu nepřeloží.
            nativeLineHeightF = o.optDouble("nlh", 0.0).toFloat(),
        )
    }
} catch (e: Exception) {
    // Poškozený/nečitelný cache záznam. Prázdný seznam je správná reakce (stránka se
    // přeloží znovu), ale tiše to spolknout znamenalo, že se rozbitá serializace nikdy
    // neprojevila jinak než "překlad se občas záhadně dělá znovu".
    e.report("translate:cache:deserialize")
    emptyList()
}
