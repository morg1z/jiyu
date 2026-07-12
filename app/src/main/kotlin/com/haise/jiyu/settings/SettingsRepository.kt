package com.haise.jiyu.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

object SettingsKeys {
    val TARGET_LANGUAGE        = stringPreferencesKey("target_language")
    val SOURCE_LANGUAGE        = stringPreferencesKey("source_language")
    val THEME                  = stringPreferencesKey("theme")
    val READING_DIRECTION      = stringPreferencesKey("reading_direction")
    val READING_MODE           = stringPreferencesKey("reading_mode")
    val TOTAL_READING_TIME     = longPreferencesKey("total_reading_time_ms")
    val TOTAL_PAGES_READ       = longPreferencesKey("total_pages_read")
    val UPDATE_INTERVAL_HOURS  = longPreferencesKey("update_interval_hours")
    val TAP_ZONES_ENABLED        = booleanPreferencesKey("tap_zones_enabled")
    val TAP_ZONE_LEFT_FRACTION   = floatPreferencesKey("tap_zone_left_fraction")
    val TAP_ZONE_RIGHT_FRACTION  = floatPreferencesKey("tap_zone_right_fraction")
    val WEBTOON_SCROLL_SPEED     = floatPreferencesKey("webtoon_scroll_speed")
    val READER_TEXT_SCALE        = floatPreferencesKey("reader_text_scale")
    val DOUBLE_PAGE_SPREAD     = booleanPreferencesKey("double_page_spread")
    val AUTO_DELETE_READ       = booleanPreferencesKey("auto_delete_read")
    val AUTO_DELETE_DELAY_DAYS = intPreferencesKey("auto_delete_delay_days")
    val ANILIST_TOKEN          = stringPreferencesKey("anilist_access_token")
    val ANILIST_ID_MAP         = stringPreferencesKey("anilist_id_map")
    val FULLSCREEN_ENABLED     = booleanPreferencesKey("fullscreen_enabled")
    val READER_THEME           = stringPreferencesKey("reader_theme")
    val OLED_MODE              = booleanPreferencesKey("oled_mode")
    val WEEKLY_GOAL_CHAPTERS   = intPreferencesKey("weekly_goal_chapters")
    val READING_STREAK_DAYS    = intPreferencesKey("reading_streak_days")
    val LAST_READ_DATE         = stringPreferencesKey("last_read_date")
    val CUSTOM_CSS             = stringPreferencesKey("custom_css_inject")
    val PAGE_SCALE             = stringPreferencesKey("page_scale")
    val AUTO_BACKUP_ENABLED    = booleanPreferencesKey("auto_backup_enabled")
    val AUTO_NEXT_CHAPTER      = booleanPreferencesKey("auto_next_chapter")
    val SAVED_SEARCHES         = stringPreferencesKey("saved_searches")
    val CROP_BORDERS           = booleanPreferencesKey("crop_borders")
    val LIBRARY_GRID_MODE      = booleanPreferencesKey("library_grid_mode")
    val DOWNLOAD_ONLY_WIFI     = booleanPreferencesKey("download_only_wifi")
    val ONBOARDING_COMPLETED   = booleanPreferencesKey("onboarding_completed")
    val DOWNLOAD_FOLDER_URI    = stringPreferencesKey("download_folder_uri")
    val TAP_ZONE_GRID          = stringPreferencesKey("tap_zone_grid")
    val NEW_CHAPTERS_COUNT     = intPreferencesKey("new_chapters_count")
    val VOLUME_KEYS_NAV        = booleanPreferencesKey("volume_keys_nav")
    val KEEP_SCREEN_ON         = booleanPreferencesKey("keep_screen_on")
    val READER_ORIENTATION     = stringPreferencesKey("reader_orientation")
    val SKIP_READ_CHAPTERS     = booleanPreferencesKey("skip_read_chapters")
    val SAVE_AS_CBZ            = booleanPreferencesKey("save_as_cbz")
    val LIBRARY_GRID_COLUMNS   = intPreferencesKey("library_grid_columns")
    val DEFAULT_CATEGORY_ID    = stringPreferencesKey("default_category_id")
    val PARALLEL_DOWNLOADS     = intPreferencesKey("parallel_downloads")
}

object ReaderTheme {
    const val DARK  = "dark"
    const val SEPIA = "sepia"
    const val PAPER = "paper"
}

object ThemeOption {
    const val SYSTEM = "system"
    const val DARK   = "dark"
    const val LIGHT  = "light"
}

object ReadingDirection {
    const val LTR = "ltr"
    const val RTL = "rtl"
}

object ReadingMode {
    const val MANGA    = "manga"    // horizontální stránky (klasická manga)
    const val WEBTOON  = "webtoon"  // vertikální scroll (manhwa/webtoon)
}

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val targetLanguage: Flow<String> =
        dataStore.data.map { it[SettingsKeys.TARGET_LANGUAGE] ?: "Czech" }

    val sourceLanguage: Flow<String> =
        dataStore.data.map { it[SettingsKeys.SOURCE_LANGUAGE] ?: "English" }

    val theme: Flow<String> =
        dataStore.data.map { it[SettingsKeys.THEME] ?: ThemeOption.SYSTEM }

    val readingDirection: Flow<String> =
        dataStore.data.map { it[SettingsKeys.READING_DIRECTION] ?: ReadingDirection.LTR }

    val readingMode: Flow<String> =
        dataStore.data.map { it[SettingsKeys.READING_MODE] ?: ReadingMode.MANGA }

    val updateIntervalHours: Flow<Long> =
        dataStore.data.map { it[SettingsKeys.UPDATE_INTERVAL_HOURS] ?: 12L }

    val tapZonesEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.TAP_ZONES_ENABLED] ?: true }

    val tapZoneLeftFraction: Flow<Float> =
        dataStore.data.map { it[SettingsKeys.TAP_ZONE_LEFT_FRACTION] ?: 0.3f }

    val tapZoneRightFraction: Flow<Float> =
        dataStore.data.map { it[SettingsKeys.TAP_ZONE_RIGHT_FRACTION] ?: 0.3f }

    val webtoonScrollSpeed: Flow<Float> =
        dataStore.data.map { it[SettingsKeys.WEBTOON_SCROLL_SPEED] ?: 1.0f }

    val readerTextScale: Flow<Float> =
        dataStore.data.map { it[SettingsKeys.READER_TEXT_SCALE] ?: 1f }

    val doublePageSpread: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.DOUBLE_PAGE_SPREAD] ?: false }

    val autoDeleteRead: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.AUTO_DELETE_READ] ?: false }

    val autoDeleteDelayDays: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.AUTO_DELETE_DELAY_DAYS] ?: 0 }

    suspend fun setAutoDeleteRead(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.AUTO_DELETE_READ] = enabled }

    suspend fun setAutoDeleteDelayDays(days: Int) =
        dataStore.edit { it[SettingsKeys.AUTO_DELETE_DELAY_DAYS] = days }

    suspend fun setTargetLanguage(lang: String) =
        dataStore.edit { it[SettingsKeys.TARGET_LANGUAGE] = lang }

    suspend fun setSourceLanguage(lang: String) =
        dataStore.edit { it[SettingsKeys.SOURCE_LANGUAGE] = lang }

    suspend fun setTheme(theme: String) =
        dataStore.edit { it[SettingsKeys.THEME] = theme }

    suspend fun setReadingDirection(dir: String) =
        dataStore.edit { it[SettingsKeys.READING_DIRECTION] = dir }

    suspend fun setReadingMode(mode: String) =
        dataStore.edit { it[SettingsKeys.READING_MODE] = mode }

    suspend fun setUpdateIntervalHours(hours: Long) =
        dataStore.edit { it[SettingsKeys.UPDATE_INTERVAL_HOURS] = hours }

    suspend fun setTapZonesEnabled(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.TAP_ZONES_ENABLED] = enabled }

    suspend fun setTapZoneLeftFraction(fraction: Float) =
        dataStore.edit { it[SettingsKeys.TAP_ZONE_LEFT_FRACTION] = fraction }

    suspend fun setTapZoneRightFraction(fraction: Float) =
        dataStore.edit { it[SettingsKeys.TAP_ZONE_RIGHT_FRACTION] = fraction }

    suspend fun setWebtoonScrollSpeed(speed: Float) =
        dataStore.edit { it[SettingsKeys.WEBTOON_SCROLL_SPEED] = speed }

    suspend fun setReaderTextScale(scale: Float) =
        dataStore.edit { it[SettingsKeys.READER_TEXT_SCALE] = scale }

    suspend fun setDoublePageSpread(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.DOUBLE_PAGE_SPREAD] = enabled }

    val totalReadingTimeMs: Flow<Long> =
        dataStore.data.map { it[SettingsKeys.TOTAL_READING_TIME] ?: 0L }

    val totalPagesRead: Flow<Long> =
        dataStore.data.map { it[SettingsKeys.TOTAL_PAGES_READ] ?: 0L }

    suspend fun addReadingTime(deltaMs: Long) =
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TOTAL_READING_TIME] = (prefs[SettingsKeys.TOTAL_READING_TIME] ?: 0L) + deltaMs
        }

    suspend fun addPagesRead(count: Long) =
        dataStore.edit { prefs ->
            prefs[SettingsKeys.TOTAL_PAGES_READ] = (prefs[SettingsKeys.TOTAL_PAGES_READ] ?: 0L) + count
        }

    val aniListToken: Flow<String?> = dataStore.data.map { it[SettingsKeys.ANILIST_TOKEN] }
    val aniListIdMap: Flow<String>  = dataStore.data.map { it[SettingsKeys.ANILIST_ID_MAP] ?: "{}" }

    suspend fun saveAniListToken(token: String?) = dataStore.edit {
        if (token == null) it.remove(SettingsKeys.ANILIST_TOKEN)
        else it[SettingsKeys.ANILIST_TOKEN] = token
    }

    suspend fun saveAniListIdMap(json: String) = dataStore.edit { it[SettingsKeys.ANILIST_ID_MAP] = json }

    val fullscreenEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.FULLSCREEN_ENABLED] ?: true }

    val readerTheme: Flow<String> =
        dataStore.data.map { it[SettingsKeys.READER_THEME] ?: ReaderTheme.DARK }

    suspend fun setFullscreenEnabled(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.FULLSCREEN_ENABLED] = enabled }

    suspend fun setReaderTheme(theme: String) =
        dataStore.edit { it[SettingsKeys.READER_THEME] = theme }

    val oledMode: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.OLED_MODE] ?: false }

    suspend fun setOledMode(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.OLED_MODE] = enabled }

    val weeklyGoal: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.WEEKLY_GOAL_CHAPTERS] ?: 0 }

    val readingStreak: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.READING_STREAK_DAYS] ?: 0 }

    val lastReadDate: Flow<String> =
        dataStore.data.map { it[SettingsKeys.LAST_READ_DATE] ?: "" }

    val customCss: Flow<String> =
        dataStore.data.map { it[SettingsKeys.CUSTOM_CSS] ?: "" }

    suspend fun setWeeklyGoal(chapters: Int) =
        dataStore.edit { it[SettingsKeys.WEEKLY_GOAL_CHAPTERS] = chapters }

    suspend fun setCustomCss(css: String) =
        dataStore.edit { it[SettingsKeys.CUSTOM_CSS] = css }

    val pageScale: Flow<String> =
        dataStore.data.map { it[SettingsKeys.PAGE_SCALE] ?: "fit_width" }

    suspend fun setPageScale(scale: String) =
        dataStore.edit { it[SettingsKeys.PAGE_SCALE] = scale }

    val autoBackupEnabled: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.AUTO_BACKUP_ENABLED] ?: false }

    suspend fun setAutoBackupEnabled(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.AUTO_BACKUP_ENABLED] = enabled }

    val autoNextChapter: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.AUTO_NEXT_CHAPTER] ?: false }

    suspend fun setAutoNextChapter(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.AUTO_NEXT_CHAPTER] = enabled }

    val savedSearches: Flow<List<String>> =
        dataStore.data.map { prefs ->
            val raw = prefs[SettingsKeys.SAVED_SEARCHES] ?: return@map emptyList()
            raw.split("|||").filter { it.isNotBlank() }
        }

    suspend fun addSavedSearch(query: String) = dataStore.edit { prefs ->
        val existing = prefs[SettingsKeys.SAVED_SEARCHES]?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()
        if (!existing.contains(query)) {
            prefs[SettingsKeys.SAVED_SEARCHES] = (listOf(query) + existing).take(10).joinToString("|||")
        }
    }

    suspend fun removeSavedSearch(query: String) = dataStore.edit { prefs ->
        val existing = prefs[SettingsKeys.SAVED_SEARCHES]?.split("|||")?.filter { it.isNotBlank() } ?: emptyList()
        prefs[SettingsKeys.SAVED_SEARCHES] = existing.filter { it != query }.joinToString("|||")
    }

    val cropBorders: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.CROP_BORDERS] ?: false }

    suspend fun setCropBorders(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.CROP_BORDERS] = enabled }

    val libraryGridMode: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.LIBRARY_GRID_MODE] ?: true }

    suspend fun setLibraryGridMode(gridMode: Boolean) =
        dataStore.edit { it[SettingsKeys.LIBRARY_GRID_MODE] = gridMode }

    val downloadOnlyWifi: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.DOWNLOAD_ONLY_WIFI] ?: false }

    suspend fun setDownloadOnlyWifi(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.DOWNLOAD_ONLY_WIFI] = enabled }

    val onboardingCompleted: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.ONBOARDING_COMPLETED] ?: false }

    suspend fun setOnboardingCompleted() =
        dataStore.edit { it[SettingsKeys.ONBOARDING_COMPLETED] = true }

    val downloadFolderUri: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.DOWNLOAD_FOLDER_URI] }

    suspend fun setDownloadFolderUri(uri: String?) = dataStore.edit {
        if (uri == null) it.remove(SettingsKeys.DOWNLOAD_FOLDER_URI)
        else it[SettingsKeys.DOWNLOAD_FOLDER_URI] = uri
    }

    val tapZoneGrid: Flow<String> =
        dataStore.data.map { it[SettingsKeys.TAP_ZONE_GRID] ?: "" }

    suspend fun setTapZoneGrid(serialized: String) =
        dataStore.edit { it[SettingsKeys.TAP_ZONE_GRID] = serialized }

    val newChaptersCount: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.NEW_CHAPTERS_COUNT] ?: 0 }

    suspend fun addNewChapters(count: Int) = dataStore.edit { prefs ->
        prefs[SettingsKeys.NEW_CHAPTERS_COUNT] = (prefs[SettingsKeys.NEW_CHAPTERS_COUNT] ?: 0) + count
    }

    suspend fun clearNewChapters() =
        dataStore.edit { it[SettingsKeys.NEW_CHAPTERS_COUNT] = 0 }

    val volumeKeysNav: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.VOLUME_KEYS_NAV] ?: true }

    val keepScreenOn: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.KEEP_SCREEN_ON] ?: true }

    val readerOrientation: Flow<String> =
        dataStore.data.map { it[SettingsKeys.READER_ORIENTATION] ?: "free" }

    suspend fun setVolumeKeysNav(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.VOLUME_KEYS_NAV] = enabled }

    suspend fun setKeepScreenOn(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.KEEP_SCREEN_ON] = enabled }

    suspend fun setReaderOrientation(orientation: String) =
        dataStore.edit { it[SettingsKeys.READER_ORIENTATION] = orientation }

    val skipReadChapters: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.SKIP_READ_CHAPTERS] ?: false }

    suspend fun setSkipReadChapters(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.SKIP_READ_CHAPTERS] = enabled }

    val saveAsCbz: Flow<Boolean> =
        dataStore.data.map { it[SettingsKeys.SAVE_AS_CBZ] ?: false }

    suspend fun setSaveAsCbz(enabled: Boolean) =
        dataStore.edit { it[SettingsKeys.SAVE_AS_CBZ] = enabled }

    val libraryGridColumns: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.LIBRARY_GRID_COLUMNS] ?: 3 }

    suspend fun setLibraryGridColumns(n: Int) =
        dataStore.edit { it[SettingsKeys.LIBRARY_GRID_COLUMNS] = n }

    val defaultCategoryId: Flow<String?> =
        dataStore.data.map { it[SettingsKeys.DEFAULT_CATEGORY_ID] }

    suspend fun setDefaultCategoryId(id: String?) = dataStore.edit {
        if (id == null) it.remove(SettingsKeys.DEFAULT_CATEGORY_ID)
        else it[SettingsKeys.DEFAULT_CATEGORY_ID] = id
    }

    val parallelDownloads: Flow<Int> =
        dataStore.data.map { it[SettingsKeys.PARALLEL_DOWNLOADS] ?: 3 }

    suspend fun setParallelDownloads(n: Int) =
        dataStore.edit { it[SettingsKeys.PARALLEL_DOWNLOADS] = n }

    suspend fun updateReadingStreak() = dataStore.edit { prefs ->
        val today = java.time.LocalDate.now().toString()
        val last = prefs[SettingsKeys.LAST_READ_DATE] ?: ""
        val yesterday = java.time.LocalDate.now().minusDays(1).toString()
        val streak = prefs[SettingsKeys.READING_STREAK_DAYS] ?: 0
        prefs[SettingsKeys.LAST_READ_DATE] = today
        prefs[SettingsKeys.READING_STREAK_DAYS] = when {
            last == today -> streak
            last == yesterday -> streak + 1
            else -> 1
        }
    }
}
