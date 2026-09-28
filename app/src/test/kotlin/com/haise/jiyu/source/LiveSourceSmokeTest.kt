package com.haise.jiyu.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Živý smoke test všech zdrojů registrovaných v [SourceManager] - proti SKUTEČNÝM webům, proto se ve
 * výchozím stavu přeskočí (`-Djiyu.live=true` ho zapne, viz `.github/workflows/live-sources.yml`). Nic
 * neasertuje: výsledek je tabulka `build/reports/live-sources.md` (OK / WARN / FAIL / CLOUDFLARE / SKIP),
 * ze které je vidět, který zdroj se rozbil, dřív než si toho všimne uživatel. Jde o vzor "živé kontraktní
 * testy" z kotatsu-parsers.
 *
 * Kroky pro každý zdroj: výpis (strana 1), strana 2 se liší od strany 1, detail prvního titulu, seznam
 * kapitol, stránky nejnovější kapitoly a HEAD/GET prvního obrázku. Zdroje za Cloudflare výzvou (v JVM bez
 * WebView nelze projít) se značí CLOUDFLARE, ne FAIL.
 *
 * POZOR: test používá holý OkHttpClient (bez Cloudflare řešiče, DoH, limitů požadavků a hotlink hlaviček z
 * `AppModule`), takže FAIL může znamenat i to, že web vyžaduje appčí interceptory - je to vodítko, ne verdikt.
 *
 * Zdroje se skládají tak, jak je skládá Hilt: SourceManager se vytvoří reflexí, jeho parametry-zdroje se
 * postaví z konstruktoru s `OkHttpClient`, cokoli jiného je mock (takový zdroj se přeskočí).
 */
class LiveSourceSmokeTest {

    private val client = LiveSourceHarness.newClient()

    private class Row(val id: String, val name: String, val status: String, val detail: String, val timing: String = "")

    @Test
    fun `live smoke test of all registered sources`() {
        assumeTrue("živý test se spouští jen s -Djiyu.live=true", System.getProperty("jiyu.live") == "true")

        // -Djiyu.live.filter=ext: omezí test na zdroje, jejichž id tímhle začíná (např. jen rozšířený katalog).
        val prefix = System.getProperty("jiyu.live.filter").orEmpty()
        val sources = LiveSourceHarness.buildSources(client).filter { prefix.isEmpty() || it.id.startsWith(prefix) }
        val gate = Semaphore(6)
        val rows = runBlocking {
            sources.map { src ->
                async(Dispatchers.IO) { gate.withPermit { check(src) } }
            }.awaitAll()
        }.sortedWith(compareBy({ it.status != "FAIL" }, { it.status != "WARN" }, { it.id }))

        val report = buildString {
            appendLine("# Živý test zdrojů")
            appendLine()
            val counts = rows.groupingBy { it.status }.eachCount().toSortedMap()
            appendLine("Zdrojů: ${rows.size} — " + counts.entries.joinToString(", ") { "${it.key}: ${it.value}" })
            appendLine()
            appendLine("| Stav | Zdroj | Časy (ms) | Detail |")
            appendLine("|---|---|---|---|")
            rows.forEach { appendLine("| ${it.status} | ${it.name} (`${it.id}`) | ${it.timing} | ${it.detail.replace("|", "/").take(140)} |") }
        }
        val out = File("build/reports/live-sources.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        // Seznam ověřených zdrojů (OK/WARN) pro generátor rozšířeného katalogu.
        File("build/reports/live-vetted.txt").writeText(rows.filter { it.status == "OK" || it.status == "WARN" }.joinToString("\n") { it.id })
        println(report)
    }

    // ── kontrola jednoho zdroje ──────────────────────────────────────────────

    private suspend fun check(src: MangaSource): Row {
        val warnings = mutableListOf<String>()
        // Časy jednotlivých kroků - uživatel se ptá "jak dlouho trvá načtení",
        // tak je report doplňuje (listing / detail / kapitoly / stránky / obrázek).
        val times = linkedMapOf<String, Long>()
        suspend fun <T> timed(name: String, block: suspend () -> T): T {
            val t0 = System.currentTimeMillis()
            return block().also { times[name] = System.currentTimeMillis() - t0 }
        }
        fun timing() = times.entries.joinToString(" ") { (k, ms) -> "$k=${ms}ms" }
        fun row(status: String, detail: String) = Row(src.id, src.name, status, detail, timing())
        // GET na adresu, vrati (HTTP kod, Content-Type) - pro cover i strankove obrazky.
        // Referer se pocita stejne jako HotlinkRefererInterceptor v AppModule
        // (CDN host -> referer zdroje); bez toho hlasi hotlink-CDN 403 falesne WARNy.
        suspend fun probe(url: String, referer: String?): Pair<Int, String?> = withContext(Dispatchers.IO) {
            runCatching {
                val host = url.toHttpUrlOrNull()?.host.orEmpty()
                val hotlink = com.haise.jiyu.di.hotlinkReferers[host]
                    ?: com.haise.jiyu.di.hotlinkRefererSuffixes.entries
                        .find { (s, _) -> host == s || host.endsWith(".$s") }?.value
                    ?: com.haise.jiyu.di.hotlinkRefererPrefixes.entries
                        .find { (p, _) -> host.startsWith(p) }?.value
                val effReferer = hotlink ?: referer
                client.newCall(
                    Request.Builder().url(url).header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                        .apply { if (effReferer != null) header("Referer", effReferer) }.get().build(),
                ).execute().use { it.code to it.header("Content-Type") }
            }.getOrDefault(-1 to null)
        }
        // GET vracejici (HTTP kod, telo, finalni path po redirectech) - pro detekci
        // challenge/WAF stranky pri prazdnem vypisu. Finalni path proto, ze nektere
        // gatekeepery (batcave.biz) presmerovavaji na "/_c?t=..." stranku, ktera
        // vraci 404 a zadny znamy marker v tele - poznat se da jen z cesty.
        suspend fun rawGet(url: String): Triple<Int, String, String> = withContext(Dispatchers.IO) {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", SourceHttp.USER_AGENT_DESKTOP).build(),
            ).execute().use { Triple(it.code, it.body?.string().orEmpty(), it.request.url.encodedPath) }
        }
        // Challenge/WAF stranka prochazi HTTP 200 ale neni obsah - typicke markery
        // (bez nich by se "prazdny vypis" mylne klasifikoval jako rozbiti parseru).
        // POZOR: "challenge-platform" sem NEpatri - /cdn-cgi/challenge-platform/jsd/main.js
        // Cloudflare vsteluje do KAZDE stranky za proxy, i normalniho obsahu.
        // Spolehlive jen u skutecne mezistranky: _cf_chl_opt / "Just a moment" /
        // "verify you are human" / "Enable JavaScript and cookies" / "Attention Required".
        fun looksLikeChallenge(body: String) = listOf(
            "just a moment", "_cf_chl_opt", "cf_chl_", "verify you are human",
            "enable javascript and cookies", "attention required", "turnstile-response",
            // Vlastni WAF/gatekeeper mimo Cloudflare - baozimh.org vraci JSON
            // {"error":"challenge_required","challenge_url":"/__gatekeeper_challenge/..."}.
            "challenge_required", "__gatekeeper_challenge",
        ).any { body.contains(it, ignoreCase = true) }
        return try {
            // 3 min na cely retezec - mimoradne pomale weby (mangadenizi ~40 s
            // na request, 3asq ~30 s) potrebuji vetsi budget; zdroje bezi
            // paralelne, takze se to do celkove doby auditu temer nepropadne.
            withTimeout(180_000) {
                // Zdroj oznaceny isBroken zustava v registru jen kvuli knihovne
                // (SourceManager ho nenabizi) - report ho skrtne jako SKIP, at
                // tabulka ukazuje jen realne dostupne zdroje.
                if (src.isBroken) {
                    return@withTimeout row("SKIP", src.brokenReason ?: "označen jako rozbitý")
                }
                val page1 = timed("list") { src.getPopular(1) }
                if (page1.isEmpty()) {
                    // Web muze vratit 200 s challenge HTML - parsuje se jako prazdny
                    // seznam, ale parser je v poradku; jen JVM klient za WAF neprojde.
                    // Challenge byva site-wide: kdyz je homepage cista, zkusim jeste
                    // interni odkazy - prednostne archivni cesty (manga/catalog/
                    // archive/series...), protoze WAF byva jen na nich (japscan:
                    // "/" 200, "/mangas/1/" challenge).
                    val home = src.homepageUrl
                    if (home != null) {
                        val probe = runCatching { rawGet(home) }.getOrNull()
                        if (probe == null) {
                            // Ani homepage se nenacetla - mrtvy web nebo WAF
                            // resetujici spojeni (report vyzaduje rucni triaz).
                            return@withTimeout row("FAIL", "výpis prázdný + homepage nenačtena (WAF/mrtvý web)")
                        }
                        val (code, body, finalPath) = probe
                        // "/_c" a "/__gatekeeper" cesty = custom gatekeepery
                        // (batcave.biz presmerovava na "/_c?t=..." stranku s 404
                        // a bez markeru v tele - pozna se jen z cesty).
                        fun isChallenged(code: Int, body: String, path: String) =
                            looksLikeChallenge(body) || code == 403 || code == 503 ||
                                path.startsWith("/_c") || path.startsWith("/__gatekeeper")
                        var challenged = isChallenged(code, body, finalPath)
                        if (!challenged) {
                            val links = org.jsoup.Jsoup.parse(body, home)
                                .select("a[href^=/], a[href^=$home]")
                                .mapNotNull { it.absUrl("href").ifBlank { null } }
                                .filter { it != home && it.length > home.length }
                                .distinct()
                            val archive = links.filter {
                                Regex("manga|comix|archive|catalog|series|library|directory|lecture", RegexOption.IGNORE_CASE)
                                    .containsMatchIn(it.substringAfter(home))
                            }
                            for (link in (archive + links).distinct().take(3)) {
                                val p = runCatching { rawGet(link) }.getOrNull() ?: continue
                                if (isChallenged(p.first, p.second, p.third)) { challenged = true; break }
                            }
                        }
                        if (challenged) return@withTimeout row("CLOUDFLARE", "výpis = challenge stránka (WAF)")
                    }
                    return@withTimeout row("FAIL", "výpis strany 1 je prázdný")
                }
                if (page1.map { it.url }.distinct().size != page1.size) warnings += "duplicity ve výpisu"
                runCatching { src.getPopular(2) }.onSuccess { p2 ->
                    if (p2.isNotEmpty() && p2.map { it.url } == page1.map { it.url }) warnings += "strana 2 == strana 1"
                }
                if (page1.first().coverUrl.isNullOrBlank()) warnings += "první titul bez coveru"
                // Detail -> kapitoly -> stránky se zkouší postupně na prvních 3 titulech
                // výpisu: první titul může být datová edge-case, ne chyba parseru
                // (Dynasty: série bez tagovaných kapitol; Vortex: coin-locknuté kapitoly).
                // Teprve když selžou všechny tři, je FAIL. Hozená výjimka je ale rovnou
                // FAIL i na pozdějším titulu - signalizuje rozbitý endpoint, ne data.
                var chapters: List<SChapter> = emptyList()
                var pages: List<Page> = emptyList()
                var usedIdx = -1
                // Sleduje, ze aspon jeden titul kapitoly mel - jinak by prazdny
                // posledni titul prepisoval drivejsi nalezeny seznam a hlaska
                // "seznam kapitol je prazdny" by lhala (realne selhaly stranky).
                var anyChapters = false
                for ((idx, manga) in page1.take(3).withIndex()) {
                    usedIdx = idx
                    val details = try { timed("detail") { src.getMangaDetails(manga) } }
                        catch (e: Throwable) { return@withTimeout failure(src, "detail", e, timing()) }
                    chapters = try { timed("chapters") { src.getChapterList(details) } }
                        catch (e: Throwable) { return@withTimeout failure(src, "kapitoly", e, timing()) }
                    if (chapters.isEmpty()) continue
                    anyChapters = true
                    // Krajní kapitoly bývají zamčené/placené (nejnovější) nebo preview
                    // (nejstarší) - zkouší se postupně i prostřední.
                    pages = timed("pages") { runCatching { src.getPageList(chapters.first()) }.getOrElse { emptyList() } }
                    if (pages.isEmpty() && chapters.size > 1) {
                        for (ch in listOf(chapters.last(), chapters[chapters.size / 2]).distinctBy { it.url }) {
                            pages = try { src.getPageList(ch) }
                                catch (e: Throwable) { return@withTimeout failure(src, "stránky", e, timing()) }
                            if (pages.isNotEmpty()) break
                        }
                    }
                    if (pages.isNotEmpty()) break
                }
                if (!anyChapters) return@withTimeout row("FAIL", "seznam kapitol je prázdný (3 tituly)")
                if (pages.isEmpty()) return@withTimeout row("FAIL", "kapitoly bez stránek (3 tituly)")
                if (usedIdx > 0) warnings += "čitelný až ${usedIdx + 1}. titul výpisu"
                // Sanity kapitol - prazdne url/jmena znamenaji, ze parser vraci smeti.
                if (chapters.any { it.url.isBlank() }) warnings += "kapitola s prázdným URL"
                if (chapters.all { it.name.isBlank() }) warnings += "kapitoly bez názvů"
                // Cover obalka - realny GET, ne jen pritomnost URL (hotlink/CDN umí
                // vracet 403 na platne vypadajici adrese).
                val cover = page1.firstNotNullOfOrNull { m -> m.coverUrl?.takeIf { it.startsWith("http") } }
                if (cover == null) {
                    warnings += "žádný titul bez coveru"
                } else {
                    val (cc, cct) = timed("cover") { probe(cover, src.homepageUrl) }
                    if (cc !in 200..299) warnings += "cover HTTP $cc"
                    else if (cct != null && !cct.startsWith("image/") && !cct.contains("octet-stream"))
                        warnings += "cover není obrázek ($cct)"
                }
                // Strankove obrazky: prvni i prostredni (lazy getImageUrl u zdroju
                // s virtualnimi URL - MangaHome chapterfun.ashx) + kontrola, ze
                // odpoved je skutecne obrazek, ne jen HTTP 200 s HTML.
                if (src.contentType != "NOVEL") {
                    val probes = listOf(pages.first(), pages.getOrElse(pages.size / 2) { pages.last() }).distinctBy { it.url }
                    for (p in probes) {
                        val imageUrl = runCatching { src.getImageUrl(p) }.getOrElse { p.url }
                        if (!imageUrl.startsWith("http")) { warnings += "stránka ${p.index} bez http URL"; continue }
                        val (c, ct) = timed("img") { probe(imageUrl, src.homepageUrl ?: imageUrl) }
                        if (c !in 200..299) warnings += "obrázek ${p.index} HTTP $c"
                        else if (ct != null && !ct.startsWith("image/") && !ct.contains("octet-stream"))
                            warnings += "stránka ${p.index} není obrázek ($ct)"
                    }
                }
                // Search sanity - na obycejnem dotazu nesmi hazet; prazdny vysledek
                // je legalni (adult zdroje, cizojazycne weby), vyjimka ne.
                runCatching { src.search("the", 1) }
                    .onFailure { warnings += "search hází ${it.javaClass.simpleName}" }
                if (warnings.isEmpty()) row("OK", "${page1.size} titulů, ${chapters.size} kapitol, ${pages.size} stránek")
                else row("WARN", warnings.joinToString("; "))
            }
        } catch (e: Throwable) {
            failure(src, "výpis", e, timing())
        }
    }

    private fun failure(src: MangaSource, stage: String, t: Throwable, timing: String = ""): Row {
        val status = if (LiveSourceHarness.isCloudflare(t)) "CLOUDFLARE" else "FAIL"
        return Row(src.id, src.name, status, "$stage: ${t.javaClass.simpleName}: ${t.message}", timing)
    }
}
