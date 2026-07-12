package com.haise.jiyu.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stažení a instalace aktualizace přes systémový DownloadManager - appka není na Play
 * Storu, takže update musí projít stejnou cestou jako ruční sideload: uživatel musí
 * povolit instalaci z tohoto zdroje a stažené APK potvrdit v systémovém instalátoru.
 */
@Singleton
class ApkUpdateInstaller @Inject constructor() {

    /** false = uživatel ještě nepovolil appce instalovat balíčky (Android 8+). */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Otevře systémové nastavení, kde uživatel povolí instalaci z této appky. */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${context.packageName}".toUri())
        context.startActivity(intent)
    }

    /**
     * Zařadí stažení APK do systémového DownloadManageru - appka v notifikační liště
     * ukáže postup stahování a po dokončení je notifikace klepnutelná rovnou na instalaci.
     */
    fun downloadUpdate(context: Context, apkUrl: String, version: String) {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Jiyu $version")
            .setDescription("Stahování aktualizace")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "jiyu-update.apk")
        manager.enqueue(request)
    }
}
