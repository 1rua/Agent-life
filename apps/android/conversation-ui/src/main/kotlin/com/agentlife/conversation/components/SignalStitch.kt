package com.agentlife.conversation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.agentlife.conversation.theme.AppColors

@Composable
fun SignalStitch(
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    val line = if (failed) AppColors.LightError else AppColors.LightPrimary
    val dot = AppColors.LightAccent
    Box(modifier = modifier.width(10.dp).fillMaxHeight()) {
        Canvas(modifier = Modifier.size(10.dp).fillMaxHeight()) {
            val strokeWidth = 2.5.dp.toPx()
            drawLine(
                color = line,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height - strokeWidth * 2),
                strokeWidth = strokeWidth,
            )
            drawCircle(
                color = dot,
                radius = strokeWidth * 0.9f,
                center = Offset(size.width / 2f, size.height - strokeWidth),
            )
        }
    }
}