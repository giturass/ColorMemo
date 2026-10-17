package dev.colormemo.app.reminder

import android.content.Context

/** Implementations read persisted data synchronously; callers must use a worker thread. */
interface ReminderStore {
    fun findNote(noteId: String): ReminderNote?
    fun allNotes(): List<ReminderNote>
}

object ReminderDependencies {
    /** Implement this on the manifest Application so receivers work after process death. */
    interface Provider {
        val reminderStore: ReminderStore
    }

    internal fun store(context: Context): ReminderStore =
        (context.applicationContext as? Provider)?.reminderStore
            ?: error("Application must implement ReminderDependencies.Provider")
}

object ReminderContract {
    const val EXTRA_NOTE_ID = "note_id"
    internal const val EXTRA_REMINDER_AT = "reminder_at"
    internal const val ACTION_REMIND = "dev.colormemo.app.action.REMIND"
}
