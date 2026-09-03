package com.openandroidintelligence.conversation.workbench

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.GenerationState

/**
 * 动效输入卡片，严格还原 HTML 动效预览（截图 1、4、5）：
 * 1. 悬浮胶囊形状折叠态 ↔ 平滑向上展开卡片态；
 * 2. 点击加号弹出附件快捷菜单（相机拍照、相册图库、本地文档）；
 * 3. 附件条呈现真实三步上传状态（准备/上传进度/核验完成/失败重试）；
 * 4. 向上箭头发送按钮，生成时平滑转为停止按钮；
 * 5. 完全遵守 Material Design 3 动态取色规范，无固定死颜色。
 */
@Composable
fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    generation: GenerationState,
    canSend: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit,
    attachments: List<AttachmentDraft> = emptyList(),
    onRemoveAttachment: (String) -> Unit = {},
    onRetryAttachment: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var isExpanded by remember { mutableStateOf(false) }
    var isPlusOpen by remember { mutableStateOf(false) }

    val running = generation == GenerationState.RUNNING || generation == GenerationState.QUEUED
    val currentShape = if (isExpanded) RoundedCornerShape(24.dp) else RoundedCornerShape(32.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ===== 1. 加号快捷操作浮层菜单（截图 4） =====
            AnimatedVisibility(
                visible = isPlusOpen,
                enter = fadeIn(spring()) + slideInVertically(spring()) { it / 2 } + scaleIn(spring(), initialScale = 0.85f),
                exit = fadeOut(spring()) + slideOutVertically(spring()) { it / 2 } + scaleOut(spring(), targetScale = 0.85f),
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(18.dp)),
                ) {
                    Column(
                        modifier = Modifier
                            .width(220.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        AttachmentMenuItem(
                            icon = Icons.Default.CameraAlt,
                            title = "拍摄现场照片",
                            onClick = {
                                isPlusOpen = false
                                onPickCamera()
                            },
                        )
                        AttachmentMenuItem(
                            icon = Icons.Default.Image,
                            title = "从系统图库选择",
                            onClick = {
                                isPlusOpen = false
                                onPickGallery()
                            },
                        )
                        AttachmentMenuItem(
                            icon = Icons.Default.AttachFile,
                            title = "附加本地文档",
                            onClick = {
                                isPlusOpen = false
                                onPickDocument()
                            },
                        )
                    }
                }
            }

            // ===== 2. 主输入卡片容器 =====
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = currentShape,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        shape = currentShape,
                    )
                    .animateContentSize(spring()),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    // 展开态顶部拖拽把手与收起按钮
                    if (isExpanded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(36.dp)
                                    .height(4.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outlineVariant),
                            )
                        }
                    }

                    // 真实附件草稿条
                    if (attachments.isNotEmpty()) {
                        AttachmentDraftStrip(
                            attachments = attachments,
                            onRemove = onRemoveAttachment,
                            onRetry = onRetryAttachment,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }

                    // 输入核心行
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 加号附件按钮
                        IconButton(
                            onClick = { isPlusOpen = !isPlusOpen },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                imageVector = if (isPlusOpen) Icons.Default.Close else Icons.Default.Add,
                                contentDescription = "添加附件",
                                tint = if (isPlusOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp),
                            )
                        }

                        // 输入框
                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                onDraftChange(it)
                                if (!isExpanded && it.isNotBlank()) {
                                    isExpanded = true
                                }
                            },
                            placeholder = {
                                Text(
                                    text = if (isExpanded) "输入消息、指令或输入 / 触发命令菜单..." else "输入消息或 / 命令...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(
                                    min = if (isExpanded) 80.dp else 48.dp,
                                    max = 160.dp,
                                )
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused) {
                                        isExpanded = true
                                    }
                                },
                            shape = RoundedCornerShape(if (isExpanded) 14.dp else 24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        // 右侧动作区：收起按钮（展开时）+ 麦克风 + 发送/停止
                        if (isExpanded) {
                            IconButton(
                                onClick = { isExpanded = false },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "收起",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            IconButton(
                                onClick = onVoiceInput,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "语音输入",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }

                        // 发送 / 停止生成按钮
                        if (running) {
                            StopButton(onStop)
                        } else {
                            SendButton(
                                enabled = canSend,
                                onSend = {
                                    onSend()
                                    isExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentMenuItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Renders attachment drafts waiting for upload/verification or retry.
 */
@Composable
fun AttachmentDraftStrip(
    attachments: List<AttachmentDraft>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id.value }) { draft ->
            AttachmentDraftChip(
                draft = draft,
                onRemove = { onRemove(draft.id.value) },
                onRetry = { onRetry(draft.id.value) },
            )
        }
    }
}

@Composable
fun AttachmentDraftChip(
    draft: AttachmentDraft,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = draft.state == AttachmentState.RETRYABLE_FAILURE || draft.state == AttachmentState.TERMINAL_FAILURE
    val isVerified = draft.state == AttachmentState.VERIFIED
    val isUploading = draft.state == AttachmentState.LOCAL_PREPARING ||
        draft.state == AttachmentState.CREATE_PENDING ||
        draft.state == AttachmentState.UPLOADING ||
        draft.state == AttachmentState.VERIFYING

    val containerColor = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isVerified -> MaterialTheme.colorScheme.secondaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                isUploading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                isVerified -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "已就绪",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                isError -> {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "上传失败",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = draft.filename,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatFileSize(draft.sizeBytes) + if (isError) " · 失败" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            if (isError && draft.state == AttachmentState.RETRYABLE_FAILURE) {
                IconButton(onClick = onRetry, modifier = Modifier.size(20.dp)) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "重试",
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(20.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "移除",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}KB"
    else -> "%.1fMB".format(bytes.toFloat() / (1024 * 1024))
}

@Composable
private fun SendButton(enabled: Boolean, onSend: () -> Unit) {
    Surface(
        color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = CircleShape,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        IconButton(onClick = onSend, enabled = enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "发送",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun StopButton(onStop: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.error,
        shape = CircleShape,
        modifier = Modifier.padding(start = 6.dp),
    ) {
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = "停止生成",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** The debounce batch strip: members keep their identity while waiting to merge. */
@Composable
fun PendingBatchStrip(
    members: List<com.openandroidintelligence.conversation.state.TimelineEntry>,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            members.forEach { member ->
                Text(
                    text = "● " + member.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                text = "同一批次 · ${members.size} 条 · 等待合并",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The gateway status line shown above the composer. */
@Composable
fun GatewayStatusLine(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
