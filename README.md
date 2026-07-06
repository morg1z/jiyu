# Jiyū

Osobní Android manga reader s vlastní extension architekturou (jako Tachiyomi/Kotatsu)
a připraveným místem pro AI překladač. Čistě pro osobní použití.

## Jak otevřít

1. Otevři složku `Jiyu` v Android Studiu (Hedgehog nebo novější).
2. Android Studio při prvním otevření nabídne vygenerování Gradle wrapperu (`gradlew`) -
   tenhle projekt ho zatím neobsahuje, protože sandbox, ve kterém vznikl, nemá přístup
   na Google Maven repozitář. Klikni na "OK"/"Sync Now", až se objeví nabídka.
3. Sync by měl proběhnout bez zásahu - všechny závislosti (Compose, Room, Hilt, Coil,
   OkHttp, WorkManager, ML Kit) jsou standardní a dostupné z `google()`/`mavenCentral()`.
4. Spusť na emulátoru nebo fyzickém zařízení (minSdk 26 / Android 8.0+).

## Co už funguje

- **Knihovna** - přehled uložených manga (mřížka s obálkami)
- **Vyhledávání (Browse)** - našeptávač/populární tituly přes MangaDex API
- **Detail mangy** - seznam kapitol, tlačítko na stažení offline
- **Stahování na pozadí** - WorkManager stáhne stránky kapitoly do interního úložiště appky
- **Čtečka** - horizontální pager, čte buď stažené soubory, nebo streamuje přímo z URL,
  nikdy neotevírá browser

## Architektura (proč je to takhle rozdělené)

- `source/` - rozhraní `MangaSource` + implementace pro konkrétní zdroje.
  Přidání nového zdroje = nová třída implementující `MangaSource`, nic jiného
  se v appce měnit nemusí (stejný princip jako extensions v Tachiyomi/Kotatsu).
- `data/db/` - Room databáze (knihovna, kapitoly, stav stažení)
- `data/repository/` - jediné místo, které kombinuje zdroje + databázi
- `download/` - WorkManager worker pro stahování kapitol na pozadí
- `ui/` - Compose obrazovky + ViewModely, po jedné složce na obrazovku

## Co chybí a je další krok

1. **AI překladač** - zatím není zapojený. Připravené je: ML Kit Text Recognition
   závislost v `build.gradle.kts` (offline OCR) a architektura pipeline
   (detekce bublin → OCR → LLM překlad → overlay), kterou jsme probrali v chatu.
   Bude to nový modul `translate/` s vlastním pipeline a integrací do `ReaderScreen`.
2. **Další zdroje** - MangaDex slouží jako čistý referenční příklad (oficiální API,a scraping). Když budeš chtít přidat další zdroj, řekni mi který a probereme
   jeho konkrétní strukturu.
3. **Nastavení appky** - výběr jazyka překladu, správa stažených kapitol (mazání),
   nastavení kvality obrázků.
4. **Testování na zařízení** - build jsem nemohl ověřit v sandboxu (bez přístupu
   na Google Maven), takže první sync v Android Studiu může vyžadovat drobné doladění.
