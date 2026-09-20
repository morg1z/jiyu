// Suppress musí být na úrovni SOUBORU - anotace na třídě nepokryje řádky s importy,
// a ty na zastaralé typy ukazují taky. Důvod viz KDoc u SecureCredentialStore.
@file:Suppress("DEPRECATION")

package com.haise.jiyu.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.security.KeyStore
import javax.inject.Singleton

/**
 * Šifrované úložiště pro citlivá data (tracker tokeny, session tokeny, přihlašovací
 * jména) - narozdíl od DataStore Preferences, které appka používá pro běžná
 * nastavení, tady je obsah na disku šifrovaný přes Android Keystore (AES256-GCM).
 *
 * Vlastní EncryptedSharedPreferences soubor, oddělený od "settings" DataStore,
 * aby se necitlivá nastavení nemusela komplikovat šifrováním.
 *
 * ## K tomu `@Suppress("DEPRECATION")`
 * Knihovna dlouho existovala jen jako alpha; s vydáním stabilní `1.1.0` Google zároveň
 * `EncryptedSharedPreferences` i `MasterKey` označil za zastaralé - Jetpack Security dál
 * nerozvíjí. Volba tedy nestojí mezi "moderní" a "zastaralé", ale mezi **alpha** a
 * **stabilní, byť zastaralou** verzí; stabilní vyhrává. Kód se nezměnil, API je stejné.
 *
 * Náhradu si tenhle soubor zaslouží samostatně: znamenala by vlastní šifrování nad
 * Android Keystore **plus migraci už uložených tokenů**, jinak by se uživatel odhlásil
 * ze všech trackerů. Do té doby jsou varování umlčená tady, ne globálně, aby zbytek
 * projektu zůstal na nule.
 */
@Suppress("DEPRECATION")
@Singleton
class SecureCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences = try {
        createPrefs(context)
    } catch (e: Exception) {
        // Klíč v Keystore přestal odpovídat souboru (obnova appky na jiné zařízení, změna
        // zámku obrazovky, poškozený Keystore). Šifrovaná data jsou nečitelná navždy -
        // smazat soubor i klíč a začít načisto (uživatel se znovu přihlásí k trackerům)
        // je lepší než pád appky při každém startu.
        Log.w("SecureCredentialStore", "encrypted prefs unreadable, recreating", e)
        context.deleteSharedPreferences(FILE_NAME)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        createPrefs(context)
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(key: String): String? = try {
        prefs.getString(key, null)
    } catch (e: java.security.GeneralSecurityException) {
        null
    }

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun remove(vararg keys: String) {
        val editor = prefs.edit()
        keys.forEach { editor.remove(it) }
        editor.apply()
    }

    private companion object {
        const val FILE_NAME = "secure_credentials"
    }
}
