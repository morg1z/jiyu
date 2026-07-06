package com.haise.jiyu.ui.browse

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.source.SManga
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowCyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.glassGradient
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import com.haise.jiyu.ui.theme.violetGlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onMangaAdded: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val results        by viewModel.results.collectAsState()
    val loading        by viewModel.loading.collectAsState()
    val error          by viewModel.error.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val previewManga   by viewModel.previewManga.collectAsState()
    val hasMore        by viewModel.hasMore.collectAsState()
    val sources = viewModel.sources
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyGridState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisible >= totalItems - 5 && totalItems > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore) viewModel.loadMore()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
    ) {
        // ── Header ──────────────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NightBlue, DeepSpace.copy(alpha = 0f)),
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Text(
                text = "Procházet",
                style = TextStyle(
                    brush = titleGradient,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                ),
            )

            // Glass search field
            TextField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.search(it)
                },
                placeholder = {
                    Text("Hledat mangu…", color = TextSecondary)
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NightBlue,
                    unfocusedContainerColor = NightBlue.copy(alpha = 0.7f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Cyan,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                ),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .glassBorder(14.dp),
            )
        }

        // ── Source tabs ──────────────────────────────────────────────────────
        val selectedIndex = sources.indexOfFirst { it.id == selectedSource.id }.coerceAtLeast(0)
        TabRow(
            selectedTabIndex = selectedIndex,
            containerColor = Color.Transparent,
            contentColor = Violet,
            indicator = { tabPositions ->
                Box(
                    modifier = Modifier
                        .tabIndicatorOffset(tabPositions[selectedIndex])
                        .height(2.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Violet, Cyan)),
                            RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                        )
                )
            },
            divider = {},
        ) {
            sources.forEach { source ->
                val isSelected = source.id == selectedSource.id
                Tab(
                    selected = isSelected,
                    onClick = {
                        query = ""
                        viewModel.selectSource(source)
                    },
                    text = {
                        Text(
                            text = source.name,
                            color = if (isSelected) Violet else TextSecondary,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 13.sp,
                        )
                    },
                )
            }
        }

        // ── Results area ─────────────────────────────────────────────────────
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

        when {
            // Loading — grid je prázdný
            loading && results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Violet)
                }
            }
            // Chyba sítě
            error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp),
                    ) {
                        Text("( ⚠ )", fontSize = 40.sp, color = GlowViolet.copy(alpha = 0.5f))
                        Text(
                            text = "Načtení selhalo",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        Text(
                            text = error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
                        )
                        OutlinedButton(onClick = { viewModel.retry() }) {
                            Text("Zkusit znovu", color = Violet)
                        }
                    }
                }
            }
            // Prázdné výsledky
            results.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("( ˘•ω•˘ )", fontSize = 36.sp, color = GlowViolet.copy(alpha = 0.5f))
                        Text(
                            text = "Žádné výsledky",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            // Grid s výsledky
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 16.dp + navBottom),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                ) {
                    items(results, key = { it.sourceId + it.url }) { manga ->
                        BrowseMangaCard(manga = manga) {
                            viewModel.showPreview(manga)
                        }
                    }
                    if (hasMore || loading) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Violet, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Preview bottom sheet ─────────────────────────────────────────────────
    previewManga?.let { manga ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissPreview() },
            sheetState = sheetState,
            containerColor = Color(0xFF111B35),
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                AsyncImage(
                    model = manga.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                )
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                    Text(
                        text = manga.title,
                        style = androidx.compose.ui.text.TextStyle(
                            brush = titleGradient,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = selectedSource.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (!manga.description.isNullOrBlank()) {
                        Text(
                            text = manga.description!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Brush.linearGradient(listOf(GlowViolet, GlowCyan.copy(alpha = 0.8f))))
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = {
                                    viewModel.addToLibrary(manga, onMangaAdded)
                                    viewModel.dismissPreview()
                                })
                            }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+ Přidat do knihovny", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseMangaCard(manga: SManga, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "browse_card_scale",
    )

    Box(
        modifier = Modifier
            .aspectRatio(0.68f)
            .scale(scale)
            .violetGlow(radius = 14f, alpha = 0.12f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowCyan.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { onClick() },
                )
            },
    ) {
        AsyncImage(
            model = manga.coverUrl,
            contentDescription = manga.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xEA070B14)),
                    )
                )
        )

        Text(
            text = manga.title,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 7.dp, vertical = 6.dp),
        )
    }
}
