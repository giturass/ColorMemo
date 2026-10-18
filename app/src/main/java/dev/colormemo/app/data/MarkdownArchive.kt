package dev.colormemo.app.data

import dev.colormemo.core.model.Note
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Includes a portable image beside each whiteboard note, using relative Markdown links. */
internal fun writeMarkdownArchive(notes: List<Note>, output: OutputStream) {
    val visibleNotes = notes.filter { it.deletedAt == null && !it.isPurged }
        .sortedWith(compareBy({ it.createdAt }, { it.id }))
    ZipOutputStream(output).use { zip ->
        visibleNotes.forEachIndexed { index, note ->
            val title = note.title.ifBlank { if (note.whiteboard == null) "未命名笔记" else "未命名白板" }
            val safeName = title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                .trim(' ', '.').take(64).ifBlank { "note" }
            val basename = "${(index + 1).toString().padStart(4, '0')}-$safeName"
            val folder = if (note.isArchived) "archive/" else "notes/"
            val markdown = buildString {
                if (note.title.isNotBlank()) append("# ").append(note.title.replace('\n', ' ')).append("\n\n")
                append(note.content)
                if (!endsWith("\n")) append('\n')
                if (note.whiteboard != null) append("\n![白板](<").append(basename).append(".svg>)\n")
            }
            zip.writeTextEntry("$folder$basename.md", markdown)
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
