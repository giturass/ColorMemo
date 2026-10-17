package dev.colormemo.app.reminder

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderPolicyTest {
    private val now = 1_800_000_000_000L
    private val upcoming = ReminderNote(
        id = "note-a",
        title = "Take the train",
        content = "Bring the tickets",
        isArchived = false,
        deletedAt = null,
        reminderAt = now + 60_000L,
    )

    @Test
    fun newFutureReminderUsesItsOriginalInstant() {
        assertEquals(
            ReminderPolicy.Decision.Schedule(now + 60_000L),
            decide(upcoming),
        )
    }

    @Test
    fun archivingDeletingOrRemovingReminderAlwaysCancelsIt() {
        listOf(
            upcoming.copy(isArchived = true),
            upcoming.copy(deletedAt = now),
            upcoming.copy(reminderAt = null),
            upcoming.copy(reminderAt = 0L),
        ).forEach { note ->
            assertEquals(ReminderPolicy.Decision.Inactive, decide(note, upcoming.reminderAt))
        }
    }

    @Test
    fun importedPastRemindersDoNotFloodNotifications() {
        val pastNote = upcoming.copy(reminderAt = now - 60_000L)
        assertEquals(ReminderPolicy.Decision.Expired, decide(pastNote))
    }

    @Test
    fun rebootRecoversPreviouslyScheduledReminderOnlyOnce() {
        val at = now - 60_000L
        val pastNote = upcoming.copy(reminderAt = at)
        assertEquals(
            ReminderPolicy.Decision.Schedule(now + ReminderPolicy.CATCH_UP_DELAY_MILLIS),
            decide(pastNote, scheduledAt = at),
        )
        assertEquals(
            ReminderPolicy.Decision.Delivered,
            decide(pastNote, scheduledAt = at, deliveredAt = at),
        )
    }

    @Test
    fun oldPendingRemindersExpireAfterOneDay() {
        val at = now - ReminderPolicy.MAX_CATCH_UP_AGE_MILLIS - 1L
        assertEquals(
            ReminderPolicy.Decision.Expired,
            decide(upcoming.copy(reminderAt = at), scheduledAt = at),
        )
    }

    @Test
    fun reminderAtTheCatchUpBoundaryIsStillRecovered() {
        val at = now - ReminderPolicy.MAX_CATCH_UP_AGE_MILLIS
        assertEquals(
            ReminderPolicy.Decision.Schedule(now + ReminderPolicy.CATCH_UP_DELAY_MILLIS),
            decide(upcoming.copy(reminderAt = at), scheduledAt = at),
        )
    }

    @Test
    fun changingReminderDoesNotInheritThePreviousPendingState() {
        val oldAt = now - 60_000L
        val newAt = now - 30_000L
        assertEquals(
            ReminderPolicy.Decision.Expired,
            decide(upcoming.copy(reminderAt = newAt), scheduledAt = oldAt),
        )
        assertEquals(
            ReminderPolicy.Decision.Schedule(requireNotNull(upcoming.reminderAt)),
            decide(upcoming, scheduledAt = oldAt, deliveredAt = oldAt),
        )
    }

    @Test
    fun settingTheClockBackCannotRepeatAnAlreadyDeliveredReminder() {
        assertEquals(
            ReminderPolicy.Decision.Delivered,
            decide(upcoming, deliveredAt = upcoming.reminderAt),
        )
    }

    @Test
    fun cancellingAnOverdueReminderPreventsLaterCatchUp() {
        val at = now - 5_000L
        val restored = upcoming.copy(reminderAt = at)
        assertEquals(ReminderPolicy.Decision.Expired, decide(restored, scheduledAt = null))
    }

    private fun decide(
        note: ReminderNote,
        scheduledAt: Long? = null,
        deliveredAt: Long? = null,
    ) = ReminderPolicy.decide(note, now, scheduledAt, deliveredAt)
}
