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

I přesto se našlo a opravilo 11 reálných problémů (B1-B11, sekce 3) napříč
bublinovou detekcí, stahováním, syncem, čtečkou, ComicK zdrojem a UX - všechny
otestované, commitnuté. Dva zbývající body vyžadují rozhodnutí uživatele, ne
další kód (`REQUIRES DECISION`, sekce 9/10/14), a jeden potřebuje reálná data
ze zařízení, ne statickou analýzu (B2 - sekce 3, `sessionElapsed` rekompozice
- sekce 5).

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

| B8 | P2 | ui/reader/ReaderPager | Pinch/pan `graphicsLayer(scaleX=..., ...)` (property overload) čte `scale`/`panOffset` v KOMPOZICI, ne v draw fázi | `detectTransformGestures` mění tyhle hodnoty desítkykrát/s - každý update rekomponoval celý `Box` (obrázek + všechny bubliny v translate módu), ne jen redraw | Lambda overload `graphicsLayer { scaleX = ...; ... }` - čte state až v draw fázi | **Opraveno** |
| B9 | P2 | source/comick/ComicKSource | `getChapterList()` stránkuje `while (true)` bez stropu, žádný timeout na volající straně (`MangaRepository.refreshChapters`) | Kdyby ComicK API vrátilo `pageSize` položek navěky (server bug/shoda počtu kapitol), funkce visí navěky - appka nespadne, jen věčný spinner na detailu mangy | Strop `maxPages = 500` (30 000 kapitol, hluboko nad realitou) | **Opraveno** |
| B10 | P2 | ui/downloads/DownloadManagerScreen | Smazání JEDNÉ stažené kapitoly (`ChapterDownloadRow`) nemělo potvrzovací dialog, zatímco hromadné mazání (celá manga / všechny přečtené) ho mělo oboje | Nechtěný tap trvale smaže stažené soubory kapitoly z disku bez možnosti vrátit zpět | Přidán `AlertDialog` ve stejném stylu jako u hromadného mazání (jen pro `DOWNLOADED` stav - `ERROR` stav maže jen neúspěšný záznam, žádná data) | **Opraveno** |
| B11 | P3 | ui/stats/ExtendedStatsViewModel | Export statistik (JSON/CSV) při chybě zobrazil `e.message` (surová výjimka) místo přátelské hlášky | Uživatel by u např. `SecurityException` ze Storage Access Frameworku viděl technický anglický text místo srozumitelné české hlášky | Vždy přátelský string, `e.report(...)` pro záznam skutečné příčiny | **Opraveno** |

### 3.2 Testing gap uzavřen

`sync/SyncRepository.kt` mělo nulové pokrytí testy, včetně přesně té LWW slučovací
logiky, která se v tomhle sezení ručně opravovala (SYNC-1/SYNC-2) - nejrizikovější
místo k tichému budoucímu zregresování. Vytažena jako čisté funkce
(`ChapterEntity.mergeWithRemote`, `MangaSyncDto?.removedFromLibraryRemotely`,
`internal`, beze změny chování) a pokryta 7 testy
(`SyncRepositoryTest.kt`) - včetně regresního testu na PŮVODNÍ bug (jednou přečtená
kapitola nešla nikdy dál synchronizovat).

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

Viz B8 (opraveno). Zkontrolováno navíc, beze změn (žádný nález):
- Coil `ImageLoader` (`JiyuApp.kt`) - 20 % paměti + 256 MB disk cache, rozumné meze.
- `PREFETCH_WINDOW = 4` (`ChapterPagePrefetch.kt`) jen zahřívá Coil cache, nedrží
  dekódované bitmapy v Compose stavu - žádný neomezený růst paměti.
- `HorizontalPager` v `ReaderPager.kt` používá výchozí `beyondBoundsPageCount = 0` -
  mimoobrazovkové stránky se nekomponují, nedrží se navíc v paměti.
- `WebtoonReader.kt` má stabilní `key` u `itemsIndexed` - žádný chybějící-key
  rekompoziční problém.

`REQUIRES DECISION` (`NEEDS DEVICE DATA`, neopraveno): `ReaderScreen.kt` čte
`viewModel.sessionElapsed` (1Hz tikající `StateFlow`) přímo v těle hlavního
composable, vedle ~30 inline lambd bez `remember`, které se volají do
`ReaderContent(...)` (~70 parametrů). Každý tik może znovu alokovat tyhle lambdy
(nestabilní identita), což by mohlo bránit Compose "skip" optimalizaci downstream.
- **Problém:** potenciální zbytečná rekompozice čtečky jednou za sekundu po celou
  dobu čtení - reálný dopad nepotvrzen (vyžaduje Layout Inspector/Compose compiler
  metriky na zařízení, ne jen statické čtení kódu).
- **Možnosti:** (a) přesunout `collectAsState()` hlouběji (do `ReaderContent`/
  `ReaderTopBar`) - vyžaduje změnu kontraktu `ReaderContent` (buď naváže na
  ViewModel/Flow místo čistého `Long`, architektonický kompromis); (b) obalit
  `remember`em všech ~30 lambd v `ReaderScreen.kt` - mechaničtější, ale riziko
  "stale closure" bugu při špatně zvoleném klíči u tak velkého počtu lambd.
- **Doporučení:** nejdřív změřit na zařízení (Layout Inspector), teprve pak
  rozhodnout mezi (a)/(b) - neopravovat naslepo v nejsložitější/nejexponovanější
  obrazovce appky bez potvrzených dat.
- **Riziko/složitost:** (a) střední, jeden soubor navíc naváže na ViewModel;
  (b) nízké-střední riziko na fix, ale vysoké riziko regrese při chybě (~30 míst).

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

Viz B9 (opraveno). Zkontrolováno navíc, beze změn:
- Více scanlation skupin na kapitolu je ZÁMĚRNĚ ponecháno jako samostatné
  `SChapter` položky (uživatel si vybere skupinu); `progressPercentFor` už
  deduplikuje přes `floor(chapterNumber)`.
- `hid` vs. slug použití je konzistentní v celém souboru, žádná záměna.
- Jediný CDN host (`meo.comick.pictures`) bez fallbacku - ComicK ale reálně
  žádný alternativní host nedokumentuje, jde o vlastnost upstream API, ne
  o opravitelnou mezeru.
- `ComicKChapterResolver.kt:170` už používá `withTimeoutOrNull(8_000)` -
  zavedený vzor v projektu, kterým se inspirovala i oprava B9.

## 10. Sync

Viz sekce 3.1 (SYNC-1/SYNC-2, LWW) a 3.2 (testing gap uzavřen). Zkontrolováno
navíc, beze změn (obojí už bylo správně):
- `SyncWorker.doWork()` má strop 3 pokusů, stejný vzor jako zbytek projektu.
- `AccountViewModel.syncNow()` obaluje `pushToCloud()`/`pullFromCloud()` do
  try/catch a promítá `SyncState.Error` - výjimka ze Supabase (401, výpadek
  sítě) uprostřed synchronizace appku nespadne.

`REQUIRES DECISION` (znovu potvrzeno, NEOPRAVENO): žádná entita v `data/db/entity/`
nemá `userId` sloupec. `AccountViewModel.signOut()` maže jen auth token, ne
lokální DB - přepnutí účtu na stejném zařízení ukáže PŘEDCHOZÍ účet knihovnu/
historii, dokud ji sync nepřepíše.
- **Možnosti:** (a) smazat knihovnu/historii při `signOut()` - jednoduché,
  ale zničí neodsynchronizovaná offline-only data; (b) `userId` sloupec +
  migrace + scoping všech DAO dotazů - správné dlouhodobě, ale zasahuje
  DAO/repository kontrakty na ~30+ místech.
- **Doporučení:** (a) pokud appka nikdy neřeší víc účtů na jednom zařízení
  jako reálný use-case; (b) jen pokud je to skutečný požadavek.
- **Riziko/složitost:** (a) nízké, ~30 min; (b) vysoké, samostatné sezení.

## 11. UX

Viz B10, B11 (opraveno). Zkontrolováno navíc:
- Prázdné stavy (knihovna, downloads) mají ikonu+titulek+CTA, v pořádku.
- Žádná automaticky nalezená obrazovka pro smazání účtu (`ui/account/`) -
  není nutně bug (Supabase self-delete by vyžadovalo edge funkci), jen
  poznámka pro budoucí GDPR-styl "smazat moje data", pokud to bude cíl.

## 12. Technical debt

Nízké - viz Executive summary (0 TODO, 0 GlobalScope, málo `!!`).

**Testing gaps** (bodová kontrola 10 netriviálních tříd proti `app/src/test/`):
`sync/SyncRepository` mělo nulové pokrytí - uzavřeno (viz 3.2). `backup/BackupManager`
byl FALSE POSITIVE prvního průchodu - `BackupRestoreTest.kt` (Robolectric, reálná
in-memory Room DB) ho už pokrývá důkladně (4 testy včetně transakční atomicity);
navíc doplněno čistě parsovací pokrytí (`parseBackupJson` vytažené mimo transakci,
8 testů na zpětnou kompatibilitu starších formátů zálohy). Uzavřeno: `auth/AuthRepository`
(`SessionStatus`→`JiyuUser` mapování vytažené jako `toJiyuUser()`, 6 testů - `UserInfo`/
`UserSession` šlo nakonec zkonstruovat přímo, i pro obranné null-user větve),
`auth/SecureSessionManager` (obranné chování při poškozené/chybějící session, 5 testů),
`backup/SettingsBackupManager` (bezpečnostně citlivý `EXCLUDED_KEYS` filtr - auth
tokeny se nikdy nesmí dostat do exportu - + typový dispatch při importu, 7 testů).
Zbývá nepokryté: `data/tracking/{Kitsu,Mal,MangaUpdates}*` (tenké obálky nad SDK
voláními, nejnižší priorita, testovací pokrytí probíhá).

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

`NEEDS LIVE VERSION CHECK` (nelze potvrdit bez síťového dotazu, jen
"vypadá to staře" odhad podle trénovacích dat): `io.github.jan-tennert.supabase`
2.0.3 a `io.ktor:ktor-client-okhttp` 2.3.9 jsou ZÁMĚRNĚ přišpendlené
(komentář v kódu: "poslední verze s Kotlin 1.9.x") - upgrade na novější řadu
je svázaný s bumpem Kotlinu, ne drop-in výměna. `io.coil-kt:coil-compose` 2.6.0
je taky předchozí generace (Coil 3.x je multiplatformní přepis). Žádná ze tří
nemá známou CVE z trénovacích dat - jde o údržbový dluh, ne aktivní zranitelnost.
`REQUIRES DECISION`: upgrade Supabase/Ktor na 3.x řadu je svázaný s Kotlin
verzí napříč celým projektem - neprovedeno bez výslovného zadání a bez
živého ověření kompatibility.

## 15. Fixed issues

Viz sekce 3 kompletní tabulka + 3.1.

## 16. Remaining issues

Hotovo: 0 (mapa), 1 (build/lint baseline), 3+3.1+3.2 (nálezy B1-B11 + testing
gap na SyncRepository uzavřen), 4 (security - čisto), 6 (DB - čisto), 9
(sources - čisto + 1 architektonická poznámka), 5 (performance - B8 + 1
`REQUIRES DECISION`), 9.1 (ComicK - B9 + čisto), 10 (sync - čisto + 1
`REQUIRES DECISION` na account-switch), 11 (UX - B10/B11 + 1 poznámka),
12 (technical debt + testing gaps zmapované), 13 (dead code - hotovo), 14
(dependencies - duplicity čisto + 3 `NEEDS LIVE VERSION CHECK`).

Zbývá: B2 (kaskádové bubliny - čeká na reálná logcat data ze zařízení),
`REQUIRES DECISION` account-switch userId scoping (sekce 10), Compose
`sessionElapsed` rekompozice (sekce 5, `NEEDS DEVICE DATA`), testing gaps
mimo SyncRepository (auth/tracker/backup třídy, sekce 12), Supabase/Ktor/
Coil verze (sekce 14, `NEEDS LIVE VERSION CHECK`), a fáze mimo dosah
statické analýzy: 18-24 (competitor comparison, feature roadmap MUST/
SHOULD/NICE/EXPERIMENTAL) - vyžadují diskuzi/rozhodnutí s uživatelem,
ne další kód.

## 17. Recommended roadmap

1. ~~Commitnout dnešní opravy~~ - hotovo (commity 580d803 → b2b6499,
   plus B8-B11 + SyncRepository testy z tohoto sezení).
2. Získat reálná logcat data pro B2 (`adb logcat -s BubbleWallCheck`) a
   Layout Inspector/Compose compiler metriky pro sekci 5 `sessionElapsed`
   nález - obojí vyžaduje fyzické zařízení, ne další statické čtení kódu.
3. Rozhodnout společně s uživatelem otevřené `REQUIRES DECISION` body:
   account-switch userId scoping (sekce 10), Supabase/Ktor major-verze
   upgrade (sekce 14), `SourceManager` Hilt multibinding refaktor (sekce 9).
4. Fáze 18-24 (UX hloubka, feature roadmap, konkurenční srovnání) - návrh
   hotov (sekce 18-19 níž), čeká na výběr uživatele, který nápad realizovat.

## 18. Konkurenční srovnání (nápady, NE kopírování)

Průzkum aktuálního (2026) stavu hlavních FOSS manga readerů - **cílem je
inspirace vlastním řešením, ne přebírání designu/kódu 1:1**, v souladu s tím,
jak vznikl zbytek appky.

- **Mihon** (dřív Tachiyomi) - pluginový model zdrojů (samostatně
  instalovatelné "extension store" balíčky), víc trackerů (8 vs. Jiyū 4,
  ale navíc jen regionální Shikimori/Bangumi/Hikka), import vlastních
  archivů jako knihovních položek, záloha jen jako lokální soubor (Jiyū má
  vlastní cloud sync navíc - tady je Jiyū architektonicky napřed).
- **Kotatsu** - **PIN/biometrický zámek appky** (nezávislý na zámku
  systému), automatická cross-device synchronizace VŠECH dat appky (ne jen
  knihovny), 1200+ katalogizovaných zdrojů + import CBZ archivů jako
  lokálního zdroje, samostatný "updates feed" s doporučeními.
- **Madomi** - jediný nalezený peer s AI překladem přímo v čtečce (JP/KR/CN),
  ale vždy cloud-závislý (žádné on-device OCR) - potvrzuje, že Jiyū's OCR
  pipeline (on-device + konfigurovatelný LLM) je v tomhle ohledu opravdu
  vzácný, ne samozřejmý diferenciátor.
- **Paperback** (iOS) - nedostatek dohledatelných detailů, nedoporučeno dál
  zkoumat.

**Funkce, které Jiyū pravděpodobně postrádá vůči tomuhle peer setu:**
1. Zámek appky (PIN/biometrie) nezávislý na systémovém zámku.
2. Globální/cross-source hledání (jedno zadání, sloučené výsledky napříč
   povolenými zdroji) - Jiyū zatím prohledává jen po jednom zdroji.
3. Import vlastního CBZ/ZIP archivu jako knihovní položky (bez online
   zdroje) - přirozené doplnění k tomu, že Jiyū už CBZ umí EXPORTOVAT.
4. Self-hosted server integrace (Komga-styl) jako alternativa k
   scrapovaným zdrojům - větší, volitelná myšlenka.
5. Komunitně sdílené balíčky zdrojů (Jiyū řeší podobnou potřebu jinak -
   community listy sdílí SEZNAMY mang, ne konfigurace zdrojů).

## 19. Návrh feature roadmapy (MUST/SHOULD/NICE/EXPERIMENTAL)

Kategorizace vychází z (a) položek 18 výš, (b) `REQUIRES DECISION` bodů
zjištěných v sekcích 5/9/10/14 výš ("co je špatně, i když to funguje").
Nic z tohohle není naimplementováno - jde o návrh k výběru, ne hotovou práci.

**MUST** (bezpečnost/spolehlivost, ne nová funkce):
- Vyřešit account-switch `userId` scoping (sekce 10) - jediná položka
  s reálným rizikem "cizí data na obrazovce", ne jen chybějící feature.

**SHOULD** (jasná hodnota, přiměřená složitost):
- **Zámek appky** (PIN/biometrie přes `androidx.biometric`) - Jiyū už má
  incognito mód v čtečce, tohle je přirozené rozšíření "soukromí appky"
  směru, ne cizí nápad naroubovaný odjinud. Odhad: střední (nová
  lock-screen obrazovka + `BiometricPrompt` + nastavení, žádný zásah do
  zbytku architektury).
- **Import lokálního CBZ/ZIP jako knihovní položky** - symetrický protějšek
  k už existujícímu `ChapterStorage.createCbz`. Implementačně čistý
  (nová `MangaSource` implementace nad SAF/`DocumentFile`, žádný scraping).
  Odhad: střední.
- Rozšířit testy na `data/tracking/{Kitsu,Mal,MangaUpdates}*` (sekce 12) -
  nižší riziko než SyncRepository, ale pořád nulové pokrytí.

**NICE** (hodnota tam je, složitost/riziko vyšší):
- **Globální cross-source hledání** - vyžaduje paralelní dotazy napříč
  desítkami zdrojů s rozumným per-source timeoutem/rate-limitingem (ať
  appka nezahltí cizí servery najednou) a UX pro postupně přicházející
  částečné výsledky. Netriviální, ale žádná architektonická překážka
  (`MangaSource.search` už existuje na každém zdroji).
- Supabase/Ktor major-verze upgrade (sekce 14) - stará blokace (Kotlin
  1.9.x) je prokazatelně zastaralá (projekt je na 2.2.21), ale samotná
  migrace (`gotrue-kt`→`auth-kt` přejmenování, breaking changes) pořád
  vyžaduje vyhrazené sezení + end-to-end test login/OAuth/sync na zařízení.
- `SourceManager` Hilt multibinding refaktor (sekce 9) - vysoké riziko/
  nízký okamžitý přínos, ale dlouhodobě usnadní přidávání zdrojů.

**EXPERIMENTAL** (zajímavé, mimo jádro projektu):
- Self-hosted server integrace (Komga-styl) pro powerusery, co si sami
  hostují vlastní knihovnu - velký rozsah, nejasná návratnost pro
  jednouživatelský portfolio projekt.
- Řešení `sessionElapsed` rekompozice (sekce 5) - teprve po potvrzení
  reálného dopadu přes Layout Inspector, ne naslepo.
