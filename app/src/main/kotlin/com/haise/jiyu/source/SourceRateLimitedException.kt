package com.haise.jiyu.source

import com.haise.jiyu.util.NonRetryable
import java.io.IOException

/**
 * HTTP 429 od zdroje - viz RateLimitInterceptor (di/AppModule.kt).
 *
 * MUSÍ být [IOException]: OkHttp v asynchronním `enqueue()` (Coil načítá stránky/obrázky
 * právě tak) zachytává v dispatcher vlákně pouze IOException - jakákoli jiná výjimka
 * propaguje dál a ZABIJE celý proces (FATAL EXCEPTION: OkHttp Dispatcher; reálně
 * zaznamenaný crash při 429 z comick.art). Jako IOException ji AsyncCall doručí do
 * `Callback.onFailure` jako normální síťovou chybu.
 *
 * Retry na ni neběží - označená [NonRetryable], takže ji `RetryInterceptor` v `isRetryable`
 * odfiltruje druhým pokusem, který by stejně zůstal rate-limitovaný.
 *
 * [retryAfterMs] je z Retry-After hlavičky (sekundy i HTTP-date formát), 0 když header
 * chybí nebo se nedá naparsovat.
 */
class SourceRateLimitedException(val retryAfterMs: Long) :
    IOException("Rate limited, retry after ${retryAfterMs}ms"), NonRetryable
