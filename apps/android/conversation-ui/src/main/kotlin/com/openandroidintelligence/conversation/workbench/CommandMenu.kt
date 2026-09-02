package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.components.LoadableRegion

/**
 * The command catalog menu.
 *
 * Selecting an entry fills the composer; it never sends. The Agent owns what a
 * command means, so the phone only offers discovery.
 */
@Composable
fun CommandMenu(
    catalogState: Loadable<com.openandroidintelligence.conversation.ports.AgentCommandCatalog>,
    query: String,
    onSelect: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!query.startsWith("/")) return
    val prefix = query.substringBefore(" ").lowercase()
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        LoadableRegion(
            state = catalogState,
            emptyHint = "该 Gateway 未提供命令目录",
            onRetry = onRetry,
            ready = { catalog ->
                val matched = catalog.commands.filter { it.command.lowercase().startsWith(prefix) }
                if (matched.isEmpty()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "没有匹配 “$prefix” 的命令，仍可原样发送",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                        items(matched) { command -> CommandRow(command, onSelect) }
                    }
                }
            },
        )
    }
}

@Composable
private fun CommandRow(command: AgentCommand, onSelect: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(command.command) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = command.command,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = command.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
