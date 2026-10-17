# 彩笺 · ColorMemo

一个受 Google Keep 启发的开源 Android 记事应用。用颜色整理想法，用 Markdown 写下细节，再把笔记同步到自己管理的 WebDAV、Amazon S3 或 Cloudflare R2。

原生 Kotlin + Jetpack Compose，Android 8.0（API 26）及以上，Apache-2.0 许可证。无需注册账号，无广告、统计 SDK 或自建中转服务器。联网同步默认关闭。

## 已实现

- **颜色卡片**：11 种卡片颜色、瀑布流、列表切换、置顶、搜索、颜色筛选、深浅主题。
- **笔记管理**：创建、编辑、归档、回收站、恢复和永久删除。Room 在本机保存；编辑页点击「保存」后提交，返回时提示处理未保存内容。
- **Markdown**：编辑/预览切换，支持标题、列表、引用、代码、链接、表格、删除线和任务列表。预览不自动下载远端图片。
- **提醒**：日期与时间选择、通知打开对应笔记、设备重启后恢复、去重；可选精确闹钟权限，缺少权限时使用非精确提醒。
- **同步**：WebDAV、Amazon S3、兼容 S3 的服务和 Cloudflare R2；手动同步、编辑后同步、约 15 分钟一次的后台同步，可限不计费网络。
- **保护修改**：ETag 条件写入、并发重试、版本历史和冲突副本；删除标记参与同步，避免旧设备直接复活已删除的原始笔记。
- **可迁移数据**：JSON 完整备份与合并导入、Markdown ZIP 导出，使用系统文件选择器，不索取整个存储空间权限。
- **本地凭据保护**：同步配置由 Android Keystore 的 AES-GCM 密钥加密，备份中不包含密码或访问密钥。

本项目使用自己的数据格式，不连接 Google Keep 账号。当前界面以简体中文为主。

## 获取 APK 与开发

从 [GitHub Releases](https://github.com/giturass/ColorMemo/releases/latest) 下载 `ColorMemo-v0.1.4.apk`，支持 Android 8.0 及以上。源码仓库：[giturass/ColorMemo](https://github.com/giturass/ColorMemo)。

正式 APK 由 [GitHub Actions](https://github.com/giturass/ColorMemo/actions/workflows/release.yml) 测试、构建、签名并发布，沿用 MDTerm Release 的签名证书。推送版本标签或手动指定已有标签即可发布；包名为 `dev.colormemo.app`。签名指纹、下载校验及维护步骤见 [发布说明](docs/releasing.md)。

用 Android Studio 打开此目录，使用 JDK 17 或 21、Android SDK 35、Build Tools 35.0.0。项目固定 AGP 8.9.2、Kotlin 2.1.20、Gradle 8.11.1 和 Compose BOM 2025.04.01，Gradle Wrapper 带发行包校验和。

```sh
# 纯 JVM 协议测试，不需要 Android SDK
./gradlew -PcoreOnly=true :core:test

# Android 源码检查与测试；这组命令不生成 APK
./gradlew :core:test :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
```

Termux 可使用仓库内的验证脚本。它把下载、日志和工具缓存放在 `~/tmp/colormemo-validation`，只执行测试和源码检查：

```sh
python3 tools/verify-termux.py --core-only
# 完整检查需要 Termux 可运行的 aapt2；脚本使用官方 Android SDK 归档并验证校验和
python3 tools/verify-termux.py --aapt2 /path/to/termux/aapt2
```

## 配置同步

打开应用设置，选择服务，填写参数并「测试连接」，然后开启同步并保存。测试连接只读取文件；首次实际同步才验证写入权限。各设备使用相同的服务器、目录或对象前缀。

### WebDAV

1. 在服务器上创建一个专用目录，例如 `ColorMemo`。
2. 地址填写目录的最终 HTTPS URL，例如 `https://cloud.example.com/remote.php/dav/files/alice/ColorMemo/`。
3. 填写用户名和密码；Nextcloud 等服务建议使用应用专用密码。

应用读写该目录下的 `colormemo-v1.json`。目录需预先存在，服务器需支持强 ETag 和 `If-Match` / `If-None-Match` 条件 PUT。不跟随重定向；遇到重定向提示，请改填最终地址。应用不自动创建服务器目录。

### Amazon S3 / 兼容 S3

| 参数 | 示例 |
| --- | --- |
| 服务地址 | `https://s3.us-east-1.amazonaws.com` |
| 存储桶 | `my-private-notes` |
| 区域 | `us-east-1`，与桶所在区域一致 |
| Access Key ID / Secret Access Key | 专用且只拥有必要权限的密钥 |
| 路径前缀 | `colormemo` |
| Session token | 仅使用临时凭据时填写 |

采用路径式地址与 AWS Signature V4。默认对象为 `colormemo/colormemo-v1.json`。需要对象的 `s3:GetObject`、`s3:PutObject` 权限；AWS S3 判断尚未创建的对象是否存在还需要 `s3:ListBucket`。仅提供对象读写权限时，可以先创建有效的空文档：`{"schemaVersion":1,"notes":[]}`。无需公开存储桶，无需 DeleteObject 权限。

### Cloudflare R2

选择 R2，地址填写 `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`，再填写桶名和 R2 S3 API Access Key ID / Secret Access Key。区域自动使用 `auto`。使用 S3 API 端点，不使用 `r2.dev` 公共访问地址。令牌需授予目标桶对象的读写权限。

### 同步行为与边界

- 以笔记修订历史判定先后；两个设备独立修改同一笔记时保留冲突副本，并在卡片上标注。
- 同步下载期间继续编辑不会被旧快照覆盖。正在变化的远端会触发有限重试；失败不会清空本地库。
- 回收站保留笔记内容。永久删除清空本机当前记录的标题和正文，但保留不含正文的删除标记以通知其他设备；服务端版本历史、已有备份和离线设备可能仍保有旧内容。
- 当前采用单个 JSON 文档，适合个人文本笔记：上限 16 MiB、10,000 条记录（含删除标记），单条正文 100,000 字符、标题 500 字符。不支持图片附件、实时协同或多同步目标同时运行。
- 服务器保存可读的 JSON；HTTPS 保护传输，本地 Keystore 保护凭据。**当前不提供端到端加密**，请使用私有存储桶/目录并限制访问权限。
- Android 的后台限制、节电模式与网络约束会延迟自动同步；「立即同步」可在前台执行。设置里的不计费网络限制仅作用于自动同步。

协议、并发策略和官方文档依据见 [同步协议](docs/sync-protocol.md)。

## 提醒与备份

设置提醒时允许通知权限；如需尽量准时，可在系统设置中授予「闹钟和提醒」权限。设备省电策略仍可能影响送达。归档或放入回收站会取消提醒；仅同步过来的过去时间不会补发。已在本机安排、因关机等原因错过的提醒可在 24 小时内补发一次。详细规则见 [提醒说明](docs/reminders.md)。

JSON 备份含笔记、归档、回收站和同步修订信息，导入会合并并保留冲突，不覆盖整个数据库。Markdown ZIP 含可见和归档笔记，便于迁移到其他编辑器；它不携带完整修订元数据。卸载前先同步或导出；系统云备份和设备传输已关闭，以避免复制不能在另一设备解密的凭据。

## 代码结构

```text
app/                     Android UI、Room、Keystore、提醒与 WorkManager
core/                    纯 Kotlin 数据模型、合并、WebDAV/S3 协议、SigV4
docs/                    协议、权限行为和文档依据
tools/verify-termux.py    Termux 无 APK 验证脚本
.github/workflows/       CI 测试、Lint、签名 Release APK 与 GitHub 发布
```

参见 [贡献指南](CONTRIBUTING.md)、[安全说明](SECURITY.md) 和 [Apache-2.0 许可证](LICENSE)。
