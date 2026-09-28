package com.haise.jiyu.translate

import org.json.JSONObject

/**
 * Self-review pass - model zdarma zkontroluje vlastní dvojice zdroj→překlad.
 *
 * ## Proč existuje
 * Slovníkový lint ([CzechOutputLint]) strukturálně nechytně chyby, kde jsou všechna
 * slova platná česká slova, ale věta je špatně: "ZABIJ U TĚ" (vypadlé písmeno),
 * "PŘEDALI JSME MAČKY MÍSTO KOPÍ" (machetes→mačky), "BYT MOŽNÉ MÍT DRINK",
 * "MOŽNÁ SUNDEJTE OBECNÉ" (general→obecný). Jediná free cesta je druhé čtení
 * stejným providerem s jiným úkolem - korektor čte dvojice a řekne OK/FIX/BAD.
 *
 * ## Bezpečnost
 * - Verdict FIX se aplikuje jen když opravený text projde stejným lintem jako
 *   originální výstup - review nemůže propašovat horší text.
 * - Verdict BAD = blok se označí untranslated (čtenář vidí originál).
 * - Neparsovatelná odpověď review = fail-open, vše se bere jako OK - review je
 *   přídavná vrstva, nikdy nesmí rozbít fungující překlad.
 */
object TranslationReview {

    /** Rozsudky review pro jednu dvojici. */
    enum class Verdict { OK, FIX, BAD }

    data class VerdictResult(val verdict: Verdict, val fixed: String = "")

    fun buildSystemPrompt(targetLanguage: String): String = """
        Jsi jazykový korektor ${targetLanguage} překladů manga bublin. Dostaneš očíslované
        dvojice ORIGINÁL -> PŘEKLAD. Pro každou dvojici rozhodni jeden verdict:
        - "OK": překlad je správný a přirozený.
        - "FIX": překlad má gramatickou/významovou chybu, neexistující slovo, špatný pád
          nebo zbytek cizího jazyka - do pole "fixed" napiš OPRAVENÝ překlad (celý text).
        - "BAD": překlad je nesmysl, gibberish, nebo říká něco jiného než originál -
          čtenář má vidět originál, ne tuhle bublinu.
        Hlídej zejména: převrácený význam (agent/pacient prohozený), špatný čas nebo
        osobu (jsme->byli, chci->chcete), vymyšlená česká slova, anglické slovo uprostřed
        české věty, větu bez slovesa, chybějící koncovou interpunkci (? ! …).
        Jména postav/míst/technik NIKDY neopravuj na běžná slova.
        Neopravuj styl - jen skutečné chyby. Když je překlad dobrý, vrať OK.
        Odpověz POUZE JSON objektem:
        {"reviews":[{"id":0,"verdict":"OK","fixed":""},{"id":1,"verdict":"FIX","fixed":"opravený text"}]}
    """.trimIndent()

    fun buildUserPrompt(pairs: List<Pair<String, String>>): String = buildString {
        pairs.forEachIndexed { i, (src, tgt) ->
            append(i).append(". SOURCE: \"").append(src.replace('"', '\'')).append("\"\n")
            append("   TRANSLATION: \"").append(tgt.replace('"', '\'')).append("\"\n")
        }
    }

    /**
     * Rozparsuje review odpověď na mapu id->verdict. Null = neparsovatelná odpověď
     * (fail-open, volající ponechá překlady beze změny).
     */
    fun parse(raw: String): Map<Int, VerdictResult>? = try {
        val root = JSONObject(GeminiUltraPrompt.extractJsonObject(raw))
        val arr = root.optJSONArray("reviews") ?: return null
        val out = HashMap<Int, VerdictResult>(arr.length())
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val id = r.optInt("id", -1)
            if (id < 0) continue
            val verdict = when (r.optString("verdict").uppercase()) {
                "FIX" -> Verdict.FIX
                "BAD" -> Verdict.BAD
                else -> Verdict.OK
            }
            out[id] = VerdictResult(verdict, r.optString("fixed").trim())
        }
        out
    } catch (_: Exception) {
        null
    }
}
