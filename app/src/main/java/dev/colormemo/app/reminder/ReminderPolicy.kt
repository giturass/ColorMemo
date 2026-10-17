package dev.colormemo.app.reminder

/** Pure decisions shared by scheduling, reboot recovery and receiver validation. */
internal object ReminderPolicy {
    const val MAX_CATCH_UP_AGE_MILLIS = 24L * 60 * 60 * 1_000
    const val CATCH_UP_DELAY_MILLIS = 1_000L

    sealed interface Decision {
        data class Schedule(val triggerAtMillis: Long) : Decision
        data object Inactive : Decision
        data object Delivered : Decision
        data object Expired : Decision
    }

    fun decide(
        note: ReminderNote,
        nowMillis: Long,
        scheduledAt: Long?,
        deliveredAt: Long?,
    ): Decision {
        val at = note.reminderAt
        if (note.isArchived || note.deletedAt != null || at == null || at <= 0) {
            return Decision.Inactive
        }
        if (deliveredAt == at) return Decision.Delivered
        if (at > nowMillis) return Decision.Schedule(at)
        // Importing an old note must not generate a new notification. Only an alarm
        // this installation previously scheduled is eligible for bounded catch-up.
        if (scheduledAt == at && nowMillis - at <= MAX_CATCH_UP_AGE_MILLIS) {
            return Decision.Schedule(nowMillis + CATCH_UP_DELAY_MILLIS)
        }
        return Decision.Expired
    }
}
