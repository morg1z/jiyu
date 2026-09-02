# Jiyū — Full Technical Audit

Průběžný dokument. Audit 24 fází je víc než jedno sezení - tenhle soubor se
doplňuje postupně, ne najednou. Sekce označené `NEAUDITOVÁNO` ještě nebyly
prozkoumány do hloubky.

## 1. Executive summary

Kódová základna je výrazně lépe udržovaná, než u sólo/portfolio projektu
tohoto rozsahu (360 hlavních + 199 testovacích .kt souborů) bývá obvyklé:

- **Nula** výskytů `GlobalScope`, **nula** TODO/FIXME markerů, jen 29 `!!`
  (a ty prohlédnuté vesměs po explicitním null-checku).
- Room DB má kompletní migrační řetěz (v3→v35, všech 32 migrací zaregistrovaných),
  bez `fallbackToDestructiveMigration` - správná volba pro nenahraditelná
  lokální data (knihovna, historie čtení).
- Supabase RLS je správně nastavené na všech třech uživatelských tabulkách
  (`profiles`, `manga_sync`, `chapter_sync` - scoped na `auth.uid()`),
  `translate_usage` má RLS zapnuté záměrně BEZ jediné policy (nejpřísnější
  varianta, jen service role). Edge funkce (`translate-proxy`) je dobře
  zdokumentovaná včetně vědomě přijatých kompromisů (globální ne per-user
  kvóta - zdůvodněno, žádný SQL injection vektor, žádný JS bridge do
  nedůvěryhodných webů v WebView).
- Historie (`ANALYSIS_REPORT.md`, `CHANGELOG.md` u v1.2.58) ukazuje aktivní,
  disciplinovaný vývoj s regresními testy u opravených bugů.

I přesto se našlo a opravilo několik reálných problémů - viz sekce 3, 15.
Navíc: v pracovním adresáři leželo **rozdělané, necommitnuté** opravy ze
čtyř workerů (viz sekce 15.1) - dokončeno, otestováno a mělo by se
zkontrolovat/commitnout spolu s tímhle auditem.

## 2. Architecture

Jeden Gradle modul (`:app`). Balíčky: `anilist`, `auth`, `backup`, `data`
(db/repository/tracking), `di`, `download`, `local`, `security`, `settings`,
`source` (~120 site-specific + `catalog`/`comic`/`madara` sdílené šablony +
`interceptor` pro Cloudflare), `sync`, `translate` (44 souborů, kompletní
OCR→bubble→layout→LLM→glosář pipeline), `ui` (21 obrazovek), `update`,
`util`, `widget`, `work`. Odpovídá tomu, co popisuje CLAUDE.md, ale
**CLAUDE.md je zastaralé** - "Aktuální stav (2026-07-12)" neodpovídá realitě
(app je na DB v35, CHANGELOG u v1.2.58, ne u DB v14 jak dokument tvrdí).

## 3. Bugs (nalezeno a opraveno v tomhle sezení)

| ID | Severity | Component | Problem | Impact | Fix | Status |
|----|----------|-----------|---------|--------|-----|--------|
| B1 | P1 | translate/BubbleShapeDetector | `edgeAwareShape` fallback neměl poměrový strop vůči vlastnímu textu | Titulek bez bubliny na bílém pozadí dostal masku přes půl stránky | `MAX_SHAPE_TO_TEXT_AREA_RATIO` teď platí i pro raw ray-cast obdélník | **Opraveno, otestováno, commitnuto** (580d803) |
| B2 | P2 | translate/BubbleMerge, OcrEngine | Podezření na false-negative "wall" detekci u kaskádových bublin (nelze potvrdit bez reálných pixelových dat) | Dvě bubliny se sloučí do jedné, tvar se nenajde, vykreslí se přes celou šířku | Přidána observabilita (`BubbleWallCheck` logcat), NE slepá změna prahu (riziko regrese watermark-bugu) | **Observabilita hotová, čeká na reálná data ze zařízení** |
| B3 | P1 | download/DownloadQueue | `enqueue()` používal obyčejné `WorkManager.enqueue()`, ne unikátní práci | Dvojtap na "Stáhnout" / souběžný auto-download z `ChapterUpdateWorker` mohly spustit DVA workery nad stejnou kapitolou, zápis do stejných souborů stránek | `enqueueUniqueWork(..., ExistingWorkPolicy.KEEP, ...)` | **Opraveno, test přidán (`DownloadQueueTest`)** |
| B4 | P2 | ui/reader/ReaderPager | `BoxWithConstraints` v hot-path čtečky (jeden na KAŽDOU stránku) nepoužíval svůj scope - zbytečná subkompozice | Zbytečný výkonový náklad při každém otočení stránky | `BoxWithConstraints` → `Box` | **Opraveno** (lint `UnusedBoxWithConstraintsScope`) |
| B5 | P3 | res/values-{en,es,fr}/strings.xml | 18 řetězců chybělo v překladech, `lintDebug` kvůli tomu **padal** (19 errorů) | CI/release lint check byl rozbitý | Doplněny anglické/španělské/francouzské překlady | **Opraveno** (`lintDebug` teď čistý) |
| B6 | P1 | translate/TranslateChapterWorker | `Result.retry()` bez stropu na JAKKOUKOLI výjimku (i trvalou) | Kapitola s trvale rozbitým parsováním/smazanou stránkou by se donekonečna zkoušela přeložit na pozadí | Strop 3 pokusů, stejný vzor jako `SyncWorker`/`AutoBackupWorker`/`ChapterUpdateWorker` | **Opraveno** |
| B7 | P1 | ui/duplicates/DuplicateDetectorViewModel | `viewModelScope.launch` bez try/catch (SupervisorJob nemá vlastní handler) + chybějící `finally` u `isLoading` | Chyba z `getAllLibraryManga()` by appku TVRDĚ SPADLA (ne jen nechala obrazovku točit se) | try/catch (report + tichý no-op) + finally pro isLoading | **Opraveno, regresní test** |

Systematicky prohledáno (Phase 17): všech 8 ViewModelů s `isLoading`/`_loading` flagem
a všech `viewModelScope.launch` bez jediného `catch` v souboru (5 kandidátů). Jediný
skutečný nález byl B7 - zbytek (`BrowseViewModel`, `CustomCssViewModel`,
`OnboardingViewModel` - čisté DataStore zápisy; `DownloadManagerViewModel` - jediné
riziko, `ChapterStorage.deleteRecursively`, má VLASTNÍ interní try/catch+report;
`HistoryViewModel` - čisté Room DAO volání) má zanedbatelné riziko selhání, konzistentní
s tím, jak zbytek projektu podobná nízkoriziková volání řeší.

### 3.1 Rozdělaná práce nalezená v working directory (ne moje, z dřívějška v tomhle sezení)

Při spouštění testů selhala kompilace na chybějícím importu
(`TranslateRepository.kt` - `CancellationException` nebyl importovaný).
Zjištěno, že v pracovním adresáři leží NEcommitnuté, ale kvalitně
zdůvodněné opravy (Czech komentáře v established stylu projektu) ve
4 souborech, žádná z nich moje:

- `translate/TranslateRepository.kt` - izolace jedné poškozené bitmapy od
  zbytku dávky OCR (try/catch kolem `ocrEngine.recognize`, jinak by
  `coroutineScope` zrušil VŠECHNY sourozenecké stránky). **Chyběl import,
  opraveno.**
- `download/ChapterDownloadWorker.kt` - (a) `nm.notify()` obalený
  try/catch proti `SecurityException` z odepřeného `POST_NOTIFICATIONS`
  (jinak by shodilo celé stahování kvůli notifikaci, ne kvůli skutečné
  chybě), (b) přechodné síťové chyby (`IOException`) dostanou strop 3
  automatických pokusů místo trvalého selhání.
- `ui/reader/ReaderViewModel.kt` - `cancelBatchTranslation()` při odchodu
  z kapitoly, jinak rozjeté "Přeložit vše" z PŘEDCHOZÍ kapitoly zapisovalo
  přeložené bloky do `_translatedPages`, které čte UI NOVÉ kapitoly.
- `work/AutoBackupWorker.kt`, `work/ChapterUpdateWorker.kt` - stejný
  strop-3-pokusů vzor jako `SyncWorker`.

Všechno prošlo review, kompiluje a testuje se čistě - doporučuji
commitnout spolu s dnešními opravami (viz `git status` na konci sezení).

## 4. Security (Phase 9/10/15/16)

**Žádný P0/P1 nález.** Zkontrolováno:
- `supabase/schema.sql` - RLS správně na všech tabulkách (viz summary).
- `supabase/functions/translate-proxy/index.ts` - klíče z `Deno.env`,
  žádný SQL injection (parametrizované `.rpc()` volání), CORS `*` je
  v pořádku pro anon-key mobilní klienta bez cookie autentizace.
- `.gitignore` pokrývá `local.properties*`, `google-services.json`,
  `supabase/.temp`, release keystore.
- `AndroidManifest.xml` - `allowBackup=false`, `usesCleartextTraffic=false`
  + `network_security_config.xml` (jedna zdůvodněná výjimka pro
  manga18fx.com, jinak striktně HTTPS + jen systémové CA).
- WebView se používá (Cloudflare challenge solving, MangaCloud bootstrap,
  AniList OAuth login) - **žádný** `addJavascriptInterface`/JS bridge do
  nedůvěryhodného obsahu, což je hlavní RCE vektor u WebView. AniList OAuth
  flow je standardní implicit-flow pattern (token se čte z fragmentu
  vlastního `jiyu://` schématu, nikdy se nikam neposílá).
- Room DB není šifrovaná (SQLCipher) - u osobní appky s daty typu "seznam
  přečtených mang" a bez platebních/citlivých údajů je tohle přijatelné
  riziko, ne nález k opravě.

`REQUIRES DECISION`: žádné - security fáze nevyžaduje žádné rozhodnutí,
jen pokračování do zbylých fází.

## 5. Performance

`NEAUDITOVÁNO` do hloubky - viz B4 (jediný nález zatím, z lintu).

## 6. Database

Viz sekce 1 (migrace) a 3. Zkontrolováno: žádný N+1 vzor (loop přes
manga se dotazem na kapitoly per-item) - agregační dotazy
(`observeUnreadCounts`, `observeTotalCounts`, `observeDownloadedCountPerManga`)
používají `GROUP BY` přesně proto, aby se tomuhle vyhnuly. `ChapterEntity`
má i složené indexy odpovídající skutečným query vzorům
(`mangaId+read`, `mangaId+chapterNumber`), ne jen jednosloupcové.

## 7. Translation

Viz B2 (observabilita čeká na reálná data), B6 (retry strop), 3.1
(izolace poškozené bitmapy). Zbytek pipeline (glosář, fallback providers,
`ProviderHealth`) `NEAUDITOVÁNO` do hloubky.

## 8. Reader

Viz B4. Zbytek (paměť, bitmap pressure, webtoon scroll, process death)
`NEAUDITOVÁNO`.

## 9. Sources

Zkontrolováno staticky (bez zařízení): **žádný P0/P1 nález.**
- Žádné duplicitní `id` mezi ~122 zdroji (ověřeno strojově, `override val id`).
- Rate limiting je SPRÁVNĚ centralizovaný v `AppModule`'s OkHttp interceptoru
  (`SourceRateLimitedException` na HTTP 429), ne rozházený po jednotlivých
  zdrojích - první dojem "jen 1 soubor to používá" byl false positive
  (hledáno jen v `source/`, skutečné místo je `di/`).
- Žádný nebezpečný `.select(...).first()!!.text()` vzor; `!!` se v celém
  `source/` adresáři nevyskytuje ANI JEDNOU.
- Custom Madara zdroj (uživatelem zadaná URL + volitelné CSS selektory,
  `SourceManager.kt:816-839`) padá na rozumné výchozí selektory při
  prázdném vstupu; neplatný CSS selektor od uživatele by za běhu vyhodil
  `Selector.SelectorParseException`, ale ten je zachycen na úrovni
  ViewModelu (`SourceBrowseViewModel` - `catch (e: Exception) { ... toFriendlyMessage() }`),
  takže nejde o pád appky, jen o chybovou hlášku na obrazovce.

`REQUIRES DECISION` (architektura, ne bug): `SourceManager`'s konstruktor má
~122 injektovaných parametrů (jeden na zdroj). Funguje to, ale každý nový
zdroj = zásah do tří míst (import, parametr konstruktoru, `staticSources`
seznam). Hilt multibinding (`@IntoSet`) by tohle zjednodušil, ale je to
mechanický refaktor přes ~120 souborů - vysoké riziko/nízký okamžitý přínos,
neprovedeno bez výslovného zadání.

## 9.1 ComicK / resolver

`NEAUDITOVÁNO` do hloubky - jen letmo zkontrolováno v předchozích sezeních.

## 10. Sync

`NEAUDITOVÁNO`.

## 11. UX

`NEAUDITOVÁNO`.

## 12. Technical debt

Nízké - viz Executive summary (0 TODO, 0 GlobalScope, málo `!!`).

## 13. Dead code

Strojový sweep (každá top-level `class`/`object` v `main/kotlin`, hledány
reference odkudkoli v main+test) našel přesně **1 skutečně mrtvý soubor**:
`source/manhwaraw18/ManhwaRaw18Source.kt` - zdroj byl odregistrován z
`SourceManager` 2026-08-24 (komentář: "manhwaraw18.com nema DNS zaznam"),
ale .kt soubor zůstal. **Smazáno** (e476b44). Zbytek "0 external refs"
byly false positivy vysvětlitelné anotacemi (Hilt `@Module`) nebo referencí
v manifestu (Activity), ne skutečně mrtvý kód.

Nekontrolováno: top-level `fun` (jen `class`/`object`), a `ui/` Composables
(mnohem těžší strojově odlišit "nepoužívané" od "volané přes navigační
graf/reflection").

## 14. Dependencies

Přehled (`app/build.gradle.kts`, 45 `implementation` závislostí) - žádná
zjevná duplicita (jeden image loader, jedna DI, jeden HTTP klient),
všechny odpovídají "vše zdarma" pravidlu (MLKit/ONNX/Supabase/Firebase
mají free tier bez karty). Verze NEKONTROLOVÁNY proti aktuálním
released verzím - vyžaduje živý lookup (`./gradlew dependencyUpdates`
plugin chybí), ne odhad z trénovacích dat. Přidána 1 nová test-only
závislost: `androidx.work:work-testing:2.9.1` (stejná verze jako
`work-runtime-ktx`, jen `testImplementation`, nedostane se do APK).

## 15. Fixed issues

Viz sekce 3 kompletní tabulka + 3.1.

## 16. Remaining issues

Hotovo dnes: 0 (mapa), 1 (build/lint baseline), 3+3.1 (nálezy B1-B7),
4 (security - čisto), 6 (DB - čisto), 9 (sources - čisto + 1 architektonická
poznámka). Zbývá: 2 (Compose recomposition detail), 5/14 (skutečný
výkon/paměť - vyžaduje profiling na zařízení, ne jen statické čtení kódu),
9.1 (ComicK resolver do hloubky), 10 (auth edge-cases), 11 (sync - jen
částečně přes B-nálezy), 13 (zbytek download systému mimo idempotenci),
18 (UX), 19 (testing gaps mimo to, co se dopisovalo cestou), 20 (dead code
- potřebuje referenční sweep), 21 (dependencies verze/bezpečnost), 22-24.

## 17. Recommended roadmap

1. ~~Commitnout dnešní opravy~~ - hotovo (3 commity: 580d803, 17e31f6,
   e7d5a83, ac585b6).
2. Získat reálná logcat data pro B2 (`adb logcat -s BubbleWallCheck`).
3. Pokračovat zbylými fázemi (viz sekce 16) v dalších sezeních - reálný
   výkon/paměť vyžaduje profiler na zařízení, ne statickou analýzu.
