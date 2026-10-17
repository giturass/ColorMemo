package dev.colormemo.app.ui

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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.colormemo.app.ui.theme.containerColor
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
        MemoSection.entries.forEach { section ->
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
            label = { Text("同步与设置") },
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
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pinnedOnly by rememberSaveable { mutableStateOf(false) }
    var compactGrid by rememberSaveable { mutableStateOf(true) }
    var colorFilter by rememberSaveable { mutableStateOf<NoteColor?>(null) }
    var colorMenu by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Note?>(null) }
    val visible = remember(state.notes, section, query, pinnedOnly, colorFilter) {
        state.notes.filter { note ->
            note.inSection(section) && (!pinnedOnly || note.isPinned) &&
                (colorFilter == null || note.color == colorFilter) &&
                (query.isBlank() || note.title.contains(query, ignoreCase = true) || note.content.contains(query, ignoreCase = true))
        }.sortedWith(
            if (section == MemoSection.REMINDERS) compareBy<Note> { it.reminderAt }.thenByDescending { it.updatedAt }
            else compareByDescending<Note> { it.isPinned }.thenByDescending { it.updatedAt },
        )
    }
    val pinned = visible.filter(Note::isPinned)
    val others = visible.filterNot(Note::isPinned)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MemoBrandMark(size = 32.dp)
                        Spacer(Modifier.width(7.dp))
                        Text("彩笺", fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = { IconButton(onClick = onOpenDrawer) { Icon(Icons.Outlined.Menu, "打开导航") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            if (section != MemoSection.TRASH) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenNote(null) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("写一条", fontWeight = FontWeight.SemiBold) },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (section == MemoSection.NOTES) "灵感，随手记。" else section.label,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    val sectionDescription = when (section) {
                        MemoSection.NOTES -> null
                        MemoSection.REMINDERS -> "把重要的事，交给一个准时的提醒"
                        MemoSection.ARCHIVE -> "收好记录，给眼前留白"
                        MemoSection.TRASH -> "移除的便笺会保留，直到你彻底删除"
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
                IconButton(onClick = { compactGrid = !compactGrid }) {
                    Icon(
                        if (compactGrid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView,
                        if (compactGrid) "切换为列表" else "切换为卡片网格",
                    )
                }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                placeholder = { Text("搜索${section.label}…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Clear, "清除搜索") }
                },
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = Color.Transparent,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                ),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !pinnedOnly && colorFilter == null, onClick = { pinnedOnly = false; colorFilter = null }, label = { Text("全部") })
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
                Text("${visible.size} 条", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    verticalItemSpacing = 12.dp,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (pinned.isNotEmpty() && section != MemoSection.REMINDERS) {
                        item(key = "pinned-header", span = StaggeredGridItemSpan.FullLine) { NoteGroupLabel("置顶") }
                        items(pinned, key = { "note:${it.id}" }) { note ->
                            MemoCard(note, onOpenNote, callbacks, onDeleteForever = { deleteTarget = it })
                        }
                    }
                    if (others.isNotEmpty() || section == MemoSection.REMINDERS) {
                        if (pinned.isNotEmpty() && section != MemoSection.REMINDERS) {
                            item(key = "others-header", span = StaggeredGridItemSpan.FullLine) { NoteGroupLabel("其他便笺") }
                        }
                        items(if (section == MemoSection.REMINDERS) visible else others, key = { "note:${it.id}" }) { note ->
                            MemoCard(note, onOpenNote, callbacks, onDeleteForever = { deleteTarget = it })
                        }
                    }
                }
            }
        }
    }
    deleteTarget?.let { note ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("彻底删除这条便笺？") },
            text = { Text("内容将从本机移除，并在下一次同步时从其他设备移除。此操作无法恢复。") },
            confirmButton = {
                TextButton(onClick = { callbacks.onDeleteForever(note.id); deleteTarget = null }) {
                    Text("彻底删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
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
private fun MemoCard(
    note: Note,
    onOpen: (Note?) -> Unit,
    callbacks: MemoCallbacks,
    onDeleteForever: (Note) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val summary = remember(note.content) { markdownSummary(note.content) }
    Card(
        onClick = { onOpen(note) },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = note.color.containerColor()),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            if (note.title.isNotBlank()) {
                Text(note.title, style = MaterialTheme.typography.titleMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (summary.isNotEmpty()) Spacer(Modifier.height(8.dp))
            }
            if (summary.isNotEmpty()) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, maxLines = 11, overflow = TextOverflow.Ellipsis)
            } else if (note.title.isBlank()) {
                Text("空白便笺", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (note.conflictOf != null) {
                Text("保留了另一份修改", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(formatModified(note.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (note.isPinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, "便笺操作", Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (note.deletedAt != null) {
                        DropdownMenuItem(
                            text = { Text("恢复便笺") },
                            leadingIcon = { Icon(Icons.Outlined.RestoreFromTrash, null) },
                            onClick = { menuOpen = false; callbacks.onRestore(note.id) },
                        )
                        DropdownMenuItem(
                            text = { Text("彻底删除") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) },
                            onClick = { menuOpen = false; onDeleteForever(note) },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(if (note.isPinned) "取消置顶" else "置顶") },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                            onClick = { menuOpen = false; callbacks.onSetPinned(note.id, !note.isPinned) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (note.isArchived) "取消归档" else "归档") },
                            leadingIcon = { Icon(if (note.isArchived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, null) },
                            onClick = { menuOpen = false; callbacks.onSetArchived(note.id, !note.isArchived) },
                        )
                        DropdownMenuItem(
                            text = { Text("移到回收站") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                            onClick = { menuOpen = false; callbacks.onTrash(note.id) },
                        )
                    }
                }
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
                hasFilter -> "还没有找到这条便笺"
                section == MemoSection.ARCHIVE -> "这里，留给值得收好的记录"
                section == MemoSection.TRASH -> "回收站很清爽"
                section == MemoSection.REMINDERS -> "给未来的自己捎个信"
                else -> "从一个小小的想法开始"
            },
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            when {
                hasFilter -> "试试另一个关键词，或清除筛选条件。"
                section == MemoSection.ARCHIVE -> "归档后的便笺会出现在这里，随时可以找回来。"
                section == MemoSection.TRASH -> "删除的便笺会先放在这里。"
                section == MemoSection.REMINDERS -> "在便笺中添加提醒，到时间便会通知你。"
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

private fun Note.inSection(section: MemoSection): Boolean = !isPurged && when (section) {
    MemoSection.NOTES -> deletedAt == null && !isArchived
    MemoSection.REMINDERS -> deletedAt == null && reminderAt != null
    MemoSection.ARCHIVE -> deletedAt == null && isArchived
    MemoSection.TRASH -> deletedAt != null
}

private val MemoSection.icon: ImageVector
    get() = when (this) {
        MemoSection.NOTES -> Icons.AutoMirrored.Outlined.Notes
        MemoSection.REMINDERS -> Icons.Outlined.NotificationsNone
        MemoSection.ARCHIVE -> Icons.Outlined.Archive
        MemoSection.TRASH -> Icons.Outlined.DeleteOutline
    }
