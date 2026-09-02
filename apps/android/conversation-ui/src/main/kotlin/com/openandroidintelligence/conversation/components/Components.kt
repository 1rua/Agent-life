package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.theme.AppColors

@Composable
fun MessageBubble(
    message: MessagePart,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (isUser) AppColors.LightSurfaceHigh else AppColors.LightSurfaceVariant
    val fg = if (isUser) AppColors.LightPrimary else AppColors.LightMuted
    val text = when (message) {
        is MessagePart.Text -> message.value
        is MessagePart.Command -> message.rawText
        is MessagePart.Attachment -> "附件 [" + message.draftId.value + "]"
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = text, color = fg, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun ToolCallCard(
    toolName: String,
    status: String,
    payloadSummary: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = AppColors.LightSurface,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "工具调用: " + toolName, style = MaterialTheme.typography.labelMedium, color = AppColors.LightMuted)
            Text(text = "状态: " + status, style = MaterialTheme.typography.bodySmall, color = AppColors.LightMuted)
            if (payloadSummary.isNotEmpty()) {
                Text(text = payloadSummary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}