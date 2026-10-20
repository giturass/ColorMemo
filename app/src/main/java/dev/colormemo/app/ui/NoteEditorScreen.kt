package dev.colormemo.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.FormatUnderlined
import androidx.compose.material.icons.outlined.StrikethroughS
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.FormatListNumbered
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.colormemo.app.ui.theme.containerColor
import dev.colormemo.app.ui.theme.displayName
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.app.data.attachmentStore
import dev.colormemo.app.data.readImageAttachment
import dev.colormemo.app.data.readRemoteImageAttachment
import dev.colormemo.app.data.decodeNoteImage
import dev.colormemo.app.data.isImage
import dev.colormemo.app.data.prepareAttachmentIntent
import dev.colormemo.app.data.markdownAttachmentReferences
import dev.colormemo.app.data.markdownPath
import dev.colormemo.app.data.retainMarkdownAttachments
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardImage
import dev.colormemo.core.sync.SyncJson
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

private const val MAX_TITLE_LENGTH = 500
private const val MAX_CONTENT_LENGTH = 100_000

private val WhiteboardDraftSaver = Saver<Whiteboard?, String>(
    save = { board -> board?.let { SyncJson.json.encodeToString(it) } ?: "" },
    restore = { value -> value.takeIf(String::isNotEmpty)?.let { SyncJson.json.decodeFromString<Whiteboard>(it) } },
)

private val PendingWhiteboardImageSaver = Saver<WhiteboardImage?, String>(
    save = { image -> image?.let { SyncJson.json.encodeToString(it) } ?: "" },
    restore = { value -> value.takeIf(String::isNotEmpty)?.let { SyncJson.json.decodeFromString<WhiteboardImage>(it) } },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NoteEditorScreen(
    note: Note?,
    state: MemoUiState,
    callbacks: MemoCallbacks,
    onClose: () -> Unit,
    newWhiteboard: Boolean = false,
) {
    // A save may commit just as rotation cancels its UI coroutine. Retrying must
    // address the same record instead of creating a second copy with a new ID.
    val newNoteId = rememberSaveable { UUID.randomUUID().toString() }
    var title by rememberSaveable { mutableStateOf(note?.title.orEmpty()) }
    var content by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue(note?.content.orEmpty())) }
    var whiteboard by rememberSaveable(stateSaver = WhiteboardDraftSaver) {
        mutableStateOf(note?.whiteboard ?: if (newWhiteboard) Whiteboard() else null)
    }
    val context = LocalContext.current
    val attachmentFiles = remember(context) { attachmentStore(context) }
    val attachmentSaver = remember(attachmentFiles) {
        Saver<List<NoteAttachment>, String>(
            save = { values -> SyncJson.json.encodeToString(values.map { it.copy(dataBase64 = "") }) },
            restore = { value -> SyncJson.json.decodeFromString<List<NoteAttachment>>(value).map(attachmentFiles::hydrate) },
        )
    }
    var attachments by rememberSaveable(stateSaver = attachmentSaver) { mutableStateOf(note?.attachments.orEmpty()) }
    var pendingWhiteboardImage by rememberSaveable(stateSaver = PendingWhiteboardImageSaver) { mutableStateOf<WhiteboardImage?>(null) }
    var inlineImageIds by rememberSaveable {
        val references = markdownAttachmentReferences(note?.content.orEmpty())
        mutableStateOf((note?.attachments.orEmpty().filter { it.isImage && it.markdownPath in references }.map { it.id } +
            note?.whiteboard?.images.orEmpty().map { it.attachmentId }).distinct())
    }
    val whiteboardImageIds = remember(whiteboard?.images, pendingWhiteboardImage) {
        (whiteboard?.images.orEmpty().map { it.attachmentId } + listOfNotNull(pendingWhiteboardImage?.attachmentId)).toSet()
    }
    val attachmentReferences = remember(content.text) { markdownAttachmentReferences(content.text) }
    // Keep removed images in the draft cache for text undo, but omit them from saves.
    val retainedAttachments = remember(attachments, inlineImageIds, attachmentReferences, whiteboardImageIds) {
        retainMarkdownAttachments(attachments, inlineImageIds, attachmentReferences, whiteboardImageIds)
    }
    val legacyAttachments = remember(retainedAttachments, attachmentReferences, whiteboardImageIds) {
        retainedAttachments.filter { it.markdownPath !in attachmentReferences && it.id !in whiteboardImageIds }
    }
    var imageInsertionStart by rememberSaveable { mutableStateOf(0) }
    var imageInsertionEnd by rememberSaveable { mutableStateOf(0) }
    var selectedImageId by rememberSaveable { mutableStateOf<String?>(null) }
    var importingImage by remember { mutableStateOf(false) }
    var imageError by remember { mutableStateOf<String?>(null) }
    var imageSourceMenu by remember { mutableStateOf(false) }
    var showRemoteImage by rememberSaveable { mutableStateOf(false) }
    var color by rememberSaveable { mutableStateOf(note?.color ?: NoteColor.DEFAULT) }
    var pinned by rememberSaveable { mutableStateOf(note?.isPinned ?: false) }
    var archived by rememberSaveable { mutableStateOf(note?.isArchived ?: false) }
    var reminderAt by rememberSaveable { mutableStateOf(note?.reminderAt) }
    var preview by rememberSaveable { mutableStateOf(note != null) }
    var requestEditorFocus by remember { mutableStateOf(false) }
    var showPalette by rememberSaveable { mutableStateOf(false) }
    var showReminder by rememberSaveable { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showWhiteboardDetails by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val readOnly = note?.deletedAt != null
    val draft = NoteDraft(note, title, content.text, color, pinned, archived, reminderAt, if (note == null) newNoteId else null, whiteboard, retainedAttachments)
    val initialFingerprint = rememberSaveable { draft.fingerprint() }
    val fingerprint = remember(title, content.text, color, pinned, archived, reminderAt, whiteboard, retainedAttachments) { draft.fingerprint() }
    val dirty = fingerprint != initialFingerprint
    val canSave = !saving && !importingImage && pendingWhiteboardImage == null && (title.isNotBlank() || content.text.isNotBlank() || retainedAttachments.isNotEmpty() ||
        whiteboard?.strokes?.isNotEmpty() == true || (whiteboard != null && note != null)) &&
        title.length <= MAX_TITLE_LENGTH && content.text.length <= MAX_CONTENT_LENGTH
    suspend fun insertImage(imported: NoteAttachment) {
        val attachment = attachments.firstOrNull { it.markdownPath == imported.markdownPath } ?: imported
        val nextAttachments = retainedAttachments.filterNot { it.id == attachment.id } + attachment
        require(nextAttachments.size <= NoteAttachment.MAX_PER_NOTE) { "每条笔记最多插入 20 张图片（含已有文件）。" }
        require(nextAttachments.sumOf { it.sizeBytes.toLong() } <= NoteAttachment.MAX_NOTE_BYTES) { "每条笔记的图片及已有文件合计最多 8 MiB。" }
        if (whiteboard != null) {
            require(whiteboard!!.images.size < Whiteboard.MAX_IMAGES) { "每张白板最多插入 20 张图片。" }
            val image = withContext(Dispatchers.Default) {
                val bitmap = requireNotNull(decodeNoteImage(attachment.bytes(), 384)) { "图片无法解码，请选择其他图片。" }
                try {
                    WhiteboardImage(UUID.randomUUID().toString(), attachment.id, 0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                } finally {
                    bitmap.recycle()
                }
            }
            pendingWhiteboardImage = image
        } else {
            content = content.copy(selection = TextRange(imageInsertionStart, imageInsertionEnd)).insertMarkdownImage(attachment)
            preview = false
            requestEditorFocus = true
        }
        attachments = attachments.filterNot { it.id == attachment.id } + attachment
        inlineImageIds = (inlineImageIds + attachment.id).distinct()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importingImage = true
            imageError = null
            scope.launch {
                try {
                    val imported = withContext(Dispatchers.IO) { readImageAttachment(context, uri) }
                    insertImage(imported)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: IllegalArgumentException) {
                    imageError = error.message ?: "无法插入图片"
                } catch (_: Exception) {
                    imageError = "无法读取图片，请重新选择。"
                } finally {
                    importingImage = false
                }
            }
        } else importingImage = false
    }
    val openAttachment: (NoteAttachment) -> Unit = { attachment ->
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { prepareAttachmentIntent(context, attachment) }
                context.startActivity(intent)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: android.content.ActivityNotFoundException) {
                selectedImageId = null
                imageError = "没有可打开此文件的应用。"
            } catch (_: Exception) {
                selectedImageId = null
                imageError = "无法打开文件，请重试。"
            }
        }
    }
    val currentNote = state.notes.firstOrNull { it.id == note?.id }
    val remoteChanged = !state.loading && note != null && currentNote != null && currentNote.revision != note.revision

    val close: () -> Unit = {
        if (!saving && !importingImage && pendingWhiteboardImage == null) {
            if (dirty && !readOnly) showLeaveDialog = true else onClose()
        }
    }
    val save: () -> Unit = {
        // The restored baseline omits the drawing and text to keep saved state small.
        // Saving an unchanged draft must not manufacture a revision from that baseline.
        if (!saving && note != null && !dirty) {
            onClose()
        } else if (canSave) {
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
                title = {
                    Text(
                        when {
                            readOnly -> if (whiteboard != null) "回收站白板" else "回收站便签"
                            whiteboard != null -> title.ifBlank { if (note == null) "新白板" else "编辑白板" }
                            note == null -> "新便签"
                            else -> "编辑便签"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
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
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            TextButton(onClick = { showDeleteDialog = true }, modifier = Modifier.align(Alignment.CenterStart)) {
                                Text("彻底删除", color = MaterialTheme.colorScheme.error)
                            }
                            Box(Modifier.align(Alignment.Center)) {
                                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    ModifiedTimeLabel(note)
                                    if (whiteboard != null) DropdownMenuItem(text = { Text("标题与说明") }, onClick = { menuOpen = false; showWhiteboardDetails = true })
                                }
                            }
                            TextButton(onClick = { callbacks.onRestore(note.id); onClose() }, modifier = Modifier.align(Alignment.CenterEnd)) { Text("恢复便签") }
                        }
                    } else {
                        if (showPalette) {
                            Text("卡片颜色 · ${color.displayName}", Modifier.padding(start = 20.dp, top = 12.dp), style = MaterialTheme.typography.labelMedium)
                            ColorPalette(color, onSelect = { if (!saving) color = it }, modifier = Modifier.fillMaxWidth())
                        }
                        if (!preview && whiteboard == null) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                                MarkdownAction(Icons.Outlined.FormatBold, "加粗", saving || importingImage) { content = content.insert("**", "**", "文字"); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.FormatItalic, "斜体", saving || importingImage) { content = content.insert("*", "*", "文字"); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.FormatUnderlined, "下划线", saving || importingImage) { content = content.insert("<u>", "</u>", "文字"); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.StrikethroughS, "删除线", saving || importingImage) { content = content.insert("~~", "~~", "文字"); focusRequester.requestFocus() }
                                TextButton(onClick = { content = content.insert("# ", "", "标题", block = true); focusRequester.requestFocus() }, enabled = !saving && !importingImage) { Text("H₁", style = MaterialTheme.typography.titleMedium) }
                                TextButton(onClick = { content = content.insert("## ", "", "标题", block = true); focusRequester.requestFocus() }, enabled = !saving && !importingImage) { Text("H₂", style = MaterialTheme.typography.titleMedium) }
                                MarkdownAction(Icons.AutoMirrored.Outlined.FormatListBulleted, "插入列表", saving || importingImage) { content = content.insert("- ", "", "列表项", block = true); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.FormatListNumbered, "插入有序列表", saving || importingImage) { content = content.insert("1. ", "", "列表项", block = true); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.CheckBox, "插入待办清单", saving || importingImage) { content = content.insert("- [ ] ", "", "待办事项", block = true); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.Link, "插入链接", saving || importingImage) { content = content.insert("[", "](https://)", "链接文字"); focusRequester.requestFocus() }
                                MarkdownAction(Icons.Outlined.Code, "插入代码块", saving || importingImage) { content = content.insert("```\n", "\n```", "代码", block = true); focusRequester.requestFocus() }
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                            Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                                IconToggleButton(checked = showPalette, onCheckedChange = { showPalette = it }, enabled = !saving) { Icon(Icons.Outlined.Palette, "选择卡片颜色") }
                                Box {
                                    IconButton(onClick = {
                                        imageError = null
                                        imageInsertionStart = if (preview && whiteboard == null) content.text.length else content.selection.start
                                        imageInsertionEnd = if (preview && whiteboard == null) content.text.length else content.selection.end
                                        focusManager.clearFocus(force = true)
                                        keyboardController?.hide()
                                        imageSourceMenu = true
                                    }, enabled = !saving && !importingImage && pendingWhiteboardImage == null &&
                                        (whiteboard == null || whiteboard!!.images.size < Whiteboard.MAX_IMAGES)) {
                                        Icon(Icons.Outlined.AddPhotoAlternate, "插入图片")
                                    }
                                    DropdownMenu(expanded = imageSourceMenu, onDismissRequest = { imageSourceMenu = false }) {
                                        DropdownMenuItem(text = { Text("本地图片") },
                                            enabled = retainedAttachments.size < NoteAttachment.MAX_PER_NOTE,
                                            onClick = {
                                                imageSourceMenu = false
                                                importingImage = true
                                                imagePicker.launch(arrayOf("image/*"))
                                            })
                                        DropdownMenuItem(text = { Text("远程图片") },
                                            enabled = whiteboard == null || retainedAttachments.size < NoteAttachment.MAX_PER_NOTE,
                                            onClick = { imageSourceMenu = false; showRemoteImage = true })
                                    }
                                }
                                IconButton(onClick = { showReminder = true }, enabled = !saving) { Icon(Icons.Outlined.NotificationsNone, "设置提醒") }
                            }
                            Box(Modifier.align(Alignment.Center)) {
                                IconButton(onClick = { menuOpen = true }, enabled = !saving) { Icon(Icons.Outlined.MoreVert, "更多操作") }
                                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                    ModifiedTimeLabel(note)
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text(if (archived) "取消归档" else "归档") }, leadingIcon = { Icon(if (archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) }, onClick = { menuOpen = false; archived = !archived })
                                    if (note != null) {
                                        DropdownMenuItem(text = { Text("移到回收站") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menuOpen = false; showDeleteDialog = true })
                                    }
                                    DropdownMenuItem(text = { Text(if (whiteboard != null) "白板帮助" else "Markdown 帮助") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.HelpOutline, null) }, onClick = { menuOpen = false; showHelp = true })
                                }
                            }
                            Box(Modifier.align(Alignment.CenterEnd)) {
                                if (whiteboard != null) {
                                    TextButton(onClick = { showWhiteboardDetails = true }, enabled = !saving) { Text("标题与说明") }
                                } else {
                                    TextButton(onClick = {
                                        requestEditorFocus = preview
                                        preview = !preview
                                    }, enabled = !saving) {
                                        Icon(if (preview) Icons.Outlined.Edit else Icons.Outlined.Preview, null, Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (preview) "编辑" else "预览")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).then(
                if (whiteboard != null) Modifier.padding(horizontal = 4.dp)
                else Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp),
            ),
        ) {
            if (remoteChanged && !readOnly) {
                EditorNotice("其他设备已修改这条便签。保存时会保留两份修改。")
            }
            saveError?.let { EditorNotice(it, isError = true) }
            imageError?.let { EditorNotice(it, isError = true) }
            if (importingImage) {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("正在插入图片…", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (readOnly) EditorNotice("这条便签在回收站中，恢复后即可编辑。")
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
            if (legacyAttachments.isNotEmpty()) {
                NoteImagesRow(
                    attachments = legacyAttachments,
                    editable = !readOnly && !saving && !importingImage,
                    onOpen = { if (it.isImage) selectedImageId = it.id else openAttachment(it) },
                    onRemove = { image -> attachments = attachments.filterNot { it.id == image.id } },
                )
            }
            if (whiteboard == null) Spacer(Modifier.height(8.dp))
            if (whiteboard != null) {
                WhiteboardEditor(
                    whiteboard = whiteboard!!,
                    onWhiteboardChange = { whiteboard = it },
                    attachments = retainedAttachments,
                    imageToInsert = pendingWhiteboardImage,
                    onImageInserted = { pendingWhiteboardImage = null },
                    readOnly = readOnly || saving || importingImage,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            } else if (preview || readOnly) {
                if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(bottom = 20.dp))
                if (content.text.isBlank()) Text("还没有正文", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else MarkdownPreview(content.text, attachments = retainedAttachments, onOpenImage = { selectedImageId = it.id })
            } else {
                BasicTextField(
                    value = title,
                    onValueChange = { if (it.length <= MAX_TITLE_LENGTH) title = it },
                    enabled = !saving && !importingImage,
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
                    onValueChange = { if (it.text.length <= MAX_CONTENT_LENGTH) content = continueOrderedListAfterEnter(content, it) },
                    enabled = !saving && !importingImage,
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
            if (whiteboard == null && (content.text.length >= MAX_CONTENT_LENGTH || title.length >= MAX_TITLE_LENGTH)) {
                Text("标题最多 500 字，正文最多 100,000 字。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // Scaffold subcomposes its content; request focus only after this field's composition.
            LaunchedEffect(preview, requestEditorFocus) {
                if (preview || whiteboard != null) {
                    focusManager.clearFocus(force = true)
                    keyboardController?.hide()
                } else if (requestEditorFocus && !readOnly) {
                    requestEditorFocus = false
                    focusRequester.requestFocus()
                    keyboardController?.show()
                }
            }
        }
    }
    if (showRemoteImage && !readOnly) {
        RemoteImageDialog(
            loading = importingImage,
            error = imageError,
            onDismiss = { if (!importingImage) { showRemoteImage = false; imageError = null } },
            onConfirm = { address ->
                imageError = null
                if (whiteboard == null) {
                    try {
                        content = content.copy(selection = TextRange(imageInsertionStart, imageInsertionEnd))
                            .insertMarkdownImage(address)
                        preview = false
                        requestEditorFocus = true
                        showRemoteImage = false
                    } catch (error: IllegalArgumentException) {
                        imageError = error.message
                    }
                } else {
                    importingImage = true
                    scope.launch {
                        try {
                            val imported = withContext(Dispatchers.IO) { readRemoteImageAttachment(context, address) }
                            insertImage(imported)
                            showRemoteImage = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: IllegalArgumentException) {
                            imageError = error.message ?: "无法插入图片。"
                        } catch (_: Exception) {
                            imageError = "无法下载图片，请检查地址和网络后重试。"
                        } finally {
                            importingImage = false
                        }
                    }
                }
            },
        )
    }
    retainedAttachments.firstOrNull { it.id == selectedImageId }?.let { image ->
        NoteImageDialog(
            attachment = image,
            onOpenOriginal = { openAttachment(image) },
            onDismiss = { selectedImageId = null },
        )
    }
    if (showWhiteboardDetails && whiteboard != null) {
        WhiteboardDetailsDialog(
            title = title,
            content = content,
            attachments = retainedAttachments,
            onOpenImage = { selectedImageId = it.id },
            onTitleChange = { if (it.length <= MAX_TITLE_LENGTH) title = it },
            onContentChange = { if (it.text.length <= MAX_CONTENT_LENGTH) content = it },
            readOnly = readOnly,
            enabled = !saving && !importingImage,
            onDismiss = {
                focusManager.clearFocus(force = true)
                keyboardController?.hide()
                showWhiteboardDetails = false
            },
        )
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
            text = { Text("保存后，便签会留在本机，并按你的同步设置同步。") },
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
            title = { Text(if (readOnly) "彻底删除这条便签？" else "移到回收站？") },
            text = { Text(if (readOnly) "内容将被移除，此操作无法恢复。删除也会同步到其他设备。" else if (dirty) "未保存的修改会丢弃，原便签会移到回收站。" else "之后可以在回收站中恢复这条便签。") },
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
            title = { Text(if (whiteboard != null) "在白板上记录" else "用 Markdown 记录") },
            text = {
                if (whiteboard != null) {
                    Text("单指或触控笔写画，双指拖动画布或捏合缩放。画布向下延伸，双指向上拖动即可继续在下方创作；双指捏合缩小可查看完整画布。\n\n点击图片按钮，选择本地图片或输入远程图片地址。图片会放在当前可见画布的中央，可直接在图片上涂鸦，保存后离线也能继续编辑。\n\n画笔设置可选择细签字笔、水彩笔、马克笔，并调整颜色和八档粗细。橡皮只擦除笔画，保留图片；插图、绘画和清空均可撤销。\n\n点击「标题与说明」编辑附加文字，点击「保存」保留白板。")
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text("用底部工具栏插入格式，点击「预览」查看排版。点击左下角图片按钮，选择本地图片或输入远程图片地址，在光标处插入 Markdown 图片；可移动图片语法调整位置，删除引用即可移除图片。预览中点击本地图片可查看原图；远程图片需联网加载。")
                        Text("# 一级标题\n## 二级标题\n**加粗文字**\n*斜体文字*\n<u>下划线文字</u>\n~~删除线文字~~\n- 列表项\n- [ ] 待办事项\n- [x] 已完成\n[链接文字](https://example.com)\n> 引用\n`行内代码`", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                        Text("也支持代码块、表格和删除线。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
        )
    }
}

@Composable
private fun WhiteboardDetailsDialog(
    title: String,
    content: TextFieldValue,
    attachments: List<NoteAttachment>,
    onOpenImage: (NoteAttachment) -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (TextFieldValue) -> Unit,
    readOnly: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    var preview by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("标题与说明") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!readOnly) {
                    TextButton(onClick = { preview = !preview }, enabled = enabled, modifier = Modifier.align(Alignment.End)) {
                        Text(if (preview) "编辑说明" else "预览说明")
                    }
                }
                if (readOnly || preview) {
                    Text(title.ifBlank { "无标题白板" }, style = MaterialTheme.typography.titleLarge)
                    if (content.text.isBlank()) Text("还没有说明", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else MarkdownPreview(content.text, attachments = attachments, onOpenImage = onOpenImage)
                } else {
                    OutlinedTextField(
                        value = title,
                        onValueChange = onTitleChange,
                        enabled = enabled,
                        label = { Text("白板标题（可选）") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content,
                        onValueChange = onContentChange,
                        enabled = enabled,
                        label = { Text("说明（可选，支持 Markdown）") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        minLines = 4,
                        maxLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (content.text.length >= MAX_CONTENT_LENGTH || title.length >= MAX_TITLE_LENGTH) {
                        Text("标题最多 500 字，说明最多 100,000 字。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(if (readOnly) "关闭" else "完成") } },
    )
}

@Composable
private fun ModifiedTimeLabel(note: Note?) {
    Text(
        note?.let { "最后修改：${formatModified(it.updatedAt)}" } ?: "尚未保存",
        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    val text = listOf(title, content, color.name, isPinned.toString(), isArchived.toString(), reminderAt.toString(),
        whiteboard?.let { SyncJson.json.encodeToString(it) }.orEmpty(),
        SyncJson.json.encodeToString(attachments.map { it.copy(dataBase64 = "") }))
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

internal fun continueOrderedListAfterEnter(previous: TextFieldValue, next: TextFieldValue): TextFieldValue {
    if (next.text.length != previous.text.length + 1) return next

    val insertionIndex = next.selection.min - 1
    if (insertionIndex < 0 || insertionIndex > previous.text.length || next.text[insertionIndex] != '\n') return next
    if (previous.text.substring(0, insertionIndex) != next.text.substring(0, insertionIndex) ||
        previous.text.substring(insertionIndex) != next.text.substring(insertionIndex + 1)
    ) {
        return next
    }

    val lineStart = previous.text.lastIndexOf('\n', insertionIndex - 1).let {
        if (it < 0) 0 else it + 1
    }
    val line = previous.text.substring(lineStart, insertionIndex)
    val prefix = Regex("""^(\s*)(\d{1,9})([.)])(\s+)(.*)$""").find(line)?.groupValues ?: return next
    val indent = prefix[1]
    val number = prefix[2].toInt()
    val delimiter = prefix[3]
    val spacing = prefix[4]
    val itemText = prefix[5]

    if (itemText.isBlank()) {
        val newText = next.text.replaceRange(lineStart, insertionIndex, "")
        return TextFieldValue(newText, TextRange(lineStart))
    }

    val nextPrefix = "$indent${number + 1}$delimiter$spacing"
    val newText = next.text.replaceRange(insertionIndex + 1, insertionIndex + 1, nextPrefix)
    if (newText.length > MAX_CONTENT_LENGTH) return next
    return TextFieldValue(newText, TextRange(insertionIndex + 1 + nextPrefix.length))
}
