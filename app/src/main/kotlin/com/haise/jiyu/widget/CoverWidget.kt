package com.haise.jiyu.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import coil.Coil
import coil.request.ImageRequest
import com.haise.jiyu.R
import com.haise.jiyu.data.db.AppDatabase
import com.haise.jiyu.data.db.ContinueReadingItem
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Widget s obálkou JEDNOHO vybraného titulu (uživatelský požadavek "víc widgetů, třeba
 * covery na knížku, a vybrat i cover") - na rozdíl od [JiyuWidget] (fixní seznam
 * naposledy čtených, bez obrázků) tenhle vždy ukazuje obálku + název + poslední
 * kapitolu titulu, který si uživatel vybral při přidání na plochu (viz
 * [CoverWidgetConfigActivity]). Klepnutí otevře appku rovnou na detailu titulu přes
 * stejný deep link, co používají notifikace (`jiyu://manga?mangaId=...`).
 */
class CoverWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val mangaId = prefs[mangaIdKey]

        val data = withContext(Dispatchers.IO) {
            try {
                if (mangaId == null) return@withContext null
                val db = EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .appDatabase()
                val item = db.mangaDao().getContinueReadingForManga(mangaId) ?: return@withContext null
                CoverWidgetData(item = item, coverBitmap = loadCoverBitmap(context, item.manga.coverUrl))
            } catch (_: Exception) { null }
        }

        val configureLabel = context.getString(R.string.widget_cover_unconfigured)

        provideContent {
            CoverWidgetContent(mangaId = mangaId, data = data, configureLabel = configureLabel)
        }
    }

    companion object {
        val mangaIdKey = stringPreferencesKey("manga_id")
    }
}

private data class CoverWidgetData(val item: ContinueReadingItem, val coverBitmap: Bitmap?)

/** Coil uz je globalne nakonfigurovany v JiyuApp.onCreate (disk cache, MangaPlusImageFetcher
 * atd.) - `Coil.imageLoader(context)` znovupouziva presne tenhle sdileny loader. Hardware
 * bitmapy nejdou pouzit v RemoteViews (widget bezi mimo appku), proto allowHardware(false). */
internal suspend fun loadCoverBitmap(context: Context, url: String?): Bitmap? {
    if (url.isNullOrBlank()) return null
    return try {
        val loader = Coil.imageLoader(context)
        val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
        val drawable = loader.execute(request).drawable ?: return null
        drawableToBitmap(drawable)
    } catch (_: Exception) {
        null
    }
}

private fun drawableToBitmap(drawable: Drawable): Bitmap {
    (drawable as? BitmapDrawable)?.bitmap?.let { return it }
    val width = drawable.intrinsicWidth.coerceAtLeast(1)
    val height = drawable.intrinsicHeight.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/** "318.0" -> "318", "318.5" -> "318.5" - stejny vzor jako jinde v appce. */
private fun formatChapterNumber(number: Float): String =
    if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

@androidx.compose.runtime.Composable
private fun CoverWidgetContent(mangaId: String?, data: CoverWidgetData?, configureLabel: String) {
    val bgColor = ColorProvider(Color(0xFF0D0D1A))
    val textColor = ColorProvider(Color.White)
    val secondaryColor = ColorProvider(Color(0xFF94A3B8))
    val scrimColor = ColorProvider(Color.Black.copy(alpha = 0.55f))

    Box(
        modifier = GlanceModifier.fillMaxSize().background(bgColor).cornerRadius(16.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        if (mangaId == null || data == null) {
            Column(
                modifier = GlanceModifier.fillMaxSize().padding(12.dp)
                    .clickable(actionStartActivity<com.haise.jiyu.MainActivity>()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = configureLabel,
                    style = TextStyle(color = secondaryColor, fontSize = 12.sp),
                )
            }
        } else {
            val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("jiyu://manga?mangaId=$mangaId"))
            if (data.coverBitmap != null) {
                Image(
                    provider = ImageProvider(data.coverBitmap),
                    contentDescription = data.item.manga.title,
                    contentScale = ContentScale.Crop,
                    modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivityIntent(openIntent)),
                )
            } else {
                Box(modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivityIntent(openIntent))) {}
            }
            Column(
                modifier = GlanceModifier.fillMaxWidth().background(scrimColor).padding(10.dp)
                    .clickable(actionStartActivityIntent(openIntent)),
            ) {
                Text(
                    text = data.item.manga.title,
                    style = TextStyle(color = textColor, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
                data.item.lastChapterNumber?.let { chapterNumber ->
                    Text(
                        text = "Ch. ${formatChapterNumber(chapterNumber)}",
                        style = TextStyle(color = secondaryColor, fontSize = 11.sp),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

class CoverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CoverWidget()
}
