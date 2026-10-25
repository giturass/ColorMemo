package dev.colormemo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import dev.colormemo.core.mcp.LocalMcpServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSettingsScreen(state: MemoUiState, callbacks: MemoCallbacks, onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("MCP服务器") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("允许本机 AI 连接", style = MaterialTheme.typography.titleMedium)
                    Text(if (state.aiServiceStarting) "正在启动…" else if (state.aiServiceRunning) "服务已开启" else "服务已关闭")
                }
                Switch(checked = state.aiServiceRunning || state.aiServiceStarting,
                    onCheckedChange = { if (it) callbacks.onStartAiService() else callbacks.onStopAiService() },
                    enabled = state.aiAccessLoaded || state.aiServiceRunning || state.aiServiceStarting)
            }
            HorizontalDivider()
            Text("连接本机客户端", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(LocalMcpServer.URL) }
            Button(onClick = callbacks.onCopyAiToken, enabled = state.aiServiceRunning) { Text("复制访问令牌") }
            OutlinedButton(onClick = callbacks.onResetAiToken, enabled = !state.aiServiceRunning && !state.aiServiceStarting) {
                Text("重置访问令牌")
            }
            Text("令牌重启后保持不变；停止服务后可重置，旧令牌随即失效。", style = MaterialTheme.typography.bodySmall)
            if (!state.notificationPermissionGranted) {
                Text("开启通知后，可从通知栏停止服务。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = callbacks.onRequestNotificationPermission) { Text("允许通知") }
            }
            TextButton(onClick = {
                runCatching { uriHandler.openUri("https://github.com/giturass/ColorMemo/blob/main/docs/ai-mcp.md") }
            }) {
                Text("使用说明与客户端配置")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
