# Jiyū - kontext projektu

Osobní Android manga reader (Kotlin, Jetpack Compose, Hilt, Room). Vzniká jako
portfolio/vzdělávací projekt, ne pro komerční distribuci ani veřejné publikování.

## Pevná pravidla (nezpochybňovat, i kdyby to zrychlilo práci)

1. **Vše musí být zdarma.** Žádné placené API, žádné placené knihovny, žádné
   služby vyžadující platební kartu (ani na free tier, který by po limitu strhával peníze).
2. **Žádné scrapery na neoprávněné scanlation/piracy stránky** (příklady toho,
   co nedělat: manhwa68, manhuaplus, asurascans, dragontea scans, nhentai a
   podobné). Tohle je pevná hranice, ne otázka vyjednávání.
3. **Zdroje musí mít buď oficiální veřejné API, nebo být self-hosted vlastní
   obsah** (např. Komga/Kavita nad vlastní naskenovanou/zakoupenou sbírkou).
   MangaDex (oficiální API) je referenční příklad, jak se zdroj dělá.
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
- `di/AppModule.kt` - Hilt poskytuje OkHttpClient a Room databázi.

## Aktuální stav

Hotovo: knihovna, browse/search (MangaDex), detail s kapitolami, offline
stahování, čtečka (HorizontalPager, čte lokální i vzdálené stránky).

Nehotovo / další kroky (v pořadí priority):
1. Ověřit že projekt sesynclý v Android Studiu a buildí se (vznikl v sandboxu
   bez přístupu na Google Maven, takže se to ještě neověřovalo naživo).
2. Přidat druhý zdroj - buď licencovaný webový zdroj (MANGA Plus by Shueisha,
   Webtoons.com - obojí zdarma a legální, ale bez veřejného API, takže
   potřeba Jsoup parsování jejich readeru), nebo self-hosted Komga/Kavita
   nad vlastní sbírkou.
3. AI překladač - nový modul `translate/`, pipeline: detekce textových bublin
   → OCR (ML Kit Text Recognition, závislost už je v build.gradle.kts,
   funguje offline) → překlad přes Groq API (Llama 3.3 70B, zdarma tier,
   stejné jako v Mediflow projektu) → vykreslení overlaye zpět do stránky.
   Napojit do `ReaderScreen`, cachovat přeložené stránky v Room, ať se
   nepřekládá opakovaně.
4. Nastavení appky (mazání stažených kapitol, volba cílového jazyka).

## Styl práce

Vysvětluj kroky stručně česky, než něco uděláš. Když narazíš na rozhodnutí,
co by mohlo znamenat porušení pravidel výše (např. "přidej mi zdroj X"), zeptej
se místo automatického postupu.
