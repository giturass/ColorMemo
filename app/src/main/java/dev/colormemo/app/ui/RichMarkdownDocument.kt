package dev.colormemo.app.ui

import dev.colormemo.app.data.isImage
import dev.colormemo.app.data.markdownAttachmentReferences
import dev.colormemo.app.data.markdownPath
import dev.colormemo.app.data.remoteImageUrl
import dev.colormemo.core.model.NoteAttachment
import java.util.UUID
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Node
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

internal const val MARKDOWN_ORIGIN = "https://appassets.androidplatform.net"
internal const val MARKDOWN_PAGE = "$MARKDOWN_ORIGIN/markdown/index.html"

/** Ordinary notes keep their lightweight native preview. Parsing still decides what is math/code. */
internal fun needsRichMarkdown(markdown: String): Boolean =
    '$' in markdown || "\\(" in markdown || "\\[" in markdown ||
        Regex("(?im)^[ \\t>]*(?:[-+*] |\\d+[.)] )?(?:`{3,}|~{3,})[ \\t]*(?:mermaid|math|latex)(?:\\s|$)")
            .containsMatchIn(markdown)

internal data class RichMarkdownTask(val line: Int, val marker: MarkdownTaskMarker)
internal data class RichMarkdownImage(val source: String, val attachment: NoteAttachment?)

internal class RichMarkdownDocument(val source: String, attachments: List<NoteAttachment>) {
    val id: String = UUID.randomUUID().toString()
    val tasks: List<RichMarkdownTask>
    val images: List<RichMarkdownImage>

    init {
        val starts = listOf(0) + Regex("\\r\\n|\\r|\\n").findAll(source).map { it.range.last + 1 }.toList()
        val parser = Parser.builder()
            .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build()
        val document = replaceMarkdownTaskItems(parser.parse(source), source)
        tasks = buildList {
            fun visit(node: Node) {
                if (node is MarkdownTaskItem) {
                    val found = starts.binarySearch(node.marker.offset)
                    add(RichMarkdownTask(if (found >= 0) found else -found - 2, node.marker))
                }
                var child = node.firstChild
                while (child != null) {
                    visit(child)
                    child = child.next
                }
            }
            visit(document)
        }
        val local = attachments.filter { it.isImage }.associateBy { it.markdownPath }
        images = markdownAttachmentReferences(source, imagesOnly = true).mapNotNull { destination ->
            val attachment = local[destination]
            if (attachment != null || runCatching { remoteImageUrl(destination) }.isSuccess) {
                RichMarkdownImage(destination, attachment)
            } else null
        }
    }

    fun imageUrl(index: Int): String = "$MARKDOWN_ORIGIN/note/$id/image/$index"

    fun task(offset: Int, checked: Boolean): MarkdownTaskMarker? =
        tasks.firstOrNull { it.marker.offset == offset && it.marker.checked == checked }?.marker
}
