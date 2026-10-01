package com.haise.jiyu.translate

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Fronta pracovních položek -> pevný počet workerů (audit OCR-7).
 *
 * Nahrazuje původní model "každá stránka jedna coroutine + semafor s 90s stropem
 * na čekání ve frontě". Tam u dlouhých kapitol (webtoon, 200-350 sliců) pozdní
 * stránky vypršely ve frontě dřív, než dostaly řadu, a tiše skončily jako
 * "bez textu" - na reálné kapitole 343 stránek takto propadlo ~185 sliců.
 *
 * Sémantika:
 * - Pořadí = FIFO podle [workItems] (pořadí čtení) - viditelné stránky jdou první.
 * - Žádná čekací deadline na položku: stránka se zpracuje, když na ni dojde řada.
 * - Souběžnost = [workerCount] - worker drží svůj prostředek (bitmapa) od začátku
 *   do konce zpracování, takže stejné číslo ohraničuje i RAM (dřív OCR-1 invariant).
 * - Deadlock pojistka přes watchdog: když žádná položka nedokončí za
 *   [stallTimeoutMillis] (zaseklý nativní recognizer, který se zrušit nedá),
 *   zbývající workery se zruší a nezpracované položky v mapě prostě chybí -
 *   volající je označí a příští běh je zkusí znovu. Zdravá fronta watchdog
 *   nikdy nenabodne, protože mezera mezi dokončeními nepřesáhne per-page timeout.
 *
 * @param process MUSÍ interně odchytit všechny výjimky kromě CancellationException
 *   (tu musí přepustit) - vyhozená výjimka shodí celý scope i s hotovými výsledky.
 * @param nowMillis injektované hodiny - testy předávají virtuální scheduler clock,
 *   produkce default `System.nanoTime()` (monotónní, ne wall-clock).
 * @return mapa dokončených výsledků; položky vynechané po watchdog abortu
 *   v mapě chybí.
 */
internal suspend fun <T> runOrderedWorkerPool(
    workItems: List<Int>,
    workerCount: Int,
    stallTimeoutMillis: Long,
    dispatcher: CoroutineDispatcher,
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    process: suspend (Int) -> T,
): Map<Int, T> = coroutineScope {
    val queue = Channel<Int>(Channel.UNLIMITED)
    workItems.forEach { queue.trySend(it) }
    queue.close()
    val results = ConcurrentHashMap<Int, T>()
    val lastProgressAt = AtomicLong(nowMillis())
    val workers = List(workerCount.coerceAtLeast(1)) {
        launch(dispatcher) {
            for (item in queue) {
                results[item] = process(item)
                lastProgressAt.set(nowMillis())
            }
        }
    }
    val watchdog = launch {
        while (true) {
            delay((stallTimeoutMillis / 4).coerceAtLeast(1))
            if (workers.all { it.isCompleted }) return@launch
            val idleMillis = nowMillis() - lastProgressAt.get()
            if (idleMillis > stallTimeoutMillis) {
                workers.forEach { it.cancel() }
                return@launch
            }
        }
    }
    workers.joinAll()
    watchdog.cancel()
    results.toMap()
}
