package com.haise.jiyu.translate

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regrese z device-testu (OCR-7): původní model "každá stránka jedna coroutine +
 * semafor s 90 s stropem na čekání ve frontě" nechal u 343-slicové kapitoly ~185
 * pozdních stránek vypršet ve frontě - tiše skončily jako "bez textu", nikdy se
 * neuložily do cache a v čtečce zůstaly anglicky napořád do dalšího běhu.
 *
 * [runOrderedWorkerPool] čekací deadline na položku nemá - frontu vyřídí pevný
 * počet workerů v pořadí čtení; deadlock pojistku nese watchdog na "žádný pokrok".
 */
class OcrWorkerPoolTest {

    @Test
    fun `OCR-7 - dlouha fronta se zpracuje cela bez propadlych stranek`() = runTest {
        // Simulace reálného scénáře: 200 stránek, pomalé OCR, fronta celkově trvá
        // déle než původních 90 s. Před fixem by pozdní stránky skončily jako
        // permit_timeout bez jediného pokusu o OCR.
        val results = runOrderedWorkerPool(
            workItems = (0 until 200).toList(),
            workerCount = 4,
            stallTimeoutMillis = 90_000,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        ) { pageIndex ->
            kotlinx.coroutines.delay(100)
            "page-$pageIndex"
        }

        assertEquals((0 until 200).toSet(), results.keys)
        assertEquals("page-199", results[199])
    }

    @Test
    fun `OCR-7 - fronta se ridi ve FIFO poradi cteni`() = runTest {
        val order = CopyOnWriteArrayList<Int>()
        runOrderedWorkerPool(
            workItems = listOf(5, 2, 8, 1, 9, 0),
            workerCount = 1,
            stallTimeoutMillis = 60_000,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        ) { item ->
            order.add(item)
            item
        }

        assertEquals(listOf(5, 2, 8, 1, 9, 0), order.toList())
    }

    @Test
    fun `OCR-7 - soubeznost se nikdy neprekroci workerCount`() = runTest {
        var active = 0
        var maxActive = 0
        runOrderedWorkerPool(
            workItems = (0 until 50).toList(),
            workerCount = 3,
            stallTimeoutMillis = 60_000,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        ) {
            active++
            if (active > maxActive) maxActive = active
            kotlinx.coroutines.delay(10)
            active--
            it
        }

        assertEquals(3, maxActive)
    }

    @Test
    fun `OCR-7 - zasekly worker watchdog ukonci a hotove vysledky preziji`() = runTest {
        // Položka 3 visí donekonečna (zaseklý nativní úkol). Zdravý worker frontu
        // dojede, watchdog pak po stallTimeout zruší uvázlého - funkce musí vrátit
        // mapu s dokončenými položkami místo nekonečného čekání celé kapitoly.
        val results = runOrderedWorkerPool(
            workItems = (0 until 10).toList(),
            workerCount = 2,
            stallTimeoutMillis = 500,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        ) { pageIndex ->
            if (pageIndex == 3) awaitCancellation() else "done-$pageIndex"
        }

        assertFalse("uvázlá položka nesmí být ve výsledcích", results.containsKey(3))
        assertEquals(9, results.size)
        assertEquals("done-9", results[9])
    }

    @Test
    fun `OCR-7 - zdrava pomala fronta watchdog nenabodne`() = runTest {
        // Stránky jedou pomalu (každá ~třetina stallTimeoutu), ale fronta se hýbe -
        // watchdog smí reagovat jen na ÚPLNÉ zastavení, ne na pomalost.
        val results = runOrderedWorkerPool(
            workItems = (0 until 8).toList(),
            workerCount = 2,
            stallTimeoutMillis = 400,
            dispatcher = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        ) {
            kotlinx.coroutines.delay(120) // < stallTimeout/4 mezera mezi dokončeními
            it
        }

        assertEquals((0 until 8).toSet(), results.keys)
    }
}
