package com.haise.jiyu.translate

/**
 * Interpunkce, která se odtrhla od svého textu - úklid těsně před vykreslením.
 *
 * ## Co se dělo
 * Nahlášeno se srovnávací dvojicí snímků (originál vs. překlad) ze stránky Vagabonda:
 * balónek „WE'RE TAKING OFF." se vykreslil jako „ODLÉTÁME" a pod tím OSAMOCENÁ TEČKA na
 * vlastním řádku. Stejný podpis měla i dřív hlášená tečka pod „MATAHACHI" a „TAKEZO." bez
 * tečky.
 *
 * Že jde o zalomení a ne o bludný OCR blok, prozradila poloha: tečka seděla přesně na svislé
 * ose textu, tedy vycentrovaná jako druhý řádek TÉHOŽ bloku. Samostatný blok by ležel tam, kde
 * tečka doopravdy je v kresbě - vpravo od středu.
 *
 * Příčina je mezera (nebo zlom řádku) před koncovou tečkou v přeloženém textu. Zalamovač pak
 * úplně korektně nabídne zlom v té mezeře, „ODLÉTÁME" se na řádek vejde a „." už ne - a tečka
 * skončí sama. Uživateli to čte jako „bublina přišla o text".
 *
 * ## Proč se to řeší až při vykreslení
 * Úklid je čistě kosmetický a nic neukládá, takže se nemusela zvedat `PIPELINE_VERSION` a
 * spraví to i stránky, které už v cache leží. Stejná úvaha jako u záplat pozadí.
 */

/**
 * Odstraní mezeru/zlom řádku, kterou od textu odtrhly koncové interpunkční znaky.
 *
 * Záměrně NEsahá na interpunkci uvnitř věty ani na úvodní výpustku: „TAK\n...KONEC" je běžná
 * komiksová sazba (věta pokračující z předchozí bubliny) a slepit ji na „TAK...KONEC" by změnilo
 * zalomení, které tam autor chtěl. Sáhne se proto jen na skupinu interpunkce, za kterou už nic
 * dalšího není - tedy přesně na ten osamocený konec věty.
 */
fun tidyStrandedPunctuation(text: String): String = SPACE_BEFORE_TRAILING_PUNCTUATION.replace(text, "")

/**
 * Odstraní mezeru před "?", "!", ":" nebo ";" KDEKOLI v textu, ne jen na konci.
 *
 * Na rozdíl od [tidyStrandedPunctuation] (jen osamocená koncová interpunkce, viz její doc
 * komentář proč zůstává úzký) je tohle bezpečné použít všude: čeština mezeru před těmihle
 * znaky nikdy nemá (na rozdíl od francouzštiny), takže žádný legitimní případ, kdy by se
 * měla zachovat, neexistuje. Nahlášeno se stránkou Vagabonda: "CO DĚLÁŠ ?", "...TAKEZŌ ?" -
 * model si zjevně někdy plete interpunkční styl se zdrojovým jazykem (viz prompt sekce
 * "ČESKÁ TYPOGRAFIE" v [GeminiUltraPrompt]), tenhle úklid je záchranná síť pro případ, že
 * pravidlo v promptu nedodrží.
 *
 * Tečka a čárka se SCHVÁLNĚ vynechávají - ty řeší [tidyStrandedPunctuation] jen pro osamocený
 * konec věty, protože mezera před nimi uprostřed věty může být legitimní (desetinná čárka,
 * citace) a nechceme přepisovat text nad rámec nahlášené chyby.
 */
fun tidyFrenchStyleSpacing(text: String): String = SPACE_BEFORE_QUESTION_EXCLAMATION_COLON.replace(text, "")

/**
 * Nese blok vůbec nějaké písmeno?
 *
 * Blok bez jediného písmene (samotná tečka, uvozovka, hvězdička z rastru) nemá co překládat -
 * ať už vznikl jakkoliv. Vykreslit ho jde jen špatně: appka přes originál položí výplň a do ní
 * nakreslí tentýž znak o kus jinam, takže z čisté stránky vznikne bludná skvrnka. Nechat
 * prosvítat originál je vždycky lepší - interpunkce zůstane přesně tam, kam ji nakreslil autor.
 */
fun hasTranslatableLetters(text: String): Boolean = text.any { it.isLetter() }

/**
 * Mezery/zlomy před koncovou interpunkcí. Podmínka „a za ní už jen mezery nebo konec" je to
 * podstatné - bez ní by pravidlo slepilo i úvodní výpustku dalšího slova (viz
 * [tidyStrandedPunctuation]).
 *
 * Třída záměrně nejde jen po ASCII mezeře/zlomu/NBSP - `\p{Zs}` pokrývá i ostatní unicode
 * "space" znaky (EM/EN/THIN/IDEOGRAPHIC SPACE atd.), U+200B a U+FEFF jsou neviditelné znaky
 * nulové šířky (zero-width space, BOM). Nahlášeno se skutečnou stránkou (Vagabond, "A JSME
 * UPRCHLÍCI" + osamocená tečka), kde tahle přesná bublina prošla testy s obyčejnou mezerou
 * beze změny - model (teď i Cerebras/Mistral/Groq, ne jen Gemini) místo U+0020 vrátil jiný
 * "space" znak, který užší třída neznala, takže zalamovači zůstal nabídnutý zlom stát.
 * Viz StrayPunctuationTest.
 */
private val SPACE_BEFORE_TRAILING_PUNCTUATION =
    Regex("[\\s\\p{Zs}\\u00A0\\u200B\\uFEFF]+(?=[.,!?:;\\u2026]+[\\s\\p{Zs}\\u00A0\\u200B\\uFEFF]*$)")

/** Mezera (jakéhokoli druhu, viz [SPACE_BEFORE_TRAILING_PUNCTUATION]) bezprostředně před "?"/"!"/":"/";". */
private val SPACE_BEFORE_QUESTION_EXCLAMATION_COLON =
    Regex("[\\s\\p{Zs}\\u00A0\\u200B\\uFEFF]+(?=[?!:;])")
