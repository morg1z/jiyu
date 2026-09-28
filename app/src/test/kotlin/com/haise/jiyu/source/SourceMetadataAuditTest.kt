package com.haise.jiyu.source

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.haise.jiyu.source.astratoons.AstraToonsSource
import com.haise.jiyu.source.dankemoe.DankeMoeSource
import com.haise.jiyu.source.hachiraw.HachirawSource
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.source.kiryuu.KiryuuSource
import com.haise.jiyu.source.mangago.MangagoSource
import com.haise.jiyu.source.mangaraw4u.MangaRaw4uSource
import com.haise.jiyu.source.mangarawbest.MangaRawBestSource
import com.haise.jiyu.source.manhwa210.Manhwa210Source
import com.haise.jiyu.source.raw1001.Raw1001Source
import com.haise.jiyu.source.twmanga.TwmangaSource
import com.haise.jiyu.source.weloma.WeLoMaSource
import com.haise.jiyu.settings.FakeDataStore
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regresni test za auditem metadat 2026-11: live probe webu (html lang,
 * titulky stranek, zanrove taxonomie) nasel zdroje sedici na vychozim
 * language="en" / isAdult=false prestoze realne obsah je jinde.
 * Chybejici override se tu propadne, kdyby se nekdy ztratil.
 */
@RunWith(RobolectricTestRunner::class)
class SourceMetadataAuditTest {

    private val client = OkHttpClient()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `neanglicke zdroje maji spravny jazyk`() {
        assertEquals("pt", AstraToonsSource(client).language)
        assertEquals("de", DankeMoeSource(client).language)
        assertEquals("id", KiryuuSource(client).language)
        assertEquals("zh", TwmangaSource(client).language)
        assertEquals("vi", MangaRaw4uSource(client).language)
    }

    @Test
    fun `japonske raw zdroje maji ja`() {
        assertEquals("ja", Raw1001Source(client).language)
        assertEquals("ja", HachirawSource(client).language)
        assertEquals("ja", WeLoMaSource(client).language)
        assertEquals("ja", MangaRawBestSource(client).language)
    }

    @Test
    fun `adult katalogy jsou oznaceny isAdult`() {
        assertTrue("Manhwa210", Manhwa210Source(client).isAdult)
        assertTrue(
            "Mangago",
            MangagoSource(client, CloudflareInterceptor(context, FakeDataStore())).isAdult,
        )
    }
}
