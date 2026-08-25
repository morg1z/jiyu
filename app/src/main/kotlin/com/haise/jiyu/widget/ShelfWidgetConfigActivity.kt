package com.haise.jiyu.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import com.haise.jiyu.R
import com.haise.jiyu.data.db.CategoryDao
import com.haise.jiyu.data.db.entity.CategoryEntity
import com.haise.jiyu.ui.theme.JiyuTheme
import compose.icons.TablerIcons
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Book
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Konfigurace přidání [ShelfWidget] na plochu - výběr kategorie (nebo "Celá knihovna"), stejný
 * vzor jako [CoverWidgetConfigActivity] pro výběr titulu.
 */
@AndroidEntryPoint
class ShelfWidgetConfigActivity : ComponentActivity() {

    @Inject lateinit var categoryDao: CategoryDao

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setResult(Activity.RESULT_CANCELED)
        appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            JiyuTheme {
                ShelfConfigScreen(
                    categoryDao = categoryDao,
                    onBack = { finish() },
                    onPick = { categoryId -> finishWithSelection(categoryId) },
                )
            }
        }
    }

    /** `categoryId == null` znamená "Celá knihovna". */
    private fun finishWithSelection(categoryId: String?) {
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(applicationContext).getGlanceIdBy(appWidgetId)
            updateAppWidgetState(applicationContext, PreferencesGlanceStateDefinition, glanceId) { prefs ->
                prefs.toMutablePreferences().apply {
                    this[ShelfWidget.configuredKey] = true
                    if (categoryId != null) {
                        this[ShelfWidget.categoryIdKey] = categoryId
                    } else {
                        remove(ShelfWidget.categoryIdKey)
                    }
                }
            }
            ShelfWidget().update(applicationContext, glanceId)

            val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(Activity.RESULT_OK, resultValue)
            finish()
        }
    }
}

@Composable
private fun ShelfConfigScreen(categoryDao: CategoryDao, onBack: () -> Unit, onPick: (String?) -> Unit) {
    var categories by remember { mutableStateOf<List<CategoryEntity>?>(null) }
    LaunchedEffect(Unit) { categories = categoryDao.getAllOnce() }

    Scaffold(
        containerColor = Color(0xFF070B14),
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back), tint = Color.White)
                }
                Text(
                    text = stringResource(R.string.widget_shelf_config_title),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        },
    ) { innerPadding ->
        val items = categories
        if (items == null) return@Scaffold
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            item(key = "__all_library__") {
                CategoryRow(
                    name = stringResource(R.string.widget_shelf_all_library),
                    color = Color(0xFF8B5CF6),
                    onClick = { onPick(null) },
                )
            }
            items(items, key = { it.id }) { category ->
                CategoryRow(
                    name = category.name,
                    color = runCatching { Color(android.graphics.Color.parseColor(category.colorHex)) }.getOrDefault(Color(0xFF8B5CF6)),
                    onClick = { onPick(category.id) },
                )
            }
        }
    }
}

@Composable
private fun CategoryRow(name: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TablerIcons.Book, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        }
        Text(text = name, color = Color.White, fontSize = 15.sp)
    }
}
