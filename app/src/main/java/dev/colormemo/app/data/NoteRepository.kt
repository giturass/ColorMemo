package dev.colormemo.app.data

import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.mcp.AiCommand
import dev.colormemo.core.mcp.AiNoteException
import dev.colormemo.core.mcp.AiNoteException.Reason
import dev.colormemo.core.mcp.AiNoteStore
import dev.colormemo.core.mcp.aiNoteJson
import dev.colormemo.core.sync.MergeEngine
import dev.colormemo.core.sync.SyncJson
import dev.colormemo.core.sync.SyncDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class NoteInputException(message: String) : IllegalArgumentException(message)

/** All writers, including sync and imports, share this lock and a Room transaction. */
class NoteRepository internal constructor(
    private val database: NoteDatabase,
    private val deviceId: String,
    private val attachmentStore: AttachmentStore,
    private val onCommitted: () -> Unit = {},
) : AiNoteStore, DraftPersistence {
    private val dao = database.notes()
    private val grants = database.aiGrants()
    private val writeMutex = Mutex()

    override suspend fun loadDraft(): EditorSession? = withContext(Dispatchers.IO) {
        database.drafts().load()?.let { row ->
            val json = GZIPInputStream(ByteArrayInputStream(row.document)).bufferedReader(Charsets.UTF_8).use { it.readText() }
            SyncJson.json.decodeFromString<EditorSession>(json).mapAttachments(attachmentStore::hydrate)
        }
    }

    override suspend fun writeDraft(session: EditorSession) = withContext(Dispatchers.IO) {
        val metadata = session.mapAttachments {
            attachmentStore.persist(it)
            it.copy(dataBase64 = "")
        }
        val bytes = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).bufferedWriter(Charsets.UTF_8).use { it.write(SyncJson.json.encodeToString(metadata)) }
        }.toByteArray()
        database.drafts().upsert(EditorDraftEntity(session.id, bytes))
    }

    override suspend fun discardDraft(id: String) = withContext(Dispatchers.IO) { database.drafts().delete(id) }

    override suspend fun commitDraft(draft: NoteDraft, sessionId: String) {
        save(draft, sessionId)
    }

    val aiVisibleIds: Flow<Set<String>> = grants.observeIds().map { it.toSet() }.flowOn(Dispatchers.IO)

    /** User-only API; a visibility change is durable immediately and never part of a stale draft. */
    suspend fun setAiVisible(id: String, visible: Boolean) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            database.runInTransaction {
                val current = dao.find(id)?.let(::decodeMetadata)
                checkInput(current != null && !current.isPurged, "笔记不存在或已永久删除")
                if (visible) grants.grant(AiNoteGrant(id)) else grants.revoke(id)
            }
        }
    }

    val notes: Flow<List<Note>> = dao.observeAll().map { rows -> rows.map(::decode) }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): List<Note> = withContext(Dispatchers.IO) { dao.getAll().map(::decode) }

    suspend fun save(draft: NoteDraft, completingDraftId: String? = null): Note = withContext(Dispatchers.IO) {
        checkInput(draft.title.length <= 500, "标题最多 500 个字符")
        checkInput(draft.content.length <= 100_000, "单条笔记最多 100,000 个字符")
        checkInput(draft.title.isNotBlank() || draft.content.isNotBlank() || draft.attachments.isNotEmpty() ||
            draft.whiteboard?.hasContent == true || (draft.whiteboard != null && draft.baseNote != null),
            "先写下或画下一点内容再保存")
        val now = System.currentTimeMillis()
        // An unchanged reminder may already be in the past. A newly chosen one must be future.
        checkInput(draft.reminderAt == null || draft.reminderAt == draft.baseNote?.reminderAt || draft.reminderAt > now,
            "请选择将来的提醒时间")
        writeMutex.withLock {
            val base = draft.baseNote ?: Note(
                id = draft.newNoteId ?: UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now,
                deviceId = deviceId,
            )
            val candidate = base.copy(
                title = draft.title.trim(),
                content = draft.content,
                color = draft.color,
                isPinned = draft.isPinned,
                isArchived = draft.isArchived,
                reminderAt = draft.reminderAt,
                whiteboard = draft.whiteboard,
                attachments = draft.attachments,
            )
            val unchanged = draft.baseNote != null && candidate == base
            val changed = if (unchanged) base else candidate.edited(deviceId, now)
            database.runInTransaction {
                if (!unchanged) {
                    val isNew = dao.find(changed.id) == null
                    mergeInTransaction(listOf(changed))
                    if (isNew && draft.newAiVisible) grants.grant(AiNoteGrant(changed.id))
                }
                completingDraftId?.let { database.drafts().delete(it) }
            }
            onCommitted()
            changed
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = mutate(id) { it.copy(isPinned = pinned) }

    suspend fun setArchived(id: String, archived: Boolean) = mutate(id) { it.copy(isArchived = archived) }

    suspend fun trash(id: String) = mutate(id, preserveUpdatedAt = true) {
        if (it.deletedAt == null) it.copy(deletedAt = System.currentTimeMillis()) else it
    }

    suspend fun restore(id: String) = mutate(id, preserveUpdatedAt = true) {
        require(!it.isPurged) { "已永久删除的笔记不能恢复" }
        it.copy(deletedAt = null)
    }

    suspend fun purge(id: String) = mutate(id) {
        require(it.deletedAt != null) { "请先将笔记移到回收站" }
        it.copy(
            title = "",
            content = "",
            whiteboard = null,
            attachments = emptyList(),
            color = NoteColor.DEFAULT,
            isPinned = false,
            isArchived = false,
            reminderAt = null,
            isPurged = true,
        )
    }

    /** Merge against the latest local rows, not the snapshot taken before an HTTP request. */
    suspend fun merge(incoming: List<Note>): Int = withContext(Dispatchers.IO) {
        writeMutex.withLock { mergeInTransaction(incoming).also { onCommitted() } }
    }

    private suspend fun mutate(
        id: String,
        preserveUpdatedAt: Boolean = false,
        transform: (Note) -> Note,
    ) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            database.runInTransaction {
                val current = dao.find(id)?.let(::decode) ?: return@runInTransaction
                val changed = transform(current)
                if (changed != current) {
                    dao.upsert(listOf(encode(changed.edited(deviceId, preserveUpdatedAt = preserveUpdatedAt))))
                    if (changed.isPurged) grants.revoke(id)
                }
            }
            onCommitted()
        }
    }

    private fun mergeInTransaction(incoming: List<Note>): Int {
        var conflicts = 0
        database.runInTransaction {
            val current = dao.getAll().map(::decode)
            val result = MergeEngine.merge(current, incoming)
            // Reject an unsyncable attachment addition before changing any rows.
            if (result.notes.any { it.attachments.isNotEmpty() }) SyncJson.encode(SyncDocument(notes = result.notes))
            val byId = current.associateBy(Note::id)
            val changed = result.notes.filter { byId[it.id] != it }
            if (changed.isNotEmpty()) dao.upsert(changed.map(::encode))
            result.notes.filter { it.isPurged }.forEach { grants.revoke(it.id) }
            conflicts = result.conflictCount
        }
        return conflicts
    }

    private fun decode(row: NoteEntity): Note = decodeMetadata(row).let { note ->
        note.copy(attachments = note.attachments.map(attachmentStore::hydrate))
    }

    /** Reads, grants, user edits, AI writes, sync and imports share one authorization boundary. */
    override suspend fun execute(command: AiCommand): JsonElement = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            var changed = false
            val result = database.runInTransaction(java.util.concurrent.Callable<JsonElement> {
                ensureActive()
                if (command is AiCommand.ListNotes) {
                    val matches = grants.grantedNotes().map(::decodeMetadata).filter { note ->
                        !note.isPurged && when (command.status) {
                            "active" -> note.deletedAt == null && !note.isArchived
                            "archived" -> note.deletedAt == null && note.isArchived
                            "trash" -> note.deletedAt != null
                            "all" -> true
                            else -> false
                        } && (command.query.isEmpty() || note.title.contains(command.query, ignoreCase = true) ||
                            note.content.contains(command.query, ignoreCase = true) ||
                            note.whiteboard?.texts?.any { it.text.contains(command.query, ignoreCase = true) } == true)
                    }.sortedBy(Note::id).drop(command.offset).take(command.limit + 1)
                    return@Callable buildJsonObject {
                        put("notes", JsonArray(matches.take(command.limit).map { aiNoteJson(it) }))
                        if (matches.size > command.limit) put("nextOffset", command.offset + command.limit)
                    }
                }
                if (command is AiCommand.Create) {
                    var note = Note(title = command.title, content = command.content, color = command.color, deviceId = deviceId)
                    // Creation can never turn into an upsert of an existing, possibly hidden ID.
                    while (dao.find(note.id) != null) note = note.copy(id = UUID.randomUUID().toString())
                    validateAiChange(note)
                    ensureActive()
                    dao.upsert(listOf(encode(note)))
                    grants.grant(AiNoteGrant(note.id))
                    changed = true
                    return@Callable aiNoteJson(note, full = true)
                }
                val id = when (command) {
                    is AiCommand.Read -> command.id
                    is AiCommand.Update -> command.id
                    is AiCommand.Manage -> command.id
                    else -> error("Unexpected command")
                }
                // Check before loading content or disclosing state/revision. Missing and hidden are identical.
                if (!grants.isGranted(id)) throw AiNoteException(Reason.UNAVAILABLE)
                val row = dao.find(id) ?: throw AiNoteException(Reason.UNAVAILABLE)
                val current = decodeMetadata(row)
                if (current.isPurged) throw AiNoteException(Reason.UNAVAILABLE)
                if (command is AiCommand.Read) return@Callable aiNoteJson(current, full = true)
                val expected = when (command) {
                    is AiCommand.Update -> command.revision
                    is AiCommand.Manage -> command.revision
                    else -> error("Unexpected command")
                }
                if (expected != current.revision) throw AiNoteException(Reason.CONFLICT)
                val candidate = when (command) {
                    is AiCommand.Update -> {
                        if (current.deletedAt != null) throw AiNoteException(Reason.INVALID_STATE)
                        current.copy(title = command.title ?: current.title, content = command.content ?: current.content,
                            color = command.color ?: current.color)
                    }
                    is AiCommand.Manage -> {
                        if (current.deletedAt != null && command.action !in setOf("restore", "trash")) throw AiNoteException(Reason.INVALID_STATE)
                        when (command.action) {
                            "pin" -> current.copy(isPinned = true)
                            "unpin" -> current.copy(isPinned = false)
                            "archive" -> current.copy(isArchived = true)
                            "unarchive" -> current.copy(isArchived = false)
                            "trash" -> current.copy(deletedAt = current.deletedAt ?: System.currentTimeMillis())
                            "restore" -> current.copy(deletedAt = null)
                            else -> throw AiNoteException(Reason.INVALID_STATE)
                        }
                    }
                    else -> error("Unexpected command")
                }
                if (candidate == current) return@Callable aiNoteJson(current, full = true)
                val updated = candidate.copy(attachments = candidate.attachments.map(attachmentStore::hydrate)).edited(
                    deviceId, preserveUpdatedAt = command is AiCommand.Manage && command.action in setOf("trash", "restore"),
                )
                validateAiChange(updated)
                ensureActive()
                dao.upsert(listOf(encode(updated)))
                changed = true
                aiNoteJson(updated, full = true)
            })
            if (changed) onCommitted()
            result
        }
    }

    private fun validateAiChange(note: Note) {
        if (note.title.isBlank() && note.content.isBlank() && note.whiteboard == null && note.attachments.isEmpty()) {
            throw AiNoteException(Reason.INVALID_STATE)
        }
        try {
            val all = dao.getAll().filter { it.id != note.id }.map(::decode) + note
            SyncJson.encode(SyncDocument(notes = all))
        } catch (_: dev.colormemo.core.sync.SyncProtocolException) {
            throw AiNoteException(Reason.LIMIT)
        }
    }

    private fun encode(note: Note): NoteEntity {
        note.attachments.forEach(attachmentStore::persist)
        val metadata = note.copy(attachments = note.attachments.map { it.copy(dataBase64 = "") })
        return NoteEntity(note.id, SyncJson.json.encodeToString(metadata))
    }

    companion object {
        private fun checkInput(condition: Boolean, message: String) {
            if (!condition) throw NoteInputException(message)
        }

        fun decodeMetadata(row: NoteEntity): Note = SyncJson.json.decodeFromString(row.document)
    }
}
