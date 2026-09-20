package com.haise.jiyu.source

import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.source.interceptor.CloudflareInterceptor
import com.haise.jiyu.util.ErrorAction
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException

/** Výsledek hledání nové adresy zdroje po selhání spojení - viz [ErrorActionHandler.resolveConnectionError]. */
sealed interface MirrorResolution {
    /** Nová adresa se našla a už se použila (stejná značka domény) - stačí zopakovat požadavek. */
    data class Applied(val host: String) : MirrorResolution

    /** Nová adresa se našla, ale je jiná značka - jen se nabízí uživateli. */
    data class Suggested(val action: ErrorAction.UseNewDomain) : MirrorResolution

    data object None : MirrorResolution
}

/**
 * Provede akci nabídnutou u chyby (viz [ErrorAction]). Vrací `true`, když se má po ní zopakovat původní operace.
 * Akce, které potřebují navigaci ([ErrorAction.OpenSourceWeb]), provádí UI samo - tady vrací `false`.
 */
@Singleton
class ErrorActionHandler @Inject constructor(
    private val cloudflare: CloudflareInterceptor,
    private val settings: SettingsRepository,
    private val sourceManager: SourceManager,
    private val mirrorProbe: MirrorProbe,
) {
    suspend fun perform(action: ErrorAction): Boolean = when (action) {
        is ErrorAction.SolveCloudflare -> cloudflare.solveNow(action.url)
        is ErrorAction.UseNewDomain -> {
            settings.setSourceDomainOverride(action.sourceId, action.host)
            true
        }
        is ErrorAction.OpenSourceWeb -> false
    }

    /**
     * Po selhání spojení zdroje ([error] = nedostupný host, TLS, connect/timeout) zjistí, jestli se web nepřestěhoval
     * (viz [MirrorProbe]). Volá se z chybové cesty ViewModelů, nikdy ze skrytého pozadí.
     */
    suspend fun resolveConnectionError(sourceId: String, error: Throwable): MirrorResolution {
        if (!isConnectionFailure(error)) return MirrorResolution.None
        val home = sourceManager.getById(sourceId)?.homepageUrl ?: return MirrorResolution.None
        val candidate = mirrorProbe.detect(sourceId, home) ?: return MirrorResolution.None
        return if (candidate.autoApply) {
            settings.setSourceDomainOverride(sourceId, candidate.host)
            MirrorResolution.Applied(candidate.host)
        } else {
            MirrorResolution.Suggested(ErrorAction.UseNewDomain(sourceId, candidate.host))
        }
    }

    internal companion object {
        fun isConnectionFailure(error: Throwable): Boolean {
            var t: Throwable? = error
            var depth = 0
            while (t != null && depth < 6) {
                if (t is UnknownHostException || t is ConnectException || t is NoRouteToHostException ||
                    t is SSLException || t is SocketTimeoutException
                ) return true
                t = t.cause?.takeIf { it !== t }
                depth++
            }
            return false
        }
    }
}
