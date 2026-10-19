package dev.colormemo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.colormemo.core.model.NoteAttachment

@Composable
internal fun AttachmentDialog(
    attachments: List<NoteAttachment>,
    readOnly: Boolean,
    importing: Boolean,
    error: String?,
    onAdd: () -> Unit,
    onOpen: (NoteAttachment) -> Unit,
    onRemove: (NoteAttachment) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("附件") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("附件内容随笔记加密同步。单个最多 5 MiB，每条笔记合计最多 8 MiB、20 个。", style = MaterialTheme.typography.bodySmall)
                if (attachments.isEmpty()) Text("还没有附件", color = MaterialTheme.colorScheme.onSurfaceVariant)
                attachments.forEach { attachment ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onOpen(attachment) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Outlined.AttachFile, null, Modifier.size(18.dp))
                            Column(Modifier.weight(1f)) {
                                Text(attachment.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${(attachment.sizeBytes + 1023) / 1024} KiB", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (!readOnly) IconButton(onClick = { onRemove(attachment) }, enabled = !importing) {
                            Icon(Icons.Outlined.Close, "移除附件：${attachment.fileName}")
                        }
                    }
                }
                if (importing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在读取附件…")
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !importing) { Text("完成") } },
        dismissButton = {
            if (!readOnly) TextButton(onClick = onAdd, enabled = !importing && attachments.size < NoteAttachment.MAX_PER_NOTE) { Text("添加附件") }
        },
    )
}
