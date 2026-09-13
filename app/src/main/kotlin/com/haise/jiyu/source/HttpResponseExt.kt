package com.haise.jiyu.source

import okhttp3.Response

/**
 * Tělo odpovědi, ale JEN když server odpověděl úspěšně - jinak vyhodí výjimku.
 *
 * Proč: desítky zdrojů měly vlastní `get()` helper, který vzal tělo odpovědi a při
 * neúspěchu tiše propadl na prázdný řetězec - stav odpovědi vůbec nekontroloval.
 * Když web vrátil 403/404/503, jeho chybová stránka se předala Jsoupu jako by to byl
 * normální obsah, selektory na ní nic nenašly a uživatel dostal prázdný seznam. Mrtvý
 * nebo blokující zdroj tak vypadal úplně stejně jako "tady prostě nic není" - a přesně
 * tohle dlouhodobě maskovalo nefunkční zdroje v katalogu.
 *
 * Vyhozená výjimka se nahoře zachytí (viz SourceBrowseViewModel), nahlásí přes
 * ErrorReporter a uživateli se ukáže skutečná chyba místo prázdné mřížky.
 *
 * POZOR: tohle patří jen do těch jednoduchých helperů. Nesmí se to plošně nasadit jako
 * interceptor - `CloudflareInterceptor` si 403/503 řeší sám (řeší challenge) a některé
 * zdroje na neúspěšný stav vědomě reagují vlastní cestou (viz `MadaraSource`, kde
 * neúspěch legitimně znamená `null`, ne chybu).
 */
fun Response.bodyOrThrow(url: String): String {
    // IOException, ne check()/IllegalStateException - RetryInterceptor (AppModule.kt) chyta
    // jen IOException, takze IllegalStateException tenhle retry uplne obejde (nahlaseno v auditu).
    if (!isSuccessful) throw java.io.IOException("HTTP $code při načítání $url")
    return body?.string().orEmpty()
}
