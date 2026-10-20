package dev.colormemo.app.data

import dev.colormemo.core.model.Note
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Preserves inline image paths and includes portable whiteboards and legacy attachments. */
internal fun writeMarkdownArchive(notes: List<Note>, output: OutputStream) {
    val visibleNotes = notes.filter { it.deletedAt == null && !it.isPurged }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))
    ZipOutputStream(output).use { zip ->
        val writtenAttachments = mutableSetOf<String>()
        visibleNotes.forEachIndexed { index, note ->
            val title = note.title.ifBlank { if (note.whiteboard == null) "未命名笔记" else "未命名白板" }
            val safeName = title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim(' ', '.').take(64).ifBlank { "note" }
            val basename = "${(index + 1).toString().padStart(4, '0')}-$safeName"
            val folder = if (note.isArchived) "archive/" else "notes/"
            val references = markdownAttachmentReferences(note.content)
            val attachmentNames = note.attachments.mapIndexed { attachmentIndex, attachment ->
                val safeFile = attachment.fileName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                    .trim(' ', '.').take(100).ifBlank { "attachment" }
                if (attachment.markdownPath in references) attachment.markdownPath
                else "$basename-attachments/${attachmentIndex + 1}-$safeFile"
            }
            val markdown = buildString {
                if (note.title.isNotBlank()) append("# ").append(note.title.replace('\n', ' ')).append("\n\n")
                append(note.content)
                if (!endsWith("\n")) append('\n')
                if (note.whiteboard != null) append("\n![白板](<").append(basename).append(".svg>)\n")
                note.attachments.forEachIndexed attachment@ { attachmentIndex, attachment ->
                    if (attachment.markdownPath in references) return@attachment
                    val label = attachment.fileName.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
                    append('\n')
                    if (attachment.isImage) append('!')
                    append('[').append(label).append("](<").append(attachmentNames[attachmentIndex]).append(">)\n")
                }
            }
            zip.writeTextEntry("$folder$basename.md", markdown)
            note.attachments.forEachIndexed { attachmentIndex, attachment ->
                val path = folder + attachmentNames[attachmentIndex]
                if (writtenAttachments.add(path)) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(attachment.bytes())
                    zip.closeEntry()
                }
            }
            note.whiteboard?.let { whiteboard ->
                zip.writeTextEntry("$folder$basename.svg", whiteboardSvg(whiteboard))
            }
        }
    }
}

private fun ZipOutputStream.writeTextEntry(name: String, content: String) {
    putNextEntry(ZipEntry(name))
    write(content.toByteArray(Charsets.UTF_8))
    closeEntry()
}
