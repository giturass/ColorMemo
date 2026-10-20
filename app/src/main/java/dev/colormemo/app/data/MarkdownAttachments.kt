package dev.colormemo.app.data

import dev.colormemo.core.model.NoteAttachment
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.parser.Parser

private val attachmentMarkdownParser = Parser.builder().build()

/** Stable across devices and exports; the digest also prevents ZIP name collisions. */
internal val NoteAttachment.markdownPath: String
    get() {
        val safeName = fileName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim(' ', '.').take(100).ifBlank { "attachment" }
        return "attachments/$sha256-$safeName"
    }

internal fun markdownLabel(value: String): String = buildString {
    value.forEach { char ->
        // CommonMark permits backslash escaping every ASCII punctuation character.
        if (char.code in 33..47 || char.code in 58..64 || char.code in 91..96 || char.code in 123..126) append('\\')
        append(char)
    }
}

internal fun markdownAttachmentReferences(markdown: String, imagesOnly: Boolean = false): Set<String> {
    val destinations = mutableSetOf<String>()
    attachmentMarkdownParser.parse(markdown).accept(object : AbstractVisitor() {
        override fun visit(image: Image) {
            destinations += image.destination
        }

        override fun visit(link: Link) {
            if (!imagesOnly) destinations += link.destination
            visitChildren(link)
        }
    })
    return destinations
}

internal fun retainMarkdownAttachments(
    attachments: List<NoteAttachment>,
    inlineImageIds: List<String>,
    references: Set<String>,
    whiteboardImageIds: Set<String> = emptySet(),
): List<NoteAttachment> = attachments.filter {
    it.id !in inlineImageIds || it.markdownPath in references || it.id in whiteboardImageIds
}
