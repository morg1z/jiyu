package com.haise.jiyu.translate

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kouřová zkouška on-device překladu přes ML Kit Translate.
 * Stáhne jazykový model (pokud chybí) a ověří, že se vůbec vrátí nějaký výsledek.
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceTranslatorTest {

    @Test
    fun englishToCzechTranslates() = runBlocking {
        val translator = OnDeviceTranslator()
        val result = translator.translate(
            texts = listOf("Hello, how are you?"),
            sourceLanguage = "English",
            targetLanguage = "Czech",
        )

        Log.i("OnDeviceTranslatorTest", "result=$result")
        assertNotNull("překlad nesmí vrátit null", result)
        assertTrue("musí vrátit jeden přeložený řetězec", result?.size == 1)
        val translated = result?.firstOrNull()
        assertNotNull("první překlad nesmí být null", translated)
        assertTrue(
            "přeložený text by se měl lišit od originálu",
            translated != null && translated != "Hello, how are you?",
        )
    }
}
