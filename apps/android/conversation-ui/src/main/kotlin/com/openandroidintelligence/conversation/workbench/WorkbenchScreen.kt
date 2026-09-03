package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.components.LoadableRegion
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.launch

enum class WorkbenchTab {
    CHAT,
    WORKFLOW
}

/**
 * 主对话与工作台界面，严格还原 HTML 动效预览（截图 1）：
 * 1. 顶部圆角顶栏：左侧胶囊汉堡菜单 ☰、中间 [对话 | 工作流] 分段指示器、右侧圆角新增按钮 +；
 * 2. 对话空状态：高保真相机拍摄入口卡片、真实命令目录快捷卡片、网关实时在线状态卡片；
 * 3. 对话进行态：MessageTimeline 呈现真实历史与流式回复，左侧信号缝线指示；
 * 4. 工作流模式：展示真实后端 Gateway 导出的 Agent 命令与工作流列表；
 * 5. 完全遵守 Material Design 3 动态取色规范，无固定死颜色，适配平板居中。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkbenchScreen(
    controller: WorkbenchController,
    gatewayLabel: String,
    onOpenSettings: () -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val listState = rememberLazyListState()

    var activeTab by remember { mutableStateOf(WorkbenchTab.CHAT) }

    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it) }
    }

    // 自动跟随流式回复尾部
    LaunchedEffect((state.timeline as? Loadable.Ready)?.value?.size) {
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isNotEmpty() && visible.last().index >= listState.layoutInfo.totalItemsCount - 2) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.fillMaxWidth(0.82f),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                ThreadDrawer(
                    gatewayLabel = gatewayLabel,
                    threads = state.threads,
                    activeThreadId = state.activeThreadId,
                    onOpenThread = { threadId ->
                        controller.openThread(threadId)
                        scope.launch { drawer.close() }
                    },
                    onCreateThread = {
                        controller.createThread()
                        scope.launch { drawer.close() }
                    },
                    onRefresh = { controller.refreshThreads() },
                    onOpenSettings = {
                        onOpenSettings()
                        scope.launch { drawer.close() }
                    },
                    onCloseDrawer = {
                        scope.launch { drawer.close() }
                    },
                )
            }
        },
        modifier = modifier.fillMaxSize(),
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) { data -> Snackbar(data) } },
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 760.dp), // 大屏与平板宽度限制，保持舒适阅读行长
                ) {
                    // ===== 1. 顶部 Header 栏（截图 1） =====
                    WorkbenchTopHeader(
                        activeTab = activeTab,
                        onTabChange = { activeTab = it },
                        onOpenDrawer = { scope.launch { drawer.open() } },
                        onCreateThread = { controller.createThread() },
                    )

                    // ===== 2. 主内容区（对话 或 工作流） =====
                    Box(modifier = Modifier.weight(1f)) {
                        when (activeTab) {
                            WorkbenchTab.CHAT -> {
                                ChatTabContent(
                                    controller = controller,
                                    timelineState = state.timeline,
                                    catalogState = state.catalog,
                                    gatewayLabel = gatewayLabel,
                                    listState = listState,
                                    onPickCamera = onPickCamera,
                                )
                            }
                            WorkbenchTab.WORKFLOW -> {
                                WorkflowTabContent(
                                    catalogState = state.catalog,
                                    onRefresh = { controller.loadCatalog() },
                                    onSelectCommand = { cmd ->
                                        controller.selectCommand(cmd)
                                        activeTab = WorkbenchTab.CHAT
                                    },
                                )
                            }
                        }
                    }

                    // ===== 3. 斜杠命令浮层（当输入以 / 开头时呈现） =====
                    CommandMenu(
                        catalogState = state.catalog,
                        query = state.draft,
                        onSelect = { controller.selectCommand(it) },
                        onRetry = { controller.loadCatalog() },
                    )

                    // ===== 4. 防抖合并批次状态条 =====
                    PendingBatchStrip(members = state.pendingBatch)

                    // ===== 5. 底部悬浮输入卡片 =====
                    ComposerBar(
                        draft = state.draft,
                        onDraftChange = { controller.editDraft(it) },
                        generation = state.generation,
                        canSend = state.draft.isNotBlank() || (state.attachments.isNotEmpty() && state.attachments.all { it.state == com.openandroidintelligence.conversation.model.AttachmentState.VERIFIED }),
                        onSend = { controller.sendDraft() },
                        onStop = { controller.stopGeneration() },
                        onPickCamera = onPickCamera,
                        onPickGallery = onPickGallery,
                        onPickDocument = onPickDocument,
                        onVoiceInput = onVoiceInput,
                        attachments = state.attachments,
                        onRemoveAttachment = { controller.removeAttachment(it) },
                        onRetryAttachment = { controller.retryAttachment(it) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 顶部导航条：汉堡菜单 + [对话 | 工作流] 胶囊切换器 + 新建对话
 */
@Composable
private fun WorkbenchTopHeader(
    activeTab: WorkbenchTab,
    onTabChange: (WorkbenchTab) -> Unit,
    onOpenDrawer: () -> Unit,
    onCreateThread: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧汉堡菜单按钮（圆角胶囊）
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(42.dp),
        ) {
            IconButton(onClick = onOpenDrawer) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = "打开会话抽屉",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        // 中间分段选择器（Segmented Switch）
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.height(42.dp),
        ) {
            Row(
                modifier = Modifier.padding(3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SegmentedItem(
                    title = "对话",
                    selected = activeTab == WorkbenchTab.CHAT,
                    onClick = { onTabChange(WorkbenchTab.CHAT) },
                )
                SegmentedItem(
                    title = "工作流",
                    selected = activeTab == WorkbenchTab.WORKFLOW,
                    onClick = { onTabChange(WorkbenchTab.WORKFLOW) },
                )
            }
        }

        // 右侧新建对话按钮（圆角胶囊）
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(42.dp),
        ) {
            IconButton(onClick = onCreateThread) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建对话",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SegmentedItem(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .animateContentSize(spring()),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 对话主视图：有消息时展示 MessageTimeline，空对话时呈现高保真引导卡片
 */
@Composable
private fun ChatTabContent(
    controller: WorkbenchController,
    timelineState: Loadable<List<com.openandroidintelligence.conversation.state.TimelineEntry>>,
    catalogState: Loadable<com.openandroidintelligence.conversation.ports.AgentCommandCatalog>,
    gatewayLabel: String,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPickCamera: () -> Unit,
) {
    LoadableRegion(
        state = timelineState,
        emptyHint = "发送第一条消息开始这段对话",
        onRetry = { controller.refreshThreads() },
        modifier = Modifier.fillMaxSize(),
        ready = { entries ->
            if (entries.isEmpty()) {
                EmptyConversationSuggestions(
                    catalogState = catalogState,
                    gatewayLabel = gatewayLabel,
                    onPickCamera = onPickCamera,
                    onSelectCommand = { controller.selectCommand(it) },
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        MessageTimeline(entries = listOf(entry))
                    }
                }
            }
        },
    )
}

/**
 * 空对话状态下的真实引导卡片（对齐截图 1）：
 * 1. 📷 启动相机拍照快捷卡片；
 * 2. 真实命令目录候选卡片（若可用）；
 * 3. Agent Gateway 实时在线状态卡片。
 */
@Composable
private fun EmptyConversationSuggestions(
    catalogState: Loadable<com.openandroidintelligence.conversation.ports.AgentCommandCatalog>,
    gatewayLabel: String,
    onPickCamera: () -> Unit,
    onSelectCommand: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 卡片 1: 相机拍照快捷入口
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .clickable(onClick = onPickCamera),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = "📷 启动半屏相机拍照动效 (高保真取景器与快门闪光)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 卡片 2: 真实命令快捷入口（由 Gateway 目录驱动）
        when (catalogState) {
            is Loadable.Ready -> {
                val firstCommand = catalogState.value.commands.firstOrNull()
                if (firstCommand != null) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectCommand(firstCommand.command) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = "${firstCommand.command} — ${firstCommand.description.ifBlank { "执行网关指令" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            is Loadable.Loading -> {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("正在拉取 Gateway 可用指令目录...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            else -> {}
        }

        // 卡片 3: Agent Gateway 实时在线状态卡片
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Agent Gateway 实时在线",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Text(
                    text = gatewayLabel.ifBlank { "在线就绪" },
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 工作流模式视图：展示当前 Gateway 真实导出的 Agent 指令与自动化能力
 */
@Composable
private fun WorkflowTabContent(
    catalogState: Loadable<com.openandroidintelligence.conversation.ports.AgentCommandCatalog>,
    onRefresh: () -> Unit,
    onSelectCommand: (String) -> Unit,
) {
    LoadableRegion(
        state = catalogState,
        emptyHint = "当前网关尚未配置任何工作流或命令",
        onRetry = onRefresh,
        modifier = Modifier.fillMaxSize(),
        ready = { catalog ->
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = "网关可用工作流与命令清单",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(catalog.commands) { cmd ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectCommand(cmd.command) },
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = cmd.command,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                if (cmd.description.isNotBlank()) {
                                    Text(
                                        text = cmd.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = { onSelectCommand(cmd.command) },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            ) {
                                Text("填入", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        },
    )
}
