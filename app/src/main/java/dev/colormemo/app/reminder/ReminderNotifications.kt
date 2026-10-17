package dev.colormemo.app.reminder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.colormemo.app.MainActivity
import dev.colormemo.app.R

internal object ReminderNotifications {
    const val CHANNEL_ID = "note_reminders"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "笔记提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "在你设置的时间提醒你查看笔记"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    @SuppressLint("MissingPermission") // Receiver checks runtime and channel permission first.
    fun show(context: Context, note: ReminderNote) {
        ensureChannel(context)
        val openNote = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.Builder().scheme("colormemo").authority("note").appendPath(note.id).build()
            putExtra(ReminderContract.EXTRA_NOTE_ID, note.id)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            openNote,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("彩笺提醒")
            .setContentText("有一条笔记提醒")
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(note.title.ifBlank { "未命名笔记" }.take(200))
            .setContentText("点按查看笔记")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setWhen(note.reminderAt ?: System.currentTimeMillis())
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        requireNotNull(context.getSystemService(NotificationManager::class.java))
            .notify(tag(note.id), NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context, noteId: String) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(tag(noteId), NOTIFICATION_ID)
    }

    private fun tag(noteId: String) = "reminder/$noteId"
}
