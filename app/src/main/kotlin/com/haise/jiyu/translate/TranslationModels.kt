package com.haise.jiyu.translate

/**
 * Velikostní třída bubliny podle délky originálu - určuje, kolik znaků smí mít český
 * překlad, aby se vešel do bubliny bez přetečení (viz [GeminiUltraPrompt]).
 * [SFX] není skutečná velikost, ale značka "nepřekládat" pro zvukové efekty.
 *
 * Hodnoty byly zvýšeny (2026-07-27) poté, co render dostal shape-aware auto-fit textu
 * (viz BubbleTextFit.kt) - box i písmo teď skutečně rostou podle potřeby, ne podle
 * pevné malé velikosti, takže původní extrémně těsné limity (TINY=8, SMALL=18...) nutily
 * model obětovat smysl/nuanci věty kvůli limitu, který renderer už tak přísně nepotřebuje.
 * [GeminiUltraPrompt.buildSystemPrompt] tyhle hodnoty interpoluje přímo do promptu, aby
 * text limitů a skutečná čísla nemohly rozejít.
 */
enum class SizeTag(val maxChars: Int) {
    TINY(12),
    SMALL(26),
    MEDIUM(60),
    LARGE(110),
    WIDE(85),
    TALL(85),
    SFX(0),
}

/**
 * Typ bubliny odhadnutý primárně z textu (VELKÁ PÍSMENA, interpunkce, klíčová slova) -
 * SPEECH/NARRATION/SYSTEM/SFX jsou rozlišitelné celkem spolehlivě, WHISPER/THOUGHT jen
 * z interpunkce (appka nemá spolehlivý signál z kresby, který by je odlišil). SHOUT navíc
 * (od 2026-07-27) používá i skutečný detekovaný obrys bubliny jako doplňkový signál -
 * viz [isJaggedShape]/[BubbleClassifier] - trsovitý/hvězdicovitý tvar je silný indikátor
 * i když text sám o sobě není celý velkými písmeny.
 */
enum class BubbleType {
    SPEECH,
    NARRATION,
    SHOUT,
    THOUGHT,
    WHISPER,
    SYSTEM,
    SFX,
}

/**
 * Výsledek lokální klasifikace jednoho OCR bloku - viz [BubbleClassifier].
 * @param lineCount kolik původních OCR řádků bylo sloučeno do tohoto bloku (viz
 *   [OcrEngine.mergeNearbyLines]) - používá se v [layoutTranslationBlocks] jako signál
 *   pro to, jestli má smysl box roztahovat nahoru (jednořádkové bloky nikdy).
 */
data class ClassifiedBubble(
    val raw: RawTextBlock,
    val sizeTag: SizeTag,
    val bubbleType: BubbleType,
    val isSfx: Boolean,
    val lineCount: Int,
)

/**
 * Jedna přeložená bublina vrácená z Gemini API (viz [GeminiUltraPrompt] pro formát
 * odpovědi, kterou tenhle DTO parsuje).
 */
data class GeminiBubbleTranslation(
    val id: Int,
    val original: String,
    val translated: String,
    val bubbleSizeTag: String,
    val isSfx: Boolean,
    val syllableBreaks: String,
    val notes: String = "",
)

/**
 * Jedno vlastní jméno (postava/místo/organizace/technika), které model rozpoznal v téhle
 * dávce a NEBYLO ještě v glosáři - viz [GeminiUltraPrompt] sekce "NOVÉ POJMY" a
 * [TranslateRepository] (auto-upsert do [GlossaryRepository], jen když tam uživatel už
 * nemá vlastní ruční záznam pro stejný zdrojový termín - ten má vždycky přednost).
 */
data class GlossarySuggestion(val source: String, val target: String)

/**
 * Naparsovaná odpověď z Gemini API - viz [GeminiTranslateClient].
 * @param model upstream model, který doopravdy odpověděl (proxy ho hlásí v poli "model") -
 *   po interním fallbacku proxy je to slabší záložní model (např. flash-lite), viz
 *   [TranslateRepository] - takový výsledek se ukáže, ale necachuje.
 */
data class GeminiTranslationResponse(
    val bubbles: List<GeminiBubbleTranslation>,
    val newTerms: List<GlossarySuggestion> = emptyList(),
    val model: String? = null,
)
