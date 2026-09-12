package com.haise.jiyu.data.db

import androidx.room.withTransaction

/**
 * Uklidí mangu, kterou sis jen prohlédl v Procházení a nikdy nepřidal do knihovny.
 *
 * ## Proč to vzniklo
 * Manga se nikdy nemaže - `removeFromLibrary` jen nastaví `inLibrary = false` a v `MangaDao`
 * není jediný DELETE. Procházení přitom mangu do databáze VKLÁDÁ (viz
 * `MangaRepository.upsertMangaMetadata`), takže každý otevřený detail tam zůstane navždy i se
 * staženými kapitolami. Není to poškození dat, ale pomalé bobtnání: kdo hodně prochází a málo
 * přidává, tomu roste databáze i zálohy.
 *
 * ## Proč to není přes cizí klíče s CASCADE
 * Původní zadání znělo "přidat ForeignKey". SQLite ale neumí přidat constraint k existující
 * tabulce - Room by musel každou z pěti dotčených tabulek postavit znovu a data překopírovat.
 * To je na živé databázi s cizí knihovnou nesrovnatelně větší riziko než tenhle explicitní
 * úklid, a přínos je jen "kdyby na to budoucí kód zapomněl". Až bude potřeba tabulky měnit
 * z jiného důvodu, cizí klíče se přidají při té příležitosti.
 *
 * ## Co se NEMAŽE (a proč)
 *  - manga v knihovně nebo označená jako oblíbená - to je zjevné,
 *  - manga s jakoukoli historií čtení nebo `lastReadAt` - četl ji, i když ji nepřidal,
 *  - manga zařazená do kategorie - to je vědomé uspořádání uživatele,
 *  - manga se STAŽENOU kapitolou (`localPath IS NOT NULL`) - smazat záznam by nechalo
 *    soubory ležet na disku bez čehokoli, co by na ně ukazovalo,
 *  - manga vlastnící kapitolu, na kterou aktuálně ukazuje nějaký `fallbackChapterId` - viz
 *    `SourceResolverViewModel.resolveCompleteChapter`, trvale zapsaná naučená náhrada za
 *    kapitolu s málo stránkami, ne náhodný bordel.
 */
suspend fun AppDatabase.deleteBrowsedManga(): Int = withTransaction {
    val dao = mangaDao()
    val ids = dao.browsedMangaIds()
    if (ids.isEmpty()) return@withTransaction 0
    // Potomci první - bez cizích klíčů je nikdo neuklidí sám a zůstali by jako sirotci.
    // Po dávkách, protože SQLite má strop na počet parametrů jednoho dotazu (999).
    ids.chunked(400).forEach { chunk ->
        dao.deleteChildrenOfManga(chunk)
        dao.deleteMangaByIds(chunk)
    }
    ids.size
}
