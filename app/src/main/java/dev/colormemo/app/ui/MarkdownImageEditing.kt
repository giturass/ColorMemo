package dev.colormemo.app.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.colormemo.app.data.markdownLabel
import dev.colormemo.app.data.markdownPath
import dev.colormemo.core.model.NoteAttachment

internal fun TextFieldValue.insertMarkdownImage(attachment: NoteAttachment, maxLength: Int = 100_000): TextFieldValue {
    return insertMarkdownImage(attachment.markdownPath, attachment.fileName, maxLength)
}

internal fun TextFieldValue.insertMarkdownImage(destination: String, description: String = "图片", maxLength: Int = 100_000): TextFieldValue {
    val before = text.substring(0, selection.min)
    val after = text.substring(selection.max)
    val label = text.substring(selection.min, selection.max).ifEmpty { description }
        .replace(Regex("[\\r\\n]+"), " ")
    val leading = if (before.isEmpty()) "" else "\n".repeat((2 - before.takeLastWhile { it == '\n' }.length).coerceAtLeast(0))
    val trailing = "\n".repeat((2 - after.takeWhile { it == '\n' }.length).coerceAtLeast(0))
    val escapedDestination = destination.replace("\\", "\\\\").replace("&", "\\&").replace("<", "%3C").replace(">", "%3E")
    val insertion = "$leading![${markdownLabel(label)}](<$escapedDestination>)$trailing"
    val result = before + insertion + after
    require(result.length <= maxLength) { "正文最多 100,000 字，请缩短正文后再插入图片。" }
    val cursor = before.length + insertion.length + minOf(2, after.takeWhile { it == '\n' }.length)
    return TextFieldValue(result, TextRange(cursor))
}
