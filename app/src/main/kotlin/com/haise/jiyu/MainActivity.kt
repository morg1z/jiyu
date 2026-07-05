package com.haise.jiyu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
import com.haise.jiyu.settings.SettingsRepository
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.ui.navigation.JiyuNavGraph
import com.haise.jiyu.ui.theme.JiyuTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val theme by settings.theme.collectAsState(initial = ThemeOption.SYSTEM)
            val forceDark = when (theme) {
                ThemeOption.DARK  -> true
                ThemeOption.LIGHT -> false
                else              -> null
            }

            JiyuTheme(forceDark = forceDark) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    JiyuNavGraph(navController = navController)
                }
            }
        }
    }
}
