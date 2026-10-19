package dev.colormemo.app.ui

import dev.colormemo.app.sync.SyncInterval
import dev.colormemo.core.model.Note
import dev.colormemo.core.model.NoteAttachment
import dev.colormemo.core.model.NoteColor
import dev.colormemo.core.model.Whiteboard

/** State supplied by the application; screen navigation and drafts stay in the UI. */
data class MemoUiState(
    val notes: List<Note> = emptyList(),
    val loading: Boolean = true,
    val syncStatus: String = "仅保存在本机",
    val syncing: Boolean = false,
    val syncSettings: SyncSettingsUi = SyncSettingsUi(),
    val notificationPermissionGranted: Boolean = true,
    val exactAlarmAllowed: Boolean = true,
    val message: String? = null,
    val requestedNoteId: String? = null,
)

/** Keep the opening snapshot so a save can preserve edits made by another device. */
data class NoteDraft(
    val baseNote: Note? = null,
    val title: String = "",
    val content: String = "",
    val color: NoteColor = NoteColor.DEFAULT,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    val reminderAt: Long? = null,
    val newNoteId: String? = null,
    val whiteboard: Whiteboard? = null,
    val attachments: List<NoteAttachment> = emptyList(),
) {
    val id: String? get() = baseNote?.id ?: newNoteId
}

enum class SyncProviderUi(val label: String) {
    WEBDAV("WebDAV"),
    S3("Amazon S3"),
    R2("Cloudflare R2"),
}

data class SyncSettingsUi(
    val enabled: Boolean = false,
    val provider: SyncProviderUi = SyncProviderUi.WEBDAV,
    val endpoint: String = "",
    val username: String = "",
    val password: String = "",
    val bucket: String = "",
    val region: String = "us-east-1",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val sessionToken: String = "",
    val pathPrefix: String = "colormemo",
    val automaticSync: Boolean = true,
    val syncIntervalMinutes: Long = SyncInterval.DEFAULT_MINUTES,
    val wifiOnly: Boolean = false,
    val encryptionKey: String = "",
) {
    override fun toString(): String = "SyncSettingsUi(enabled=$enabled, provider=$provider, " +
        "automaticSync=$automaticSync, syncIntervalMinutes=$syncIntervalMinutes, wifiOnly=$wifiOnly, " +
        "credentials=[REDACTED], encryptionKey=[REDACTED])"
}

data class MemoCallbacks(
    /** Return true only after the local transaction succeeds; false retains the editor. */
    val onSaveNote: suspend (NoteDraft) -> Boolean,
    val onSetPinned: (String, Boolean) -> Unit,
    val onSetArchived: (String, Boolean) -> Unit,
    val onTrash: (String) -> Unit,
    val onRestore: (String) -> Unit,
    val onDeleteForever: (String) -> Unit,
    val onSaveSyncSettings: (SyncSettingsUi) -> Unit,
    val onTestSync: (SyncSettingsUi) -> Unit,
    val onSync: () -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onRequestExactAlarmPermission: () -> Unit,
    val onMessageShown: () -> Unit,
    val onConsumeRequestedNote: () -> Unit,
    val onExportNotes: (() -> Unit)? = null,
    val onImportNotes: (() -> Unit)? = null,
    val onExportMarkdown: (() -> Unit)? = null,
)
