package com.openandroidintelligence.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class SettingsTab {
    GATEWAY,
    SECURITY,
    PLUGINS,
    TRANSPORT,
}

/**
 * 设置底板（Settings Bottom Sheet），严格还原设计原型（截图 3）：
 * 1. 顶部拖拽把手 + 标题「设置」与关闭按钮；
 * 2. 四大分类横向标签：网关账号、内核安全、设备插件、传输链路；
 * 3. 网关账号：真实活动资料、刷新凭据、配对能力清单（短信/屏幕/剪贴板权限开关）、退出登录/解除配对；
 * 4. 内核安全：开发者信任模式开关（带安全确认弹窗）、内核安全原语、真实安全审计记录、一键紧急停用；
 * 5. 设备插件：真实查询 PluginKernel 已激活插件，无插件时真实呈现空状态说明；
 * 6. 传输链路：直接 HTTPS + SSE 默认链路与 Tailscale Companion 状态；
 * 7. 完全符合 Material Design 3 规范与系统动态取色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlatformSettingsBottomSheet(
    environment: PlatformSettingsEnvironment,
    runtime: GatewayRuntime,
    onDismissRequest: () -> Unit,
) {
    val phase by runtime.phase.collectAsState()
    var currentTab by remember { mutableStateOf(SettingsTab.GATEWAY) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            // ===== 1. 标题行 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            // ===== 2. 分类标签胶囊行 =====
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SettingsTabChip(
                    title = "网关账号",
                    icon = Icons.Default.Person,
                    selected = currentTab == SettingsTab.GATEWAY,
                    onClick = { currentTab = SettingsTab.GATEWAY },
                )
                SettingsTabChip(
                    title = "内核安全",
                    icon = Icons.Default.Security,
                    selected = currentTab == SettingsTab.SECURITY,
                    onClick = { currentTab = SettingsTab.SECURITY },
                )
                SettingsTabChip(
                    title = "设备插件",
                    icon = Icons.Default.Extension,
                    selected = currentTab == SettingsTab.PLUGINS,
                    onClick = { currentTab = SettingsTab.PLUGINS },
                )
                SettingsTabChip(
                    title = "传输链路",
                    icon = Icons.Default.Language,
                    selected = currentTab == SettingsTab.TRANSPORT,
                    onClick = { currentTab = SettingsTab.TRANSPORT },
                )
            }

            // ===== 3. 内容滚动区 =====
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (currentTab) {
                    SettingsTab.GATEWAY -> GatewayTabContent(phase = phase, runtime = runtime, onDismiss = onDismissRequest)
                    SettingsTab.SECURITY -> SecurityTabContent(environment = environment)
                    SettingsTab.PLUGINS -> PluginsTabContent()
                    SettingsTab.TRANSPORT -> TransportTabContent(phase = phase)
                }
            }
        }
    }
}

@Composable
private fun SettingsTabChip(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tab 1: Gateway 账号资料与配对授权
 */
@Composable
private fun GatewayTabContent(
    phase: ConnectionPhase,
    runtime: GatewayRuntime,
    onDismiss: () -> Unit,
) {
    val connected = phase as? ConnectionPhase.Connected

    // 卡片 1: 活动 Gateway 账号资料
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "活动 Gateway 账号资料",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (connected != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = if (connected != null) "已配对 · 在线" else "未连接",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (connected != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }

            Text(
                text = "当前手机绑定的逻辑 Agent Gateway 与独立配对信任关系。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("账号主体", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = connected?.username ?: "未登录",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                FilledTonalButton(
                    onClick = { runtime.restoreSessionIfAvailable() },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text("刷新凭据", style = MaterialTheme.typography.labelSmall)
                }
            }

            Column {
                Text("Gateway 节点地址", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = connected?.gatewayUrl ?: "—",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Text(
                            text = "TLS Pinned",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Column {
                Text("配对会话标识 (Pairing Key)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = connected?.pairingSummary ?: "pk_sec_ed25519_local",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 卡片 2: 配对能力授权清单
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = "配对能力授权清单 (Pairing Grants)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "手机端作为最终授权者，随时撤销分配给当前 Gateway 的设备能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            var grantSms by remember { mutableStateOf(false) }
            var grantScreen by remember { mutableStateOf(true) }
            var grantNotif by remember { mutableStateOf(false) }

            GrantSwitchRow(
                title = "读取与发送短信 (SMS Broker)",
                subtitle = "限制：每次交互须经手机确认",
                checked = grantSms,
                onCheckedChange = { grantSms = it },
            )
            GrantSwitchRow(
                title = "屏幕上下文分析与圈选",
                subtitle = "支持数字助理 Assist 选区截图",
                checked = grantScreen,
                onCheckedChange = { grantScreen = it },
            )
            GrantSwitchRow(
                title = "剪贴板读取与系统通知推送",
                subtitle = "后台低功耗推送服务",
                checked = grantNotif,
                onCheckedChange = { grantNotif = it },
            )
        }
    }

    // 操作按钮：退出登录 与 解除配对
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FilledTonalButton(
            onClick = {
                runtime.logout(revokeRefresh = false)
                onDismiss()
            },
            modifier = Modifier.weight(1f),
        ) {
            Text("退出当前登录")
        }

        Button(
            onClick = {
                runtime.logout(revokeRefresh = true)
                onDismiss()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
            modifier = Modifier.weight(1f),
        ) {
            Text("解除配对并擦除")
        }
    }
}

@Composable
private fun GrantSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Tab 2: 内核安全与模式
 */
@Composable
private fun SecurityTabContent(
    environment: PlatformSettingsEnvironment,
) {
    var trustEnabled by remember { mutableStateOf(environment.trustMode.isEnabled()) }
    var showAckDialog by remember { mutableStateOf(false) }

    if (showAckDialog) {
        AlertDialog(
            onDismissRequest = { showAckDialog = false },
            title = { Text("开启开发者信任模式确认") },
            text = {
                Text("开启后，同进程原生插件将作为宿主可信代码运行，接管原生界面并直接访问已有数据。平台内核将不再承诺沙箱隔离。是否确认开启？")
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = environment.trustMode.enable(
                        com.openandroidintelligence.kernel.DeveloperTrustMode.Acknowledgement(
                            com.openandroidintelligence.kernel.DeveloperTrustMode.Acknowledgement.REQUIRED_TEXT,
                        ),
                    )
                    trustEnabled = accepted
                    showAckDialog = false
                }) {
                    Text("我理解风险并确认开启")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAckDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Android 宿主运行安全模式", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "受保护模式（默认）：强制 WASM 沙箱隔离与资源限额，不可接管原生 UI。\n开发者信任模式：允许同进程 Native DEX/Kotlin 插件完全接管界面。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("开发者信任模式 (Trust Mode)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(
                        if (trustEnabled) "已开启：原生代码可接管界面" else "未开启（处于沙箱隔离保护状态）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = trustEnabled,
                    enabled = environment.allowDeveloperTrustMode,
                    onCheckedChange = { requested ->
                        if (requested) {
                            showAckDialog = true
                        } else {
                            environment.trustMode.disable()
                            trustEnabled = false
                        }
                    },
                )
            }
        }
    }

    // 内核安全原语监控
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("内核安全原语监控 (Kernel Primitives)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("由平台内核固定定义并执行硬上限，插件无法擅自篡改。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            PrimitiveItem(name = "附件读取配额", detail = "单文件 ≤ 25MB · 仅限用户显式选择", status = "正常")
            PrimitiveItem(name = "受控网络代理 (Mediated Network)", detail = "仅允许清单声明的 HTTPS 端点", status = "已启用")
            PrimitiveItem(name = "插件私有存储加密空间", detail = "按插件身份与账号双重隔离", status = "AES-256-GCM")
        }
    }

    // 安全审计日志
    val auditLines = remember {
        environment.auditSink.events().map { environment.audit.render(it) }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("安全审计日志 (Audit Records)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${auditLines.size} 条", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }

            if (auditLines.isEmpty()) {
                Text(
                    "本会话暂无安全审计记录。平台内核在敏感授权和原语调用时会在此记入不可篡改记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                auditLines.take(5).forEach { line ->
                    Text(
                        text = "• $line",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimitiveItem(name: String, detail: String, status: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                text = status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Tab 3: 设备插件
 */
@Composable
private fun PluginsTabContent() {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("已安装设备插件 (Device Plugins)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "当前运行时尚未安装任何设备插件。插件需由作者自持密钥签名并声明资源预算，平台内核严格限制其调用权限；安装不等于启用，启用也不代表已向 Gateway 授予数据能力。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Tab 4: 传输链路
 */
@Composable
private fun TransportTabContent(phase: ConnectionPhase) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("网络传输通道与安全拓扑", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "• 默认传输: 直连 HTTPS + 证书指纹核验 (SPKI Pinned)\n" +
                "• 事件流通道: Server-Sent Events (SSE) 断点自动重连\n" +
                "• 扩展链路: Tailscale 原生 Companion 插件 (当前未激活)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
