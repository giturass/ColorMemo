package dev.colormemo.app.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import androidx.core.content.ContextCompat
import dev.colormemo.core.mcp.LocalMcpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AiServiceStatus(val running: Boolean = false, val starting: Boolean = false, val error: String? = null)

/** Credentials exist only in the live server, never preferences, saved UI state or notifications. */
class AiServiceController(private val context: Context) {
    private val lock = Any()
    private val serviceIntent = Intent(context, AiMcpService::class.java)
    private var server: LocalMcpServer? = null
    private var servicePendingDestruction = false
    private val mutableStatus = MutableStateFlow(AiServiceStatus())
    val status = mutableStatus.asStateFlow()

    fun start() = synchronized(lock) {
        if (servicePendingDestruction) return@synchronized
        servicePendingDestruction = true
        mutableStatus.value = AiServiceStatus(starting = true)
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (_: Exception) {
            servicePendingDestruction = false
            mutableStatus.value = AiServiceStatus(error = "无法启动 AI 服务，请保持应用在前台后重试")
        }
    }

    internal fun install(value: LocalMcpServer): Boolean = synchronized(lock) {
        if (!mutableStatus.value.starting) return@synchronized false
        value.start()
        server = value
        mutableStatus.value = AiServiceStatus(running = true)
        true
    }

    fun stop() {
        clear()
        context.stopService(serviceIntent)
    }

    internal fun clear(error: String? = null) = synchronized(lock) {
        server?.close()
        server = null
        mutableStatus.value = AiServiceStatus(error = error)
    }

    internal fun destroyed() = synchronized(lock) {
        clear(mutableStatus.value.error)
        servicePendingDestruction = false
    }

    fun copyToken(): Boolean = synchronized(lock) {
        val token = server?.token ?: return@synchronized false
        val clip = ClipData.newPlainText("彩笺 AI 访问令牌", token)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    }
}
