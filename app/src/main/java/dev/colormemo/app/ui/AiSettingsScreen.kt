package dev.colormemo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.colormemo.core.mcp.LocalMcpServer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AiSettingsScreen(state: MemoUiState, callbacks: MemoCallbacks, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Scaffold(topBar = {
        TopAppBar(title = { Text("本地 AI 服务") }, navigationIcon = {
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
            Text("Codex 等本机 AI 客户端可通过 MCP 创建、读取、搜索、编辑、置顶、归档和回收笔记。服务运行时会显示通知，可随时停止；每次开启都会生成新的访问令牌。")
            Text("已有笔记和手动创建的笔记默认对 AI 不可见。在编辑页底部三点按钮右侧，点击 AI 可见性按钮可逐条授权。AI 创建的笔记默认可见。")
            Text("AI 不可见的笔记不会出现在搜索中，也不能被 AI 读取、编辑、归档、回收或恢复。隐藏立即生效，不需要保存正文；已经发送给客户端的内容无法撤回。")
            Text("可见性仅保存在当前设备，不随同步或备份传递。AI 无法修改权限，也不能永久删除笔记、访问附件文件或导出整个数据库。")
            HorizontalDivider()
            Text("连接本机客户端", style = MaterialTheme.typography.titleMedium)
            SelectionContainer { Text(LocalMcpServer.URL) }
            Button(onClick = callbacks.onCopyAiToken, enabled = state.aiServiceRunning) { Text("复制本次访问令牌") }
            Text("在启动 AI 客户端的终端中，将令牌放入 COLORMEMO_MCP_TOKEN 环境变量，再添加以下配置。服务重启后请更新令牌并重启客户端。")
            SelectionContainer {
                Text("[mcp_servers.colormemo]\nurl = \"${LocalMcpServer.URL}\"\nbearer_token_env_var = \"COLORMEMO_MCP_TOKEN\"",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (!state.notificationPermissionGranted) {
                Text("通知权限未开启，系统可能只在运行中的应用列表显示服务。允许通知后可从通知栏直接停止。")
                TextButton(onClick = callbacks.onRequestNotificationPermission) { Text("允许通知") }
            }
            Text("仅监听 127.0.0.1，不向局域网开放。访问令牌只交给可信的本机客户端；获得令牌的本机程序可以操作所有已授权笔记。", style = MaterialTheme.typography.bodySmall)
        }
    }
}
