package com.haise.jiyu.source

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

/**
 * Detailní živý audit všech NOVEL zdrojů - proti SKUTEČNÝM webům, ve výchozím stavu
 * přeskočen (`-Djiyu.live=true` zapne, `-Djiyu.live.filter=ext:` omezí na prefix id).
 * Nic neasertuje - výsledkem je tabulka `build/reports/novel-sources-audit.md`.
 *
 * Na rozdíl od [LiveSourceSmokeTest] (jeden rychlý průchod) měří:
 *  - Popular: strana 1 (čas, tituly, kolik má coverUrl), scroll strany 2-3 (nekonečno?)
 *  - Latest: strany 1-2 (sortBy="latest")
 *  - Search: hledání podle názvu titulu z popular feedu
 *  - Tagy: getAvailableTags + listing s jedním tagem (jako Filtry v appce)
 *  - 3 tituly: getMangaDetails + getChapterList + getPageList nejnovější kapitoly
 *  - Cover GET: až 3 obálky z feedu s Referer hlavičkou (HTTP kód + velikost)
 *
 * POZOR: stejný caveat jako u smoke testu - holý OkHttpClient bez appčích
 * interceptorů (CF řešič, DoH, rate-limity), takže FAIL může být artefakt.
 */
class LiveNovelSourceAuditTest {

    private val client = LiveSourceHarness.newClient()

    private data class Timed<T>(val ms: Long, val value: T)

    private suspend fun <T> timed(block: suspend () -> T): Timed<T> {
        var box: Any? = null
        val ms = measureTimeMillis { box = block() }
        @Suppress("UNCHECKED_CAST")
        return Timed(ms, box as T)
    }

    private class ListingStep(val ms: Long, val count: Int, val covers: Int, val dupesWithPrev: Boolean)

    private class TitleAudit(
        val title: String,
        val detailMs: Long,
        val chaptersMs: Long,
        val chapterCount: Int,
        val error: String?,
    )

    private class SourceReport(
        val id: String, val name: String, val lang: String,
        var status: String = "OK",
        var pop1: ListingStep? = null,
        var pop2: ListingStep? = null,
        var pop3: ListingStep? = null,
        var lat1: ListingStep? = null,
        var lat2: ListingStep? = null,
        var searchMs: Long? = null,
        var searchCount: Int? = null,
        var searchTerm: String = "",
        var searchFoundTitle: Boolean? = null,
        var tagsCount: Int? = null,
        var tagsMs: Long? = null,
        var tagFilterMs: Long? = null,
        var tagFilterCount: Int? = null,
        var tagUsed: String = "",
        val titles: MutableList<TitleAudit> = mutableListOf(),
        var pagesMs: Long? = null,
        var pagesCount: Int? = null,
        val coverChecks: MutableList<String> = mutableListOf(),
        var totalMs: Long = 0,
        val notes: MutableList<String> = mutableListOf(),
    )

    @Test
    fun `live audit of all NOVEL sources`() {
        assumeTrue("živý audit se spouští jen s -Djiyu.live=true", System.getProperty("jiyu.live") == "true")

        val prefix = System.getProperty("jiyu.live.filter").orEmpty()
        val sources = LiveSourceHarness.buildSources(client)
            .filter { it.contentType == "NOVEL" }
            .filter { prefix.isEmpty() || it.id.startsWith(prefix) }
        println("NOVEL zdrojů k auditu: ${sources.size}")

        val gate = Semaphore(6)
        val reports = runBlocking {
            sources.map { src ->
                async(Dispatchers.IO) { gate.withPermit { audit(src).also { println("[done] ${src.id} -> ${it.status} (${it.totalMs} ms)") } } }
            }.awaitAll()
        }.sortedBy { it.id }

        val report = buildReport(reports)
        val out = File("build/reports/novel-sources-audit.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        println(report)
    }

    // ── audit jednoho zdroje ────────────────────────────────────────────────

    private suspend fun audit(src: MangaSource): SourceReport {
        val r = SourceReport(src.id, src.name, src.language)
        val total = measureTimeMillis {
            try {
                withTimeout(240_000) { auditBody(src, r) }
            } catch (e: Throwable) {
                r.status = if (LiveSourceHarness.isCloudflare(e)) "CLOUDFLARE" else "FAIL"
                r.notes += "celková chyba: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
            }
        }
        r.totalMs = total
        if (r.status == "OK" && r.notes.isNotEmpty()) r.status = "WARN"
        return r
    }

    private suspend fun auditBody(src: MangaSource, r: SourceReport) {
        // ── Popular strana 1 ────────────────────────────────────────────────
        val pop1 = try {
            timed { src.getPopular(1) }.also { t ->
                r.pop1 = ListingStep(t.ms, t.value.size, t.value.count { !it.coverUrl.isNullOrBlank() }, false)
            }
        } catch (e: Throwable) {
            r.status = if (LiveSourceHarness.isCloudflare(e)) "CLOUDFLARE" else "FAIL"
            r.notes += "popular p1: ${e.javaClass.simpleName}: ${e.message?.take(100)}"
            return // bez výpisu nemá zbytek smysl
        }
        if (pop1.value.isEmpty()) {
            r.status = "FAIL"; r.notes += "popular p1 prázdný"; return
        }
        val pop1Urls = pop1.value.map { it.url }.toSet()

        // ── Popular scroll (strany 2 a 3) - "nekonečný" scroll ─────────────
        for (page in 2..3) {
            val step = runCatching { timed { src.getPopular(page) } }
                .getOrElse {
                    r.notes += "popular p$page: ${it.javaClass.simpleName}: ${it.message?.take(80)}"
                    null
                }
            val urls = step?.value?.map { it.url }?.toSet().orEmpty()
            val listing = step?.let { ListingStep(it.ms, it.value.size, it.value.count { m -> !m.coverUrl.isNullOrBlank() }, urls.isNotEmpty() && urls == pop1Urls) }
            if (page == 2) r.pop2 = listing else r.pop3 = listing
            if (step != null && step.value.isEmpty()) r.notes += "popular p$page prázdná (scroll končí na p${page - 1})"
            if (listing?.dupesWithPrev == true) r.notes += "popular p$page == p1 (scroll se opakuje!)"
        }

        // ── Latest strany 1-2 (sortBy=latest) ───────────────────────────────
        val latestFilter = MangaFilter(sortBy = "latest")
        var lat1Urls = emptySet<String>()
        var lat1Ordered = emptyList<String>()
        for (page in 1..2) {
            val step = runCatching { timed { src.getPopular(page, latestFilter) } }
                .getOrElse {
                    r.notes += "latest p$page: ${it.javaClass.simpleName}: ${it.message?.take(80)}"
                    null
                }
            val urls = step?.value?.map { it.url }?.toSet().orEmpty()
            val listing = step?.let { ListingStep(it.ms, it.value.size, it.value.count { m -> !m.coverUrl.isNullOrBlank() }, urls.isNotEmpty() && urls == lat1Urls) }
            if (page == 1) { r.lat1 = listing; lat1Urls = urls; lat1Ordered = step?.value?.map { it.url }.orEmpty() } else r.lat2 = listing
            if (page == 2 && step != null && step.value.isEmpty()) r.notes += "latest p2 prázdná"
            if (listing?.dupesWithPrev == true) r.notes += "latest p2 == p1"
        }
        // Porovnavat musime serazene SEZNAMY, ne mnoziny - u malych katalogu
        // se shoduje mnozina titulu na p1 i kdyz razeni funguje (jiné poradi).
        // A hlasku davame jen zdrojum, ktere razeni deklaruji (UI prepinac) -
        // u supportsSortOrder=false je shoda zamerna, ne chyba.
        if (src.supportsSortOrder && lat1Ordered.isNotEmpty() &&
            lat1Ordered == pop1.value.map { it.url }
        ) {
            r.notes += "latest == popular (zdroj řazení nerozlišuje)"
        }

        // ── Hledání podle titulu z popular feedu ────────────────────────────
        val probe = pop1.value.first().title
        val term = probe.split(Regex("\\s+")).firstOrNull { it.length >= 4 && it.first().isLetter() }
            ?: probe.split(Regex("\\s+")).firstOrNull().orEmpty()
        r.searchTerm = term
        if (term.isNotBlank()) {
            try {
                val s = timed { src.search(term, 1, MangaFilter()) }
                r.searchMs = s.ms
                r.searchCount = s.value.size
                r.searchFoundTitle = s.value.any { it.title.equals(probe, ignoreCase = true) }
                if (s.value.isEmpty()) r.notes += "search '$term' = 0 výsledků"
            } catch (e: UnsupportedOperationException) {
                r.notes += "search není podporovaný"
            } catch (e: Throwable) {
                r.notes += "search: ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            }
        }

        // ── Tagy ────────────────────────────────────────────────────────────
        if (src.supportsTagFilter) {
            try {
                val tags = timed { src.getAvailableTags() }
                r.tagsMs = tags.ms
                r.tagsCount = tags.value.size
                // Web muze mit taxonomii, kde nektere zanry jsou legitimne
                // prazdne (napr. "chinese" na novel-short.com) - proto zkousime
                // az 3 tagy a prvni neprazdny vysledek bereme jako dukaz, ze
                // filtr funguje. "0 vysledku" se hlasi jen kdyz jsou prazdne
                // vsechny vyzkousene.
                var tried = 0
                var totalFilteredMs = 0L
                var lastCount = 0
                var lastLabel: String? = null
                for (tag in tags.value.take(3)) {
                    tried++
                    lastLabel = tag.label
                    try {
                        val filtered = timed { src.getPopular(1, MangaFilter(genres = listOf(tag.id))) }
                        totalFilteredMs += filtered.ms
                        lastCount = filtered.value.size
                        if (filtered.value.isNotEmpty()) {
                            r.tagUsed = tag.label
                            break
                        }
                    } catch (e: Throwable) {
                        r.notes += "tag filtr: ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                        break
                    }
                }
                if (tried > 0) {
                    r.tagFilterMs = totalFilteredMs
                    r.tagFilterCount = lastCount
                    if (r.tagUsed.isBlank()) r.tagUsed = lastLabel ?: ""
                    if (lastCount == 0) r.notes += "tag filtr = 0 výsledků ($tried zkoušené tagy prázdné)"
                }
                if (tags.value.isEmpty()) r.notes += "getAvailableTags = 0 tagů"
            } catch (e: Throwable) {
                r.notes += "tagy: ${e.javaClass.simpleName}: ${e.message?.take(80)}"
            }
        }

        // ── 3 tituly: detail + kapitoly ─────────────────────────────────────
        var firstChapterPagesDone = false
        for (manga in pop1.value.take(3)) {
            try {
                val d = timed { src.getMangaDetails(manga) }
                val c = timed { src.getChapterList(d.value) }
                r.titles += TitleAudit(manga.title.take(50), d.ms, c.ms, c.value.size, null)
                if (c.value.isEmpty()) r.notes += "'${manga.title.take(30)}' má 0 kapitol"
                // U prvního titulu změř i stránky nejnovější kapitoly
                if (!firstChapterPagesDone && c.value.isNotEmpty()) {
                    firstChapterPagesDone = true
                    try {
                        val p = timed { src.getPageList(c.value.first()) }
                        r.pagesMs = p.ms; r.pagesCount = p.value.size
                    } catch (e: Throwable) {
                        r.notes += "pageList: ${e.javaClass.simpleName}: ${e.message?.take(80)}"
                    }
                }
            } catch (e: Throwable) {
                r.titles += TitleAudit(manga.title.take(50), -1, -1, -1, "${e.javaClass.simpleName}: ${e.message?.take(60)}")
            }
        }

        // ── Covery: GET až 3 z feedu (Referer jako v appce) ─────────────────
        // Bez explicitniho User-Agent - Coil na obrazky vlastni UA neposila,
        // takze jde OkHttp vychozi "okhttp/4.x" a audit ma odpovidat realite
        // (u nekterych CF webu je desktop UA na /wp-content/ blokovany, zatimco
        // okhttp UA projde - desktop UA by tu reportoval falesne 403).
        for (manga in pop1.value.filter { !it.coverUrl.isNullOrBlank() }.take(3)) {
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    client.newCall(
                        Request.Builder().url(manga.coverUrl!!)
                            .header("Referer", src.homepageUrl ?: manga.coverUrl!!)
                            .get().build(),
                    ).execute().use { resp -> "${resp.code}/${(resp.body?.contentLength() ?: -1) / 1024}kB" }
                }.getOrElse { "ERR:${it.javaClass.simpleName}" }
            }
            r.coverChecks += code
        }
        if (r.coverChecks.isEmpty()) r.notes += "žádný coverUrl v listingu"
    }

    // ── report ──────────────────────────────────────────────────────────────

    private fun fmt(step: ListingStep?): String = when {
        step == null -> "-"
        step.count == 0 -> "prázdná ${step.ms}ms"
        else -> "${step.count}t/${step.covers}c ${step.ms}ms" + if (step.dupesWithPrev) " DUP!" else ""
    }

    private fun buildReport(reports: List<SourceReport>): String = buildString {
        appendLine("# Živý audit NOVEL zdrojů")
        appendLine()
        val counts = reports.groupingBy { it.status }.eachCount().toSortedMap()
        appendLine("Zdrojů: ${reports.size} - " + counts.entries.joinToString(", ") { "${it.key}: ${it.value}" })
        appendLine("Legenda: `Nt/Nc Xms` = titulů/covery za X ms; '-' = krok se nepovedl; DUP! = stránka vrací stejná URL jako předchozí")
        appendLine()

        appendLine("## Souhrn")
        appendLine()
        appendLine("| Zdroj | Lang | Stav | Pop p1 | Pop p2 | Pop p3 | Lat p1 | Lat p2 | Search | Tagy | Celkem |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in reports) {
            val search = when {
                r.searchMs == null -> "-"
                else -> "${r.searchCount}t ${r.searchMs}ms" + (if (r.searchFoundTitle == true) " OK" else "")
            }
            val tags = when {
                r.tagsCount == null -> "n/a"
                else -> "${r.tagsCount}tagů" + (r.tagFilterCount?.let { " -> ${it}t" } ?: "")
            }
            appendLine("| ${r.name} (`${r.id}`) | ${r.lang} | ${r.status} | ${fmt(r.pop1)} | ${fmt(r.pop2)} | ${fmt(r.pop3)} | ${fmt(r.lat1)} | ${fmt(r.lat2)} | $search | $tags | ${r.totalMs}ms |")
        }
        appendLine()

        appendLine("## Detaily (tituly, kapitoly, stránky, covery)")
        appendLine()
        for (r in reports) {
            appendLine("### ${r.name} (`${r.id}`) - ${r.status}, ${r.totalMs}ms")
            r.titles.forEachIndexed { i, t ->
                if (t.error != null) {
                    appendLine("- titul ${i + 1} \"${t.title}\": CHYBA ${t.error}")
                } else {
                    appendLine("- titul ${i + 1} \"${t.title}\": detail ${t.detailMs}ms, kapitoly ${t.chaptersMs}ms (${t.chapterCount} kap.)")
                }
            }
            if (r.pagesCount != null) appendLine("- pageList nejnovější kapitoly: ${r.pagesCount} str., ${r.pagesMs}ms")
            if (r.coverChecks.isNotEmpty()) appendLine("- cover GET: ${r.coverChecks.joinToString(", ")}")
            if (r.searchTerm.isNotBlank()) appendLine("- search term: \"${r.searchTerm}\"" + (r.searchFoundTitle?.let { if (it) " (titul nalezen)" else " (titul NEnalezen)" } ?: ""))
            if (r.tagUsed.isNotBlank()) appendLine("- tag test: \"${r.tagUsed}\"")
            r.notes.forEach { appendLine("- ! $it") }
            appendLine()
        }
    }
}
