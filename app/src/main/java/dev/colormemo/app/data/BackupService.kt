package dev.colormemo.app.data

import android.content.Context
import android.net.Uri
import dev.colormemo.core.sync.SyncDocument
import dev.colormemo.core.sync.SyncJson
import java.io.ByteArrayOutputStream

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
        val notes = repository.snapshot()
        val stream = requireNotNull(resolver.openOutputStream(uri, "wt")) { "无法打开导出位置" }
        writeMarkdownArchive(notes, stream)
    }
}
