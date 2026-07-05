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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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

@Composable
fun BrowseScreen(
    onMangaAdded: (String) -> Unit,
    viewModel: BrowseViewModel = hiltViewModel(),
) {
    val results by viewModel.results.collectAsState()
    val selectedSource by viewModel.selectedSource.collectAsState()
    val sources = viewModel.sources
    var query by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

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

        // ── Results grid ─────────────────────────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(results, key = { it.sourceId + it.url }) { manga ->
                BrowseMangaCard(manga = manga) {
                    viewModel.addToLibrary(manga, onMangaAdded)
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
