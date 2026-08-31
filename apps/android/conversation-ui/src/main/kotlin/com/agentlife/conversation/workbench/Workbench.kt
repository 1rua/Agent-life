package com.agentlife.conversation.workbench

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agentlife.conversation.components.MessageBubble
import com.agentlife.conversation.model.*
import com.agentlife.conversation.theme.MossFlintColors

@Composable
fun WorkbenchView(
    state: ConversationSessionState,
    timeline: List<MessagePart>,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var textInput by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            items(timeline) { part ->
                MessageBubble(
                    message = part,
                    isUser = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.generation == GenerationState.RUNNING) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                color = MossFlintColors.LightMoss,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            TextField(
                value = textInput,
                onValueChange = { textInput = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息或 /new ...") },
                enabled = state.generation != GenerationState.RUNNING,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        onSendMessage(textInput)
                        textInput = ""
                    }
                },
                enabled = textInput.isNotBlank() && state.generation != GenerationState.RUNNING,
                colors = ButtonDefaults.buttonColors(containerColor = MossFlintColors.LightMoss),
            ) {
                Text("发送")
            }
        }
    }
}
