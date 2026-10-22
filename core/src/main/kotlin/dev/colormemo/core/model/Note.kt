package dev.colormemo.core.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class NoteColor {
    DEFAULT, CORAL, PEACH, SAND, MINT, SAGE, FOG, STORM, DUSK, BLOSSOM, CLAY,
}

/** A revision is immutable; every user mutation must call [edited]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Note(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val content: String = "",
    val color: NoteColor = NoteColor.DEFAULT,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val deletedAt: Long? = null,
    val isPurged: Boolean = false,
    val reminderAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val revision: String = UUID.randomUUID().toString(),
    val parentRevision: String? = null,
    val deviceId: String = "local",
    val ancestorRevisions: Set<String> = emptySet(),
    val conflictOf: String? = null,
    val conflictRevision: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val whiteboard: Whiteboard? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val attachments: List<NoteAttachment> = emptyList(),
) {
    /**
     * Call after copy(...), using the revision of the note on which the edit was based.
     * Trash and restore preserve [updatedAt] so they keep the note's position in its list.
     */
    fun edited(
        deviceId: String,
        now: Long = System.currentTimeMillis(),
        newRevision: String = UUID.randomUUID().toString(),
        preserveUpdatedAt: Boolean = false,
    ): Note {
        require(newRevision.isNotBlank() && newRevision != revision && newRevision !in ancestorRevisions)
        require(deviceId.isNotBlank())
        val editedAt = if (preserveUpdatedAt) updatedAt else maxOf(now, updatedAt + 1)
        return copy(
            title = if (isPurged) "" else title,
            content = if (isPurged) "" else content,
            whiteboard = if (isPurged) null else whiteboard,
            attachments = if (isPurged) emptyList() else attachments,
            deletedAt = if (isPurged) deletedAt ?: editedAt else deletedAt,
            reminderAt = if (isPurged || deletedAt != null) null else reminderAt,
            updatedAt = editedAt,
            revision = newRevision,
            parentRevision = revision,
            ancestorRevisions = (ancestorRevisions + listOfNotNull(parentRevision, revision)).toSortedSet(),
            deviceId = deviceId,
        )
    }
}
