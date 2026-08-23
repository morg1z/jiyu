# Changelog

> Poznámka: mezi v0.3.3 a v0.8.0 se tenhle soubor neudržoval. Co se dělo mezitím, je
> vidět v historii commitů a v popisech jednotlivých vydání na GitHubu; zpětně to sem
> nedopisuju, abych si nevymýšlel.

## v1.2.55

### Nové: tlačítko Domů v panelu čtečky
Do spodního panelu čtečky (mezi ikonu překladu a jasu) přibylo tlačítko domečku - okamžitě
přeskočí do Knihovny bez nutnosti se ručně proklikávat zpátky přes detail mangy.

## v1.2.54

### Oprava: znovu přidaná manga zdědila starý stav "přečteno"
Odebrání mangy z knihovny mangu ani kapitoly nemazalo (jen skrylo z knihovny), takže při
pozdějším znovu-přidání stejného titulu appka potichu obnovila starý stav čtení z doby před
odebráním - čerstvě přidaný titul tak mohl vypadat jako už kompletně přečtený, i když jsi
neotevřel/a jedinou kapitolu. Odebrání z knihovny teď zároveň resetuje stav čtení (přečteno,
pozici, čas čtení), takže další přidání téhož titulu je vždy opravdu čisté.

### Oprava: Můj seznam měl zbytečně velké okraje od hran obrazovky
Karty v mřížkovém zobrazení (16dp) i řádky v seznamovém zobrazení (16dp) měly větší odstup od
levého/pravého okraje než zbytek appky. Zmenšeno na 6dp.

## v1.2.53

### Oprava: Novinky ukazovaly starý archiv jako nové a duplicitní upozornění u ComicK
"Novinky" braly datum VYDÁNÍ kapitoly na zdroji, ne kdy ji appka objevila - při přidání mangy
s už existujícím archivem (např. 8 kapitol) appka celý archiv ukázala/oznámila jako 8 nových
kapitol, i když jde jen o kapitoly starší, než kdy jsi mangu přidal. Nová kapitola se teď počítá
podle toho, kdy ji appka poprvé zaznamenala, ne podle původního data vydání - takže Novinky
začnou "od teď", ne od celé historie.

Zároveň platí, že u agregovaných zdrojů (ComicK) může stejné číslo kapitoly vydat víc
překladatelských skupin zvlášť - to už appka nebude počítat/oznamovat víckrát za sebou, jen
jednou za číslo kapitoly.

### Oprava: barvy štítků MANHWA/MANHUA byly nerozeznatelné
Štítky typu obsahu MANHWA a MANHUA ukazovaly stejnou fialovou barvu (obě byly ve skutečnosti
jen alias na stejnou barvu motivu, i mezi mřížkovým a seznamovým zobrazením se navíc lišily).
MANHWA je teď modrá, MANHUA zlatá - a obě zobrazení používají stejnou paletu.

## v1.2.52

### Oprava: pomalé stahování aktualizací (Novinky + Knihovna)
Ruční obnovení v záložce Novinky a "potáhnutím obnovit" v Knihovně kontrolovalo každou mangu v
knihovně jednu po druhé - jeden pomalejší/Cloudflare chráněný zdroj tak zdržel všechny ostatní
za sebou. Teď se kontroluje až 5 najednou (stejně jako už dřív dělala tichá kontrola na pozadí),
takže by to mělo být citelně rychlejší.

## v1.2.51

### Oprava: možná příčina náhodných pádů appky/kapitoly u srolování stránky
GL vykreslovací vlákno (u stylu "Srolování stránky") nemělo žádné zachytávání chyb - když se
nepodařilo připravit texturu (např. appka mezitím stihla vyměnit stránku, než vlákno stihlo
předchozí), pád na tomhle vlákně shodil CELOU appku, ne jen tenhle jeden snímek. Teď se taková
chyba jen nahlásí a přeskočí, appka/kapitola by se tím pádem neměla zavírat.

## v1.2.50

### Oprava: dlaždicová karta v Mém seznamu měla rámeček na všech stranách
V dlaždicovém (mřížkovém) zobrazení Mého seznamu měla každá karta trvalý fialový rámeček
kolem celé obálky - vedle čistého spodního pruhu postupu v seznamovém zobrazení to působilo
jako "pruhy i po bocích". Rámeček kolem celé karty teď zůstává jen u vybrané položky, postup
čtení ukazuje tenký pruh dole (stejně jako v seznamovém zobrazení).

## v1.2.49

### Nové: zdroj BatCave (americké komiksy)
Přidán zdroj BatCave (batcave.biz) - Marvel/DC a další vydavatelé. Web je za Cloudflare a má
netypickou čtečku (seznam kapitol a obrázky stránek se stahují přes vlastní API, ne ze
statického HTML), takže tenhle zdroj zatím nebyl živě otestovaný na zařízení - dej vědět, jak
funguje.

## v1.2.48

### Oprava: procenta u ComicK titulů počítala jen ze zlomku kapitol
Seznam kapitol pro ComicK tituly se filtroval podle jazyka nastaveného pro PŘEKLAD (výchozí
angličtina) - u titulu, jehož anglický překlad zaostává za originálem, appka viděla jen malou
část skutečných kapitol, takže "% přečteno" v knihovně vycházelo směšně vysoko (např. 100 % u
kapitoly 1 z titulu, který má ve skutečnosti 150+ kapitol). Appka teď stahuje čísla kapitol
napříč všemi jazyky, takže "celkem kapitol" odpovídá skutečnosti.

## v1.2.47

### Oprava: kapitola se občas nenačetla a zůstala trvale na "načítání"
Zdroje chráněné Cloudflare umí appku bez jakékoli chybové hlášky nechat viset i přes minutu -
appka to opakuje (retry) na chybu, ale to opakování zahrnuje i celé (drahé) řešení Cloudflare
výzvy znovu od začátku, takže se to může sečíst. Načítání stránek kapitoly má teď strop 45 s -
po jeho vypršení appka rovnou ukáže "Kapitolu se nepodařilo načíst" (jde použít nové tlačítko
pro restart kapitoly z v1.2.45) místo věčného čekání.

## v1.2.46

### Nové: Nekonečné čtení (webtoon)
V Nastavení → Čtečka přibyl přepínač "Nekonečné čtení" - ve svislém (webtoon) čtecím režimu
appka místo přepnutí na další kapitolu po dočtení potichu na pozadí stáhne a plynule přilepí
její stránky pod tu aktuální, takže scroll pokračuje dál bez přerušení. Na hranici mezi
kapitolami se objeví tenká karta s tlačítky Předchozí/Další pro ruční přeskočení.

## v1.2.45

### Nové: tlačítko pro restart kapitoly
V horní liště čtečky (mezi názvem a seznamem kapitol) přibylo tlačítko pro znovunačtení
aktuální kapitoly od začátku - pro případ, že se nenačte správně.

## v1.2.44

### Oprava: nová kapitola ve webtoon režimu se neotevřela nahoře
Při přechodu na další/předchozí kapitolu ve svislém (webtoon) čtecím režimu appka nechala
scroll pozici z PŘEDCHOZÍ kapitoly - nová kapitola se tak neotevřela nahoře, ale někde
uprostřed nebo i na konci, podle toho, kam náhodou stará pozice v novém (jinak dlouhém)
seznamu stránek padla. Obnovení pozice se spouštělo jen jednou při prvním otevření čtečky,
ne při každé změně kapitoly.

## v1.2.43

### Oprava GL srolování: efekt byl schovaný za stránkou (skutečná příčina)
Oprava v1.2.42 (hardwarová bitmapa) problém nevyřešila - skutečná příčina byla jinde.
`GLSurfaceView`, ve kterém OpenGL efekt běží, se bez výslovného nastavení vykresluje na
samostatné vrstvě ZA oknem aplikace - takže ho statická stránka nahoře úplně zakrývala. Tažení
tak nemělo žádnou viditelnou animaci a po puštění prstu appka jen rovnou přepnula na další
stránku, jako by šlo o obyčejné ťuknutí. Teď se vrstva s GL efektem výslovně staví nad
zbytek obrazovky.

## v1.2.42

### Oprava GL srolování: efekt se vůbec nezakřivoval (žádná animace)
Textura pro OpenGL srolovací efekt (v1.2.40/v1.2.41) se natahovala přímo z bitmapy, kterou
appka renderuje z Compose vrstvy - ta je na tomhle typu zařízení hardwarová (`Bitmap.Config.
HARDWARE`), což OpenGL nahrání textury tiše přeskočí/nezvládne. Stránka tak při tažení
zůstávala úplně plochá, jako by žádná animace neběžela (stejný druh chyby, jaký dřív potkal
starší 2D ohyb - viz v1.2.34 oprava). Bitmapa se teď před nahráním do GL vždy převede na
běžný formát.

## v1.2.41

### Oprava GL srolování: vypadalo jako spirála/trubička, ne hladký ohyb
Ohýbaný OpenGL port (v1.2.40) při podržení prstu v dotažené pozici vytvářel spirálovitý průřez
(vypadalo to jako svinutá trubička) - originální knihovna počítala jen s rychlou 300ms animací,
kde to oko nepostřehne, naše čtečka ale nechává tažení podržet v libovolné pozici. Zpomalena
vlnová délka ohybu, ať zůstane jeden hladký oblouk i při podržení.

### Vylepšení GL srolování: kónický ohyb (síla podle výšky)
Ohyb byl stejný na každém řádku (rovnoměrný "válec" po celé výšce stránky) - teď sílí směrem k
dolnímu rohu a slábne k hornímu, jako u fyzického uchopení stránky za roh.

## v1.2.40

### Srolování stránky teď jede přes skutečný OpenGL port (karacken.curl)
Styl "Srolování stránky" byl doteď vlastní 2D aproximace (Canvas mesh). Nahrazeno přímým portem
open-source knihovny karacken.curl (denis554/PlayLikeCurl) do Kotlinu - stejná matematika ohybu
(vlnová plocha ve 3D, ne 2D komprese), stejné vykreslování přes OpenGL ES, jen napojené na naše
vlastní tažení prstem/stav místo vlastního dotykového ovládání knihovny. "Klasické otáčení"
zůstává beze změny (2D Canvas efekt, funguje dobře).

## v1.2.39

### Oprava: odstraněn rušivý zvýrazňovací proužek na ose ohybu
Ohyb stránky měl podél celé osy tenký světlý proužek (odlesk simulující ohnutý papír), který
na některých stránkách vypadal jako rušivá čára navíc, ne jako přirozený stín. Odstraněno.

## v1.2.38

### Vylepšení: skutečná 3D perspektiva místo ploché sinusové aproximace
Ohyb stránky dřív počítal jen vodorovnou pozici bodu na pomyslném válci (ortografická
projekce - jako by se divák díval z nekonečné vzdálenosti). Teď se navíc počítá i hloubka bodu
(jak moc se při otáčení vzdaluje od diváka) a aplikuje se skutečné perspektivní dělení - body
dál v ohybu se komprimují k ose o něco víc, takže to vypadá jako fyzická 3D perspektiva
otáčející se stránky, ne mechanicky protažená křivka. Blíž stylu Google Knih.

## v1.2.37

### Oprava: efekt otáčení stránek se vůbec nezakřivoval
Ohyb stránky (klasický i srolování) se v poslední verzi vykresloval jako plochý řez bez
jakéhokoli zakřivení nebo stínování - vypadalo to jako obyčejné přeskočení na další stránku
místo ohýbání papíru. Příčina: stránka se interně rasterizuje do bitmapy, která na tomhle
zařízení skončí v paměti dostupné jen grafické kartě (ne procesoru) - vykreslovací funkce pro
zakřivení potřebuje na pixely sáhnout přímo, takže tiše nenakreslila nic. Teď se bitmapa nejdřív
zkopíruje do procesorem čitelné podoby.

### Nový styl otáčení: druhý přidán vedle Klasického
Přibyl druhý volitelný vzhled otáčení stránky - "Srolování stránky" (stránka se svine do úzké
trubičky, která putuje napříč obrazovkou podle tažení, včetně tmavší "rubové" strany svitku).
Přepínáš v Nastavení čtečky, hned pod zapnutím 3D efektu.

### Vylepšení: kónický (ne válcový) ohyb + měkčí stín
Ohyb stránky teď silí směrem k dolnímu rohu (odkud se stránka typicky "drží" při otáčení) a
slábne směrem k hornímu okraji, místo stejně silného ohybu po celé výšce - blíž tomu, jak se
otáčí stránka v Google Knihách. Zároveň měkčí, plynulejší vržený stín a odlesk na ose ohybu
místo tvrdých hran.

## v1.2.36

### Oprava opakovaného pádu appky pár minut po startu
Appka občas spadla asi minutu po otevření a vyhodila tě z kapitoly - opakovalo se to
donekonečna. Příčina: synchronizace na pozadí (SyncWorker) jako první v appce sahala na
přihlašovací modul (Supabase Auth), který si vyžaduje registraci na hlavním vlákně appky -
na vlákně pro práci na pozadí to appce spolehlivě spadlo. Teď se tenhle modul připraví hned
při startu appky na hlavním vlákně, dřív než se k němu synchronizace na pozadí vůbec dostane.

### Oprava otáčení stránek (page-curl) u manga/manhwa
Tažení prstem u manga/manhwa čtečky efekt otáčení vůbec nespouštělo (fungovalo jen ťuknutí
na okraj) - gesto pro přiblížení prsty (pinch-zoom) omylem "sežralo" i obyčejné jednoprstové
tažení dřív, než se dostalo k otáčení stránky. U novel čtečky, která přiblížení nemá, to
fungovalo správně už předtím.

Zároveň přepracovaný samotný vzhled ohybu (u manga i novel) - hladší zaoblení a stínování
blíž stylu Google Play Books.

## v1.2.35

### Oprava vzhledu page-curl efektu
Efekt otáčení stránek (v1.2.34) měl špatně vykreslený samotný ohyb - místo skutečného
zakřivení stránky se obsah kolem osy ohybu zrcadlil sám na sebe (viditelný "duch"/duplikát
textu). Přepsáno na válcový ohyb ve stylu Google Play Books/iBooks - obsah se u osy ohybu
komprimuje a zaobluje podle úhlu na pomyslném válci, s jemným stínováním. Žádné zrcadlení,
žádný duplikát textu.

## v1.2.34

### Nový volitelný efekt otáčení stránek (page-curl)
Appka umí nově simulovat otáčení stránky jako u knihy - ohybová animace místo
plynulého scrollu nebo obyčejného swipu. Zapíná se v Nastavení čtečky
("Otáčení stránek" -> "Použití 3D efektu při otáčení stránek") a funguje
jak v čtečce light novelů, tak v manga/manhwa čtečce (pro režim čtení
"po stránkách", ne pro webtoon plynulý scroll) - včetně zoomu, dvoustránkového
zobrazení, zón pro tapnutí, hlasitostních tlačítek, sdílení stránky a
automatického přechodu na další kapitolu. Výchozí chování se nemění - efekt
je vypnutý, dokud si ho v nastavení nezapneš.

## v1.2.32

### ComicK: dolaďování automatického výběru zdroje z v1.2.31
Pět navazujících oprav poté, co se automatický výběr zdroje (v1.2.31) dostal do každodenního
používání:

- **"Pokračovat ve čtení" a procenta u ComicK titulů se nikdy nehýbaly** - appka po vyřešení
  kapitoly na skutečný zdroj otevírala kapitolu "na pozadí" (bez přidání do knihovny), ale
  "přečteno" se nikdy nepropsalo zpátky na samotný ComicK titul, co uživatel skutečně má
  v knihovně. Teď se propisuje při každém vyřešení.
- **Appka čekala na prohledání úplně všech zdrojů**, i když nejlepší kandidát (oblíbený nebo
  stejná překladatelská skupina, jakou měla otevíraná kapitola) dorazil hned na začátku. Teď
  jakmile takový kandidát s hledanou kapitolou dorazí, appka rovnou otevře a zbytek hledání
  zruší.
- **Rozšířena detekce "preferované" překladatelské skupiny** - appka teď bere v potaz nejen
  skupinu, co přeložila právě otevíranou kapitolu, ale i skupiny od 1., poslední a předposlední
  kapitoly titulu - spolehlivější signál "hlavního" překladatele.
- **Hlavička (hledání, Populární/Nejnovější) na obrazovce jednotlivého zdroje** (MangaDex,
  MANGA Plus a další) vypadala zmenšená/posunutá doprostřed oproti stavu při načítání -
  dvojité odsazení od kraje. Opraveno.
- **Vyskakující Cloudflare ověření přerušovaly čtení/hledání** - hromadné prohledávání desítek
  zdrojů najednou umělo narazit na víc webů vyžadujících interaktivní lidské ověření a appka
  je ukazovala jedno po druhém. Appka teď během hromadného hledání interaktivní výzvu
  přeskočí (zdroj se bere jako dočasně nedostupný, hledání pokračuje jinde) - přímé procházení
  jednoho vybraného zdroje se nemění. Zároveň doladěn tichý (bezinterakční) způsob řešení -
  spolehlivější a rychlejší reakce - a vyřešení se teď pamatuje 2 hodiny místo 25 minut.

## v1.2.31

### ComicK: automatický výběr zdroje a oprava počtu kapitol
Appka dřív po otevření kapitoly v agregovaném ComicK režimu vždycky ukázala seznam nalezených
zdrojů k ručnímu výběru - i když jeden z nich byl jasně nejlepší (přesně ta překladatelská
skupina, co kapitolu přeložila, a s kompletním pokrytím). Appka teď vždy sama vybere a rovnou
otevře nejvhodnější zdroj podle priority: oblíbený zdroj → stejná překladatelská skupina jako
u otevírané kapitoly (Asura, Thunderscans, ...) → zdroj, co danou kapitolu má → nejúplnější
pokrytí. Ruční seznam se ukáže jen když se nenajde žádný zdroj.

Opraven i špatně zobrazovaný počet kapitol u ComicK titulů - ComicK eviduje jednu kapitolu
vícekrát, jednou za každou skupinu, co ji přeložila, takže appka dřív sčítala všechny tyhle
duplicity dohromady (titul se 156 kapitolami tak ukazoval "434 kapitol"). Teď se počítají
unikátní čísla kapitol.

### Nový zdroj: Thunderscans
Hlavní doména thunderscans.com je bohužel hijacknutá - místo obsahu teď servíruje
fingerprinting redirect na cizí doménu. Appka proto používá funkční anglický mirror
(en-thunderscans.com).

### Detekce tvaru bublin: doladění a záložní algoritmus
Vylepšení heuristiky, která odhaduje přesný tvar bubliny z OCR textu - tolerantnější poměr
tvaru k ploše textu, nová záložní metoda (paprskové skenování hranic) pro případy, kdy
základní flood-fill selže, a strop na to, jak moc se odhadovaný obdélník bubliny může
roztáhnout nad samotný OCR text.

## v1.2.30

### Nová ikona appky
Nahrazuje původní fialové dveře za nový design - otevřenou knihu, jejíž stránky se rozpadají
do zářících fragmentů. Doladěná i barva pozadí adaptive icon, aby seděla s novým logem na
všech tvarech masky (kruh, čtverec se zaoblenými rohy).

## v1.2.29

### Bezpečnost: vlastní podpisový klíč a šifrovaná session účtu
Dva nálezy z bezpečnostního code review (kontrola před tím, než se na projekt podívá senior
dev):

- **Release APK se dosud podepisoval defaultním DEBUG klíčem** - stejným na každé instalaci
  Android Studia na světě, takže kontrola podpisu při aktualizaci nic reálně nechránila.
  Appka teď má vlastní privátní release keystore. Cena za přechod: **tuhle aktualizaci je
  nutné nainstalovat ručně přes odinstalaci a novou instalaci** (Android odmítne update
  podepsaný jiným klíčem, než má nainstalovaná appka) - zálohuj si knihovnu přes Nastavení →
  Zálohovat a obnovit před odinstalací. Od téhle verze dál už každý další update půjde
  normálně přes appku samotnou.
- **Přihlašovací session k účtu Jiyu (Google/e-mail) se ukládala nešifrovaně** - appka má pro
  tracker tokeny (MAL/Kitsu/MangaUpdates) vlastní AES-256 šifrované úložiště přes Android
  Keystore, ale Supabase Auth session tuhle cestu nepoužívala a spadala na výchozí
  nešifrované úložiště knihovny. Opraveno - session teď jde přes stejné šifrované úložiště.
  Důsledek: po aktualizaci se account odhlásí, je potřeba se znovu přihlásit (knihovna v Room
  databázi tím není nijak dotčená).

## v1.2.28

### Onboarding: nový krok pro výběr stylu procházení
Appka měla dva prohlížecí styly (agregovaný ComicK katalog vs. ruční výběr
z jednotlivých zdrojů) schované jen v Nastavení, bez vysvětlení rozdílu.
Onboarding teď přidává vlastní krok, který oba styly popíše (včetně toho,
že agregovaný ComicK režim neobsahuje novely ani americké komiksy) a
připomene, že volba jde kdykoli později změnit v Nastavení → Zdroje mang.
Zároveň odstraněna prázdná úvodní věta na kroku Věk a soukromí.

### README
Aktualizace na skutečný stav appky - 110+ zdrojů místo 60+, popis obou
prohlížecích stylů, účtu a cloud syncu, komunitních funkcí na ComicK a
rozšířeného nastavení čtečky.

## v1.2.27

### Překlad: pět oprav vykreslování a kvality textu v bublinách
Živé procházení celé přeložené kapitoly odhalilo několik konkrétních chyb,
každá opravená s testem, který přesně reprodukuje nahlášený případ:

- **Anglický "překlad" doslovně zkopírovaný z originálu** se dřív vykreslil,
  jako by šlo o hotový český text ("HOW WE MAKE A LIVING." zůstalo anglicky).
  Appka teď takové odpovědi pozná a bublinu nechá radši nepřeloženou (čitelný
  originál) než falešný "překlad".
- **Osamocená tečka/čárka na vlastním řádku** ("ODLÉTÁME" + tečka pod tím) -
  příčinou byla mezera před koncovou interpunkcí, kterou model občas napíše
  jiným unicode znakem (širší mezera, neviditelný znak nulové šířky), který
  appka dřív neuměla rozpoznat.
- **Mezera před otazníkem/vykřičníkem** ("CO DĚLÁŠ ?") - čeština na rozdíl
  od francouzštiny mezeru před `?!:;` nikdy nemá; appka ji teď sama odstraní,
  i když ji model omylem nechá.
- **Bublina napůl anglicky, napůl česky** - komiksový lettering občas sází
  první slovo repliky větším/tučným písmem kvůli důrazu, což appka dřív
  vyhodnotila jako dvě různé bubliny a přeložila jen tu druhou půlku věty.
- **Slovo rozlomené uprostřed bez pomlčky** ("PANTEŘÍ" vykreslené jako
  "PANTER"/"Í") - fitter velikosti písma měřil dělitelná slova jako jeden
  nedělitelný kus, takže se zbytečně vzdával a spadl na Compose vlastní
  nekontrolované zalomení.

## v1.2.26

### ComicK: opravy a grafika na míru zbytku appky
Stejná úprava jako u hlavičky na Domů z minulé verze teď platí i pro
obrazovku jednotlivých zdrojů (MangaDex, MANGA Plus, Hitomi.La atd.) -
záhlaví se zpět tlačítkem/hledáním/filtrem/přepínačem Populární-Nejnovější
odjíždí pryč se scrollem místo aby zabíralo místo napořád. Vyhledávání na
ComicK Domů teď navíc zobrazuje výsledky jako kompaktní řádky (náhled +
název + počet kapitol) místo mřížky velkých karet - stejně jako to má
ComicK vlastní vyhledávací nápověda.

### Komentáře: odsazení odpovědí přestalo utíkat mimo obrazovku
U delších vláken se odsazení každé další úrovně odpovědi sčítalo, takže
text nakonec vytlačilo skoro celý mimo obrazovku. Všechny odpovědi, i
vícekrát vnořené, se teď zobrazují na jedné pevné odsazené úrovni pod
rodičovským komentářem - a protože se tím ztrácelo, komu kdo přesně
odpovídá, přibyl u vnořených odpovědí štítek "↳ Odpověď uživateli X".

### Předvolby aktualizací a doporučené tituly
Sekce Typ/Demografie/Obsah pro dospělé v Předvolbách aktualizací teď
používají checkboxy s popiskem (stejně jako ComicK vlastní stránka
Preferences) místo pilulkových chipů bez vysvětlení a rozbitého
zmáčklého checkboxu. Sheet "Doporučené tituly" se navíc přizpůsobuje
skutečnému počtu doporučení místo aby se vždy natáhl na 85 % obrazovky
s prázdnou plochou pod jednou dvěma kartami.

### Filtry na Procházet - přehlednější a bez nechtěného zavírání
Mezi sekcemi filtrů (Řazení/Typ/Demografie/Stav/Žánry/Tagy...) přibyla
oddělovací čára a výraznější nadpisy i chipy, takže dlouhá zeď pilulek
u Žánrů/Tagů je čitelnější. Opravený i bug, kdy rychlý fling nahoru hned
po dojetí na konec seznamu zavřel celý sheet.

## v1.2.25

### Hlavička na ComicK Domů už nezůstává přilepená nahoře
Nadpis "ComicK", lupa a tlačítka Domů/Procházet byly napevno ukotvené
nahoře obrazovky a při scrollování zůstávaly na místě. Teď jsou součástí
obsahu a odjedou pryč spolu se sekcemi - stejně tak v režimu vyhledávání
i při načítání/chybě.

## v1.2.24

### Kapacita: dva noví free-tier provideři (Cerebras, Mistral)
Fallback řetězec pro AI překlad měl jen tři nezávislé služby (Gemini/Groq/
OpenRouter) - když všechny vyčerpaly denní kvótu, appka hlásila "denní
limit vyčerpán". Přibyly dva další, oba bez placení a bez karty:
Cerebras (stejný model jako Groq, ale ~5x větší denní rozpočet) a
Mistral (nezávislá evropská alternativa se silnou podporou češtiny).

### Quota-aware ProviderHealth
Appka dřív po odmítnutí providera čekala napevno 15 minut, protože
nepoznala limit "na minutu" od vyčerpané denní kvóty. Teď čte skutečné
navržené čekání přímo z odpovědi providera (Retry-After hlavička u
Groq/OpenRouter/Cerebras/Mistral, tělo odpovědi u Gemini) a použije ho
přesně - žádné zbytečné čekání ani zbytečné předčasné zkoušení
vyčerpaného providera.

### Žánrové tón-pravidlo a kontext pro novely
Překlad teď zohledňuje demografické cílení díla (shónen/šódžo/seinen/
džosei), pokud ho appka zná ze žánrových štítků. Novely navíc konečně
dostávají stejný kontext o díle (název/typ/žánry) jako manga - dřív
model při překladu novely nevěděl ani název díla.

## v1.2.23

### Oprava: bílý pruh přes sousední bublinu a kresbu
Bublina bez detekovaného tvaru (heuristický box) nevěděla o sousední bublině
s detekovaným tvarem a mohla svůj box roztáhnout až 3x vlastní šířku přímo
skrz ni a do kresby za ní - nahlášeno na natěsno namačkaném trsu bublin
("C'MON!" vedle "LET'S LEARN TOGETHER, IORI."). Heuristika teď bere tvarové
bubliny jako pevné překážky, které nikdy nepřejede.

### Příprava na budoucí doladění: dvě další observability logy
- `BubbleSkip`/`TinyBubbleBox` - u nahlášeného případu, kdy bublina ("YAH!")
  v natěsno namačkaném trsu úplně zmizela (ani originál, ani překlad).
- `DroppedSentence` - u nahlášeného případu, kdy "spojená" (dvouhrbá)
  bublina se dvěma větami skončila v překladu jen s jednou.
Ani jeden log nic nemění na chování appky, jen sbírá data pro budoucí
opravu na jistotu, ne na dohad.

## v1.2.22

### Spolehlivost překladu novel
`translateNovelChapter` (překlad light novel) neměl žádný záložní provider -
při vyčerpané Groq kvótě rovnou spadl celý překlad kapitoly. Teď má stejný
fallback na OpenRouter, jaký už dřív fungoval u překladu manga bublin.

### Urgentní oprava: Groq vyřazuje model, co appka používala
Groq k 16. 8. 2026 vyřazuje `llama-3.3-70b-versatile`. Po živém ověření, že
`openai/gpt-oss-120b` je jediná zbylá vhodná produkční volba, appka na něj
přešla - nasazeno přímo na server (translate-proxy).

### Příprava na budoucí doladění překladového pipeline (bez změny chování)
Přibylo 5 nových logů (BubbleShapeRatio, NativeFontCap, ShapeCoverage,
VerbatimCopy, OcrConfidence z ML Kitu) pro sbírání reálných dat k prahům,
co byly dřív jen odhadnuté - nic se jimi teď v appce nemění, jen se
připravuje podklad pro budoucí přesnější doladění detekce bublin a kvality
překladu.

### Pro vývojáře
Instrumentovaná sada regresních testů (reprodukce nahlášených chyb
překladu) se teď spouští automaticky v CI při každém push/PR, ne jen ručně.

## v1.2.21

### Oprava: stahování aktualizace občas odmítalo začít
Cílový soubor stahování měl vždy stejné jméno - Android DownloadManager
odmítne stahovat, pokud tam z předchozího pokusu (i úspěšného, co appka po
instalaci nikdy neuklidila) už soubor leží. Uživatel viděl jen "nepovedlo
se" bez důvodu. Starý soubor se teď smaže před každým novým pokusem; u
nedostatku místa na zařízení appka navíc řekne konkrétně, co je špatně.

## v1.2.20

### ComicK agregátor: hledání zdrojů se plní průběžně, ne až na konci
Když appka hledá skutečný zdroj pro ComicK titul (Vyber zdroj), dřív čekala,
až projde úplně všechny zdroje, než cokoliv ukázala. Teď přidá každý
nalezený zdroj hned, jak ho dohledá - ostatní hledá dál na pozadí ("Hledám
další zdroje…" pod už nalezenými kartami). Pořadí (oblíbené první, pak podle
počtu kapitol) se přeskládá až jednou na konci hledání, ne po každém
výsledku - už se nemůže stát, že seznam přehodí pořadí zrovna pod prstem.

### ComicK Domů: dlouhý stisk na název otevře Předvolby, lupa hledá na místě
Dlouhý stisk na "ComicK" v hlavičce otevře stejné Předvolby, co dřív šly jen
přes ozubené kolo u Aktualizací. Lupa už neotevírá celou obrazovku Procházet
- zůstane na Domů a jen přehodí hlavičku na vyhledávací pole, výsledky se
ukážou hned pod ní.

### Procházet: seznam místo mřížky, jako ComicK Search
Mřížka obálek nahrazena řádky - foto, název, počet kapitol, bez pořadového
čísla a bez úryvku popisu.

### Detail titulu: info sloupec jako ComicK, komentáře, doporučené tituly
- Sloupec Typ/Demografie/Vydáno/Status/... měl kvůli emoji nerovnoměrné
  mezery mezi řádky - teď je bez nich a vypadá jako u ComicK. Obálka o
  trochu větší (150dp), info sloupec se posunul doprava.
- Nová sekce Komentáře pod seznamem kapitol (jen ComicK) - jen ke čtení,
  appka nemá napojený ComicK účet na psaní. Vedle nadpisu je tlačítko
  "Otevřít na ComicK" pro ty, co chtějí komentovat.
- Nové tlačítko "Doporučené" pod obálkou (jen ComicK) - otevře mřížku
  podobných titulů podle ComicK doporučení, klepnutí otevře jejich detail.
- Řádek kapitoly ukazoval jen prvního překladatele - teď všechny.

### Knihovna
- Číslo kapitoly na kartičkách "Pokračovat ve čtení" mělo jen měkký stín,
  na světlých obálkách bylo špatně čitelné - teď má skutečný černý obrys
  (funguje na jakémkoli pozadí).
- Filtr typu obsahu (ikonka se třemi čárkami vedle vyhledávání) na
  domovské Knihovně byl zbytečný - pryč. Filtr v Seznamu zůstal.

### Nový widget s obálkou titulu
Vedle stávajícího widgetu (textový seznam naposledy čtených) přibyl nový,
co ukazuje obálku + název + poslední kapitolu JEDNOHO vybraného titulu. Při
přidání na plochu appka nejdřív nabídne výběr z knihovny - klepnutím na
widget se otevře rovnou detail toho titulu.

## v1.2.19

### Pojistka proti omylem hromadně přečtenému titulu
Kapitoly umí hromadně "Označit vše starší jako přečtené", "Označit toto i vše
starší přečtené" a "Označit vše přečtené" (celý titul). Doteď se to spustilo
hned po klepnutí na položku v menu - stačilo nechtěně trefit prstem špatnou
volbu (typicky při dlouhém podržení kapitoly ve scrollujícím seznamu) a celý
titul se najednou tvářil jako 100% přečtený, i když jste přečetli jen jednu
kapitolu. Teď se před provedením vždy zeptá na potvrzení.

### Odkud titul je - nový řádek "Zdroj" na detailu
Na detailu titulu (ve sloupci s Typ/Demografie/Žánry) přibyl řádek "Zdroj" s
názvem zdroje, ze kterého je titul stažený. Usnadní to dohledat, kde přesně
vzniká problém, když se u konkrétního titulu objeví divná data (např. rozbité
číslo kapitoly).

## v1.2.18

### Úklid Knihovny: pryč s X, počty a fialovými boxy
- "Zobrazit vše" u Pokračovat ve čtení / Nedávno přidané: odstraněno tlačítko
  X na smazání u každého řádku (bylo zbytečné, mazání jde jinudy).
- Nadpisy sekcí na Knihovně (POKRAČOVAT VE ČTENÍ, NEDÁVNO PŘIDANÉ, DOKONČENÉ):
  odstraněn fialový kolečkový odznak s počtem titulů vedle nadpisu.
- Číslo kapitoly na kartičkách "Pokračovat ve čtení": pryč fialový box na
  pozadí, teď je to jen fialové číslo s černým stínem/podtónem - čitelné
  na jakékoli obálce, i světlé.

### Seznam kapitol na detailu titulu: tenčí řádky + stránkování jako ComicK
Místo jednoho nekonečně dlouhého scrollu (a tlustých karet s kapitolou,
skupinou a datem na 3 řádcích) teď kapitoly vypadají jako ComicK tabulka -
tenký jednořádkový záznam (kapitola / datum / skupina) a dole stránkování
(šipky + čísla stránek s "…", 30 kapitol na stránku). Bez vlaječky a bez
počtu hlasů se šipkou nahoru - to appka nemá jak spočítat.

### Krátká lhůta při omylem přehozené záložce dole
Když jste rozkliknutí (např. na detailu titulu otevřeném z Procházet) a
omylem klepnete na jinou záložku dole (např. Nastavení), appka už rozkliknutý
stav hned nezahazuje - 4 sekundy po odchodu ze záložky ho ještě drží, takže
návrat zpátky vás vrátí přesně tam, kde jste byli. Po uplynutí 4 sekund se
záložka chová jako dřív a resetuje se na svůj kořen.

### Hlavička detailu titulu: tlačítko na zkopírování názvu, těsnější mezery
Název a alternativní názvy pod ním byly odsazené jako dva samostatné bloky
s velkou mezerou mezi nimi. Teď jsou k sobě blíž jako na ComicKu. Vedle
názvu navíc přibylo malé tlačítko s ikonou kopírování - klepnutím se název
titulu zkopíruje do schránky (potvrzeno hláškou dole na obrazovce).

## v1.2.17

### Ošklivá "ComicK API chyba 404" hláška při otevření titulu
`getChapterList` (volá se při otevření titulu z Procházet/Aktualizací) volal
stejný `/comic/{slug}` endpoint jako `getMangaDetails`, ale bez už dřív
napsané přátelské hlášky pro 404 - ukazovala se tak syrová technická chyba
místo vysvětlení.

Zjištěno živě: jde konkrétně o `content_rating: "pornographic"` tituly (ne
o "suggestive"/"erotica", ty appka otevře normálně) - ComicK sám tenhle
nejexplicitnější stupeň přes veřejné API neposkytuje vůbec (ani přes
web bez přihlášení), appka to nemá jak obejít.

## v1.2.16

### Nové: Předvolby (Preferences) pro Aktualizace na ComicK
Ověřeno živě proti ComicK API: parametry Type/Demographic appka na feedu
kapitol filtruje sama po straně appky (ComicK je v query parametrech na
`/chapter` tiše ignoruje - funguje tam jen `content_rating`). Nové ozubené
kolo vedle Hot/New na Aktualizacích otevře stejné 3 sekce, které má ComicK
vlastní Preferences stránka:
- **Typ** (Manga/Manhwa/Manhua/Ostatní)
- **Demografie** (Shounen/Josei/Seinen/Shoujo/Bez demografie)
- **Obsah pro dospělé** (jen s zapnutými 18+ zdroji) - sugestivní obsah,
  násilí/krvavé scény, obsah pro dospělé - to jsou opt-in přepínače
  navíc k běžnému obsahu, ne filtr co ho skrývá

"Display comics in my list" a "countdown timers" appka nemá - vázané na
ComicK účet/premium funkce, které appka nezná.

### Odstraněny nepoužívané sekce Nastavení
"Cíle čtení & Série" a "Community manga listy" - appka je nikde jinde
nepoužívala.

## v1.2.15

### Sloupec s informacemi na detailu titulu sjednocen podle ComicK
Status (Vychází/Dokončeno/...) byl dřív samostatná barevná pilulka nad
seznamem info řádků - jediný prvek se stylem odlišným od zbytku, což
působilo rozházeně. Teď je Status obyčejný řádek uvnitř stejného seznamu
jako Typ/Demografie/Vydáno/Překlad, přesně jako na ComicK, včetně stejných
emoji (📗 Dokončeno, 📖 Vychází/Probíhá, 🚫 Zrušeno, ⏸ Přerušeno).

### "Zobrazit vše" na Knihovně (Pokračovat ve čtení, Nedávno přidané) jako seznam
Tahle obrazovka byla mřížka po třech obálkách bez dalších informací - teď
je to seznam řádků stylem ComicK Read History: malá obálka vlevo, název,
kapitola (u Pokračovat ve čtení), relativní čas a tlačítko X na odebrání
z knihovny vpravo (místo dřívějšího dlouhého podržení).

## v1.2.14

### Aktualizace na Domů se teď načítají celé, ne jen náhled
Sekce Aktualizace dole na Domů se dřív ořízla na pár položek s odkazem
"Zobrazit vše" - teď se načítá úplně celá (nekonečné scrollování), stejně
jako to má ComicK.

### Záložka "Aktualizace" nahoře nahrazena "Procházet"
Protože je teď celý feed Aktualizací rovnou na Domů, samostatná záložka
"Aktualizace" nahoře (vedle "Domů") byla zbytečná - nahrazena tlačítkem
"Procházet", které otevře obrazovku hledání a filtrů (stejná, kterou appka
otevírala z lupy nahoře).

## v1.2.13

### Aktualizace (Hot/New) jako mřížka obálek, stylem ComicK
Feed kapitol (na Domů i na vlastní záložce Aktualizace) dřív vypadal jako
úzké textové řádky - teď je to mřížka o dvou sloupcích s velkými obálkami,
číslem kapitoly, relativním časem ("před 2 h"), skupinou a lajky/komentáři,
podobně jako na ComicK webu/appce. Náhled na Domů zůstává (rozšířen na 8
položek), plná záložka Aktualizace má pořád nekonečné scrollování.

### Filtry na Procházet (ComicK): přidané "Průměrné hodnocení" + vysvětlivky u řazení
Vedle "Hodnocení" (bayesovské - víc hlasů, víc důvěryhodnosti) teď appka
nabízí i "Průměrné hodnocení" (prostý průměr) jako samostatnou možnost
řazení, stejně jako na ComicK webu. Pod řazením se navíc zobrazí krátké
vysvětlení aktuálně vybrané možnosti.

## v1.2.12

### Nová ikona appky
Fialové dveře na schodech (nahradila předchozí ikonu).

### "Další možnosti" ve čtečce (překlad atd.) se zavíralo samo pod rukama
Auto-schovávání ovládacích prvků běželo dál i s otevřeným sheetem
(jazyky/překlad/orientace) - zmizelo po 3s bez ohledu na to, že v něm
uživatel aktivně pracoval. Teď se sheet zavře jen explicitním tapnutím
mimo. Základní zpoždění schovávání lišt taky prodlouženo (3s → 5s).

## v1.2.11

### Tlačítko Pokračovat na detailu titulu - úprava podle ComicK
- Nižší, jeden řádek textu místo dvou (mizelo tam "Pokračovat" rozdělené
  na "Pokračova" + "t" na vlastním řádku - text se do dvouřádkového
  layoutu nevešel)
- Míň tučné písmo, číslo kapitoly rovnou v textu ("Pokračovat 318")
- Šipka dolů na tlačítku pryč - **podržení tlačítka** teď otevře nabídku
  číst normálně/anonymně (dřív k tomu byla ta šipka)
- Status vedle (Sleduji/Dokončeno/...) má teď stejnou výšku jako Pokračovat
- Odznak stavu (Vychází/Dokončeno/...) sjednocen na stejné zaoblení rohů
  jako tlačítka pod ním (dřív byl jako plná pilulka, tlačítka míň kulatá)

### Emoji u polí na detailu titulu, jako má ComicK
- Typ: vlaječka podle původu (🇯🇵 Manga, 🇰🇷 Manhwa, 🇨🇳 Manhua)
- Status a Překlad: 📖 vychází/probíhá, ✅ dokončeno, 🚫 zrušeno, ⏸ přerušeno

## v1.2.10

### Aktualizace (Hot/New) teď rovnou na Domů, ne jen na vlastní záložce
Přidána nová sekce dole na ComicK Domů, hned pod "Nedávné recenze" - krátký
náhled posledních aktualizací kapitol s přepínačem Hot (🔥)/New (☀️), stylem
podobně jako na ComicK webu. "Zobrazit vše" přepne na plnou záložku
Aktualizace se stejným řazením. Data se teď načtou hned při otevření Domů,
ne až při prvním přepnutí na Aktualizace.

## v1.2.9

### Nová obrazovka: Procházet ComicK se všemi filtry, co má web
Klepnutí na lupu na ComicK domovské obrazovce teď otevře novou obrazovku
s vyhledáváním a tlačítkem "Filtry" - ověřeno živě proti ComicK API, jde
filtrovat/kombinovat:

- Žánry a tagy (víc najednou)
- Typ/původ (Manga/Manhwa/Manhua/Ostatní)
- Demografie (Shounen/Josei/Seinen/Shoujo)
- Status (Vychází/Dokončeno/Zrušeno/Přerušeno)
- Content rating (jen když jsou zapnuté 18+ zdroje v nastavení)
- Minimální počet kapitol
- Rok vydání od-do
- Řazení (Populární/Nejnovější/Hodnocení/Název)

Hot/New přepínač v Aktualizacích appka už měla dřív - jen o pár řádků níž,
po přepnutí ze záložky Domů na Aktualizace (aktuálně pojmenované Populární/
Nejnovější).

## v1.2.8

### Seznam kapitol ve čtečce se otevíral přes celou obrazovku a vždy od nejnovější kapitoly
Klepnutí na ikonu se třemi čárkami v horní liště čtečky otevřelo seznam všech
kapitol na celou výšku obrazovky a vždy shora (nejnovější kapitola) - u
dlouhých sérií (300+ kapitol) se tak muselo ručně scrollovat k té, na které
člověk zrovna je. Teď:

- seznam zabírá max. 70 % výšky obrazovky, ne celou
- otevře se rovnou u aktuálně čtené kapitoly (pár řádků nad ní)
- aktuální kapitola je v seznamu zvýrazněná fialovým podkladem

### Appka teď používá font Inter
Celá appka běžela na výchozím systémovém fontu (Roboto). Přepnuto na Inter
(zdarma, SIL OFL licence, Google Fonts) - jeden variabilní soubor pokrývá
všechny váhy písma napříč appkou.

### Vlaječky a globus pryč z horního jazykového filtru na Procházet
Filtr "Vše/EN/FR/ES/PT/RAW" nahoře na obrazovce Procházet měl u každé
položky vlaječku (a "Vše" glóbus) - odstraněno, zůstal jen text. Vlaječky
u jednotlivých zdrojů v mřížce níž zůstávají beze změny.

## v1.2.7

### Detail titulu na ComicK měl málo informací oproti webu
Appka už dřív z ComicK API stahovala popis/stav/žánry/typ, ale samotný detail
titulu odpovídal `/comic/{slug}` API endpointu, který obsahuje mnohem víc -
bez jakéhokoli dalšího requestu navíc. Doplněno (jen pro ComicK zdroj):

- **Hodnocení** (★ 8.2) a **žebříček** (#82) - z `bayesian_rating`/`follow_rank`
- **Počet sledujících** ("Sledující: 81 139") - z `user_follow_count`
- **Alternativní názvy** pod hlavním titulem (jiné jazyky/přepisy) - appka
  tahle data ve skutečnosti dřív stahovala jen interně kvůli párování zdrojů
  (`md_titles`), teď se stejná data zobrazí i uživateli

## v1.2.6

### Žánry na detailu titulu vypadaly jako fialová pilulka nafouklá do kruhu
Poslední oprava (v1.2.5) omezila jen délku jednotlivých žánrů, ale u titulů
se spoustou krátkých "špinavých" žánrů (Bato.to) se pořád mohla fialová
pilulka roztáhnout přes celou stránku. Žánry teď appka zobrazuje jako
obyčejný textový řádek (stejný styl jako Typ/Demografie/Vydáno) - žádné
pozadí, žádné ohraničení, max jeden řádek s třemi tečkami na konci. Nemůže
se to vizuálně rozjet bez ohledu na to, co zdroj vrátí.

### Přepnutí na jinou záložku dole a zpátky tě vrátilo přesně tam, kde jsi skončil
Spodní navigace (Knihovna/Seznam/Novinky/Procházet/Historie/Nastavení) si podle
standardního Android vzoru pamatovala celý stav každé záložky - když jsi měl
rozkliknutý titul v knihovně, odešel přes zdroje a vrátil se, appka tě vrátila
zpátky na ten titul místo do knihovny. Stejně tak rozkliknutá sekce v nastavení
zůstala rozkliknutá. Tohle "pamatování" je teď vypnuté - klepnutí na záložku
dole vždy resetuje na její výchozí obrazovku.

## v1.2.5

### Detail titulu se rozpadl do obřího šedého kruhu přes celý popis
Bato.to u některých titulů vrací jako jeden ze "žánrů" obří nesmyslný text
(uvítání/poznámky slepené bez čárek, např. u "Vagabond [VIZBIG Edition]").
Appka to bez omezení vykreslila jako "pilulku" se zaoblením 50 %, která tím
pádem narostla do kruhu přes celou stránku. Přidán filtr (žánr delší než 30
znaků se ignoruje) a pojistka na max. šířku s "..." na konci, aby se tohle
nemohlo stát znovu ani u jiného zdroje.

## v1.2.4

### Appka nechávala nahoře pruh se status barem, i kolem výřezu kamery
Mimo čtečku appka pořád rezervovala plnou výšku status baru (naschvál zvětšenou
systémem kvůli výřezu přední kamery), takže nahoře byl vidět prázdný pruh -
zvlášť nápadné na telefonech se středovým "punch-hole" výřezem (např. Galaxy S26
Ultra). Čtečka už dřív běžela na celou obrazovku (systémové lišty schované,
vytáhnou se tažením od okraje) - teď stejné chování platí všude v appce, ne jen
při čtení kapitoly.

### Asura Scans nezobrazoval autora/kresliče/status
`AsuraScansSource` měl rozbitý CSS selektor na autora (vracel text "Search"
místo jména) a vůbec nečetl kresliče ani status vydávání. Opraveno přesným
hledáním podle popisku pole místo hádání podle okolí v HTML.

### Detail titulu předělaný podle vzoru ComicK
Větší obálka, méně zaoblená a vyváženěji široká tlačítka, popis díla přesunutý
až pod tlačítka akcí (dřív byl nad nimi) - odpovídá rozvržení, jaké má ComicK
na svém webu.

### Obálky v knihovně byly moc "vytáhlé"
Poměr stran karet s obálkami byl napříč appkou nastavený na 0.68 - sjednoceno
na 0.74, obálky teď vypadají přirozeněji.

### Nová funkce: galerie všech historických obálek titulu (jen ComicK agregátor)
Klepnutím na obálku titulu z ComicK zdroje appka teď (stejně jako web comick.io)
zobrazí mřížku všech obálek, které kdy byly k titulu použity, včetně čísla
svazku. Data appka bere z veřejného MangaDex Cover Art API, protože ComicK je
u sebe nedrží.

## v1.2.3

### Asura Scans ukazoval manhwa tituly jako Manga
`AsuraScansSource` nikde nenastavoval `contentType`, takže všechny tituly z tohoto
zdroje tiše dostaly výchozí hodnotu "MANGA" bez ohledu na skutečný typ - stejná
past, kterou appka řešila u 21 jiných zdrojů, Asura Scans v té dávce jenom chyběl
(nahlášeno na "Return of The Unrivaled Spear Knight", ověřeno na ComicK že jde
o Manhwa). Detailní stránka má vlastní pole "Type" - appka ho teď čte a mapuje
na Manhwa/Manhua/Novel/Manga. Opraví se při otevření detailu titulu nebo ručním
obnovení knihovny, ne v přehledu Procházet (tam web typ neukazuje).

### Slovo "Původ" na detailu titulu nahrazeno za "Typ"
Doslovný překlad ComicK labelu "Origination" nepůsobil přirozeně v češtině.

## v1.2.2

### Horní lišta čtečky přeplněná ikonami, spodní se sipkou na špatném místě
Druhé kolo redesignu čtečky podle screenshotu od uživatele. Horní lišta měla
šipky předchozí/další kapitoly, přepínač režimu panelu, časovač spánku,
inkognito a překlad - zůstává jen seznam kapitol (tři čáry). Místo ikon je
teď v horní liště vidět **název titulu** (appka ho do teď do čtečky vůbec
neposílala, jen název kapitoly) - klepnutí na název otevře detail titulu.
Předchozí/další kapitola pořád funguje přes gesto (swipe), jen bez tlačítka.

Ve spodní liště se šipka další kapitoly přesunula na konec řady. Ikona
překladu už nepřepíná rovnou, ale otevře okno s nastavením (jazyky, přepínač
překlad zapnuto/vypnuto pro tuto kapitolu, přeložit vše) - tlačítko "Další
možnosti" tím odpadlo, překlad je teď jediný vstup do stejného obsahu.
Přesunutý režim panelu/časovač spánku/inkognito ze zrušené horní lišty
přibyly jako řádek ikon navrch tohoto okna.

## v1.2.1

### Spodní lišta čtečky vypadala jinak, než měla
v1.2.0 zavedla novou tenkou spodní lištu, ale vizuálně to byl jen tenký černý
povlak přes celou šířku obrazovky, přilepený na spodní hranu bez zaoblení -
neodpovídalo to zamýšlenému vzhledu (ověřeno živě na zařízení, screenshot od
uživatele). Lišta je teď zaoblená plovoucí karta s mezerou od okrajů obrazovky,
pozadí skoro neprůhledné v tmavě námořnické barvě (stejná jako ostatní panely
ve čtečce) místo tenkého povlaku, ikony mírně zvětšeny pro výraznější vzhled.

## v1.2.0

### Appka nově cílí na Android 13 a novější
Update overlay používá AGSL `RuntimeShader` (viz níže), který na starších verzích
neexistuje - `minSdk` zvednut z 26 na 33. Appku nepůjde nainstalovat na Android 12
a starší; osm míst v kódu s vlastní kontrolou verze (`Build.VERSION.SDK_INT`)
zjednodušeno, protože se vždy vyhodnotí stejně.

### Nová animace stahování aktualizace - meditující postava, ne plochá "bublina"
Dřívější animace byla plochý drátěný tvar kreslený z primitiv Canvasu. Nahrazena
skutečným AGSL fragment shaderem: meditující postava kultivuje - rozptýlená čchi
(fialová, drží barvu appky) je vtahována po hedvábných stuhách do dolního tantienu,
kde se zjemňuje a zhušťuje v **zlaté jádro** (金丹, Jindan), na těle svítí rudá pečeť,
při dokončení jádro ztuhne a vyšle rázovou vlnu. Postava je celá procedurální
geometrie (ne obrázek), takže s ní světlo doopravdy interaguje - obrys se rozsvěcuje
podle vzdálenosti od jádra, tělo má skutečné anatomické proporce (Loomisův kánon).
Přidán bloom kolem jádra, tep (dvě pulzace za cyklus), jemné zrno v mlze a
chromatická disperze na rázové vlně pro filmovější vzhled.

### Redesign spodní lišty čtečky
Dřív byl dole pořád viditelný těžký panel se vším najednou (jazyky překladu,
posuvník stránek, jas, orientace, hromadný překlad). Nahrazen tenkou lištou
s nejčastějšími akcemi (první/poslední stránka, překlad, další kapitola, jas);
pokročilé nastavení se schovává za tlačítko "Další možnosti" místo aby bylo
pořád na očích. Seznam kapitol nahoře dostal skutečnou ikonu tří čar a vlastní
místo u pravého rohu místo aby zapadal mezi čtyři další ikony.

### Nová domovská obrazovka pro ComicK agregovaný režim
Browse v ComicK režimu měl dřív jen jednoduchou mřížku s přepínačem Populární/
Nejnovější. Nahrazeno bohatší domovskou stránkou podle vzoru webu comick.io: pět
sekcí (Nedávno přidané, Populární nové s přepínačem 7d/1m/3m, Nejpopulárnější,
Nedávné recenze, Aktualizace s taby Hot/New a nekonečným scrollováním), každá
s vlastní "Zobrazit vše" obrazovkou.

### Nová stránka skupiny (kliknutelné chipy autorů/týmů na ComicK)
Klepnutí na jméno skupiny/autora u ComicK titulu dřív nikam nevedlo. Teď otevře
mřížku všech titulů dané skupiny.

### Redesign obrazovky Statistiky
Sloupcový graf bez os/popisků a stohované pruhy stavu čtení nahrazeny ikonami
(Tabler Icons místo emoji), kalendářní mřížkou aktivity za 30 dní (styl GitHub
kontribučního grafu) a prstenem pro stav čtení.

### Redesign detailu titulu
Podle vzoru appky ComicK (referenční screenshoty od uživatele): název titulu se
přesunul nad obálku/metadata místo vedle ní, popis dostal vlastní nadpis, tlačítko
Pokračovat/Začít číst je větší a plnou barvou místo tenké pilulky.

### Přesná pozice čtení (stránka + scroll) se pamatuje 10 dní
Dřív se při návratu do kapitoly appka vracela vždy na začátek. Teď si kapitola
pamatuje přesnou stránku i posun scrollu, pokud je čtení mladší než 10 dní -
starší kapitoly se otevřou od začátku, ale pořád na správné kapitole.

### Odznak "NOVÉ" v Knihovně odstraněn, karty v Procházet už nejsou nesouměrné
Krátký jednořádkový název zdroje ("Comics Kingdom") nechával kartu v mřížce nižší
než souseda se dvouřádkovým názvem ("ReadFreeComicsOnline"), takže se řady
neposouvaly stejně. Název teď vždy rezervuje místo na dva řádky bez ohledu na
skutečnou délku.

### Drobné opravy v Procházet
Hledací pole a přepínač Populární/Nejnovější zůstávají přišpendlené nahoře při
scrollování výsledků (dřív se posouvaly pryč se stránkou). Odstraněno plovoucí
tlačítko "+" z Můj seznam. Zmenšen padding kolem favicony zdroje v kartě, aby
všechny ikony vyšly vizuálně stejně velké.

### Nastavení zjednodušené
Obrazovka "Čtení" sloučena do hlavního seznamu Nastavení (méně kliknutí),
sekce Čtečka rozdělena na 3 tematické podsekce místo jedné dlouhé.

### 49 nových zdrojů
Napříč několika koly auditu přidáno 49 zdrojů (manhwa/webtoon, hentai/doujin,
RAW čínské/korejské/japonské weby, americké komiksy). ComicK resolver navíc
přestal prohledávat 18+ zdroje u běžných (ne-adult) titulů bez ohledu na globální
nastavení - teď respektuje adult stav konkrétního titulu.

### V karuselu "Pokračovat ve čtení" šlo jen skočit rovnou do poslední kapitoly
Klepnutí na kartu vždycky otevřelo přímo rozečtenou kapitolu - nešlo se přes obálku
podívat na detail titulu (seznam kapitol, popis) bez otevření čtečky. Teď má karta
malé kolečko s tlačítkem přehrání navrch obálky, které skočí rovnou do rozečtené
kapitoly jako dřív; klepnutí kdekoli jinde na kartě otevře detail titulu. Stejně to
teď funguje i u velké karty nahoře na Knihovně.

### Manhwa/manhua/novel tituly ukazovaly v knihovně štítek "MANGA"
`SManga` (interní popis titulu) má `contentType` s výchozí hodnotou "MANGA" - a přesně
21 zdrojů (9 novel: Novelhall, Ranobes, Wuxia Box, ScribbleHub, WoopRead, HostedNovel,
Light Novel World, Royal Road, Novel Fire; 10 manhwa: ManhuaBuddy, Webtoon, Kingofshojo,
KuraManga, Galaxy Manga, Comizy, Manga18fx, Hentai20, HiveToons, Void Scans; plus
generický MadaraSource, který pohání dalších ~10 nakonfigurovaných webů typu manhua/
manhwa/novel) tenhle parametr při stavění výpisu ani při obnově detailu nikdy
neposílalo - takže každá položka z těchto zdrojů dostala tiše "MANGA" bez ohledu na
skutečný typ webu, a to jak při prvním přidání do knihovny, tak i po ručním obnovení
(pull-to-refresh), protože ani tam se typ znovu nezjišťoval. Teď ho každý z těchto
zdrojů posílá výslovně - u nových přidání se objeví správně hned, u už přidaných titulů
stačí knihovnu ručně obnovit (potáhnutím dolů).

U generického MadaraSource (weby jako mangaread.org, které hostí smíchaný obsah) navíc
pole "Type" na detailu titulu u některých položek neobsahuje klasifikaci, ale seznam
alternativních názvů (ověřeno živě na "The Former Supreme") - appka teď jako záložní
zdroj zkusí ještě štítek Manga/Manhwa/Manhua/Novel v poli "Genre(s)", které bývá
spolehlivější.

## v1.0.9

Devět oprav zdrojů v Procházet, objevených postupně na hlášení konkrétních zdrojů, které
nefungovaly nebo nezobrazovaly všechno — u tří z nich (chybějící/duplicitní výsledky u
HiveToons, nekonečné načítání u MangaCloud a stránkování obecně) ověřeno přímo na
reálném telefonu, ne jen v testech.

### Procházet se u mnoha zdrojů nikdy neposouvalo za první stránku
Appka poznávala "možná je toho víc" podle toho, jestli první stránka měla aspoň 20
položek - domněnka, že plná stránka má vždy přesně 20. Spousta zdrojů (MangaWorld,
KuraManga a dalších ~17, ověřeno živě: jiné tituly na stránce 2 než na stránce 1) má
ale přirozeně menší stránku (9, 13, 16...), takže první stránka vždy vypadala jako
poslední a scrollování dál nikdy nic nenačetlo - i když web měl klidně desítky dalších
stránek. "Konec seznamu" appka teď pozná jedině podle prázdné stránky, ne podle
magického čísla. Ověřeno přímo na telefonu - MangaWorld teď při scrollování plynule
načítá další a další stránky.

### nhentai v Procházet ukazoval pořád jen tu samou hrstku titulů
`/galleries/popular`, který appka volala, je podle vlastní dokumentace nhentai "dnešní
populární galerie" - pevná pětice bez stránkování (page=1/2/3 vracelo identický
výsledek, ověřeno živě). Teď appka volá obecný `/galleries?page=N`, který má skutečné
stránkování (25 různých titulů na stránku) - ověřeno i přímo na telefonu.

### HiveToons a Vortex Scans nezobrazovaly žádné výsledky
Oba weby (stejná Astro šablona) posílají na adresu bez koncového lomítka přesměrování
na **http://**, ne https - appka takové nešifrované spojení správně odmítá, což se navenek
projevilo jako "Žádné výsledky". Ověřeno přímo na telefonu (log skutečného síťového
přenosu). S lomítkem web odpoví rovnou, žádné přesměrování.

Po opravě se navíc ukázal druhý, skrytý bug: HiveToons u každého titulu v seznamu má
dva odkazy na stejnou stránku (obálka + název pod ní) - bez odstranění duplicit appka
na to spadla ("Key ... was already used"). Taky opraveno a ověřeno pádem i zprovozněním
na reálném zařízení.

### Odebrány dva mrtvé zdroje
PAWMANGA (pawmanga.com) - doména je zaparkovaná, žádný manga obsah. Manhuarm
(manhuarmtl.com) - web žije, ale katalog je prázdný na všech testovaných stránkách.

### Mangago nezobrazoval žádné výsledky
Web přesunul výpis populárních titulů z `/list/allmanga/page/N/` (mrtvé, "Total: 0") na
`/list/?page=N` a hledání z `/r/search.php` (404) na `/r/l_search/` - obě URL i jejich
struktura HTML se od doby, kdy byl zdroj napsaný, změnily. Adresa obálky navíc není v
`src` (to je jen sdílený placeholder pro lazy-load), ale v `data-src`.

### MangaCloud se dokola točil při načítání
Web vyžaduje neviditelně vyřešenou Cloudflare výzvu přes WebView, což trvá až 30 sekund.
Když se to nepovedlo, appka to zkoušela znovu od nuly při každé další kartě/scrollu/
otevření zdroje - uživatel viděl nekonečné točení, i když jednotlivé pokusy nakonec
doběhly. Neúspěch se teď na 2 minuty zapamatuje, takže další pokusy selžou rovnou
místo dalšího 30sekundového čekání (stejný princip jako `CloudflareInterceptor` už
používá pro jiné zdroje).

### Chybějící obálky v Procházet u Novelhall, Comics Kingdom a Dynasty Scans
Rychlý výpis titulů (Populární/Nejnovější) u těchto tří zdrojů obálku vůbec neobsahuje -
je jen na stránce detailu jednotlivého titulu. Karta v mřížce si teď obálku dotáhne sama,
jakmile se doscroluje do viewportu (`SourceBrowseViewModel.fetchCoverIfMissing`) - ne
najednou pro celou stránku výsledků, jen pro to, co reálně vidíš.

### Chybějící obálky v Procházet u Novel Cool
Web používá lazy-loading obrázků: `src` je vždy jen sdílený placeholder, skutečná
adresa obálky je ve vlastním atributu `lazy_url`, který se dosud nikde nečetl.

### Odebrání z knihovny dlouhým podržením
Na kartě mangy/novely v Knihovně (rozečtené, nedávno přidané, dokončené i výsledky
hledání a jejich "Zobrazit vše" mřížky) teď jde dlouze podržet prst a odebrat titul přímo
odtud — dřív to šlo jen ze seznamu Seznam přes tři tečky. Protože akce smaže i stažené
kapitoly a celý postup čtení a nejde vzít zpět, dlouhý stisk nejdřív ukáže potvrzovací
dialog se jménem titulu.

### Špatné štítky MANGA/MANHWA/MANHUA u titulů ze smíchaných webů
Weby jako mangaread.org hostí manga, manhwa i manhua dohromady, takže appka měla pro
celý web jeden pevný odhad typu — u titulu, který do většinového typu webu nezapadal
(např. "Return of The Unrivaled Spear Knight" nebo "The Former Supreme", obojí manhwa
na webu tagovaném jako MANGA), to vyšlo špatně. Standardní Madara šablona přitom u
každého titulu sama uvádí přesný typ v poli "Type" na stránce detailu — appka ho jen
nikdy nečetla. Teď se čte a má přednost před odhadem za celý web.

Netýká se automatické kontroly nových kapitol na pozadí (běží dál beze změny) — oprava
se spustí až při ručním refreshi (potáhnutí v Knihovně dolů, nebo tlačítko refresh na
detailu titulu), protože stahovat navíc celou stránku jen kvůli kosmetickému štítku by
se do tichého denního běhu nehodilo.

## v1.0.8

Dvě opravy sazby textu v bublině. **Nic se nepřekládá znovu** — obojí se počítá až při
zobrazení, do cache nejde nic, takže se to projeví i na stránkách, které tam už leží.

### Příliš velký text se do bubliny zmenší místo toho, aby se ořízl
Box s překladem se ořezává obrysem bubliny a má strop výšky. Co se nevejde, se nevykreslí —
zmizí. Jako pojistka proti přetékání do sousedního panelu to bylo myšleno dobře, ale znamenalo
to, že kdykoliv sazba neuspěje, text se ztratí místo aby byl malý.

A neuspět musela: podlaha velikosti písma se počítala jako `6 × textScale`, přičemž posuvník
velikosti textu ve čtečce má rozsah 0,7–1,6. Na maximu tedy podlaha vycházela na 9,6 sp a do
drobné bubliny se takové písmo nevejde ani teoreticky.

Důsledek, který stojí za vyslovení: **čím větší písmo sis nastavil, tím víc textu z malých
bublin zmizelo** — přesně opačně, než co jsi tím nastavením chtěl.

Podlaha se teď smí posunout už jen dolů (kdo si písmo zmenšuje, chce menší text); nahoru ji
nastavení nezvedne, to ovlivňuje dál jen strop a preferovanou velikost. Drobné písmo je vědomě
lepší než uříznutý text — nečitelnou bublinu jde pořád klepnutím přepnout na originál a dlouhým
stiskem ručně opravit.

### Koncová tečka nezůstává sama na řádku pod textem
Rozhodla srovnávací dvojice snímků: originál „WE'RE TAKING OFF." se vykreslil jako „ODLÉTÁME"
a pod tím osamocená tečka na vlastním řádku. Nechybělo tam tedy nic, překlad byl celý — rozbitá
byla jen ta tečka. Stejný podpis měla i „TAKEZO." vykreslená bez tečky a dřív hlášená osamocená
tečka pod „MATAHACHI".

Příčinou je mezera (nebo zlom řádku) před koncovou tečkou. Zalamovač v ní zcela korektně nabídne
zlom, „ODLÉTÁME" se na řádek vejde a „." už ne.

Pravidlo sahá záměrně jen na interpunkci, za kterou už nic není: „…KONEC" na začátku bubliny je
běžná komiksová sazba (věta pokračující z předchozí bubliny) a slepit ji na předchozí slovo by
změnilo zalomení, které tam autor chtěl.

Druhá, nezávislá pojistka: blok bez jediného písmene se nevykresluje vůbec. Nemá co překládat
a nechat prosvítat originál je vždycky lepší — interpunkce zůstane přesně tam, kam ji nakreslil
autor.

820 unit testů, 0 varování.

## v1.0.7

Oprava regrese z v1.0.6. **Pipeline se mění (16 → 17), stránky se přeloží znovu** — chybný
příznak se ukládá do cache, takže bez toho by placky na už přeložených stránkách zůstaly.

### Plná výplň se rozlila přes kresbu
Popisek „BITVA U SEKIGAHARY" dostal přes vodovkovou bitevní scénu modrou placku, panely
„DUP"/„TROMP" růžovou. Do v1.0.5 se tam kresba dopočítávala a bylo to bez poznání.

Způsobila to změna z v1.0.6, kterou jsem zavedl kvůli zbytkům originálu v bublinách:
jednolitost pozadí přestala hlídat největší odchylku vzorku a začala brát 85. percentil.
Jenže ta bitevní scéna je barevně *docela* jednotná — většina prstence padne do tolerance
a mimo ni je jen menšina (kmen stromu, tmavý terén pod popiskem). Prošla tedy jako
„jednolité pozadí" a dostala plnou výplň místo záplaty.

Rozhoduje zase největší odchylka, tedy stav z v1.0.5.

Podstatné je, proč to nešlo jen doladit jinou mezí: z pouhých vzorků je „pár odlehlých
tmavých hodnot" **nerozlišitelné** mezi tahem písmene a tmavým detailem kresby. Percentil
tedy nemůže být ten mechanismus, ať dostane jakýkoliv práh.

**Vrací se tím i ústupek z v1.0.5:** v bublině může pod překladem zůstat drobný zbytek
originálu. Placka přes kresbu je horší, takže to je vědomá volba, ne přehlédnutí. Řešit se
to bude tím, kde se vzorkuje prstenec kolem textu — ne tolerancí.

808 unit testů, 0 varování.

## v1.0.6

Samé opravy překladu, všechny z nahlášených stránek Vagabonda. **Pipeline se mění
(PIPELINE_VERSION 15 → 16), takže se už přeložené stránky přeloží znovu** — jinak by
nová pravidla na staré cache nebyla vidět.

### Klasifikace zvuků polykala dialogy
Věty jako „POSLEDNÍ" nebo „SKONČILA." se braly za kreslený zvuk (SFX) a nechávaly se
nepřeložené. Vinou toho bylo pravidlo „vše velkými písmeny je zvuk" plus ruční seznam
výjimek, který nešlo doplnit tak, aby pokryl každé krátké slovo v jazyce.

Rozhoduje teď uzavřený seznam skutečných zvuků a dva signály, které žádný seznam
nepotřebují: latinské slovo bez jediné samohlásky, a krátký text nakreslený přímo na
kresbě (mimo bublinu). Seznam zvuků navíc snese protažené varianty — „KRAAASH"
i „KRASH" se spárují se stejnou položkou.

### Rozdělená slova s jedním písmenem na řádku
„POSLEDN" / „Í" — model směl dělící místa navrhovat kamkoliv a appka je přebírala bez
kontroly. Nově se každý úsek mezi dělítky počítá na písmena a s méně než dvěma se
dělení zahodí.

### Náhradní překladač psal bez kontextu
Když hlavní poskytovatel odpadl, záložní dostal holé věty bez názvu díla i bez
předchozích replik — odtud nesouvislé a doslovné překlady uprostřed dialogu. Kontext
se teď posílá i tudy.

> Vyžaduje nasazení edge funkce, jinak ji appka posílá a proxy zahazuje.

### Bílá bublina se brala za pestrou kresbu
Pod přeloženým textem zůstávaly zbytky originálu — osamocená tečka, drobné artefakty.
Prstenec kolem textu se vzorkuje kousek od OCR boxu a ten občas ořízne kraj písmene,
takže pár vzorků padlo rovnou na černý tah. Podmínka přitom brala největší odchylku,
takže jediný takový vzorek přehodil celou bublinu na „pestrá kresba" a ta pak dostala
záplatu místo plné výplně.

Rozhoduje teď 85. percentil: hrst vzorků na písmenu verdikt neovlivní. Ověřeno na
zařízení, že text na SKUTEČNĚ pestré kresbě dál vychází správně — tolerance neoslabila
rozpoznání, kvůli kterému záplata vznikla.

### Popisky na kresbě zůstávaly čitelné i po překladu
Maska záplaty se ořezávala přesně na OCR box, jenže ten je jen aproximace otisku písma —
ML Kit ho běžně vede kus uvnitř skutečných tahů. Co přesáhlo, zůstalo nedotčené: lem
kolem písmen, spodky dotahů, tečky na hranici. Oblast se teď před ořezáním rozšíří
o rezervu odvozenou z výšky písma.

808 unit testů, 0 varování.

## v1.0.5

Na prekladu se nic nemeni - prelozene stranky zustavaji v cache.

### Prepinac "Je mi 18 a vice" zdroje pro dospele neodemykal
Prepinac psal jen priznak potvrzeneho veku, kdezto seznam zdroju se ridi uplne jinym nastavenim (Nastaveni > Zdroje). A byl zapojeny jen JEDNIM smerem: pri vypnuti zdroje schoval, pri zapnuti neudelal nic - prestoze pod nim stoji "Odemyka zdroje s obsahem pro dospele". Tykalo se to i zdroju se smisenym obsahem.

Bylo to dusledkem drivejsi zmeny, kdy se vychozi viditelnost prehodila z "zapnuto" na "vypnuto"; do te doby to bylo zapnute samo a rozpojeny smer nebyl videt.

Opraveno na obou stranach: prepinac ted nastavuje obojí, a kdo ma plnoletost potvrzenou z drivejska a viditelnost nikdy vyslovne nenastavenou, uvidi zdroje hned po aktualizaci bez sahani na cokoliv. Kdo si je vypnul sam, ma svou volbu zachovanou.

### Hlavicky uz pri scrollovani neplavou s obsahem
Historie, Aktualizace, Muj seznam a Nastaveni - stejne jako driv Prochazet a Knihovna. Hlavicka stala mimo scrollovanou oblast, takze zustavala viset nahore a obsah jezdil pod ni.

775 unit testu, 0 varovani.

## v1.0.4

Na prekladu samotnem se nic nemeni - prelozene stranky zustavaji v cache.

### Preklad kapitol na pozadi
Preklad se do ted spoustel vyhradne ze ctecky, a to ve viewModelScope - tedy ve scope svazanem se zivotem te obrazovky. Odejdi ze ctecky nebo zavri appku a preklad se zrusil uprostred. Zadne prekladani na pozadi v appce neexistovalo, jen se tak tvarilo.

Nove bezi ve WorkManageru s popredovou notifikaci. Overeno: po spusteni a zavreni appky notifikace zustava, worker vola API a preklad pokracuje. Do cache se behem testu se zavrenou appkou ulozilo 45 prelozenych stranek.

### Prelozit kapitoly dopredu
V detailu mangy: prekryvne menu kapitol -> "Prelozit dopredu..." -> 1/3/5/10 kapitol. Fronta je zamerne sekvencni: preklad nebrzdi rychlost site jako stahovani, ale znakova kvota, takze pet kapitol najednou by ji vycerpalo petkrat rychleji a stalo by frontu na tentyz upstream.

Bere se od nejnizsiho cisla NEPRECTENYCH kapitol. Seznam na detailu je bezne otoceny (nejnovejsi nahore), takze bez razeni podle cisla by "5 kapitol dopredu" prelozilo pet nejnovejsich - presny opak toho, co chce ctenar.

### Groq obcas odpovedel prozou misto JSONu
Vynuceny JSON rezim dostavali dva provideri ze tri: Gemini responseMimeType, OpenRouter json_schema, Groq nic. Model tedy smel odpovedet prozou ("Preklady...") a appka celou davku zahodila na vyjimce vcetne znaku, ktere za to volani upstream uz odecetl.

Groq ted dostava response_format json_object (vyzaduje nasazeni edge funkce, uz provedeno) a parseResponse navic vyloupne JSON i z okolniho textu.

768 unit testu (+11), 0 varovani.

## v1.0.3

Jen ovladani, na prekladu se nic nemeni - prelozene stranky zustavaji v cache.

### Hlavicky uz pri scrollovani neplavou s obsahem
Na Prochazet i na Knihovne stala hlavicka (nadpis, hledani, filtry) mimo scrollovanou oblast a zustavala viset nahore. Zabirala skoro tretinu obrazovky porad, i pri scrollovani hluboko v seznamu. Ted odjede s obsahem: v Prochazet je po odscrolovani videt 7 rad zdroju misto 4,5.

### "Zobrazit vse" na Knihovne konecne neco dela
Sipka i napis vypadaly jako odkaz, ale byl to obycejny text bez jakehokoli kliknuti - nikdy to nic nedelalo. Otevira se ted obrazovka s celou sekci (Pokracovat ve cteni / Nedavno pridane / Dokoncene) v mrizce po trech misto vodorovneho posuvniku, ve kterem se u vic titulu neda nic najit. U rozecetenych titulu vede klepnuti rovnou do posledni kapitoly.

757 unit testu, 0 varovani.

## v1.0.2

Prelozene stranky se pri prvnim otevreni prelozi znovu (PIPELINE_VERSION 13 -> 15).

### Vzorovane pozadi bubliny uz neprekryje bila nalepka
Bubliny, ktere maji uvnitr jemnou texturu, dostavaly pres sebe bilou plochu, ktera pokryla jen stred - po okrajich prosvitala puvodni textura a vypadalo to jako nalepka nalepena pres kresbu. Obrys balonku hledame vylevanim barvy a texturni cary se pro nej chovaji jako stena, takze se vylevani zastavi driv, nez dojde k okraji. Nove rozhoduje, jestli JE pozadi jedne barvy, ne jestli se nasel nejaky obrys: kdyz jednolite neni, zakryji se jen tahy pismen a vzorek kolem prezije.

### Preklad vi, odkud dilo je
Typ obsahu se modelu posilal jen jako nalepka v zavorce a co z ni plyne si musel domyslet sam - u manhwy si typicky domyslel japonska honorifika, prestoze "hyung" a "senpai" nejsou zamenitelne. Kazdy typ ma ted vlastni pravidlo pro osloveni a prepis jmen.

### Preklad navazuje na to, co uz zaznelo
Uvnitr jedne davky mel model kontext vzdycky, ale na jeji hranici zacinal s cistym stolem, takze se uprostred rozhovoru mohlo prehodit tykani/vykani nebo osloveni postavy. K dalsi davce se ted pribali ocasek uz prelozenych replik. Plati to i pri cteni stranku po strance.

### Svisle sazena japonstina
Cela stranka se slevala do JEDNOHO bloku s promichanym textem. ML Kit vraci cely sloupec jako jeden "radek" a stare pravidlo porovnavalo mezeru mezi sloupci s vyskou sloupce - to je vzdalenost pres pul stranky, takze se slily i bubliny 350 px od sebe. Sloupce maji ted vlastni pravidlo a skladaji se zprava doleva.

### Zmereno a zamitnuto
Predzpracovani obrazku pred OCR (binarizace, roztazeni kontrastu, zvetseni) i pouziti OCR confidence jako varovani. Ani jedno nepomohlo natolik, aby stalo za svou cenu - cisla jsou v repozitari u prislusnych sond, aby se to nezkouselo znovu od nuly.

757 unit testu (+32), 0 varovani.

## v1.0.1

Prelozene stranky se pri prvnim otevreni prelozi znovu (PIPELINE_VERSION 12 -> 13).

### Kvalita prekladu - tri doblozene priciny
- **Slova delena pomlckou na konci radku se spoji.** Bublina "EVERY-" / "ONE DON'T SCATTER, STAY TOGETHER!" dorazila k modelu jako rozsypany zacatek vety a v prekladu z ni vypadl zapor: "VSICHNI SE ROZPTYLEJTE, ZUSTAVEJTE SPOLU!" - veta, ktera si odporuje sama v sobe. Pomlcka se nemaze, jen se odstrani zalomeni za ni, takze skutecny spojovnik ("well-known") zustane nedotceny.
- **Do glosare uz nejde ulozit cokoliv.** Plnil se automaticky z toho, co model vratil, BEZ jedine kontroly - a v promptu byl oznaceny jako zavazny. Stacilo, aby si tam jednou zapsal nesmysl, a vnucoval si ho ve vsech dalsich kapitolach. Odtud "ZAVRI PANU" misto "drz hubu"; slovo "mouth" pritom zadny druhy vyznam nema. Nove projde jen to, co vypada jako jmeno, ne bezne slovo ani cela veta.
- **Prompt ma pet pravidel uplne nahore** (zapor se nesmi ztratit, veta si nesmi odporovat, idiom nedoslova) a **zaverecnou kontrolu** pred sestavenim odpovedi. Glosar uz neni nadrazeny smyslu vety.

Zadne z toho nestoji jedine API volani navic.

725 unit testu (+18), 0 varovani.

## v1.0.0

Prvni plna verze. Prelozene stranky se pri prvnim otevreni prelozi znovu (PIPELINE_VERSION 11 -> 12, viz v0.9.1).

### Preklad
- **Rucni oprava bubliny.** Dlouhy stisk -> prepsat text -> ulozit. Preklad stoji na free modelech, ktere obcas selzou, a do ted s tim neslo nic delat. Opravy zijou ve vlastni tabulce, takze prezijou i prepocet po zvednuti verze pipeline; bublina se pozna podle puvodniho textu, ne podle poradi. Prazdne pole opravu zrusi.
- Cerna placka pres pul panelu v miste vodoznaku (v0.9.1), rozmazany cizi text pres preklad (v0.8.9), necentrovany text v bublinach s ocaskem (v0.8.8), neprelozeny utrzek v kaskadove bubline (v0.9.0).

### Soukromi a vek
- Novy krok onboardingu: datum narozeni (uklada se JEN odvozeny priznak, datum se zahodi) a prehled toho, co z telefonu odchazi.
- Zdroje pro dospele jsou nove VYCHOZE SKRYTE - drive se nabizely rovnou po instalaci.
- Hlaseni padu je nezaskrtnuty souhlas. Do ted se sbiralo v kazdem release buildu natvrdo.
- Obojí jde kdykoli zmenit v Nastaveni -> O aplikaci -> Soukromi.

### Pod kapotou
- **Skok zavislosti**: Kotlin 1.9.24 -> 2.2.21, AGP 8.5.2 -> 8.13.2, Compose BOM 2024.06 -> 2025.12, Room 2.8.4, Gradle 8.13. targetSdk 34 -> 36, overeno na skutecnem Androidu 16.
- Pull-to-refresh prepsan na PullToRefreshBox, TabRow -> SecondaryTabRow a dalsi vynucene migrace.
- **Zadny catch uz chybu nespolkne beze stopy** - 25 mist prevedeno na ErrorReporter.
- Kvota prekladove proxy se uz nestrhava za pokusy, ktere upstream odmitl.
- Uklid jen prohlednute mangy pri startu - tabulka rostla z kazdeho otevreneho detailu a nic ji nemazalo.
- Vsechny instrumentovane testy zapnute (drive 5 z 6 vypnutych) a nezavisle na stavu zarizeni.
- CHANGELOG doplnen o 23 chybejicich verzi (v0.3.4 - v0.7.9).

707 unit testu, 14 instrumentovanych, 0 varovani kompilatoru.

## v0.9.1

Prelozene stranky se pri prvnim otevreni prelozi znovu (PIPELINE_VERSION 11 -> 12) - obrys bubliny se uklada, takze na starych zaznamech by se oprava neprojevila.

### Opravene chyby
- **Cerna placka pres pul panelu tam, kde je vodoznak skenlacni skupiny.** Obrys bubliny se hleda vylitim barvy od bodu kolem OCR textu. U vodoznaku lezicino na tmavem pruhu je plocha kolem nej souvisle tmava pres cely panel, takze se vyliti nezastavilo na zadne hranici. Jedina pojistka byl plosny limit vztazeny ke CELE strance (ctvrtina) - jenze ctvrtina stranky 1440x3120 je pres milion pixelu, do kterych se unikle vyliti pohodlne vejde.

  Zmereno na zarizeni na nahlasene strance (obalovy obdelnik obrysu proti OCR boxu textu):

  | blok | pomer |
  |---|---|
  | "MOUNTAIN BEASTS..." | 2,7x |
  | "GOOD HEAVENS, IT'S A TRAP!" | 4,3x |
  | "DAMN..." (jedno slovo v kulate bubline) | 16,1x |
  | vodoznak "SIRENSCANS.COM" | 54x az 216x |

  Skutecne bubliny tedy konci u 17x, unikle vyliti zacina nad 54x. Novy limit je 30x - lezi mezi nimi s rezervou na obe strany. Pri prekroceni se obrys zahodi a pouzije se heuristicky obdelnik: horsi odhad tvaru, ale nikdy ne placka pres kresbu. Overeno na skutecne strance v sesti ruznych rozlisenich: vodoznak obrys ztratil ve vsech, vsechny tri skutecne bubliny si ho ve vsech nechaly.

## v0.9.0

Prelozene stranky se pri prvnim otevreni prelozi znovu (PIPELINE_VERSION 10 -> 11) - obe opravy nize meni to, co se uklada, takze na starych zaznamech by se neprojevily.

### Opravene chyby
- **Utrzek vety v horni bublince kaskadove ("snehulakove") bubliny zustaval anglicky.** Nahlaseny pripad: horni lalok "...SAY," anglicky, spodni lalok cesky. Dve nezavisle priciny, obe musely padnout:

  **1. Klasifikator ho oznacil za zvukovy efekt** - a SFX se nikdy neposila na preklad ani nevykresluje, takze v bubline zustane original.

  Pravidlo "kratky text velkymi pismeny bez mezer = zvuk" ma jedinou pojistku: seznam beznych kratkych slov, ktera zvuk nejsou. Jenze text se pred porovnanim orezaval jen o `!`, `?`, `.` a mezeru - **carka mezi ne nepatrila**. Do porovnani tedy slo "WAIT," misto "WAIT" a nikdy se netrefilo. Propadla tak i slova, ktera seznam VYSLOVNE chrani: overeno, ze jako zvuk se klasifikovalo "WAIT,", "DAMN,", "NO,", "HEY," i "AH~".

  Nove se oreze veskera okrajova interpunkce (carka, strednik, dvojtecka, vlnovka, uvozovky, CJK protejsky) a navic plati, ze **text pokracujici ve vete zvuk neni** - carka na konci nebo vypustka na zacatku jsou gramaticke znacky pokracovani a funguji v jakemkoli jazyce, na rozdil od rucniho anglickeho seznamu. Do seznamu pribylo i "SAY" a dalsi bezne jednoslovne repliky.

  **2. Prompt si protirecil.** Sekce o vetach pres vic bublin zakazuje nechat bublinu prazdnou, ale sekce CHYBY uvadela "utrzek" jako duvod vratit `[UNTRANSLATED]`. Horni lalok kaskadove bubliny JE utrzek, takze i kdyby se blok poslal, model mel duvod ho odmitnout. Marker je nove vyhrazeny textu, ktery se neda PRECIST (zkomolene OCR, zbytek vodoznaku); kratka nebo nedokoncena veta duvod neni.

## v0.8.9

Hotove preklady zustavaji v platnosti - tahle verze meni jen vykreslovani, ne to, co je ulozene.

### Opravene chyby
- **V nekterych bublinach se pres cesky preklad vznasel rozmazany cizi text.** Zaplata pozadi (ta, co u textu leziciho primo na kresbe zakryva jen tahy pisma a zbytek obrazu nechava byt) se POCITALA z OCR boxu textu, ale VYKRESLOVALA se pres cely box bubliny - a ten je vzdycky vetsi. Kreslila se pres `ContentScale.FillBounds`, takze se maly vyrez roztahl pres velkou plochu. Zbytky tahu, ktere se pri dopoctu nepodarilo docistit, se tim zvetsily, rozmazaly a posunuly mimo sve misto - doprostred prelozeneho textu.

  Zmereno na nahlasene strance: radkovani zbytku 88 px proti 62 px v originale, tedy zvetseni 1,4x. U textu na kresbe muze byt box az 3,3x sirsi nez OCR box, tam bylo roztazeni jeste vetsi.

  Tri opravy:
  - Bublina s **detekovanym obrysem** zaplatu uz nedostava vubec. Flood-fill najde obrys jen tam, kde je uvnitr souvisla plocha jedne barvy - to je definice skutecne nakreslene bubliny, kde je vypln oriznuta tvarem od originalu k nerozeznani. Presne tyhle bubliny byly na nahlasenych snimcich.
  - U zbylych bloku se zaplata pocita presne pres ten obdelnik, pres ktery se vykresli. Obe strany si ho berou ze stejne funkce, takze uz se nemuzou rozejit.
  - Kresli se `FillWidth` + zarovnane k hornimu okraji misto `FillBounds`, takze vyska boxu (ta se ridi delkou textu) uz zaplatu svisle netahne.

- **Pismo se hleda jen v miste, kde ho OCR naslo.** Zaplata ted pokryva vetsi plochu nez drive, a prahovat i ten presah by znamenalo rozmazavat kresbu tam, kde zadny text nikdy nebyl. Mimo textovou oblast se pixely jen opisou.

- **Dve shodne bubliny na strance si mohly prohodit zaplatu.** Dohledavala se pres `blocks.indexOf(block)`; dva bloky se stejnym textem i souradnicemi jsou si podle data class rovny, takze `indexOf` vracel porad ten prvni. Klicem je nove poloha v seznamu.

## v0.8.8

Hotove preklady zustavaji v platnosti - tahle verze meni jen vykreslovani, ne to, co je ulozene.

### Opravene chyby
- **Text sedel v bubline moc vysoko nebo moc nizko.** Obrys bubliny se hleda vylitim barvy a to zabere i OCASEK - ten uzky vybezek, co ukazuje na mluvciho. Text se pritom centroval na obalovy obdelnik celeho obrysu, jenze ten je kvuli ocasku o dost vyssi nez plocha, kde text doopravdy je. Blok se tak vzdycky odtahl smerem k ocasku.

  Zmereno na nahlasene strance: horni lalok mel obrys y=0.488..0.645 (stred 0,567), ale skutecna textova plocha y=0.559..0.645 (stred 0,602) a puvodni anglicky text stred 0,600. Sazba tedy mirila o 3,3 % vysky stranky vys, nez kde text v originale byl - pres sto obrazovych bodu. Nove se centruje na textovou plochu, ktera na originalni umisteni sedi na tri tisiciny.

  Nejvic to bylo videt u kaskadovych bublin, kde ocasek visi na jednom z laloku: horni text se tlacil nahoru, spodni dolu.

## v0.8.7

### Upozorneni pri aktualizaci
- Ulozene preklady se po instalaci prepocitaji — model nove dostava informaci o vetach pres vic bublin, coz meni vysledny preklad.

### Opravene chyby
- **Veta rozdelena do dvou laloku se prekladala po pulkach.** Appka si umi spocitat, ze dve bubliny tvori jednu vetu, a modelu to predava jako fakt — jenze u kaskadove („snehulakove“) bubliny se to nikdy nespustilo. Vyzadovalo se, aby se bubliny vodorovne prekryvaly aspon z 35 %, coz mlcky predpoklada, ze lezi pod sebou. Laloky kaskadove bubliny jsou ale posunute do stran, prave to jim dava ten schodovity tvar.

  Zmereno na nahlasene strance: skutecny prekryv byl **17,8 %**. Model se tedy o souvislosti nedozvedel a kazdou pulku prelozil jako samostatnou vetu. Prah je nove 15 %.

### Jak se to naslo
Dve kreslene rekonstrukce te stranky se od skutecnosti rozesly zrovna v tom, co rozhodovalo. Pipeline proto bezela primo na nahlasenem snimku a namerila, co se doopravdy deje — vcetne dukazu, ze premalovavani bublin, ktere trapilo predchozi verze, je na tehle strance opravdu vyresene.

## v0.8.6

### Upozornění při aktualizaci
- Uložené překlady se po instalaci přepočítají — obrys bublin se počítá jinak a staré záznamy nesou ten původní, přetékající.

### Opravené chyby
- **Kaskádová bublina pořád přemalovávala text.** Oprava z v0.8.5 se u nahlášené stránky vůbec nespustila. Rozhodovala se podle toho, jestli se rámečky rozpoznaného textu vodorovně překrývají aspoň ze čtvrtiny — jenže laloky kaskádové bubliny jsou *záměrně* posunuté do stran (horní vpravo, spodní vlevo), právě to jim dává ten schodovitý tvar, takže se rámečky překrývají sotva.

  Změřeno na emulátoru před opravou: oba bloky dostaly **totožný obrys celého balónu**, tedy přesně stav bez opravy. Nově se místo rámečků ptáme na to podstatné — *pokrývá můj obrys cizí text?* Po opravě mají tytéž bloky každý svůj úsek a ani jeden už na cizí text nesahá.

- **Písmo bylo zhruba o třetinu menší, než mělo.** Velikost se odhadovala z výšky rozpoznaného rámečku pevným dělením, které neodpovídalo skutečnosti. Naměřeno přímo na zařízení: rámeček je u verzálek 0,73× a u textu s malými písmeny 1,05× výška písma. Text tak v bublinách sedí o dost lépe.

## v0.8.5

### Upozornění při aktualizaci
- Uložené překlady se po instalaci přepočítají — obrys bublin se nově počítá jinak a staré záznamy nesou ten původní, přetékající.

### Opravené chyby
- **Bublina přemalovávala text bubliny sousední.** Kaskádová replika bývá nakreslená jako dvě *překrývající se* bublinky, které tvoří jednu spojitou bílou plochu. Hledání obrysu se přes to místo přelilo do druhého laloku, takže každá bublina si myslela, že jí patří plocha obou — a ta poslední vykreslená přemalovala text těch ostatních. Zmizel tak i text, který se vůbec nepřeložil: místo něj zůstala prázdná bílá plocha.

  Změřeno na zařízení: bez opravy dostaly všechny tři textové bloky na testovací stránce **naprosto stejný obrys** (celý balón), po opravě má každý svůj vlastní úsek.

  Při té příležitosti se potvrdilo, že rozpoznávání textu horní bublinu **najde** — chyba byla čistě ve vykreslování, ne v OCR ani v překladu.

## v0.8.4

### Upozornění při aktualizaci
- Uložené překlady se po instalaci přepočítají. Změnilo se, co překladač o bublinách ví (viz níž), takže staré výsledky by tu opravu neobsahovaly. Nic se neztratí, jen první otevření kapitoly bude pomalejší.

### Kvalita překladu
- **Věta rozdělená do dvou bublin se překládá jako celek.** Překladač dosud viděl jen plochý seznam textů a neměl jak poznat, že dvě bubliny tvoří jednu repliku — kaskádový dialog (úvodní citoslovce nahoře, zbytek dole) se tak překládal po kouscích a návaznost se ztrácela. Nově se souvislost spočítá z rozmístění bublin a interpunkce a překladač ji dostane jako zadání: přelož jako celek, ale **rozděl zpátky přesně tak, jak byl rozdělený originál**. Text se nikdy nepřesouvá mezi bublinami, horní zůstává nahoře a spodní dole.
- **Tradiční čínština se čte zprava doleva.** Směr rozhodovala jen japonština, takže tchajwanské a hongkongské komiksy dostávaly bubliny seřazené obráceně a překladač četl repliky pozpátku. Zjednodušené čínštiny se to netýká — ta se čte zleva doprava. Projeví se, jen když si zdrojový jazyk vyberete ručně.

## v0.8.3

### Upozornění při aktualizaci
- Uložené překlady se po instalaci přepočítají. Zdrojový jazyk je součástí jejich klíče, a ten se teď mění (viz níž) — nic se neztratí, jen první otevření kapitoly bude pomalejší a sáhne to na denní limit překladů.

### Opravené chyby
- **Výchozí zdrojový jazyk byl „English", takže japonská manga se nepřeložila vůbec.** Na japonskou, korejskou i čínskou stránku se pouštěl latinkový rozpoznávač, který na nich nenajde nic — naměřeno doslova nula znaků. Výsledek: prázdný překlad bez jediného vysvětlení. Nově je výchozí „Auto" a rozpoznávač se vybírá podle toho, co na stránce doopravdy je.

### Vzhled
- **Text ležící přímo na kresbě už se nepřekrývá jednolitou plochou.** Dosud se přes celý rámeček natáhla jedna navzorkovaná barva — odtud hnědé placky přes barevné kresby a tmavé skvrny přes obličeje. Nově se zakryjí jen tahy původního písma a každý zakrytý bod se dopočítá z okolí, takže kresba mezi písmeny zůstane vidět. U běžných bublin se nic nemění, tam byla dosavadní výplň k nerozeznání od originálu.

## v0.8.2

### Opravené chyby
- **Sdílení QR kódu bylo opravené jen napůl.** Ve v0.8.0 se dialog sdílení začal otevírat, ale příjemce obrázek nesměl otevřít — místo náhledu zůstalo prázdno a sdílení do řady aplikací selhalo. Nalezeno až při zkoušce na emulátoru.

## v0.8.1

### Upozornění při aktualizaci
- Uložené překlady se po instalaci zahodí a spočítají znovu (změnila se rozpoznávací a klasifikační logika). První otevření kapitoly bude pomalejší a sáhne to na denní limit překladů.

### Opravené chyby
- **Zdrojový jazyk „Auto" ve skutečnosti znamenal latinku.** Byla to první nabízená možnost, ale rozpoznávání textu pro ni nemělo vlastní větev a spadlo na latinkový model — kdo si „Auto" vybral a otevřel japonskou, korejskou nebo čínskou mangu, dostal nesmysl nebo nic. Bubliny se navíc seřadily zleva doprava, takže překladač četl repliky pozpátku. Nově se rozpoznávač vybírá podle toho, co na stránce opravdu je.
- **Běžný dialog mohl zmizet jako „vodoznak".** Tři repliky, kde každá jen prodlužovala předchozí („HELP" / „HELP ME" / „HELP ME NOW"), se označily za nastampovaný vodoznak a vůbec se nepřeložily.
- **Krátké repliky v neanglických komiksech se ztrácely.** Pravidlo „krátký text velkými písmeny = zvukový efekt" mělo pojistku jen pro angličtinu, takže třeba španělské „VAMOS" propadlo jako zvuk a zůstalo nepřeložené.

### Data
- **Cache přeložených novel se nikdy nezneplatnila ani neuklízela.** Po opravě překladu zůstávala stará verze napořád, tabulka rostla bez omezení a tlačítko „smazat cache překladů" se jí vůbec nedotklo.
- Počet uložených překladů v Úložišti teď zahrnuje i novely — dřív ukazoval míň, než kolik toho appka doopravdy držela.

## v0.8.0

### Upozornění při aktualizaci
- Uložené překlady se po instalaci zahodí a spočítají znovu (změnil se překladový řetězec). První otevření kapitoly bude pomalejší a sáhne to na denní limit překladů.

### Opravené chyby
- **Sdílení QR kódu nedělalo vůbec nic.** Tlačítko „Sdílet" pokaždé selhalo a mlčelo o tom, protože v manifestu chyběl FileProvider.
- **Reset hesla hlásil selhání jako úspěch.** Když se e-mail nepodařilo odeslat, vyskočila hláška „Chyba: Email pro reset odeslán". Teď se ukáže skutečná příčina.
- **Časovač spánku držel v paměti celou obrazovku appky** po celou dobu odpočtu a po otočení displeje přestal fungovat.
- **Obrazovka Statistik byla nedosažitelná** — 575 řádků hotového kódu, na který nevedla žádná cesta. Nově se otevírá klepnutím na statistický řádek v knihovně.
- **Tlačítko zrušení časovače spánku** nereagovalo na to, že časovač mezitím běží.
- **Mrtvý nebo blokující zdroj** už nevypadá, jako by prostě nic nenašel.
- Doplněno 51 chybějících překladů — anglické rozhraní na několika místech ukazovalo češtinu.
- Opraven únik síťového spojení při neúspěšném přihlášení ke Kitsu a MangaUpdates.

### Data a soukromí
- **Obnova ze zálohy teď běží celá najednou.** Dřív se zapisovala po částech, takže chyba uprostřed nechala knihovnu rozečtenou. Nově se obnoví buď všechno, nebo nic.
- **Záloha z novější verze appky se odmítne** místo toho, aby se naslepo naparsovala jako ta současná.
- **Inkognito režim už nezapisuje nic.** Dřív vynechal jen historii a hlášení trackerům, ale kapitolu stejně označil jako přečtenou, posunul „naposledy čteno" a započítal čas i stránky do Statistik.

### Vzhled a texty
- **Počty se konečně skloňují.** Místo „1 kapitol", „3 kapitol" nebo „Přidat 1 mang do kategorie" appka používá správné tvary ve všech čtyřech jazycích.

### Pod kapotou
- **Release build šel po dlouhé době znovu sestavit** — kvůli chybě v R8 padal každý pokus a vydávalo se ve skutečnosti ladicí APK. Tohle je první pořádně minifikované vydání: **51,7 MB místo 68,3 MB**.
- Zapnuto hlášení chyb — zachycené výjimky už nemizí beze stopy.
- Knihovna pro šifrované úložiště tracker tokenů povýšena z alpha na stabilní verzi.
- Přibyly první testy ViewModelů; celkem jich projekt má 578.

<!--
Verze v0.3.4 az v0.7.9 se v dobe vydani do CHANGELOGu nezapisovaly. Sekce nize jsou
ZPETNE SESTAVENE z predmetu commitu mezi prislusnymi tagy (2026-08-02) - jsou tedy
strucnejsi a syrovejsi nez rucne psane zaznamy vys, protoze nic jineho k dispozici
neni. Cistky verze (chore/Release/ci/merge) jsou vynechane.
-->

## v0.7.9 (2026-07-31)

- fix: prekladovy prompt varuje pred doslovnymi idiomy a spatnym zvratnym slovesem
- fix: dlaždicovaný/rozházený vodoznak napříč stránkou už není jako text
- fix: tenky vodoznak mezi pulkami repliky rozdelil bublinu na dve

## v0.7.8 (2026-07-31)

- feat: preklad zkusi nejdriv velikost pisma puvodniho originalu, ne rovnou maximum
- fix: model si pod velkou davkou spletl cislovani "id" a preklad skoncil u jine bubliny
- fix: kaskadova bublina s posunutym druhym radkem ztracela pulku textu

## v0.7.7 (2026-07-31)

- fix: preklad se tvaril jako hotovy i kdyz se nic neprelozilo

## v0.7.6 (2026-07-31)

- fix: jedna zasekla stranka zamrazila ukazatel prekladu na 0/N pro celou kapitolu

## v0.7.5 (2026-07-31)

- fix: appka pri prekladu tvrde padala - Coil hardwarova bitmapa + pixel access

## v0.7.4 (2026-07-31)

- fix: detekce tvaru bubliny zerala stovky MB pameti - appka pri prekladu tise umirala

## v0.7.3 (2026-07-31)

- fix: chybejici preklad uz se nevykresli jako anglicky original + oprava sazby do sirsiho obrysu

## v0.7.2 (2026-07-31)

- fix: preklad kapitoly uz nemarni cas na providerovi, ktery odmita obsluhu

## v0.7.1 (2026-07-28)

- feat: vyvazena sazba textu do tvaru bubliny (kosoctvercovy blok jako profesionalni lettering)

## v0.7.0 (2026-07-27)

- fix: skutecna pricina rozbiteho textu v bublinach - lamani slov po pismenech + prepis sazby

## v0.6.2 (2026-07-27)

- fix: 4 dalsi chyby prekladu bublin (skvrna z watermarku, useknuty text, extremni velikosti)

## v0.6.1 (2026-07-27)

- fix: 3 chyby prekladu bublin nahlasene uzivatelem (mizejici bublina, slita placka, useknuta slova)
- chore: odebran CLAUDE.md ze sledovani gitem

## v0.6.0 (2026-07-27)

- docs: anglicka verze README + poznamka o cestine jako hlavnim jazyce prekladu
- fix: citelne jmeno stazenych kapitol pro export na PC + pad stahovani na Androidu 14
- fix: zvednuty svevolny denni limit prekladove proxy (nasazeno)
- fix: rate limit u jednoho providera uz nezastavi cely prekladovy retezec
- feat: 5 vylepseni kvality prekladu (poradi bublin, mene komprese, kontext, ucici se glosar, shape-aware SHOUT)
- fix: 3 problemy prekladu bublin (UNTRANSLATED leak, shape-aware fit, mene agresivni placka)
- revert: vraceny 3 sloupce v mrizce zdroju na Prochazet
- redesign: cistsi karta zdroje na Prochazet (2 sloupce, ikona vedle nazvu)
- feat: karusel oblibenych zdroju na obrazovce Prochazet
- feat: rozsireny info blok na detailu mangy (Origination/Demographic/Published) + sipka u popisu
- fix: overeno a zamitnuto navraceni manhuafast/manhuaus (uzivatelska korekce)
- fix: odstraneno mangafire - Cloudflare Turnstile token, ne jen chybejici auth
- docs: zdokumentovano ctvrte kolo auditu zdroju (fix vs. remove)
- fix: odstraneno 17 zdroju s nereseitelnou ochranou/strukturou (ctvrte kolo)
- fix: tri dalsi zdroje ze tretiho kola auditu (flamecomics, scanvf, wuxiabox)
- fix: NovelFire selektor + Japscan zastarala domena (6f)
- fix: MadaraSource NOVEL zdroje vracely 0 stranek kapitoly (6e)
- fix: EvilManga archivni URL + zdokumentovano 3. kolo Cloudflare testu (6c)
- fix: opraveno 7/10 zdroju s chybejicimi hotlink referery (6d)
- fix: oprava 5 Madara zdroju se zmenenou archivni URL + druhe kolo auditu
- Fix: odstraneno Bato.to - potvrzeno nefunkcni i v realne appce
- Fix: odstraneno ComicK - funguje uz jen jako tracker, ne zdroj obrazku
- Feat: obecny report v Nastaveni, hromadny prepinac adult zdroju + dokonceni auditu zdroju

## v0.5.0 (2026-07-26)

- Feat: menu na kartě zdroje (oblíbené/report) + pripevnena hlavicka Prochazet
- Feat: chunking fix pro novely + prednacitani prekladu dalsi kapitoly
- Feat: gradientova vyplin bublin + entrance animace
- Refactor: OcrEngine bez OkHttpClient, bitmapy přes novy PageBitmapLoader
- Feat: tap-to-flip bublin (originál/preklad) + obrysovy text pro citelnost
- Refactor: rozdeleni ReaderScreen.kt do fokusovanych souboru
- Perf: dávkový překlad kapitoly místo volání API po jedné stránce
- Feat: OpenRouter (Gemma) jako záložní překladač + skloňování jmen v promptu

## v0.4.2 (2026-07-26)

- fix: audit a oprava rozbitych manga/manhwa/manhua zdroju

## v0.4.1 (2026-07-25)

- (jen zvednuti verze)

## v0.4.0 (2026-07-25)

- (jen zvednuti verze)

## v0.3.9 (2026-07-25)

- (jen zvednuti verze)

## v0.3.8 (2026-07-25)

- Fix: CloudflareInterceptor - overit finalni pokus + presnejsi detekce vyzvy
- Feat: pridat zdroj ManhuaUS (manhuaus.com)
- Fix: DemonicScans cerna stranka - rozseknuti obrich obrazku na kousky
- Feat: Groq muze prekladat stejnym "ultra" promptem jako Gemini (komprese/deleni)
- Fix: Gemini rate limit padne na Groq misto oznaceni [UNTRANSLATED]

## v0.3.7 (2026-07-24)

- Feat: box pro preklad kopiruje skutecny tvar bubliny (BubbleClipShape)
- Feat: font podle typu bubliny (tucne na krik, kurziva na myslenku/sepot)
- Feat: layoutTranslationBlocks pouzije presny tvar bubliny misto heuristiky, kdyz je k dispozici
- Feat: propagace tvaru bubliny do TranslatedBlock, cache + migrace starych zaznamu
- Feat: napojit BubbleShapeDetector do OcrEngine.recognize()
- Feat: BubbleShapeDetector - flood-fill detekce tvaru bubliny (cisty JVM algoritmus)
- Docs: fix - pouzit .clip() misto jen .background(color, shape), aby se ostrihl i obsah
- Docs: implementacni plan pro detekci tvaru bubliny a font podle stylu
- Docs: spec pro detekci tvaru bubliny a font podle stylu

## v0.3.6 (2026-07-24)

- (jen zvednuti verze)

## v0.3.5 (2026-07-24)

- (jen zvednuti verze)

## v0.3.4 (2026-07-20)

- Feat: redesign zdrojů (reálná loga, barevné karty) + spolehlivější překlad

## v0.3.3

### Nové funkce
- Stahování aktualizace teď zobrazuje celoobrazovkovou animaci - stylizovaný "skleněný květ" se otevírá a fialové srdce uprostřed sílí podle skutečného procenta stažení, místo prostého progress baru v Nastavení. Lze schovat tlačítkem X (stahování běží dál na pozadí).

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
