package dev.colormemo.app.data

import android.content.Context
import android.net.Uri
import dev.colormemo.core.sync.SyncDocument
import dev.colormemo.core.sync.SyncJson
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** SAF lets the user pick a destination without broad storage permissions. */
class BackupService(context: Context, private val repository: NoteRepository) {
    private val resolver = context.contentResolver

    suspend fun exportJson(uri: Uri) {
        val bytes = SyncJson.encodeDocument(SyncDocument(notes = repository.snapshot()))
        requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出位置" }.use { it.write(bytes) }
    }

    suspend fun importJson(uri: Uri): Int {
        val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法打开备份文件" }.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                total += size
                require(total <= SyncJson.MAX_DOCUMENT_BYTES) { "备份超过大小限制" }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
        val document = SyncJson.decodeDocument(bytes)
        // Validation finishes before the transaction starts; importing never truncates the DB.
        return repository.merge(document.notes)
    }

    suspend fun exportMarkdown(uri: Uri) {
        val notes = repository.snapshot().filter { it.deletedAt == null && !it.isPurged }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
        val stream = requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出位置" }
        ZipOutputStream(stream).use { zip ->
            notes.forEachIndexed { index, note ->
                val title = note.title.ifBlank { "未命名笔记" }
                val safeName = title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
                    .trim(' ', '.').take(64).ifBlank { "note" }
                val folder = if (note.isArchived) "archive/" else "notes/"
                zip.putNextEntry(ZipEntry("$folder${(index + 1).toString().padStart(4, '0')}-$safeName.md"))
                val markdown = buildString {
                    if (note.title.isNotBlank()) append("# ").append(note.title.replace('\n', ' ')).append("\n\n")
                    append(note.content)
                    if (!endsWith("\n")) append('\n')
                }
                zip.write(markdown.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }
}
