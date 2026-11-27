package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun PingSparklineChart(
    samples: List<Int>,
    strokeColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.25f))
    ) {
        val paddingPx = 8.dp.toPx()
        val effectiveWidth = (size.width - 2 * paddingPx).coerceAtLeast(1f)
        val height = size.height
        if (samples.isEmpty()) return@Canvas

        val dataPoints = if (samples.size == 1) listOf(samples[0], samples[0]) else samples
        val maxVal = (dataPoints.maxOrNull() ?: 150).coerceAtLeast(100).toFloat()
        val minVal = (dataPoints.minOrNull() ?: 20).coerceAtMost(20).toFloat()
        val range = (maxVal - minVal).coerceAtLeast(1f)

        val stepX = effectiveWidth / (dataPoints.size - 1)
        val path = Path()
        val fillPath = Path()

        dataPoints.forEachIndexed { index, value ->
            val x = paddingPx + index * stepX
            val normalizedY = (value - minVal) / range
            val y = height - (normalizedY * (height * 0.7f) + height * 0.15f)

            if (index == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, height)
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }

        val lastX = paddingPx + effectiveWidth
        fillPath.lineTo(lastX, height)
        fillPath.close()

        // Draw gradient fill
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(strokeColor.copy(alpha = 0.35f), Color.Transparent),
                startY = 0f,
                endY = height
            )
        )

        // Draw stroke
        drawPath(
            path = path,
            color = strokeColor,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )

        // Highlight latest point safely without edge clipping
        val lastVal = dataPoints.last()
        val lastY = height - (((lastVal - minVal) / range) * (height * 0.7f) + height * 0.15f)
        drawCircle(color = strokeColor, radius = 5.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(color = Color.White, radius = 2.dp.toPx(), center = Offset(lastX, lastY))
    }
}
