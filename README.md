# Jiyū

*(English below, Czech / čeština níže)*

## English

Personal Android reader for manga, manhwa, manhua, American comics and light
novels, with its own extension architecture (like Tachiyomi/Kotatsu) and a
built-in AI translator. Strictly personal use, no paid APIs or libraries.

> **Note:** the translator is tuned mainly for Czech (compression rules,
> hyphenation, glossary prompts - all written with Czech output in mind), but
> the target language is just a free-text setting, so other languages work
> too, just with less-tuned prompts.

### Two ways to browse

During first-run setup (and any time after, in Settings → Manga sources)
you choose how the app should feel:

- **Aggregated catalog (ComicK style)** - one unified catalog pulled from
  comick.io's own API: browsing, search, filters, genres/tags, translation
  groups, comments and a live updates feed all look and behave like the real
  ComicK website. Actually reading a chapter is resolved automatically behind
  the scenes - the app cross-checks every real reading source it knows for
  that title and opens the one that actually has the chapter you asked for,
  or the closest match if none has it exactly, so it never silently lands
  you on a source whose translation has stalled or only started much later.
  This mode covers manga, manhwa and manhua only - no light novels, no
  American comics.
- **Manual source browsing** - the classic way: browse and pick from all
  110+ built-in sources one by one, including light novels and American
  comics.

Both modes share the same downloaded library and reading history, so
switching later doesn't lose anything.

### Running it

1. Open the `jiyu` folder in Android Studio.
2. Sync should just work - all dependencies are standard (Compose, Room,
   Hilt, Coil, OkHttp, WorkManager, ML Kit) and come from
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
- ML Kit Text Recognition (EN/CN/KR) plus a dedicated on-device manga OCR
  model for Japanese - on-device OCR either way, no server round-trip
- Gemini, Groq, OpenRouter, Cerebras and Mistral - five independent LLM
  providers chained together as translation fallbacks, all routed through a
  Supabase Edge Function acting as a proxy (the app never holds API keys,
  the client only ever calls its own backend)
- Supabase - authentication (Google sign-in through Android's Credential
  Manager, or plain email/password), cloud library sync, community features

### Features

**Sources** - 110+ `MangaSource` implementations (manga, manhwa, manhua,
American comics, light novels). MangaDex, ComicK and MANGA Plus go through
official APIs, the rest are scrapers for specific sites or the generic
Madara template (`source/madara`), where adding a new site is just a base
URL.

**Reader** - horizontal and webtoon mode, reads downloaded files or streams
straight from the URL, never opens a browser. Configurable page zoom (fit
width, fit height, fit screen, or stretch), double-page spread in landscape,
automatic cropping of blank page borders, an OLED-friendly pure black
background between pages, a fullscreen toggle, adjustable webtoon scroll
speed, automatic advance to the next chapter, and background pre-translation
of the next chapter so it's already waiting when you get there.

**AI bubble translation** - OCR finds the text and the bubble's actual shape
(not just a rectangular box); Japanese has its own dedicated on-device OCR
model tuned for manga lettering, with ML Kit's Japanese recognizer only as a
fallback if it fails, times out, or the output looks degenerate (stuck in a
repeating loop). The LLM translates with context about the work and a
glossary of proper nouns (the glossary learns new terms on the fly from the
model's own answers, and any entry can be marked "protected" so it's used
exactly as written instead of being inflected by grammar), then the overlay
renders the text back into the bubble shape with shrink-to-fit sizing. If
one provider hits a rate limit, the chain automatically falls back to the
next one. A translation that looks broken - a repeated/looping phrase, a
dropped sentence, or a reply that came back in the wrong language entirely -
gets silently retried before anything is shown. The renderer also catches
and quietly fixes common translation-quality slips on its own - leftover
untranslated text, stray punctuation dropped onto its own line, wrong
spacing around punctuation, and words broken mid-way without a hyphen. If a
page still comes out wrong, long-pressing a bubble opens an editor that can
fix just that one line, or retranslate the whole page from scratch.

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
statistics, duplicate-title detection, AniList/MyAnimeList/Kitsu/MangaUpdates
sync, library and settings backup/restore (JSON export/import), sharing a
manga via QR code, a home screen widget.

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
- `translate/` - the whole translation pipeline: OCR → bubble shape
  detection → LLM client → glossary → layout/render overlay
- `sync/`, `anilist/`, `auth/` - cloud sync and tracker integrations
- `ui/` - Compose screens + ViewModels, one folder per screen (`ui/comickhome`
  and `ui/resolver` cover the aggregated catalog, `ui/account` the cloud
  account)

### What's next

- More sources as needed - cheap to add thanks to the `MangaSource` interface
- Improve OCR on harder bubbles (tight shout bubbles, heavily colored title
  pages)
- App settings - finer control over image quality on download

---

## Čeština

Osobní Android reader na manga/manhwu/manhuu/americké komiksy/light novely
s vlastní extension architekturou (jako Tachiyomi/Kotatsu) a zabudovaným AI
překladačem. Čistě pro osobní použití, žádné placené API ani knihovny.

> **Poznámka:** překladač je laděný hlavně na češtinu (kompresní pravidla,
> dělení slov, glosářové prompty - všechno psané s ohledem na český výstup),
> ale cílový jazyk je jen textové nastavení, takže fungují i jiné jazyky,
> jen s méně doladěnými prompty.

### Dva styly procházení

Při prvním spuštění (a kdykoli potom v Nastavení → Zdroje mang) si vybereš,
jak má appka fungovat:

- **Agregovaný katalog (styl ComicK)** - jeden sjednocený katalog tažený
  přímo z API comick.io: procházení, hledání, filtry, žánry/tagy,
  překladatelské skupiny, komentáře i živý feed aktualizací vypadají a
  fungují stejně jako skutečný web ComicK. Samotné čtení kapitoly se řeší
  automaticky na pozadí - appka projde všechny reálné čtecí zdroje, které
  pro daný titul zná, a otevře ten, který skutečně má požadovanou kapitolu,
  případně nejbližší dostupnou, pokud ji nemá žádný přesně - takže tě nikdy
  potichu nepošle na zdroj, jehož překlad už dávno skončil nebo teprve
  nedávno začal. Tenhle režim pokrývá jen mangu, manhwu a manhuu - bez light
  novel a bez amerických komiksů.
- **Ruční výběr zdrojů** - klasický způsob: procházíš a vybíráš z 110+
  vestavěných zdrojů jednotlivě, včetně light novel a amerických komiksů.

Oba režimy sdílejí stejnou staženou knihovnu a historii čtení, takže
přepnutí později nic nesmaže.

### Jak spustit

1. Otevři složku `jiyu` v Android Studiu.
2. Sync by měl proběhnout bez zásahu - všechny závislosti jsou standardní
   (Compose, Room, Hilt, Coil, OkHttp, WorkManager, ML Kit) a táhnou se
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
- ML Kit Text Recognition (EN/CN/KR) plus vlastní on-device manga OCR model
  pro japonštinu - OCR vždycky přímo na zařízení, bez volání serveru
- Gemini, Groq, OpenRouter, Cerebras a Mistral - pět nezávislých LLM
  poskytovatelů zapojených jako zálohy za sebou, všechno přes Supabase Edge
  Function jako proxy (appka nikdy nedrží API klíče, klient jen volá vlastní
  backend)
- Supabase - přihlášení (Google přes Android Credential Manager, nebo klasicky
  e-mail a heslo), cloud sync knihovny, komunitní funkce

### Co umí

**Zdroje** - 110+ implementací `MangaSource` (manga, manhwa, manhua, americké
komiksy, light novely). MangaDex, ComicK a MANGA Plus běží přes oficiální
API, zbytek jsou scrapery na konkrétní stránky nebo generická Madara šablona
(`source/madara`), kam stačí dodat jen base URL nového webu.

**Čtečka** - horizontální i webtoon mód, čte stažené soubory nebo streamuje
přímo z URL, nikdy neotevírá browser. Nastavitelné přiblížení stránky (na
šířku, na výšku, na obrazovku, nebo roztáhnout), dvoustránkové zobrazení na
šířku, automatický ořez prázdných bílých okrajů, čistě černé pozadí mezi
stránkami šetřící OLED displej, přepínač celé obrazovky, nastavitelná
rychlost scrollování u webtoonu, automatický přechod na další kapitolu a
přednačítání překladu další kapitoly na pozadí, aby byla hned připravená.

**AI překlad bublin** - OCR najde text a skutečný tvar bubliny (ne jen
obdélníkový box); pro japonštinu appka používá vlastní OCR model přímo na
zařízení, laděný na manga písmo - ML Kit japonský rozpoznávač zůstává jen
jako záloha, když selže, vyprší mu čas, nebo výstup vypadá zdegenerovaně
(zacyklený na dokola se opakující frázi). LLM přeloží s ohledem na kontext
díla a glosář vlastních jmen (glosář se učí za běhu z odpovědí modelu a
kterýkoli záznam jde označit jako "chráněný", takže se použije přesně tak,
jak je napsaný, místo aby ho appka skloňovala), overlay pak text
vyrenderuje zpátky do tvaru bubliny se shrink-to-fit velikostí písma. Když
jeden poskytovatel narazí na limit, řetězec automaticky zkusí dalšího.
Překlad, který vypadá rozbitě - opakující se/zacyklená fráze, ztracená věta,
nebo odpověď vrácená rovnou v úplně jiném jazyce - se potichu zkusí přeložit
znovu, ještě než se vůbec zobrazí. Vykreslovač si navíc sám hlídá a potichu
opravuje časté chyby kvality překladu - nepřeložené zbytky původního textu,
osamocenou interpunkci na vlastním řádku, špatné mezery kolem interpunkce a
slova rozlomená napůl bez pomlčky. Když i tak stránka vyjde špatně, dlouhý
stisk na bublinu otevře editor, který umí opravit jen tenhle jeden řádek,
nebo přeložit celou stránku úplně znovu.

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
detekce duplicitně přidaných titulů, sync s AniList/MyAnimeList/Kitsu/
MangaUpdates, záloha/obnova knihovny i nastavení (JSON export/import),
sdílení mangy přes QR kód, widget na plochu.

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
- `translate/` - celý překladový pipeline: OCR → detekce tvaru bubliny →
  LLM klient → glosář → layout/render overlay
- `sync/`, `anilist/`, `auth/` - cloud sync a tracker integrace
- `ui/` - Compose obrazovky + ViewModely, po jedné složce na obrazovku
  (`ui/comickhome` a `ui/resolver` mají na starosti agregovaný katalog,
  `ui/account` cloudový účet)

### Co dál

- Víc zdrojů podle potřeby - přidávání je levné díky `MangaSource` rozhraní
- Doladit kvalitu OCR na těžších bublinách (kompaktní shout bubliny, sytě
  barevné title pages)
- Nastavení appky - jemnější kontrola kvality obrázků při stahování
