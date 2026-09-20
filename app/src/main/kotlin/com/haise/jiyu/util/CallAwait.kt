package com.haise.jiyu.util

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Provede blokující `execute()` tak, aby zrušení korutiny (zavřená obrazovka, `withTimeoutOrNull`,
 * zastavený worker) zrušilo i samotné HTTP volání - jinak vlákno čeká na odpověď nebo timeout
 * (u BYOK až 120 s), i když už na výsledek nikdo nečeká. [handler] běží na volajícím vlákně
 * (typicky `Dispatchers.IO`) a odpověď se po něm zavře; jeho výjimky se propagují volajícímu.
 * Zrušení skončí `CancellationException` - volající ji nesmí spolknout obecným `catch (Exception)`.
 */
suspend inline fun <T> Call.executeCancellable(crossinline handler: (Response) -> T): T =
    suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        try {
            cont.resume(execute().use { handler(it) })
        } catch (e: Throwable) {
            if (cont.isActive) cont.resumeWithException(e)
        }
    }
