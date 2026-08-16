package com.haise.jiyu.translate

import org.json.JSONArray
import org.json.JSONObject

/**
 * Staví system+user prompt pro Gemini překlad manga bublin do češtiny a
 * parsuje strukturovanou JSON odpověď zpět do [GeminiTranslationResponse].
 *
 * Prompt (ne server-side proxy) je tu, kde žijí všechna pravidla komprese/glosáře/formátu,
 * protože [GeminiTranslateClient] posílá hotový text na tenký, obecný proxy endpoint
 * (Supabase Edge Function jen vkládá tajný API klíč a přeposílá) - viz komentář v
 * [GeminiTranslateClient] proč se klíč nesmí posílat z appky přímo.
 */
object GeminiUltraPrompt {

    /**
     * Free tier model na Google AI Studio - rychlý a dost kvalitní na literární kompresi.
     * "-latest" alias místo pevné verze (např. "gemini-2.5-flash") záměrně - konkrétní
     * verzované modely Google postupně vyřazuje z free tieru pro nové klíče (ověřeno
     * 2026-07-24: "gemini-2.5-flash" vracelo 404 "no longer available to new users",
     * zatímco "gemini-2.0-flash"/"gemini-2.5-pro" mají na free tieru nulovou kvótu -
     * 429 RESOURCE_EXHAUSTED). Alias Google průběžně přesměruje na aktuální podporovaný
     * model, takže appka nemusí čekat na ruční update při každé rotaci modelů.
     */
    const val MODEL = "gemini-flash-latest"

    /**
     * Sentinel, který model vrátí místo hádaného překladu, když OCR text nedává smysl
     * (viz prompt níže, sekce "CHYBY"). [TranslateRepository] tuhle hodnotu zachytává a
     * bublinu vůbec nevykresluje (necháme prosvítat originál) - bez tyhle kontroly by se
     * doslovný "[UNTRANSLATED]" vykreslil čtenáři přímo do bubliny jako by to byl překlad.
     */
    const val UNTRANSLATED_MARKER = "[UNTRANSLATED]"

    /**
     * Pravidla plynoucí z toho, ODKUD dílo pochází - podle `MangaEntity.contentType`.
     *
     * Typ díla se modelu posílal už dřív, ale jen jako nálepka v závorce ("Solo Leveling"
     * (manhwa)). Co z ní plyne, si musel domyslet sám, a to je zbytečné hádání: každá
     * z těch tradic má vlastní oslovení a vlastní přepis jmen. Korejské "hyung" a japonské
     * "senpai" nejsou zaměnitelné a model, který si myslí, že čte japonskou mangu, přeloží
     * korejské jméno podle japonského čtení.
     *
     * Text je schválně krátký. Jde do systémového promptu při KAŽDÉM požadavku, a limit
     * znaků je podle živých dat náš skutečný strop - dlouhé poučování by se zaplatilo
     * ubranou kvótou na skutečný překlad.
     *
     * Neznámý nebo prázdný typ vrací prázdný řetězec: radši žádné pravidlo než špatné.
     * Starý záznam v databázi ho mít nemusí a odhadovat "asi manga" by u manhwy uškodilo.
     */
    fun mediumRules(contentType: String): String = when (contentType.trim().uppercase()) {
        "MANGA" ->
            "Japonské dílo. Honorifika (-san, -kun, -chan, -senpai, -sensei) v češtině vypusť, " +
                "pokud nenesou vztah, který jinak zmizí - pak ho vyjádři česky (sensei -> učiteli). " +
                "Jména přepisuj podle japonské výslovnosti."
        "MANHWA" ->
            "Korejské dílo, NE japonské. Oslovení hyung/noona/oppa/unnie/sunbae jsou korejská - " +
                "přelož je vztahem (bratře, starší), ne japonskými protějšky. Jména přepisuj podle " +
                "korejské výslovnosti."
        "MANHUA" ->
            "Čínské dílo. Oslovení shixiong/shimei/shizun a pojmy kultivace (qi, dao, sekta, " +
                "říše) drž konzistentně. Jména přepisuj podle čínské výslovnosti."
        "COMIC" ->
            "Západní komiks. Žádná honorifika ani asijská oslovení - kdyby se v textu objevila, " +
                "je to chyba OCR."
        "NOVEL" ->
            "Próza, ne bubliny. Limity délky ber jen jako orientační - souvislost vět a plynulost " +
                "odstavce mají přednost před stručností."
        else -> ""
    }

    /**
     * Tón podle demografického cílení díla (shónen/šódžo/seinen/džosei), pokud ho appka
     * zná ze žánrových štítků. Na rozdíl od [mediumRules] (odkud dílo POCHÁZÍ - japonské/
     * korejské/čínské) tohle říká, PRO KOHO je psané - ovlivňuje formálnost a přímočarost
     * dialogu nezávisle na původu.
     *
     * Funguje jen u zdrojů, které demografii dávají přímo mezi žánrové štítky (běžné u
     * generických Madara webů) - MangaDex ji má v odděleném poli API
     * (`attributes.publicationDemographic`), které appka zatím nestahuje do `genres`, takže
     * tam se pravidlo neuplatní. Prázdný řetězec je bezpečný no-op, ne chyba.
     */
    internal fun demographicToneRule(genres: List<String>): String {
        val normalized = genres.map { it.trim().lowercase() }
        return when {
            normalized.any { "shounen" in it || "shonen" in it } ->
                "Cílovka shónen: akční, přímočará mluva, krátké průbojné repliky."
            normalized.any { "shoujo" in it || "shojo" in it } ->
                "Cílovka šódžo: emocionální, jemnější odstíny citu, méně drsný slovník."
            normalized.any { "seinen" in it } ->
                "Cílovka seinen: komplexnější témata, formálnější/dospělejší jazyk, kde to sedí."
            normalized.any { "josei" in it } ->
                "Cílovka džosei: realistický, dospělý tón, méně dětinský slovník."
            else -> ""
        }
    }

    /**
     * Složí blok "KONTEXT DÍLA" z toho, co appka o díle ví.
     *
     * Je to čistá funkce a ne pár řádků přímo v [TranslateRepository] schválně: bez ní by
     * nešlo otestovat, že se [mediumRules] do kontextu opravdu DOSTANOU. Samotná pravidla
     * se testují snadno, ale jejich zapojení by šlo smazat a žádný test by si toho nevšiml.
     */
    fun buildMangaContext(title: String, contentType: String, genres: List<String>): String = buildString {
        append("Název: \"$title\" (${contentType.lowercase()})")
        if (genres.isNotEmpty()) append(", žánry: ${genres.joinToString(", ")}")
        mediumRules(contentType).takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it)
        }
        demographicToneRule(genres).takeIf { it.isNotBlank() }?.let {
            append("\n")
            append(it)
        }
    }

    fun buildSystemPrompt(glossary: Map<String, String>, mangaContext: String = ""): String {
        val glossaryBlock = if (glossary.isEmpty()) {
            "(žádné zatím uložené pojmy pro tuhle mangu)"
        } else {
            glossary.entries.joinToString("\n") { (source, target) -> "- \"$source\" -> \"$target\"" }
        }
        val contextBlock = mangaContext.ifBlank { "(neznámé - žádný dodatečný kontext k dispozici)" }

        return """
            Jsi profesionální překladatel manga/manhwa/manhua bublin do češtiny. Překládáš pro
            čtenáře komiksu, ne pro titulky filmu - text musí znít přirozeně a vejít se do
            bubliny, ale PŘESNOST A ZACHOVÁNÍ SMYSLU MAJÍ VŽDY PŘEDNOST před zkracováním - render
            appky umí bublinu i písmo zvětšit, takže není nutné obětovat nuanci věty jen kvůli
            co nejkratšímu překladu.

            === PĚT PRAVIDEL, KTERÁ PLATÍ NADE VŠÍM OSTATNÍM ===
            Tahle jsou důležitější než formát, délka i cokoliv dál v tomhle promptu:
            1. ZÁPOR SE NIKDY NESMÍ ZTRATIT ANI PŘIDAT. "don't", "not", "never", "no", "stop"
               obrací význam věty. Přeložená věta si navíc nesmí odporovat sama v sobě - když
               z ní vyjde "rozptýlete se, držte se pohromadě", je špatně.
            2. Překládej VÝZNAM, ne slovo po slovu.
            3. Idiom přelož českým protějškem, ne doslova.
            4. Nikdy nevkládej slovo, které v originále nemá oporu. Když si nejsi jistý,
               drž se doslovnějšího, ale SMYSLUPLNÉHO překladu.
            5. Zachovej tón a intenzitu mluvčího - hrubost, výhrůžku, strach.

            === KONTEXT DÍLA ===
            $contextBlock
            Zohledni tón/žánr při volbě slovní zásoby a formálnosti (temné fantasy vs. komedie
            vs. herní systémové okno apod.).

            === LIMITY VELIKOSTI BUBLINY ===
            Každá bublina má SIZE tag s ORIENTAČNÍM maximem znaků českého překladu - je to
            měkký strop pro přirozeně stručnou češtinu, ne důvod měnit nebo vynechávat význam:
            [TINY]    max ${SizeTag.TINY.maxChars} znaků   -> "Vítej." "Jasné." "Co se děje?"
            [SMALL]   max ${SizeTag.SMALL.maxChars} znaků  -> "Co tady děláš?" "Omlouvám se." "Ne, díky."
            [MEDIUM]  max ${SizeTag.MEDIUM.maxChars} znaků  -> "Zkusím všechna kouzla, co mám."
            [LARGE]   max ${SizeTag.LARGE.maxChars} znaků  -> "Magie tíže: Ovládá tíži libovolného objektu."
            [WIDE]    max ${SizeTag.WIDE.maxChars} znaků, 1-2 řádky
            [TALL]    max ${SizeTag.TALL.maxChars} znaků, 4-5 řádků
            [SFX]     NEPŘEKLÁDAT - tyhle bubliny se ti vůbec neposílají.
            Pokud se přesný, věrný překlad do limitu přesto nevejde, teprve pak ho zkrať - ale
            nikdy neztrácej informaci, která je pro pochopení scény důležitá.

            === VĚTY PŘES VÍC BUBLIN (nejdřív pochop, pak teprve překládej) ===
            Nepřekládej bubliny jako izolované útržky. Nejdřív si projdi celou dávku a zjisti, které
            bubliny patří do JEDNÉ věty nebo jedné repliky, a teprve pak překládej - se znalostí celé
            scény.

            Bublina označená "POKRAČUJE Z: [BUBBLE n]" je druhou (nebo další) částí věty, která
            začala v bublině n. Přelož si je jako JEDEN celek a výsledek pak rozděl zpátky přesně tak,
            jak byl rozdělený originál - úvodní citoslovce nebo oslovní část zůstává v první
            bublině, zbytek věty ve druhé.

            ŽELEZNÁ PRAVIDLA, která nesmíš porušit:
            - Text se NIKDY nepřesouvá mezi bublinami. Co bylo v horní, zůstane v horní; co bylo ve
              spodní, zůstane ve spodní.
            - Žádnou bublinu nenechávej prázdnou proto, že ses rozhodl celou větu vměstnat do té druhé.
            - Bubliny nesluč do jedné ani nerozděluj na víc. Počet i pořadí musí sedět na vstup.
            - Každá část musí dávat smysl na svém místě a plynule navazovat na sousední, jako když
              repliku sází lettering v originálním vydání.
            Pokud pokračování navazuje uprostřed věty, nezačínej ho velkým písmenem a nedoplňuj podmět,
            který v originále není - má to znít jako plynulé pokračování téže věty.

            === PŘIROZENÁ ČEŠTINA (ne umělé zkracování) ===
            - Piš, jak by to skutečně řekl český mluvčí - přirozená stručnost, ne mrzačení věty:
              "Co se děje?" -> "Co je?" (obojí přirozené, druhé jen běžnější v hovorové řeči)
            - Neformální tykání, nikdy vykání
            - Příklady přirozeného zkrácení (pořád zachovávají smysl):
              "promiň mi to" -> "promiň" | "jsem si jistý" -> "jsem si jist"
              "podívej se na to" -> "podívej" | "všechno je v pořádku" -> "vše OK"
              "počkej chvíli" -> "počkej" | "kam jdeš?" -> "kam?"

            === IDIOMY A USTÁLENÉ VÝRAZY (překládej SMYSL, ne slovo od slova) ===
            Anglické idiomy a ustálené obraty přelož podle toho, co VYJADŘUJÍ, ne doslovným
            převodem jednotlivých slov - doslovný převod často zní v češtině cize nebo vynechá
            důraz, který idiom nese:
              "to get lost after coming all this way" (idiom zdůrazňuje DÉLKU/NÁMAHU cesty, ne
              jen že se vydali na cestu) -> špatně: "ztratit se po tom, co jsme se sem vydali"
              (ztrácí ten důraz) -> správně: "ztratit se po tak dlouhé cestě" / "ztratit se po
              tom, co jsme ušli takovou dálku"
              "out of the blue" -> "z ničeho nic" (NE "z modra")
              "break a leg" -> "hodně štěstí" (NE doslovně "zlom si nohu", pokud kontext není
              doslovný požadavek na zlomeninu)

            === ZVRATNÁ SLOVESA (přidávej "se" jen tam, kam gramaticky patří) ===
            Model má sklon skládat dohromady dva různé vzory a vytvořit negramatickou kombinaci -
            typicky sloveso, které "ztracení se" už vyjadřuje samo o sobě, PLUS zvratné "se" navíc:
              "zbloudit" už znamená "ztratit se/zabloudit z cesty" - NIKDY "zbloudit se" (to je
              negramatické, mísí dva vzory). Použij BUĎ "zbloudit" bez "se", NEBO zvratné
              "ztratit se" - nikdy obojí najednou.
            Než odešleš překlad, zkontroluj si každé zvratné sloveso, jestli "se"/"si" v dané
            větě gramaticky patří.

            === PŘÍKLADY (zdroj -> špatně/dlouze -> správně) ===
            "Welcome." [SMALL] -> "Vítejte." (8) -> "Vítej." (6)
            "What are you doing here?" [MEDIUM] -> "Co tady děláš?" (15) -> "Co děláš?" (10)
            "I'll try every magic I have instantly." [MEDIUM] -> "Zkusím všechna kouzla, co mám, okamžitě." (40) -> "Zkusím všechna kouzla." (22)
            "By the way, after being a sorceress..." [SMALL] -> "Mimochodem, poté, co byla čarodějnicí..." (39) -> "Mimochodem..." (13)
            "It's obvious." [SMALL] -> "Je to zřejmé." (13) -> "Jasné." (6)

            === DĚLENÍ SLOV (soft hyphen) ===
            Do pole "syllable_breaks" vlož STEJNÝ text jako "translated", ale s měkkým rozdělovníkem
            ­ VÝHRADNĚ na platných slabičných hranicích, aby renderer nikdy nezalomil slovo
            uprostřed slabiky:
              "gravitace" -> "gravi­tace" | "používám" -> "pou­ží­vám"
              "čarodějnice" -> "čaro­děj­nice" | "okamžitě" -> "oka­mži­tě"
            Pokud si nejsi jistý slabičnou hranicí, radši žádný ­ nevkládej (nezalomené slovo
            je lepší než špatně rozdělené).

            === JMÉNA, MÍSTA A NÁZVY (anglicky, ale skloňuj) ===
            Jména postav, měst, organizací a pojmenovaných technik/schopností NEPŘEKLÁDEJ do
            češtiny - použij zavedený anglický přepis (počítá se fanouškovský i oficiální anglický
            překlad), bez ohledu na to, z jakého jazyka překládáš (japonština, korejština, čínština,
            ruština...). Pokud pro název anglický ekvivalent neznáš, přepiš ho sám do angličtiny -
            nenechávej ho v původním písmu (kanji, hangul, azbuka...). Nevymýšlej český název a
            nepřekládej doslovný význam jména (město, jehož název v originále znamená "bouře",
            zůstává pod svým zavedeným anglickým jménem, ne "Bouřov"). Pokud text už obsahuje jméno
            zapsané latinkou, nech ho přesně tak, jak je.
            Tahle anglická jména ale SKLOŇUJ podle českých pádů, aby věta zněla přirozeně - pravopis
            jména zůstává anglický, mění se jen koncovka podle vzoru odpovídajícího rodu postavy:
              "Frodo" -> "Vidím Froda." (4. p.) / "Řekl Frodovi." (3. p.) / "Frodův meč." (přivl.)
              "Naruto" -> "Narutu"/"Narutovi"/"Narutem" | "Sakura" -> "Sakuru"/"Sakuře"/"Sakurou"
            Pokud by skloňování znělo krkolomně nebo nejednoznačně, oprav to opisem s předložkou
            ("k Frodovi") místo násilné koncovky - ale nevynechávej skloňování úplně, jméno pořád
            v 1. pádě uprostřed věty, kde gramaticky nepatří, zní v češtině nepřirozeně.

            === GLOSÁŘ POJMŮ ===
            Platí VÝHRADNĚ na jména postav, míst, organizací a pojmenovaných technik. Běžná
            slova překládej podle kontextu, i kdyby se v glosáři náhodou objevila - glosář se
            plní automaticky a může obsahovat omyl. Nikdy kvůli němu neobětuj smysl věty
            (pravidlo 1 a 4 nahoře platí i tady).
            $glossaryBlock

            === NOVÉ POJMY (učení glosáře) ===
            Kromě "bubbles" vrať i pole "new_terms" - vlastní jména (postavy, místa,
            organizace, pojmenované techniky/schopnosti) z TÉHLE dávky, která NEJSOU už
            uvedená v glosáři výše. Každá položka {"source": "<originál>", "target":
            "<tvůj český přepis v 1. pádě>"} - "target" vždy v ZÁKLADNÍM (1.) pádě, i když
            se jméno v textu objevilo skloňované, aby šlo použít jako budoucí glosářový
            záznam. Neopakuj termíny, které už glosář obsahuje. Žádná nová jména -> prázdné pole.

            === TYP BUBLINY ===
            SPEECH: normální neformální čeština. NARRATION: může být formálnější/delší.
            SHOUT: VELKÁ PÍSMENA, co nejkratší. THOUGHT: měkčí, introspektivní tón.
            WHISPER: přidej "(šeptem)" jen pokud se to vejde do limitu. SYSTEM: technický,
            přesný jazyk (herní/status okna).

            === HONORIFIKY ===
            Japonské přípony (-san, -kun, -chan) vynech, pokud nejsou pro děj klíčové.
            "Senpai" nech "Senpai". "Onii-chan" -> "Bráško" nebo nech.

            === VULGARISMY ===
            Odpovídej intenzitě originálu, necenzuruj, pokud není cenzurovaný i zdroj:
            "Damn"->"Sakra/Do háje" "Crap"->"Sračka/Do prdele" "Fuck"->"Do prdele/Kurva"
            "Bastard"->"Hajzl/Kretén" "Idiot"->"Idiot/Blbeček"

            === CHYBY ===
            "$UNTRANSLATED_MARKER" vracej JEN u textu, který se nedá PŘEČÍST - zkomolené OCR,
            náhodné znaky, zbytek vodoznaku. Nikdy nehádej význam nazdařbůh.
            NEVRACEJ ho proto, že je bublina krátká nebo věta nedokončená. Útržek ("...poslyš,"
            / "no," / "a pak") je plnohodnotná část repliky a překládá se jako útržek, i když
            sám o sobě celou větu nedává - typicky je to horní lalok kaskádové bubliny, jejíž
            zbytek stojí v bublině hned vedle (viz sekce VĚTY PŘES VÍC BUBLIN).

            === KONTROLA PŘED ODESLÁNÍM (projdi každou bublinu, než sestavíš JSON) ===
            - Má překlad stejný význam jako originál?
            - Nezměnil jsem kladnou větu na zápornou nebo naopak? (viz pravidlo 1)
            - Neodporuje si věta sama v sobě?
            - Nepřeložil jsem idiom doslova?
            - Nevložil jsem slovo, které v originále nemá oporu?
            - Zní ta věta jako skutečná česká mluva?
            Když najdeš chybu, oprav ji ještě před sestavením odpovědi.

            === VÝSTUPNÍ FORMÁT (POUZE JSON, žádný text mimo JSON, žádné markdown bloky) ===
            {
              "bubbles": [
                {
                  "id": 0,
                  "original": "Welcome.",
                  "translated": "Vítej.",
                  "bubble_size_tag": "SMALL",
                  "is_sfx": false,
                  "syllable_breaks": "Vítej.",
                  "notes": ""
                }
              ],
              "new_terms": [
                {"source": "Frodo", "target": "Frodo"},
                {"source": "Gravity Magic", "target": "Magie tíže"}
              ]
            }
            Vrať přesně jednu položku "bubbles" pro každou bublinu z requestu, ve stejném
            pořadí "id". "new_terms" vrať vždy (klidně jako prázdné pole [], nikdy nechybí).
        """.trimIndent()
    }

    /**
     * Ocásek už přeložených replik, který se přibalí k další dávce, aby na sebe překlad
     * navazoval i PŘES hranici dávky.
     *
     * Uvnitř jedné dávky kontext existoval odjakživa - jde do jednoho požadavku celá dávka
     * v pořadí čtení. Mezi dávkami ale nebyl žádný: kapitola se posílá po kusech (viz
     * [TranslateRepository.chunkPages]) a každý kus začínal s čistým stolem, takže se
     * v půlce rozhovoru mohlo přehodit tykání/vykání nebo oslovení postavy.
     *
     * Bere se od KONCE a jsou to jen výsledné české repliky, ne dvojice s originálem -
     * posílat obojí by cenu zdvojnásobilo a na navázání tónu stačí to, co "zaznělo".
     * Konzistenci jmen řeší glosář, ne tohle.
     *
     * Rozpočet je dvojí (počet řádků i znaků) schválně: samotný počet řádků neochrání před
     * několika dlouhými monology za sebou, a znakový limit je náš skutečný strop.
     */
    fun recentContextLines(
        previous: List<String>,
        maxLines: Int = RECENT_CONTEXT_MAX_LINES,
        maxChars: Int = RECENT_CONTEXT_MAX_CHARS,
    ): List<String> {
        val result = ArrayDeque<String>()
        var chars = 0
        for (line in previous.asReversed()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (result.size >= maxLines || chars + trimmed.length > maxChars) break
            result.addFirst(trimmed)
            chars += trimmed.length
        }
        return result.toList()
    }

    fun buildUserPrompt(bubbles: List<ClassifiedBubble>, previousLines: List<String> = emptyList()): String {
        // Návaznost se počítá v kódu (geometrie + interpunkce, viz [detectContinuations]) a modelu
        // se předává jako fakt. Sám by ji z pořadí odvodit nemohl: v jedné dávce jde i několik
        // stránek najednou, takže sousední položky spolu vůbec nemusí souviset.
        val continuations = detectContinuations(bubbles)
        val sb = StringBuilder("Přelož tyto manga bubliny do češtiny.\n")
        if (previousLines.isNotEmpty()) {
            // Varování "nepřekládej znovu" tu není pro parádu: bez něj model tyhle repliky
            // bere jako součást zadání a vrátí je v odpovědi, což rozhodí párování podle id.
            sb.append("\n=== CO UŽ ZAZNĚLO DŘÍV (jen kontext) ===\n")
            sb.append("Poslední repliky z týhle kapitoly, v pořadí. Navaž na ně tónem, tykáním/vykáním\n")
            sb.append("a oslovením postav. NEPŘEKLÁDEJ je znovu a nevracej je v odpovědi.\n")
            previousLines.forEach { sb.append("- \"${it.replace("\"", "'")}\"\n") }
        }
        sb.append("\n=== BUBLINY ===\n")
        bubbles.forEachIndexed { id, bubble ->
            sb.append("\n[BUBBLE $id]\n")
            if (id in continuations) {
                sb.append("POKRAČUJE Z: [BUBBLE ${id - 1}] (jedna věta rozdělená do dvou bublin)\n")
            }
            sb.append("SIZE: [${bubble.sizeTag.name}]\n")
            sb.append("TYPE: ${bubbleTypeToText(bubble.bubbleType)}\n")
            // Lettering deli slova na konci radku pomlckou; bez spojeni dorazi k modelu
            // rozsypany zacatek vety (`EVERY- ONE DON'T SCATTER...`). Viz [joinHyphenatedLineBreaks].
            val text = joinHyphenatedLineBreaks(bubble.raw.text).replace("\"", "'")
            sb.append("TEXT: \"$text\"\n")
        }
        return sb.toString()
    }

    private fun bubbleTypeToText(type: BubbleType) = when (type) {
        BubbleType.SPEECH -> "SPEECH"
        BubbleType.NARRATION -> "NARRATION"
        BubbleType.SHOUT -> "SHOUT"
        BubbleType.THOUGHT -> "THOUGHT"
        BubbleType.WHISPER -> "WHISPER"
        BubbleType.SYSTEM -> "SYSTEM"
        BubbleType.SFX -> "SFX"
    }

    /**
     * Vyloupne samotný JSON objekt z odpovědi modelu.
     *
     * Dřív se jen ořezávaly markdown fence přes `removePrefix`, což zvládlo jedinou podobu
     * odchylky: ```json na ÚPLNÉM začátku. Naměřeno na zařízení, že to nestačí - Groq vrátil
     * odpověď začínající prózou ("Překlady…") a appka celou dávku zahodila na JSONException,
     * včetně znaků, které za to volání upstream odečetl.
     *
     * Bere se proto od první `{` po poslední `}`. Zvládne to fence, úvodní omluvu i závěrečný
     * komentář za JSONem, a je to kratší než původní řetěz `removePrefix`/`removeSuffix`.
     *
     * Když v textu žádný objekt není, vrací se vstup nedotčený - ať chybová hláška z parsování
     * pořád ukazuje, co model doopravdy poslal, místo prázdného řetězce.
     *
     * Tohle je záchranná síť, ne řešení. Aby k tomu vůbec nedocházelo, dostávají všichni tři
     * provideři v proxy vynucený JSON režim (viz supabase/functions/translate-proxy).
     */
    internal fun extractJsonObject(rawText: String): String {
        val start = rawText.indexOf('{')
        val end = rawText.lastIndexOf('}')
        if (start < 0 || end <= start) return rawText.trim()
        return rawText.substring(start, end + 1)
    }

    fun parseResponse(rawText: String): GeminiTranslationResponse {
        val root = JSONObject(extractJsonObject(rawText))
        val arr: JSONArray = root.getJSONArray("bubbles")
        val bubbles = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            GeminiBubbleTranslation(
                id = o.getInt("id"),
                original = o.optString("original", ""),
                translated = o.optString("translated", ""),
                bubbleSizeTag = o.optString("bubble_size_tag", ""),
                isSfx = o.optBoolean("is_sfx", false),
                syllableBreaks = o.optString("syllable_breaks", o.optString("translated", "")),
                notes = o.optString("notes", ""),
            )
        }

        // Chybí u starších/degradovaných odpovědí (model instrukci nedodrží, nebo jde o
        // fallback cestu) - prázdný seznam, ne pád parsování.
        val newTermsArr = root.optJSONArray("new_terms")
        val newTerms = if (newTermsArr != null) {
            (0 until newTermsArr.length()).mapNotNull { i ->
                val o = newTermsArr.optJSONObject(i) ?: return@mapNotNull null
                val source = o.optString("source", "").trim()
                val target = o.optString("target", "").trim()
                if (source.isBlank() || target.isBlank()) null else GlossarySuggestion(source, target)
            }
        } else {
            emptyList()
        }

        return GeminiTranslationResponse(bubbles, newTerms)
    }

    /**
     * Rozpočet na kontextový ocásek (viz [recentContextLines]). Šest replik zhruba odpovídá
     * jedné výměně mezi dvěma postavami; 350 znaků je proti dávce (viz
     * [TranslateRepository] CHUNK_CHAR_LIMIT) přirážka v řádu procent.
     */
    private const val RECENT_CONTEXT_MAX_LINES = 6
    private const val RECENT_CONTEXT_MAX_CHARS = 350
}
