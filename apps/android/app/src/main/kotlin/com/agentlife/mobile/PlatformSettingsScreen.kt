package com.agentlife.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.agentlife.kernel.AndroidAuditStore
import com.agentlife.kernel.DeveloperTrustMode
import com.agentlife.kernel.InMemoryAuditSink

/** Distribution policy from the build flavor; the Play build cannot unlock trust mode. */
data class DistributionPolicy(
    val allowRuntimePlugins: Boolean,
    val allowDeveloperTrustMode: Boolean,
)

/** Everything the settings screen reads, wired from the composition root. */
data class PlatformSettingsEnvironment(
    val trustMode: DeveloperTrustMode,
    val audit: AndroidAuditStore,
    val auditSink: InMemoryAuditSink,
    val allowDeveloperTrustMode: Boolean,
)

/**
 * The platform management surface.
 *
 * Every value here is a real runtime fact: the trust mode switch drives the
 * kernel's [DeveloperTrustMode] (including its required acknowledgement), the
 * audit list renders actual recorded events, and the plugin card states what
 * this build has actually registered — an honest empty state, never a fixture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsScreen(
    environment: PlatformSettingsEnvironment,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var trustEnabled by remember { mutableStateOf(environment.trustMode.isEnabled()) }
    var showAcknowledgement by remember { mutableStateOf(false) }
    val auditLines = remember {
        environment.auditSink.events().map { environment.audit.render(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置与平台管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard(title = "运行安全模式") {
                SettingsRow(
                    title = "开发者信任模式",
                    subtitle = if (trustEnabled) {
                        "已开启：原生插件可接管界面（宿主不再承诺隔离）"
                    } else {
                        "未开启：处于 WASM 沙箱隔离保护状态"
                    },
                    enabled = environment.allowDeveloperTrustMode,
                    checked = trustEnabled,
                    onCheckedChange = { requested ->
                        if (requested) {
                            showAcknowledgement = true
                        } else {
                            environment.trustMode.disable()
                            trustEnabled = false
                        }
                    },
                )
                if (!environment.allowDeveloperTrustMode) {
                    Text(
                        text = "当前分发渠道不允许开启开发者信任模式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SettingsCard(title = "设备插件") {
                Text(
                    text = "当前运行时尚未安装任何设备插件",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "安装插件后，平台内核将在此列出其身份、能力请求与资源预算；安装不等于启用，启用也不等于已向 Gateway 授权。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsCard(
                title = "安全审计记录",
                trailing = { Text("${auditLines.size} 条", style = MaterialTheme.typography.labelSmall) },
            ) {
                if (auditLines.isEmpty()) {
                    Text(
                        text = "本会话尚无审计记录。记录由平台内核在插件执行、权限裁决与用户确认时写入，仅包含主体、时间、动作与结果。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    auditLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (line.contains("outcome=DENIED") || line.contains("outcome=FAILED")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }

            SettingsCard(title = "协议与身份") {
                Text(
                    text = "网络路径：直连 HTTPS + SSE（默认）",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "请求认证：设备密钥签名 + 短期访问令牌",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "事件恢复：SSE 光标断点续传",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (showAcknowledgement) {
        AlertDialog(
            onDismissRequest = { showAcknowledgement = false },
            title = { Text("开启开发者信任模式") },
            text = { Text(DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val accepted = environment.trustMode.enable(
                            DeveloperTrustMode.Acknowledgement(
                                DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
                            ),
                        )
                        trustEnabled = accepted
                        showAcknowledgement = false
                    },
                ) { Text("我已知晓并开启") }
            },
            dismissButton = {
                TextButton(onClick = { showAcknowledgement = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SettingsCard(
    title: String,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                trailing?.invoke()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}
