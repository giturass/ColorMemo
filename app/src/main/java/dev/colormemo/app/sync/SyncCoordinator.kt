package dev.colormemo.app.sync

import dev.colormemo.app.data.NoteRepository
import dev.colormemo.app.data.SecureSettingsStore
import dev.colormemo.app.data.SyncPreferences
import dev.colormemo.core.sync.SyncEngine
import dev.colormemo.core.sync.SyncEncryptionKey
import dev.colormemo.core.sync.SyncException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.DateFormat
import java.util.Date
import javax.net.ssl.SSLException

data class SyncStatus(
    val running: Boolean = false,
    val description: String = "仅保存在本机",
    val lastSuccessAt: Long = 0,
)

/** Serializes manual/background runs and configuration changes within this process. */
class SyncCoordinator(private val repository: NoteRepository, private val settings: SecureSettingsStore) {
    private val mutex = Mutex()
    private val mutableStatus = MutableStateFlow(SyncStatus(lastSuccessAt = settings.lastSyncTime()))
    val status = mutableStatus.asStateFlow()

    fun refreshStatus() {
        val time = settings.lastSyncTime()
        mutableStatus.value = SyncStatus(
            description = when {
                !settings.settings.value.enabled -> "仅保存在本机"
                settings.settings.value.encryptionKey.isBlank() -> "请设置端到端加密恢复密钥"
                time == 0L -> "尚未同步"
                else -> "上次加密同步 ${formatTime(time)}"
            },
            lastSuccessAt = time,
        )
    }

    suspend fun saveSettings(value: SyncPreferences) = mutex.withLock {
        // Validate both the endpoint and recovery key before enabling background synchronization.
        if (value.enabled) engine(value)
        val previous = settings.settings.value
        settings.save(value)
        if (previous.copy(enabled = value.enabled, automaticSync = value.automaticSync,
                syncIntervalMinutes = value.syncIntervalMinutes, wifiOnly = value.wifiOnly) != value) {
            settings.recordSync(0)
        }
        refreshStatus()
    }

    suspend fun test(value: SyncPreferences): String = mutex.withLock {
        mutableStatus.value = mutableStatus.value.copy(running = true, description = "正在检查连接…")
        try {
            val result = engine(value).testConnection()
            if (result.remoteExists) "连接及解密成功，远端有 ${result.noteCount} 条记录（尚未验证写入权限）"
            else "服务可访问，尚无同步文件；首次同步将创建加密文件并检查写入权限"
        } finally {
            refreshStatus()
        }
    }

    /** False means edits continued throughout the run and a follow-up pass is still required. */
    suspend fun sync(): Boolean = mutex.withLock {
        val config = settings.settings.value
        if (!config.enabled) {
            refreshStatus()
            return@withLock true
        }
        mutableStatus.value = mutableStatus.value.copy(running = true, description = "正在同步…")
        try {
            val engine = engine(config)
            var conflicts = 0
            repeat(3) {
                val result = engine.sync(repository.snapshot())
                conflicts += result.conflictCount + repository.merge(result.notes)
                // Another local edit can arrive while HTTP is in flight. Upload that edit too.
                if (repository.snapshot().associateBy { it.id } == result.notes.associateBy { it.id }) {
                    val now = System.currentTimeMillis()
                    settings.recordSync(now)
                    mutableStatus.value = SyncStatus(
                        description = if (conflicts > 0) "加密同步完成，已保留冲突副本" else "已加密同步 · ${formatTime(now)}",
                        lastSuccessAt = now,
                    )
                    return@withLock true
                }
            }
            mutableStatus.value = mutableStatus.value.copy(running = false, description = "已合并，新修改等待继续同步")
            false
        } catch (cancelled: CancellationException) {
            refreshStatus()
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.value = mutableStatus.value.copy(running = false, description = "同步失败 · ${userMessage(error)}")
            throw error
        }
    }

    private fun engine(value: SyncPreferences) = SyncEngine(
        config = value.remoteConfig(),
        encryptionKey = SyncEncryptionKey.parse(value.encryptionKey),
    )

    companion object {
        fun userMessage(error: Throwable): String = when (error) {
            is SyncException -> error.message ?: "同步失败，请检查配置"
            is UnknownHostException -> "无法连接服务器，请检查网络和地址"
            is SocketTimeoutException -> "连接超时，请稍后再试"
            is SSLException -> "HTTPS 证书验证失败，请检查服务器证书"
            is IllegalArgumentException -> "设置或文件内容无效，请检查输入"
            // Never expose request headers, raw HTTP bodies, URLs or credentials in UI/logs.
            else -> "请检查网络、服务地址、访问权限和同步文件格式"
        }

        private fun formatTime(time: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))
    }
}
