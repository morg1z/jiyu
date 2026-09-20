package com.haise.jiyu.translate

import android.util.Log
import com.haise.jiyu.BuildConfig

// Observabilita kvality překladu (jen debug build) - vytaženo z TranslateRepository (bez změny chování).

/**
 * Loguje, kdyz "preklad" vysel (az na velikost pismen) doslova stejny jako original -
 * viz [isSuspiciousVerbatimCopy]. Prompt ma sekci "KONTROLA PRED ODESLANIM" (zaporky,
 * zadna vymyslena slova...), ale nic v kodu drive neoverovalo, jestli ji model doopravdy
 * dodrzel - tohle je jediny spolehlivy, jazykove nezavisly signal, ktery se z odpovedi
 * da mechanicky vycist. Cistě observabilita: `adb logcat -s VerbatimCopy` pri beznem
 * cteni ukaze, jak casto k tomu dochazi.
 */
internal fun logIfSuspiciousVerbatimCopy(original: String, translated: String) {
    if (!isSuspiciousVerbatimCopy(original, translated)) return
    if (BuildConfig.DEBUG) Log.d("VerbatimCopy", "original=\"$original\"")
}

/**
 * Loguje, kdyz preklad vicevetne bubliny (slouceny OCR blok nebo "POKRACUJE Z" navazujici
 * bublina) zjevne zahodil celou vetu - viz [likelyDroppedSentence]. Cistě observabilita:
 * `adb logcat -s DroppedSentence` u nahlaseneho "spojena bublina ztratila vetu" ukaze, jak
 * casto k tomu dochazi a jestli se to tyka konkretniho providera/typu bubliny.
 */
internal fun logIfLikelyDroppedSentence(original: String, translated: String) {
    if (!likelyDroppedSentence(original, translated)) return
    if (BuildConfig.DEBUG) Log.d("DroppedSentence", "original=\"$original\" translated=\"$translated\"")
}

/**
 * Loguje, kdyz preklad zjevne ignoroval glosarovy pojem (viz [isGlossaryViolation]) -
 * cistě observabilita, stejny vzor jako predchozi dve funkce. `adb logcat -s
 * GlossaryViolation` pri beznem cteni ukaze, jak casto se to stava a u ktereho providera.
 */
internal fun logIfGlossaryViolation(original: String, translated: String, glossary: Map<String, String>) {
    if (!isGlossaryViolation(original, translated, glossary)) return
    if (BuildConfig.DEBUG) Log.d("GlossaryViolation", "original=\"$original\" translated=\"$translated\"")
}
