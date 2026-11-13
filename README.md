# Jiyū

*(English below, Czech / čeština níže)*

## English

Personal Android reader for manga, manhwa, manhua, American comics and light
novels, with its own extension architecture (like Tachiyomi/Kotatsu). Strictly personal
use, no paid APIs or libraries.

### Two ways to browse

During first-run setup (and any time after, in Settings → Manga sources)
you choose how the app should feel:

- **Aggregated catalog (ComicK style)** - one unified catalog pulled from
  comick.io's own API: browsing, search, filters, genres/tags, scanlation
  groups, comments and a live updates feed all look and behave like the real
  ComicK website. Actually reading a chapter is resolved automatically behind
  the scenes - the app cross-checks every real reading source it knows for
  that title and opens the one that actually has the chapter you asked for,
  or the closest match if none has it exactly, so it never silently lands
  you on a source whose translation has stalled or only started much later.
  This mode covers manga, manhwa and manhua only - no light novels, no
  American comics.
- **Manual source browsing** - the classic way: browse and pick from all
  150+ built-in sources one by one, including light novels and American
  comics.

Both modes share the same downloaded library and reading history, so
switching later doesn't lose anything.

### Running it

1. Open the `jiyu` folder in Android Studio.
2. Sync should just work - all dependencies are standard (Compose, Room,
   Hilt, Coil, OkHttp, WorkManager) and come from
   `google()`/`mavenCentral()`.
3. `minSdk 33` / `targetSdk 36` - runs on an emulator or a real device
   (Android 13+; the floor is set by an AGSL shader effect used in the reader
   that doesn't exist on older Android versions).

Tests: `./gradlew testDebugUnitTest`. Build APK: `./gradlew assembleDebug`.

### Stack

- Kotlin + Jetpack Compose, Hilt (DI), Room (local DB)
- WorkManager - background chapter downloads and periodic new-chapter checks
- OkHttp + Jsoup - HTTP and HTML parsing for sources without an official API
- Coil - images, DataStore - settings
- Supabase - authentication (Google sign-in through Android's Credential
  Manager, or plain email/password), cloud library sync, community features

### Features

**Sources** - 150+ `MangaSource` implementations (manga, manhwa, manhua,
American comics, light novels). MangaDex, ComicK and MANGA Plus go through
official APIs, the rest are scrapers for specific sites or the generic
templates - Madara (`source/madara`), MangaThemesia (`source/mangathemesia`)
and ZeistManga, i.e. Blogger sites read through their JSON feed
(`source/zeistmanga`) - where adding a new site is just a base URL and a few
options. On top of that comes a community catalog of ~90 more sites on those
templates (`source/community`, see `docs/community-sources.md`), each one
verified by a live smoke test before it was added. A weekly CI job re-runs
that test. The browse screen has a compact filter row (content type +
language) with searchable bottom sheets; the language list only offers
languages that have at least one source.

**Network resilience** - requests retry once on a flaky connection, fail
fast when offline, share cookies with the built-in WebView and cache
responses for a few minutes. Cloudflare and Turnstile checks are solved
automatically in an invisible WebView (no dialogs, no manual verification;
global search verifies protected sources one by one in the background). A
site that moved to a new address is detected (`MirrorProbe`), mirrors can be
set per source, and there is an optional HTTP/SOCKS proxy and an opt-in image
proxy. Errors carry an action (retry, open the source's site, use the new
domain) instead of a bare message.

**Reader** - horizontal and webtoon mode, reads downloaded files or streams
straight from the URL, never opens a browser. Configurable page zoom (fit
width, fit height, fit screen, or stretch), double-page spread in landscape,
automatic cropping of blank page borders, an OLED-friendly pure black
background between pages, a fullscreen toggle, adjustable webtoon scroll
speed, and automatic advance to the next chapter.

**Offline downloads** - a WorkManager worker downloads a whole chapter in
the background, optionally zips it into a `.cbz`. Chapters are saved under a
readable `Manga Title/0012 - Chapter Name` structure, so you can just copy
them to a PC.

**Account & cloud sync** - optional sign-in with Google or with email and
password (Supabase-backed). Once signed in, the library, reading history and
settings sync across every device you use, and ComicK titles unlock
community features - comments with reply threads, personalized
recommendations, and update-check preferences (which content types,
demographics and maturity ratings to include when the app looks for new
chapters).

**Library & tracking** - categories, reading history, reading goals,
statistics, duplicate-title detection (grouped by creator, so two different
series sharing a name aren't flagged), AniList/MyAnimeList/Kitsu/MangaUpdates
sync, an optional ComicK account link - OAuth sign-in, a previewed import of
your whole ComicK library, and configurable progress sync (off, pull-only,
or two-way) - library and settings backup/restore (JSON export/import),
sharing a manga via QR code, a home screen widget.

**First-run setup** - a short guided setup on first launch: app language,
browsing mode (aggregated vs. manual, see above), reading direction and
style, download folder, and age/privacy (crash reporting is opt-in and off
by default). Nothing asked here is required to use the app, and every choice
can be changed later in Settings.

### Architecture

- `source/` - the `MangaSource` interface + one implementation per source,
  plus `source/comick/` for the ComicK API client and the chapter resolver
  that finds a readable source for a title opened in aggregated mode.
- `data/db/` - Room (library, chapters, download state, history)
- `data/repository/` - the single place that combines sources + database
- `download/` - WorkManager worker for background downloads
- `source/interceptor/` - the shared OkHttp chain (retry, offline check,
  slowdown, cookies, Cloudflare, caches, proxies); `source/community/` - the
  community catalog built on the templates
- `sync/`, `anilist/`, `auth/` - cloud sync and tracker integrations
  (cloud sync pages through the results and pushes only changed chapters)
- `supabase/` - cloud database schema (`schema.sql`) and migrations
- `ui/` - Compose screens + ViewModels, one folder per screen (`ui/comickhome`
  and `ui/resolver` cover the aggregated catalog, `ui/account` the cloud
  account)

### What's next

- More sources as needed - cheap to add thanks to the `MangaSource` interface
- App settings - finer control over image quality on download

---

## Čeština

Osobní Android reader na manga/manhwu/manhuu/americké komiksy/light novely
s vlastní extension architekturou (jako Tachiyomi/Kotatsu). Čistě pro osobní
použití, žádné placené API ani knihovny.

### Dva styly procházení

Při prvním spuštění (a kdykoli potom v Nastavení → Zdroje mang) si vybereš,
jak má appka fungovat:

- **Agregovaný katalog (styl ComicK)** - jeden sjednocený katalog tažený
  přímo z API comick.io: procházení, hledání, filtry, žánry/tagy,
  scanlation skupiny, komentáře i živý feed aktualizací vypadají a
  fungují stejně jako skutečný web ComicK. Samotné čtení kapitoly se řeší
  automaticky na pozadí - appka projde všechny reálné čtecí zdroje, které
  pro daný titul zná, a otevře ten, který skutečně má požadovanou kapitolu,
  případně nejbližší dostupnou, pokud ji nemá žádný přesně - takže tě nikdy
  potichu nepošle na zdroj, jehož překlad už dávno skončil nebo teprve
  nedávno začal. Tenhle režim pokrývá jen mangu, manhwu a manhuu - bez light
  novel a bez amerických komiksů.
- **Ruční výběr zdrojů** - klasický způsob: procházíš a vybíráš z 150+
  vestavěných zdrojů jednotlivě, včetně light novel a amerických komiksů.

Oba režimy sdílejí stejnou staženou knihovnu a historii čtení, takže
přepnutí později nic nesmaže.

### Jak spustit

1. Otevři složku `jiyu` v Android Studiu.
2. Sync by měl proběhnout bez zásahu - všechny závislosti jsou standardní
   (Compose, Room, Hilt, Coil, OkHttp, WorkManager) a táhnou se
   z `google()`/`mavenCentral()`.
3. `minSdk 33` / `targetSdk 36` - spustí se na emulátoru i fyzickém zařízení
   (Android 13+; hranici dané verzí drží AGSL shader efekt v čtečce, který
   na starších Androidech neexistuje).

Testy: `./gradlew testDebugUnitTest`. Build APK: `./gradlew assembleDebug`.

### Stack

- Kotlin + Jetpack Compose, Hilt (DI), Room (lokální DB)
- WorkManager - stahování kapitol a periodická kontrola nových kapitol na
  pozadí
- OkHttp + Jsoup - HTTP a HTML parsing pro zdroje bez oficiálního API
- Coil - obrázky, DataStore - nastavení
- Supabase - přihlášení (Google přes Android Credential Manager, nebo klasicky
  e-mail a heslo), cloud sync knihovny, komunitní funkce

### Co umí

**Zdroje** - 150+ implementací `MangaSource` (manga, manhwa, manhua, americké
komiksy, light novely). MangaDex, ComicK a MANGA Plus běží přes oficiální
API, zbytek jsou scrapery na konkrétní stránky nebo generické šablony -
Madara (`source/madara`), MangaThemesia (`source/mangathemesia`) a
ZeistManga, tedy Blogger weby čtené přes JSON feed (`source/zeistmanga`) -,
kam stačí dodat jen base URL nového webu a pár voleb. K tomu komunitní katalog
zhruba 90 dalších webů na těchto šablonách (`source/community`, viz
`docs/community-sources.md`), každý ověřený živým testem, než se přidal;
týdenní CI úloha ten test opakuje. Procházení má kompaktní řadu filtrů (typ
obsahu + jazyk) s vyhledávatelnými výběry; jazyky nabízí jen ty, pro které
existuje aspoň jeden zdroj.

**Odolnost sítě** - požadavky se při vadném spojení jednou zopakují, offline
selžou hned, sdílejí cookies s vestavěným WebView a odpovědi se pár minut
cachují. Ověření Cloudflare a Turnstile se řeší automaticky v neviditelném
WebView (žádná okna, žádné ruční ověřování; globální hledání ověřuje chráněné
zdroje po jednom na pozadí). Přestěhovaný web se pozná (`MirrorProbe`),
zrcadla jdou nastavit u každého zdroje a je tu volitelná HTTP/SOCKS proxy a
volitelná proxy obrázků. Chyby nesou akci (zkusit znovu, otevřít web zdroje,
použít novou doménu), ne jen hlášku.

**Čtečka** - horizontální i webtoon mód, čte stažené soubory nebo streamuje
přímo z URL, nikdy neotevírá browser. Nastavitelné přiblížení stránky (na
šířku, na výšku, na obrazovku, nebo roztáhnout), dvoustránkové zobrazení na
šířku, automatický ořez prázdných bílých okrajů, čistě černé pozadí mezi
stránkami šetřící OLED displej, přepínač celé obrazovky, nastavitelná
rychlost scrollování u webtoonu, automatický přechod na další kapitolu.

**Offline stahování** - WorkManager worker stáhne celou kapitolu na pozadí,
volitelně zabalí do `.cbz`. Kapitoly se ukládají pod čitelnou strukturou
`Název mangy/0012 - Název kapitoly`, jde je normálně zkopírovat na PC.

**Účet a cloud sync** - volitelné přihlášení přes Google nebo e-mail a heslo
(běží na Supabase). Po přihlášení se knihovna, historie čtení i nastavení
synchronizují mezi všemi zařízeními, a u titulů na ComicK se navíc odemknou
komunitní funkce - komentáře s vlákny odpovědí, doporučené tituly na míru a
předvolby kontroly aktualizací (jaké typy obsahu, demografie a hodnocení pro
dospělé se mají brát v úvahu při hledání nových kapitol).

**Knihovna a sledování** - kategorie, historie čtení, cíle čtení, statistiky,
detekce duplicitně přidaných titulů (seskupená podle tvůrce, takže dvě různé
série se stejným názvem se nenahlásí jako duplikát), sync s AniList/
MyAnimeList/Kitsu/MangaUpdates, volitelné připojení ComicK účtu - OAuth
přihlášení, import celé ComicK knihovny s náhledem předem a nastavitelný
sync postupu (vypnuto, jen stahovat, nebo obousměrně) - záloha/obnova
knihovny i nastavení (JSON export/import), sdílení mangy přes QR kód,
widget na plochu.

**Úvodní nastavení** - krátký průvodce při prvním spuštění: jazyk appky,
styl procházení (agregovaný vs. ruční, viz výše), styl a směr čtení, složka
pro stahování a věk/soukromí (hlášení pádů je volitelné a ve výchozím stavu
vypnuté). Nic z toho není pro používání appky povinné a všechno jde kdykoli
změnit v Nastavení.

### Architektura

- `source/` - rozhraní `MangaSource` + jedna implementace na zdroj, plus
  `source/comick/` s klientem ComicK API a resolverem kapitol, který k
  titulu otevřenému v agregovaném režimu najde skutečný čtecí zdroj.
- `data/db/` - Room (knihovna, kapitoly, stav stažení, historie)
- `data/repository/` - jediné místo, které kombinuje zdroje + databázi
- `download/` - WorkManager worker pro stahování na pozadí
- `source/interceptor/` - společný řetěz OkHttp (opakování, kontrola offline,
  zpomalení, cookies, Cloudflare, cache, proxy); `source/community/` -
  komunitní katalog postavený na šablonách
- `sync/`, `anilist/`, `auth/` - cloud sync a tracker integrace (cloud sync
  stahuje po stránkách a posílá jen změněné kapitoly)
- `supabase/` - schéma cloudové databáze (`schema.sql`) a migrace
- `ui/` - Compose obrazovky + ViewModely, po jedné složce na obrazovku
  (`ui/comickhome` a `ui/resolver` mají na starosti agregovaný katalog,
  `ui/account` cloudový účet)

### Co dál

- Víc zdrojů podle potřeby - přidávání je levné díky `MangaSource` rozhraní
- Nastavení appky - jemnější kontrola kvality obrázků při stahování
