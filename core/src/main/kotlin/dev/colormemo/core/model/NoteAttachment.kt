package dev.colormemo.core.model

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable

/** Complete file contents travel inside the authenticated, encrypted note snapshot. */
@Serializable
data class NoteAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Int,
    val sha256: String,
    val dataBase64: String,
) {
    fun bytes(): ByteArray = Base64.getDecoder().decode(dataBase64)

    companion object {
        const val MAX_FILE_BYTES = 5 * 1024 * 1024
        const val MAX_NOTE_BYTES = 8 * 1024 * 1024
        const val MAX_PER_NOTE = 20
        private val mimePattern = Regex("[A-Za-z0-9!#$&^_.+-]+/[A-Za-z0-9!#$&^_.+-]+")

        fun isValidMimeType(value: String): Boolean = value.length <= 127 && mimePattern.matches(value)

        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        fun create(fileName: String, mimeType: String, bytes: ByteArray): NoteAttachment {
            require(bytes.size <= MAX_FILE_BYTES) { "单个附件最多 5 MiB" }
            val safeName = fileName.map { if (it.isISOControl() || it == '/' || it == '\\') '_' else it }
                .joinToString("").trim().take(255).ifBlank { "附件" }
            return NoteAttachment(UUID.randomUUID().toString(), safeName,
                mimeType.takeIf(::isValidMimeType) ?: "application/octet-stream", bytes.size,
                digest(bytes), Base64.getEncoder().encodeToString(bytes))
        }
    }
}
