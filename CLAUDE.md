# Jiyū - kontext projektu

Osobní Android manga reader (Kotlin, Jetpack Compose, Hilt, Room). Vzniká jako
portfolio/vzdělávací projekt, ne pro komerční distribuci ani veřejné publikování.

## Pevná pravidla (nezpochybňovat, i kdyby to zrychlilo práci)

1. **Vše musí být zdarma.** Žádné placené API, žádné placené knihovny, žádné
   služby vyžadující platební kartu (ani na free tier).
2. **Podpora pro flexibilní webové zdroje (HTML Scraping).** Projekt plně podporuje
   vytváření parserů pomocí knihovny Jsoup pro parsování veřejných webových stránek.
3. **Generická architektura.** Zdroje, které nemají oficiální API, se implementují
   pomocí univerzálních šablon (např. generická šablona pro weby typu Madara,
   MangaStream atd.). Konkrétní CSS selektory a URL adresy mohou být dynamické nebo
   konfigurovatelné uživatelem.
4. **Appka nikdy neotevírá browser.** Všechno - čtení kapitol, procházení -
   jede přímo v appce přes `MangaSource` rozhraní.
5. **Offline stahování je klíčová funkce**, ne nice-to-have. Kapitoly se
   stahují na pozadí (WorkManager) a čtou se i bez internetu.
6. Cílová platforma: nativní Kotlin/Compose, minSdk 26. Žádný Flutter/RN switch.

## Architektura (už postavená, drž se tohoto vzoru)

- `source/` - `MangaSource` rozhraní (search, getPopular, getMangaDetails,
  getChapterList, getPageList). Nový zdroj = nová třída implementující tohle
  rozhraní, zaregistrovaná v `SourceManager`.
- `data/db/` - Room: `MangaEntity` (knihovna), `ChapterEntity` (kapitoly +
  stav stažení `DownloadStatus`).
- `data/repository/MangaRepository` - jediné místo, co kombinuje zdroje + DB.
- `download/` - `ChapterDownloadWorker` (WorkManager) + `DownloadQueue`.
- `ui/` - Compose obrazovky podle vzoru Screen + ViewModel (Hilt), po jedné
  složce na obrazovku: library, browse, detail, reader.
- `di/AppModule.kt` - Hilt poskytuje OkHttpClient and Room databázi.

## Aktuální stav

Hotovo (2026-07-06):
- Knihovna: kategorie, sort (název/aktualizace/nepřečtené), progress, offline
  badge, pokračovat ve čtení
- Browse: MangaDex, MANGA Plus, ComicK, generický Madara (uživatel přidá URL
  + volitelné CSS selector overridy v Nastavení) + nekonečný scroll
- Detail: status chip, datum kapitol, scanlation group, sdílení, bulk download
- Čtečka: HorizontalPager + webtoon, jas, tap zóny, velikost textu,
  dvoustránkový spread, AI překlad s volbou zdroj→cíl jazyka (13 jazyků)
- Download Manager, periodické aktualizace na pozadí + notifikace
- Záloha/obnovení (JSON), nastavení čtečky
- Unit testy (DAO, Room migrace, Madara parser), live test na emulátoru
- Room DB v7, WorkManager + HiltWorkerFactory správně inicializovány

## Styl práce

Vysvětluj kroky stručně česky, než něco uděláš. Kód piš čistě v Kotlinu podle
architektury projektu.