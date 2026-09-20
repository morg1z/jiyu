package com.haise.jiyu.source.interceptor

import com.haise.jiyu.security.SecureCredentialStore
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.settings.StoredProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ukládání a použití volitelné proxy: údaje (bez hesla) leží v nastavení, heslo v šifrovaném úložišti
 * ([SecureCredentialStore]). [bind] přenáší uložené hodnoty do [NetworkProxyConfig], který čtou klienti sítě.
 */
@Singleton
class ProxyRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val secureStore: SecureCredentialStore,
    private val config: NetworkProxyConfig,
) {
    /** Uložená proxy (bez hesla), nebo `null` = vypnuto. */
    val stored: Flow<StoredProxy?> = settings.proxy

    fun bind(scope: CoroutineScope) {
        scope.launch {
            settings.proxy.collect { stored ->
                config.settings = stored?.let {
                    val type = runCatching { ProxyType.valueOf(it.type) }.getOrDefault(ProxyType.HTTP)
                    ProxySettings(type, it.host, it.port, it.user, secureStore.get(PASSWORD_KEY))
                }
            }
        }
    }

    /** `false` = neplatný vstup (nic se neuložilo). */
    suspend fun save(type: ProxyType, host: String, port: String, user: String?, password: String?): Boolean {
        val parsed = ProxySettings.parse(type, host, port, user, password) ?: return false
        if (parsed.password.isNullOrEmpty()) secureStore.remove(PASSWORD_KEY) else secureStore.set(PASSWORD_KEY, parsed.password)
        settings.setProxy(StoredProxy(parsed.type.name, parsed.host, parsed.port, parsed.user))
        return true
    }

    suspend fun clear() {
        secureStore.remove(PASSWORD_KEY)
        settings.setProxy(null)
    }

    private companion object {
        const val PASSWORD_KEY = "network_proxy_password"
    }
}
