package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.components.SignalStitch
import com.openandroidintelligence.conversation.state.TimelineEntry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The conversation timeline.
 *
 * Assistant messages are open text with the signal stitch on the left; user
 * messages are tonal surfaces on the right. A pending send shows its own state
 * line instead of pretending the server already accepted it.
 */
@Composable
fun MessageTimeline(
    entries: List<TimelineEntry>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        entries.forEach { entry -> TimelineRow(entry) }
    }
}

@Composable
private fun TimelineRow(entry: TimelineEntry) {
    if (entry.isUser) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 4.dp),
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(text = entry.text, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = sendStateLabel(entry),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                    )
                }
            }
        }
    } else {
        Row(modifier = Modifier.fillMaxWidth()) {
            SignalStitch(
                failed = false,
                modifier = Modifier.height(44.dp),
            )
            Spacer(modifier = Modifier.padding(2.dp))
            Column(modifier = Modifier.widthIn(max = 320.dp)) {
                Text(
                    text = entry.text.ifBlank { "此内容已删除" },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = formatTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun sendStateLabel(entry: TimelineEntry): String = when {
    entry.pendingAcceptance -> "等待合并"
    else -> formatTime(entry.timestamp)
}

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

private fun formatTime(epochMillis: Long): String =
    if (epochMillis <= 0) "" else TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis))
