package com.haise.jiyu

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.navigation.compose.rememberNavController
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.ui.navigation.MainScreen
import com.haise.jiyu.ui.theme.JiyuTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* výsledek ignorujeme — appka funguje bez notifikací */ }

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

        setContent {
            val theme by settings.theme.collectAsState(initial = ThemeOption.SYSTEM)
            val isDark = when (theme) {
                ThemeOption.DARK  -> true
                ThemeOption.LIGHT -> false
                else              -> isSystemInDarkTheme()
            }

            // Přizpůsob barvu ikon systémových lišt aktuálnímu tématu
            SideEffect {
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }

            JiyuTheme(forceDark = when (theme) {
                ThemeOption.DARK  -> true
                ThemeOption.LIGHT -> false
                else              -> null
            }) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    MainScreen(navController = navController)
                }
            }
        }
    }
}
