# 提醒

每条未归档、未删除的笔记可设置一个提醒时间。时间以 UTC 毫秒时间戳保存，界面按设备当前时区显示；旅行切换时区不会改变提醒对应的实际时刻。归档、移入回收站、删除提醒或彻底删除笔记会取消相应闹钟和已显示的通知。清单项目的到期时间、重复提醒暂不属于此功能。

## 权限与系统行为

- Android 13 及以上需要用户允许通知。应在用户设置提醒时请求 `POST_NOTIFICATIONS`，并显示拒绝后的状态；已关闭的通知渠道同样会阻止提醒通知。
- Android 12 及以上的“闹钟和提醒”特殊访问是可选项。允许时使用 `setExactAndAllowWhileIdle()`；未允许时使用 `setAndAllowWhileIdle()`，可能被系统延迟。界面可通过 `ReminderPermissions.exactAlarmSettingsIntent()` 提供用户主动开启权限的入口。
- 每条提醒还保留一份独立的非精确闹钟。系统撤销精确闹钟权限时会停止应用并删除精确闹钟；这份备用闹钟使该情况仍有机会触发通知。任意一份成功触发后都会取消两份闹钟，通过本机投递记录避免重复通知。
- 通知默认使用“笔记提醒”渠道，点击直接打开对应笔记。通知正文不包含笔记内容；锁屏公开版本仅显示“有一条笔记提醒”。最终显示方式由用户的系统通知设置控制。
- 使用系统闹钟 `PendingIntent`，应用进程退出后仍可触发。设备重启、应用更新、时间/时区变更、精确权限授予时会重新读取数据库并恢复提醒。
- 若设备在提醒时关闭，或通知被禁用，本机曾安排过且过期不超过 24 小时的提醒可在恢复调度时补发一次。已经投递过的提醒、导入或同步来的历史提醒不补发；超过 24 小时的提醒不补发。投递记录留在本机，不通过 WebDAV/S3 同步。
- 在系统设置中强行停止应用、清除应用数据或部分厂商限制后台运行，会影响系统闹钟；强行停止后需要再次打开应用。接收器只进行本机数据库读取和通知/闹钟操作，不执行同步网络请求。

## 集成约定

`Application` 实现 `ReminderDependencies.Provider`，提供线程安全的同步 `ReminderStore`：

```kotlin
override val reminderStore: ReminderStore = object : ReminderStore {
    override fun findNote(noteId: String): ReminderNote? = /* 读取持久化笔记并转换 */
    override fun allNotes(): List<ReminderNote> = /* 读取全部持久化笔记并转换 */
}
```

接收器会在工作线程上访问此接口，因此不依赖打开过某个界面或尚未初始化的 ViewModel。将 `Note` 转成 `ReminderNote` 时完整保留归档、删除和提醒时间字段。

`ReminderScheduler(context)` 提供：

| 方法 | 用途 |
| --- | --- |
| `schedule(note)` | 持久化修改后安排或取消该笔记提醒，返回 `EXACT`、`APPROXIMATE` 或 `NONE` |
| `cancel(noteId)` | 取消待触发闹钟及已显示通知，保留本机投递历史 |
| `rescheduleAll()` | 在应用启动、同步完成和从权限设置返回后重建调度，并清理不存在的笔记 |

这些方法都包含磁盘访问，应在 `Dispatchers.IO` 等工作线程上调用。所有新笔记提醒应设置在未来。`rescheduleAll()` 从当前数据库读取完整快照，适合应用状态变更后的统一协调。

`ReminderPermissions.canPostNotifications(context)` 同时检查运行时权限、应用总通知开关和提醒渠道开关；`canScheduleExactAlarms(context)` 表示精确提醒是否可用。`notificationSettingsIntent(context)` 可跳转到系统应用通知设置。

通知使用 `R.drawable.ic_notification`，由应用资源提供。`MainActivity` 在 `onCreate` 和 `onNewIntent` 中读取 `ReminderContract.EXTRA_NOTE_ID`（`"note_id"`），打开对应笔记；笔记已删除时回到笔记列表。

Manifest 需包含：

```xml
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />

<application ...>
    <receiver
        android:name=".reminder.ReminderReceiver"
        android:exported="false" />
    <receiver
        android:name=".reminder.ReminderRescheduleReceiver"
        android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.BOOT_COMPLETED" />
            <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            <action android:name="android.intent.action.TIME_SET" />
            <action android:name="android.intent.action.TIMEZONE_CHANGED" />
            <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
        </intent-filter>
    </receiver>
</application>
```

使用带 10 秒超时的局部唤醒锁，在 `goAsync()` 工作结束时主动释放，保证设备休眠期间能够完成短暂的本机数据库读取。任务完成后必须调用 `PendingResult.finish()`；网络同步不能放进此工作队列。

## 验证

`ReminderPolicyTest` 覆盖未来提醒、归档/删除/移除提醒、历史数据导入、重启补发、24 小时边界、修改时间、取消后恢复、回拨系统时钟及重复投递判断。无需打包 APK 即可运行对应单元测试；设备通知、权限界面及厂商后台限制仍需在设备上验证。

设备验收建议：分别在允许和拒绝精确提醒权限时设置未来提醒；修改时间后确认旧闹钟不显示；归档/删除后确认不再显示；关闭应用进程、重启、切换时区后确认恢复；撤销精确提醒权限后验证备用提醒；拒绝通知后重新允许并打开应用，验证仅补发本机未投递且在 24 小时内的提醒。

## 官方参考

- [安排闹钟与精确权限](https://developer.android.com/develop/background-work/services/alarms)
- [AlarmManager API 与精确权限撤销行为](https://developer.android.com/reference/android/app/AlarmManager)
- [Android 13 通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)
- [通知点击行为与锁屏可见性](https://developer.android.com/develop/ui/views/notifications/build-notification)
- [BroadcastReceiver.goAsync() 时限](https://developer.android.com/reference/android/content/BroadcastReceiver#goAsync())

以上接口按 Android 官方文档核对于 2026-10-17。提醒模块只依赖 Android 平台 API，最低 Android 8（API 26），无额外 WorkManager 依赖。
