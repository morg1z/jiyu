package com.haise.jiyu.settings

import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Systematický audit "funguje každé nastavení": pro každý getter/setter pár v
 * [SettingsRepository] ověřuje výchozí hodnotu a round-trip (zapsat → přečíst
 * zpět). FakeDataStore používá skutečné Preferences, takže test zachytí i chyby
 * typu "setter píše pod jiným klíčem než getter čte" nebo špatný typ.
 */
class SettingsRepositoryRoundTripTest {

    private fun repository() = SettingsRepository(FakeDataStore())

    // ── Překlad ──────────────────────────────────────────────────────────────

    @Test
    fun `target and source language round-trip`() = runTest {
        val s = repository()
        assertEquals("Czech", s.targetLanguage.first())
        assertEquals("Auto", s.sourceLanguage.first())

        s.setTargetLanguage("English"); s.setSourceLanguage("Japanese")

        assertEquals("English", s.targetLanguage.first())
        assertEquals("Japanese", s.sourceLanguage.first())
    }

    // ── Vzhled ───────────────────────────────────────────────────────────────

    @Test
    fun `theme, library grid, columns and default category round-trip`() = runTest {
        val s = repository()
        assertEquals(ThemeOption.SYSTEM, s.theme.first())
        assertTrue(s.libraryGridMode.first())
        assertEquals(3, s.libraryGridColumns.first())
        assertNull(s.defaultCategoryId.first())

        s.setTheme(ThemeOption.LIGHT); s.setLibraryGridMode(false)
        s.setLibraryGridColumns(4); s.setDefaultCategoryId("cat-1")

        assertEquals(ThemeOption.LIGHT, s.theme.first())
        assertFalse(s.libraryGridMode.first())
        assertEquals(4, s.libraryGridColumns.first())
        assertEquals("cat-1", s.defaultCategoryId.first())

        s.setDefaultCategoryId(null)
        assertNull("null musí klíč odebrat", s.defaultCategoryId.first())
    }

    // ── Čtečka ───────────────────────────────────────────────────────────────

    @Test
    fun `reader direction, mode, theme, orientation, scale round-trip`() = runTest {
        val s = repository()
        assertEquals(ReadingDirection.LTR, s.readingDirection.first())
        assertEquals(ReadingMode.MANGA, s.readingMode.first())
        assertEquals(ReaderTheme.DARK, s.readerTheme.first())
        assertEquals("free", s.readerOrientation.first())
        assertEquals("fit_width", s.pageScale.first())

        s.setReadingDirection(ReadingDirection.RTL)
        s.setReadingMode(ReadingMode.WEBTOON)
        s.setReaderTheme(ReaderTheme.SEPIA)
        s.setReaderOrientation("landscape")
        s.setPageScale("fit_screen")

        assertEquals(ReadingDirection.RTL, s.readingDirection.first())
        assertEquals(ReadingMode.WEBTOON, s.readingMode.first())
        assertEquals(ReaderTheme.SEPIA, s.readerTheme.first())
        assertEquals("landscape", s.readerOrientation.first())
        assertEquals("fit_screen", s.pageScale.first())
    }

    @Test
    fun `reader toggles round-trip`() = runTest {
        val s = repository()
        assertTrue(s.fullscreenEnabled.first())
        assertFalse(s.oledMode.first())
        assertTrue(s.volumeKeysNav.first())
        assertTrue(s.keepScreenOn.first())
        assertFalse(s.skipReadChapters.first())
        assertFalse(s.cropBorders.first())
        assertFalse(s.doublePageSpread.first())
        assertFalse(s.autoNextChapter.first())
        assertFalse(s.infiniteScrollEnabled.first())

        s.setFullscreenEnabled(false); s.setOledMode(true)
        s.setVolumeKeysNav(false); s.setKeepScreenOn(false)
        s.setSkipReadChapters(true); s.setCropBorders(true)
        s.setDoublePageSpread(true); s.setAutoNextChapter(true)
        s.setInfiniteScrollEnabled(true)

        assertFalse(s.fullscreenEnabled.first()); assertTrue(s.oledMode.first())
        assertFalse(s.volumeKeysNav.first()); assertFalse(s.keepScreenOn.first())
        assertTrue(s.skipReadChapters.first()); assertTrue(s.cropBorders.first())
        assertTrue(s.doublePageSpread.first()); assertTrue(s.autoNextChapter.first())
        assertTrue(s.infiniteScrollEnabled.first())
    }

    @Test
    fun `tap zones - enable, fractions, grid - round-trip`() = runTest {
        val s = repository()
        assertTrue(s.tapZonesEnabled.first())
        assertEquals(0.3f, s.tapZoneLeftFraction.first())
        assertEquals(0.3f, s.tapZoneRightFraction.first())
        assertEquals("", s.tapZoneGrid.first())

        s.setTapZonesEnabled(false)
        s.setTapZoneLeftFraction(0.4f); s.setTapZoneRightFraction(0.25f)
        s.setTapZoneGrid("2x3:prev,menu,next")

        assertFalse(s.tapZonesEnabled.first())
        assertEquals(0.4f, s.tapZoneLeftFraction.first())
        assertEquals(0.25f, s.tapZoneRightFraction.first())
        assertEquals("2x3:prev,menu,next", s.tapZoneGrid.first())
    }

    @Test
    fun `webtoon speed and reader text scale round-trip`() = runTest {
        val s = repository()
        assertEquals(1.0f, s.webtoonScrollSpeed.first())
        assertEquals(1f, s.readerTextScale.first())

        s.setWebtoonScrollSpeed(2.5f); s.setReaderTextScale(1.4f)

        assertEquals(2.5f, s.webtoonScrollSpeed.first())
        assertEquals(1.4f, s.readerTextScale.first())
    }

    @Test
    fun `page curl + style round-trip`() = runTest {
        val s = repository()
        assertFalse(s.pageCurlEnabled.first())
        assertEquals(CurlStyleSetting.CLASSIC, s.curlStyle.first())

        s.setPageCurlEnabled(true); s.setCurlStyle(CurlStyleSetting.ROLL)

        assertTrue(s.pageCurlEnabled.first())
        assertEquals(CurlStyleSetting.ROLL, s.curlStyle.first())
    }

    @Test
    fun `preload switches default to on and round-trip`() = runTest {
        val s = repository()
        assertTrue(s.preloadNextNovelChapter.first())
        assertTrue(s.preloadNextChapterManga.first())
        assertTrue(s.preloadNextChapterWifiOnly.first())

        s.setPreloadNextNovelChapter(false)
        s.setPreloadNextChapterManga(false)
        s.setPreloadNextChapterWifiOnly(false)

        assertFalse(s.preloadNextNovelChapter.first())
        assertFalse(s.preloadNextChapterManga.first())
        assertFalse(s.preloadNextChapterWifiOnly.first())
    }

    // ── Stahování ────────────────────────────────────────────────────────────

    @Test
    fun `download settings round-trip`() = runTest {
        val s = repository()
        assertFalse(s.downloadOnlyWifi.first())
        assertEquals(3, s.parallelDownloads.first())
        assertFalse(s.autoDeleteRead.first())
        assertEquals(0, s.autoDeleteDelayDays.first())
        assertFalse(s.saveAsCbz.first())
        assertNull(s.downloadFolderUri.first())

        s.setDownloadOnlyWifi(true); s.setParallelDownloads(5)
        s.setAutoDeleteRead(true); s.setAutoDeleteDelayDays(7)
        s.setSaveAsCbz(true); s.setDownloadFolderUri("content://dl")

        assertTrue(s.downloadOnlyWifi.first())
        assertEquals(5, s.parallelDownloads.first())
        assertTrue(s.autoDeleteRead.first())
        assertEquals(7, s.autoDeleteDelayDays.first())
        assertTrue(s.saveAsCbz.first())
        assertEquals("content://dl", s.downloadFolderUri.first())

        s.setDownloadFolderUri(null)
        assertNull("null musí klíč odebrat", s.downloadFolderUri.first())
    }

    // ── Aktualizace + notifikace ─────────────────────────────────────────────

    @Test
    fun `update interval and notification switches round-trip`() = runTest {
        val s = repository()
        assertEquals(12L, s.updateIntervalHours.first())
        assertTrue(s.notifyNewChapters.first())
        assertTrue(s.notifyDownloads.first())

        s.setUpdateIntervalHours(6)
        s.setNotifyNewChapters(false); s.setNotifyDownloads(false)

        assertEquals(6L, s.updateIntervalHours.first())
        assertFalse(s.notifyNewChapters.first())
        assertFalse(s.notifyDownloads.first())
    }

    // ── Proxy / image proxy / BYOK ───────────────────────────────────────────

    @Test
    fun `image proxy switch round-trip`() = runTest {
        val s = repository()
        assertFalse(s.imageProxyEnabled.first())
        s.setImageProxyEnabled(true)
        assertTrue(s.imageProxyEnabled.first())
    }

    @Test
    fun `proxy - default null, full set, clear removes everything`() = runTest {
        val s = repository()
        assertNull(s.proxy.first())

        s.setProxy(StoredProxy("SOCKS5", "10.0.0.1", 1080, "joe"))
        val stored = s.proxy.first()
        assertEquals(StoredProxy("SOCKS5", "10.0.0.1", 1080, "joe"), stored)

        s.setProxy(StoredProxy("HTTP", "10.0.0.2", 8080, null))
        val noUser = s.proxy.first()
        assertNull("proxy bez uživatele nemá smět zůstat staré jméno", noUser?.user)

        s.setProxy(null)
        assertNull(s.proxy.first())
    }

    @Test
    fun `byok config round-trip`() = runTest {
        val s = repository()
        assertFalse(s.byokEnabled.first())
        assertEquals("", s.byokBaseUrl.first())
        assertEquals("", s.byokModel.first())

        s.setByokEnabled(true); s.setByokBaseUrl("http://localhost:1234/v1"); s.setByokModel("llama")

        assertTrue(s.byokEnabled.first())
        assertEquals("http://localhost:1234/v1", s.byokBaseUrl.first())
        assertEquals("llama", s.byokModel.first())
    }

    // ── Zdroje ───────────────────────────────────────────────────────────────

    @Test
    fun `favorite sources toggle adds and removes`() = runTest {
        val s = repository()
        assertTrue(s.favoriteSourceIds.first().isEmpty())

        s.toggleFavoriteSource("mangadex"); s.toggleFavoriteSource("comick")

        assertEquals(setOf("mangadex", "comick"), s.favoriteSourceIds.first())

        s.toggleFavoriteSource("mangadex")
        assertEquals(setOf("comick"), s.favoriteSourceIds.first())
    }

    @Test
    fun `source domain overrides - set, multi-source, per-source and last removal`() = runTest {
        val s = repository()
        assertTrue(s.sourceDomainOverrides.first().isEmpty())

        s.setSourceDomainOverride("mangadex", "md.example.com")
        s.setSourceDomainOverride("comick", "ck.example.com")
        assertEquals(
            mapOf("mangadex" to "md.example.com", "comick" to "ck.example.com"),
            s.sourceDomainOverrides.first(),
        )

        s.setSourceDomainOverride("mangadex", null)
        assertEquals(mapOf("comick" to "ck.example.com"), s.sourceDomainOverrides.first())

        s.setSourceDomainOverride("comick", null)
        assertTrue("poslední odebraná doména musí smazat celý klíč", s.sourceDomainOverrides.first().isEmpty())
    }

    @Test
    fun `domain overrides codec ignores malformed lines`() = runTest {
        val raw = "ok\tgood.example.com\nnoTab\ntabAtEnd\t\n\temptyKey\nsecond\ttwo.example.com"
        val decoded = decodeDomainOverrides(raw)
        assertEquals(mapOf("ok" to "good.example.com", "second" to "two.example.com"), decoded)
        assertEquals(raw.split("\n").filter { it.startsWith("ok\t") || it.startsWith("second\t") }.joinToString("\n"), encodeDomainOverrides(decoded))
    }

    // ── ComicK Aktualizace preferences ───────────────────────────────────────

    @Test
    fun `comick updates preferences - defaults and round-trip`() = runTest {
        val s = repository()
        assertEquals(setOf("jp", "kr", "cn", "others"), s.comickUpdatesCountries.first())
        assertEquals(setOf("0", "1", "2", "3", "4"), s.comickUpdatesDemographics.first())
        assertTrue("mature opt-in výchozí prázdný", s.comickUpdatesMatureFlags.first().isEmpty())

        s.setComickUpdatesCountries(setOf("jp", "kr"))
        s.setComickUpdatesDemographics(setOf("1"))
        s.setComickUpdatesMatureFlags(setOf("suggestive"))

        assertEquals(setOf("jp", "kr"), s.comickUpdatesCountries.first())
        assertEquals(setOf("1"), s.comickUpdatesDemographics.first())
        assertEquals(setOf("suggestive"), s.comickUpdatesMatureFlags.first())
    }

    // ── Záloha ───────────────────────────────────────────────────────────────

    @Test
    fun `backup settings round-trip`() = runTest {
        val s = repository()
        assertFalse(s.autoBackupEnabled.first())
        assertNull(s.backupFolderUri.first())

        s.setAutoBackupEnabled(true); s.setBackupFolderUri("content://backup")

        assertTrue(s.autoBackupEnabled.first())
        assertEquals("content://backup", s.backupFolderUri.first())

        s.setBackupFolderUri(null)
        assertNull(s.backupFolderUri.first())
    }

    // ── Soukromí ─────────────────────────────────────────────────────────────

    @Test
    fun `crash reporting defaults off and round-trips`() = runTest {
        val s = repository()
        assertFalse("crash reporting je opt-in", s.crashReporting.first())
        s.setCrashReporting(true)
        assertTrue(s.crashReporting.first())
    }

    // ── Onboarding / app mode ────────────────────────────────────────────────

    @Test
    fun `onboarding flag flips to done`() = runTest {
        val s = repository()
        assertFalse(s.onboardingCompleted.first())
        s.setOnboardingCompleted()
        assertTrue(s.onboardingCompleted.first())
    }

    @Test
    fun `app mode accepts all four modes`() = runTest {
        val s = repository()
        assertEquals(AppMode.SOURCES, s.appMode.first())
        for (mode in listOf(AppMode.COMICK, AppMode.NOVEL, AppMode.COMIC, AppMode.SOURCES)) {
            s.setAppMode(mode)
            assertEquals(mode, s.appMode.first())
        }
    }

    @Test
    fun `aggregated modes - toggles are independent, disabling active falls back`() = runTest {
        val s = repository()
        // Čerstvá instalace (appMode=SOURCES, klíč AGGREGATED_MODES chybí) = nic povoleno.
        assertEquals(emptySet<String>(), s.aggregatedModes.first())

        // Dva toggly zapnuté zároveň - vzájemně se nevylučují.
        s.setAggregatedModeEnabled(AppMode.COMICK, true)
        s.setAggregatedModeEnabled(AppMode.NOVEL, true)
        assertEquals(setOf(AppMode.COMICK, AppMode.NOVEL), s.aggregatedModes.first())
        // Zapnutí režimu ho rovnou aktivuje.
        assertEquals(AppMode.NOVEL, s.appMode.first())

        // Vypnutí aktivního režimu padne zpět na klasické zdroje, druhý zůstane povolený.
        s.setAggregatedModeEnabled(AppMode.NOVEL, false)
        assertEquals(setOf(AppMode.COMICK), s.aggregatedModes.first())
        assertEquals(AppMode.SOURCES, s.appMode.first())
    }

    @Test
    fun `aggregated modes - legacy install derives set from active appMode`() = runTest {
        // Stará instalace má jen APP_MODE, AGGREGATED_MODES se odvodí z ní -
        // aktivní režim musí být povolený, jinak by sheet nenabídl to, co běží.
        val ds = FakeDataStore()
        ds.edit { it[SettingsKeys.APP_MODE] = AppMode.COMIC }
        val s = SettingsRepository(ds)

        assertEquals(setOf(AppMode.COMIC), s.aggregatedModes.first())
    }

    @Test
    fun `aggregated modes - setAppMode keeps active mode inside enabled set`() = runTest {
        val s = repository()
        s.setAggregatedModeEnabled(AppMode.NOVEL, true)

        // Jakýkoli volající (onboarding, deep link) nastaví aktivní režim, který
        // v povolené sadě chybí -> doplní se, aby stav nebyl nekonzistentní.
        s.setAppMode(AppMode.COMIC)
        assertEquals(setOf(AppMode.NOVEL, AppMode.COMIC), s.aggregatedModes.first())
        assertEquals(AppMode.COMIC, s.appMode.first())
    }

    @Test
    fun `aggregated modes - sources and unknown values are ignored`() = runTest {
        val s = repository()
        s.setAggregatedModeEnabled(AppMode.SOURCES, true)
        s.setAggregatedModeEnabled("bogus-mode", true)
        assertTrue(s.aggregatedModes.first().isEmpty())
    }

    // ── Hledání ──────────────────────────────────────────────────────────────

    @Test
    fun `saved searches - newest first, no duplicates, capped at ten, removable`() = runTest {
        val s = repository()
        assertTrue(s.savedSearches.first().isEmpty())

        s.addSavedSearch("one piece"); s.addSavedSearch("berserk")
        assertEquals(listOf("berserk", "one piece"), s.savedSearches.first())

        s.addSavedSearch("one piece")
        assertEquals("duplicita se nepřidá", listOf("berserk", "one piece"), s.savedSearches.first())

        repeat(12) { s.addSavedSearch("q$it") }
        assertEquals(10, s.savedSearches.first().size)

        s.removeSavedSearch("q11")
        assertFalse(s.savedSearches.first().contains("q11"))
        assertEquals(9, s.savedSearches.first().size)
    }

    @Test
    fun `comick search history - re-query moves to front, capped, removable`() = runTest {
        val s = repository()
        assertTrue(s.comickSearchHistory.first().isEmpty())

        s.addComickSearchHistory("a"); s.addComickSearchHistory("b")
        assertEquals(listOf("b", "a"), s.comickSearchHistory.first())

        s.addComickSearchHistory("a")
        assertEquals("opakovaný dotaz skočí dopředu", listOf("a", "b"), s.comickSearchHistory.first())

        repeat(12) { s.addComickSearchHistory("h$it") }
        assertEquals(10, s.comickSearchHistory.first().size)

        s.removeComickSearchHistory("h11")
        assertFalse(s.comickSearchHistory.first().contains("h11"))
    }

    // ── Statistiky čtení ─────────────────────────────────────────────────────

    @Test
    fun `reading time accumulates total and today`() = runTest {
        val s = repository()
        assertEquals(0L, s.totalReadingTimeMs.first())
        assertEquals(0L, s.todayReadingTimeMs.first())

        s.addReadingTime(60_000); s.addReadingTime(30_000)

        assertEquals(90_000L, s.totalReadingTimeMs.first())
        assertEquals("stejný den se sčítá", 90_000L, s.todayReadingTimeMs.first())
    }

    @Test
    fun `today reading time is zero when stored day is yesterday`() = runTest {
        val ds = FakeDataStore()
        ds.edit { prefs ->
            prefs[SettingsKeys.DAILY_READING_DAY] = java.time.LocalDate.now().minusDays(1).toString()
            prefs[SettingsKeys.DAILY_READING_TIME] = 45_000L
        }
        val s = SettingsRepository(ds)

        assertEquals("včerejší čas se do dneška nepočítá", 0L, s.todayReadingTimeMs.first())
    }

    @Test
    fun `pages read accumulates`() = runTest {
        val s = repository()
        assertEquals(0L, s.totalPagesRead.first())
        s.addPagesRead(20); s.addPagesRead(15)
        assertEquals(35L, s.totalPagesRead.first())
    }

    // ── Streak ───────────────────────────────────────────────────────────────

    @Test
    fun `reading streak - first read starts at one`() = runTest {
        val s = repository()
        s.updateReadingStreak()
        assertEquals(1, s.readingStreak.first())
    }

    @Test
    fun `reading streak - same day does not increment`() = runTest {
        val ds = FakeDataStore()
        ds.edit { prefs ->
            prefs[SettingsKeys.LAST_READ_DATE] = java.time.LocalDate.now().toString()
            prefs[SettingsKeys.READING_STREAK_DAYS] = 5
        }
        val s = SettingsRepository(ds)
        s.updateReadingStreak()
        assertEquals(5, s.readingStreak.first())
    }

    @Test
    fun `reading streak - consecutive day increments`() = runTest {
        val ds = FakeDataStore()
        ds.edit { prefs ->
            prefs[SettingsKeys.LAST_READ_DATE] = java.time.LocalDate.now().minusDays(1).toString()
            prefs[SettingsKeys.READING_STREAK_DAYS] = 5
        }
        val s = SettingsRepository(ds)
        s.updateReadingStreak()
        assertEquals(6, s.readingStreak.first())
    }

    @Test
    fun `reading streak - gap resets to one`() = runTest {
        val ds = FakeDataStore()
        ds.edit { prefs ->
            prefs[SettingsKeys.LAST_READ_DATE] = java.time.LocalDate.now().minusDays(4).toString()
            prefs[SettingsKeys.READING_STREAK_DAYS] = 9
        }
        val s = SettingsRepository(ds)
        s.updateReadingStreak()
        assertEquals(1, s.readingStreak.first())
    }

    // ── Sync / knihovna interní stav ─────────────────────────────────────────

    @Test
    fun `pending removed manga ids add and clear subset`() = runTest {
        val s = repository()
        assertTrue(s.pendingRemovedMangaIds.first().isEmpty())

        s.addPendingRemovedMangaId("m1"); s.addPendingRemovedMangaId("m2"); s.addPendingRemovedMangaId("m1")
        assertEquals(setOf("m1", "m2"), s.pendingRemovedMangaIds.first())

        s.clearPendingRemovedMangaIds(setOf("m1"))
        assertEquals(setOf("m2"), s.pendingRemovedMangaIds.first())
    }

    @Test
    fun `local data owner and sync push timestamp round-trip`() = runTest {
        val s = repository()
        assertNull(s.localDataOwnerId.first())
        assertEquals(0L, s.syncLastChapterPushAt.first())

        s.setLocalDataOwnerId("user-7"); s.setSyncLastChapterPushAt(123_456L)

        assertEquals("user-7", s.localDataOwnerId.first())
        assertEquals(123_456L, s.syncLastChapterPushAt.first())
    }

    @Test
    fun `anilist id map round-trip`() = runTest {
        val s = repository()
        assertEquals("{}", s.aniListIdMap.first())
        s.saveAniListIdMap("""{"a":1}""")
        assertEquals("""{"a":1}""", s.aniListIdMap.first())
    }

    @Test
    fun `new chapters counter adds and clears`() = runTest {
        val s = repository()
        assertEquals(0, s.newChaptersCount.first())
        s.addNewChapters(3); s.addNewChapters(2)
        assertEquals(5, s.newChaptersCount.first())
        s.clearNewChapters()
        assertEquals(0, s.newChaptersCount.first())
    }

    @Test
    fun `pending update download id set and cleared`() = runTest {
        val s = repository()
        assertNull(s.pendingUpdateDownloadId.first())
        s.setPendingUpdateDownloadId(42)
        assertEquals(42L, s.pendingUpdateDownloadId.first())
        s.setPendingUpdateDownloadId(null)
        assertNull("null musí klíč odebrat", s.pendingUpdateDownloadId.first())
    }

    // ── Vlastní obsah čtečky ─────────────────────────────────────────────────

    @Test
    fun `custom css, font url round-trip`() = runTest {
        val s = repository()
        assertEquals("", s.customCss.first())
        assertEquals("", s.customFontUrl.first())

        s.setCustomCss("body{color:red}"); s.setCustomFontUrl("https://f.example/font.ttf")

        assertEquals("body{color:red}", s.customCss.first())
        assertEquals("https://f.example/font.ttf", s.customFontUrl.first())
    }
}
