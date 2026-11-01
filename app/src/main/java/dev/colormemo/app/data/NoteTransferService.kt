package dev.colormemo.app.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import dev.colormemo.core.sync.SyncDocument
import dev.colormemo.core.sync.SyncJson
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class NoteImportResult(val noteCount: Int, val conflicts: Int, val unresolvedLinks: Int)
internal class NoteExportException(message: String) : IllegalStateException(message)

/** SAF grants access to the selected export folder or import file without storage permissions. */
internal class NoteTransferService(context: Context, private val repository: NoteRepository) {
    private val resolver = context.contentResolver
    private val cacheDirectory = context.cacheDir

    suspend fun exportNotes(tree: Uri) {
        val notes = repository.snapshot()
        val json = SyncJson.encodeDocument(SyncDocument(notes = notes))
        val archive = File.createTempFile("notes-export-", ".zip", cacheDirectory)
        try {
            // Finish both representations of this snapshot before creating destination files.
            archive.outputStream().use { writeMarkdownArchive(notes, it) }
            if (archive.length() > MAX_IMPORT_FILE_BYTES) {
                throw NoteExportException("Markdown ZIP 超过 64 MiB，请减少附件后重试")
            }
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss"))
            val stem = "ColorMemo-" + stamp + "-" + UUID.randomUUID().toString().take(8)
            val created = mutableListOf<Uri>()
            fun create(mimeType: String, name: String): Uri =
                requireNotNull(DocumentsContract.createDocument(resolver, parent, mimeType, name)) { "无法创建导出文件" }
                    .also(created::add)
            try {
                val jsonUri = create("application/json", stem + ".json")
                val zipUri = create("application/zip", stem + "-Markdown.zip")
                requireNotNull(resolver.openOutputStream(jsonUri, "wt")).use { it.write(json) }
                requireNotNull(resolver.openOutputStream(zipUri, "wt")).use { output ->
                    archive.inputStream().use { it.copyTo(output) }
                }
            } catch (error: Exception) {
                // Only delete files created by this attempt; earlier exports are never replaced.
                val cleaned = created.map { uri ->
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
                }.all { it }
                if (error is CancellationException) throw error
                throw NoteExportException(if (cleaned) "导出失败，请检查存储空间或重新选择文件夹"
                    else "导出未完成，所选文件夹中可能保留本次的不完整文件，请删除后重试")
            }
        } finally {
            archive.delete()
        }
    }

    suspend fun importNotes(uri: Uri): NoteImportResult {
        val file = File.createTempFile("notes-import-", ".data", cacheDirectory)
        try {
            requireNotNull(resolver.openInputStream(uri)) { "无法打开导入文件" }.use { input ->
                file.outputStream().use { input.copyImportBytes(it, MAX_IMPORT_FILE_BYTES) }
            }
            val imported = readNoteImport(file)
            val conflicts = repository.importNotes(imported.notes)
            return NoteImportResult(imported.notes.count { !it.isPurged }, conflicts, imported.unresolvedLinks)
        } finally {
            file.delete()
        }
    }
}
