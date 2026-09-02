package com.openandroidintelligence.conversation.selection

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.AppColors

@Composable
fun ScreenSelectionOverlay(
    onCropConfirmed: (left: Float, top: Float, right: Float, bottom: Float) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var currentOffset by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.4f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startOffset = offset
                        currentOffset = offset
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        currentOffset = (currentOffset ?: Offset.Zero) + dragAmount
                    },
                    onDragEnd = {
                        val s = startOffset
                        val c = currentOffset
                        if (s != null && c != null) {
                            val l = minOf(s.x, c.x)
                            val t = minOf(s.y, c.y)
                            val r = maxOf(s.x, c.x)
                            val b = maxOf(s.y, c.y)
                            if (r - l > 10 && b - t > 10) {
                                onCropConfirmed(l, t, r, b)
                            }
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = startOffset
            val c = currentOffset
            if (s != null && c != null) {
                val l = minOf(s.x, c.x)
                val t = minOf(s.y, c.y)
                val w = kotlin.math.abs(c.x - s.x)
                val h = kotlin.math.abs(c.y - s.y)
                drawRect(
                    color = AppColors.LightPrimary,
                    topLeft = Offset(l, t),
                    size = Size(w, h),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
    }
}