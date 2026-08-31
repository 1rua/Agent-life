package com.agentlife.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentlife.conversation.model.*
import com.agentlife.conversation.theme.MossFlintColors

@Composable
fun MessageBubble(
    message: MessagePart,
    isUser: Boolean,
    modifier: Modifier = Modifier,
) {
    val bg = if (isUser) MossFlintColors.LightMossContainer else MossFlintColors.LightSurfaceVariant
    val fg = if (isUser) MossFlintColors.LightMoss else MossFlintColors.LightFlint
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
        color = MossFlintColors.LightFlintContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = "工具调用: " + toolName, style = MaterialTheme.typography.labelMedium, color = MossFlintColors.LightFlint)
            Text(text = "状态: " + status, style = MaterialTheme.typography.bodySmall, color = MossFlintColors.LightFlint)
            if (payloadSummary.isNotEmpty()) {
                Text(text = payloadSummary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
