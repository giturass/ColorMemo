package dev.colormemo.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.colormemo.app.data.decodeNoteImage
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.Whiteboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Render the document, independent of the camera, guides, selection and instrument overlays. */
internal suspend fun saveWhiteboardPng(context: Context, uri: Uri, board: Whiteboard, attachments: List<NoteAttachment>) =
    withContext(Dispatchers.IO) {
        val bitmap = Bitmap.createBitmap(Whiteboard.CANVAS_WIDTH.toInt(), Whiteboard.CANVAS_HEIGHT.toInt(), Bitmap.Config.ARGB_8888)
        try {
            val scope = CanvasDrawScope()
            val canvas = Canvas(bitmap.asImageBitmap())
            fun render(part: Whiteboard, images: Map<String, androidx.compose.ui.graphics.ImageBitmap> = emptyMap()) {
                scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                    drawWhiteboard(part, imageBitmaps = images)
                }
            }
            scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(bitmap.width.toFloat(), bitmap.height.toFloat())) {
                drawRect(Color.White)
            }
            // Decode one image at a time to keep memory bounded on older devices.
            board.images.forEach { image ->
                ensureActive()
                val attachment = requireNotNull(attachments.firstOrNull { it.id == image.attachmentId }) { "白板图片缺失，无法保存。" }
                val decoded = requireNotNull(decodeNoteImage(attachment.bytes(), 2048)) { "白板图片无法解码，未完成保存。" }
                try {
                    render(Whiteboard(images = listOf(image)), mapOf(image.attachmentId to decoded.asImageBitmap()))
                } finally { decoded.recycle() }
            }
            ensureActive()
            render(board.copy(images = emptyList()))
            requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "无法打开保存位置，请重新选择。" }.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "图片保存失败，请重试。" }
            }
        } finally { bitmap.recycle() }
    }
