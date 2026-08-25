package com.haise.jiyu.source

/**
 * HTTP 429 od zdroje - viz RateLimitInterceptor (di/AppModule.kt). Záměrně NENÍ IOException,
 * aby ji RetryInterceptor (3x opakování celého řetězce) nezachytil a zbytečně neopakoval
 * request, který stejně zůstane rate-limitovaný.
 *
 * [retryAfterMs] je z Retry-After hlavičky (sekundy i HTTP-date formát), 0 když header
 * chybí nebo se nedá naparsovat.
 */
class SourceRateLimitedException(val retryAfterMs: Long) : Exception("Rate limited, retry after ${retryAfterMs}ms")
