package dev.colormemo.core.mcp

import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.Whiteboard
import kotlinx.serialization.json.*

/** Implementations must authorize and mutate atomically against current, durable grants. */
fun interface AiNoteStore {
    suspend fun execute(command: AiCommand): JsonElement
}

sealed interface AiCommand {
    data class ListNotes(val query: String, val status: String, val offset: Int, val limit: Int) : AiCommand
    data class Read(val id: String) : AiCommand
    data class Create(val title: String, val content: String, val color: NoteColor) : AiCommand
    data class Update(val id: String, val revision: String, val title: String?, val content: String?, val color: NoteColor?) : AiCommand
    data class Manage(val id: String, val revision: String, val action: String) : AiCommand
}

/** Only these fixed, non-sensitive messages may cross the MCP boundary. */
class AiNoteException(val reason: Reason) : Exception(reason.message) {
    enum class Reason(val message: String) {
        UNAVAILABLE("Note unavailable."),
        CONFLICT("Note changed. Read it again before modifying it."),
        INVALID_STATE("Operation is not allowed in the current note state."),
        LIMIT("Note or library limit exceeded."),
    }
}

internal class InvalidArguments : Exception()

internal fun parseAiCommand(name: String, args: JsonObject): AiCommand {
    fun keys(vararg names: String) {
        if (args.keys.any { it !in names }) throw InvalidArguments()
    }
    fun string(key: String, max: Int, default: String? = null): String {
        val value = args[key] ?: return default ?: throw InvalidArguments()
        return (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.length <= max } ?: throw InvalidArguments()
    }
    fun identifier(key: String) = string(key, 200).also {
        if (it.isBlank() || it.any(Char::isISOControl)) throw InvalidArguments()
    }
    fun integer(key: String, default: Int, range: IntRange): Int {
        val value = args[key] ?: return default
        return (value as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            ?.takeIf { it in range } ?: throw InvalidArguments()
    }
    fun color() = try { NoteColor.valueOf(string("color", 20, "DEFAULT")) } catch (_: IllegalArgumentException) { throw InvalidArguments() }
    return when (name) {
        "list_notes" -> {
            keys("query", "status", "offset", "limit")
            val status = string("status", 16, "active")
            if (status !in setOf("active", "archived", "trash", "all")) throw InvalidArguments()
            AiCommand.ListNotes(string("query", 500, ""), status, integer("offset", 0, 0..10_000), integer("limit", 20, 1..50))
        }
        "read_note" -> { keys("id"); AiCommand.Read(identifier("id")) }
        "create_note" -> {
            keys("title", "content", "color")
            val title = string("title", 500, "").trim()
            val content = string("content", 100_000, "")
            if (title.isBlank() && content.isBlank()) throw InvalidArguments()
            AiCommand.Create(title, content, color())
        }
        "update_note" -> {
            keys("id", "expectedRevision", "title", "content", "color")
            if (args.keys.none { it in setOf("title", "content", "color") }) throw InvalidArguments()
            AiCommand.Update(identifier("id"), identifier("expectedRevision"),
                if ("title" in args) string("title", 500).trim() else null,
                if ("content" in args) string("content", 100_000) else null,
                if ("color" in args) color() else null)
        }
        "manage_note" -> {
            keys("id", "expectedRevision", "action")
            val action = string("action", 16)
            if (action !in setOf("pin", "unpin", "archive", "unarchive", "trash", "restore")) throw InvalidArguments()
            AiCommand.Manage(identifier("id"), identifier("expectedRevision"), action)
        }
        else -> throw InvalidArguments()
    }
}

/** Explicit projection: no device identifiers, revision history, file paths or attachment bytes. */
fun aiNoteJson(note: Note, full: Boolean = false): JsonObject = buildJsonObject {
    put("id", note.id)
    put("revision", note.revision)
    put("title", note.title)
    put("color", note.color.name)
    put("pinned", note.isPinned)
    put("archived", note.isArchived)
    put("trashed", note.deletedAt != null)
    put("kind", if (note.whiteboard == null) "text" else "whiteboard")
    put("createdAt", note.createdAt)
    put("updatedAt", note.updatedAt)
    if (full) {
        put("content", note.content)
        note.whiteboard?.let { put("whiteboard", Json.encodeToJsonElement(Whiteboard.serializer(), it)) }
        putJsonArray("attachments") {
            note.attachments.forEach { attachment ->
                add(buildJsonObject {
                    put("id", attachment.id)
                    put("fileName", attachment.fileName)
                    put("mimeType", attachment.mimeType)
                    put("sizeBytes", attachment.sizeBytes)
                })
            }
        }
    }
}
