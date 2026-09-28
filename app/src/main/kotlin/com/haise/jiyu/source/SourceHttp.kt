package com.haise.jiyu.source

/**
 * Sdílené HTTP konstanty pro zdroje. Dřív měl každý zdroj vlastní kopii User-Agent řetězce (~150 míst),
 * takže se verze prohlížeče nedala změnit na jednom místě.
 *
 * Dvě varianty jsou záměrně zachované (ne sjednocené): část webů/CDN se za Cloudflare chová podle verze
 * UA, a změna verze u zdroje, který funguje, je zbytečné riziko.
 */
object SourceHttp {
    const val USER_AGENT_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    const val USER_AGENT_DESKTOP_124 =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    /**
     * Mobilni Android identita. Nektere weby za Cloudflare blokuji desktop UA
     * interaktivni vyzvou (403 "Checking your browser"), ale mobilni UA pusti
     * rovnou (overeno zive 2026-09-23: noicetranslations.com, sleepytranslations.com).
     * Stejny tvar ma i skutecny WebView UA, se kterym se resi vyzvy - viz
     * CloudflareUserAgent.
     */
    const val USER_AGENT_ANDROID =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
