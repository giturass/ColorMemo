package dev.colormemo.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.colormemo.app.data.BackupService
import dev.colormemo.app.data.EditorSession
import dev.colormemo.core.model.Note
import dev.colormemo.core.sync.SyncProtocolException
import dev.colormemo.app.data.NoteInputException
import dev.colormemo.app.data.SyncPreferences
import dev.colormemo.app.reminder.ReminderPermissions
import dev.colormemo.app.sync.SyncCoordinator
import dev.colormemo.app.ui.MemoUiState
import dev.colormemo.app.ui.NoteDraft
import dev.colormemo.app.ui.SyncSettingsUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MemoViewModel(application: Application) : AndroidViewModel(application) {
    private val graph = (application as ColorMemoApplication).graph
    private val mutableState = MutableStateFlow(MemoUiState())
    val state = mutableState.asStateFlow()
    private val backups = BackupService(application, graph.repository)

    init {
        viewModelScope.launch {
            graph.drafts.state.collect { editor -> mutableState.update { it.copy(editor = editor) } }
        }
        refreshPermissions()
        viewModelScope.launch {
            try {
                graph.repository.aiVisibleIds.collect { ids ->
                    mutableState.update { it.copy(aiVisibleNoteIds = ids, aiAccessLoaded = true) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                graph.ai.stop()
                mutableState.update { it.copy(aiVisibleNoteIds = emptySet(), aiAccessLoaded = false, message = "无法读取 AI 权限，服务已停止") }
            }
        }
        viewModelScope.launch {
            graph.ai.status.collect { status ->
                mutableState.update { it.copy(aiServiceRunning = status.running, aiServiceStarting = status.starting) }
                status.error?.let(::showMessage)
            }
        }
        viewModelScope.launch {
            graph.ready.await()
            try {
                graph.repository.notes.collect { notes ->
                    mutableState.update { it.copy(notes = notes, loading = false) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = "读取笔记失败，请重新打开应用") }
            }
        }
        viewModelScope.launch {
            graph.settings.settings.collect { settings -> mutableState.update { it.copy(syncSettings = settings.toUi()) } }
        }
        viewModelScope.launch {
            graph.sync.status.collect { status ->
                mutableState.update { it.copy(syncStatus = status.description, syncing = status.running) }
            }
        }
        viewModelScope.launch {
            graph.startupMessage.collect { message -> if (message != null) showMessage(message) }
        }
    }

    suspend fun saveNote(draft: NoteDraft): Boolean = graph.scope.async {
        try {
            val saved = graph.drafts.save(draft)
            if (saved) {
                graph.scope.launch {
                    try {
                        graph.ready.await()
                        graph.work.afterEdit(graph.settings.settings.value)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { showMessage("笔记已保存到本机，自动同步暂未安排") }
                }
                showMessage(graph.startupMessage.value ?: "已保存到本机")
            }
            saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showMessage(if (error is NoteInputException || error is SyncProtocolException) error.message ?: "笔记内容无效" else "笔记保存失败，请重试")
            false
        }
    }.await()

    fun openEditor(note: Note?, whiteboard: Boolean) = graph.drafts.open(note, whiteboard)
    fun updateDraft(session: EditorSession) = graph.drafts.update(session)
    suspend fun discardDraft(): Boolean = graph.drafts.discard()
    fun flushDraft() = graph.drafts.flush()

    fun setPinned(id: String, pinned: Boolean) = edit { graph.repository.setPinned(id, pinned) }
    fun setArchived(id: String, archived: Boolean) = edit { graph.repository.setArchived(id, archived) }
    fun trash(id: String) = edit { graph.repository.trash(id) }
    fun restore(id: String) = edit { graph.repository.restore(id) }
    fun deleteForever(id: String) = edit { graph.repository.purge(id) }

    suspend fun setAiVisible(id: String, visible: Boolean): Boolean = try {
        graph.repository.setAiVisible(id, visible)
        mutableState.update { it.copy(aiVisibleNoteIds = if (visible) it.aiVisibleNoteIds + id else it.aiVisibleNoteIds - id) }
        showMessage(if (visible) "本机 AI 可读取和管理此笔记" else "已对本机 AI 隐藏，立即生效")
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { showMessage("AI 权限未更改，请重试"); false }

    fun startAiService() {
        if (state.value.aiAccessLoaded) graph.ai.start() else showMessage("请等待本地 AI 权限加载完成")
    }
    fun stopAiService() = graph.ai.stop()
    fun copyAiToken() {
        showMessage(if (graph.ai.copyToken()) "访问令牌已复制，仅交给可信的本机 AI 客户端" else "请先开启本地 AI 服务")
    }

    fun resetAiToken() {
        viewModelScope.launch {
            try {
                val reset = withContext(Dispatchers.IO) { graph.ai.resetToken() }
                showMessage(if (reset) "访问令牌已重置，开启服务后请复制新令牌并更新客户端" else "请先停止服务，稍后再重置访问令牌")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { showMessage("访问令牌重置失败，请重试") }
        }
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                graph.ready.await()
                block()
                graph.work.afterEdit(graph.settings.settings.value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("修改未保存，请重试")
            }
        }
    }

    fun saveSyncSettings(ui: SyncSettingsUi) {
        viewModelScope.launch {
            try {
                graph.ready.await()
                val preferences = SyncPreferences.fromUi(ui)
                graph.sync.saveSettings(preferences)
                graph.work.configure(preferences)
                graph.work.afterEdit(preferences)
                showMessage("同步设置已保存")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage(SyncCoordinator.userMessage(error))
            }
        }
    }

    fun testSync(ui: SyncSettingsUi) {
        if (state.value.syncing) return
        viewModelScope.launch {
            try {
                graph.ready.await()
                showMessage(graph.sync.test(SyncPreferences.fromUi(ui)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage("连接检查失败 · ${SyncCoordinator.userMessage(error)}")
            }
        }
    }

    fun syncNow() {
        if (state.value.syncing) return
        viewModelScope.launch {
            try {
                graph.ready.await()
                if (!graph.settings.settings.value.enabled) {
                    showMessage("请先在设置中配置并开启同步")
                    return@launch
                }
                val complete = graph.sync.sync()
                if (!complete) graph.work.afterEdit(graph.settings.settings.value)
                showMessage(graph.sync.status.value.description)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showMessage("同步失败 · ${SyncCoordinator.userMessage(error)}")
            }
        }
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        mutableState.update {
            it.copy(
                notificationPermissionGranted = ReminderPermissions.canPostNotifications(context),
                exactAlarmAllowed = ReminderPermissions.canScheduleExactAlarms(context),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            graph.ready.await()
            try {
                graph.reminders.rescheduleAll()
            } catch (_: Exception) {
                showMessage("提醒设置暂未生效，请重新打开应用")
            }
        }
    }

    fun openNote(id: String?) {
        if (!id.isNullOrBlank()) mutableState.update { it.copy(requestedNoteId = id) }
    }

    fun consumeRequestedNote() = mutableState.update { it.copy(requestedNoteId = null) }

    fun consumeMessage() {
        mutableState.update { it.copy(message = null) }
        graph.startupMessage.value = null
    }

    fun showMessage(message: String) = mutableState.update {
        // A completed import/sync must not hide a reminder scheduling failure.
        it.copy(message = graph.startupMessage.value ?: message)
    }

    fun exportBackup(uri: Uri) = fileOperation("备份已导出") { backups.exportJson(uri) }
    fun exportMarkdown(uri: Uri) = fileOperation("Markdown ZIP 已导出") { backups.exportMarkdown(uri) }

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            try {
                graph.ready.await()
                val conflicts = withContext(Dispatchers.IO) { backups.importJson(uri) }
                graph.work.afterEdit(graph.settings.settings.value)
                showMessage(if (conflicts > 0) "导入完成，已保留冲突副本" else "导入完成")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("导入失败，请选择有效的彩笺 JSON 备份；原有笔记未被替换")
            }
        }
    }

    private fun fileOperation(success: String, action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                graph.ready.await()
                withContext(Dispatchers.IO) { action() }
                showMessage(success)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                showMessage("导出失败，请检查存储空间或重新选择位置")
            }
        }
    }
}
