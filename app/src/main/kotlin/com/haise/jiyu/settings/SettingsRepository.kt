package com.haise.jiyu.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

object SettingsKeys {
    val TARGET_LANGUAGE   = stringPreferencesKey("target_language")
    val THEME             = stringPreferencesKey("theme")
    val READING_DIRECTION = stringPreferencesKey("reading_direction")
}

/** Hodnoty témat */
object ThemeOption {
    const val SYSTEM = "system"
    const val DARK   = "dark"
    const val LIGHT  = "light"
}

/** Hodnoty směru čtení */
object ReadingDirection {
    const val LTR = "ltr"   // left-to-right (manhwa, Western)
    const val RTL = "rtl"   // right-to-left (japonská manga)
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val targetLanguage: Flow<String> =
        dataStore.data.map { it[SettingsKeys.TARGET_LANGUAGE] ?: "Czech" }

    val theme: Flow<String> =
        dataStore.data.map { it[SettingsKeys.THEME] ?: ThemeOption.SYSTEM }

    val readingDirection: Flow<String> =
        dataStore.data.map { it[SettingsKeys.READING_DIRECTION] ?: ReadingDirection.LTR }

    suspend fun setTargetLanguage(lang: String) =
        dataStore.edit { it[SettingsKeys.TARGET_LANGUAGE] = lang }

    suspend fun setTheme(theme: String) =
        dataStore.edit { it[SettingsKeys.THEME] = theme }

    suspend fun setReadingDirection(dir: String) =
        dataStore.edit { it[SettingsKeys.READING_DIRECTION] = dir }
}
