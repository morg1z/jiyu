package com.haise.jiyu.ui.navigation

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import com.haise.jiyu.R
import com.haise.jiyu.settings.AppMode
import com.haise.jiyu.ui.components.FeatureTipSheet
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Kolik ms po opusteni zalozky jeste appka pri navratu obnovi rozkliknuty stav. */
private const val GRACE_PERIOD_MS = 4_000L

/** Jak dlouho musi prst drzet zalozku Prochazet, nez se ukaze vyber agregovaneho rezimu. */
private const val LONG_PRESS_MS = 450L

/** Radky ve sheetu pro prepinani rezimu Prochazet (dlouhy stisk na zalozce). */
private data class BrowseModeRow(val mode: String, val labelRes: Int, val icon: ImageVector)

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
    NavTab(Routes.HISTORY,  stringResource(R.string.main_screen_tab_history), TablerIcons.History,     TablerIcons.History),
    NavTab(Routes.SETTINGS, stringResource(R.string.settings_title),          TablerIcons.User,        TablerIcons.User),
)

@Composable
fun MainScreen(
    navController: androidx.navigation.NavHostController,
    startDestination: String = Routes.LIBRARY,
    viewModel: MainViewModel = hiltViewModel(),
) {
    val newChaptersCount by viewModel.newChaptersCount.collectAsStateWithLifecycle()
    val appMode by viewModel.appMode.collectAsStateWithLifecycle()
    val tabs = rememberNavTabs(appMode)
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDest = navBackStack?.destination
    val currentRoute = currentDest?.route

    // Ktera zalozka je "aktivni" - na rozdil od currentRoute prezije rozkliknuti
    // dal (napr. na detail titulu z Prochazet), protoze graf je plochy a detail
    // neni soucasti hierarchie zadne zalozky. Meni se jen klepnutim na zalozku.
    var activeTabRoute by rememberSaveable { mutableStateOf(startDestination) }
    // Kdy jsme naposledy z ktere zalozky odesli - viz GRACE_PERIOD_MS nize.
    val tabLeftAt = remember { mutableMapOf<String, Long>() }

    // ── Dlouhy stisk na "Prochazet" -> vyber agregovaneho rezimu ──────────
    // Detekce z interakci (PressInteraction), ne overlay clickable - zustava
    // nativni ripple i selected stav NavigationBarItem. Pri dlouhem stisku se
    // item docasne ZAKAZE, aby na release neodpalil normalni klik na zalozku.
    val browseRoute = Routes.browseRoute(appMode)
    var showModeSheet by remember { mutableStateOf(false) }
    var showBrowseTip by remember { mutableStateOf(false) }
    var browseItemEnabled by remember { mutableStateOf(true) }
    val browseInteractionSource = remember { MutableInteractionSource() }
    LaunchedEffect(browseInteractionSource) {
        browseInteractionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Press) {
                val ended = withTimeoutOrNull(LONG_PRESS_MS) {
                    browseInteractionSource.interactions.first {
                        it is PressInteraction.Release || it is PressInteraction.Cancel
                    }
                }
                if (ended == null) {
                    browseItemEnabled = false
                    showModeSheet = true
                }
            }
        }
    }
    LaunchedEffect(showModeSheet) { if (!showModeSheet) browseItemEnabled = true }

    // Jednorazovy tip pri prvnim vstupu na zalozku Prochazet - flag je per
    // funkce (ne z ONBOARDING_COMPLETED), takze se ukaze i uzivatelum, kteri
    // onboarding prosli davno pred pridanim prepinace rezimu.
    val browseModeTipShown by viewModel.browseModeTipShown.collectAsStateWithLifecycle()
    LaunchedEffect(currentRoute, browseModeTipShown) {
        if (!browseModeTipShown && currentRoute == browseRoute) showBrowseTip = true
    }

    // Sheet nabizi jen režimy POVOLENE v Nastaveni > Zdroje (toggly se tam
    // uz vzajemne nevylucuji - viz SettingsKeys.AGGREGATED_MODES). Radek
    // "Zdroje jednotlive" je vzdy dostupny jako navrat ke klasickemu rezimu.
    val aggregatedModes by viewModel.aggregatedModes.collectAsStateWithLifecycle()
    val browseModeRows = listOf(
        BrowseModeRow(AppMode.SOURCES, R.string.settings_sources_mode_sources_title, TablerIcons.World),
    ) + listOf(
        BrowseModeRow(AppMode.COMICK,  R.string.settings_sources_mode_comick_title,  TablerIcons.Book),
        BrowseModeRow(AppMode.NOVEL,   R.string.settings_sources_mode_novel_title,   TablerIcons.Bookmark),
        BrowseModeRow(AppMode.COMIC,   R.string.settings_sources_mode_comic_title,   TablerIcons.Stack),
    ).filter { it.mode in aggregatedModes }

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
                        val isBrowseTab = tab.route == browseRoute
                        NavigationBarItem(
                            selected = selected,
                            enabled = !isBrowseTab || browseItemEnabled,
                            interactionSource = if (isBrowseTab) browseInteractionSource else remember { MutableInteractionSource() },
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
                                    popUpTo(Routes.LIBRARY) {
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

    if (showBrowseTip && !showModeSheet) {
        FeatureTipSheet(
            title = stringResource(R.string.tip_browse_title),
            body = stringResource(R.string.tip_browse_body),
            icon = TablerIcons.Search,
            onDismiss = {
                showBrowseTip = false
                viewModel.setBrowseModeTipShown()
            },
        )
    }

    if (showModeSheet) {
        BrowseModeSheet(
            appMode = appMode,
            rows = browseModeRows,
            onDismiss = { showModeSheet = false },
            onPick = { mode ->
                showModeSheet = false
                if (mode != appMode) {
                    viewModel.setAppMode(mode)
                    val route = Routes.browseRoute(mode)
                    activeTabRoute = route
                    navController.navigate(route) {
                        popUpTo(Routes.LIBRARY) { inclusive = false }
                        launchSingleTop = true
                    }
                }
            },
        )
    }
}

/** Mala tabulka pro prepnuti agregovaneho rezimu - otevira se dlouhym stiskem na zalozce Prochazet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseModeSheet(
    appMode: String,
    rows: List<BrowseModeRow>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF111B35),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(
                text = stringResource(R.string.browse_mode_sheet_title),
                color = Violet,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(10.dp))
            rows.forEach { row ->
                val active = row.mode == appMode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = { onPick(row.mode) })
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        tint = if (active) Violet else TextSecondary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                    Text(
                        text = stringResource(row.labelRes),
                        color = if (active) Violet else Color.White,
                        fontSize = 15.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        Icon(
                            imageVector = TablerIcons.Check,
                            contentDescription = null,
                            tint = Violet,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
