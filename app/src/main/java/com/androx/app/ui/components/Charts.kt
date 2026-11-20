package com.androx.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max

/** Seven rounded bars with weekday letters underneath. [values] are oldest → newest; the last bar is "today". */
@Composable
fun ActivityBars(values: List<Int>, labels: List<String>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val peak = max(1, values.maxOrNull() ?: 1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val n = values.size.coerceAtLeast(1)
            val slot = size.width / n
            val barWidth = slot * 0.5f
            values.forEachIndexed { i, v ->
                val h = if (v == 0) 6.dp.toPx() else (size.height * v / peak).coerceAtLeast(10.dp.toPx())
                val x = slot * i + (slot - barWidth) / 2
                val color = if (i == values.lastIndex) scheme.primary else scheme.primary.copy(alpha = 0.35f)
                drawRoundRect(
                    color = if (v == 0) scheme.outlineVariant else color,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/** A circular gauge: [fraction] of the ring is filled. */
@Composable
fun ProgressRing(fraction: Float, modifier: Modifier = Modifier, size: Dp = 72.dp, stroke: Dp = 8.dp, color: Color = MaterialTheme.colorScheme.primary, track: Color = MaterialTheme.colorScheme.surfaceContainerHighest) {
    Canvas(modifier.size(size)) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(this.size.width - s, this.size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
    }
}

@Composable
fun RingWithLabel(fraction: Float, label: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(modifier, contentAlignment = Alignment.Center) {
        ProgressRing(fraction)
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
