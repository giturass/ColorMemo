package dev.colormemo.app.mcp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import dev.colormemo.app.ColorMemoApplication
import dev.colormemo.app.MainActivity
import dev.colormemo.app.R
import dev.colormemo.core.mcp.AiCommand
import dev.colormemo.core.mcp.AiNoteStore
import dev.colormemo.core.mcp.LocalMcpServer
import dev.colormemo.core.mcp.McpProtocol
import kotlinx.coroutines.*

/** Started only from the visible application. No boot start or restart after process death. */
class AiMcpService : Service() {
    private val graph get() = (application as ColorMemoApplication).graph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var launched = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP || !graph.ai.status.value.let { it.starting || it.running }) {
            graph.ai.clear()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            showNotification()
        } catch (_: Exception) {
            graph.ai.clear("无法显示 AI 服务通知，服务未启动")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!launched) {
            launched = true
            scope.launch {
                var candidate: LocalMcpServer? = null
                var installed = false
                try {
                    graph.ready.await()
                    ensureActive()
                    val token = try {
                        graph.ai.loadToken()
                    } catch (_: Exception) {
                        ensureActive()
                        graph.ai.clear("无法读取或保存访问令牌，请重试；仍失败可在停止服务后重置令牌")
                        stopSelf()
                        return@launch
                    }
                    ensureActive()
                    candidate = LocalMcpServer(McpProtocol(AiNoteStore { command ->
                        val result = graph.repository.execute(command)
                        if (command !is AiCommand.ListNotes && command !is AiCommand.Read) {
                            // The durable mutation has succeeded; a scheduler failure must not invite a duplicate create.
                            runCatching { graph.work.afterEdit(graph.settings.settings.value) }
                        }
                        result
                    }), token = token)
                    ensureActive()
                    installed = graph.ai.install(candidate)
                    if (!installed) stopSelf()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    graph.ai.clear("AI 服务启动失败，请检查本机端口 39451 是否被占用后重试")
                    stopSelf()
                } finally {
                    if (!installed) candidate?.close()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun showNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "本地 AI 服务", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, AiMcpService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("彩笺本地 AI 服务已开启")
            .setContentText("仅允许访问本机标记为 AI 可见的笔记")
            .setContentIntent(open).setOngoing(true).setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, "停止服务", stop).build()
        ServiceCompat.startForeground(this, 39451, notification,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
    }

    override fun onDestroy() {
        scope.cancel()
        graph.ai.destroyed()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "local_ai_service"
        const val STOP = "dev.colormemo.app.STOP_AI_SERVICE"
    }
}
