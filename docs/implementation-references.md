# 实现依据

以下官方文档在开发时实际查询，用于核对接口与约束。依赖版本在 Gradle 配置中固定；此处链接不代表依赖自动跟随最新版。

- [离线优先数据层](https://developer.android.com/topic/architecture/data-layer/offline-first)：本地库作为 UI 数据来源，网络结果写回本地。
- [Room DAO](https://developer.android.com/training/data-storage/room/accessing-data)：Flow 查询、Upsert 与查询参数。
- [Room 事务](https://developer.android.com/reference/androidx/room/RoomDatabase#runInTransaction(java.lang.Runnable))：本地与同步写入在事务内合并。
- [WorkManager 请求](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)：周期任务最短 15 分钟、约束与指数退避。
- [唯一任务链](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)：编辑后的同步使用 APPEND_OR_REPLACE，避免正在执行的任务遗漏后续修改。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：设备生成并保护 AES 密钥。
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：系统选择导入和导出目标，无需广泛文件权限。
- [AGP 8.9 兼容性](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes)：API 35、Gradle 8.11.1、JDK 17。
- [Compose 日期选择器](https://developer.android.com/develop/ui/compose/components/datepickers) 与 [时间选择器](https://developer.android.com/develop/ui/compose/components/time-pickers)：选择本地日期和时间，保存为 epoch 毫秒。
- [Markwon 核心 API](https://noties.io/Markwon/docs/v4/core/getting-started.html)：TextView 渲染 Markdown，不启用网络图片插件。
- [Android 自适应图标](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)：前景、背景和 monochrome 图层；Android 13 起支持主题图标。

WebDAV、S3/R2、SigV4 的参考链接见 [同步协议](sync-protocol.md)；闹钟及通知权限的参考链接见 [提醒说明](reminders.md)。
