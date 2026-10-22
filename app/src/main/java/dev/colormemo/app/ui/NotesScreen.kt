package dev.colormemo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.colormemo.app.ui.theme.containerColor
import dev.colormemo.app.data.isImage
import dev.colormemo.app.ui.theme.displayName
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor

@Composable
internal fun MemoDrawer(
    selected: MemoSection,
    notes: List<Note>,
    onSelect: (MemoSection) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(modifier = Modifier.width(304.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MemoBrandMark(size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("彩笺", style = MaterialTheme.typography.titleLarge)
                Text("想法有颜色，记录有归处", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        MemoSection.entries.filterNot { it == MemoSection.REMINDERS }.forEach { section ->
            val count = notes.count { it.inSection(section) }
            NavigationDrawerItem(
                selected = selected == section,
                onClick = { onSelect(section) },
                label = { Text(section.label) },
                icon = { Icon(section.icon, contentDescription = null) },
                badge = { if (count > 0) Text(count.toString()) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 16.dp))
        NavigationDrawerItem(
            selected = false,
            onClick = onSettings,
            label = { Text("设置") },
            icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotesScreen(
    state: MemoUiState,
    callbacks: MemoCallbacks,
    section: MemoSection,
    onOpenDrawer: () -> Unit,
    onOpenNote: (Note?) -> Unit,
    onCreateWhiteboard: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }
    var compactGrid by rememberSaveable { mutableStateOf(true) }
    var colorFilter by rememberSaveable { mutableStateOf<NoteColor?>(null) }
    var colorMenu by remember { mutableStateOf(false) }
    var deleteTargets by remember { mutableStateOf(emptyList<Note>()) }
    var selectedIds by rememberSaveable(section) { mutableStateOf(emptyList<String>()) }
    var selectionMenu by remember { mutableStateOf(false) }
    val visible = remember(state.notes, section, query, pinnedOnly, colorFilter) {
        state.notes.filter { note ->
            note.inSection(section) && (!pinnedOnly || note.isPinned) &&
                (colorFilter == null || note.color == colorFilter) &&
                (query.isBlank() || note.title.contains(query, ignoreCase = true) || note.content.contains(query, ignoreCase = true) ||
                    note.attachments.any { it.fileName.contains(query, ignoreCase = true) })
        }.sortedWith(
            if (section == MemoSection.REMINDERS) compareBy<Note> { it.reminderAt }.thenByDescending { it.updatedAt }
            else compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt },
        )
    }
    val selectedNotes = visible.filter { it.id in selectedIds }
    val selecting = selectedNotes.isNotEmpty()
    LaunchedEffect(visible) { selectedIds = selectedIds.filter { id -> visible.any { it.id == id } } }
    BackHandler(enabled = selecting) { selectedIds = emptyList() }
    fun toggleSelection(note: Note) {
        selectedIds = if (note.id in selectedIds) selectedIds - note.id else selectedIds + note.id
    }
    val onCardClick: (Note) -> Unit = { if (selecting) toggleSelection(it) else onOpenNote(it) }
    val pinned = visible.filter(Note::isPinned)
    val others = visible.filterNot(Note::isPinned)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) {
                        Text("已选 ${selectedNotes.size} 条", style = MaterialTheme.typography.titleMedium)
                    } else if (searchActive) {
                        NotesSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "搜索${section.label}…",
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MemoBrandMark(size = 32.dp)
                            Spacer(Modifier.width(7.dp))
                            Text("彩笺", fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (selecting) selectedIds = emptyList() else onOpenDrawer() }) {
                        Icon(if (selecting) Icons.Outlined.Close else Icons.Outlined.Menu, if (selecting) "退出多选" else "打开导航")
                    }
                },
                actions = {
                    if (selecting) {
                        IconButton(onClick = { selectedIds = if (selectedNotes.size == visible.size) emptyList() else visible.map { it.id } }) {
                            Icon(Icons.Outlined.SelectAll, if (selectedNotes.size == visible.size) "取消全选" else "全选")
                        }
                        IconButton(onClick = {
                            if (section == MemoSection.TRASH) deleteTargets = selectedNotes
                            else { selectedNotes.forEach { callbacks.onTrash(it.id) }; selectedIds = emptyList() }
                        }) {
                            Icon(if (section == MemoSection.TRASH) Icons.Outlined.DeleteForever else Icons.Outlined.DeleteOutline,
                                if (section == MemoSection.TRASH) "彻底删除所选便签" else "将所选便签移到回收站")
                        }
                        Box {
                            IconButton(onClick = { selectionMenu = true }) { Icon(Icons.Outlined.MoreVert, "所选便签操作") }
                            DropdownMenu(expanded = selectionMenu, onDismissRequest = { selectionMenu = false }) {
                                if (section == MemoSection.TRASH) {
                                    DropdownMenuItem(text = { Text("恢复所选便签") }, leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) }, onClick = {
                                        selectedNotes.forEach { callbacks.onRestore(it.id) }; selectedIds = emptyList(); selectionMenu = false
                                    })
                                } else {
                                    val allPinned = selectedNotes.all { it.isPinned }
                                    val allArchived = selectedNotes.all { it.isArchived }
                                    DropdownMenuItem(text = { Text(if (allPinned) "取消置顶" else "置顶") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = {
                                        selectedNotes.forEach { callbacks.onSetPinned(it.id, !allPinned) }; selectedIds = emptyList(); selectionMenu = false
                                    })
                                    DropdownMenuItem(text = { Text(if (allArchived) "取消归档" else "归档") }, leadingIcon = { Icon(if (allArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) }, onClick = {
                                        selectedNotes.forEach { callbacks.onSetArchived(it.id, !allArchived) }; selectedIds = emptyList(); selectionMenu = false
                                    })
                                }
                            }
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (searchActive) {
                                    query = ""
                                    searchActive = false
                                } else {
                                    searchActive = true
                                }
                            },
                        ) {
                            Icon(
                                if (searchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                                if (searchActive) "关闭搜索" else "搜索",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (section != MemoSection.TRASH && !selecting) {
                ExtendedFloatingActionButton(
                    onClick = { if (section == MemoSection.WHITEBOARDS) onCreateWhiteboard() else onOpenNote(null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text(if (section == MemoSection.WHITEBOARDS) "新建白板" else "写一条", fontWeight = FontWeight.SemiBold) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (section != MemoSection.NOTES) {
                Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 4.dp)) {
                    Text(
                        section.label,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    val sectionDescription = when (section) {
                        MemoSection.NOTES -> null
                        MemoSection.WHITEBOARDS -> "随手画下灵感，留住每一笔想法"
                        MemoSection.REMINDERS -> "把重要的事，交给一个准时的提醒"
                        MemoSection.ARCHIVE -> "收好记录，给眼前留白"
                        MemoSection.TRASH -> "移除的便签会保留，直到你彻底删除"
                    }
                    sectionDescription?.let { description ->
                        Text(
                            description,
                            modifier = Modifier.padding(top = 6.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilterChip(
                        selected = !pinnedOnly && colorFilter == null,
                        onClick = { pinnedOnly = false; colorFilter = null },
                        label = { Text("全部") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Notes, null, Modifier.size(16.dp)) },
                    )
                    FilterChip(
                        selected = pinnedOnly,
                        onClick = { pinnedOnly = !pinnedOnly },
                        label = { Text("置顶") },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp)) },
                    )
                    Box {
                        FilterChip(
                            selected = colorFilter != null,
                            onClick = { colorMenu = true },
                            label = { Text(colorFilter?.displayName ?: "颜色") },
                            leadingIcon = { Icon(Icons.Outlined.Palette, null, Modifier.size(16.dp)) },
                        )
                        DropdownMenu(expanded = colorMenu, onDismissRequest = { colorMenu = false }) {
                            DropdownMenuItem(text = { Text("所有颜色") }, onClick = { colorFilter = null; colorMenu = false })
                            NoteColor.entries.forEach { color ->
                                DropdownMenuItem(
                                    text = { Text(color.displayName) },
                                    leadingIcon = { Box(Modifier.size(20.dp).background(color.containerColor(), CircleShape)) },
                                    trailingIcon = { if (colorFilter == color) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp)) },
                                    onClick = { colorFilter = color; colorMenu = false },
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = { compactGrid = !compactGrid }) {
                    Icon(
                        if (compactGrid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                        if (compactGrid) "切换为列表" else "切换为卡片网格",
                    )
                }
            }
            if (state.syncSettings.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (state.syncing) Icons.Outlined.Sync else Icons.Outlined.CloudDone,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        state.syncStatus,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = callbacks.onSync, enabled = !state.syncing, modifier = Modifier.size(40.dp)) {
                        if (state.syncing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.Sync, "立即同步", Modifier.size(18.dp))
                    }
                }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                visible.isEmpty() -> EmptyNotes(
                    section = section,
                    hasFilter = query.isNotBlank() || pinnedOnly || colorFilter != null,
                    onClearFilter = { query = ""; pinnedOnly = false; colorFilter = null },
                )
                else -> LazyVerticalStaggeredGrid(
                    columns = if (compactGrid) StaggeredGridCells.Adaptive(152.dp) else StaggeredGridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (section == MemoSection.NOTES) 176.dp else 104.dp),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (pinned.isNotEmpty() && section != MemoSection.REMINDERS) {
                        item(key = "pinned-header", span = StaggeredGridItemSpan.FullLine) { NoteGroupLabel("置顶") }
                        items(pinned, key = { "note:${it.id}" }) { note ->
                            MemoCard(note, selecting, note.id in selectedIds, onClick = { onCardClick(note) }, onLongClick = { toggleSelection(note) }, onTrash = { callbacks.onTrash(note.id) })
                        }
                    }
                    if (others.isNotEmpty() || section == MemoSection.REMINDERS) {
                        if (pinned.isNotEmpty() && section != MemoSection.REMINDERS) {
                            item(key = "others-header", span = StaggeredGridItemSpan.FullLine) { NoteGroupLabel(if (section == MemoSection.WHITEBOARDS) "其他白板" else "其他便签") }
                        }
                        items(if (section == MemoSection.REMINDERS) visible else others, key = { "note:${it.id}" }) { note ->
                            MemoCard(note, selecting, note.id in selectedIds, onClick = { onCardClick(note) }, onLongClick = { toggleSelection(note) }, onTrash = { callbacks.onTrash(note.id) })
                        }
                    }
                }
            }
        }
    }
    if (deleteTargets.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { deleteTargets = emptyList() },
            title = { Text("彻底删除这 ${deleteTargets.size} 条便签？") },
            text = { Text("内容将从本机移除，并在下一次同步时从其他设备移除。此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = { deleteTargets.forEach { callbacks.onDeleteForever(it.id) }; deleteTargets = emptyList(); selectedIds = emptyList() }) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = emptyList() }) { Text("取消") } },
        )
    }
}

@Composable
private fun NoteGroupLabel(label: String) {
    Text(
        label,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun NotesSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().height(56.dp),
        placeholder = { Text(placeholder) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Outlined.Clear, "清除搜索") }
        },
        shape = CircleShape,
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            unfocusedBorderColor = Color.Transparent,
            focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemoCard(
    note: Note,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTrash: () -> Unit,
) {
    val canSwipe = !selecting && note.deletedAt == null
    if (!canSwipe) {
        MemoCardContent(note, selecting, isSelected, onClick, onLongClick, onTrash)
        return
    }
    val latestOnTrash by rememberUpdatedState(onTrash)
    val leftDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) SwipeToDismissBoxValue.EndToStart else SwipeToDismissBoxValue.StartToEnd
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.45f })
    LaunchedEffect(swipe, leftDirection) {
        snapshotFlow { swipe.currentValue }.collect { value ->
            if (value == leftDirection) {
                // Clear the saved dismissal before removing the card. A reset animation
                // must never run on an unmeasured card when entering the trash.
                swipe.snapTo(SwipeToDismissBoxValue.Settled)
                latestOnTrash()
            }
        }
    }
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = leftDirection == SwipeToDismissBoxValue.StartToEnd,
        enableDismissFromEndToStart = leftDirection == SwipeToDismissBoxValue.EndToStart,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
                contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.DeleteOutline, "移到回收站", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        MemoCardContent(note, selecting, isSelected, onClick, onLongClick, onTrash)
    }
}

@Composable
private fun MemoCardContent(
    note: Note,
    selecting: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onTrash: () -> Unit,
) {
    val summary = remember(note.content) { markdownSummary(note.content) }
    val canSwipe = !selecting && note.deletedAt == null
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = note.color.containerColor()),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "选择便签")
            .semantics {
                selected = isSelected
                if (canSwipe) customActions = listOf(CustomAccessibilityAction("移到回收站") { onTrash(); true })
            },
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) {
            if (selecting || note.isPinned) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(checked = isSelected, onCheckedChange = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.weight(1f))
                    if (note.isPinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(16.dp))
                }
                Spacer(Modifier.height(8.dp))
            }
            note.whiteboard?.let { board ->
                WhiteboardPreview(board, Modifier.fillMaxWidth().padding(bottom = 12.dp), attachments = note.attachments)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Outlined.Draw, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("白板", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(6.dp))
            }
            note.attachments.firstOrNull { image -> image.isImage && note.whiteboard?.images.orEmpty().none { it.attachmentId == image.id } }?.let { image ->
                NoteImagePreview(image, Modifier.fillMaxWidth().height(120.dp).padding(bottom = 12.dp))
            }
            if (note.title.isNotBlank()) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (summary.isNotEmpty()) Spacer(Modifier.height(8.dp))
            }
            if (summary.isNotEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 11, overflow = TextOverflow.Ellipsis)
            } else if (note.title.isBlank() && note.whiteboard == null) {
                Text(note.attachments.firstOrNull()?.fileName ?: "空白便签", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            note.reminderAt?.let { reminder ->
                Surface(
                    modifier = Modifier.padding(top = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.45f),
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.NotificationsNone, null, Modifier.size(13.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(formatReminder(reminder), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
            }
            if (note.attachments.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val imageCount = note.attachments.count { it.isImage }
                    val fileCount = note.attachments.size - imageCount
                    Icon(if (imageCount > 0) Icons.Outlined.Image else Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(14.dp))
                    Text(listOfNotNull(
                        if (imageCount > 0) "$imageCount 张图片" else null,
                        if (fileCount > 0) "$fileCount 个文件" else null,
                    ).joinToString(" · "), style = MaterialTheme.typography.labelSmall)
                }
            }
            if (note.conflictOf != null) {
                Text("保留了另一份修改", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyNotes(section: MemoSection, hasFilter: Boolean, onClearFilter: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        MemoBrandMark(size = 112.dp)
        Spacer(Modifier.height(24.dp))
        Text(
            when {
                hasFilter -> "还没有找到这条便签"
                section == MemoSection.ARCHIVE -> "这里，留给值得收好的记录"
                section == MemoSection.TRASH -> "回收站很清爽"
                section == MemoSection.REMINDERS -> "给未来的自己捎个信"
                section == MemoSection.WHITEBOARDS -> "给灵感一块白板"
                else -> "从一个小小的想法开始"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when {
                hasFilter -> "试试另一个关键词，或清除筛选条件。"
                section == MemoSection.ARCHIVE -> "归档后的便签会出现在这里，随时可以找回来。"
                section == MemoSection.TRASH -> "删除的便签会先放在这里。"
                section == MemoSection.REMINDERS -> "在便签中添加提醒，到时间便会通知你。"
                section == MemoSection.WHITEBOARDS -> "点击「新建白板」，随手写画。\n保存后，随时回来继续。"
                else -> "随手记下灵感、清单和日常。\n选一个颜色，让记录变得好找。"
            },
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (hasFilter) TextButton(onClick = onClearFilter) { Text("清除筛选") }
        Spacer(Modifier.height(64.dp))
    }
}

internal fun Note.inSection(section: MemoSection): Boolean = !isPurged && when (section) {
    MemoSection.NOTES -> deletedAt == null && !isArchived && whiteboard == null
    MemoSection.WHITEBOARDS -> deletedAt == null && !isArchived && whiteboard != null
    MemoSection.REMINDERS -> deletedAt == null && reminderAt != null
    MemoSection.ARCHIVE -> deletedAt == null && isArchived
    MemoSection.TRASH -> deletedAt != null
}

private val MemoSection.icon: ImageVector
    get() = when (this) {
        MemoSection.NOTES -> Icons.AutoMirrored.Outlined.Notes
        MemoSection.WHITEBOARDS -> Icons.Outlined.Draw
        MemoSection.REMINDERS -> Icons.Outlined.NotificationsNone
        MemoSection.ARCHIVE -> Icons.Outlined.Archive
        MemoSection.TRASH -> Icons.Outlined.DeleteOutline
    }
