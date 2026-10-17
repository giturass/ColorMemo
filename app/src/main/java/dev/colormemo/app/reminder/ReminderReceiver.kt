package dev.colormemo.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderContract.ACTION_REMIND) return
        val noteId = intent.getStringExtra(ReminderContract.EXTRA_NOTE_ID)
            ?.takeIf(String::isNotBlank) ?: return
        val at = intent.getLongExtra(ReminderContract.EXTRA_REMINDER_AT, 0L)
        if (at <= 0) return
        ReminderReceiverWork.execute(context, goAsync()) {
            ReminderScheduler(context).deliver(noteId, at)
        }
    }
}
