package com.haise.jiyu.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.GridCells
import androidx.glance.appwidget.lazy.LazyVerticalGrid
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.haise.jiyu.R
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Kolik titulů se maximálně vejde do widgetu - RemoteViews (na rozdíl od běžné appky) má
 * tvrdý limit na velikost přenášených dat (TransactionTooLargeException), a každá obálka
 * je tu předem dekódovaná bitmapa, ne líně natahovaný obrázek. */
private const val MAX_SHELF_ITEMS = 12

private data class ShelfItem(val id: String, val title: String, val coverBitmap: Bitmap?)

/**
 * Widget s celou knihovnou nebo jednou kategorií jako mřížkou obálek (na rozdíl od
 * [CoverWidget] - jeden titul - a [JiyuWidget] - textový seznam bez obrázků). Kategorie se
 * vybírá při přidání widgetu na plochu - viz [ShelfWidgetConfigActivity]. `null`/prázdný
 * řetězec v [categoryIdKey] znamená "celá knihovna", ne "žádná kategorie nenalezena".
 */
class ShelfWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = getAppWidgetState(context, PreferencesGlanceStateDefinition, id)
        val configured = prefs[configuredKey] ?: false
        val categoryId = prefs[categoryIdKey]

        val items: List<ShelfItem> = if (!configured) emptyList() else withContext(Dispatchers.IO) {
            try {
                val db = EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .appDatabase()
                val mangaList = if (categoryId.isNullOrBlank()) {
                    db.mangaDao().getAllLibrary()
                } else {
                    db.categoryDao().observeMangaInCategory(categoryId).first()
                }
                mangaList.take(MAX_SHELF_ITEMS).map { manga ->
                    ShelfItem(manga.id, manga.title, loadCoverBitmap(context, manga.coverUrl))
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        val configureLabel = context.getString(R.string.widget_shelf_unconfigured)
        val emptyLabel = context.getString(R.string.widget_shelf_config_empty)

        provideContent {
            ShelfWidgetContent(configured = configured, items = items, configureLabel = configureLabel, emptyLabel = emptyLabel)
        }
    }

    companion object {
        val categoryIdKey = stringPreferencesKey("shelf_category_id")
        val configuredKey = booleanPreferencesKey("shelf_configured")
    }
}

@androidx.compose.runtime.Composable
private fun ShelfWidgetContent(
    configured: Boolean,
    items: List<ShelfItem>,
    configureLabel: String,
    emptyLabel: String,
) {
    val bgColor = ColorProvider(Color(0xFF0D0D1A))
    val secondaryColor = ColorProvider(Color(0xFF94A3B8))

    Box(modifier = GlanceModifier.fillMaxSize().background(bgColor).cornerRadius(16.dp)) {
        when {
            !configured -> CenteredMessage(configureLabel, secondaryColor)
            items.isEmpty() -> CenteredMessage(emptyLabel, secondaryColor)
            else -> LazyVerticalGrid(
                gridCells = GridCells.Fixed(3),
                modifier = GlanceModifier.fillMaxSize().padding(8.dp),
            ) {
                items(items, itemId = { it.id.hashCode().toLong() }) { item ->
                    ShelfCoverItem(item)
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun CenteredMessage(text: String, color: ColorProvider) {
    Column(
        modifier = GlanceModifier.fillMaxSize().padding(12.dp)
            .clickable(actionStartActivity<com.haise.jiyu.MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = text, style = TextStyle(color = color, fontSize = 12.sp))
    }
}

@androidx.compose.runtime.Composable
private fun ShelfCoverItem(item: ShelfItem) {
    val openIntent = Intent(Intent.ACTION_VIEW, Uri.parse("jiyu://manga?mangaId=${item.id}"))
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(110.dp)
            .padding(4.dp)
            .cornerRadius(8.dp)
            .background(ColorProvider(Color(0xFF111B35)))
            .clickable(actionStartActivityIntent(openIntent)),
    ) {
        if (item.coverBitmap != null) {
            Image(
                provider = ImageProvider(item.coverBitmap),
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = GlanceModifier.fillMaxWidth().height(110.dp),
            )
        }
    }
}

class ShelfWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ShelfWidget()
}
