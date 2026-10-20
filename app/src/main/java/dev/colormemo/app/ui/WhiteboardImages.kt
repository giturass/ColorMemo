package dev.colormemo.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.colormemo.app.data.decodeNoteImage
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.Whiteboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun rememberWhiteboardImages(
    whiteboard: Whiteboard,
    attachments: List<NoteAttachment>,
    maxDimension: Int,
): Map<String, ImageBitmap> {
    val imageIds = remember(whiteboard.images) { whiteboard.images.map { it.attachmentId }.toSet() }
    val sources = remember(imageIds, attachments) { attachments.filter { it.id in imageIds } }
    val bitmaps by produceState(emptyMap<String, ImageBitmap>(), sources, maxDimension) {
        val previous = value
        value = previous.filterKeys { id -> sources.any { it.id == id } }
        val shared = mutableMapOf<String, ImageBitmap>()
        for (attachment in sources) {
            val bitmap = previous[attachment.id] ?: shared[attachment.sha256] ?: withContext(Dispatchers.Default) {
                try {
                    decodeNoteImage(attachment.bytes(), maxDimension)?.asImageBitmap()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (bitmap != null) {
                shared[attachment.sha256] = bitmap
                value = value + (attachment.id to bitmap)
            }
        }
    }
    return bitmaps
}
