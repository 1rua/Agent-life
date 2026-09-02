package com.agentlife.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The gateway login screen: negotiate + password login, nothing else.
 *
 * The phase banner reflects only what the runtime proved: negotiating,
 * authenticating, failed with the Gateway's own error code, or connected with
 * the limits this connection negotiated.
 */
@Composable
fun GatewayLoginScreen(
    phase: ConnectionPhase,
    onLogin: (url: String, username: String, password: CharArray) -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var url by remember { mutableStateOf("https://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Cloud,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(44.dp),
        )
        Text(text = "连接你的 Agent Gateway", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "手机始终是本机数据与设备操作的最终授权者",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Gateway 地址") },
                    placeholder = { Text("https://gateway.example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Default.VisibilityOff
                                } else {
                                    Icons.Default.Visibility
                                },
                                contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                PhaseBanner(phase, onRetry)

                Button(
                    onClick = { onLogin(url, username, password.toCharArray()) },
                    enabled = (url.startsWith("https://") || url.startsWith("http://")) &&
                        url.trim() != "https://" &&
                        url.trim() != "http://" &&
                        username.isNotBlank() &&
                        password.isNotEmpty() &&
                        phase !is ConnectionPhase.Negotiating &&
                        phase !is ConnectionPhase.Authenticating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("登录并配对")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) { Text("设置与平台管理") }
    }
}

@Composable
private fun PhaseBanner(phase: ConnectionPhase, onRetry: () -> Unit) {
    when (phase) {
        ConnectionPhase.Disconnected -> Unit

        ConnectionPhase.Negotiating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.padding(4.dp))
            Text("正在协商协议版本…", style = MaterialTheme.typography.bodySmall)
        }

        ConnectionPhase.Authenticating -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.padding(4.dp))
            Text("正在验证账号密码…", style = MaterialTheme.typography.bodySmall)
        }

        is ConnectionPhase.Connected -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.padding(4.dp))
            Text(
                text = "已连接 ${phase.username}",
                style = MaterialTheme.typography.bodySmall,
            )
            phase.limits?.maxSingleAttachmentBytes?.let { max ->
                Spacer(modifier = Modifier.padding(6.dp))
                Text(
                    text = "附件上限 ${max / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is ConnectionPhase.Failed -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = MaterialTheme.colorScheme.error)
                Spacer(modifier = Modifier.padding(4.dp))
                Text(
                    text = "连接失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = phase.code,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            androidx.compose.material3.TextButton(onClick = onRetry) { Text("重新输入") }
        }
    }
}

@Composable
private fun StatusDot(color: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}
