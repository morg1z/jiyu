# Changelog

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
