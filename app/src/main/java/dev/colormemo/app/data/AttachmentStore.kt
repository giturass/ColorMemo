package dev.colormemo.app.data

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.colormemo.core.model.NoteAttachment
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Immutable private files keep large payloads out of SQLite CursorWindow and saved state. */
internal class AttachmentStore(private val directory: File) {
    fun persist(attachment: NoteAttachment) {
        val target = file(attachment.sha256)
        if (target.isFile) return
        check(directory.isDirectory || directory.mkdirs()) { "无法创建附件目录" }
        val bytes = attachment.bytes()
        require(NoteAttachment.digest(bytes) == attachment.sha256) { "附件内容校验失败" }
        val temporary = File.createTempFile("attachment-", ".tmp", directory)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun hydrate(metadata: NoteAttachment): NoteAttachment {
        if (metadata.dataBase64.isNotEmpty()) return metadata
        val bytes = file(metadata.sha256).readBytes()
        check(bytes.size == metadata.sizeBytes && NoteAttachment.digest(bytes) == metadata.sha256) { "本机附件损坏" }
        return metadata.copy(dataBase64 = Base64.getEncoder().encodeToString(bytes))
    }

    private fun file(hash: String): File {
        require(Regex("[a-f0-9]{64}").matches(hash)) { "附件编号无效" }
        return File(directory, hash)
    }
}

internal fun attachmentStore(context: Context) = AttachmentStore(File(context.filesDir, "attachments"))

internal fun readImageAttachment(context: Context, uri: Uri): NoteAttachment {
    val resolver = context.contentResolver
    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    } ?: "图片"
    val bytes = requireNotNull(resolver.openInputStream(uri)) { "无法读取图片" }.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= NoteAttachment.MAX_FILE_BYTES) { "单张图片最多 5 MiB" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
    // Validate the actual image, since providers need not honor the picker MIME filter.
    val mimeType = imageMimeType(bytes)
    val preview = requireNotNull(decodeNoteImage(bytes, 384)) { "图片无法解码，请选择其他图片。" }
    preview.recycle()
    return NoteAttachment.create(name, mimeType, bytes).also { attachmentStore(context).persist(it) }
}

/** Only this narrow cache directory is exposed, with a temporary read grant. */
internal fun prepareAttachmentIntent(context: Context, attachment: NoteAttachment): Intent {
    val safeName = attachment.fileName.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
        .trim(' ', '.').take(120).ifBlank { "attachment" }
    val directory = File(context.cacheDir, "opened-attachments/${attachment.sha256}")
    require(Regex("[a-f0-9]{64}").matches(attachment.sha256))
    check(directory.isDirectory || directory.mkdirs())
    val file = File(directory, safeName)
    file.writeBytes(attachment.bytes())
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
    return Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mimeType)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(attachment.fileName, uri) }
}
