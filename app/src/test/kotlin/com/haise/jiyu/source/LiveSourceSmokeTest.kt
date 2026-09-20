package com.haise.jiyu.source

import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.settings.AppMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.interceptor.DomainOverrides
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private class Row(val id: String, val name: String, val status: String, val detail: String)

    @Test
    fun `live smoke test of all registered sources`() {
        assumeTrue("živý test se spouští jen s -Djiyu.live=true", System.getProperty("jiyu.live") == "true")

        // -Djiyu.live.filter=ext: omezí test na zdroje, jejichž id tímhle začíná (např. jen rozšířený katalog).
        val prefix = System.getProperty("jiyu.live.filter").orEmpty()
        val sources = buildSources().filter { prefix.isEmpty() || it.id.startsWith(prefix) }
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
            appendLine("| Stav | Zdroj | Detail |")
            appendLine("|---|---|---|")
            rows.forEach { appendLine("| ${it.status} | ${it.name} (`${it.id}`) | ${it.detail.replace("|", "/").take(160)} |") }
        }
        val out = File("build/reports/live-sources.md")
        out.parentFile.mkdirs()
        out.writeText(report)
        // Seznam ověřených zdrojů (OK/WARN) pro generátor rozšířeného katalogu.
        File("build/reports/live-vetted.txt").writeText(rows.filter { it.status == "OK" || it.status == "WARN" }.joinToString("\n") { it.id })
        println(report)
    }

    // ── sestavení zdrojů ─────────────────────────────────────────────────────

    private fun buildSources(): List<MangaSource> {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.showAdultSources } returns flowOf(true)
        every { settings.appMode } returns flowOf(AppMode.SOURCES)
        every { settings.sourceDomainOverrides } returns flowOf(emptyMap())
        val dao = mockk<CustomSourceDao>(relaxed = true)
        every { dao.observeAll() } returns emptyFlow()

        fun instantiate(type: Class<*>): Any = when {
            type == OkHttpClient::class.java -> client
            type == CustomSourceDao::class.java -> dao
            type == SettingsRepository::class.java -> settings
            type == DomainOverrides::class.java -> DomainOverrides()
            MangaSource::class.java.isAssignableFrom(type) -> buildSource(type) ?: io.mockk.mockkClass(type.kotlin, relaxed = true)
            else -> io.mockk.mockkClass(type.kotlin, relaxed = true)
        }

        val ctor = SourceManager::class.java.constructors.single()
        val manager = ctor.newInstance(*ctor.parameterTypes.map { instantiate(it) }.toTypedArray())
        @Suppress("UNCHECKED_CAST")
        fun sourcesIn(name: String) = (SourceManager::class.java.getDeclaredField(name).apply { isAccessible = true }.get(manager) as List<MangaSource>)
        return (sourcesIn("staticSources") + sourcesIn("communitySources")).filter { !it.javaClass.name.contains("Subclass") && runCatching { it.id.isNotBlank() }.getOrDefault(false) }
    }

    private fun buildSource(type: Class<*>): Any? {
        val ctor = type.constructors.firstOrNull { c -> c.parameterTypes.all { it == OkHttpClient::class.java } } ?: return null
        return ctor.newInstance(*ctor.parameterTypes.map { client }.toTypedArray())
    }

    // ── kontrola jednoho zdroje ──────────────────────────────────────────────

    private fun isCloudflare(t: Throwable): Boolean {
        val m = (t.message ?: "") + (t.cause?.message ?: "")
        return Regex("403|503|Just a moment|challenge|cloudflare", RegexOption.IGNORE_CASE).containsMatchIn(m)
    }

    private suspend fun check(src: MangaSource): Row {
        val warnings = mutableListOf<String>()
        fun row(status: String, detail: String) = Row(src.id, src.name, status, detail)
        return try {
            withTimeout(90_000) {
                val page1 = src.getPopular(1)
                if (page1.isEmpty()) return@withTimeout row("FAIL", "výpis strany 1 je prázdný")
                if (page1.map { it.url }.distinct().size != page1.size) warnings += "duplicity ve výpisu"
                runCatching { src.getPopular(2) }.onSuccess { p2 ->
                    if (p2.isNotEmpty() && p2.map { it.url } == page1.map { it.url }) warnings += "strana 2 == strana 1"
                }
                val first = page1.first()
                val details = runCatching { src.getMangaDetails(first) }.getOrElse { return@withTimeout failure(src, "detail", it) }
                val chapters = runCatching { src.getChapterList(details) }.getOrElse { return@withTimeout failure(src, "kapitoly", it) }
                if (chapters.isEmpty()) return@withTimeout row("FAIL", "seznam kapitol je prázdný (${first.title})")
                // Nejnovější kapitola bývá zamčená/placená - když nemá stránky, zkusí se ještě nejstarší.
                var pages = runCatching { src.getPageList(chapters.first()) }.getOrElse { emptyList() }
                if (pages.isEmpty() && chapters.size > 1) {
                    pages = runCatching { src.getPageList(chapters.last()) }.getOrElse { return@withTimeout failure(src, "stránky", it) }
                }
                if (pages.isEmpty()) return@withTimeout row("FAIL", "kapitola nemá stránky (${chapters.first().name})")
                val imageUrl = runCatching { src.getImageUrl(pages.first()) }.getOrElse { pages.first().url }
                if (src.contentType != "NOVEL" && imageUrl.startsWith("http")) {
                    val code = withContext(Dispatchers.IO) {
                        runCatching {
                            client.newCall(
                                Request.Builder().url(imageUrl).header("User-Agent", SourceHttp.USER_AGENT_DESKTOP)
                                    .header("Referer", src.homepageUrl ?: imageUrl).get().build(),
                            ).execute().use { it.code }
                        }.getOrDefault(-1)
                    }
                    if (code !in 200..299) warnings += "první obrázek HTTP $code"
                }
                if (warnings.isEmpty()) row("OK", "${page1.size} titulů, ${chapters.size} kapitol, ${pages.size} stránek")
                else row("WARN", warnings.joinToString("; "))
            }
        } catch (e: Throwable) {
            failure(src, "výpis", e)
        }
    }

    private fun failure(src: MangaSource, stage: String, t: Throwable): Row {
        val status = if (isCloudflare(t)) "CLOUDFLARE" else "FAIL"
        return Row(src.id, src.name, status, "$stage: ${t.javaClass.simpleName}: ${t.message}")
    }
}
