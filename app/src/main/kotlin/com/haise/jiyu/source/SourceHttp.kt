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
}
