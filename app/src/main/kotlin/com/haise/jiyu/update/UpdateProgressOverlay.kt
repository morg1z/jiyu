package com.haise.jiyu.update

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.ui.theme.AccentLight
import com.haise.jiyu.ui.theme.DeepSpace
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import compose.icons.TablerIcons
import compose.icons.tablericons.X
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Globální celoobrazovkový overlay pro stahování aktualizace - viz [ApkUpdateInstaller]
 * pro proč stav žije mimo obrazovku Nastavení. Vložit jednou nekam vysoko ve stromu
 * (MainActivity), stejně jako CloudflareChallengeHost.
 */
@Composable
fun UpdateProgressOverlay(installer: ApkUpdateInstaller) {
    val visible by installer.overlayVisible.collectAsState()
    val state by installer.downloadState.collectAsState()

    // Neresitelne selhani nema smysl drzet v teto obrazovce - schovej overlay a
    // necht uzivatele padnout zpet do Nastaveni, kde uz existuje Retry tlacitko.
    LaunchedEffect(state) {
        if (state is UpdateDownloadState.Failed) installer.dismissOverlay()
    }

    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(DeepSpace.copy(alpha = 0.97f)),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = { installer.dismissOverlay() },
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(TablerIcons.X, contentDescription = null, tint = TextSecondary)
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val current = state
                val fraction = when (current) {
                    is UpdateDownloadState.Downloading -> if (current.progress >= 0) current.progress / 100f else -1f
                    UpdateDownloadState.ReadyToInstall -> 1f
                    else -> -1f
                }
                GlassFlowerProgress(
                    progress = fraction,
                    modifier = Modifier.size(240.dp),
                )
                Spacer(Modifier.height(28.dp))
                val label = when (current) {
                    is UpdateDownloadState.Downloading ->
                        if (current.progress >= 0) "Stahování aktualizace… ${current.progress} %" else "Stahování aktualizace…"
                    UpdateDownloadState.ReadyToInstall -> "Otevírám instalaci…"
                    else -> "Připravuji stahování…"
                }
                Text(label, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

/**
 * Stylizovaný "skleněný květ" - hroty se otevírají a fialová záře uprostřed sílí
 * podle [progress] (0f–1f). Záporná hodnota = neurčitý postup (appka ještě nezná
 * celkovou velikost stahovaného souboru) - v tom případě se otevřenost/záře jemně
 * pulzuje místo sledování konkrétní hodnoty.
 */
@Composable
private fun GlassFlowerProgress(progress: Float, modifier: Modifier = Modifier) {
    val indeterminate = progress < 0f
    val infinite = rememberInfiniteTransition(label = "glassFlower")

    val rotationDeg by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(48_000, easing = LinearEasing)),
        label = "rotation",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val corePulse by infinite.animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "corePulse",
    )

    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(700, easing = FastOutSlowInEasing),
        label = "progress",
    )
    val openness = if (indeterminate) pulse else (0.3f + animatedProgress * 0.7f)
    val glowStrength = (if (indeterminate) pulse else animatedProgress) * corePulse

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = min(size.width, size.height) / 2f

        // Jemna ambientni zare v pozadi celeho tvaru
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GlowViolet.copy(alpha = 0.10f * glowStrength + 0.03f), Color.Transparent),
                center = center,
                radius = maxRadius * 1.15f,
            ),
            radius = maxRadius * 1.15f,
            center = center,
        )

        val shardCount = 14
        for (i in 0 until shardCount) {
            val isLong = i % 2 == 0
            val angleDeg = (360f / shardCount) * i + rotationDeg
            val angleRad = Math.toRadians(angleDeg.toDouble())
            val dir = Offset(cos(angleRad).toFloat(), sin(angleRad).toFloat())
            val perp = Offset(-sin(angleRad).toFloat(), cos(angleRad).toFloat())

            val baseLen = maxRadius * 0.22f
            val fullLen = maxRadius * (if (isLong) 0.98f else 0.60f)
            val len = baseLen + (fullLen - baseLen) * openness

            val innerPoint = center + dir * (maxRadius * 0.06f)
            val shoulderDist = len * 0.32f
            val shoulderWidth = len * (if (isLong) 0.045f else 0.035f)
            val shoulderCenter = center + dir * shoulderDist
            val tip = center + dir * len
            val p1 = shoulderCenter + perp * shoulderWidth
            val p2 = shoulderCenter - perp * shoulderWidth

            val shardPath = Path().apply {
                moveTo(innerPoint.x, innerPoint.y)
                lineTo(p1.x, p1.y)
                lineTo(tip.x, tip.y)
                lineTo(p2.x, p2.y)
                close()
            }

            drawPath(
                path = shardPath,
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.10f),
                        Color.White.copy(alpha = 0.65f + 0.2f * openness),
                    ),
                    start = innerPoint,
                    end = tip,
                ),
            )
            drawPath(
                path = shardPath,
                color = AccentLight.copy(alpha = 0.45f),
                style = Stroke(width = 1.2f),
            )
        }

        // Fialove "srdce" uprostred - roste a jasni s postupem
        val coreRadius = maxRadius * (0.05f + 0.16f * glowStrength)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    GlowViolet.copy(alpha = 0.9f * glowStrength),
                    GlowViolet.copy(alpha = 0f),
                ),
                center = center,
                radius = maxRadius * (0.18f + 0.35f * glowStrength),
            ),
            radius = maxRadius * (0.18f + 0.35f * glowStrength),
            center = center,
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f * glowStrength),
            radius = coreRadius * 0.5f,
            center = center,
        )
    }
}
