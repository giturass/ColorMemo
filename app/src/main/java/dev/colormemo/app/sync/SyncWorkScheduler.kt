package dev.colormemo.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dev.colormemo.app.data.SyncPreferences
import java.util.concurrent.TimeUnit

class SyncWorkScheduler(context: Context) {
    private val manager = WorkManager.getInstance(context)

    fun configure(settings: SyncPreferences) {
        if (!settings.enabled || !settings.automaticSync) {
            manager.cancelUniqueWork(PERIODIC)
            manager.cancelUniqueWork(AFTER_EDIT)
            return
        }
        val work = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work)
    }

    fun afterEdit(settings: SyncPreferences) {
        if (!settings.enabled || !settings.automaticSync) return
        val work = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInitialDelay(10, TimeUnit.SECONDS)
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        // Keep a follow-up if the last edit happens after a running worker's final snapshot.
        manager.enqueueUniqueWork(AFTER_EDIT, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
    }

    private fun constraints(settings: SyncPreferences) = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()

    private companion object {
        const val PERIODIC = "colormemo-periodic-sync"
        const val AFTER_EDIT = "colormemo-after-edit-sync"
    }
}
