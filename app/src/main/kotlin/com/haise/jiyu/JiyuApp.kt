package com.haise.jiyu

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.Coil
import coil.ImageLoader
import com.haise.jiyu.source.mangaplus.MangaPlusImageFetcher
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Vstupní bod aplikace. Hilt tady zapíná DI graf pro celou appku
 * (databáze, síťové klienty, zdroje, download manager...).
 */
@HiltAndroidApp
class JiyuApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var httpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // Registrace custom Coil fetcheru pro XOR-šifrované MANGA Plus obrázky
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(MangaPlusImageFetcher.Factory(httpClient)) }
                .build()
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
