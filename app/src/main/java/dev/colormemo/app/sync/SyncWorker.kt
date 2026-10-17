package dev.colormemo.app.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.colormemo.app.ColorMemoApplication
import kotlinx.coroutines.CancellationException

class SyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as ColorMemoApplication).graph
        graph.ready.await()
        val preferences = graph.settings.settings.value
        if (!preferences.enabled || !preferences.automaticSync) return Result.success()
        return try {
            if (graph.sync.sync()) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (runAttemptCount < 4) Result.retry() else Result.failure()
        }
    }
}
