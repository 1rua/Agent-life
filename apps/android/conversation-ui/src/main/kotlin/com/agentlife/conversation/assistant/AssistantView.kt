package com.agentlife.conversation.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.agentlife.conversation.theme.AppColors

@Composable
fun AssistantDockedBall(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = AppColors.LightPrimary,
        shape = CircleShape,
        shadowElevation = 8.dp,
        modifier = modifier
            .size(56.dp)
            .clickable(onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("AI", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun AssistantFloatingOverlay(
    visible: Boolean,
    onDismiss: () -> Unit,
    onStartSelection: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + slideInVertically(
            spring(stiffness = Spring.StiffnessMediumLow),
            initialOffsetY = { it / 2 },
        ),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMediumLow)) + slideOutVertically(
            spring(stiffness = Spring.StiffnessMediumLow),
            targetOffsetY = { it / 2 },
        ),
    ) {
        Surface(
            color = AppColors.LightSurface.copy(alpha = 0.95f),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 16.dp,
            modifier = modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("智能助理", style = MaterialTheme.typography.titleMedium, color = AppColors.LightPrimary)
                    Row {
                        TextButton(onClick = onStartSelection) {
                            Text("屏幕选区")
                        }
                        TextButton(onClick = onDismiss) {
                            Text("收起")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                content()
            }
        }
    }
}