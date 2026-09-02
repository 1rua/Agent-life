package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.state.Loadable

/**
 * Renders one remote-backed region.
 *
 * [emptyHint] and [onRetry] are required so no call site can render a blank
 * screen for a failed or empty read; a region without guidance is a bug, not a
 * design choice.
 */
@Composable
fun <T> LoadableRegion(
    state: Loadable<T>,
    emptyHint: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    ready: @Composable (T) -> Unit,
) {
    when (state) {
        Loadable.Idle -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "尚未请求数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Loadable.Loading -> Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp))
        }

        Loadable.Empty -> StatusColumn(
            modifier = modifier,
            title = "暂无数据",
            body = emptyHint,
            action = "重新加载" to onRetry,
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(56.dp),
                )
            },
        )

        is Loadable.Failed -> StatusColumn(
            modifier = modifier,
            title = "加载失败",
            body = state.code,
            action = if (state.retryable) "重试" to onRetry else null,
        )

        is Loadable.Ready -> ready(state.value)
    }
}

@Composable
private fun StatusColumn(
    modifier: Modifier,
    title: String,
    body: String,
    action: Pair<String, () -> Unit>?,
    icon: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            icon()
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.let { (label, onClick) ->
                Button(onClick = onClick) { Text(label) }
            }
        }
    }
}
