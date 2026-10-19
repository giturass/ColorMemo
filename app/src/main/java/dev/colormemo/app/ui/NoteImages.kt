package dev.colormemo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.colormemo.app.data.decodeNoteImage
import dev.colormemo.app.data.isImage
import dev.colormemo.core.model.NoteAttachment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun NoteImagesRow(
    attachments: List<NoteAttachment>,
    editable: Boolean,
    onOpen: (NoteAttachment) -> Unit,
    onRemove: (NoteAttachment) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(attachments, key = { it.id }) { attachment ->
            Box(Modifier.width(152.dp)) {
                Column(
                    Modifier.clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button, onClickLabel = "查看：${attachment.fileName}") { onOpen(attachment) },
                ) {
                    if (attachment.isImage) {
                        NoteImagePreview(attachment, Modifier.fillMaxWidth().height(112.dp))
                    } else {
                        // Older notes can still contain documents; retain access and removal.
                        Box(Modifier.fillMaxWidth().height(112.dp), contentAlignment = Alignment.Center) {
                            Icon(Icons.AutoMirrored.Outlined.InsertDriveFile, null, Modifier.size(32.dp))
                        }
                    }
                    Text(attachment.fileName, Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (editable) {
                    Surface(
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    ) {
                        IconButton(onClick = { onRemove(attachment) }) {
                            Icon(Icons.Outlined.Close, "移除${if (attachment.isImage) "图片" else "文件"}：${attachment.fileName}", Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

private data class ImagePreviewState(val bitmap: ImageBitmap? = null, val loading: Boolean = true)

@Composable
internal fun NoteImagePreview(attachment: NoteAttachment, modifier: Modifier = Modifier, maxDimension: Int = 384) {
    val preview by produceState(ImagePreviewState(), attachment.sha256, maxDimension) {
        value = ImagePreviewState()
        val bitmap = withContext(Dispatchers.Default) {
            try {
                decodeNoteImage(attachment.bytes(), maxDimension)?.asImageBitmap()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
        value = ImagePreviewState(bitmap, loading = false)
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val bitmap = preview.bitmap
        when {
            bitmap != null -> Image(bitmap, attachment.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            preview.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> Text("无法预览图片", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun NoteImageDialog(attachment: NoteAttachment, onOpenOriginal: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(attachment.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { NoteImagePreview(attachment, Modifier.fillMaxWidth().height(320.dp), maxDimension = 1600) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = onOpenOriginal) { Text("打开原图") } },
    )
}
