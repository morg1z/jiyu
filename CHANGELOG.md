# Changelog

## v0.3.2

### Opravené chyby
- Oprava zaseklého dialogu "ověření, že nejsi robot", který se donekonečna opakoval, pokud web appku trvale zablokoval (ne řešitelná výzva, rovnou "Sorry, you have been blocked"). Appka si teď po neúspěchu na 10 minut pamatuje, že daný web je zablokovaný, a nezobrazuje dialog znovu pro každý další obrázek/stránku - týká se všech zdrojů v appce. Přidáno i viditelné tlačítko "Zavřít" do dialogu.

## v0.3.1

### Opravené chyby
- Přeložený text v čtečce se už nepřekrýval sám se sebou ani nepřetékal mimo bublinu - OCR teď slučuje textové řádky do bublin přesněji, box pro překlad dostane jen tolik místa, kolik je volné k nejbližší sousední bublině, a velikost písma se automaticky zmenší, aby se text vešel. Styl překladu změněn z černého boxu s bílým textem na bílý štítek s tmavým textem (méně ruší kresbu).

### Nové zdroje
- Přidány Manga18fx, Hentai20.io a Webtoon XYZ.

## v0.2.2

### Bezpečnost
- Odstraněn nevyužívaný exportovaný deep link `jiyu://anilist` (implicit-flow OAuth token by přes něj teoreticky mohl zachytit jiný nainstalovaný app se stejným schématem).

### Opravené chyby
- Světlý režim: opraven hardcoded tmavý horní gradient, kvůli kterému byl v light theme nečitelný horní panel.
- Opraven kontrast textu v tmavých dialozích/sheetech (obálky, hromadné akce v knihovně, filtr v Procházet, potvrzovací dialogy), které v light theme používaly na pevně tmavém pozadí barvy reagující na motiv.
- Opraveno přetékání textu v horní liště čtečky (název kapitoly se ořezával po pár znacích kvůli přeplněné řadě ikon).
- Sjednoceny nekonzistentní (mix anglicky/česky) popisy zdrojů v katalogu zdrojů.
- **Americké komiksy a Light Novel zdroje**: většina vestavěných zdrojů byla dlouhodobě nefunkční (mrtvé domény, ukončené služby, Cloudflare/JS ochrana). Odstraněno 12 mrtvých comic zdrojů (ReadComicOnline, ReadAllComics, ViewComic, XoxoComics, ZipComic, ComicPunch, GoComics, GlobalComix, ComicKingdom, ComicExtra, ReadComicsOnline, SuperHeroComics) a 3 mrtvé novel zdroje (BoxNovel, LightNovelWorld, LightNovelPub). Nahrazeno funkčními alternativami (ReadFreeComicsOnline, FreeWebNovel) a opraveny zbylé rozbité zdroje (GetComics, ComicBookPlus, NovelFull).

### Nové funkce
- Plná internacionalizace uživatelského rozhraní (čeština/angličtina/francouzština/španělština) - předtím byla externalizována jen malá část textů.

## v0.2.1

### Bezpečnost
- Odstraněn GROQ_API_KEY z klientské appky (dal se vytáhnout přímo z veřejného APK). AI překlad teď jde přes server-side Supabase Edge Function proxy (`translate-proxy`), klíč zůstává jen na serveru.
- Přidán rate-limiting proti zneužití proxy (denní limit počtu požadavků a znaků na uživatele).
- Zrušena funkce AI shrnutí kapitol / AI analýza mangy (aby nebylo nutné vystavovat další klíč přes druhé proxy).

### Opravené chyby
- Oprava pádu při AI překladu v čtečce (`NoSuchMethodError` na `JSONObject.put(String, float)`).
- Oprava pádu při zálohování knihovny, pokud obsahovala alespoň jednu kapitolu (stejná příčina jako výše).
- Oprava kontroly aktualizací, která nenacházela nové verze.

### Nové funkce
- Aktualizace se nyní stahují a instalují přímo v appce (systémový DownloadManager + notifikace), místo otevírání GitHubu v prohlížeči.
- Nová ikona aplikace.
- Nastavení kompletně přestavěno do stylu Kotatsu: 10 kategorií na hlavní stránce, každá se otevírá do vlastní podstránky ("O aplikaci" jako poslední).

## v0.2.0

- Nová Knihovna dashboard, redesign Procházet.
- Oprava pádu při AI překladu.
- Oprava únikajícího API klíče (první průchod, dořešeno v v0.2.1).
