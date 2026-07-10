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
import com.haise.jiyu.ui.community.CommunityScreen
import com.haise.jiyu.ui.css.CustomCssScreen
import com.haise.jiyu.ui.detail.MangaDetailScreen
import com.haise.jiyu.ui.downloads.DownloadManagerScreen
import com.haise.jiyu.ui.goals.ReadingGoalsScreen
import com.haise.jiyu.ui.history.HistoryScreen
import com.haise.jiyu.ui.library.LibraryScreen
import com.haise.jiyu.ui.onboarding.OnboardingScreen
import com.haise.jiyu.ui.qr.MangaQrScreen
import com.haise.jiyu.ui.reader.ReaderScreen
import com.haise.jiyu.ui.search.GlobalSearchScreen
import com.haise.jiyu.ui.settings.SettingsScreen
import com.haise.jiyu.ui.settings.SourceCatalogScreen
import com.haise.jiyu.ui.duplicates.DuplicateDetectorScreen
import com.haise.jiyu.ui.stats.ExtendedStatsScreen
import com.haise.jiyu.ui.updates.UpdatesScreen

internal object Routes {
    const val ONBOARDING    = "onboarding"
    const val LIBRARY       = "library"
    const val UPDATES       = "updates"
    const val BROWSE        = "browse"
    const val DETAIL        = "detail/{mangaId}"
    const val READER        = "reader/{chapterId}?incognito={incognito}"
    const val SETTINGS      = "settings"
    const val DOWNLOADS     = "downloads"
    const val HISTORY       = "history"
    const val CATALOG       = "catalog"
    const val ACCOUNT       = "account"
    const val GLOBAL_SEARCH = "global_search"
    const val STATS         = "stats"
    const val GOALS         = "goals"
    const val COMMUNITY     = "community"
    const val CUSTOM_CSS    = "custom_css"
    const val DUPLICATES    = "duplicates"
    const val QR            = "qr/{mangaId}?title={mangaTitle}"

    fun detail(mangaId: String) = "detail/${android.net.Uri.encode(mangaId)}"
    fun reader(chapterId: String, incognito: Boolean = false) =
        "reader/${android.net.Uri.encode(chapterId)}?incognito=$incognito"
    fun qr(mangaId: String, mangaTitle: String) =
        "qr/${android.net.Uri.encode(mangaId)}?title=${android.net.Uri.encode(mangaTitle)}"
}

@Composable
fun JiyuNavGraph(
    navController: NavHostController,
    startDestination: String = Routes.LIBRARY,
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Routes.LIBRARY) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.LIBRARY) {
            LibraryScreen(
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
                onOpenBrowse = { navController.navigate(Routes.BROWSE) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenChapter = { chapterId -> navController.navigate(Routes.reader(chapterId)) },
                onOpenStats = { navController.navigate(Routes.STATS) },
            )
        }

        composable(Routes.UPDATES) {
            UpdatesScreen(
                onOpenChapter = { chapterId -> navController.navigate(Routes.reader(chapterId)) },
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
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
                onOpenChapterIncognito = { chapterId -> navController.navigate(Routes.reader(chapterId, incognito = true)) },
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
                onOpenQr = { mangaId, title -> navController.navigate(Routes.qr(mangaId, title)) },
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("chapterId") { type = NavType.StringType },
                navArgument("incognito") { type = NavType.BoolType; defaultValue = false },
            ),
        ) {
            ReaderScreen()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenDownloadManager = { navController.navigate(Routes.DOWNLOADS) },
                onOpenSourceCatalog = { navController.navigate(Routes.CATALOG) },
                onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                onOpenCustomCss = { navController.navigate(Routes.CUSTOM_CSS) },
                onOpenGoals = { navController.navigate(Routes.GOALS) },
                onOpenCommunity = { navController.navigate(Routes.COMMUNITY) },
                onOpenDuplicates = { navController.navigate(Routes.DUPLICATES) },
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
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
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

        composable(Routes.GOALS) {
            ReadingGoalsScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.COMMUNITY) {
            CommunityScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.CUSTOM_CSS) {
            CustomCssScreen(onBack = { navController.popBackStack() })
        }

        composable(Routes.DUPLICATES) {
            DuplicateDetectorScreen(
                onBack = { navController.popBackStack() },
                onOpenManga = { mangaId -> navController.navigate(Routes.detail(mangaId)) },
            )
        }

        composable(
            route = Routes.QR,
            arguments = listOf(
                navArgument("mangaId") { type = NavType.StringType },
                navArgument("mangaTitle") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { backStack ->
            val mangaId = backStack.arguments?.getString("mangaId") ?: ""
            val mangaTitle = backStack.arguments?.getString("mangaTitle") ?: ""
            MangaQrScreen(
                mangaId = mangaId,
                mangaTitle = mangaTitle,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
