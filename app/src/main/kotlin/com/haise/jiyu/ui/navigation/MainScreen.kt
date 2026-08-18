package com.haise.jiyu.ui.navigation

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.haise.jiyu.R
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet

/** Kolik ms po opusteni zalozky jeste appka pri navratu obnovi rozkliknuty stav. */
private const val GRACE_PERIOD_MS = 4_000L

private data class NavTab(
    val route: String,
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

@Composable
private fun rememberNavTabs(appMode: String): List<NavTab> = listOf(
    NavTab(Routes.LIBRARY,  stringResource(R.string.main_screen_tab_library),  TablerIcons.Book,        TablerIcons.Book),
    NavTab(Routes.MY_LIST,  stringResource(R.string.main_screen_tab_list),     TablerIcons.ListCheck,   TablerIcons.ListCheck),
    NavTab(Routes.UPDATES,  stringResource(R.string.main_screen_tab_updates), TablerIcons.Compass,     TablerIcons.Compass),
    NavTab(Routes.browseRoute(appMode), stringResource(R.string.main_screen_tab_browse),  TablerIcons.Search,      TablerIcons.Search),
    NavTab(Routes.SETTINGS, stringResource(R.string.settings_title),          TablerIcons.User,        TablerIcons.User),
)

@Composable
fun MainScreen(
    navController: androidx.navigation.NavHostController,
    startDestination: String = Routes.LIBRARY,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val newChaptersCount by viewModel.newChaptersCount.collectAsState()
    val appMode by viewModel.appMode.collectAsState()
    val tabs = rememberNavTabs(appMode)
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination
    val currentRoute = currentDest?.route

    // Ktera zalozka je "aktivni" - na rozdil od currentRoute prezije rozkliknuti
    // dal (napr. na detail titulu z Prochazet), protoze graf je plochy a detail
    // neni soucasti hierarchie zadne zalozky. Meni se jen klepnutim na zalozku.
    var activeTabRoute by remember { mutableStateOf(startDestination) }
    // Kdy jsme naposledy z ktere zalozky odesli - viz GRACE_PERIOD_MS nize.
    val tabLeftAt = remember { mutableMapOf<String, Long>() }

    val showNavBar = currentRoute != null &&
        !currentRoute.startsWith(Routes.READER.substringBefore("{")) &&
        !currentRoute.startsWith(Routes.QR.substringBefore("{")) &&
        currentRoute != Routes.ONBOARDING &&
        currentRoute != Routes.GLOBAL_SEARCH &&
        currentRoute != Routes.STATS &&
        currentRoute != Routes.CUSTOM_CSS &&
        currentRoute != Routes.DOWNLOADS &&
        currentRoute != Routes.ACCOUNT &&
        currentRoute != Routes.CATALOG

    Scaffold(
        containerColor = Color.Transparent,
        // Kazda obrazovka uz sama resi status bar padding (statusBarsPadding()
        // v hlavicce) a bottom nav bar uz sam pocita navigationBars inset -
        // vychozi Scaffold.contentWindowInsets (systemBars) by to zdvojilo
        // a vytvorilo velkou prazdnou mezeru nahore na kazde obrazovce.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showNavBar) {
                NavigationBar(
                    containerColor = NightBlue.copy(alpha = 0.95f),
                    tonalElevation = 0.dp,
                ) {
                    tabs.forEach { tab ->
                        val selected = if (tab.route.startsWith(Routes.SOURCE_BROWSE.substringBefore("{"))) {
                            val expectedSourceId = tab.route.substringAfterLast("/")
                            currentDest?.hierarchy?.any { it.route == Routes.SOURCE_BROWSE } == true &&
                                navBackStack?.arguments?.getString("sourceId") == expectedSourceId
                        } else {
                            currentDest?.hierarchy?.any { it.route == tab.route } == true
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                // Nahodne prehozeni zalozky (napr. omylem klepnu na Nastaveni,
                                // kdyz jsem rozkliknuty na detailu titulu z Prochazet) nema
                                // rozkliknuty stav hned zahodit - ma par sekund na to se vratit.
                                // Po uplynuti GRACE_PERIOD_MS uz se zalozka chova jako driv a
                                // resetuje se zpatky na koren.
                                if (activeTabRoute != tab.route) {
                                    tabLeftAt[activeTabRoute] = System.currentTimeMillis()
                                }
                                val leftAt = tabLeftAt[tab.route]
                                val withinGracePeriod = leftAt != null && System.currentTimeMillis() - leftAt < GRACE_PERIOD_MS
                                activeTabRoute = tab.route
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        inclusive = false
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = withinGracePeriod
                                }
                            },
                            icon = {
                                val showBadge = tab.route == Routes.UPDATES && newChaptersCount > 0
                                if (showBadge) {
                                    BadgedBox(badge = {
                                        Badge {
                                            Text(if (newChaptersCount > 99) "99+" else "$newChaptersCount")
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                                            contentDescription = tab.label,
                                        )
                                    }
                                } else {
                                    Icon(
                                        imageVector = if (selected) tab.iconSelected else tab.iconUnselected,
                                        contentDescription = tab.label,
                                    )
                                }
                            },
                            label = { Text(tab.label, fontSize = 10.sp) },
                            alwaysShowLabel = false,
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Violet,
                                selectedTextColor = Violet,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary,
                                indicatorColor = Violet.copy(alpha = 0.15f),
                            ),
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            JiyuNavGraph(navController = navController, startDestination = startDestination, appMode = appMode)
        }
    }
}
