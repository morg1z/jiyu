package com.haise.jiyu

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.source.interceptor.CloudflareChallengeHost
import com.haise.jiyu.ui.navigation.MainScreen
import com.haise.jiyu.ui.theme.JiyuTheme
import com.haise.jiyu.update.ApkUpdateInstaller
import com.haise.jiyu.update.UpdateProgressOverlay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var updateInstaller: ApkUpdateInstaller

    private val _pendingDeepLink = MutableStateFlow<Intent?>(null)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* výsledek ignorujeme — appka funguje bez notifikací */ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri: Uri = intent.data ?: return
        if (uri.scheme == "jiyu" && uri.host != "anilist") {
            _pendingDeepLink.value = intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Edge-to-edge: obsah se kreslí pod status barem i navigační lištou
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // POST_NOTIFICATIONS je runtime permission jen od Androidu 13 (API 33) - na starsich
        // verzich by volani bez SDK guardu bylo zbytecne systemove volani pri KAZDEM vytvoreni
        // activity, a puvodne bezelo jeste PRED super.onCreate(), coz pri obnove po process-death
        // riskovalo, ze registry pro activity-result kontrakty jeste neni pripraveny.
        // Jen při čerstvém startu - rotace (savedInstanceState != null) by jinak dialog s
        // žádostí o oprávnění vyvolala znovu.
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Obsah se kreslí kolem výřezu přední kamery (notch / punch-hole)
        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS

        // Appka běží na celou obrazovku bez rezervovaného pruhu pro status/nav bar
        // (stejně jako čtečka); lišty jdou dočasně vytáhnout tažením od okraje
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Cold start: notifikace nebo widget tap, appka nebyla v paměti
        // Po rotaci/obnově (savedInstanceState != null) je intent pořád ten původní - deep link
        // by se přehrál znovu a vrátil uživatele tam, odkud už odešel.
        if (savedInstanceState == null) {
            intent?.data?.takeIf { it.scheme == "jiyu" && it.host != "anilist" && it.host != "mal-auth" }
                ?.let { _pendingDeepLink.value = intent }
        }

        setContent {
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeOption.SYSTEM)
            // null = ještě načítáme; false = onboarding nutný; true = přeskočit
            val onboardingCompleted by settings.onboardingCompleted.collectAsStateWithLifecycle(initialValue = null)
            val isDark = when (theme) {
                ThemeOption.DARK, ThemeOption.TRUE_BLACK -> true
                ThemeOption.LIGHT                        -> false
                else                                      -> isSystemInDarkTheme()
            }

            // Přizpůsob barvu ikon systémových lišt aktuálnímu tématu
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }

            JiyuTheme(mode = theme) {
                // Počkáme na načtení onboarding statusu — zobrazíme prázdnou plochu
                if (onboardingCompleted != null) {
                    // Explicitni color = colorScheme.background (ne vychozi colorScheme.surface,
                    // ktery je vizualne jina barva - Midnight misto DeepSpace, viz Color.kt).
                    // MainScreen's Scaffold je schvalne containerColor = Transparent (kazda
                    // obrazovka si sama resi statusBarsPadding), takze kdekoli obsah nepokryje
                    // uplne celou fyzickou plochu (pod status barem, u cutoutu), prosvital by
                    // spatny root color jako viditelny pruh jine barvy (nahlaseno uzivatelem).
                    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        val navController = rememberNavController()
                        val pendingDeepLink by _pendingDeepLink.collectAsStateWithLifecycle()
                        LaunchedEffect(pendingDeepLink) {
                            val i = pendingDeepLink ?: return@LaunchedEffect
                            navController.handleDeepLink(i)
                            _pendingDeepLink.value = null
                        }
                        MainScreen(
                            navController = navController,
                            startDestination = if (onboardingCompleted == true)
                                com.haise.jiyu.ui.navigation.Routes.LIBRARY
                            else
                                com.haise.jiyu.ui.navigation.Routes.ONBOARDING,
                        )
                        // Globalni overlay pro interaktivni Cloudflare vyzvy (viz CloudflareInterceptor)
                        CloudflareChallengeHost()
                        // Globalni overlay pro postup stahovani aktualizace (viz ApkUpdateInstaller)
                        UpdateProgressOverlay(installer = updateInstaller)
                    }
                }
            }
        }
    }
}
