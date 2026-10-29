package com.dividetask.audiobookreader.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Triangle + bar, like a "skip to next/previous track" icon. */
@Composable
fun ChapterSkipIcon(forward: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier = modifier) {
        scale(scaleX = if (forward) 1f else -1f, scaleY = 1f) {
            val top = size.height * 0.25f
            val bottom = size.height * 0.75f
            val left = size.width * 0.25f
            val tip = size.width * 0.65f
            val barWidth = size.width * 0.1f
            val triangle = Path().apply {
                moveTo(left, top)
                lineTo(tip, size.height / 2)
                lineTo(left, bottom)
                close()
            }
            drawPath(triangle, color)
            drawRect(color, topLeft = Offset(tip, top), size = Size(barWidth, bottom - top))
        }
    }
}

/** Circular arrow with the skip interval in the middle. */
@Composable
fun SkipSecondsIcon(seconds: Int, forward: Boolean, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            scale(scaleX = if (forward) 1f else -1f, scaleY = 1f) {
                val stroke = size.minDimension * 0.08f
                val inset = stroke + size.minDimension * 0.08f
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                // Circle open between 12 and 1 o'clock, with the arrowhead at 12 pointing clockwise
                drawArc(
                    color = color,
                    startAngle = -60f,
                    sweepAngle = 330f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val cx = size.width / 2
                val head = size.minDimension * 0.16f
                val arrow = Path().apply {
                    moveTo(cx + head * 0.8f, inset)
                    lineTo(cx - head * 0.2f, inset - head * 0.6f)
                    lineTo(cx - head * 0.2f, inset + head * 0.6f)
                    close()
                }
                drawPath(arrow, color)
            }
        }
        Text(
            text = seconds.toString(),
            color = color,
            fontSize = if (seconds >= 100) 10.sp else 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
