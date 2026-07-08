package com.haise.jiyu.ui.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.haise.jiyu.BuildConfig
import com.haise.jiyu.MainActivity
import com.haise.jiyu.data.tracking.MalAuthManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MalCallbackActivity : ComponentActivity() {

    @Inject
    lateinit var malAuthManager: MalAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val code = intent?.data?.getQueryParameter("code")
        if (code != null) {
            lifecycleScope.launch {
                val clientId = BuildConfig.MAL_CLIENT_ID
                if (clientId.isNotBlank()) {
                    malAuthManager.handleCallback(code, clientId)
                }
                startActivity(Intent(this@MalCallbackActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                finish()
            }
        } else {
            finish()
        }
    }
}
