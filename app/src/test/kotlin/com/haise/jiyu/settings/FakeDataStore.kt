package com.haise.jiyu.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * DataStore<Preferences> v pameti pro testy - zadny Context/Robolectric potreba.
 * Pouziva se jen tam, kde produkcni trida potrebuje SettingsRepository, ale test
 * zajima jen jeho vychozi hodnoty (napr. sourceLanguage = "English").
 */
class FakeDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow(emptyPreferences())
    override val data: Flow<Preferences> = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val updated = transform(state.value)
        state.value = updated
        return updated
    }
}
