package com.haise.jiyu.ui.library

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.haise.jiyu.data.db.entity.MangaEntity
import com.haise.jiyu.ui.theme.CyanLight
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.VioletLight
import com.haise.jiyu.ui.theme.glassBorder
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import com.haise.jiyu.ui.theme.violetGlow

@Composable
fun LibraryScreen(
    onOpenManga: (String) -> Unit,
    onOpenBrowse: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val library by viewModel.library.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
    ) {
        // ── Vlastní header ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(NightBlue, DeepSpace.copy(alpha = 0f)),
                    )
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column {
                Text(
                    text = "JIYU",
                    style = TextStyle(
                        brush = titleGradient,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 6.sp,
                    ),
                )
                Text(
                    text = "Knihovna · ${library.size} manga",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary,
                )
            }
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Nastavení",
                    tint = TextSecondary,
                )
            }
        }

        if (library.isEmpty()) {
            // Empty state
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "( ˘•ω•˘ )",
                        fontSize = 36.sp,
                        color = GlowViolet.copy(alpha = 0.5f),
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                    Text(
                        text = "Knihovna je prázdná",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextSecondary,
                    )
                    Text(
                        text = "Tap + pro přidání mangy",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(library, key = { it.id }) { manga ->
                    AnimeMangaCard(manga = manga, onClick = { onOpenManga(manga.id) })
                }
            }
        }
    }

    // FAB-style browse button (fixed bottom-right)
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .violetGlow()
                .background(
                    Brush.linearGradient(listOf(GlowViolet, com.haise.jiyu.ui.theme.GlowCyan.copy(alpha = 0.8f))),
                    RoundedCornerShape(16.dp),
                )
                .clip(RoundedCornerShape(16.dp))
                .pointerInput(Unit) { detectTapGestures(onTap = { onOpenBrowse() }) }
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Text(
                text = "+ Přidat",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun AnimeMangaCard(manga: MangaEntity, onClick: () -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "card_scale",
    )

    Box(
        modifier = Modifier
            .aspectRatio(0.68f)
            .scale(scale)
            .violetGlow(radius = 16f, alpha = 0.15f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, GlowViolet.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
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

        // Gradient overlay (transparent → dark bottom)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xE5070B14)),
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
