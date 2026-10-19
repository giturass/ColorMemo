package dev.colormemo.core.sync

import dev.colormemo.core.model.Note
import java.util.ArrayDeque
import java.util.UUID

data class MergeResult(val notes: List<Note>, val conflictCount: Int)

/**
 * Revision ancestry, not device clocks, decides which edits supersede others.
 * Divergent revisions are kept as deterministic conflict copies. Tombstones are never pruned.
 */
object MergeEngine {
    fun merge(local: List<Note>, remote: List<Note>): MergeResult {
        SyncJson.validate(SyncDocument(notes = local))
        SyncJson.validate(SyncDocument(notes = remote))
        val originalIds = (local + remote).mapTo(mutableSetOf()) { it.id }
        val copies = mutableSetOf<String>()
        val result = sortedMapOf<String, Note>()
        val pending = ArrayDeque((local + remote).sortedWith(compareBy<Note> { it.id }.thenBy { it.revision }))
        var steps = 0
        while (pending.isNotEmpty()) {
            if (++steps > SyncJson.MAX_NOTES * 8) {
                throw SyncProtocolException("同步冲突过多，已停止同步并保留现有数据。")
            }
            val incoming = pending.removeFirst()
            val existing = result[incoming.id]
            if (existing == null) {
                result[incoming.id] = incoming
                continue
            }
            val resolved = resolve(existing, incoming)
            result[incoming.id] = resolved.first
            resolved.second?.let { copy ->
                if (copy.id !in originalIds) copies += copy.id
                pending.addLast(copy)
            }
        }
        val notes = result.values.map { it.copy(ancestorRevisions = it.ancestorRevisions.toSortedSet()) }
        SyncJson.validate(SyncDocument(notes = notes))
        return MergeResult(notes, copies.size)
    }

    private fun resolve(left: Note, right: Note): Pair<Note, Note?> {
        if (left.revision == right.revision) {
            if (left.copy(ancestorRevisions = emptySet()) != right.copy(ancestorRevisions = emptySet())) {
                throw SyncProtocolException("相同笔记版本包含不同内容，已停止同步以防止丢失数据。")
            }
            return left.copy(ancestorRevisions = left.ancestorRevisions + right.ancestorRevisions) to null
        }
        val leftDescends = right.revision == left.parentRevision || right.revision in left.ancestorRevisions
        val rightDescends = left.revision == right.parentRevision || left.revision in right.ancestorRevisions
        if (leftDescends && rightDescends) {
            throw SyncProtocolException("笔记版本历史出现循环，已停止同步。")
        }
        if (leftDescends) return incorporate(left, right) to null
        if (rightDescends) return incorporate(right, left) to null

        val winner = maxOf(left, right, conflictOrder)
        val loser = if (winner === left) right else left
        val conflict = if (!sameUserData(winner, loser) && !loser.isPurged) conflictCopy(loser) else null
        return incorporate(winner, loser) to conflict
    }

    /** A merge also records revisions preserved in a conflict copy as incorporated history. */
    private fun incorporate(winner: Note, other: Note): Note = winner.copy(
        ancestorRevisions = (
            winner.ancestorRevisions + other.ancestorRevisions +
                listOfNotNull(winner.parentRevision, other.parentRevision, other.revision)
            ) - winner.revision,
    )

    private fun sameUserData(left: Note, right: Note): Boolean =
        left.title == right.title && left.content == right.content && left.color == right.color &&
            left.isPinned == right.isPinned && left.isArchived == right.isArchived &&
            (left.deletedAt != null) == (right.deletedAt != null) &&
            left.isPurged == right.isPurged && left.reminderAt == right.reminderAt &&
            left.whiteboard == right.whiteboard && left.attachments == right.attachments

    private fun conflictCopy(note: Note): Note {
        val copyId = stableUuid("colormemo-conflict-v1:${note.id}:${note.revision}")
        return note.copy(
            id = copyId,
            revision = stableUuid("colormemo-conflict-revision-v1:${note.id}:${note.revision}"),
            parentRevision = null,
            ancestorRevisions = emptySet(),
            conflictOf = note.conflictOf ?: note.id,
            conflictRevision = note.revision,
            reminderAt = null,
        )
    }

    private fun stableUuid(value: String): String = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()

    private val conflictOrder = compareBy<Note> { it.isPurged }
        .thenBy { it.deletedAt != null }
        .thenBy { it.updatedAt }
        .thenBy { it.revision }
}
