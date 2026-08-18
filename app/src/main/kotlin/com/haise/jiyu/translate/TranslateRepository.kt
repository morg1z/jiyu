package com.haise.jiyu.translate

import android.util.Log
import com.haise.jiyu.data.db.MangaDao
import com.haise.jiyu.data.db.TranslatedNovelDao
import com.haise.jiyu.data.db.ManualTranslationDao
import com.haise.jiyu.data.db.TranslatedPageDao
import com.haise.jiyu.data.db.entity.TranslatedNovelEntity
import com.haise.jiyu.data.db.entity.ManualTranslationEntity
import com.haise.jiyu.data.db.entity.TranslatedPageEntity
import com.haise.jiyu.util.report
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranslateRepository @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val pageBitmapLoader: PageBitmapLoader,
    private val groqClient: GroqTranslateClient,
    private val geminiClient: GeminiTranslateClient,
    private val glossaryRepository: GlossaryRepository,
    private val providerHealth: ProviderHealth,
    private val mangaDao: MangaDao,
    private val dao: TranslatedPageDao,
    private val novelDao: TranslatedNovelDao,
    private val manualDao: ManualTranslationDao,
) {
    val isApiKeyConfigured: Boolean get() = groqClient.isConfigured

    // ML Kit translator is created lazily; in unit tests it cannot be instantiated
    // because it needs Android Google Play Services.
    private val onDeviceTranslator by lazy { OnDeviceTranslator() }

    private suspend fun glossaryFor(mangaId: String, targetLanguage: String): Map<String, String> =
        glossaryRepository.getMap(mangaId, targetLanguage)

    /**
     * Krátký kontext o samotné maze (název/typ obsahu/žánry) pro [GeminiUltraPrompt] -
     * model bez něj nemá tušení, jestli překládá temné fantasy, komedii nebo herní systém,
     * a volí tón/slovník podle toho. Prázdný řetězec, když se manga nenajde nebo nemá
     * vyplněné žánry (starý/ještě nenačtený záznam) - prompt takový řádek prostě vynechá.
     *
     * Typ obsahu se přidává DVAKRÁT a je to schválně: jednou jako nálepka v závorce (kvůli
     * názvu díla) a jednou rozepsaný do pravidel ([GeminiUltraPrompt.mediumRules]). Ze
     * samotné nálepky si model musel domýšlet, co z ní plyne - a u manhwy si typicky
     * domyslel japonská oslovení.
     */
    private suspend fun mangaContextFor(mangaId: String): String {
        val manga = mangaDao.getById(mangaId) ?: return ""
        val genres = manga.genres.split(",").map { it.trim() }.filter { it.isNotBlank() }
        return GeminiUltraPrompt.buildMangaContext(manga.title, manga.contentType, genres)
    }

    /**
     * Vrátí přeložené bloky pro jednu stránku.
     * Cache-first: pokud jsou v Room, vrátí okamžitě.
     * @return bloky nebo emptyList() pokud OCR/API selže
     */
    suspend fun translatePage(
        pageUrl: String,
        chapterId: String,
        mangaId: String,
        pageIndex: Int,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
    ): List<TranslatedBlock> {
        getCachedPage(chapterId, pageIndex, targetLanguage, sourceLanguage, pageUrl)?.let { return it }
        // Když jsou odstavení všichni cloud provideři, nemá smysl volat jejich řetězec,
        // ale on-device překlad (ML Kit) se zkusí jako poslední možnost, když je k dispozici.
        if (providerHealth.allUnavailable() && groqClient.isConfigured) throw RateLimitedException()

        // Stejný strop jako v translateChapter - bez něj by jedna zaseklá síťová odpověď
        // (viz komentář tam, až 180s na jeden obrázek) nechala appku bez zpětné vazby
        // stejně dlouho i tady, jen na jedné stránce místo celé kapitoly.
        val rawBlocks = withTimeoutOrNull(PAGE_OCR_TIMEOUT_MILLIS) {
            val bitmap = pageBitmapLoader.load(pageUrl) ?: return@withTimeoutOrNull emptyList()
            ocrEngine.recognize(bitmap, sourceLanguage)
        } ?: emptyList()
        if (rawBlocks.isEmpty()) return emptyList()

        val glossary = glossaryFor(mangaId, targetLanguage)
        val mangaContext = mangaContextFor(mangaId)
        val classified = BubbleClassifier.classifyPage(rawBlocks)

        // Návaznost při čtení stránku po stránce: co zaznělo na té předchozí. Bere se jen
        // z cache - dohledávat ji překladem by znamenalo přeložit stránku, kterou čtenář
        // možná vůbec neotevře. Na začátku kapitoly (a při skoku doprostřed) prostě není.
        val recentLines = if (pageIndex > 0) {
            val previous = getCachedPage(chapterId, pageIndex - 1, targetLanguage, sourceLanguage, pageUrl = null)
            GeminiUltraPrompt.recentContextLines(
                previous.orEmpty().filter { !it.isSfx && !it.isUntranslated }.map { it.translatedText },
            )
        } else {
            emptyList()
        }

        // GeminiUltraPrompt je napsaný natvrdo pro češtinu (znakové limity a kompresní
        // pravidla mají české příklady) - pro jiný cílový jazyk zůstáváme na obecném
        // Groq promptu (translate-proxy mode="manga"), který jazyk dostává jako parametr.
        //
        // Pokud není nakonfigurovaný žádný cloudový provider (Supabase/Groq/Gemini),
        // rovnou zkusíme on-device ML Kit překlad, aby uživatel viděl alespoň náhled.
        val blocks = if (!groqClient.isConfigured) {
            translateOnDevice(classified, targetLanguage, sourceLanguage, mangaId) ?: emptyList()
        } else if (targetLanguage == "Czech") {
            // 1) Gemini. 2) Stejný "ultra" prompt (komprese/sylabické dělení), ale přes OpenRouter
            //    free-tier model (provider="openrouter") - zachytí Gemini-specifické selhání
            //    (deprekovaný model, jeho vlastní výpadek) beze ztráty kvality. 3) Holý Groq
            //    překlad bez komprese jako záchrana, 4) stejně přes OpenRouter.
            //
            // "Ultra" prompt přes Groq (provider="groq") se SCHVÁLNĚ VYNECHÁVÁ - Groq po
            // vyřazení llama-3.3-70b-versatile (viz [GeminiTranslateClient] komentář u třídy)
            // zbyl jen na gpt-oss-120b, kterému free tier dává jen ~200K tokenů/den. Celý
            // "ultra" prompt (pravidla/příklady/glosář) má přes 2000 tokenů SÁM O SOBĚ - jedna
            // kapitola tak dokázala vyčerpat celý denní rozpočet Groq účtu na pár desítkách
            // bublin, než se appka vůbec dostala k levnému [translateWithGroq] níž (viz
            // uživatelská zpětná vazba - "denní limit vyčerpán" po jedné přeložené kapitole).
            // Groq má teď v řetězci jen ten levný, holý mód (mode="manga", jen texty/glosář/
            // kontext, žádná pravidla ani příklady) - ten samý malý rozpočet vydrží mnohem déle.
            //
            // RateLimitedException z JEDNOHO kroku už neznamená konec (viz translateChain) -
            // Gemini/Groq/OpenRouter jsou tři nezávislé komerční služby s vlastní kvótou,
            // 429 na proxy je jen jeho VLASTNÍ limit počtu požadavků (viz komentář u
            // RateLimitedException), ne nutně důkaz, že mají vyčerpáno i ostatní dva.
            // Cerebras/Mistral - dva další nezávislé free-tier provideři přidaní kvůli
            // kapacitě, jen tenhle levný holý mód (bez "ultra" promptu, stejný důvod jako
            // u Groq výš). Cerebras servíruje TENTÝŽ gpt-oss-120b jako Groq, ale s ~5x
            // větším denním rozpočtem - viz translate-proxy/index.ts komentář u konstant.
            translateChain(
                { translateWithGemini(classified, glossary, mangaContext, provider = "gemini", mangaId, targetLanguage, recentLines) },
                { translateWithGemini(classified, glossary, mangaContext, provider = "openrouter", mangaId, targetLanguage, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "groq", mangaContext, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "openrouter", mangaContext, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "cerebras", mangaContext, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "mistral", mangaContext, recentLines) },
                { translateOnDevice(classified, targetLanguage, sourceLanguage, mangaId) },
            )
        } else {
            // GeminiUltraPrompt je psaný natvrdo pro češtinu, takže pro jiné cílové jazyky
            // nemá smysl - ale i tak appka dřív měla jen JEDNU cestu (holý Groq) bez jakékoli
            // zálohy. Teď zkusí Groq a při selhání OpenRouter/Cerebras/Mistral (stejný obecný
            // "manga"/"novel" prompt parametrizovaný cílovým jazykem, viz translate-proxy
            // systemPromptFor).
            translateChain(
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "groq", mangaContext, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "openrouter", mangaContext, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "cerebras", mangaContext, recentLines) },
                { translateWithGroq(classified, glossary, targetLanguage, sourceLanguage, "mistral", mangaContext, recentLines) },
                { translateOnDevice(classified, targetLanguage, sourceLanguage, mangaId) },
            )
        }
        if (blocks.isEmpty()) return emptyList()

        dao.upsert(TranslatedPageEntity(id = cacheId(chapterId, pageIndex, targetLanguage, sourceLanguage), blocksJson = blocks.serialize()))
        // Ručně opravené bubliny se napařují AŽ TEĎ, na čerstvý strojový překlad, a do cache
        // se schválně neukládají - cache se při zvednutí PIPELINE_VERSION zahodí, kdežto oprava
        // má přežit. Viz [ManualTranslationEntity].
        return blocks.withManualEdits(chapterId, pageIndex)
    }

    /** Napařuje uložené ruční opravy - viz [applyManualEdits]. */
    private suspend fun List<TranslatedBlock>.withManualEdits(chapterId: String, pageIndex: Int): List<TranslatedBlock> {
        val edits = manualDao.forPage(chapterId, pageIndex)
        if (edits.isEmpty()) return this
        return applyManualEdits(this, edits.associate { normalizeOriginal(it.originalText) to it.text })
    }

    /**
     * Uloží ruční opravu jedné bubliny. Prázdný text opravu ZRUŠÍ a vrátí strojový překlad -
     * jinak by nešlo vzít změnu zpět jinak než přeložením celé kapitoly znovu.
     */
    suspend fun saveManualEdit(chapterId: String, pageIndex: Int, originalText: String, text: String) {
        val id = manualEditId(chapterId, pageIndex, originalText)
        if (text.isBlank()) {
            manualDao.delete(id)
            return
        }
        manualDao.upsert(
            ManualTranslationEntity(
                id = id,
                chapterId = chapterId,
                pageIndex = pageIndex,
                originalText = originalText,
                text = text.trim(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * Přeloží celou kapitolu v omezeném počtu dávkových API volání místo jednoho volání na
     * stránku (viz [translatePage]) - snižuje počet požadavků na proxy a odstraňuje potřebu
     * prodlevy mezi KAŽDOU stránkou v ReaderViewModelu, protože jedno volání umí přeložit
     * bubliny z více stránek najednou.
     *
     * Bublinám z více stránek se nepřidává žádné "page" pole do promptu ani JSON schématu -
     * stránky se před odesláním jen spojí do jednoho plochého seznamu (stejné id-schéma jako
     * [translatePage] pro jednu stránku, viz [GeminiUltraPrompt.buildUserPrompt] - "id" je
     * pozice v předaném seznamu) a po odpovědi se podle známého počtu bublin na stránku
     * rozdělí zpátky. Díky tomu tahle cesta nevyžaduje žádnou změnu [GeminiUltraPrompt] ani
     * Edge Function proxy.
     *
     * Dávky mají omezenou velikost (viz [chunkPages]/[CHAPTER_CHUNK_CHAR_LIMIT]), aby výstup
     * nepřekročil limit tokenů jednoho API volání - jedno volání na CELOU kapitolu by se u
     * delší kapitoly snadno oříznulo v půlce JSON odpovědi a celá kapitola by neuspěla najednou.
     *
     * @param onPageReady zavolá se pro KAŽDOU stránku zvlášť, jakmile je hotová (i když šla
     *   v dávce s ostatními) - zachovává postupné zobrazování stránek v ReaderViewModelu
     *   místo čekání na celou kapitolu najednou.
     * @throws RateLimitedException stejná sémantika jako [translatePage] - NEODCHYTÁVÁ se
     *   tady, volající (ReaderViewModel) na to má vlastní hlášku a přeruší zbytek kapitoly.
     */
    suspend fun translateChapter(
        pages: List<String>,
        chapterId: String,
        mangaId: String,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
        onPageReady: suspend (pageIndex: Int, blocks: List<TranslatedBlock>) -> Unit,
    ) {
        val uncached = mutableListOf<Int>()
        for (pageIndex in pages.indices) {
            val cached = getCachedPage(chapterId, pageIndex, targetLanguage, sourceLanguage, pages[pageIndex])
            if (cached != null) onPageReady(pageIndex, cached) else uncached += pageIndex
        }
        if (uncached.isEmpty()) return

        val glossary = glossaryFor(mangaId, targetLanguage)
        val mangaContext = mangaContextFor(mangaId)

        // Stahování bitmap (síť, viz PageBitmapLoader) a OCR (ML Kit, viz OcrEngine) mají
        // rozdílnou povahu souběžnosti - stahování je I/O čekání, snese víc paralelních
        // požadavků najednou; OCR recognizery jsou sdílené instance a plné rozlišení víc
        // stránek v paměti najednou by zbytečně riskovalo OOM na slabších telefonech, proto
        // má vlastní, přísnější limit.
        val bitmapLoadSemaphore = Semaphore(BITMAP_LOAD_PARALLELISM)
        val ocrSemaphore = Semaphore(OCR_PARALLELISM)
        val bubblesByPage: Map<Int, List<ClassifiedBubble>> = coroutineScope {
            uncached.map { pageIndex ->
                async(Dispatchers.IO) {
                    // Postup se hlásí (onPageReady) až PO téhle celé awaitAll - jedna jediná
                    // stránka s pomalým/zaseklým síťovým požadavkem (OkHttp má 30s connect +
                    // 30s read, RetryInterceptor to opakuje 3x = až 180s na jeden obrázek)
                    // by bez stropu zamrazila ukazatel postupu na 0/N pro CELOU kapitolu, i
                    // kdyby zbylých deset stránek dávno doběhlo (viz uživatelská zpětná vazba
                    // "3 minuty prekladam a porad 0/11" - 3 minuty odpovídá přesně tomu
                    // nejhoršímu případu). Timeout tu stránku prostě přeskočí jako bez textu,
                    // místo aby nechal viset celou dávku na neurčito.
                    val raw = withTimeoutOrNull(PAGE_OCR_TIMEOUT_MILLIS) {
                        val bitmap = bitmapLoadSemaphore.withPermit { pageBitmapLoader.load(pages[pageIndex]) }
                        bitmap?.let { bmp -> ocrSemaphore.withPermit { ocrEngine.recognize(bmp, sourceLanguage) } } ?: emptyList()
                    } ?: emptyList()
                    pageIndex to BubbleClassifier.classifyPage(raw)
                }
            }.awaitAll()
        }.toMap()

        val translatable = uncached.filter { bubblesByPage.getValue(it).isNotEmpty() }
        for (pageIndex in uncached) {
            if (pageIndex !in translatable) onPageReady(pageIndex, emptyList())
        }
        if (translatable.isEmpty()) return

        // Ocásek replik z PŘEDCHOZÍ dávky. Uvnitř dávky měl model kontext odjakživa (jde do
        // jednoho požadavku celá, v pořadí čtení), ale na hranici dávky začínal s čistým
        // stolem - uprostřed rozhovoru se pak mohlo přehodit tykání/vykání nebo oslovení.
        // Viz [GeminiUltraPrompt.recentContextLines], kde je i rozpočet.
        var recentLines = emptyList<String>()

        chunkPages(translatable, bubblesByPage).forEachIndexed { chunkIndex, chunk ->
            if (chunkIndex > 0) delay(800L)
            // Zbytek kapitoly by jen rychle "doběhl" s prázdnými výsledky - radši srozumitelná
            // hláška o limitu. Viz [ProviderHealth.allUnavailable].
            if (providerHealth.allUnavailable() && groqClient.isConfigured) throw RateLimitedException()
            val flatBubbles = chunk.flatMap { bubblesByPage.getValue(it) }

            // Stejný fallback řetězec jako translatePage - viz komentář tam ("ultra" prompt
            // přes Groq schválně vynechaný kvůli malému dennímu tokenovému rozpočtu
            // gpt-oss-120b). Volá se přes sdílené translateWithGemini/translateWithGroq beze
            // změny: obě funkce už dnes vždy vrací seznam přesně dlouhý jako vstupní
            // "flatBubbles" (chybějící "id" v odpovědi se doplní originálem, nikdy se
            // nezahodí), takže rozdělení jedné odpovědi zpátky po stránkách podle počtu
            // bublin níž je bezpečné.
            //
            // Pokud není nakonfigurovaný cloud (Supabase/Groq/Gemini), rovnou zkusíme
            // on-device ML Kit překlad.
            val blocks = if (!groqClient.isConfigured) {
                translateOnDevice(flatBubbles, targetLanguage, sourceLanguage, mangaId) ?: emptyList()
            } else if (targetLanguage == "Czech") {
                translateChain(
                    { translateWithGemini(flatBubbles, glossary, mangaContext, provider = "gemini", mangaId, targetLanguage, recentLines) },
                    { translateWithGemini(flatBubbles, glossary, mangaContext, provider = "openrouter", mangaId, targetLanguage, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "groq", mangaContext, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "openrouter", mangaContext, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "cerebras", mangaContext, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "mistral", mangaContext, recentLines) },
                    { translateOnDevice(flatBubbles, targetLanguage, sourceLanguage, mangaId) },
                )
            } else {
                translateChain(
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "groq", mangaContext, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "openrouter", mangaContext, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "cerebras", mangaContext, recentLines) },
                    { translateWithGroq(flatBubbles, glossary, targetLanguage, sourceLanguage, "mistral", mangaContext, recentLines) },
                    { translateOnDevice(flatBubbles, targetLanguage, sourceLanguage, mangaId) },
                )
            }

            val perPage = splitBlocksByPage(chunk, chunk.map { bubblesByPage.getValue(it).size }, blocks)
            for ((pageIndex, pageBlocks) in perPage) {
                if (pageBlocks.isNotEmpty()) {
                    dao.upsert(TranslatedPageEntity(id = cacheId(chapterId, pageIndex, targetLanguage, sourceLanguage), blocksJson = pageBlocks.serialize()))
                }
                onPageReady(pageIndex, pageBlocks)
            }

            // Ocásek pro DALŠÍ dávku. Zvukové efekty a bubliny, které model označil za
            // nečitelné, se vynechávají - jako "co zaznělo" by jen zabíraly rozpočet.
            recentLines = GeminiUltraPrompt.recentContextLines(
                blocks.filter { !it.isSfx && !it.isUntranslated }.map { it.translatedText },
            )
        }
    }

    /**
     * Rozdělí stránky (v pořadí) do dávek, kde součet délky bublinových textů v jedné dávce
     * nepřekročí [CHAPTER_CHUNK_CHAR_LIMIT] - jedna stránka je vždy atomická (nikdy se
     * nerozdělí mezi dvě dávky), stejný princip jako [chunkParagraphs] u novel překladu.
     */
    internal fun chunkPages(pageIndices: List<Int>, bubblesByPage: Map<Int, List<ClassifiedBubble>>): List<List<Int>> {
        val chunks = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var currentLen = 0
        for (pageIndex in pageIndices) {
            val len = bubblesByPage.getValue(pageIndex).sumOf { it.raw.text.length }
            if (current.isNotEmpty() && currentLen + len > CHAPTER_CHUNK_CHAR_LIMIT) {
                chunks += current
                current = mutableListOf()
                currentLen = 0
            }
            current += pageIndex
            currentLen += len
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    /**
     * @param provider "gemini", "groq" nebo "openrouter" - viz [GeminiTranslateClient.translateBubbles].
     *   Všichni tři provideři používají STEJNÝ [GeminiUltraPrompt] (komprese, sylabické dělení),
     *   liší se jen upstream model, na který proxy request přepošle.
     * @return null když se nepodařilo přeložit ani jednu bublinu (proxy nemá nasazený
     *   "gemini" mód, síť selhala po všech pokusech, upstream model vrátil chybu...) -
     *   volající ([translatePage]) pak zkusí dalšího providera nebo nakonec
     *   [translateWithGroq] (bez komprese) jako poslední záchrannou síť.
     * @throws RateLimitedException když je vyčerpaná sdílená denní kvóta proxy - viz
     *   [translatePage], tohle se záměrně NEODCHYTÁVÁ tady.
     */
    private suspend fun translateWithGemini(
        classified: List<ClassifiedBubble>,
        glossary: Map<String, String>,
        mangaContext: String,
        provider: String,
        mangaId: String,
        targetLanguage: String,
        previousLines: List<String> = emptyList(),
    ): List<TranslatedBlock>? {
        if (!geminiClient.isConfigured) return null
        val response = geminiClient.translateBubbles(classified, glossary, provider, mangaContext, previousLines) ?: return null

        // Model občas bublinu v odpovědi vynechá nebo pro ni vrátí prázdný řetězec. Doptáme se
        // JEN na ty chybějící (ne na celou dávku znovu) - je jich pár, takže je to jedno krátké
        // volání navíc, a bez něj by se místo překladu vykreslil anglický originál nebo prázdná
        // bublina (viz [TranslationMerge] a uživatelské screenshoty s "THE FIRST PLACE.").
        val missing = missingTranslationIndices(classified, response.bubbles.associateBy { it.id })
        val byId = if (missing.isEmpty()) {
            response.bubbles.associateBy { it.id }
        } else {
            val retryResponse = geminiClient.translateBubbles(
                bubbles = missing.map { classified[it] },
                glossary = glossary,
                provider = provider,
                mangaContext = mangaContext,
                previousLines = previousLines,
            )
            mergeRetry(response.bubbles.associateBy { it.id }, missing, retryResponse, classified)
        }

        // Auto-učení glosáře (viz GeminiUltraPrompt sekce "NOVÉ POJMY") - model sám
        // identifikuje vlastní jména v téhle dávce, appka je uloží, aby byla konzistentní
        // i v dalších kapitolách BEZ nutnosti ručního zásahu. Ruční záznam uživatele má
        // vždycky přednost - proto se přeskočí, když glosář už stejný zdrojový termín má
        // (ignoreCase, protože ID záznamu je case-insensitive, viz GlossaryRepository.upsert).
        for (term in response.newTerms) {
            // Do te doby se ukladalo VSECHNO, co model vratil, bez kontroly - a glosar je
            // v promptu zavazny, takze jeden nesmysl si model vnucoval ve vsech dalsich
            // kapitolach. Viz [isPlausibleGlossaryTerm].
            if (!isPlausibleGlossaryTerm(term.source, term.target)) continue
            if (glossary.keys.none { it.equals(term.source, ignoreCase = true) }) {
                glossaryRepository.upsert(mangaId, term.source, term.target, targetLanguage)
            }
        }

        // mapIndexed (ne mapIndexedNotNull) - chybějící "id" v odpovědi musí zůstat na svém
        // místě jako blok s originálem místo zmizet, jinak by se posunula pozice ostatních
        // bublin v seznamu, na které translateChapter spoléhá při rozdělování jedné dávky
        // (víc stránek najednou) zpátky po stránkách podle počtu bublin.
        val result = classified.mapIndexed { i, c ->
            if (c.isSfx) return@mapIndexed sfxBlock(c)
            val t = byId[i]
            // Bublina je "nepřeložená" ve čtyřech případech: model vrátil UNTRANSLATED_MARKER
            // (OCR nedává smysl, viz prompt), vrátil prázdný řetězec, vynechal ji v odpovědi
            // úplně (a to i po opravném dotazu výš), nebo jeho echované "original" neodpovídá
            // tomu, co bublina doopravdy obsahovala (viz [originalMatches] - model si spletl
            // číslování "id" pod velkou dávkou a odpověděl na JINOU bublinu). Ve VŠECH čtyřech
            // se musí označit isUntranslated, aby ji TranslationLayer vůbec nekreslil a originál
            // zůstal čitelný. Bez posledního případu appka slepě věřila poli "id" a bublina
            // dostala cizí text patřící jiné bublině o pár pozic dál - viz uživatelská zpětná
            // vazba ("NOT LIKE THAT." zobrazila překlad patřící jiné bublině na jiné stránce).
            val isUntranslated = !isUsableTranslation(t, c.raw.text)
            val translatedText = if (isUntranslated) c.raw.text else t!!.translated
            if (!isUntranslated) {
                logIfSuspiciousVerbatimCopy(c.raw.text, translatedText)
                logIfLikelyDroppedSentence(c.raw.text, translatedText)
            }
            // Model syllable_breaks se použije JEN, když opravdu odpovídá translatedText po
            // odstranění rozdělovníků (viz isValidSyllableBreaks) - jinak by poškozený/
            // neshodující se výstup modelu potichu nahradil správný překlad viditelně
            // rozbitým textem (viz uživatelská zpětná vazba - "OKAMŽITĚ" -> "OKAM" + zbytek).
            // ensureFallbackHyphens navíc doplní rozdělovník do dlouhých slov, která ho
            // nemají ani po týhle validaci (model ho pro ně nevrátil vůbec).
            val syllableBreaks = t?.syllableBreaks
            val validatedDisplay = if (syllableBreaks != null && isValidSyllableBreaks(translatedText, syllableBreaks)) {
                syllableBreaks
            } else {
                translatedText
            }
            TranslatedBlock(
                originalText = c.raw.text,
                translatedText = translatedText,
                leftF = c.raw.leftF,
                topF = c.raw.topF,
                rightF = c.raw.rightF,
                bottomF = c.raw.bottomF,
                displayText = if (isUntranslated) c.raw.text else ensureFallbackHyphens(validatedDisplay),
                bgColorArgb = c.raw.bgColorTopArgb,
                bgColorBottomArgb = c.raw.bgColorBottomArgb,
                isSfx = false,
                lineCount = c.lineCount,
                shape = c.raw.shape,
                bubbleType = c.bubbleType,
                isUntranslated = isUntranslated,
                bgUniform = c.raw.bgUniform,
                nativeLineHeightF = c.raw.nativeLineHeightF,
            )
        }
        return result.ifEmpty { null }
    }

    /**
     * Legacy/fallback cesta - vrací jen holé přeložené texty, žádné syllable_breaks.
     * @param provider "groq" (výchozí) nebo "openrouter" - stejný obecný prompt
     *   (translate-proxy mode="manga"/"novel"), jiný upstream model.
     * @return null při selhání (žádný text se nepřeložil) - volající zkusí dalšího
     *   providera nebo nakonec vrátí prázdný seznam.
     */
    private suspend fun translateWithGroq(
        classified: List<ClassifiedBubble>,
        glossary: Map<String, String>,
        targetLanguage: String,
        sourceLanguage: String,
        provider: String = "groq",
        mangaContext: String = "",
        previousLines: List<String> = emptyList(),
    ): List<TranslatedBlock>? {
        val toTranslate = classified.filter { !it.isSfx }
        val translations = if (toTranslate.isEmpty()) emptyList() else groqClient.translateBatch(
            texts = toTranslate.map { it.raw.text },
            targetLanguage = targetLanguage,
            sourceLanguage = sourceLanguage,
            glossary = glossary,
            provider = provider,
            mangaContext = mangaContext,
            previousLines = previousLines,
        )
        if (toTranslate.isNotEmpty() && translations.isEmpty()) return null

        var ti = 0
        return classified.map { c ->
            if (c.isSfx) {
                sfxBlock(c)
            } else {
                val raw = translations.getOrNull(ti)?.trim()
                ti++
                // Stejné pravidlo jako u translateWithGemini: chybějící (kratší odpověď než
                // vstup), prázdný i UNTRANSLATED_MARKER výsledek znamená NEPŘELOŽENO. Dřív se
                // v prvních dvou případech potichu propadl originál a vykreslil se jako
                // překlad - viz [TranslationMerge]. Doslovná kopie originálu (viz
                // isSuspiciousVerbatimCopy) je stejná třída selhání - bez tyhle kontroly appka
                // klidně vykreslila anglickou větu jako hotový český překlad (nahlášeno: "HOW WE
                // MAKE A LIVING."/"ABOUT WHAT?").
                val usable = raw?.takeIf {
                    it.isNotEmpty() && it != GeminiUltraPrompt.UNTRANSLATED_MARKER && !isSuspiciousVerbatimCopy(c.raw.text, it)
                }
                val isUntranslated = usable == null
                val translated = usable ?: c.raw.text
                if (!isUntranslated) {
                    logIfSuspiciousVerbatimCopy(c.raw.text, translated)
                    logIfLikelyDroppedSentence(c.raw.text, translated)
                }
                TranslatedBlock(
                    originalText = c.raw.text,
                    translatedText = translated,
                    leftF = c.raw.leftF,
                    topF = c.raw.topF,
                    rightF = c.raw.rightF,
                    bottomF = c.raw.bottomF,
                    // Groq/OpenRouter cesta nemá žádný syllable_breaks od modelu (jen
                    // GeminiUltraPrompt ho umí) - ensureFallbackHyphens je tu JEDINÁ ochrana
                    // proti tomu, aby dlouhé slovo přeteklo a Compose ho useklo bez pomlčky.
                    displayText = if (isUntranslated) translated else ensureFallbackHyphens(translated),
                    bgColorArgb = c.raw.bgColorTopArgb,
                    bgColorBottomArgb = c.raw.bgColorBottomArgb,
                    isSfx = false,
                    lineCount = c.lineCount,
                    shape = c.raw.shape,
                    bubbleType = c.bubbleType,
                    isUntranslated = isUntranslated,
                    bgUniform = c.raw.bgUniform,
                    nativeLineHeightF = c.raw.nativeLineHeightF,
                )
            }
        }
    }

    /**
     * Loguje, kdyz "preklad" vysel (az na velikost pismen) doslova stejny jako original -
     * viz [isSuspiciousVerbatimCopy]. Prompt ma sekci "KONTROLA PRED ODESLANIM" (zaporky,
     * zadna vymyslena slova...), ale nic v kodu drive neoverovalo, jestli ji model doopravdy
     * dodrzel - tohle je jediny spolehlivy, jazykove nezavisly signal, ktery se z odpovedi
     * da mechanicky vycist. Cistě observabilita: `adb logcat -s VerbatimCopy` pri beznem
     * cteni ukaze, jak casto k tomu dochazi.
     */
    private fun logIfSuspiciousVerbatimCopy(original: String, translated: String) {
        if (!isSuspiciousVerbatimCopy(original, translated)) return
        Log.d("VerbatimCopy", "original=\"$original\"")
    }

    /**
     * Loguje, kdyz preklad vicevetne bubliny (slouceny OCR blok nebo "POKRACUJE Z" navazujici
     * bublina) zjevne zahodil celou vetu - viz [likelyDroppedSentence]. Cistě observabilita:
     * `adb logcat -s DroppedSentence` u nahlaseneho "spojena bublina ztratila vetu" ukaze, jak
     * casto k tomu dochazi a jestli se to tyka konkretniho providera/typu bubliny.
     */
    private fun logIfLikelyDroppedSentence(original: String, translated: String) {
        if (!likelyDroppedSentence(original, translated)) return
        Log.d("DroppedSentence", "original=\"$original\" translated=\"$translated\"")
    }

    /** SFX bublina se nikdy nepřekládá (viz [BubbleClassifier]) - originál zůstává, jen si nese klasifikaci pro render. */
    private fun sfxBlock(c: ClassifiedBubble) = TranslatedBlock(
        originalText = c.raw.text,
        translatedText = c.raw.text,
        leftF = c.raw.leftF,
        topF = c.raw.topF,
        rightF = c.raw.rightF,
        bottomF = c.raw.bottomF,
        displayText = c.raw.text,
        bgColorArgb = c.raw.bgColorTopArgb,
        bgColorBottomArgb = c.raw.bgColorBottomArgb,
        isSfx = true,
        lineCount = c.lineCount,
        shape = c.raw.shape,
        bubbleType = c.bubbleType,
        bgUniform = c.raw.bgUniform,
        nativeLineHeightF = c.raw.nativeLineHeightF,
    )

    /**
     * Poslední záchrana: on-device ML Kit překlad, když není dostupný žádný cloudový provider.
     * Kvalita je nižší než u modelových API, ale nepotřebuje žádný klíč ani Supabase.
     * Vrací null, když ML Kit buď nepodporuje daný jazykový pár, nebo selhal úplně všechen text.
     */
    private suspend fun translateOnDevice(
        classified: List<ClassifiedBubble>,
        targetLanguage: String,
        sourceLanguage: String,
        mangaId: String,
    ): List<TranslatedBlock>? {
        val toTranslate = classified.filter { !it.isSfx }
        if (toTranslate.isEmpty()) return classified.map { sfxBlock(it) }

        val translations = onDeviceTranslator.translate(
            texts = toTranslate.map { it.raw.text },
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            glossary = glossaryFor(mangaId, targetLanguage),
        )
        if (translations.all { it.isNullOrBlank() }) return null

        var ti = 0
        return classified.map { c ->
            if (c.isSfx) {
                sfxBlock(c)
            } else {
                val raw = translations.getOrNull(ti)?.trim()
                ti++
                val isUntranslated = raw.isNullOrEmpty()
                val translated = raw ?: c.raw.text
                TranslatedBlock(
                    originalText = c.raw.text,
                    translatedText = translated,
                    leftF = c.raw.leftF,
                    topF = c.raw.topF,
                    rightF = c.raw.rightF,
                    bottomF = c.raw.bottomF,
                    displayText = if (isUntranslated) translated else ensureFallbackHyphens(translated),
                    bgColorArgb = c.raw.bgColorTopArgb,
                    bgColorBottomArgb = c.raw.bgColorBottomArgb,
                    isSfx = false,
                    lineCount = c.lineCount,
                    shape = c.raw.shape,
                    bubbleType = c.bubbleType,
                    isUntranslated = isUntranslated,
                    bgUniform = c.raw.bgUniform,
                    nativeLineHeightF = c.raw.nativeLineHeightF,
                )
            }
        }
    }

    /**
     * Vrátí výsledek z Room cache bez volání překladového API; null = není v cache.
     * @param pageUrl když je zadané a cache záznam ještě nemá dopočítaný tvar bubliny
     *   (starý formát), dopočítá se tvar (bez nového OCR/překladu) a cache se přepíše -
     *   viz OcrEngine.detectShapesOnly. Bitmapa se stahuje (přes PageBitmapLoader) JEN když
     *   je migrace opravdu potřeba, ne při každém cache-hitu. Bez pageUrl (starší volající,
     *   co ho nemají po ruce) se migrace přeskočí a bloky zůstanou s shape=null (heuristický
     *   fallback v layoutu). Selhání stažení bitmapy vrátí nezmigrované bloky, ne null.
     */
    suspend fun getCachedPage(
        chapterId: String,
        pageIndex: Int,
        targetLanguage: String,
        sourceLanguage: String = "Auto",
        pageUrl: String? = null,
    ): List<TranslatedBlock>? {
        val id = cacheId(chapterId, pageIndex, targetLanguage, sourceLanguage)
        val cached = dao.getById(id)?.deserialize()?.withManualEdits(chapterId, pageIndex) ?: return null
        if (pageUrl == null) return cached

        val needsShapeMigration = cached.any { !it.isSfx && it.shape == null }
        if (!needsShapeMigration) return cached

        // translateChapter volá getCachedPage SEKVENČNĚ pro každou stránku (viz cache-check
        // smyčka) ještě PŘED paralelní dávkou nepřeložených stránek - stejný strop jako tam,
        // ať zaseklá migrace jedné staré stránky nezablokuje i tenhle úvodní průchod.
        return withTimeoutOrNull(PAGE_OCR_TIMEOUT_MILLIS) {
            val bitmap = pageBitmapLoader.load(pageUrl) ?: return@withTimeoutOrNull cached
            val migrated = ocrEngine.detectShapesOnly(bitmap, cached)
            dao.upsert(TranslatedPageEntity(id = id, blocksJson = migrated.serialize()))
            migrated
        } ?: cached
    }

    private fun cacheId(chapterId: String, pageIndex: Int, targetLanguage: String, sourceLanguage: String) =
        "$chapterId::$pageIndex::$sourceLanguage::$targetLanguage::v$PIPELINE_VERSION"

    companion object {
        /**
         * Verze překladového pipeline (OCR klasifikace + prompt), zahrnutá do klíče cache.
         * Bez ní zůstávaly po aktualizaci appky viset staré, rozbité výsledky - uživatel
         * nainstaloval opravu, ale pořád viděl přesně tu chybu, co byla opravená, protože se
         * stránka vzala z cache a znovu se nezpracovala (viz uživatelská zpětná vazba
         * "nic si neopravil"). Zvyš tohle číslo VŽDY, když se změní něco, co ovlivňuje
         * ULOŽENÁ data (klasifikace SFX/vodoznaku, prompt, struktura bloků) - ne když se
         * mění jen vykreslování (to se počítá při zobrazení a na cache nezávisí).
         *
         * v2 (2026-07-27): detekce vodoznaku scanlation skupiny + krátká slova už nejsou SFX.
         * v3 (2026-07-31): chybějící/prázdná odpověď modelu se ukládá jako isUntranslated
         *   místo tichého propadnutí originálu (viz [TranslationMerge]) - staré záznamy nesou
         *   isUntranslated=false u bublin, kde má nově být true, takže se musí přepočítat.
         * v4 (2026-07-31): dvě změny mění, co se z OCR/modelu doopravdy uloží -
         *   (1) hasWallBetween veden středem PŘEKRYVU, ne středy celých bloků (viz
         *   BubbleMerge.kt) - kaskádová/"dvouhrbá" bublina s vodorovně posunutým druhým
         *   řádkem se dřív omylem rozdělila na dvě bubliny a půlka textu zmizela (viz
         *   uživatelská zpětná vazba "GOOD HEAVENS," chybějící z "TO GET LOST..."); (2)
         *   ověření modelem echovaného "original" proti tomu, co bublina doopravdy
         *   obsahovala (viz [originalMatches]) - dřív appka slepě věřila poli "id" a
         *   posunuté číslování u velké dávky (víc stránek najednou) přeneslo překlad na
         *   jinou bublinu, než pro kterou byl určen. Staré cache záznamy mají obojí
         *   spočítané podle starší, chybové logiky, proto se musí přepočítat.
         * v5 (2026-07-31): další dvě změny mění, co se z OCR doopravdy uloží -
         *   (1) hasWallBetween teď vyžaduje VĚTŠINU vzorků na úsečce, ne jediný, aby
         *   prohlásil "zeď" (viz BubbleMerge.kt) - tenký/diagonální vodoznak nastříknutý
         *   přes bublinu (např. "VORTEXSCANS.COM" ležící mezi dvěma půlkami repliky)
         *   protínal přímou úsečku jen v 1 z 5 bodů, což dřív stačilo na rozdělení jedné
         *   bubliny na dvě a půlka textu zmizela z překladu; (2) BubbleClassifier.classifyPage
         *   navíc detekuje rozházený/dlaždicovaný vodoznak NAPŘÍČ CELOU stránkou (víc
         *   samostatných OCR bloků se stejným, různě zkomoleným jménem skenlační skupiny),
         *   ne jen jeden blok samotný - staré záznamy mají obojí spočítané podle starší
         *   logiky, proto se musí přepočítat.
         * v6 (2026-08-01): dvě změny mění, co se z OCR doopravdy uloží -
         *   (1) zdrojový jazyk "Auto" konečně vybírá rozpoznávač podle toho, co na stránce
         *   opravdu je (viz [resolveAutoLanguage]) - dřív pro něj nebyla větev a spadl na
         *   LATINKOVÝ model, takže japonská/korejská/čínská stránka vrátila nesmysl nebo nic
         *   a bubliny se navíc seřadily zleva doprava; (2) shlukování dlaždicovaného vodoznaku
         *   už nebere souvislý úsek jako důkaz (viz [looksLikeGarbledRepeat]) - tři repliky,
         *   kde každá jen prodlužuje předchozí ("HELP" / "HELP ME" / "HELP ME NOW"), se dřív
         *   označily za vodoznak a vůbec se nepřeložily. Staré záznamy mají obojí spočítané
         *   podle starší logiky, proto se musí přepočítat.
         * v7 (2026-08-01): prompt nově dostává informaci o tom, které bubliny tvoří JEDNU
         *   větu rozdělenou do dvou (viz [detectContinuations] a sekce "VĚTY PŘES VÍC BUBLIN"
         *   v [GeminiUltraPrompt]). Dřív model viděl jen plochý seznam textů a kaskádovou
         *   repliku překládal po kouscích - každou půlku bez druhé, takže z první vypadl úvod
         *   nebo se druhá přeložila jako samostatná věta. Změna ovlivňuje ULOŽENÝ překlad,
         *   proto se staré záznamy musí přepočítat.
         * v8 (2026-08-01): obrys bubliny se ořezává, aby nesahal přes text bubliny sousední
         *   (viz [clampShapeToOwnLobe]). Kaskádová replika bývá nakreslená jako dvě
         *   PŘEKRÝVAJÍCÍ SE bublinky tvořící jednu spojitou bílou plochu; flood-fill se přes
         *   ten pas přelil do druhého laloku a výplň spodní bubliny pak přemalovala text té
         *   horní - včetně textu, který se vůbec nepřeložil, takže z něj nezbylo nic. Obrys se
         *   ukládá do bloku, proto se staré záznamy musí přepočítat.
         * v9 (2026-08-01): ořez z v8 se u kaskádové bubliny vůbec nespustil. Rozhodoval se podle
         *   toho, jestli se OCR boxy vodorovně překrývají aspoň ze čtvrtiny - jenže laloky
         *   kaskádové bubliny jsou ZÁMĚRNĚ posunuté do stran (horní vpravo, spodní vlevo), právě
         *   to jim dává ten schodovitý tvar, takže se boxy překrývají sotva. Změřeno na zařízení:
         *   oba bloky dostaly totožný tvar celého balónu (0.102..0.637), přesně jako bez opravy.
         *   Nově rozhoduje, jestli tvar POKRÝVÁ cizí text (viz [clampShapeToOwnLobe]); po opravě
         *   tytéž bloky dostaly 0.102..0.311 a 0.335..0.637. Obrys se ukládá, proto přepočet.
         * v10 (2026-08-01): [detectContinuations] u kaskádové bubliny nikdy nesepnulo. Vyžadovalo
         *   vodorovný překryv aspoň 0,35 užší bubliny, jenže laloky jsou posunuté do stran -
         *   změřeno na nahlášené stránce: 0,178. Model se tedy nedozvěděl, že obě půlky tvoří
         *   JEDNU větu, a přeložil je odděleně. Práh je nově 0,15. Ovlivňuje prompt, tedy
         *   ULOŽENÝ překlad, proto se staré záznamy musí přepočítat.
         * v11 (2026-08-02): útržek věty se nikdy nepřeložil. Dvě nezávislé příčiny, obě mění
         *   uložený výsledek: (1) [BubbleClassifier] označil "...SAY," za zvukový efekt, takže
         *   se blok vůbec neposlal na překlad - "core" se ořezávalo jen o !?. a mezeru, čárka
         *   mezi ně nepatřila, a tím se rozbila jediná pojistka pravidla o krátkém ALL CAPS
         *   textu (porovnávalo se "WAIT," místo "WAIT", takže propadla i slova, která seznam
         *   VÝSLOVNĚ chrání); nově se ořezává veškerá okrajová interpunkce a text pokračující
         *   ve větě (čárka na konci, výpustka na začátku) není zvuk. (2) Prompt v sekci CHYBY
         *   uváděl "útržek" jako důvod pro [GeminiUltraPrompt.UNTRANSLATED_MARKER], což si
         *   protiřečilo se sekcí o větách přes víc bublin - marker je nově jen pro NEČITELNÝ
         *   text. Staré záznamy mají obojí spočítané podle starší logiky, proto přepočet.
         * v12 (2026-08-02): obrys se odmítne, když je nesmyslně velký proti textu uvnitř (viz
         *   [BubbleShapeDetector] MAX_SHAPE_TO_TEXT_AREA_RATIO). Plošný limit byl vztažený ke celé
         *   stránce, což je u vysokých stránek obrovská rezerva - flood-fill, který unikl z bubliny
         *   do tmavé kresby, se do ní pohodlně vešel a výplň pak přemalovala půl panelu. Změřeno na
         *   zařízení na nahlášené stránce: skutečné bubliny 2,6x až 17,4x plochy svého OCR boxu,
         *   vodoznak na tmavém pruhu 54x až 216x. Obrys se ukládá do bloku, proto přepočet.
         * v13 (2026-08-02): tri opravy kvality prekladu naraz, vsechny meni ULOZENY vysledek.
         *   (1) Slova, ktera lettering deli pomlckou na konci radku, se pred odeslanim spoji.
         *   Bublina "EVERY-" / "ONE DON'T SCATTER..." dorazila k modelu jako rozsypany zacatek
         *   vety a v prekladu z ni vypadl zapor - vysla veta, ktera si odporuje sama v sobe
         *   ("rozptylte se, zustavejte spolu"). Viz [joinHyphenatedLineBreaks].
         *   (2) Do glosare uz nejde ulozit cokoliv. Do ted se zapsalo vse, co model vratil,
         *   a glosar je v promptu zavazny, takze jeden nesmysl si model vnucoval i ve vsech
         *   dalsich kapitolach - odtud nahlaseny nesmysl "ZAVRI PANU" misto "drz hubu",
         *   pricemz slovo "mouth" zadny druhy vyznam nema. Viz [isPlausibleGlossaryTerm].
         *   (3) Prompt ma nove pet pravidel uplne nahore (zapor se nesmi ztratit, veta si nesmi
         *   odporovat) a zaverecnou kontrolu pred sestavenim JSON; glosar uz neni nadrazeny
         *   smyslu vety.
         * v14 (2026-08-02): typ dila se modelu posila rozepsany do pravidel, ne jen jako nalepka
         *   v zavorce (viz [GeminiUltraPrompt.mediumRules]). Ze samotneho "(manhwa)" si model
         *   musel domyslet, co z toho plyne - a u manhwy si typicky domyslel japonska
         *   honorifika, prestoze "hyung" a "senpai" nejsou zamenitelne a jmena se prepisuji
         *   jinak. Meni to prompt, tedy i ulozeny preklad.
         *   Ve stejne verzi: k davce se pribaluje ocasek uz prelozenych replik (viz
         *   [GeminiUltraPrompt.recentContextLines]). Uvnitr davky mel model kontext vzdycky,
         *   na jeji hranici ale zacinal s cistym stolem - uprostred rozhovoru se pak mohlo
         *   prehodit tykani/vykani nebo osloveni postavy.
         * v15 (2026-08-02): svisle sazena japonstina se slucuje vlastnim pravidlem. ML Kit vraci
         *   cely SLOUPEC jako jeden "radek" a stare pravidlo porovnavalo mezeru mezi sloupci
         *   s VYSKOU sloupce - 1,8x vyska sloupce je pres pul stranky, takze se slily i bubliny
         *   350 px od sebe. Namereno sondou na zarizeni: cela stranka se slila do JEDNOHO bloku
         *   s promichanym textem. Meni to vysledek OCR, tedy i ulozene bloky.
         * v16 (2026-08-03): tri opravy z nahlasene stranky Vagabonda, vsechny meni ulozena data.
         *   (1) Zrusene pravidlo "kratky text velkymi pismeny bez mezer = zvuk" (viz
         *   [BubbleClassifier]) - v komiksu je verzalkami VSECHNO, takze nerozlisovalo nic a
         *   polykalo bezne repliky ("I", "TOO...", "TAKEZO."). Meni to, ktere bloky jsou SFX,
         *   tedy i ktere se vubec prelozi.
         *   (2) Rozdelovnik od modelu se overuje i na to, KAM padne, ne jen jestli po jeho
         *   odstraneni sedi text (viz [isValidSyllableBreaks]) - odtud "POSLEDN" + osamocene
         *   "I" na dalsim radku. Meni to ulozeny displayText.
         *   (3) Zalozni cesta (Groq/OpenRouter) dostava kontext dila a ocasek predchozich
         *   replik, ktere do teto chvile mela jen cesta pres Gemini - bez nich prekladala
         *   izolovane vety naslepo ("JUST LEAVE ME HERE." -> "ZUSTANTE ME TADY").
         *   (4) Jednolitost pozadi uz neurcuje NEJVETSI odchylka vzorku (viz
         *   [isBackgroundUniform]). Prstenec se vzorkuje par pixelu od OCR boxu a ten obcas
         *   kraj pismene orizne, takze jediny vzorek spadly na tah pisma prehodil i ciste
         *   bilou bublinu na "pestra kresba" - a ta pak dostala zaplatu misto plne vyplne.
         *   Zaplata z principu nedocisti vsechno, odtud zbytky originalu pod prekladem.
         *   Zmereno sondou na zarizeni: "SURVIVOR..." uprostred bile bubliny vychazelo
         *   bgUniform=false. Meni to ulozeny priznak bgUniform, tedy i vzhled bubliny.
         *
         *   Body (1)-(4) sly ven najednou ve v1.0.6.
         * v17 (2026-08-03): bod (4) z v16 se VRACI zpet - percentil byl chyba a v1.0.6 ji
         *   vydal. Vodovkova bitevni scena z Vagabonda je barevne docela jednotna: vetsina
         *   prstence padne do tolerance a mimo ni je jen MENSINA vzorku (kmen stromu, tmavy
         *   teren pod popiskem). S percentilem tedy prosla jako "jednolite pozadi", dostala
         *   plnou vypln a pres kresbu se rozlila placka - nahlaseno okamzite, popisek "BITVA U
         *   SEKIGAHARY" a panely "DUP"/"TROMP". Rozhoduje zase NEJVETSI odchylka; proc to
         *   nejde zachranit jinou mezi, je zapsane u [isBackgroundUniform]. Vraci se tim i
         *   znamy ustupek: v bubline muze pod prekladem zustat drobny zbytek originalu.
         *   Priznak bgUniform je ULOZENY, takze bez zvednuti verze by placky na uz prelozenych
         *   strankach zustaly viset.
         */
        private const val PIPELINE_VERSION = 17

        /** Maximální počet znaků originálu na jedno API volání - drží výstup pod limitem max_tokens. */
        private const val NOVEL_CHUNK_CHAR_LIMIT = 2500

        /**
         * Maximální součet délky bublinových textů (přes všechny stránky v jedné dávce)
         * pro [translateChapter]/[chunkPages] - nižší než [NOVEL_CHUNK_CHAR_LIMIT], protože
         * odpověď na jednu bublinu nese original+translated+syllable_breaks+notes (několik
         * násobků vstupní délky) plus JSON obálku, ne jen jeden přeložený odstavec.
         *
         * Zvednuto z 1200 (odhad: JSON obálka ~105 znaků/bublinu + cca 3x vstupní délka na
         * obsah bubliny dává při 1800 znacích originálu kolem 1700 tokenů výstupu - pořád
         * bezpečně pod nejnižším max_tokens stropem v proxy, 4096 u Groq/OpenRouter). Cíl:
         * míň API volání na stejný obsah kapitoly, tedy míň opakovaně placené ~3000tokenové
         * "daně" za systémový prompt (viz [GeminiUltraPrompt.buildSystemPrompt]) - ten se
         * posílá znovu při KAŽDÉM volání bez ohledu na velikost dávky, takže se draze
         * amortizuje jen tím, kolik bublin/stránek se do jedné dávky vejde. Pokud by odhad
         * byl moc optimistický a odpověď modelu se začala řezat, appka to odhalí přes
         * existující Crashlytics hlášení neparsovatelné odpovědi (viz
         * [GeminiTranslateClient.translateBubbles] `e.report(...)`), ne tichým selháním.
         */
        private const val CHAPTER_CHUNK_CHAR_LIMIT = 1800

        /** Kolik stránek smí [translateChapter] OCR-ovat souběžně - ML Kit recognizery jsou
         *  sdílené instance a plné rozlišení víc stránek najednou v paměti by zbytečně
         *  riskovalo OOM na slabších telefonech. */
        private const val OCR_PARALLELISM = 3

        /** Kolik stránek smí [translateChapter] stahovat (přes [PageBitmapLoader]) souběžně -
         *  čistě síťové I/O čekání, snese vyšší souběžnost než samotné OCR ([OCR_PARALLELISM]). */
        private const val BITMAP_LOAD_PARALLELISM = 5

        /**
         * Tvrdý strop na stažení bitmapy + OCR JEDNÉ stránky (viz [translateChapter],
         * [translatePage], [getCachedPage] migrace).
         *
         * Bez něj mohla jediná stránka s pomalou/mrtvou síťovou odpovědí viset neomezeně
         * dlouho: OkHttp klient má 30s connect + 30s read timeout a RetryInterceptor to
         * opakuje 3x (viz AppModule), takže jeden nešťastný obrázek dokázal zablokovat
         * celý požadavek až 180 sekund - a [translateChapter] hlásí postup (onPageReady)
         * teprve AŽ PO dokončení OCR úplně všech stránek dávky, takže ta jedna zaseklá
         * stránka zamrazila ukazatel na 0/N pro CELOU kapitolu, i kdyby zbytek dávno
         * doběhl (viz uživatelská zpětná vazba "3 minuty prekladam a porad 0/11" -
         * 3 minuty odpovídají přesně tomu nejhoršímu případu 30s×2×3).
         *
         * 40 sekund je dost pro pomalou mobilní síť, ale citelně pod oněmi 180s - stránka,
         * která tenhle strop nestihne, se prostě přeskočí jako bez textu místo toho, aby
         * appka vypadala zaseklá donekonečna.
         */
        private const val PAGE_OCR_TIMEOUT_MILLIS = 40_000L
    }

    // ── Light novel překlad (prostý text, ne obrázek) ────────────────────────

    /**
     * Přeloží celou kapitolu light novel (odstavce oddělené \n). Rozdělí dlouhý text
     * do více dávek, aby výstup nepřekročil limit tokenů jednoho API volání.
     * @return přeložený text (odstavce spojené \n) nebo null při selhání
     */
    suspend fun translateNovelChapter(
        chapterId: String,
        mangaId: String,
        text: String,
        targetLanguage: String = "Czech",
        sourceLanguage: String = "Auto",
    ): String? {
        getCachedNovel(chapterId, targetLanguage, sourceLanguage)?.let { return it }
        if (!groqClient.isConfigured) return null

        val paragraphs = text.split("\n").filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) return null

        val glossary = glossaryFor(mangaId, targetLanguage)
        // Stejný kontext (název/typ/žánry) jako manga cesta - novely jsou v mangaDao vedené
        // stejně (contentType = "NOVEL"), jen se odsud dřív nikdy neposílal, takže si model
        // musel domýšlet, jestli překládá temné fantasy nebo komedii. Viz mediumRules("NOVEL").
        val mangaContext = mangaContextFor(mangaId)
        // Odstavce se nikdy nedělí NAPŘÍČ dávkami (viz chunkUnits) - jediná výjimka je jeden
        // odstavec delší než limit sám o sobě, ten se rozseká na věty (viz toTranslationUnits),
        // nikdy uprostřed věty.
        val units = toTranslationUnits(paragraphs)
        val chunks = chunkUnits(units)
        val translatedUnits = mutableListOf<String>()
        for (chunk in chunks) {
            val texts = chunk.map { it.text }
            // Na rozdíl od manga cesty tu dřív nebyl ŽÁDNÝ fallback - vyčerpaná denní kvóta
            // Groq free tieru (přesně scénář, pro který vznikla ProviderHealth) rovnou
            // shodila celý překlad novely, i kdyby byl OpenRouter volný. Stejný vzor jako
            // poslední čtyři kroky manga řetězce (translateWithGroq "groq"->"openrouter"->
            // "cerebras"->"mistral").
            var translated = groqClient.translateNovelBatch(texts, targetLanguage, sourceLanguage, glossary, provider = "groq", mangaContext = mangaContext)
            if (translated.size != chunk.size) {
                translated = groqClient.translateNovelBatch(texts, targetLanguage, sourceLanguage, glossary, provider = "openrouter", mangaContext = mangaContext)
            }
            if (translated.size != chunk.size) {
                translated = groqClient.translateNovelBatch(texts, targetLanguage, sourceLanguage, glossary, provider = "cerebras", mangaContext = mangaContext)
            }
            if (translated.size != chunk.size) {
                translated = groqClient.translateNovelBatch(texts, targetLanguage, sourceLanguage, glossary, provider = "mistral", mangaContext = mangaContext)
            }
            if (translated.size != chunk.size) return null // dávka selhala nebo neúplná -> necachovat polovičatý výsledek
            translatedUnits += translated
        }

        // Rekonstrukce odstavců: "continuation" kousky (části jednoho moc dlouhého odstavce
        // rozdělené podle vět, viz toTranslationUnits) se spojí zpátky mezerou do JEDNOHO
        // odstavce - teprve mezi SKUTEČNÝMI odstavci jde nový řádek.
        val resultParagraphs = mutableListOf<StringBuilder>()
        units.forEachIndexed { i, unit ->
            if (unit.isContinuation && resultParagraphs.isNotEmpty()) {
                resultParagraphs.last().append(" ").append(translatedUnits[i])
            } else {
                resultParagraphs += StringBuilder(translatedUnits[i])
            }
        }
        val result = resultParagraphs.joinToString("\n") { it.toString() }
        novelDao.upsert(TranslatedNovelEntity(id = novelCacheId(chapterId, sourceLanguage, targetLanguage), translatedText = result))
        return result
    }

    suspend fun getCachedNovel(chapterId: String, targetLanguage: String, sourceLanguage: String = "Auto"): String? =
        novelDao.getById(novelCacheId(chapterId, sourceLanguage, targetLanguage))?.translatedText

    /**
     * Klíč cache přeložených novel. [PIPELINE_VERSION] tu dřív CHYBĚL, i když ho klíč
     * stránek má odjakživa - překlady novel se proto po opravě promptu nikdy nepřepočítaly
     * a uživatel viděl starou, rozbitou verzi navždycky. Přesně tomu mělo verzování zabránit.
     */
    internal fun novelCacheId(chapterId: String, sourceLanguage: String, targetLanguage: String) =
        "$chapterId::$sourceLanguage::$targetLanguage::v$PIPELINE_VERSION"

    /**
     * Jeden "kousek" poslaný k překladu jako samostatná položka dávky. Normální (krátký)
     * odstavec je jeden unit s [isContinuation]=false. Odstavec delší než
     * [NOVEL_CHUNK_CHAR_LIMIT] se rozseká na věty ([splitAtSentenceBoundaries]) do víc units -
     * první má isContinuation=false (začíná nový odstavec), zbytek true (patří k tomu samému
     * odstavci, při skládání výsledku zpátky se spojí mezerou, ne novým řádkem).
     */
    private data class TranslationUnit(val text: String, val isContinuation: Boolean)

    private fun toTranslationUnits(paragraphs: List<String>): List<TranslationUnit> {
        val units = mutableListOf<TranslationUnit>()
        for (p in paragraphs) {
            if (p.length <= NOVEL_CHUNK_CHAR_LIMIT) {
                units += TranslationUnit(p, isContinuation = false)
            } else {
                splitAtSentenceBoundaries(p, NOVEL_CHUNK_CHAR_LIMIT).forEachIndexed { i, piece ->
                    units += TranslationUnit(piece, isContinuation = i > 0)
                }
            }
        }
        return units
    }

    /**
     * Rozdělí text na konce vět (. ! ?) a hladově balí do kusů pod [limit] - NIKDY neuseknuté
     * uprostřed věty. Když ani jedna věta sama o sobě nevejde do limitu (extrémně dlouhá věta
     * bez interpunkce), vrátí ji jako jeden předimenzovaný kus - radši jedno moc velké API
     * volání než rozseknutá věta v půlce.
     */
    private fun splitAtSentenceBoundaries(text: String, limit: Int): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        if (sentences.size <= 1) return listOf(text)

        val pieces = mutableListOf<String>()
        val current = StringBuilder()
        for (s in sentences) {
            if (current.isNotEmpty() && current.length + s.length + 1 > limit) {
                pieces += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(" ")
            current.append(s)
        }
        if (current.isNotEmpty()) pieces += current.toString()
        return pieces
    }

    private fun chunkUnits(units: List<TranslationUnit>): List<List<TranslationUnit>> {
        val chunks = mutableListOf<List<TranslationUnit>>()
        var current = mutableListOf<TranslationUnit>()
        var currentLen = 0
        for (u in units) {
            if (current.isNotEmpty() && currentLen + u.text.length > NOVEL_CHUNK_CHAR_LIMIT) {
                chunks += current
                current = mutableListOf()
                currentLen = 0
            }
            current += u
            currentLen += u.text.length
        }
        if (current.isNotEmpty()) chunks += current
        return chunks
    }

    // ── JSON (de)serialization ───────────────────────────────────────────────

    private fun List<TranslatedBlock>.serialize(): String = JSONArray().also { arr ->
        forEach { b ->
            arr.put(JSONObject().apply {
                put("orig", b.originalText)
                put("trans", b.translatedText)
                put("disp", b.displayText)
                put("bg", b.bgColorArgb)
                put("bgBottom", b.bgColorBottomArgb)
                put("sfx", b.isSfx)
                put("lc", b.lineCount)
                put("type", b.bubbleType.name)
                put("untrans", b.isUntranslated)
                put("bgUniform", b.bgUniform)
                put("nlh", b.nativeLineHeightF.toDouble())
                b.shape?.let { shape ->
                    put("shape", JSONArray().apply {
                        shape.forEach { p ->
                            put(JSONArray().apply { put(p.yF.toDouble()); put(p.leftF.toDouble()); put(p.rightF.toDouble()) })
                        }
                    })
                }
                // put(String, float) na Android org.json.JSONObject neexistuje (jen desktopová
                // verze knihovny) -> NoSuchMethodError za běhu. Double overload existuje vždy.
                put("l", b.leftF.toDouble())
                put("t", b.topF.toDouble())
                put("r", b.rightF.toDouble())
                put("b", b.bottomF.toDouble())
            })
        }
    }.toString()

    /** disp/bg/sfx/lc/shape/type chybí ve starších cache záznamech - optXxx s výchozí hodnotou stejnou jako [TranslatedBlock] defaults, ať se nic nerozbije. */
    private fun TranslatedPageEntity.deserialize(): List<TranslatedBlock> = try {
        val arr = JSONArray(blocksJson)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val translated = o.getString("trans")
            val shapeArr = o.optJSONArray("shape")
            val shape = if (shapeArr != null) {
                List(shapeArr.length()) { j ->
                    val p = shapeArr.getJSONArray(j)
                    BubbleShapePoint(yF = p.getDouble(0).toFloat(), leftF = p.getDouble(1).toFloat(), rightF = p.getDouble(2).toFloat())
                }
            } else null
            TranslatedBlock(
                originalText = o.getString("orig"),
                translatedText = translated,
                leftF = o.getDouble("l").toFloat(),
                topF = o.getDouble("t").toFloat(),
                rightF = o.getDouble("r").toFloat(),
                bottomF = o.getDouble("b").toFloat(),
                displayText = o.optString("disp", translated),
                bgColorArgb = if (o.has("bg")) o.getInt("bg") else DEFAULT_BUBBLE_BG_ARGB,
                // Starší cache záznamy nemají "bgBottom" - fallback na horní barvu (stejné
                // chování jako TranslatedBlock default), takže degradují na plnou barvu bez
                // gradientu místo pádu, dokud se stránka znovu nepřeloží.
                bgColorBottomArgb = o.optInt("bgBottom", if (o.has("bg")) o.getInt("bg") else DEFAULT_BUBBLE_BG_ARGB),
                isSfx = o.optBoolean("sfx", false),
                lineCount = o.optInt("lc", 1),
                shape = shape,
                bubbleType = try { BubbleType.valueOf(o.optString("type", "SPEECH")) } catch (e: Exception) { BubbleType.SPEECH },
                isUntranslated = o.optBoolean("untrans", false),
                // Starší cache záznamy nemají "bgUniform" - default true (rovnoměrné pozadí)
                // odpovídá chování PŘED touhle změnou (heuristika roztahovala box stejně
                // štědře pro všechny bloky bez tvaru), takže staré záznamy vypadají stejně,
                // dokud se stránka znovu nepřeloží.
                bgUniform = o.optBoolean("bgUniform", true),
                // Starší cache záznamy nemají "nlh" - default 0f (neznámá nativní velikost)
                // znamená, že fitter spadne na dřívější chování (hledej rovnou největší
                // velikost, co se vejde), dokud se stránka znovu nepřeloží.
                nativeLineHeightF = o.optDouble("nlh", 0.0).toFloat(),
            )
        }
    } catch (e: Exception) {
        // Poškozený/nečitelný cache záznam. Prázdný seznam je správná reakce (stránka se
        // přeloží znovu), ale tiše to spolknout znamenalo, že se rozbitá serializace nikdy
        // neprojevila jinak než "překlad se občas záhadně dělá znovu".
        e.report("translate:cache:deserialize")
        emptyList()
    }
}

/**
 * Zkusí [steps] popořadě - výsledek prvního, co vrátí ne-null seznam, se použije. Na
 * rozdíl od prostého řetězce `?:` (dřívější řešení) [RateLimitedException] z JEDNOHO
 * kroku už neznamená okamžitý konec: Gemini/Groq/OpenRouter jsou tři nezávislé komerční
 * služby s vlastní kvótou, 429 z proxy je jen JEJÍ VLASTNÍ limit počtu požadavků (viz
 * komentář u [RateLimitedException]), ne důkaz, že mají vyčerpáno i zbylé dva kroky -
 * "první rate limit = vzdej to" tak zbytečně promarnilo kapacitu, kterou další krok
 * třeba ještě měl.
 *
 * [RateLimitedException] se propaguje dál JEN když byly rate-limited (nebo selhaly)
 * úplně všechny kroky - `ReaderViewModel` na ni má vlastní hlášku a měl by ji dostat
 * pořád, jen ne už po prvním neúspěchu.
 *
 * Top-level (ne metoda [TranslateRepository]) - jde tak otestovat čistě na dvojici
 * fake suspend lambd, bez nutnosti mockovat celý repository se všemi závislostmi.
 */
/**
 * Rozdělí JEDNU dávkovou odpověď zpátky po stránkách podle toho, kolik bublin která stránka
 * poslala. [pageIndices] a [bubbleCounts] jsou dvě strany téhož - i-tá stránka poslala
 * i-tý počet bublin.
 *
 * Vytaženo z [TranslateRepository.translateChapter] kvůli testům: tohle je přesně to místo,
 * kde se text může tiše ztratit nebo (hůř) přesunout k cizí bublině, a přitom to bylo
 * schované uvnitř 90řádkové suspend funkce se sítí, OCR i databází, takže se to nedalo
 * otestovat jinak než celou kapitolou.
 *
 * Když je [blocks] kratší, než součet počtů (model odpověděl méně, než dostal), dostanou
 * chybějící stránky prázdný seznam místo výjimky - nepřeložená stránka je pořád lepší než
 * spadlý překlad celé kapitoly.
 */
internal fun splitBlocksByPage(
    pageIndices: List<Int>,
    bubbleCounts: List<Int>,
    blocks: List<TranslatedBlock>,
): List<Pair<Int, List<TranslatedBlock>>> {
    var offset = 0
    return pageIndices.mapIndexed { i, pageIndex ->
        val count = bubbleCounts[i]
        val from = offset.coerceAtMost(blocks.size)
        val to = (offset + count).coerceAtMost(blocks.size)
        offset += count
        pageIndex to blocks.subList(from, to)
    }
}

internal suspend fun translateChain(vararg steps: suspend () -> List<TranslatedBlock>?): List<TranslatedBlock> {
    var anyRateLimited = false
    for (step in steps) {
        try {
            val result = step()
            if (result != null) return result
        } catch (e: RateLimitedException) {
            anyRateLimited = true
        }
    }
    if (anyRateLimited) throw RateLimitedException()
    return emptyList()
}
