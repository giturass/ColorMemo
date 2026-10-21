package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.Whiteboard
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

open class SyncException(
    message: String,
    val isRetryable: Boolean = false,
    cause: Throwable? = null,
) : IOException(message, cause)

class SyncProtocolException(message: String, cause: Throwable? = null) :
    SyncException(message, cause = cause)

class SyncConfigurationException(message: String) : SyncException(message)

internal fun requireSyncConfiguration(valid: Boolean, safeMessage: String) {
    if (!valid) throw SyncConfigurationException(safeMessage)
}

@Serializable
data class SyncDocument(
    val schemaVersion: Int = 1,
    val notes: List<Note> = emptyList(),
)

/** Strict decoding prevents an older client from silently removing unknown data. */
object SyncJson {
    const val MAX_DOCUMENT_BYTES: Int = 16 * 1024 * 1024
    const val MAX_NOTES: Int = 10_000
    const val MAX_CONTENT_CHARACTERS: Int = 100_000
    const val MAX_TITLE_CHARACTERS: Int = 500

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun encodeDocument(document: SyncDocument): ByteArray = encode(document).toByteArray(Charsets.UTF_8)

    fun decodeDocument(bytes: ByteArray): SyncDocument = decode(bytes)

    fun encode(document: SyncDocument): String {
        validate(document)
        val canonical = document.copy(notes = document.notes.sortedBy { it.id }.map {
            it.copy(ancestorRevisions = it.ancestorRevisions.toSortedSet())
        })
        val encoded = json.encodeToString(canonical)
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) {
            throw SyncProtocolException("笔记数据超过 16 MiB 同步上限，请先导出并拆分数据。")
        }
        return encoded
    }

    fun decode(encoded: String): SyncDocument = decode(encoded.toByteArray(Charsets.UTF_8))

    fun decode(bytes: ByteArray): SyncDocument {
        if (bytes.size > MAX_DOCUMENT_BYTES) {
            throw SyncProtocolException("远端笔记数据超过 16 MiB，未覆盖本地数据。")
        }
        val document = try {
            val encoded = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
            val root = json.parseToJsonElement(encoded) as? JsonObject
                ?: throw SyncProtocolException("远端文件不是彩笺同步文档。")
            val version = root["schemaVersion"] as? JsonPrimitive
            if (version?.isString != false || version.intOrNull != 1 || root["notes"] !is JsonArray) {
                throw SyncProtocolException("同步文件版本不受支持，未覆盖任何数据。")
            }
            for (element in root.getValue("notes") as JsonArray) {
                val note = element as? JsonObject
                    ?: throw SyncProtocolException("同步文件中的笔记格式无效。")
                if (!note.keys.containsAll(listOf("id", "revision", "deviceId", "createdAt", "updatedAt"))) {
                    throw SyncProtocolException("同步文件中的笔记缺少编号或版本信息。")
                }
            }
            json.decodeFromJsonElement(SyncDocument.serializer(), root)
        } catch (error: SyncException) {
            throw error
        } catch (error: SerializationException) {
            throw SyncProtocolException("同步文件格式不正确或包含当前版本不支持的数据。", error)
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw SyncProtocolException("同步文件不是有效的 UTF-8 文本。", error)
        } catch (error: IllegalArgumentException) {
            throw SyncProtocolException("同步文件格式不正确。", error)
        }
        validate(document)
        return document.copy(notes = document.notes.sortedBy { it.id })
    }

    fun validate(document: SyncDocument) {
        if (document.schemaVersion != 1) throw SyncProtocolException("同步文件版本不受支持。")
        if (document.notes.size > MAX_NOTES) throw SyncProtocolException("笔记数量超过 10,000 条同步上限。")
        if (document.notes.map { it.id }.toSet().size != document.notes.size) {
            throw SyncProtocolException("同步文件包含重复的笔记编号，已停止同步。")
        }
        for (note in document.notes) {
            validateIdentifier(note.id)
            validateIdentifier(note.revision)
            validateIdentifier(note.deviceId)
            listOfNotNull(note.parentRevision, note.conflictOf, note.conflictRevision).forEach(::validateIdentifier)
            if (note.parentRevision == note.revision || note.revision in note.ancestorRevisions) {
                throw SyncProtocolException("笔记版本历史无效，已停止同步。")
            }
            if (note.ancestorRevisions.size > 100_000) {
                throw SyncProtocolException("笔记版本历史超过当前协议上限。")
            }
            note.ancestorRevisions.forEach(::validateIdentifier)
            if (note.title.length > MAX_TITLE_CHARACTERS || note.content.length > MAX_CONTENT_CHARACTERS) {
                throw SyncProtocolException("单条笔记过大，未覆盖任何数据。")
            }
            validateAttachments(note.attachments)
            note.whiteboard?.let { validateWhiteboard(it, note.attachments) }
            if (note.createdAt < 0 || note.updatedAt < 0 || note.updatedAt == Long.MAX_VALUE ||
                note.deletedAt?.let { it < 0 } == true || note.reminderAt?.let { it < 0 } == true
            ) {
                throw SyncProtocolException("笔记时间数据无效。")
            }
            if (note.isPurged && (note.deletedAt == null || note.title.isNotEmpty() ||
                    note.content.isNotEmpty() || note.reminderAt != null || note.whiteboard != null || note.attachments.isNotEmpty())
            ) {
                throw SyncProtocolException("永久删除记录包含无效数据。")
            }
        }
    }

    private fun validateAttachments(attachments: List<NoteAttachment>) {
        if (attachments.size > NoteAttachment.MAX_PER_NOTE ||
            attachments.map { it.id }.toSet().size != attachments.size ||
            attachments.sumOf { it.sizeBytes.toLong() } > NoteAttachment.MAX_NOTE_BYTES
        ) throw SyncProtocolException("单条笔记的附件数量或大小超过上限。")
        attachments.forEach { attachment ->
            if (!Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(attachment.id) ||
                attachment.fileName.isBlank() || attachment.fileName.length > 255 ||
                attachment.fileName.any { it.isISOControl() || it == '/' || it == '\\' } ||
                !NoteAttachment.isValidMimeType(attachment.mimeType) ||
                attachment.sizeBytes !in 0..NoteAttachment.MAX_FILE_BYTES ||
                !Regex("[a-f0-9]{64}").matches(attachment.sha256) ||
                attachment.dataBase64.length != ((attachment.sizeBytes + 2) / 3) * 4
            ) throw SyncProtocolException("附件信息无效，未覆盖任何数据。")
            val bytes = try { attachment.bytes() } catch (_: IllegalArgumentException) {
                throw SyncProtocolException("附件编码无效，未覆盖任何数据。")
            }
            if (bytes.size != attachment.sizeBytes || NoteAttachment.digest(bytes) != attachment.sha256 ||
                java.util.Base64.getEncoder().encodeToString(bytes) != attachment.dataBase64
            ) throw SyncProtocolException("附件内容校验失败，未覆盖任何数据。")
        }
    }

    private fun validateWhiteboard(whiteboard: Whiteboard, attachments: List<NoteAttachment>) {
        val cutoutLists = whiteboard.strokes.map { it.cutouts } + whiteboard.images.map { it.cutouts } + whiteboard.texts.map { it.cutouts }
        if (cutoutLists.any { areas -> areas.size > Whiteboard.MAX_CUTOUTS_PER_LAYER || areas.any {
                !it.left.isFinite() || !it.top.isFinite() || !it.right.isFinite() || !it.bottom.isFinite() ||
                    it.left < 0f || it.top < 0f || it.right > Whiteboard.CANVAS_WIDTH || it.bottom > Whiteboard.CANVAS_HEIGHT ||
                    it.width <= 0f || it.height <= 0f
            } }
        ) throw SyncProtocolException("白板裁剪区域无效或数量超限，未覆盖任何数据。")
        if (whiteboard.images.size > Whiteboard.MAX_IMAGES ||
            whiteboard.images.map { it.id }.toSet().size != whiteboard.images.size
        ) throw SyncProtocolException("白板图片数量或编号无效，未覆盖任何数据。")
        val imageIds = attachments.filter { it.mimeType.startsWith("image/", ignoreCase = true) }.map { it.id }.toSet()
        for (image in whiteboard.images) {
            validateIdentifier(image.id)
            if (image.attachmentId !in imageIds ||
                !image.x.isFinite() || !image.y.isFinite() || !image.width.isFinite() || !image.height.isFinite() ||
                image.x < 0f || image.y < 0f || image.width <= 0f || image.height <= 0f ||
                image.x + image.width > Whiteboard.CANVAS_WIDTH || image.y + image.height > Whiteboard.CANVAS_HEIGHT
            ) throw SyncProtocolException("白板图片引用或位置无效，未覆盖任何数据。")
        }
        if (whiteboard.texts.size > Whiteboard.MAX_TEXTS ||
            whiteboard.texts.map { it.id }.toSet().size != whiteboard.texts.size
        ) throw SyncProtocolException("白板文字数量或编号无效，未覆盖任何数据。")
        for (text in whiteboard.texts) {
            validateIdentifier(text.id)
            if (!dev.colormemo.core.model.validWhiteboardTextContent(text.text) ||
                !text.x.isFinite() || !text.y.isFinite() || !text.width.isFinite() || !text.fontSize.isFinite() ||
                text.fontSize !in Whiteboard.MIN_TEXT_SIZE..Whiteboard.MAX_TEXT_SIZE ||
                text.color !in 0xFF000000L..0xFFFFFFFFL || text.x < 0f || text.y < 0f || text.width <= 0f ||
                text.x + text.width > Whiteboard.CANVAS_WIDTH || text.y + text.height > Whiteboard.CANVAS_HEIGHT
            ) throw SyncProtocolException("白板文字内容或位置无效，未覆盖任何数据。")
        }
        if (whiteboard.strokes.size > Whiteboard.MAX_STROKES ||
            whiteboard.strokes.sumOf { it.points.size.toLong() } > Whiteboard.MAX_POINTS
        ) {
            throw SyncProtocolException("单张白板超过笔画或点数上限，未覆盖任何数据。")
        }
        for (stroke in whiteboard.strokes) {
            if (stroke.color !in 0xFF000000L..0xFFFFFFFFL || !stroke.width.isFinite() ||
                stroke.width !in Whiteboard.MIN_STROKE_WIDTH..Whiteboard.MAX_STROKE_WIDTH ||
                stroke.points.isEmpty()
            ) {
                throw SyncProtocolException("白板笔画数据无效，未覆盖任何数据。")
            }
            if (stroke.points.any { point ->
                    !point.x.isFinite() || !point.y.isFinite() ||
                        point.x !in 0f..Whiteboard.CANVAS_WIDTH || point.y !in 0f..Whiteboard.CANVAS_HEIGHT
                }
            ) {
                throw SyncProtocolException("白板坐标数据无效，未覆盖任何数据。")
            }
        }
    }

    private fun validateIdentifier(value: String) {
        if (value.isBlank() || value.length > 200 || value.any { it.isISOControl() }) {
            throw SyncProtocolException("笔记编号或版本编号无效。")
        }
    }
}
