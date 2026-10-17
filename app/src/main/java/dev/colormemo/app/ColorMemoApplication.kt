package dev.colormemo.app

import android.app.Application
import dev.colormemo.app.data.NoteDatabase
import dev.colormemo.app.data.NoteRepository
import dev.colormemo.app.data.SecureSettingsStore
import dev.colormemo.app.reminder.ReminderDependencies
import dev.colormemo.app.reminder.ReminderNote
import dev.colormemo.app.reminder.ReminderScheduler
import dev.colormemo.app.reminder.ReminderStore
import dev.colormemo.app.sync.SyncCoordinator
import dev.colormemo.app.sync.SyncWorkScheduler
import dev.colormemo.core.model.Note
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class ColorMemoApplication : Application(), ReminderDependencies.Provider {
    lateinit var graph: AppGraph
        private set

    override val reminderStore: ReminderStore
        get() = graph.reminderStore

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }
}

class AppGraph(application: ColorMemoApplication) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database = NoteDatabase.open(application)
    val settings = SecureSettingsStore(application)
    val reminders = ReminderScheduler(application)
    val repository = NoteRepository(database, settings.deviceId, ::reconcileReminders)
    val sync = SyncCoordinator(repository, settings)
    val work = SyncWorkScheduler(application)
    val startupMessage = MutableStateFlow<String?>(null)

    val reminderStore = object : ReminderStore {
        override fun findNote(noteId: String): ReminderNote? = database.notes().find(noteId)?.let {
            NoteRepository.decode(it).asReminder()
        }

        override fun allNotes(): List<ReminderNote> = database.notes().getAll().map {
            NoteRepository.decode(it).asReminder()
        }
    }

    val ready = scope.async(start = CoroutineStart.LAZY) {
        try {
            settings.load()
        } catch (_: Exception) {
            startupMessage.value = "无法读取加密的同步设置，请在设置中重新填写；本地笔记不受影响"
        }
        sync.refreshStatus()
        work.configure(settings.settings.value)
    }

    fun start() {
        ready.start()
        scope.launch {
            ready.await()
            try {
                repository.notes.collect {
                    // Receiver state is reconciled with durable notes, including remote deletions.
                    reconcileReminders()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                startupMessage.value = "本地笔记或提醒加载失败，请重新打开应用"
            }
        }
    }

    private fun reconcileReminders() {
        try {
            reminders.rescheduleAll()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            startupMessage.value = "笔记已保存，但提醒暂未设定，请重新打开应用检查权限"
        }
    }
}

private fun Note.asReminder() = ReminderNote(
    id = id,
    title = title,
    content = content,
    isArchived = isArchived,
    deletedAt = deletedAt,
    reminderAt = reminderAt,
)
