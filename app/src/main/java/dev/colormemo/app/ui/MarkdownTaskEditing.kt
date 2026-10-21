package dev.colormemo.app.ui

import androidx.compose.ui.text.input.TextFieldValue
import org.commonmark.node.CustomBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text

internal data class MarkdownTaskMarker(val offset: Int, val checked: Boolean)

internal class MarkdownTaskItem(val marker: MarkdownTaskMarker) : CustomBlock()

/** Changes one marker character, preserving Markdown formatting and the editor selection. */
internal fun TextFieldValue.toggleMarkdownTask(marker: MarkdownTaskMarker): TextFieldValue {
    val offset = marker.offset
    if (offset <= 0 || offset >= text.lastIndex || text[offset - 1] != '[' || text[offset + 1] != ']') return this
    val value = text[offset]
    if (value !in " xX" || (value != ' ') != marker.checked) return this
    return copy(text = text.replaceRange(offset, offset + 1, if (marker.checked) " " else "x"), composition = null)
}

/** Only parsed list paragraphs with a literal source marker become interactive tasks. */
internal fun replaceMarkdownTaskItems(document: Node, markdown: String): Node {
    val lineStarts = mutableListOf(0)
    var index = 0
    while (index < markdown.length) {
        when (markdown[index]) {
            '\r' -> {
                if (markdown.getOrNull(index + 1) == '\n') index++
                lineStarts += index + 1
            }
            '\n' -> lineStarts += index + 1
        }
        index++
    }
    fun replaceChildren(parent: Node) {
        var child = parent.firstChild
        while (child != null) {
            val next = child.next
            val paragraph = (child as? ListItem)?.firstChild as? Paragraph
            val text = paragraph?.firstChild as? Text
            val span = paragraph?.sourceSpans?.firstOrNull()
            val start = span?.let { lineStarts.getOrNull(it.lineIndex)?.plus(it.columnIndex) }
            val literal = text?.literal.orEmpty()
            if (start != null && literal.length >= 3 && literal[0] == '[' && literal[1] in " xX" && literal[2] == ']' &&
                markdown.regionMatches(start, literal, 0, 3) &&
                (markdown.getOrNull(start + 3)?.let { it == ' ' || it == '\t' || it == '\r' || it == '\n' } != false)
            ) {
                val task = MarkdownTaskItem(MarkdownTaskMarker(start + 1, literal[1] != ' '))
                text!!.literal = literal.drop(3).trimStart(' ', '\t')
                if (text.literal.isEmpty()) text.unlink()
                child.insertBefore(task)
                while (child.firstChild != null) task.appendChild(child.firstChild)
                child.unlink()
                replaceChildren(task)
            } else {
                replaceChildren(child)
            }
            child = next
        }
    }
    replaceChildren(document)
    return document
}
