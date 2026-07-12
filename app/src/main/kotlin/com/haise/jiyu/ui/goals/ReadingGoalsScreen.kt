package com.haise.jiyu.ui.goals

import compose.icons.TablerIcons
import compose.icons.tablericons.*


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.haise.jiyu.ui.theme.Cyan
import com.haise.jiyu.ui.theme.GlowViolet
import com.haise.jiyu.ui.theme.NightBlue
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient

@Composable
fun ReadingGoalsScreen(
    onBack: () -> Unit,
    viewModel: ReadingGoalsViewModel = hiltViewModel(),
) {
    val streak by viewModel.readingStreak.collectAsState()
    val weeklyGoal by viewModel.weeklyGoal.collectAsState()
    val chaptersThisWeek by viewModel.chaptersThisWeek.collectAsState()

    val progress = if (weeklyGoal > 0) (chaptersThisWeek.toFloat() / weeklyGoal).coerceIn(0f, 1f) else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(TablerIcons.ArrowBack, null, tint = TextPrimary)
                }
                Text(
                    "Cíle čtení",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(24.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(NightBlue.copy(alpha = 0.6f))
                        .padding(32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            TablerIcons.Flame,
                            contentDescription = null,
                            tint = Color(0xFFFF6B35),
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "$streak",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 56.sp,
                        )
                        Text(
                            text = if (streak == 1) "den v řadě" else "dní v řadě",
                            color = TextSecondary,
                            fontSize = 14.sp,
                        )
                    }
                }

                Spacer(Modifier.height(32.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(NightBlue.copy(alpha = 0.6f))
                        .padding(20.dp),
                ) {
                    Column {
                        Text(
                            "Týdenní cíl",
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (weeklyGoal == 0) "Vypnuto" else "$chaptersThisWeek / $weeklyGoal kapitol",
                            color = Cyan,
                            fontSize = 14.sp,
                        )
                        if (weeklyGoal > 0) {
                            Spacer(Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = if (progress >= 1f) Cyan else Violet,
                                trackColor = Violet.copy(alpha = 0.2f),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = "Nastav cíl: ${if (weeklyGoal == 0) "Vypnuto" else "$weeklyGoal kap/týden"}",
                            color = TextSecondary,
                            fontSize = 13.sp,
                        )
                        Slider(
                            value = weeklyGoal.toFloat(),
                            onValueChange = { viewModel.setWeeklyGoal(it.toInt()) },
                            valueRange = 0f..50f,
                            steps = 49,
                            colors = SliderDefaults.colors(
                                thumbColor = Violet,
                                activeTrackColor = Violet,
                                inactiveTrackColor = Violet.copy(alpha = 0.2f),
                            ),
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
