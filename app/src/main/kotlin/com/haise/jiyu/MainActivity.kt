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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.res.stringResource
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
import io.github.jan.supabase.gotrue.handleDeeplinks
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
    @Inject lateinit var supabase: io.github.jan.supabase.SupabaseClient

    private val _pendingDeepLink = MutableStateFlow<Intent?>(null)
    /** jiyu://auth?… / #access_token=… - recovery odkaz z e-mailu (reset hesla). Jde mimo
     *  navDeepLink cesty: session se musí importovat přes Supabase, ne přes mapování route. */
    private val _pendingAuthLink = MutableStateFlow<Intent?>(null)

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* výsledek ignorujeme — appka funguje bez notifikací */ }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri: Uri = intent.data ?: return
        when {
            uri.scheme != "jiyu" -> Unit
            // SEC-1: auth link pustime dal jen s dukazem, ze je to recovery odkaz -
            // cizi intent bez type=recovery/code by jinak mohl importovat SESSION
            // utocnikova uctu (staci, kdyz obet klikne na jeho reset link).
            uri.host == "auth" -> if (isRecoveryAuthLink(uri.toString())) _pendingAuthLink.value = intent
            uri.host != "anilist" -> _pendingDeepLink.value = intent
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
            intent?.data?.let { uri ->
                when {
                    uri.scheme != "jiyu" -> Unit
                    uri.host == "auth" -> if (isRecoveryAuthLink(uri.toString())) _pendingAuthLink.value = intent
                    uri.host != "anilist" && uri.host != "mal-auth" -> _pendingDeepLink.value = intent
                }
            }
        }

        setContent {
            val theme by settings.theme.collectAsStateWithLifecycle(initialValue = ThemeOption.SYSTEM)
            // effectiveThemeAccent: během tahu hue sliderem ve Vzhledu nese živý náhled
            // (in-memory), jinak persistovanou hodnotu - celá app se přebarvuje za tahu.
            val accent by settings.effectiveThemeAccent.collectAsStateWithLifecycle(initialValue = com.haise.jiyu.settings.AccentOption.VIOLET)
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

            JiyuTheme(mode = theme, accentKey = accent) {
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
                        val pendingAuthLink by _pendingAuthLink.collectAsStateWithLifecycle()
                        pendingAuthLink?.let { authIntent ->
                            // SEC-1: session import z externího intentu jen po potvrzení
                            // uzivatelem - bez dialogu by stacilo nechat obet kliknout na
                            // cizi reset link a appka by tise nacalovala utocnikovu session.
                            AlertDialog(
                                onDismissRequest = { _pendingAuthLink.value = null },
                                title = { Text(stringResource(R.string.auth_reset_link_title)) },
                                text = { Text(stringResource(R.string.auth_reset_link_message)) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        _pendingAuthLink.value = null
                                        // Recovery odkaz z e-mailu: fragment (#access_token…) nebo ?code=
                                        // (PKCE) importuje knihovna asynchronně sama; obrazovka hesla
                                        // mezitím čeká na session (handleDeeplinks nemá error callback -
                                        // prošlý/škaredý link pozná sama přes timeout čekání na přihlášení).
                                        lifecycleScope.launch {
                                            supabase.handleDeeplinks(authIntent)
                                            navController.navigate(com.haise.jiyu.ui.navigation.Routes.RESET_PASSWORD)
                                        }
                                    }) { Text(stringResource(R.string.common_confirm)) }
                                },
                                dismissButton = {
                                    TextButton(onClick = { _pendingAuthLink.value = null }) {
                                        Text(stringResource(R.string.common_cancel))
                                    }
                                },
                            )
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

/**
 * SEC-1: `jiyu://auth` link pustíme do Supabase jen se známkou recovery resetu -
 * `type=recovery` (implicit flow v #fragmentu nebo ?query) nebo PKCE `?code=…`.
 * Cizí intent/QR/škodlivá appka bez tohoto parametru nesmí importovat session -
 * jinak by oběti nacalovala útočníkův účet a sync poslala její knihovnu do jeho
 * cloudu (viz audit finding SEC-1).
 *
 * String verze (ne android.net.Uri) = JVM-testovatelná bez Android runtime.
 */
internal fun isRecoveryAuthLink(url: String): Boolean {
    fun params(part: String): List<List<String>> =
        part.substringAfter('?', "").split('&').map { it.split('=', limit = 2) }

    val query = url.substringBefore('#')
    val fragment = url.substringAfter('#', missingDelimiterValue = "")

    val querySaysRecovery = params(query).any { it[0] == "type" && it.getOrNull(1) == "recovery" }
    val hasPkceCode = params(query).any { it[0] == "code" && it.getOrNull(1)?.isNotEmpty() == true }
    // Implicit flow nese tokeny v #fragmentu jako "access_token=…&type=recovery".
    val fragmentSaysRecovery = fragment.split('&')
        .map { it.split('=', limit = 2) }
        .any { it[0] == "type" && it.getOrNull(1) == "recovery" }

    return querySaysRecovery || hasPkceCode || fragmentSaysRecovery
}
