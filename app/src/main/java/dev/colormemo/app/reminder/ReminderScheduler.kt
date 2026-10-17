package dev.colormemo.app.reminder

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.CancellationException

/**
 * One-shot local alarms. All methods perform disk I/O and must run off the main thread.
 * Alarm PendingIntents, rather than in-process listeners, survive process death.
 */
class ReminderScheduler(context: Context) {
    private val context = context.applicationContext
    private val alarmManager = requireNotNull(context.getSystemService(AlarmManager::class.java))
    private val state = ReminderState(this.context)

    enum class ScheduleResult { EXACT, APPROXIMATE, NONE }

    init {
        ReminderNotifications.ensureChannel(this.context)
    }

    fun schedule(note: ReminderNote): ScheduleResult = synchronized(ReminderState.lock) {
        when (val decision = ReminderPolicy.decide(
            note,
            System.currentTimeMillis(),
            state.scheduledAt(note.id),
            state.deliveredAt(note.id),
        )) {
            is ReminderPolicy.Decision.Schedule -> {
                val at = requireNotNull(note.reminderAt)
                if (state.scheduledAt(note.id) != at) {
                    ReminderNotifications.cancel(context, note.id)
                }
                state.rememberScheduled(note.id, at)
                scheduleAlarms(note.id, at, decision.triggerAtMillis)
            }
            ReminderPolicy.Decision.Delivered -> {
                cancelAlarms(note.id)
                state.forgetScheduled(note.id)
                ScheduleResult.NONE
            }
            ReminderPolicy.Decision.Inactive, ReminderPolicy.Decision.Expired -> {
                cancel(note.id)
                ScheduleResult.NONE
            }
        }
    }

    /** Cancels both pending alarms and any visible notification, retaining delivery history. */
    fun cancel(noteId: String) = synchronized(ReminderState.lock) {
        cancelAlarms(noteId)
        ReminderNotifications.cancel(context, noteId)
        state.forgetScheduled(noteId)
    }

    /** Call after startup, a synchronization merge, and return from permission settings. */
    fun rescheduleAll(): Unit = synchronized(ReminderState.lock) {
        // Keep the snapshot and reconciliation together. A concurrent note update
        // schedules its replacement after this lock, rather than losing it to an
        // earlier snapshot from a boot/settings broadcast.
        val notes = ReminderDependencies.store(context).allNotes()
        val noteIds = notes.mapTo(HashSet()) { it.id }
        var firstFailure: Exception? = null
        fun reconcile(action: () -> Unit) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed alarm must not prevent cancellation of a different note's
                // deleted/archived reminder. Still report failure to the caller.
                if (firstFailure == null) firstFailure = error
            }
        }
        (state.knownNoteIds() - noteIds).forEach { noteId ->
            reconcile {
                cancel(noteId)
                state.forgetNote(noteId)
            }
        }
        notes.forEach { note -> reconcile { schedule(note) } }
        firstFailure?.let { throw it }
    }

    internal fun deliver(noteId: String, expectedAt: Long) = synchronized(ReminderState.lock) {
        // Read under the same lock as cancellation: an already-cancelled alarm must not
        // publish a stale snapshot after an archive/delete operation finishes.
        val note = ReminderDependencies.store(context).findNote(noteId)
        if (note == null) {
            cancel(noteId)
            return@synchronized
        }
        if (note.isArchived || note.deletedAt != null || note.reminderAt == null) {
            cancel(noteId)
            return@synchronized
        }
        // A queued broadcast from an old reminder must leave its replacement intact.
        if (note.reminderAt != expectedAt) return@synchronized
        if (state.deliveredAt(noteId) == expectedAt) {
            cancelAlarms(noteId)
            state.forgetScheduled(noteId)
            return@synchronized
        }
        if (state.scheduledAt(noteId) != expectedAt) return@synchronized
        val now = System.currentTimeMillis()
        if (expectedAt > now) {
            schedule(note)
            return@synchronized
        }
        if (now - expectedAt > ReminderPolicy.MAX_CATCH_UP_AGE_MILLIS) {
            cancel(noteId)
            return@synchronized
        }
        // Preserve pending state so granting notifications and reopening the app can
        // recover this reminder within the catch-up window.
        if (!ReminderPermissions.canPostNotifications(context)) return@synchronized

        val previousDelivery = state.deliveredAt(noteId)
        state.markDelivered(noteId, expectedAt)
        try {
            ReminderNotifications.show(context, note)
        } catch (error: Exception) {
            state.restoreAfterFailedDelivery(noteId, expectedAt, previousDelivery)
            throw error
        }
        cancelAlarms(noteId)
    }

    @SuppressLint("ScheduleExactAlarm") // Availability is checked; revocation races fall back.
    private fun scheduleAlarms(noteId: String, at: Long, triggerAt: Long): ScheduleResult {
        // Keep a separate inexact alarm even when exact access is granted. Android
        // removes exact alarms and stops the process when that access is revoked.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            requireNotNull(pendingIntent(noteId, at, Lane.FALLBACK, create = true)),
        )
        if (ReminderPermissions.canScheduleExactAlarms(context)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAt,
                    requireNotNull(pendingIntent(noteId, at, Lane.EXACT, create = true)),
                )
                return ScheduleResult.EXACT
            } catch (_: SecurityException) {
                // Special access can be revoked between the permission check and call.
            }
        }
        cancelAlarm(noteId, Lane.EXACT)
        return ScheduleResult.APPROXIMATE
    }

    private fun cancelAlarms(noteId: String) {
        Lane.entries.forEach { cancelAlarm(noteId, it) }
    }

    private fun cancelAlarm(noteId: String, lane: Lane) {
        pendingIntent(noteId, 0L, lane, create = false)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    private fun pendingIntent(
        noteId: String,
        at: Long,
        lane: Lane,
        create: Boolean,
    ): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderContract.ACTION_REMIND
            // Data participates in PendingIntent identity. IDs cannot collide, unlike
            // hash-code request codes, and exact/fallback extras stay independent.
            data = Uri.Builder().scheme("colormemo").authority("reminder")
                .appendPath(noteId).appendPath(lane.name).build()
            putExtra(ReminderContract.EXTRA_NOTE_ID, noteId)
            putExtra(ReminderContract.EXTRA_REMINDER_AT, at)
        }
        val flag = if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, 0, intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    private enum class Lane { EXACT, FALLBACK }
}
