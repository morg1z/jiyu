package com.haise.jiyu.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.haise.jiyu.ui.theme.titleGradient

/**
 * Společné logo aplikace pro horní lištu hlavních záložek.
 */
@Composable
fun JiyuWordmark(
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Jiyū",
        style = TextStyle(
            brush = titleGradient,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 4.sp,
        ),
        maxLines = 1,
        modifier = modifier.padding(start = 8.dp),
    )
}
