# 实现依据

以下官方文档在开发时实际查询，用于核对接口与约束。依赖版本在 Gradle 配置中固定；此处链接不代表依赖自动跟随最新版。

- [离线优先数据层](https://developer.android.com/topic/architecture/data-layer/offline-first)：本地库作为 UI 数据来源，网络结果写回本地。
- [Room DAO](https://developer.android.com/training/data-storage/room/accessing-data)：Flow 查询、Upsert 与查询参数。
- [Room 事务](https://developer.android.com/reference/androidx/room/RoomDatabase#runInTransaction(java.lang.Runnable))：本地与同步写入在事务内合并。
- [WorkManager 请求](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)：周期任务最短 15 分钟、约束与指数退避。
- [唯一任务链](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work)：编辑后的同步使用 APPEND_OR_REPLACE，避免正在执行的任务遗漏后续修改。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)（加密扩展于 2026-10-18 复核）：设备生成并保护用于本机配置加密的 AES 密钥；跨设备恢复密钥随配置加密保存，不将设备 Keystore 密钥导出共享。
- [Android 密码学建议](https://developer.android.com/privacy-and-security/cryptography)（2026-10-18）：AES/GCM/NoPadding 与 256 位密钥、平台默认密码学提供方。
- [敏感剪贴板内容](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste#SensitiveContent)（2026-10-18）：复制恢复密钥时设置 `ClipDescription.EXTRA_IS_SENSITIVE`，避免系统在剪贴板预览中显示密钥。
- [Java 17 Cipher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html) 与 [GCMParameterSpec](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html)（2026-10-18）：GCM 同一密钥下 IV 不重复，先传入 AAD，再加密或认证解密；认证标签按位设置为 128。
- [RFC 5116](https://www.rfc-editor.org/rfc/rfc5116.html) 与 [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648.html)（2026-10-18）：AEAD_AES_256_GCM 的密钥、nonce 和标签长度，AEAD 不提供防重放；密钥与信封采用各自固定的规范 Base64 编码。
- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：系统选择导入和导出目标，无需广泛文件权限。
- [AGP 8.9 兼容性](https://developer.android.com/build/releases/past-releases/agp-8-9-0-release-notes)：API 35、Gradle 8.11.1、JDK 17。
- [Compose 日期选择器](https://developer.android.com/develop/ui/compose/components/datepickers) 与 [时间选择器](https://developer.android.com/develop/ui/compose/components/time-pickers)：选择本地日期和时间，保存为 epoch 毫秒。
- [Markwon 核心 API](https://noties.io/Markwon/docs/v4/core/getting-started.html)：TextView 渲染 Markdown，不启用网络图片插件。
- [Android 自适应图标](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)：前景、背景和 monochrome 图层；Android 13 起支持主题图标。
- [Compose 手势处理](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)（2026-10-18）：`awaitEachGesture` / `awaitFirstDown` 处理连续笔画，事件传播顺序及 `consume()` 避免绘画手势同时滚动编辑页。
- [Compose 多点触控](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch) 与 [AndroidX 手势实现](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/gestures/TransformGestureDetector.kt)（2026-10-18）：以持续按下的触点计算中心、平移和缩放，避免触点增减造成画布跳动；绘画与导航共用一次手势循环，双指接管时回滚本次临时笔迹。缩放和平移仅改变视图，保存与导出沿用原有白板坐标。
- [SVG 2 笔画绘制](https://www.w3.org/TR/SVG2/painting.html#StrokeLinecapProperty)（2026-10-18）：圆帽、圆角与单点笔画的表现；白板导出使用固定世界坐标，单点显式绘制为圆形。文件仍通过上述 Storage Access Framework 写入用户选择的 ZIP，不新增存储权限。
- [SVG 2 viewBox](https://www.w3.org/TR/SVG2/coords.html#ViewBoxAttribute) 与 [kotlinx.serialization 严格字段处理](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html)（2026-10-19 纵向画布扩展复核）：画布、预览、SVG 背景及 viewBox 统一采用 `1000×2000`；JSON 不新增字段，坐标数值上限由应用验证，旧版本会拒绝超出其纵向范围的数据。

WebDAV、S3/R2、SigV4 的参考链接见 [同步协议](sync-protocol.md)；闹钟及通知权限的参考链接见 [提醒说明](reminders.md)。

2026-10-19 编辑交互扩展复核：

- [Compose 滑动删除](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/swipe-to-dismiss)：使用 `SwipeToDismissBox` 单向滑动，将卡片移入可恢复的回收站，多选时停用滑动。
- [Markwon HTML](https://noties.io/Markwon/docs/v4/html/) 与 [4.6.2 UnderlineHandler](https://github.com/noties/Markwon/blob/v4.6.2/markwon-html/src/main/java/io/noties/markwon/html/tag/UnderlineHandler.java)：仅注册下划线处理器，渲染 `<u>…</u>`；不启用图片加载。
- 文件选择、附件打开、笔型序列化及 SVG 的依据见 [同步协议](sync-protocol.md) 中 2026-10-19 的文档记录。

- [Android 自适应图标](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)（2026-10-19 再次复核）：前景保留透明边距，以适应启动器形状裁切；背景为不透明底色，单色层沿用新图案的透明度，支持主题图标。

- [WorkManager 周期请求](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work) 与 [ExistingPeriodicWorkPolicy.UPDATE](https://developer.android.com/reference/androidx/work/ExistingPeriodicWorkPolicy)（2026-10-19 再次复核）：周期最短 15 分钟；保存间隔时更新唯一周期任务，正在执行的任务继续，下次周期使用新的设置。系统省电与网络条件可能延迟执行。

- [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)、[高效加载位图](https://developer.android.com/topic/performance/graphics/load-bitmap) 与 [AndroidX ExifInterface 1.4.2](https://developer.android.com/jetpack/androidx/releases/exifinterface#1.4.2)（2026-10-19 图片插入复核）：`OpenDocument` 限定 `image/*`，再按真实文件内容检查可解码性；后台用 `inJustDecodeBounds` 和 `inSampleSize` 加载有尺寸上限的预览，并用 AndroidX 组件按 EXIF 修正方向，兼容旧 Android。原文件沿用已有附件字段保存、加密同步和备份，Markdown ZIP 使用图片引用；不新增存储权限或同步字段。

2026-10-20 Markdown 图片接入复核：

- [CommonMark 图片语法](https://spec.commonmark.org/0.31.2/#images) 与 [反斜杠转义](https://spec.commonmark.org/0.31.2/#backslash-escapes)：插入 `![说明](<attachments/摘要-文件名>)`，文件名中的 Markdown 标点转义为普通文字。使用当前 Markwon 依赖的 CommonMark 解析器识别内联及引用式图片、链接，代码中的示例不计为图片引用。ZIP 使用相同相对路径，保留正文原文与图片顺序，相同内容路径只写入一次。
- Markwon 4.6.2 官方实现 [CorePlugin](https://github.com/noties/Markwon/blob/v4.6.2/markwon-core/src/main/java/io/noties/markwon/core/CorePlugin.java)、[AsyncDrawableLoader](https://github.com/noties/Markwon/blob/v4.6.2/markwon-core/src/main/java/io/noties/markwon/image/AsyncDrawableLoader.java)、[AsyncDrawableScheduler](https://github.com/noties/Markwon/blob/v4.6.2/markwon-core/src/main/java/io/noties/markwon/image/AsyncDrawableScheduler.java)：通过核心图片 Span 和自定义加载器显示本笔记附件；后台采样解码并复用同图位图，正文替换或视图离开时取消 Span 加载。加载器仅匹配本笔记附件路径，不访问网络或任意本机文件。图片数据继续沿用已有附件字段，无同步格式变更。
