package dev.colormemo.app.data

import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.Whiteboard
import dev.colormemo.core.model.WhiteboardImage
import kotlinx.serialization.Serializable
import java.util.UUID

/** Device-local recovery data, never included in sync, exports or AI reads. */
@Serializable
data class EditorSession(
    val id: String = UUID.randomUUID().toString(),
    val initial: NoteDraft,
    val draft: NoteDraft = initial,
    val pendingImage: WhiteboardImage? = null,
    val inlineImageIds: List<String> = emptyList(),
) {
    companion object {
        fun open(note: Note?, whiteboard: Boolean = false): EditorSession {
            val draft = NoteDraft(
                baseNote = note,
                newNoteId = if (note == null) UUID.randomUUID().toString() else null,
                title = note?.title.orEmpty(), content = note?.content.orEmpty(),
                color = note?.color ?: dev.colormemo.core.model.NoteColor.DEFAULT,
                isPinned = note?.isPinned ?: false, isArchived = note?.isArchived ?: false,
                reminderAt = note?.reminderAt,
                whiteboard = note?.whiteboard ?: if (whiteboard) Whiteboard() else null,
                attachments = note?.attachments.orEmpty(),
            )
            val references = markdownAttachmentReferences(draft.content)
            return EditorSession(initial = draft, inlineImageIds = (
                draft.attachments.filter { it.isImage && it.markdownPath in references }.map { it.id } +
                    draft.whiteboard?.images.orEmpty().map { it.attachmentId }
                ).distinct())
        }
    }
}

interface DraftPersistence {
    suspend fun loadDraft(): EditorSession?
    suspend fun writeDraft(session: EditorSession)
    suspend fun discardDraft(id: String)
    suspend fun commitDraft(draft: NoteDraft, sessionId: String)
}

internal fun EditorSession.mapAttachments(transform: (NoteAttachment) -> NoteAttachment): EditorSession {
    fun NoteDraft.mapFiles() = copy(
        attachments = attachments.map(transform),
        baseNote = baseNote?.let { it.copy(attachments = it.attachments.map(transform)) },
    )
    return copy(initial = initial.mapFiles(), draft = draft.mapFiles())
}
