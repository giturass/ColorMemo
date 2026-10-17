package dev.colormemo.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.Executors

/** Receivers only read local data and schedule/post notifications; never do network I/O here. */
internal object ReminderReceiverWork {
    private val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "colormemo-reminder").apply { isDaemon = true }
    }

    fun execute(
        context: Context,
        pendingResult: BroadcastReceiver.PendingResult,
        work: () -> Unit,
    ) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            wakeLock = context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ColorMemo:reminder")?.apply {
                    setReferenceCounted(false)
                    acquire(10_000L)
                }
            val receiverWakeLock = wakeLock
            executor.execute {
                try {
                    work()
                } catch (error: Exception) {
                    // Deserialization errors can contain a snippet of private note JSON.
                    Log.e("ColorMemoReminder", "Reminder background work failed: ${error.javaClass.simpleName}")
                } finally {
                    try {
                        if (receiverWakeLock?.isHeld == true) receiverWakeLock.release()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        } catch (error: Exception) {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } finally {
                pendingResult.finish()
            }
            Log.e("ColorMemoReminder", "Unable to start reminder background work: ${error.javaClass.simpleName}")
        }
    }
}
