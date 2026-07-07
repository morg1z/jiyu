package com.haise.jiyu.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.haise.jiyu.ui.account.AccountScreen
import com.haise.jiyu.ui.browse.BrowseScreen
import com.haise.jiyu.ui.detail.MangaDetailScreen
import com.haise.jiyu.ui.downloads.DownloadManagerScreen
import com.haise.jiyu.ui.history.HistoryScreen
import com.haise.jiyu.ui.library.LibraryScreen
import com.haise.jiyu.ui.reader.ReaderScreen
import com.haise.jiyu.ui.search.GlobalSearchScreen
import com.haise.jiyu.ui.settings.SettingsScreen
import com.haise.jiyu.ui.settings.SourceCatalogScreen
import com.haise.jiyu.ui.stats.ExtendedStatsScreen

internal object Routes {
    const val LIBRARY       = "library"
    const val BROWSE        = "browse"
    const val DETAIL        = "detail/{mangaId}"
    const val READER        = "reader/{chapterId}"
    const val SETTINGS      = "settings"
    const val DOWNLOADS     = "downloads"
    const val HISTORY       = "history"
    const val CATALOG       = "catalog"
    const val ACCOUNT       = "account"
    const val GLOBAL_SEARCH = "global_search"
    const val STATS         = "stats"

    fun detail(mangaId: String) = "detail/$mangaId"
    fun reader(chapterId: String) = "reader/$chapterId"
}

@Composable
fun JiyuNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.LIBRARY) {

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
                onOpenBrowse = { navController.navigate(Routes.BROWSE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenChapter = { chapterId -> navController.navigate(Routes.reader(chapterId)) },
                onOpenStats = { navController.navigate(Routes.STATS) },
            )
        }

        composable(Routes.BROWSE) {
            BrowseScreen(
                onMangaAdded = { mangaId ->
                    navController.navigate(Routes.detail(mangaId)) {
                        popUpTo(Routes.LIBRARY)
                    }
                },
                onGlobalSearch = { navController.navigate(Routes.GLOBAL_SEARCH) },
            )
        }

        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("mangaId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = "jiyu://manga?mangaId={mangaId}" }),
        ) {
            MangaDetailScreen(
                onOpenChapter = { chapterId -> navController.navigate(Routes.reader(chapterId)) },
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("chapterId") { type = NavType.StringType }),
        ) {
            ReaderScreen()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDownloadManager = { navController.navigate(Routes.DOWNLOADS) },
                onOpenSourceCatalog = { navController.navigate(Routes.CATALOG) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
            )
        }

        composable(Routes.ACCOUNT) {
            AccountScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CATALOG) {
            SourceCatalogScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DOWNLOADS) {
            DownloadManagerScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onResumeReading = { chapterId -> navController.navigate(Routes.reader(chapterId)) },
            )
        }

        composable(Routes.GLOBAL_SEARCH) {
            GlobalSearchScreen(
                onBack = { navController.popBackStack() },
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
            )
        }

        composable(Routes.STATS) {
            ExtendedStatsScreen(onBack = { navController.popBackStack() })
        }
    }
}
