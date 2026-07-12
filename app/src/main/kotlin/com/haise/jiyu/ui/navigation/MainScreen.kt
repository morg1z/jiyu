package com.haise.jiyu.ui.navigation

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet

private data class NavTab(
    val route: String,
    val label: String,
    val iconSelected: ImageVector,
    val iconUnselected: ImageVector,
)

private val TABS = listOf(
    NavTab(Routes.LIBRARY,  "Knihovna",    TablerIcons.Book,        TablerIcons.Book),
    NavTab(Routes.UPDATES,  "Aktualizace", TablerIcons.BellRinging, TablerIcons.BellRinging),
    NavTab(Routes.BROWSE,   "Procházet",   TablerIcons.Search,      TablerIcons.Search),
    NavTab(Routes.HISTORY,  "Historie",    TablerIcons.History,     TablerIcons.History),
    NavTab(Routes.SETTINGS, "Nastavení",   TablerIcons.Settings,    TablerIcons.Settings),
)

@Composable
fun MainScreen(
    navController: androidx.navigation.NavHostController,
    startDestination: String = Routes.LIBRARY,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val newChaptersCount by viewModel.newChaptersCount.collectAsState()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination
    val currentRoute = currentDest?.route

    val showNavBar = currentRoute != null &&
        !currentRoute.startsWith(Routes.READER.substringBefore("{")) &&
        !currentRoute.startsWith(Routes.QR.substringBefore("{")) &&
        currentRoute != Routes.ONBOARDING &&
        currentRoute != Routes.GLOBAL_SEARCH &&
        currentRoute != Routes.STATS &&
        currentRoute != Routes.GOALS &&
        currentRoute != Routes.COMMUNITY &&
        currentRoute != Routes.CUSTOM_CSS &&
        currentRoute != Routes.DOWNLOADS &&
        currentRoute != Routes.ACCOUNT &&
        currentRoute != Routes.CATALOG

    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (showNavBar) {
                NavigationBar(
                    containerColor = NightBlue.copy(alpha = 0.95f),
                    tonalElevation = 0.dp,
                ) {
                    TABS.forEach { tab ->
                        val selected = currentDest?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
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
            JiyuNavGraph(navController = navController, startDestination = startDestination)
        }
    }
}
