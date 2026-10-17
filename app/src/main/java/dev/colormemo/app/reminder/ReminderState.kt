package dev.colormemo.app.reminder

import android.content.Context
import android.content.SharedPreferences

/** Device-local delivery history deliberately does not travel through note synchronization. */
internal class ReminderState(context: Context) {
    private val preferences = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)

    fun scheduledAt(noteId: String): Long? = preferences.readTime(SCHEDULED + noteId)
    fun deliveredAt(noteId: String): Long? = preferences.readTime(DELIVERED + noteId)

    fun knownNoteIds(): Set<String> = preferences.all.keys.mapNotNull { key ->
        when {
            key.startsWith(SCHEDULED) -> key.removePrefix(SCHEDULED)
            key.startsWith(DELIVERED) -> key.removePrefix(DELIVERED)
            else -> null
        }
    }.toSet()

    fun rememberScheduled(noteId: String, at: Long) {
        if (scheduledAt(noteId) == at) return
        save(preferences.edit().putLong(SCHEDULED + noteId, at))
    }

    fun forgetScheduled(noteId: String) {
        if (scheduledAt(noteId) == null) return
        save(preferences.edit().remove(SCHEDULED + noteId))
    }

    fun markDelivered(noteId: String, at: Long) {
        save(preferences.edit().putLong(DELIVERED + noteId, at).remove(SCHEDULED + noteId))
    }

    fun restoreAfterFailedDelivery(noteId: String, at: Long, previousDelivery: Long?) {
        val editor = preferences.edit().putLong(SCHEDULED + noteId, at)
        if (previousDelivery == null) editor.remove(DELIVERED + noteId)
        else editor.putLong(DELIVERED + noteId, previousDelivery)
        save(editor)
    }

    fun forgetNote(noteId: String) {
        save(preferences.edit().remove(SCHEDULED + noteId).remove(DELIVERED + noteId))
    }

    private fun SharedPreferences.readTime(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun save(editor: SharedPreferences.Editor) {
        // commit() matters here: process death must not resurrect a delivered alarm.
        check(editor.commit()) { "Unable to persist reminder delivery state" }
    }

    companion object {
        val lock = Any()
        private const val SCHEDULED = "scheduled/"
        private const val DELIVERED = "delivered/"
    }
}
