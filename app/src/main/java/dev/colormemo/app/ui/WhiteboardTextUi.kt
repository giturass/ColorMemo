package dev.colormemo.app.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardPoint
import dev.colormemo.core.model.WhiteboardText
import dev.colormemo.core.model.validWhiteboardTextContent
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

@Composable
internal fun WhiteboardTextEditor(
    existing: WhiteboardText?,
    position: WhiteboardPoint,
    initialColor: Long,
    onSave: (WhiteboardText) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(existing?.text.orEmpty()) }
    var fontSize by rememberSaveable { mutableStateOf(existing?.fontSize ?: 32f) }
    val width = remember(value, fontSize) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = fontSize; typeface = Typeface.DEFAULT }
        ceil(value.split('\n').maxOf { paint.measureText(it) } + 2f)
    }
    val height = (value.count { it == '\n' } + 1) * fontSize * WhiteboardText.LINE_HEIGHT
    val error = when {
        value.isBlank() -> null
        !validWhiteboardTextContent(value) -> "文字含有不支持的字符，请修改后再插入。"
        width > Whiteboard.CANVAS_WIDTH -> "文字过宽，请换行或调小字号。"
        height > Whiteboard.CANVAS_HEIGHT -> "文字过长，请分段插入或调小字号。"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "插入文字" else "编辑文字") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = value, onValueChange = { next ->
                    val normalized = next.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
                    if (normalized.length <= Whiteboard.MAX_TEXT_LENGTH) value = normalized
                }, label = { Text("文字内容，可换行") }, minLines = 3, maxLines = 6,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp), isError = error != null,
                    supportingText = { Text(error ?: "${value.length}/${Whiteboard.MAX_TEXT_LENGTH}") })
                Text("字号 ${fontSize.roundToInt()}", style = MaterialTheme.typography.labelLarge)
                Slider(value = fontSize, onValueChange = { fontSize = it.roundToInt().toFloat() },
                    valueRange = Whiteboard.MIN_TEXT_SIZE..Whiteboard.MAX_TEXT_SIZE,
                    modifier = Modifier.semantics { contentDescription = "白板文字字号" })
                Text("文字使用当前画笔颜色。在文字工具中，拖动文字移动，轻点文字编辑。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank() && error == null, onClick = {
                val text = WhiteboardText(existing?.id ?: UUID.randomUUID().toString(), value,
                    existing?.x ?: (position.x - width / 2f), existing?.y ?: (position.y - height / 2f), width, fontSize, initialColor,
                    cutouts = existing?.cutouts.orEmpty())
                onSave(text.movedTo(text.x, text.y))
            }) { Text(if (existing == null) "插入" else "完成") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

internal fun DrawScope.drawWhiteboardTexts(texts: List<WhiteboardText>, scale: Float, origin: Offset) {
    if (texts.isEmpty()) return
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        try {
            native.translate(origin.x, origin.y)
            native.scale(scale, scale)
            texts.forEach { text ->
                native.save()
                try {
                    text.cutouts.forEach { native.clipOutRect(it.left, it.top, it.right, it.bottom) }
                    paint.color = text.color.toInt()
                    paint.textSize = text.fontSize
                    text.text.split('\n').forEachIndexed { index, line ->
                        native.drawText(line, text.x, text.y + text.fontSize + index * text.fontSize * WhiteboardText.LINE_HEIGHT, paint)
                    }
                } finally { native.restore() }
            }
        } finally {
            native.restore()
        }
    }
}
