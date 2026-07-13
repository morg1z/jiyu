package com.haise.jiyu.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.haise.jiyu.MainActivity
import com.haise.jiyu.R
import com.haise.jiyu.data.db.AppDatabase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun appDatabase(): AppDatabase
}

class JiyuWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val recentTitles = withContext(Dispatchers.IO) {
            try {
                val db = EntryPointAccessors
                    .fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
                    .appDatabase()
                db.mangaDao().getAllLibrary()
                    .sortedByDescending { it.lastUpdated }
                    .take(5)
                    .map { it.title }
            } catch (_: Exception) { emptyList() }
        }

        val libraryLabel = context.getString(R.string.widget_library_label)
        val emptyLabel = context.getString(R.string.widget_empty_library)

        provideContent {
            WidgetContent(titles = recentTitles, libraryLabel = libraryLabel, emptyLabel = emptyLabel)
        }
    }

    @androidx.compose.runtime.Composable
    private fun WidgetContent(titles: List<String>, libraryLabel: String, emptyLabel: String) {
        val bgColor = ColorProvider(Color(0xFF0D0D1A))
        val textColor = ColorProvider(Color(0xFFE2E8F0))
        val accentColor = ColorProvider(Color(0xFF8B5CF6))
        val secondaryColor = ColorProvider(Color(0xFF94A3B8))

        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(bgColor)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = GlanceModifier.fillMaxWidth(),
            ) {
                Text(
                    text = "JIYU",
                    style = TextStyle(color = accentColor, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = libraryLabel,
                    style = TextStyle(color = secondaryColor, fontSize = 11.sp),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            if (titles.isEmpty()) {
                Text(
                    text = emptyLabel,
                    style = TextStyle(color = secondaryColor, fontSize = 12.sp),
                )
            } else {
                titles.take(4).forEach { title ->
                    Row(
                        modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = GlanceModifier.size(4.dp).background(accentColor)) {}
                        Spacer(GlanceModifier.width(8.dp))
                        Text(
                            text = title,
                            style = TextStyle(color = textColor, fontSize = 12.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

class JiyuWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = JiyuWidget()
}
