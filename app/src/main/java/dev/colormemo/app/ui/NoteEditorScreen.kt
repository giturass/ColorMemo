package dev.colormemo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.colormemo.app.ui.theme.containerColor
import dev.colormemo.app.ui.theme.displayName
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MAX_TITLE_LENGTH = 500
private const val MAX_CONTENT_LENGTH = 100_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorScreen(
    note: Note?,
    state: MemoUiState,
    callbacks: MemoCallbacks,
    onClose: () -> Unit,
) {
    // A save may commit just as rotation cancels its UI coroutine. Retrying must
    // address the same record instead of creating a second copy with a new ID.
    val newNoteId = rememberSaveable { UUID.randomUUID().toString() }
    var title by rememberSaveable { mutableStateOf(note?.title.orEmpty()) }
    var content by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(note?.content.orEmpty())) }
    var color by rememberSaveable { mutableStateOf(note?.color ?: NoteColor.DEFAULT) }
    var pinned by rememberSaveable { mutableStateOf(note?.isPinned ?: false) }
    var archived by rememberSaveable { mutableStateOf(note?.isArchived ?: false) }
    var reminderAt by rememberSaveable { mutableStateOf(note?.reminderAt) }
    var preview by rememberSaveable { mutableStateOf(note?.deletedAt != null) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showReminder by rememberSaveable { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val readOnly = note?.deletedAt != null
    val draft = NoteDraft(note, title, content.text, color, pinned, archived, reminderAt, if (note == null) newNoteId else null)
    val initialFingerprint = rememberSaveable { draft.fingerprint() }
    val fingerprint = remember(title, content.text, color, pinned, archived, reminderAt) { draft.fingerprint() }
    val dirty = fingerprint != initialFingerprint
    val canSave = !saving && (title.isNotBlank() || content.text.isNotBlank()) &&
        title.length <= MAX_TITLE_LENGTH && content.text.length <= MAX_CONTENT_LENGTH
    val currentNote = state.notes.firstOrNull { it.id == note?.id }
    val remoteChanged = !state.loading && note != null && currentNote != null && currentNote.revision != note.revision

    val close: () -> Unit = {
        if (!saving) {
            if (dirty && !readOnly) showLeaveDialog = true else onClose()
        }
    }
    val save: () -> Unit = {
        if (canSave) {
            saving = true
            saveError = null
            scope.launch {
                try {
                    if (callbacks.onSaveNote(draft)) onClose()
                    else saveError = "保存失败，修改仍保留在这里，请重试。"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    saveError = "保存失败，修改仍保留在这里，请重试。"
                } finally {
                    saving = false
                }
            }
        }
    }
    BackHandler(onBack = close)

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = color.containerColor(),
        topBar = {
            TopAppBar(
                title = { Text(if (readOnly) "回收站便笺" else if (note == null) "新便笺" else "编辑便笺", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = close, enabled = !saving) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    if (!readOnly) {
                        IconToggleButton(checked = pinned, onCheckedChange = { pinned = it }, enabled = !saving) {
                            Icon(Icons.Outlined.PushPin, if (pinned) "取消置顶" else "置顶", tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = save, enabled = canSave) {
                            if (saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.Check, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (saving) "保存中" else "保存")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = color.containerColor()),
            )
        },
        bottomBar = {
            Surface(color = color.containerColor(), tonalElevation = 1.dp, modifier = Modifier.navigationBarsPadding()) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    if (readOnly) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { showDeleteDialog = true }, modifier = Modifier.weight(1f)) { Text("彻底删除", color = MaterialTheme.colorScheme.error) }
                            Button(onClick = { callbacks.onRestore(note.id); onClose() }, modifier = Modifier.weight(1f)) { Text("恢复便笺") }
                        }
                    } else {
                        if (showPalette) {
                            Text("卡片颜色 · ${color.displayName}", Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium)
                            ColorPalette(color, onSelect = { if (!saving) color = it }, modifier = Modifier.fillMaxWidth())
                        }
                        if (!preview) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                                MarkdownAction(Icons.Outlined.FormatBold, "加粗", saving) { content = content.insert("**", "**", "文字"); focusRequester.requestFocus() }
                                TextButton(onClick = { content = content.insert("## ", "", "标题", block = true); focusRequester.requestFocus() }, enabled = !saving) { Text("H₂", style = MaterialTheme.typography.titleMedium) }
                                MarkdownAction(Icons.AutoMirrored.Outlined.FormatListBulleted, "插入列表", saving) { content = content.insert("- ", "", "列表项", block = true); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.CheckBox, "插入待办清单", saving) { content = content.insert("- [ ] ", "", "待办事项", block = true); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.Link, "插入链接", saving) { content = content.insert("[", "](https://)", "链接文字"); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.Code, "插入代码块", saving) { content = content.insert("```\n", "\n```", "代码", block = true); focusRequester.requestFocus() }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconToggleButton(checked = showPalette, onCheckedChange = { showPalette = it }, enabled = !saving) { Icon(Icons.Outlined.Palette, "选择卡片颜色") }
                            IconButton(onClick = { showReminder = true }, enabled = !saving) { Icon(Icons.Outlined.NotificationsNone, "设置提醒") }
                            IconToggleButton(checked = archived, onCheckedChange = { archived = it }, enabled = !saving) { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (archived) "取消归档" else "归档") }
                            Box {
                                IconButton(onClick = { menuOpen = true }, enabled = !saving) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    DropdownMenuItem(text = { Text("Markdown 帮助") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) }, onClick = { menuOpen = false; showHelp = true })
                                    if (note != null) {
                                        DropdownMenuItem(text = { Text("移到回收站") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menuOpen = false; showDeleteDialog = true })
                                    }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { preview = !preview }, enabled = !saving) {
                                Icon(if (preview) Icons.Outlined.Edit else Icons.Outlined.Preview, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (preview) "编辑" else "预览")
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
        ) {
            if (remoteChanged && !readOnly) {
                EditorNotice("其他设备已修改这条便笺。保存时会保留两份修改。")
            }
            saveError?.let { EditorNotice(it, isError = true) }
            if (readOnly) EditorNotice("这条便笺在回收站中，恢复后即可编辑。")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (archived) AssistChip(onClick = { if (!readOnly && !saving) archived = false }, label = { Text("已归档") }, leadingIcon = { Icon(Icons.Outlined.Archive, null, Modifier.size(16.dp)) })
                reminderAt?.let { time ->
                    AssistChip(
                        onClick = { if (!readOnly && !saving) showReminder = true },
                        label = { Text(formatReminder(time)) },
                        leadingIcon = { Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(16.dp)) },
                    )
                    if (!readOnly) IconButton(onClick = { reminderAt = null }, enabled = !saving) { Icon(Icons.Outlined.Close, "移除提醒", Modifier.size(18.dp)) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (preview || readOnly) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 20.dp))
                if (content.text.isBlank()) Text("还没有正文", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else MarkdownPreview(content.text)
            } else {
                BasicTextField(
                    value = title,
                    onValueChange = { if (it.length <= MAX_TITLE_LENGTH) title = it },
                    enabled = !saving,
                    textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                    decorationBox = { field ->
                        Box {
                            if (title.isEmpty()) Text("标题", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f))
                            field()
                        }
                    },
                )
                BasicTextField(
                    value = content,
                    onValueChange = { if (it.text.length <= MAX_CONTENT_LENGTH) content = it },
                    enabled = !saving,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp).focusRequester(focusRequester),
                    decorationBox = { field ->
                        Box {
                            if (content.text.isEmpty()) Text("开始记录…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                            field()
                        }
                    },
                )
            }
            Text(
                if (note == null) "${content.text.length} 字 · Markdown" else "${content.text.length} 字 · 上次修改 ${formatModified(note.updatedAt)}",
                modifier = Modifier.padding(top = 32.dp, bottom = 24.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (content.text.length >= MAX_CONTENT_LENGTH || title.length >= MAX_TITLE_LENGTH) {
                Text("标题最多 500 字，正文最多 100,000 字。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (showReminder) {
        ReminderPickerDialog(
            initialTime = reminderAt,
            notificationPermissionGranted = state.notificationPermissionGranted,
            exactAlarmAllowed = state.exactAlarmAllowed,
            onRequestNotifications = callbacks.onRequestNotificationPermission,
            onRequestExactAlarm = callbacks.onRequestExactAlarmPermission,
            onDismiss = { showReminder = false },
            onConfirm = { reminderAt = it; showReminder = false },
        )
    }
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            title = { Text("保存这次修改？") },
            text = { Text("保存后，便笺会留在本机，并按你的同步设置同步。") },
            confirmButton = { TextButton(onClick = { showLeaveDialog = false; save() }, enabled = canSave) { Text("保存") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { showLeaveDialog = false; onClose() }) { Text("放弃修改") }
                    TextButton(onClick = { showLeaveDialog = false }) { Text("继续编辑") }
                }
            },
        )
    }
    if (showDeleteDialog && note != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(if (readOnly) "彻底删除这条便笺？" else "移到回收站？") },
            text = { Text(if (readOnly) "内容将被移除，此操作无法恢复。删除也会同步到其他设备。" else if (dirty) "未保存的修改会丢弃，原便笺会移到回收站。" else "之后可以在回收站中恢复这条便笺。") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    if (readOnly) callbacks.onDeleteForever(note.id) else callbacks.onTrash(note.id)
                    onClose()
                }) { Text(if (readOnly) "彻底删除" else "移到回收站", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("取消") } },
        )
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text("用 Markdown 记录") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("用底部工具栏插入格式，点击「预览」查看排版。")
                    Text("# 一级标题\n## 二级标题\n**加粗文字**\n*斜体文字*\n- 列表项\n- [ ] 待办事项\n- [x] 已完成\n[链接文字](https://example.com)\n> 引用\n`行内代码`", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    Text("也支持代码块、表格和删除线。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun MarkdownAction(icon: ImageVector, description: String, disabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = !disabled) { Icon(icon, description, Modifier.size(20.dp)) }
}

@Composable
private fun EditorNotice(message: String, isError: Boolean = false) {
    Surface(
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Text(message, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun NoteDraft.fingerprint(): String {
    val text = listOf(title, content, color.name, isPinned.toString(), isArchived.toString(), reminderAt.toString())
        .joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private fun TextFieldValue.insert(prefix: String, suffix: String, placeholder: String, block: Boolean = false): TextFieldValue {
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    val selected = text.substring(start, end).ifEmpty { placeholder }
    val leading = if (block && start > 0 && text[start - 1] != '\n') "\n" else ""
    val insertion = leading + prefix + selected + suffix
    val newText = text.replaceRange(start, end, insertion)
    if (newText.length > MAX_CONTENT_LENGTH) return this
    return TextFieldValue(newText, TextRange(start + leading.length + prefix.length, start + leading.length + prefix.length + selected.length))
}
