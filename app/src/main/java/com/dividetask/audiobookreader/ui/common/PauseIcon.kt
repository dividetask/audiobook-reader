package com.dividetask.audiobookreader.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

@Composable
fun PauseIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier = modifier) {
        val barWidth = size.width * 0.25f
        val barHeight = size.height * 0.6f
        val gap = size.width * 0.15f
        val startX = (size.width - barWidth * 2 - gap) / 2
        val startY = (size.height - barHeight) / 2

        drawRoundRect(
            color = color,
            topLeft = Offset(startX, startY),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth * 0.2f)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(startX + barWidth + gap, startY),
            size = Size(barWidth, barHeight),
            cornerRadius = CornerRadius(barWidth * 0.2f)
        )
    }
}
