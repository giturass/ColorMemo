package dev.colormemo.app.reminder

/** A small snapshot keeps the alarm receiver independent of the database implementation. */
data class ReminderNote(
    val id: String,
    val title: String,
    val content: String,
    val isArchived: Boolean,
    val deletedAt: Long?,
    val reminderAt: Long?,
)
