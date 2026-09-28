package com.haise.jiyu.source

import com.haise.jiyu.data.db.CustomSourceDao
import com.haise.jiyu.settings.AppMode
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.interceptor.DomainOverrides
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Sdílená infrastruktura pro živé (live) testy zdrojů - [LiveSourceSmokeTest] i
 * [LiveNovelSourceAuditTest]. Skládá zdroje tak, jak je skládá Hilt: SourceManager
 * se vytvoří reflexí, jeho parametry-zdroje se postaví z konstruktoru s
 * `OkHttpClient`, cokoli jiného je mock (takový zdroj se přeskočí).
 *
 * POZOR: používá se holý OkHttpClient (bez Cloudflare řešiče, DoH, limitů požadavků
 * a hotlink hlaviček z `AppModule`), takže FAIL může znamenat i to, že web vyžaduje
 * appčí interceptory - je to vodítko, ne verdikt.
 */
internal object LiveSourceHarness {

    fun newClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun buildSources(client: OkHttpClient): List<MangaSource> {
        val settings = mockk<SettingsRepository>(relaxed = true)
        every { settings.showAdultSources } returns flowOf(true)
        every { settings.appMode } returns flowOf(AppMode.SOURCES)
        every { settings.sourceDomainOverrides } returns flowOf(emptyMap())
        val dao = mockk<CustomSourceDao>(relaxed = true)
        every { dao.observeAll() } returns emptyFlow()

        fun buildSource(type: Class<*>): Any? {
            val ctor = type.constructors.firstOrNull { c -> c.parameterTypes.all { it == OkHttpClient::class.java } } ?: return null
            return ctor.newInstance(*ctor.parameterTypes.map { client }.toTypedArray())
        }

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
        // novelCommunitySources je TRETI katalog (ext:* novel weby) - bez nej by live testy
        // pokryvaly jen staticSources + manga community.
        return (sourcesIn("staticSources") + sourcesIn("communitySources") + sourcesIn("novelCommunitySources"))
            .filter { !it.javaClass.name.contains("Subclass") && runCatching { it.id.isNotBlank() }.getOrDefault(false) }
    }

    fun isCloudflare(t: Throwable): Boolean {
        val m = (t.message ?: "") + (t.cause?.message ?: "")
        return Regex("403|503|Just a moment|challenge|cloudflare", RegexOption.IGNORE_CASE).containsMatchIn(m)
    }
}
