package com.haise.jiyu.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.MainActivity
import com.haise.jiyu.R
import com.haise.jiyu.data.tracking.ComickAuthManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Callback pro Comick OAuth - URI `com.haise.jiyu:/oauth/comick?code=..&state=..`
 * (reverse-domain scheme, viz AndroidManifest). Oproti MAL callbacku se muze objevit
 * i `error` param (uzivatel zamitl consent) - proste zavreme a uzivatel muze zkusit znovu.
 */
@AndroidEntryPoint
class ComickCallbackActivity : ComponentActivity() {

    @Inject
    lateinit var comickAuthManager: ComickAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        val code = uri?.getQueryParameter("code")
        val state = uri?.getQueryParameter("state")
        // Filtr matchuje cele scheme com.haise.jiyu - obslouzime jen oauth path.
        if (code != null && uri?.path?.startsWith("/oauth") == true) {
            lifecycleScope.launch {
                val clientId = BuildConfig.COMICK_CLIENT_ID
                val ok = clientId.isNotBlank() &&
                    comickAuthManager.handleCallback(code, clientId, state)
                if (!ok) {
                    // Bez tohohle by selhani vymeny tokenu probehlo tise - uzivatel by
                    // v settings videl "nepripojeno" bez jakehokoliv vysvetleni.
                    android.widget.Toast.makeText(
                        this@ComickCallbackActivity,
                        R.string.comick_auth_failed,
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                startActivity(Intent(this@ComickCallbackActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                finish()
            }
        } else {
            finish()
        }
    }
}
