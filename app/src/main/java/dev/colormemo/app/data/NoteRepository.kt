package dev.colormemo.app.data

import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.sync.MergeEngine
import dev.colormemo.core.sync.SyncJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

class NoteInputException(message: String) : IllegalArgumentException(message)

/** All writers, including sync and imports, share this lock and a Room transaction. */
class NoteRepository(
    private val database: NoteDatabase,
    private val deviceId: String,
    private val onCommitted: () -> Unit = {},
) {
    private val dao = database.notes()
    private val writeMutex = Mutex()

    val notes: Flow<List<Note>> = dao.observeAll().map { rows -> rows.map(::decode) }.flowOn(Dispatchers.IO)

    suspend fun snapshot(): List<Note> = withContext(Dispatchers.IO) { dao.getAll().map(::decode) }

    suspend fun save(draft: NoteDraft): Note = withContext(Dispatchers.IO) {
        checkInput(draft.title.length <= 500, "标题最多 500 个字符")
        checkInput(draft.content.length <= 100_000, "单条笔记最多 100,000 个字符")
        checkInput(draft.title.isNotBlank() || draft.content.isNotBlank() ||
            draft.whiteboard?.strokes?.isNotEmpty() == true || (draft.whiteboard != null && draft.baseNote != null),
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
            )
            if (draft.baseNote != null && candidate == base) return@withLock base
            val changed = candidate.edited(deviceId, now)
            mergeInTransaction(listOf(changed))
            onCommitted()
            changed
        }
    }

    suspend fun setPinned(id: String, pinned: Boolean) = mutate(id) { it.copy(isPinned = pinned) }

    suspend fun setArchived(id: String, archived: Boolean) = mutate(id) { it.copy(isArchived = archived) }

    suspend fun trash(id: String) = mutate(id) { it.copy(deletedAt = System.currentTimeMillis()) }

    suspend fun restore(id: String) = mutate(id) {
        require(!it.isPurged) { "已永久删除的笔记不能恢复" }
        it.copy(deletedAt = null, isArchived = false)
    }

    suspend fun purge(id: String) = mutate(id) {
        require(it.deletedAt != null) { "请先将笔记移到回收站" }
        it.copy(
            title = "",
            content = "",
            whiteboard = null,
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

    private suspend fun mutate(id: String, transform: (Note) -> Note) = withContext(Dispatchers.IO) {
        writeMutex.withLock {
            database.runInTransaction {
                val current = dao.find(id)?.let(::decode) ?: return@runInTransaction
                val changed = transform(current)
                if (changed != current) dao.upsert(listOf(encode(changed.edited(deviceId))))
            }
            onCommitted()
        }
    }

    private fun mergeInTransaction(incoming: List<Note>): Int {
        var conflicts = 0
        database.runInTransaction {
            val current = dao.getAll().map(::decode)
            val result = MergeEngine.merge(current, incoming)
            val byId = current.associateBy(Note::id)
            val changed = result.notes.filter { byId[it.id] != it }
            if (changed.isNotEmpty()) dao.upsert(changed.map(::encode))
            conflicts = result.conflictCount
        }
        return conflicts
    }

    companion object {
        private fun checkInput(condition: Boolean, message: String) {
            if (!condition) throw NoteInputException(message)
        }

        fun decode(row: NoteEntity): Note = SyncJson.json.decodeFromString(row.document)
        fun encode(note: Note): NoteEntity = NoteEntity(note.id, SyncJson.json.encodeToString(note))
    }
}
