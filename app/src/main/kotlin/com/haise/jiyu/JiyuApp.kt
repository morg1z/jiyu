package com.haise.jiyu

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Vstupní bod aplikace. Hilt tady zapíná DI graf pro celou appku
 * (databáze, síťové klienty, zdroje, download manager...).
 */
@HiltAndroidApp
class JiyuApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
