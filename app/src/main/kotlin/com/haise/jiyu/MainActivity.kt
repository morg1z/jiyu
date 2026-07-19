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
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
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
        // Android 13+ vyžaduje runtime žádost o POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        super.onCreate(savedInstanceState)

        // Obsah se kreslí kolem výřezu přední kamery (notch / punch-hole)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
        }

        // Cold start: notifikace nebo widget tap, appka nebyla v paměti
        intent?.data?.takeIf { it.scheme == "jiyu" && it.host != "anilist" && it.host != "mal-auth" }
            ?.let { _pendingDeepLink.value = intent }

        setContent {
            val theme by settings.theme.collectAsState(initial = ThemeOption.SYSTEM)
            // null = ještě načítáme; false = onboarding nutný; true = přeskočit
            val onboardingCompleted by settings.onboardingCompleted.collectAsState(initial = null)
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
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val navController = rememberNavController()
                        val pendingDeepLink by _pendingDeepLink.collectAsState()
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
