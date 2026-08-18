import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "jsr:@supabase/supabase-js@2";

const GROQ_API_KEY = Deno.env.get("GROQ_API_KEY") ?? "";
const GEMINI_API_KEY = Deno.env.get("GEMINI_API_KEY") ?? "";
const OPENROUTER_API_KEY = Deno.env.get("OPENROUTER_API_KEY") ?? "";
// Cerebras a Mistral - dva další nezávislé free-tier provideři přidaní kvůli kapacitě
// (Groq po vyřazení llama-3.3-70b-versatile zbyl jen na gpt-oss-120b s malým denním
// rozpočtem, viz PR #13). Cerebras servíruje TENTÝŽ gpt-oss-120b, ale s ~5x větším
// free-tier stropem (1M tokenů/den vs. Groqových ~200K) - stejná kvalita, víc kapacity.
const CEREBRAS_API_KEY = Deno.env.get("CEREBRAS_API_KEY") ?? "";
const MISTRAL_API_KEY = Deno.env.get("MISTRAL_API_KEY") ?? "";
const SUPABASE_URL = Deno.env.get("SUPABASE_URL")!;
const SERVICE_ROLE_KEY = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;

// Svévolná bezpečnostní pojistka proti runaway smyčce/zneužití, NE skutečný limit od
// Gemini/Groq/OpenRouteru - ty mají vlastní free-tier limity a samy odmítnou požadavek
// zdarma, appka na to nikdy nic neplatí. Zvednuto 2026-07-27 poté, co reálné dní (24. a
// 25. 7.) narazily na původních 500 000 znaků (508k/632k) - počet requestů (131/205) byl
// přitom hluboko pod tehdejším limitem 5000, takže znakový limit byl ten skutečný strop.
//
// POZOR NA VÝZNAM OBOU ČÍSEL - od 2026-08-02 každé měří něco jiného:
//
// DAILY_REQUEST_LIMIT počítá POKUSY, ne úspěšné překlady. Dávka, která projde až na čtvrtý
// krok fallback řetězce, se započítá čtyřikrát, a je to tak správně: tohle číslo je pojistka
// proti rozjeté smyčce a rozjetá smyčka se skládá právě z neúspěšných pokusů.
//
// DAILY_CHAR_LIMIT naopak od té doby odhaduje SKUTEČNĚ zpracovaný objem. Znaky se pořád
// strhávají PŘED voláním upstreamu (jinak by strop nešlo vynutit atomicky), ale když upstream
// požadavek odmítne (HTTP != 2xx), vrátí se zpátky - viz [refundQuota] a [shouldRefund].
// Znaky se NEVRACEJÍ, když upstream odpoví 200 bez použitelného textu; model v tom případě
// běžel a tokeny spotřeboval.
const DAILY_CHAR_LIMIT = 3_000_000;
const DAILY_REQUEST_LIMIT = 20_000;

const OPENROUTER_MODEL = "google/gemma-4-26b-a4b-it:free";

const NAME_HANDLING_INSTRUCTION =
  "For character names, place names, organizations, and named skills/techniques, use " +
  "the name commonly used in English translations of this work - both fan translations " +
  "and official English releases count as valid sources - regardless of what language " +
  "you are translating from or into. If no established English name is known for a " +
  "particular term, render it into English yourself rather than leaving it in the " +
  "original script or inventing a name in the target language. When an established " +
  "English name IS known, do not invent a different transliteration for it, do not " +
  "translate its literal meaning into the target language (a city whose name means " +
  '"storm" in the original language should stay as its established English name, not ' +
  "become the target-language word for storm), and do not substitute an alternate " +
  "localized name used in other language editions - official translations into other " +
  "languages sometimes rename things in ways that don't match what fans use, ignore " +
  "those. If the source text already contains the name written in Latin letters, keep " +
  "it exactly as written. Translate the same recurring name or term the same way every " +
  "time. ";

const CZECH_DECLENSION_INSTRUCTION =
  "Since the target language is Czech, decline every English-spelled name (character " +
  "names, place names, organizations) according to Czech grammatical case so the sentence " +
  "reads naturally - keep the Latin/English spelling of the name itself, but change its " +
  'ending to match the grammatical case, the way Czech naturally declines foreign proper ' +
  'nouns (example: "Frodo" becomes "Froda" in genitive/accusative, "Frodovi" in dative, ' +
  '"Frodův" as a possessive; "Naruto" becomes "Narutovi"/"Narutem"; "Sakura" becomes ' +
  '"Sakuru"/"Sakuře"/"Sakurou"), following Czech masculine/feminine noun patterns based on ' +
  "the character's gender. If declining a name would sound forced or ambiguous, rephrase " +
  "using a preposition instead of forcing an awkward ending - but do not skip declension " +
  "altogether, since leaving every name in the nominative case regardless of its role in " +
  "the sentence reads unnatural in Czech. ";

const HONORIFICS_AND_TONE_INSTRUCTION =
  "Drop Japanese/Korean/Chinese honorific suffixes (-san, -kun, -chan, -senpai, -sama, etc.) " +
  "unless they matter to the plot or characterization - render the relationship or respect " +
  "they imply naturally in the target language instead of leaving them attached by default, " +
  'except for a few terms fan translations conventionally keep as-is (e.g. "senpai", "-sama" ' +
  "in a reverent context) where dropping them would feel wrong to readers used to that " +
  "convention. Match the intensity and register of the original dialogue - if the source is " +
  "crude, vulgar, or profane, translate it with equivalent intensity instead of softening or " +
  "censoring language that isn't censored in the source. ";

const MANGA_BREVITY_INSTRUCTION =
  "Keep dialogue natural and concise the way people actually speak, the way it would appear " +
  "in a comic speech bubble - avoid overly formal, literal, or wordy phrasing that would feel " +
  "stiff or unnatural spoken aloud. ";

/**
 * Blok, ktery rekne modelu, CO vlastne preklada a co uz zaznelo.
 *
 * NALEZ: zalozni cesta (tahle) posilala jen holy seznam vet - zadny nazev dila, zadny typ
 * (manga/manhwa/novela), zadna navaznost na predchozi bubliny - prestoze appka obojí zna a
 * Gemini ceste to posila. Jakmile Gemini vypadl na kvote, kvalita spadla na doslovny preklad
 * izolovanych vet ("JUST LEAVE ME HERE." -> "ZUSTANTE ME TADY").
 *
 * Kontext jde do SYSTEMOVEHO promptu, ne do uzivatelske zpravy: ta musi zustat cistym JSON
 * polem, jinak model snadno zamicha poradi nebo prida do odpovedi neco navic.
 */
function contextClauseFor(context: string, recent: string[]): string {
  let clause = "";
  if (context) {
    clause += "\n\nYou are translating this work: " + context +
      ". Let its medium, genre and tone guide word choice and register.";
  }
  if (recent.length > 0) {
    clause += "\n\nFor continuity only, these lines were said just before this batch " +
      "(already translated - do NOT translate or repeat them, they are not part of the " +
      "input):\n" + recent.map((line) => "- " + line).join("\n");
  }
  return clause;
}

function systemPromptFor(mode: string, fromClause: string, target: string): string {
  const nameHandling = target.trim().toLowerCase() === "czech"
    ? NAME_HANDLING_INSTRUCTION + CZECH_DECLENSION_INSTRUCTION
    : NAME_HANDLING_INSTRUCTION;

  if (mode === "novel") {
    return (
      "You are a professional literary translator specializing in light novels. " +
      `Given a JSON array of paragraphs from a light novel chapter, return a JSON array of ${fromClause}${target} ` +
      "translations in exactly the same order, preserving tone, dialogue formatting and paragraph " +
      "structure. Translate idioms, jokes and cultural references naturally so the prose reads " +
      `fluently in ${target}, not word-for-word. ` +
      nameHandling +
      HONORIFICS_AND_TONE_INSTRUCTION +
      "Return ONLY the JSON array, no explanations, no markdown."
    );
  }
  return (
    "You are an experienced manga translator. Given a JSON array of manga text strings, " +
    `return a JSON array of ${fromClause}${target} translations in exactly the same order. ` +
    "Translate jokes, wordplay, slang, and cultural references naturally and idiomatically " +
    `so the result reads fluently in ${target}, rather than translating word-for-word. ` +
    nameHandling +
    HONORIFICS_AND_TONE_INSTRUCTION +
    MANGA_BREVITY_INSTRUCTION +
    "Return ONLY the JSON array, no explanations, no markdown."
  );
}

const CORS_HEADERS: Record<string, string> = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

function json(body: unknown, status: number): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...CORS_HEADERS },
  });
}

/**
 * Kód důvodu selhání upstreamu, který appka dostane v poli "error" (viz [upstreamErrorCode]).
 *
 * Do verze 13 se KAŽDÉ selhání upstreamu (včetně 429 od Googlu) vracelo jako HTTP 200 s
 * prázdným textem. Appka to nemohla odlišit od "síť škytla", takže na natvrdo vyčerpanou
 * denní kvótu pálila tři pokusy s exponenciálním čekáním - a to na každém z pěti providerů
 * v řetězci, u KAŽDÉ dávky kapitoly. Odtud pocházelo hlášené zpomalení (54stránková kapitola
 * z ~2 minut na 15+ minut, viz uživatelská zpětná vazba "22/54 po 15 minutách").
 *
 * Status zůstává 200 schválně - HTTP 429 je vyhrazené VÝHRADNĚ pro vlastní denní kvótu proxy
 * (ta znamená "skonči úplně", ne "zkus jiného providera") a starší verze appky, které pole
 * "error" neznají, se tak chovají přesně jako dřív.
 *
 * - "upstream_rate_limited": upstream vyčerpal kvótu (429/402) - providera má smysl na chvíli
 *   úplně vynechat, další dávka na něj nemá ztrácet čas.
 * - "upstream_error": jiná chyba upstreamu (5xx, deprekovaný model, neplatný klíč) - taky
 *   důvod providera dočasně vynechat, ale příčina může být krátkodobá.
 * - "upstream_empty": upstream odpověděl v pořádku, ale bez použitelného textu (safety filtr,
 *   dojely output tokeny). Tohle je vlastnost KONKRÉTNÍ dávky, ne providera - opakovat stejný
 *   požadavek nemá smysl, ale providera samotného vyřazovat nechceme.
 */
type UpstreamErrorCode =
  | "upstream_rate_limited"
  | "upstream_error"
  | "upstream_empty";

/** Jen pro stavové kódy - "upstream_empty" se nastavuje ručně tam, kde přišla prázdná odpověď. */
function upstreamErrorCode(status: number): Exclude<UpstreamErrorCode, "upstream_empty"> {
  // 429 = rate limit, 402 = došel kredit (OpenRouter u free modelů vrací obojí),
  // 403 u Gemini běžně znamená "API klíč nemá na tenhle model nárok / kvóta projektu".
  return status === 429 || status === 402 || status === 403
    ? "upstream_rate_limited"
    : "upstream_error";
}

/**
 * Sekundy, po kterých má smysl providera zkusit znovu - přímo z jeho standardní
 * Retry-After hlavičky, místo slepého odhadu appky (viz ProviderHealth.kt). Groq i
 * OpenRouter ji posílají na 429/503. undefined = provider ji nedal, appka spadne na
 * vlastní exponenciální odhad jako dřív.
 */
function retryDelaySecondsFromHeader(resp: Response): number | undefined {
  const header = resp.headers.get("retry-after");
  if (!header) return undefined;
  const seconds = Number(header);
  return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined;
}

/**
 * Gemini na rozdíl od Groq/OpenRouteru neposílá Retry-After hlavičku - navržené čekání
 * nese TĚLO 429 odpovědi (`error.details[].retryDelay`, např. "34s"). Krátká hodnota
 * znamená přechodný limit "na minutu", dlouhá (řádu hodin) skutečně vyčerpanou denní
 * kvótu - appka obojí teď umí rozlišit místo pevného 15minutového stropu.
 */
function retryDelaySecondsFromGeminiBody(bodyText: string): number | undefined {
  try {
    const data = JSON.parse(bodyText);
    const details = data?.error?.details;
    if (!Array.isArray(details)) return undefined;
    const retryInfo = details.find((d: Record<string, unknown>) =>
      typeof d["@type"] === "string" && d["@type"].includes("RetryInfo")
    );
    const delay = retryInfo?.retryDelay;
    if (typeof delay !== "string") return undefined;
    const seconds = Number(delay.replace(/s$/, ""));
    return Number.isFinite(seconds) && seconds > 0 ? seconds : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Denní strop. ZÁMĚRNĚ globální (jedna řádka na den za celý projekt), ne per-uživatel -
 * funkce běží s verify_jwt=false a appka je osobní, takže tu není koho identifikovat.
 *
 * Důsledek, o kterém je dobré vědět: kdo si z veřejného APK vytáhne URL + anon klíč,
 * může tenhle strop vyčerpat a tím odstavit překlad i skutečnému uživateli. U osobní
 * appky je to přijatelné; kdyby ji začal používat někdo další, tohle je první místo,
 * které je potřeba předělat - přidat identifikátor volajícího do RPC i do primárního
 * klíče `translate_usage` (viz supabase/schema.sql).
 *
 * Schéma tabulky i funkce jsou od 2026-07-31 verzované v supabase/schema.sql - dřív
 * existovaly jen v živém projektu a jejich ztráta by překlad tiše rozbila.
 */
async function checkQuota(charCount: number): Promise<{ allowed: boolean; errored: boolean }> {
  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);
  const { data: allowed, error: quotaError } = await supabase.rpc(
    "increment_translate_usage",
    {
      p_chars: charCount,
      p_daily_char_limit: DAILY_CHAR_LIMIT,
      p_daily_request_limit: DAILY_REQUEST_LIMIT,
    },
  );
  if (quotaError) {
    console.error("quota rpc failed", quotaError);
    return { allowed: false, errored: true };
  }
  return { allowed: Boolean(allowed), errored: false };
}

/**
 * Vrátí znaky do denního stropu za pokus, při kterém upstream vůbec nic nevygeneroval.
 *
 * Počet požadavků se ZÁMĚRNĚ nevrací - viz komentář u `refund_translate_usage` ve
 * supabase/schema.sql. Stručně: request_count je pojistka proti rozjeté smyčce a ta se
 * skládá právě z neúspěšných pokusů, takže vracet ho by pojistku vyřadilo.
 *
 * Selhání refundu se jen zaloguje. Kvóta pak zůstane stržená, což je bezpečný směr chyby
 * (napočítá se víc, nikdy míň), a rozhodně to není důvod zahodit už hotovou odpověď.
 */
async function refundQuota(charCount: number): Promise<void> {
  const supabase = createClient(SUPABASE_URL, SERVICE_ROLE_KEY);
  const { error } = await supabase.rpc("refund_translate_usage", { p_chars: charCount });
  if (error) console.error("refund rpc failed", error);
}

/**
 * Má se za tuhle chybu vracet kvóta?
 *
 * ANO u "upstream_rate_limited" a "upstream_error" - upstream odmítl požadavek (HTTP != 2xx),
 * takže nic nespočítal a žádné tokeny nespotřeboval; strhnout si za to znaky by znamenalo
 * účtovat si práci, která se nestala.
 *
 * NE u "upstream_empty" - tam upstream odpověděl HTTP 200, model tedy BĚŽEL a tokeny spotřeboval;
 * jen z toho nevypadl použitelný text (bezpečnostní filtr, došly output tokeny, rozbitý JSON).
 * To je stejně reálné čerpání free-tieru jako úspěch a strop ho má vidět.
 */
function shouldRefund(error: unknown): boolean {
  return error === "upstream_rate_limited" || error === "upstream_error";
}

/**
 * Záložní Gemini modely, které se zkusí, když primární model z payloadu neprojde
 * (404, deprekace, regionální nedostupnost). Uchovává se v tomto pořadí: nejprve
 * levný 2.5 Flash Lite (nevypíná thinking, protože ho defaultně neprovádí), pak 3.5
 * Flash s minimal thinking, pak 2.5 Flash s vypnutým thinking.
 */
const GEMINI_FALLBACK_MODELS = [
  "gemini-2.5-flash-lite",
  "gemini-3.5-flash",
  "gemini-2.5-flash",
];

/**
 * Konfigurace thinking pro daný Gemini model.
 * - Gemini 3.x používá thinkingLevel; "minimal" minimalizuje skryté reasoning tokeny,
 *   které jinak mohou vyčerpat output a vrátit prázdný JSON.
 * - Gemini 2.5.x používá thinkingBudget; 0 ho vypne.
 * - Starší modely thinking nemají, vracíme undefined.
 */
function geminiThinkingConfig(model: string): Record<string, unknown> | undefined {
  if (model.startsWith("gemini-3")) {
    return { thinkingLevel: "minimal" };
  }
  if (model.startsWith("gemini-2.5") || model.startsWith("gemini-2.0")) {
    return { thinkingBudget: 0 };
  }
  return undefined;
}

async function handleGeminiApi(system: string, user: string, model: string): Promise<Response> {
  if (!GEMINI_API_KEY) {
    console.error("GEMINI_API_KEY secret není nastavený na tomto projektu");
    return json({ text: "" }, 500);
  }

  const generationConfig: Record<string, unknown> = {
    temperature: 0.2,
    maxOutputTokens: 8192,
    responseMimeType: "application/json",
  };
  const thinking = geminiThinkingConfig(model);
  if (thinking) {
    generationConfig.thinkingConfig = thinking;
  }

  const geminiResp = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`,
    {
      method: "POST",
      headers: {
        "x-goog-api-key": GEMINI_API_KEY,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        systemInstruction: { parts: [{ text: system }] },
        contents: [{ role: "user", parts: [{ text: user }] }],
        generationConfig,
      }),
    },
  );

  if (!geminiResp.ok) {
    const bodyText = await geminiResp.text();
    console.error("gemini call failed", geminiResp.status, bodyText);
    const retryAfterSeconds = retryDelaySecondsFromGeminiBody(bodyText);
    return json({ text: "", error: upstreamErrorCode(geminiResp.status), retryAfterSeconds }, 200);
  }

  const data = await geminiResp.json();
  const parts = data?.candidates?.[0]?.content?.parts;
  const text: string = Array.isArray(parts)
    ? parts.map((p: { text?: string }) => p.text ?? "").join("")
    : "";
  if (!text) {
    // Prázdný text při HTTP 200 znamená safety filtr nebo vyčerpané output tokeny -
    // viz finishReason. Není to důvod providera vyřazovat, ale opakovat stejnou dávku
    // taky nemá smysl (viz UpstreamErrorCode).
    console.error("gemini returned no text", data?.candidates?.[0]?.finishReason);
    return json({ text: "", error: "upstream_empty" }, 200);
  }
  return json({ text }, 200);
}

async function handleGroqApi(system: string, user: string): Promise<Response> {
  if (!GROQ_API_KEY) {
    console.error("GROQ_API_KEY secret není nastavený na tomto projektu");
    return json({ text: "" }, 500);
  }

  const groqResp = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${GROQ_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "openai/gpt-oss-120b",
      temperature: 0.1,
      max_tokens: 4096,
      messages: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
      // Groq byl JEDINÝ provider, kterému se neřeklo, že se čeká JSON - Gemini dostává
      // responseMimeType a OpenRouter json_schema. Model pak občas odpověděl prózou
      // ("Překlady…") a appka celou odpověď zahodila na JSONException. Volání se tím
      // promarnilo včetně znaků, které za něj upstream odečetl.
      //
      // json_object, ne json_schema: podmínkou režimu je, aby se slovo "JSON" objevilo
      // v promptu - splněno, viz sekce VÝSTUPNÍ FORMÁT v GeminiUltraPrompt.
      response_format: { type: "json_object" },
    }),
  });

  if (!groqResp.ok) {
    console.error("groq chat call failed", groqResp.status, await groqResp.text());
    const retryAfterSeconds = retryDelaySecondsFromHeader(groqResp);
    return json({ text: "", error: upstreamErrorCode(groqResp.status), retryAfterSeconds }, 200);
  }

  const data = await groqResp.json();
  const text: string = data?.choices?.[0]?.message?.content ?? "";
  if (!text) return json({ text: "", error: "upstream_empty" }, 200);
  return json({ text }, 200);
}

async function handleOpenRouterApi(system: string, user: string): Promise<Response> {
  if (!OPENROUTER_API_KEY) {
    console.error("OPENROUTER_API_KEY secret není nastavený na tomto projektu");
    return json({ text: "" }, 500);
  }

  const orResp = await fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${OPENROUTER_API_KEY}`,
      "Content-Type": "application/json",
      "HTTP-Referer": "https://github.com/morg1z/jiyu",
      "X-Title": "Jiyu",
    },
    body: JSON.stringify({
      model: OPENROUTER_MODEL,
      temperature: 0.1,
      max_tokens: 4096,
      messages: [
        { role: "system", content: system },
        { role: "user", content: user },
      ],
      response_format: {
        type: "json_schema",
        json_schema: {
          name: "bubble_translation",
          strict: true,
          schema: {
            type: "object",
            additionalProperties: false,
            properties: {
              bubbles: {
                type: "array",
                items: {
                  type: "object",
                  additionalProperties: false,
                  properties: {
                    id: { type: "integer" },
                    original: { type: "string" },
                    translated: { type: "string" },
                    bubble_size_tag: { type: "string" },
                    is_sfx: { type: "boolean" },
                    syllable_breaks: { type: "string" },
                    notes: { type: "string" },
                  },
                  required: ["id", "translated"],
                },
              },
            },
            required: ["bubbles"],
          },
        },
      },
    }),
  });

  if (!orResp.ok) {
    console.error("openrouter call failed", orResp.status, await orResp.text());
    const retryAfterSeconds = retryDelaySecondsFromHeader(orResp);
    return json({ text: "", error: upstreamErrorCode(orResp.status), retryAfterSeconds }, 200);
  }

  const data = await orResp.json();
  const text: string = data?.choices?.[0]?.message?.content ?? "";
  if (!text) return json({ text: "", error: "upstream_empty" }, 200);
  return json({ text }, 200);
}

async function handleGemini(payload: Record<string, unknown>): Promise<Response> {
  const system = typeof payload.system === "string" ? payload.system : "";
  const user = typeof payload.user === "string" ? payload.user : "";
  const model = typeof payload.model === "string" && payload.model.length > 0
    ? payload.model
    : "gemini-2.5-flash-lite";
  const provider = payload.provider === "groq"
    ? "groq"
    : payload.provider === "openrouter"
    ? "openrouter"
    : "gemini";

  if (!user) return json({ text: "" }, 200);

  // Chybějící klíč znamená, že se upstream ani nezkusí zavolat - nemá tedy smysl si za to
  // ukrajovat z denního stropu. Dřív se strhl a teprve pak se zjistilo, že není kam poslat.
  const missingKey = (provider === "groq" && !GROQ_API_KEY) ||
    (provider === "openrouter" && !OPENROUTER_API_KEY) ||
    (provider === "gemini" && !GEMINI_API_KEY);
  if (missingKey) {
    console.error(`klíč pro providera "${provider}" není na tomhle projektu nastavený`);
    return json({ text: "", error: "upstream_error" }, 200);
  }

  const charCount = system.length + user.length;
  const { allowed, errored } = await checkQuota(charCount);
  if (errored) return json({ text: "" }, 500);
  if (!allowed) return json({ text: "", error: "daily_quota_exceeded" }, 429);

  let resp: Response;
  if (provider === "groq") {
    resp = await handleGroqApi(system, user);
  } else if (provider === "openrouter") {
    resp = await handleOpenRouterApi(system, user);
  } else {
    // Gemini: zkusíme primární model a při "upstream_error" (včetně 404/403 deprekace)
    // postupně záložní modely. "upstream_rate_limited" a "daily_quota_exceeded" zastaví
    // okamžitě - další model by stejně neprošel kvótou. "upstream_empty" vrátíme rovnou,
    // protože model běžel, jen vrátil prázdný text.
    const tryModels = [model, ...GEMINI_FALLBACK_MODELS].filter((m, i, a) => a.indexOf(m) === i);
    let lastResp: Response | undefined;
    for (const m of tryModels) {
      lastResp = await handleGeminiApi(system, user, m);
      const body = await lastResp.clone().json().catch(() => null);
      if (body?.text) {
        return lastResp;
      }
      if (body?.error === "upstream_rate_limited" || body?.error === "daily_quota_exceeded") {
        return lastResp;
      }
      if (body?.error === "upstream_error") {
        console.error(`gemini model ${m} odmítl, zkouším záložní`);
        continue;
      }
      return lastResp;
    }
    resp = lastResp ?? json({ text: "", error: "upstream_error" }, 200);
  }

  // Důvod selhání nesou handlery v těle odpovědi, ne v návratovém typu. Přečíst si ho z
  // KLONU je tady levnější a bezpečnější než přestavovat všechny tři cesty tak, aby místo
  // hotové Response vracely mezistav - tahle funkce běží v ostrém provozu a menší zásah
  // znamená menší riziko. Klon je nutný proto, že tělo Response jde přečíst jen jednou.
  const body = await resp.clone().json().catch(() => null);
  if (shouldRefund(body?.error)) await refundQuota(charCount);
  return resp;
}

/**
 * @returns content = odpověď modelu, nebo null při selhání. [error] nese důvod selhání ve
 *   stejném slovníku jako [UpstreamErrorCode] - viz tam, proč to appka potřebuje vědět.
 */
async function callChatCompletion(
  provider: "groq" | "openrouter" | "cerebras" | "mistral",
  system: string,
  userContent: string,
): Promise<{ content: string | null; error?: string; retryAfterSeconds?: number }> {
  if (provider === "cerebras") {
    if (!CEREBRAS_API_KEY) {
      console.error("CEREBRAS_API_KEY secret není nastavený na tomto projektu");
      return { content: null, error: "upstream_error" };
    }
    // Stejný model jako Groq (gpt-oss-120b), jiný upstream - Cerebras má ~5x větší
    // free-tier denní rozpočet (1M tokenů/den vs. Groqových ~200K), viz komentář u konstant.
    const resp = await fetch("https://api.cerebras.ai/v1/chat/completions", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${CEREBRAS_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "gpt-oss-120b",
        temperature: 0.1,
        max_tokens: 4096,
        messages: [
          { role: "system", content: system },
          { role: "user", content: userContent },
        ],
      }),
    });
    if (!resp.ok) {
      console.error("cerebras call failed", resp.status, await resp.text());
      return {
        content: null,
        error: upstreamErrorCode(resp.status),
        retryAfterSeconds: retryDelaySecondsFromHeader(resp),
      };
    }
    const data = await resp.json();
    return { content: data?.choices?.[0]?.message?.content ?? "" };
  }

  if (provider === "mistral") {
    if (!MISTRAL_API_KEY) {
      console.error("MISTRAL_API_KEY secret není nastavený na tomto projektu");
      return { content: null, error: "upstream_error" };
    }
    const resp = await fetch("https://api.mistral.ai/v1/chat/completions", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${MISTRAL_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "mistral-small-latest",
        temperature: 0.1,
        max_tokens: 4096,
        messages: [
          { role: "system", content: system },
          { role: "user", content: userContent },
        ],
      }),
    });
    if (!resp.ok) {
      console.error("mistral call failed", resp.status, await resp.text());
      return {
        content: null,
        error: upstreamErrorCode(resp.status),
        retryAfterSeconds: retryDelaySecondsFromHeader(resp),
      };
    }
    const data = await resp.json();
    return { content: data?.choices?.[0]?.message?.content ?? "" };
  }

  if (provider === "openrouter") {
    if (!OPENROUTER_API_KEY) {
      console.error("OPENROUTER_API_KEY secret není nastavený na tomto projektu");
      return { content: null, error: "upstream_error" };
    }
    const resp = await fetch("https://openrouter.ai/api/v1/chat/completions", {
      method: "POST",
      headers: {
        "Authorization": `Bearer ${OPENROUTER_API_KEY}`,
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/morg1z/jiyu",
        "X-Title": "Jiyu",
      },
      body: JSON.stringify({
        model: OPENROUTER_MODEL,
        temperature: 0.1,
        max_tokens: 4096,
        messages: [
          { role: "system", content: system },
          { role: "user", content: userContent },
        ],
      }),
    });
    if (!resp.ok) {
      console.error("openrouter call failed", resp.status, await resp.text());
      return {
        content: null,
        error: upstreamErrorCode(resp.status),
        retryAfterSeconds: retryDelaySecondsFromHeader(resp),
      };
    }
    const data = await resp.json();
    return { content: data?.choices?.[0]?.message?.content ?? "" };
  }

  if (!GROQ_API_KEY) {
    console.error("GROQ_API_KEY secret není nastavený na tomto projektu");
    return { content: null, error: "upstream_error" };
  }
  const resp = await fetch("https://api.groq.com/openai/v1/chat/completions", {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${GROQ_API_KEY}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "openai/gpt-oss-120b",
      temperature: 0.1,
      max_tokens: 4096,
      messages: [
        { role: "system", content: system },
        { role: "user", content: userContent },
      ],
    }),
  });
  if (!resp.ok) {
    console.error("groq call failed", resp.status, await resp.text());
    return {
      content: null,
      error: upstreamErrorCode(resp.status),
      retryAfterSeconds: retryDelaySecondsFromHeader(resp),
    };
  }
  const data = await resp.json();
  return { content: data?.choices?.[0]?.message?.content ?? "" };
}

async function handleGroq(payload: Record<string, unknown>, mode: "manga" | "novel"): Promise<Response> {
  const provider: "groq" | "openrouter" | "cerebras" | "mistral" =
    payload.provider === "openrouter" ? "openrouter"
    : payload.provider === "cerebras" ? "cerebras"
    : payload.provider === "mistral" ? "mistral"
    : "groq";

  const texts: unknown = payload.texts;
  const targetLanguage: string = (payload.targetLanguage as string) ?? "Czech";
  const sourceLanguage: string = (payload.sourceLanguage as string) ?? "Auto";
  const glossary: Record<string, string> = (payload.glossary as Record<string, string>) ?? {};
  // Volitelné - starší verze appky je neposílají, takže se musí snést i jejich nepřítomnost.
  const context: string = typeof payload.context === "string" ? payload.context : "";
  const recent: string[] = Array.isArray(payload.recent)
    ? (payload.recent as unknown[]).filter((l): l is string => typeof l === "string")
    : [];

  if (!Array.isArray(texts) || texts.length === 0) {
    return json({ translations: [] }, 200);
  }

  const contextClause = contextClauseFor(context, recent);

  // Kontext se do kvóty počítá - je to znaky poslané upstreamu jako každé jiné, a Gemini
  // cesta si je taky započítává (viz handleGemini, charCount = system.length + user.length).
  const charCount = texts.reduce(
    (sum: number, t: unknown) => sum + (typeof t === "string" ? t.length : 0),
    0,
  ) + contextClause.length;

  const { allowed, errored } = await checkQuota(charCount);
  if (errored) return json({ translations: [] }, 500);
  if (!allowed) return json({ translations: [], error: "daily_quota_exceeded" }, 429);

  const fromClause = sourceLanguage && sourceLanguage !== "Auto" ? `from ${sourceLanguage} ` : "";
  const glossaryClause = Object.keys(glossary).length > 0
    ? "\n\nThe following terms MUST be translated exactly as specified below, with no " +
      "deviation, regardless of what the general translation style would otherwise suggest:\n" +
      Object.entries(glossary).map(([k, v]) => `- "${k}" → "${v}"`).join("\n")
    : "";

  const systemPrompt = systemPromptFor(mode, fromClause, targetLanguage) + glossaryClause + contextClause;

  // Status 200 i při selhání upstreamu (dřív se u Groqu vracelo 500) - jinak by appka
  // odpověď zahodila jako "server chyba, zkus znovu" a k poli "error", ve kterém stojí
  // "tenhle provider má vyčerpanou kvótu", by se vůbec nedostala. Viz [UpstreamErrorCode].
  const { content, error, retryAfterSeconds } = await callChatCompletion(provider, systemPrompt, JSON.stringify(texts));
  if (content === null) {
    if (shouldRefund(error)) await refundQuota(charCount);
    return json({ translations: [], error, retryAfterSeconds }, 200);
  }
  if (!content.trim()) return json({ translations: [], error: "upstream_empty" }, 200);

  const cleaned = content.trim()
    .replace(/^```json/, "")
    .replace(/^```/, "")
    .replace(/```$/, "")
    .trim();

  let translations: unknown;
  try {
    translations = JSON.parse(cleaned);
    if (!Array.isArray(translations)) throw new Error("not an array");
  } catch {
    console.error(`failed to parse ${provider} response as JSON array`, cleaned);
    // Vlastnost konkrétní odpovědi, ne providera - opakovat nemá smysl, vyřazovat taky ne.
    return json({ translations: [], error: "upstream_empty" }, 200);
  }

  return json({ translations }, 200);
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response(null, { headers: CORS_HEADERS });
  }

  try {
    const payload = await req.json().catch(() => null);
    if (!payload) return json({ translations: [] }, 400);

    if (payload.mode === "gemini") {
      return await handleGemini(payload);
    }

    const mode: "manga" | "novel" = payload.mode === "novel" ? "novel" : "manga";
    return await handleGroq(payload, mode);
  } catch (e) {
    console.error(e);
    return json({ translations: [] }, 500);
  }
});
