package com.haise.jiyu.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.settings.ThemeOption
import com.haise.jiyu.ui.theme.ACCENT_HUE_PREFIX
import com.haise.jiyu.ui.theme.ACCENT_OPTIONS
import com.haise.jiyu.ui.theme.CardBorder
import com.haise.jiyu.ui.theme.TextMuted
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.accentSpecFor
import com.haise.jiyu.ui.theme.screenGradient
import kotlin.math.roundToInt

@Composable
fun AppearanceSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val theme             by viewModel.theme.collectAsStateWithLifecycle()
    val themeAccent       by viewModel.themeAccent.collectAsStateWithLifecycle()
    val libraryGridColumns by viewModel.libraryGridColumns.collectAsStateWithLifecycle()
    val defaultCategoryId  by viewModel.defaultCategoryId.collectAsStateWithLifecycle()
    val allCategories      by viewModel.categories.collectAsStateWithLifecycle()

    Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0)) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(screenGradient)
                .padding(innerPadding),
        ) {
            SettingsSubScreenHeader(title = stringResource(com.haise.jiyu.R.string.settings_main_appearance_title), onBack = onBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_language)) {
                    val appLanguages = listOf(
                        "cs" to "🇨🇿  Čeština",
                        "en" to "🇬🇧  English",
                        "fr" to "🇫🇷  Français",
                        "es" to "🇪🇸  Español",
                    )
                    appLanguages.forEach { (tag, label) ->
                        val currentTag = androidx.appcompat.app.AppCompatDelegate
                            .getApplicationLocales().toLanguageTags()
                            .split(",").firstOrNull()?.take(2) ?: "cs"
                        GlassRadioRow(
                            label = label,
                            selected = currentTag == tag,
                            onClick = { viewModel.setLanguage(tag) },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_appearance_theme_title)) {
                    listOf(
                        ThemeOption.SYSTEM to stringResource(com.haise.jiyu.R.string.settings_appearance_theme_system),
                        ThemeOption.LIGHT to stringResource(com.haise.jiyu.R.string.settings_appearance_theme_light),
                        ThemeOption.DARK to stringResource(com.haise.jiyu.R.string.settings_appearance_theme_dark),
                        ThemeOption.TRUE_BLACK to stringResource(com.haise.jiyu.R.string.settings_appearance_theme_true_black),
                    ).forEach { (value, label) ->
                        GlassRadioRow(label = label, selected = theme == value, onClick = { viewModel.setTheme(value) })
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_appearance_accent_title)) {
                    // SettingsSection nemá vnitřní padding - obsah by přesahoval na hranu karty.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        AccentHuePicker(
                            themeAccent = themeAccent,
                            onSelect = { viewModel.setThemeAccent(it) },
                            onPreview = { viewModel.previewThemeAccent(it) },
                        )
                        Spacer(Modifier.height(12.dp))
                        // Rychlé presety - tečka se zvýrazní jen pro pojmenovaný klíč
                        // (u vlastního odstínu ze slideru žádná nesvítí, slider drží polohu sám).
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ACCENT_OPTIONS.forEach { spec ->
                                val selected = themeAccent == spec.key
                                val label = stringResource(spec.labelRes)
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .semantics { contentDescription = label }
                                        .selectable(
                                            selected = selected,
                                            onClick = { viewModel.setThemeAccent(spec.key) },
                                            role = Role.RadioButton,
                                        )
                                        .clip(CircleShape)
                                        .background(spec.dark.accent)
                                        .border(
                                            width = if (selected) 2.dp else 1.dp,
                                            color = if (selected) TextPrimary else CardBorder,
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                SettingsSection(title = stringResource(com.haise.jiyu.R.string.settings_appearance_library_title)) {
                    androidx.compose.material3.Text(
                        text = stringResource(com.haise.jiyu.R.string.settings_appearance_grid_columns_title),
                        color = com.haise.jiyu.ui.theme.TextSecondary,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                    )
                    listOf(
                        2 to stringResource(com.haise.jiyu.R.string.settings_appearance_columns_2),
                        3 to stringResource(com.haise.jiyu.R.string.settings_appearance_columns_3_default),
                        4 to stringResource(com.haise.jiyu.R.string.settings_appearance_columns_4),
                    ).forEach { (n, label) ->
                        GlassRadioRow(
                            label = label,
                            selected = libraryGridColumns == n,
                            onClick = { viewModel.setLibraryGridColumns(n) },
                        )
                    }

                    if (allCategories.isNotEmpty()) {
                        androidx.compose.material3.Text(
                            text = stringResource(com.haise.jiyu.R.string.settings_appearance_default_category_title),
                            color = com.haise.jiyu.ui.theme.TextSecondary,
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp),
                        )
                        GlassRadioRow(
                            label = stringResource(com.haise.jiyu.R.string.settings_appearance_default_category_none),
                            selected = defaultCategoryId == null,
                            onClick = { viewModel.setDefaultCategoryId(null) },
                        )
                        allCategories.forEach { cat ->
                            GlassRadioRow(
                                label = cat.name,
                                selected = defaultCategoryId == cat.id,
                                onClick = { viewModel.setDefaultCategoryId(cat.id) },
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }

                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Spacer(Modifier.height(40.dp + navBottom))
            }
        }
    }
}

/** Gradient spektra pro [AccentHuePicker] - 13 zastávek po 30° kryje celý hue okruh. */
private val HUE_STOPS = List(13) { i -> Color.hsl(i * 30f, 0.82f, 0.60f) }

/**
 * Spektrální výběr akcentové barvy: duhová lišta přes celý hue okruh (0-359°) s jezdeckem
 * vybarveným aktuálním odstínem. Během tahu se [onPreview] živě přebarvuje celá appka
 * (in-memory preview kanál, viz SettingsRepository.effectiveThemeAccent), do nastavení se
 * hodnota "h:<hue>" zapíše až při puštění prstu (a na tap) - DataStore tak nedostává
 * zápis za každý pixel pohybu.
 */
@Composable
private fun AccentHuePicker(
    themeAccent: String,
    onSelect: (String) -> Unit,
    onPreview: (String?) -> Unit,
) {
    var dragHue by remember { mutableStateOf<Float?>(null) }
    // Puštění tahu čeká, až DataStore promítne novou hodnotu - jezdec i náhled mezitím drží
    // pozici z prstu, jinak by na jeden frame skočily na starou barvu.
    LaunchedEffect(themeAccent) {
        dragHue = null
        onPreview(null)
    }
    // Pojistka: odchod z obrazovky uprostřed tahu nesmí nechat náhled viset.
    DisposableEffect(Unit) { onDispose { onPreview(null) } }
    val hue = dragHue ?: accentSpecFor(themeAccent).hue

    val trackShape = RoundedCornerShape(12.dp)
    val thumbSize = 22.dp
    val thumbSizePx = with(LocalDensity.current) { thumbSize.toPx() }
    val thumbColor = Color.hsl(hue, 0.82f, 0.62f)
    fun accentKey(h: Float) = "$ACCENT_HUE_PREFIX${h.roundToInt()}"
    fun commit(h: Float) = onSelect(accentKey(h))

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val h = (offset.x / size.width * 360f).coerceIn(0f, 359.9f)
                    dragHue = h
                    commit(h)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = { dragHue?.let(::commit) },
                    onHorizontalDrag = { change, _ ->
                        val h = (change.position.x / size.width * 360f).coerceIn(0f, 359.9f)
                        dragHue = h
                        onPreview(accentKey(h))
                    },
                )
            },
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        Box(
            Modifier
                .fillMaxSize()
                .clip(trackShape)
                .background(Brush.horizontalGradient(HUE_STOPS))
                .border(1.dp, CardBorder, trackShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset {
                    val x = (hue / 360f * widthPx - thumbSizePx / 2)
                        .coerceIn(0f, widthPx - thumbSizePx)
                    IntOffset(x.roundToInt(), 0)
                }
                .size(thumbSize)
                .shadow(4.dp, CircleShape)
                .clip(CircleShape)
                .background(thumbColor)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}
