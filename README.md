# 彩笺 · ColorMemo

一个受 Google Keep 启发的开源 Android 记事应用。用颜色整理想法，用 Markdown 写下细节，再把笔记同步到自己管理的 WebDAV、Amazon S3 或 Cloudflare R2。

原生 Kotlin + Jetpack Compose，Android 8.0（API 26）及以上，Apache-2.0 许可证。无需注册账号，无广告、统计 SDK 或自建中转服务器。联网同步默认关闭。

## 已实现

- **分类浏览**：侧栏“便签”和“白板”分别显示文字笔记与白板，分类数量各自统计。
- **颜色卡片**：11 种卡片颜色、瀑布流、列表切换、置顶、搜索、颜色筛选、深浅主题。卡片长按多选、左划移入回收站；最后修改时间在编辑页底部中间的三点菜单内。
- **笔记管理**：创建、编辑、归档、回收站、恢复和永久删除。Room 在本机保存；编辑页点击「保存」后提交，返回时提示处理未保存内容。
- **Markdown**：编辑/预览切换，支持标题、列表、引用、代码、链接、表格、下划线、删除线和任务列表。预览不自动下载远端图片。
- **白板**：从侧边栏进入，画布占满主要编辑区域，支持双指平移与缩放及一键查看全图；图标切换画笔和橡皮，支持细签字笔、水彩笔、马克笔以及八档圆点粗细（1、3、6、10、16、24、32、40），可选择画笔颜色，整笔擦除、撤销、重做和清空，标题与说明单独编辑。保存后可继续编辑，卡片显示缩略图，侧栏可筛选白板。
- **提醒**：日期与时间选择、通知打开对应笔记、设备重启后恢复、去重；可选精确闹钟权限，缺少权限时使用非精确提醒。
- **同步**：WebDAV、Amazon S3、兼容 S3 的服务和 Cloudflare R2；手动同步、编辑后同步、后台定期同步，可限不计费网络。
- **附件**：编辑页左下角颜色旁可添加文件、打开和移除附件；文件完整内容随笔记加密同步，JSON 备份及 Markdown ZIP 导出也保留附件。单个文件最多 5 MiB，每条笔记合计最多 8 MiB、20 个附件。
- **端到端加密**：同步前在设备上用 AES-256-GCM 加密整份快照，包含文字、白板、附件和修订信息；各设备使用同一恢复密钥，存储服务只收到密文。
- **保护修改**：ETag 条件写入、并发重试、版本历史和冲突副本；删除标记参与同步，避免旧设备直接复活已删除的原始笔记。
- **可迁移数据**：JSON 完整备份与合并导入、Markdown ZIP 导出，使用系统文件选择器，不索取整个存储空间权限。
- **本地凭据保护**：同步配置与恢复密钥由 Android Keystore 的 AES-GCM 密钥加密，备份中不包含密码、访问密钥或恢复密钥。

本项目使用自己的数据格式，不连接 Google Keep 账号。当前界面以简体中文为主。

## 获取 APK 与开发

从 [GitHub Releases](https://github.com/giturass/ColorMemo/releases/latest) 下载 `ColorMemo-v0.2.3.apk`，支持 Android 8.0 及以上。源码仓库：[giturass/ColorMemo](https://github.com/giturass/ColorMemo)。

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

打开应用设置，选择服务，填写参数和恢复密钥并「测试连接」，然后开启同步并保存。测试连接只读取、解密和验证文件；首次实际同步才验证写入权限。各设备使用相同的服务器、目录或对象前缀以及恢复密钥。

### 恢复密钥与旧版升级

首次使用同步时，在设置中生成恢复密钥，并将它保存到密码管理器等安全位置；在其他设备填写同一密钥。恢复密钥是应用随机生成的 256 位密钥，显示为 43 个字符，不是自选密码。服务器、维护者和服务登录密码都不能帮你找回密钥；密钥及所有已配置设备均丢失后，远端笔记将无法解密。

**这是同步协议的不兼容升级：所有同步都必须加密，旧版明文文件会被拒绝。** 如已有同步数据，升级前让旧设备完成同步并保存备份，然后停止旧版同步。升级所有设备后选择新的空 WebDAV 目录或 S3/R2 路径前缀，生成并备份一份恢复密钥，在所有设备配置同一目标和密钥，再从已有本地笔记上传。应用不会自动转换或删除旧远端文件；旧文件、对象历史版本和已有备份仍需自行管理。

更换恢复密钥同样需要新目标和完整本地数据；直接更改密钥不能解密原目标，也不会覆盖其密文。文件名仍为 `colormemo-v1.json`，其中的加密信封版本已改为 2。

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

采用路径式地址与 AWS Signature V4。默认对象为 `colormemo/colormemo-v1.json`。需要对象的 `s3:GetObject`、`s3:PutObject` 权限；AWS S3 判断尚未创建的对象是否存在还需要 `s3:ListBucket`。请为首次同步配置该权限，不能用明文空 JSON 预创建同步文件。无需公开存储桶，无需 DeleteObject 权限。

### Cloudflare R2

选择 R2，地址填写 `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`，再填写桶名和 R2 S3 API Access Key ID / Secret Access Key。区域自动使用 `auto`。使用 S3 API 端点，不使用 `r2.dev` 公共访问地址。令牌需授予目标桶对象的读写权限。

### 同步行为与边界

- 以笔记修订历史判定先后；两个设备独立修改同一笔记时保留冲突副本，并在卡片上标注。
- 同步下载期间继续编辑不会被旧快照覆盖。正在变化的远端会触发有限重试；失败不会清空本地库。
- 回收站保留笔记内容。永久删除清空本机当前记录的标题、正文、白板和附件，但保留不含内容的删除标记以通知其他设备；服务端版本历史、已有备份和离线设备可能仍保有旧内容。
- 当前采用单个加密快照，适合个人文字、白板及小文件附件：解密后上限 16 MiB、10,000 条记录（含删除标记），单条正文 100,000 字符、标题 500 字符，每块白板最多 500 笔、合计 6,000 个采样点。加密后的 JSON 因 Base64 编码约增加三分之一体积。附件的 Base64 内容同样计入 16 MiB 上限；加入附件使全库超过上限时会拒绝保存并保留草稿。不支持实时协同或多同步目标同时运行。
- 白板随笔记一起加密同步，参与修订和冲突保留。JSON 备份保留可编辑笔画，旧版会拒绝含白板的备份，以免丢失图画；所有同步设备均须升级到支持加密的版本。新增附件或水彩／马克笔后，旧客户端会拒绝不认识的字段，全部设备需更新到支持这些功能的版本。
- 服务器可看到对象大小、更新时间和访问记录，无法从密文读取笔记。端到端加密不阻止服务器删除或重放旧密文；HTTPS、私有存储桶/目录和最小权限凭据仍需保留。本机数据库与手动导出文件不加密。
- 自动同步每 15 分钟检查一次；Android 的后台限制、节电模式与网络约束仍可能延迟执行。「立即同步」可在前台执行，设置里的不计费网络限制仅作用于自动同步。

协议、并发策略和官方文档依据见 [同步协议](docs/sync-protocol.md)。

## 提醒与备份

设置提醒时允许通知权限；如需尽量准时，可在系统设置中授予「闹钟和提醒」权限。设备省电策略仍可能影响送达。归档或放入回收站会取消提醒；仅同步过来的过去时间不会补发。已在本机安排、因关机等原因错过的提醒可在 24 小时内补发一次。详细规则见 [提醒说明](docs/reminders.md)。

JSON 备份含笔记、白板笔画、完整附件、归档、回收站和同步修订信息，导入会合并并保留冲突，不覆盖整个数据库。Markdown ZIP 含可见和归档笔记，每块白板另存为同目录 SVG，并在对应 Markdown 中引用，便于其他编辑器显示；附件保存在各笔记对应的子目录中并使用相对链接引用。它不携带完整修订元数据。卸载前先同步或导出；系统云备份和设备传输已关闭，以避免复制不能在另一设备解密的凭据。

JSON 和 Markdown ZIP 导出均为明文，也不包含恢复密钥；请分别安全保存备份与恢复密钥。远端加密文件不能直接作为 JSON 备份导入，应在应用中配置目标和密钥后同步恢复。

## 代码结构

```text
app/                     Android UI、Room、Keystore、提醒与 WorkManager
core/                    纯 Kotlin 数据模型、合并、端到端加密、WebDAV/S3 协议、SigV4
docs/                    协议、权限行为和文档依据
tools/verify-termux.py    Termux 无 APK 验证脚本
.github/workflows/       CI 测试、Lint、签名 Release APK 与 GitHub 发布
```

参见 [贡献指南](CONTRIBUTING.md)、[安全说明](SECURITY.md) 和 [Apache-2.0 许可证](LICENSE)。
