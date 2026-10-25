package dev.colormemo.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.colormemo.app.sync.SyncInterval
import dev.colormemo.app.BuildConfig
import dev.colormemo.core.sync.SyncEncryptionKey
import java.net.URI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SyncSettingsScreen(state: MemoUiState, callbacks: MemoCallbacks, onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    // Credentials intentionally stay out of savedInstanceState / unencrypted Bundles.
    var draft by remember(state.syncSettings) { mutableStateOf(state.syncSettings) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var recoveryKeySaved by remember(state.syncSettings, draft.encryptionKey) { mutableStateOf(false) }
    var recoveryKeyCopied by remember(draft.encryptionKey) { mutableStateOf(false) }
    var recoveryKeyMenu by remember(draft.encryptionKey) { mutableStateOf(false) }
    var confirmNewRecoveryKey by remember(state.syncSettings, draft.encryptionKey) { mutableStateOf(false) }
    var intervalMenu by remember { mutableStateOf(false) }
    val validRecoveryKey = remember(draft.encryptionKey) {
        runCatching { SyncEncryptionKey.parse(draft.encryptionKey.trim()) }.isSuccess
    }
    // Use the saved key, so toggling sync or reopening settings cannot restart setup.
    val isRecoveryKeySetup = remember(state.syncSettings.encryptionKey) {
        runCatching { SyncEncryptionKey.parse(state.syncSettings.encryptionKey.trim()) }.isFailure
    }
    val needsRecoveryConfirmation = draft.enabled && isRecoveryKeySetup
    val recoveryConfirmed = !needsRecoveryConfirmation || recoveryKeySaved
    val dirty = draft != state.syncSettings
    val close: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    val save: () -> Unit = {
        // Disabling sync must not save an unconfirmed key and finish first-time setup.
        if (!draft.enabled) draft = draft.copy(encryptionKey = state.syncSettings.encryptionKey)
        val candidate = draft.normalized()
        validationError = if (candidate.enabled) candidate.validationError() else null
        if (validationError == null && recoveryConfirmed) callbacks.onSaveSyncSettings(candidate)
    }
    BackHandler(onBack = close)

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("提醒", style = MaterialTheme.typography.titleMedium)
                }
                PermissionRow("通知权限", state.notificationPermissionGranted, callbacks.onRequestNotificationPermission)
                PermissionRow("准时提醒", state.exactAlarmAllowed, callbacks.onRequestExactAlarmPermission)
                Text("提醒在本机触发。主题跟随系统深色或浅色模式。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SettingsCard {
                SettingsToggle(
                    title = "启用云同步",
                    description = "在设备之间同步便签、颜色和提醒",
                    checked = draft.enabled,
                    onCheckedChange = { draft = draft.copy(enabled = it); validationError = null },
                )
                if (draft.enabled) {
                    HorizontalDivider()
                    Text("选择存储服务", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SyncProviderUi.entries.forEach { provider ->
                            FilterChip(
                                selected = draft.provider == provider,
                                onClick = {
                                    draft = draft.copy(provider = provider, region = if (provider == SyncProviderUi.R2) "auto" else if (draft.region == "auto") "us-east-1" else draft.region)
                                    validationError = null
                                },
                                label = {
                                    Text(
                                        text = when (provider) {
                                            SyncProviderUi.WEBDAV -> "WebDAV"
                                            SyncProviderUi.S3 -> "S3"
                                            SyncProviderUi.R2 -> "R2"
                                        },
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                },
                                modifier = Modifier.weight(1f).height(48.dp),
                            )
                        }
                    }
                    SettingsField(
                        value = draft.endpoint,
                        onValueChange = { draft = draft.copy(endpoint = it); validationError = null },
                        label = "服务器地址",
                        placeholder = when (draft.provider) {
                            SyncProviderUi.WEBDAV -> "https://cloud.example.com/dav/"
                            SyncProviderUi.S3 -> "https://s3.us-east-1.amazonaws.com"
                            SyncProviderUi.R2 -> "https://<账户 ID>.r2.cloudflarestorage.com"
                        },
                        keyboardType = KeyboardType.Uri,
                    )
                    if (draft.provider == SyncProviderUi.WEBDAV) {
                        SettingsField(draft.username, { draft = draft.copy(username = it) }, "用户名")
                        SecretField(draft.password, { draft = draft.copy(password = it) }, "密码 / 应用专用密码")
                    } else {
                        SettingsField(draft.bucket, { draft = draft.copy(bucket = it) }, "存储桶名称", placeholder = "my-notes")
                        if (draft.provider == SyncProviderUi.S3) {
                            SettingsField(draft.region, { draft = draft.copy(region = it) }, "区域", placeholder = "us-east-1")
                        }
                        SettingsField(draft.accessKeyId, { draft = draft.copy(accessKeyId = it) }, "Access Key ID", keyboardType = KeyboardType.Ascii)
                        SecretField(draft.secretAccessKey, { draft = draft.copy(secretAccessKey = it) }, "Secret Access Key")
                        TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起临时凭据" else "使用临时凭据（可选）") }
                        if (advanced) SecretField(draft.sessionToken, { draft = draft.copy(sessionToken = it) }, "Session Token（可选）")
                    }
                    if (draft.provider != SyncProviderUi.WEBDAV) {
                        SettingsField(draft.pathPrefix, { draft = draft.copy(pathPrefix = it) }, "对象前缀（可留空）", placeholder = "colormemo")
                    }
                    HorizontalDivider()
                    Text("端到端加密", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "所有同步内容（包括便签、白板、图片、颜色和提醒）都会在本机加密后上传。每台设备必须使用同一恢复密钥。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isRecoveryKeySetup) {
                        SecretField(
                            value = draft.encryptionKey,
                            onValueChange = { draft = draft.copy(encryptionKey = it); validationError = null },
                            label = "恢复密钥",
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                when {
                                    draft.encryptionKey.isBlank() -> "尚未设置密钥"
                                    validRecoveryKey -> "密钥已填写"
                                    else -> "请填写完整密钥"
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (draft.encryptionKey.isBlank()) {
                                OutlinedButton(onClick = { confirmNewRecoveryKey = true }, enabled = !state.syncing) {
                                    Icon(Icons.Outlined.VpnKey, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("生成密钥")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        val key = draft.encryptionKey.trim()
                                        if (clipboard != null && runCatching { SyncEncryptionKey.parse(key) }.isSuccess) {
                                            val clip = ClipData.newPlainText("ColorMemo 恢复密钥", key).apply {
                                                description.extras = PersistableBundle().apply {
                                                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                                                }
                                            }
                                            clipboard.setPrimaryClip(clip)
                                            recoveryKeyCopied = true
                                        }
                                    },
                                    enabled = clipboard != null && validRecoveryKey,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                ) {
                                    Icon(if (recoveryKeyCopied) Icons.Outlined.Check else Icons.Outlined.ContentCopy, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (recoveryKeyCopied) "已复制" else "复制密钥")
                                }
                                Box {
                                    IconButton(onClick = { recoveryKeyMenu = true }, enabled = !state.syncing) {
                                        Icon(Icons.Outlined.MoreVert, "恢复密钥更多操作")
                                    }
                                    DropdownMenu(expanded = recoveryKeyMenu, onDismissRequest = { recoveryKeyMenu = false }) {
                                        DropdownMenuItem(
                                            text = { Text("重新生成密钥…") },
                                            leadingIcon = { Icon(Icons.Outlined.VpnKey, null) },
                                            onClick = { recoveryKeyMenu = false; confirmNewRecoveryKey = true },
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            "仅首次配置时提供密钥生成和复制。连接已有加密数据时，请输入原密钥。请在保存设置前将密钥安全备份到应用外；保存后不再显示密钥，也不再提供复制或生成入口。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().toggleable(
                                value = recoveryKeySaved,
                                enabled = draft.encryptionKey.isNotBlank(),
                                role = Role.Checkbox,
                                onValueChange = { recoveryKeySaved = it },
                            ).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = recoveryKeySaved, onCheckedChange = null, enabled = draft.encryptionKey.isNotBlank())
                            Text("已在应用外安全保存恢复密钥", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("恢复密钥已配置", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            "密钥已安全保存在本机，不再提供显示、复制或生成入口。在其他设备配置同步时，请使用首次配置时备份的恢复密钥。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider()
                    SettingsToggle("自动同步", "修改后同步，并定期检查其他设备的更新", draft.automaticSync, { draft = draft.copy(automaticSync = it) })
                    if (draft.automaticSync) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("同步间隔", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Box {
                                OutlinedButton(onClick = { intervalMenu = true }) {
                                    Text(SyncInterval.label(draft.syncIntervalMinutes))
                                    Icon(Icons.Outlined.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = intervalMenu, onDismissRequest = { intervalMenu = false }) {
                                    SyncInterval.choices.forEach { minutes ->
                                        DropdownMenuItem(
                                            text = { Text(SyncInterval.label(minutes)) },
                                            trailingIcon = { if (draft.syncIntervalMinutes == minutes) Icon(Icons.Outlined.Check, null) },
                                            onClick = { draft = draft.copy(syncIntervalMinutes = minutes); intervalMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        Text("后台检查更新的间隔，最短 15 分钟；系统省电策略可能延后执行。修改便签后仍会自动同步。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SettingsToggle("仅在不计费网络自动同步", "手动同步不受此项限制", draft.wifiOnly, { draft = draft.copy(wifiOnly = it) })
                    }
                }
                validationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (draft.enabled) {
                        OutlinedButton(onClick = {
                            val candidate = draft.normalized()
                            validationError = candidate.validationError()
                            if (validationError == null && recoveryConfirmed) callbacks.onTestSync(candidate)
                        }, enabled = !state.syncing && recoveryConfirmed, modifier = Modifier.weight(1f)) { Text("测试连接") }
                    }
                    Button(onClick = save, enabled = dirty && !state.syncing && recoveryConfirmed, modifier = Modifier.weight(1f)) {
                        Text(if (dirty) "保存设置" else "已保存")
                    }
                }
                if (dirty) Text("修改尚未保存；测试连接不会保存设置。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (state.syncSettings.enabled) Icons.Outlined.Cloud else Icons.Outlined.CloudOff, null, Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text("同步状态", style = MaterialTheme.typography.titleMedium)
                }
                Text(state.syncStatus, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = callbacks.onSync, enabled = state.syncSettings.enabled && !state.syncing && !dirty, modifier = Modifier.fillMaxWidth()) {
                    if (state.syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.syncing) "同步中…" else "立即同步")
                }
            }

            if (callbacks.onExportNotes != null || callbacks.onImportNotes != null || callbacks.onExportMarkdown != null) {
                SettingsCard {
                    Text("备份与迁移", style = MaterialTheme.typography.titleMedium)
                    Text("用 JSON 在彩笺间备份恢复；用 Markdown 文件迁移到其他工具。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    callbacks.onExportNotes?.let { action ->
                        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出 JSON 备份") }
                    }
                    callbacks.onImportNotes?.let { action ->
                        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导入 JSON 备份") }
                    }
                    callbacks.onExportMarkdown?.let { action ->
                        OutlinedButton(onClick = action, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("导出 Markdown ZIP") }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                MemoBrandMark(size = 44.dp)
                Text("彩笺 ColorMemo · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Text("开源，给灵感一个自由的家。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            }
        }
    }
    if (confirmNewRecoveryKey && draft.enabled && isRecoveryKeySetup) {
        val replacing = draft.encryptionKey.isNotBlank()
        AlertDialog(
            onDismissRequest = { confirmNewRecoveryKey = false },
            title = { Text(if (replacing) "重新生成恢复密钥？" else "生成恢复密钥？") },
            text = { Text(if (replacing)
                "这会替换尚未保存的恢复密钥。新密钥无法解密原有云端数据；连接已有数据时，请使用原密钥。请在首次保存设置前备份密钥，保存后不再提供显示、复制或生成入口。"
                else "首次设置云同步时才需要生成新密钥。如果已有其他设备或云端数据，请取消并填写原来的恢复密钥。") },
            confirmButton = {
                TextButton(onClick = {
                    draft = draft.copy(encryptionKey = SyncEncryptionKey.generate())
                    recoveryKeySaved = false
                    validationError = null
                    confirmNewRecoveryKey = false
                }, enabled = !state.syncing) { Text(if (replacing) "确认重新生成" else "生成密钥") }
            },
            dismissButton = { TextButton(onClick = { confirmNewRecoveryKey = false }) { Text("取消") } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的设置？") },
            text = { Text("当前修改还没有保存。你可以返回设置页保存后再离开。") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("放弃修改") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续设置") } },
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (visible) "隐藏$label" else "显示$label") }
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (granted) {
            Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text("已开启", modifier = Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        } else TextButton(onClick = onRequest) { Text("开启") }
    }
}

private fun SyncSettingsUi.normalized(): SyncSettingsUi = copy(
    endpoint = endpoint.trim(),
    username = username.trim(),
    bucket = bucket.trim(),
    region = if (provider == SyncProviderUi.R2) "auto" else region.trim(),
    accessKeyId = accessKeyId.trim(),
    pathPrefix = pathPrefix.trim().trim('/'),
    encryptionKey = encryptionKey.trim(),
    syncIntervalMinutes = SyncInterval.normalize(syncIntervalMinutes),
)

private fun SyncSettingsUi.validationError(): String? {
    val uri = runCatching { URI(endpoint) }.getOrNull()
    if (uri == null || !uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
        return "请填写完整的 HTTPS 服务器地址。"
    }
    if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return "服务器地址中不要包含账户、查询参数或片段。"
    if (provider != SyncProviderUi.WEBDAV && (bucket.isBlank() || region.isBlank() || accessKeyId.isBlank() || secretAccessKey.isBlank())) {
        return "请填写存储桶、区域和访问凭据。"
    }
    if (runCatching { SyncEncryptionKey.parse(encryptionKey) }.isFailure) {
        return "请生成恢复密钥，或输入其他设备使用的 43 位恢复密钥。"
    }
    return null
}
