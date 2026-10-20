# 彩笺同步协议 v2：端到端加密

彩笺使用自己的加密 JSON 快照在设备间同步记事，不调用 Google Keep 的私有接口。每次同步先读取、认证解密并验证远端，再合并，最后重新加密并使用条件写入提交；不能安全解密、合并或服务器拒绝条件写入时，保留本地数据并报告错误。核心实现位于 `core/`，不依赖 Android，可单独运行 JVM 测试。

所有同步强制端到端加密。本次升级不兼容旧版明文远端，不提供明文回退或自动转换。已有用户需先让旧设备完成同步并备份，再停用旧版同步、升级全部设备，将本地数据上传到新的空目录或对象前缀；所有设备须使用同一个恢复密钥。旧远端文件及历史版本不会自动加密或删除。

## 存储位置与配置

| 后端 | 设置 | 远端文件 |
| --- | --- | --- |
| WebDAV | 已存在的目录 URL、用户名、应用密码 | `<目录>/colormemo-v1.json` |
| S3 | HTTPS endpoint、bucket、region、Access Key ID、Secret Access Key、可选 session token 与 prefix | `<endpoint>/<bucket>/<prefix>/colormemo-v1.json` |
| Cloudflare R2 | `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`、bucket、region=`auto`、R2 的 S3 密钥 | 默认 `<bucket>/colormemo/colormemo-v1.json` |

`colormemo-v1.json` 保留为既定对象名称，文件内的加密信封版本为 2。名称不会让旧版客户端兼容新协议：旧版严格拒绝未知版本，不能重写此密文。配置还必须包含本地生成或从另一台可信设备导入的恢复密钥；恢复密钥不会作为 HTTP 参数、请求头或密钥字段上传。

WebDAV 目录应提前在服务端创建。例如 Nextcloud 的目录 URL 可以是 `https://cloud.example.com/remote.php/dav/files/alice/ColorMemo/`。不填公开分享页地址。用户名和密码填写在独立字段中；目录地址不能含凭据、查询参数或片段。当前使用 HTTP Basic 身份验证，不支持交互式网页登录、Digest 或 OAuth。

S3 使用 path-style 寻址；普通 AWS S3 建议填写对应区域的 endpoint，例如 `https://s3.ap-southeast-1.amazonaws.com`，region 填实际区域。自建兼容服务必须支持 `GetObject`、`PutObject`、强 ETag 及原子的 `If-Match` / `If-None-Match` 条件写入。本协议不使用 AWS S3 Express directory bucket、对象 ACL、multipart upload 或桶创建接口。

AWS S3 至少需要目标对象的 `s3:GetObject`、`s3:PutObject`。首次不存在的对象只有在具有桶的 `s3:ListBucket` 权限时才返回 404；否则 AWS 返回 403，彩笺会将其当作权限错误，绝不把拒绝访问当成空远端。请为首次同步授予该权限，不能用明文空 JSON 预创建同步文件。R2 在目标桶上选择 **Object Read & Write**，使用其生成的 Access Key ID 和 Secret Access Key，不是 Cloudflare 通用 API Bearer token。存在 jurisdiction 的 R2 桶需使用控制台对应的 endpoint（如 `.eu.r2.cloudflarestorage.com`）。

正式地址强制 HTTPS，系统正常校验证书；核心模块仅对 `localhost`、`127.0.0.1`、`::1` 允许 HTTP，供 JVM 测试使用，Android 应用清单进一步禁止所有明文 HTTP。客户端不跟随重定向，不将凭据转发到新地址，不忽略证书错误。配置对象的 `toString()` 会隐藏凭据；凭据校验仅返回固定的 `SyncConfigurationException` 信息，远端错误响应正文不会拼入可见错误信息。

## 加密信封与密钥

恢复密钥由 `SecureRandom` 生成 32 字节（256 位），以 RFC 4648 URL-safe Base64 编码为 43 个字符，省略 `=`。输入可去除首尾空白，之后必须是规范编码；不接受自选口令、内部空白、填充符或非规范 pad bits。各设备共享此密钥，密钥本身不从服务器下载，也不存在服务端恢复或密码派生步骤。

远端 JSON 只含以下四个字段；字段顺序不影响读取：

| 字段 | 类型与约束 |
| --- | --- |
| `schemaVersion` | 整数 `2` |
| `encryption` | 字符串 `AES-256-GCM` |
| `nonce` | 新生成的 12 字节（96 位）随机值，使用标准 RFC 4648 Base64 编码，固定 16 个字符 |
| `ciphertext` | AES-GCM 密文及末尾 16 字节（128 位）认证标签整体的标准 RFC 4648 Base64 编码，保留所需 `=` 填充 |

每次加密和并发写入重试都生成新的随机 nonce；同一密钥下不能复用 nonce。AES 使用 `AES/GCM/NoPadding`，认证标签长度固定 128 位，明文是下节完整 `SyncDocument` 的规范 UTF-8 JSON 字节。整个快照一起加密，包括标题、正文、白板笔画、附件元数据与完整文件内容、颜色、提醒、删除标记、时间、设备 ID 和修订历史。信封中的两个 Base64 字段使用标准字母表 `+/`，恢复密钥使用 URL-safe 字母表 `-_`，二者不可混用。

AAD 在加密和解密前通过 `Cipher.updateAAD()` 设置为下列 UTF-8 字节，无 BOM、无末尾换行；两处换行均为单个 LF（`0A`）：

```text
ColorMemo sync
2
AES-256-GCM
```

固定 AAD 将密文绑定到彩笺同步用途、版本和算法。解析器在解密前校验信封的精确字段集合、字段类型、版本、算法、nonce 长度和规范 Base64；重复字段、未知字段、缺失字段、转义字段值、无效 UTF-8 和超限数据都会被拒绝。仅认证成功的完整明文才交给 `SyncJson` 验证并参与合并。密钥错误、认证失败或格式不受支持时停止，不能回退为明文、把远端当空文件或 PUT 覆盖。

加密前的快照上限保持 16 MiB。密文仅比明文多 16 字节标签，Base64 约增加三分之一长度；HTTP 信封上限为 `ceil((16 MiB + 16) / 3) × 4 + 256 = 22,369,900` 字节（约 21.34 MiB），其中 256 字节预留给信封头。解密后再次验证原有 16 MiB 与记录数量等限制。不会对内容进行压缩。

以下是公开的互操作测试向量，**只能用于测试，不能用作个人同步密钥**：密钥字节依次为 `00..1f`（恢复密钥 `AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8`），nonce 字节为 `00..0b`，明文精确为 `{"schemaVersion":1,"notes":[]}`，AAD 如上。加密结果：

```json
{"schemaVersion":2,"encryption":"AES-256-GCM","nonce":"AAECAwQFBgcICQoL","ciphertext":"PCCleK2Ar3rbJOX42IYWT7nnqxaeFCsZS0Xf3kAUeXJUSF7PZwTt89WhG0qbyA=="}
```

恢复密钥在本机作为完整同步配置的一部分，由设备 Android Keystore 中另一个不可导出的 AES-GCM 密钥加密保存。跨设备使用的是恢复密钥，而不是 Keystore 密钥；应用处理配置和同步时需要在进程内使用恢复密钥。手动 JSON 备份、Markdown ZIP 和 Room 数据库不采用此同步加密。

## 解密后的文档格式

内部文档与手动 JSON 备份继续使用 `schemaVersion: 1`，它只能在本机或加密信封内出现，不能直接作为远端同步文件：

```json
{
  "schemaVersion": 1,
  "notes": [
    {
      "id": "c55ba3fa-7626-4653-bdce-652261f32690",
      "title": "周末计划",
      "content": "# 山间一日\n\n- [ ] 带水\n- [ ] 带相机",
      "color": "MINT",
      "isPinned": true,
      "isArchived": false,
      "deletedAt": null,
      "isPurged": false,
      "reminderAt": null,
      "createdAt": 1792195200000,
      "updatedAt": 1792195200000,
      "revision": "f5e8814d-94e4-4cb1-a7be-fbd3b80627cf",
      "parentRevision": null,
      "deviceId": "761786b2-42c6-4442-9852-4d0ea9f251ba",
      "ancestorRevisions": [],
      "conflictOf": null,
      "conflictRevision": null
    }
  ]
}
```

时间为 Unix epoch 毫秒。`content` 保存原始 Markdown；颜色枚举为 `DEFAULT`、`CORAL`、`PEACH`、`SAND`、`MINT`、`SAGE`、`FOG`、`STORM`、`DUSK`、`BLOSSOM`、`CLAY`。`reminderAt` 同步的是时间点，各设备自行申请通知权限并安排系统提醒；它不表示一台设备提醒后，其他设备就必定取消提醒。

白板笔记额外包含 `whiteboard` 字段，文字说明仍保存于 `content`，例如：

```json
"whiteboard": {
  "strokes": [
    {
      "color": 4278190080,
      "width": 8.0,
      "points": [{"x": 120.0, "y": 200.0}, {"x": 400.0, "y": 500.0}]
    }
  ]
}
```

笔画按绘制顺序排列，坐标固定为横向 `0..1000`、纵向 `0..2000` 的长方形空间，与显示尺寸无关；宽度为同一空间内的 `1..40`，颜色为不透明 ARGB 无符号值（JSON 以正整数表示）。点坐标与宽度必须为有限数值，每笔至少一个点，每块白板最多 500 笔、合计 6,000 点；默认签字笔的单点显示为圆点，多点显示为圆帽、圆角折线。`{"strokes":[]}` 表示空白板，`whiteboard` 缺失或为 `null` 表示普通文字笔记。

2026-10-19 纵向扩展：旧的 `1000×1000` 笔迹保留原始坐标，在其下方增加空白区域，不拉伸、重写或迁移旧笔迹。JSON 字段、内部文档版本和加密信封保持不变，仅扩展纵坐标合法范围；旧客户端的坐标校验会拒绝包含 `y > 1000` 的同步快照或备份，因此使用下方新区域后，所有同步设备须更新到支持此范围的版本。卡片预览和 SVG 导出均采用 `1000×2000` 比例。此次复核了 [kotlinx.serialization 严格字段处理](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html) 与 [SVG 2 viewBox](https://www.w3.org/TR/SVG2/coords.html#ViewBoxAttribute)；数值范围仍由 `SyncJson.validateWhiteboard()` 在收发两端校验。

白板扩展仍使用内部文档的 `schemaVersion: 1`。写出 JSON 时使用 `@EncodeDefault(NEVER)` 省略默认的 `whiteboard: null`，因此文字笔记的内部格式保持不变。旧客户端采用严格未知字段校验，会拒绝含白板的 JSON 备份，避免重写时丢弃笔画；所有同步设备均须升级到支持加密的版本。JSON 备份保留可编辑笔画；Markdown ZIP 导出同目录 SVG 及相对图片引用，不包含完整同步修订信息。

2026-10-20 白板图片图层扩展：`whiteboard.images` 为有序列表，每个元素包含 `id`、`attachmentId`、`x`、`y`、`width`、`height`。图片引用本笔记 `attachments` 中的图片附件，坐标与笔画共用 `1000×2000` 世界空间，先绘制所有图片，再绘制笔画；擦除笔画不删除图片。每块白板最多 20 个图层，图层编号必须唯一，坐标及尺寸须为有限数，尺寸为正且整个矩形在画布内，不允许悬空或非图片附件引用。原文件继续使用现有附件校验、大小上限、加密同步和 JSON 备份。空列表用 `@EncodeDefault(NEVER)` 省略，旧笔画文档格式不变；内部版本及加密信封不变，旧客户端会严格拒绝含 `images` 的快照，所有同步设备须先更新到支持图片图层的版本。

本地与远程白板图片使用同一图层格式；远程图片插入时通过 HTTPS 下载为附件，之后不依赖原 URL。便签远程图片仍是正文里的 Markdown URL，不新增附件或协议字段。Markdown ZIP 中白板 SVG 以 data URL 内嵌经过 EXIF 方向校正、最长边不超过 2048 像素的 PNG，以便独立打开并保留图片与笔迹的位置；原始附件同时保留在 ZIP 中。撤销／清空移除的图片仅在当前草稿保留用于重做，保存时只保留仍被白板或 Markdown 引用的托管附件。

每笔还可包含 `brush`：`FINELINER`（默认，写出时省略）、`WATERCOLOR` 或 `MARKER`。水彩用宽度系数 1、0.8、0.55 和透明度 0.12、0.14、0.18 的三层圆帽笔迹呈现柔边叠色；马克笔使用 0.7 透明度、方形笔帽和斜角连接，单点为正方形。屏幕、卡片预览和 SVG 采用同一组参数，原始颜色字段仍是上述不透明 ARGB。界面提供 1、3、6、10、16、24、32、40 八档，不改变原有宽度范围。

2026-10-21 白板文字扩展：可选 `whiteboard.texts` 为有序列表，绘制在图片和笔画上方。每段文字包含 `id`、`text`、左上角 `x/y`、文本框 `width`、`fontSize` 和不透明 ARGB `color`，全部使用白板世界坐标。每张白板最多 100 段，每段最多 2,000 个 UTF-16 字符；内容不能全为空白，显式换行使用 `\n`，不允许其他控制字符、无效代理项或 XML 禁用字符。字号为 `12..96`，位置、宽度、字号必须有限，文本框宽度为正，高度按行数乘以字号乘以 `1.25` 计算，整个框须位于画布内。编号在同一白板内唯一，颜色在创建或编辑文字时取当前画笔颜色。文字可移动、编辑、删除和撤销，橡皮只处理笔迹。

文字空列表使用 `@EncodeDefault(NEVER)` 省略，既有白板和普通文字笔记格式不变；内部文档仍为版本 1，加密信封仍为版本 2。旧客户端会严格拒绝带 `texts` 的同步快照或备份，使用此功能前所有同步设备须升级到支持文字图层的版本。JSON 备份保留可编辑文字；SVG 用 `text/tspan`、显式行位置及 XML 转义保留多行文本。复核依据：[kotlinx.serialization 未知字段处理](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html)、[SVG 2 text/tspan](https://www.w3.org/TR/SVG2/text.html#TextElement)。图形、圆规画圆和量角器绘制的角仍保存为普通笔画；辅助线、圆规与量角器的可调节覆盖层不保存、不参与同步或 SVG 导出。

附件放在笔记的可选 `attachments` 数组中，默认空数组写出时省略。每项包含：

| 字段 | 类型与约束 |
| --- | --- |
| `id` | 小写 UUID，同一笔记内不能重复 |
| `fileName` | 1–255 字符，不能全为空白、包含控制字符或路径分隔符 |
| `mimeType` | 最多 127 字符的 `type/subtype` |
| `sizeBytes` | 原始字节数，0 到 5 MiB |
| `sha256` | 原始文件的 SHA-256，小写 64 位十六进制 |
| `dataBase64` | 原始文件字节的标准 RFC 4648 Base64，含规范填充，可表示空文件 |

每条笔记最多 20 个附件，原始内容合计最多 8 MiB。接收和发送均验证长度、Base64 规范编码及 SHA-256；附件内容在整个 AES-GCM 快照内认证加密，文件名、类型和摘要不会额外发送给服务端。总快照仍限制为 16 MiB，附件 Base64 膨胀后的大小也计入限制。本机添加附件时检查合并后的总快照，超限不写入数据库并保留编辑草稿。没有额外的远端附件对象或需要单独清理的云端文件。

附件列表参与用户内容比较、修订和冲突副本；回收站与恢复保留完整文件，永久删除记录清空附件。JSON 备份保留整个数组；Markdown ZIP 以独立文件和相对链接导出。本机在私有目录按摘要存储文件，Room 行和编辑器保存状态只存元数据，避免大文件超过 SQLite CursorWindow 或 Android saved state 的大小限制；数据库不应单独拷贝作为备份，应使用应用内导出。通过 FileProvider 的限定缓存目录和临时只读授权打开附件，不暴露整个私有目录。

附件和笔型扩展仍沿用内部文档版本 1、加密信封版本 2；缺失新字段时读取为原有默认值。严格拒绝未知字段的旧客户端会停止同步或导入，不会悄悄丢弃附件、改变笔型；使用新功能后所有设备必须升级。

快照最多 16 MiB UTF-8、10,000 条记录（包含回收站和永久删除标记），单条正文最多 100,000 个 Kotlin UTF-16 字符，标题最多 500 个字符，版本历史最多 100,000 个编号。正文和标题与编辑器限制一致，防止导入无法继续编辑的笔记。超限时停止写入，不静默裁切。重复笔记 ID、缺失身份/时间字段、未知版本或字段、无效 UTF-8、循环版本历史和同一版本号下不同内容都会被拒绝。未知字段失败是刻意设计，避免旧客户端重写并抹掉新版本数据。

笔记按 ID 排序，历史编号按字典序排序，使相同快照产生稳定的明文 JSON；随机 nonce 使同一快照再次加密时产生不同密文。版本号与设备 ID 不存储密码。备份导入和已认证解密的 HTTP 内容共用 `SyncJson.decodeDocument()` / `validate()`，不可绕过校验直接信任 `Json.decodeFromString()`。远端加密信封不能直接用作手动 JSON 备份导入。

## 版本、冲突与删除

创建笔记和每次用户修改产生 UUID 版本号。修改必须从用户实际编辑的版本调用 `Note.copy(...).edited(deviceId, now)`；`parentRevision` 记录基础版本，`ancestorRevisions` 保存所有已包含的祖先/已合并版本。多次离线修改仍能识别较旧远端，不靠设备时间判断因果。时间仅在真正并发的版本之间用于稳定排序。

合并规则如下：

1. 单侧存在的记录保留；“某条记录没有出现在快照里”不表示删除。
2. 同一版本的正文等字段必须相同；其历史集合可以合并。
3. 一方包含另一方的版本历史时，保留其后继版本。
4. 真正并发且用户数据不同的版本保留两份。原 ID 的保留顺序为永久删除标记、软删除标记、更新时间、版本号；另一份产生确定性的冲突副本 ID。副本保留文字、白板、颜色等数据，设置 `conflictOf` / `conflictRevision`，清除提醒时间以避免无意重复提醒。白板作为整块笔记内容参与比较和冲突保留，不自动拼接不同设备的笔画。界面显示“冲突副本”，用户可以编辑、归档或删除它。
5. 合并将已经保存到副本的版本加入主记录历史。相同并发版本重试时复用确定性 ID，不反复创建副本。用户删除了冲突副本，其删除版本也会继续保留。

软删除只设置 `deletedAt` 并产生新版本；回收站内容仍在快照中。恢复笔记是删除版本的新后继。永久删除设置 `isPurged=true`、清空标题和正文、将白板设为 `null`、清空提醒，同时保留 ID 与版本历史。**当前不会自动清理删除标记**，否则长期离线设备上的旧笔记可能重新出现。若在一台设备删除的同时另一台设备离线修改，主记录保持删除状态，并保留对方修改的冲突副本，供用户确认。

永久删除不会擦除存储提供商已有的对象版本、备份或还未同步的离线设备。JSON 中仅保留已清空文字与白板的标记；存储服务历史版本由用户按其备份策略管理。

## 网络事务

1. 从当前本地笔记取得快照，GET 远端对象。仅 404 表示尚未创建；401/403、损坏响应等均停止。
2. 对 200 响应验证强 ETag，并解析、认证解密及验证信封内容。缺失 ETag、未带引号或 `W/` 弱 ETag 均不能用于安全写入，因此停止。GET 使用 `Cache-Control: no-cache` 和 `Accept-Encoding: identity`。
3. 合并本地与已认证的远端版本。已存在的远端解密后与本地相同时不 PUT；尚无远端时，即使没有笔记也创建加密快照，以确立该目标使用的恢复密钥。随机加密不会引起无内容变化的重复上传。
4. 将合并后的完整快照加密，更新现存对象使用 `If-Match: "<读取到的 ETag>"`；首次创建使用 `If-None-Match: *`。从不退回无条件覆盖或明文上传。
5. 仅 PUT 的 200 / 201 / 204 表示成功。409 / 412 重新 GET、合并并重试；S3 中已存在对象在条件 PUT 时返回 404 也按并发删除重试。最多尝试四次，并保留每次合并的记录，避免重试途中丢失刚见到的远端内容。
6. 成功后，应用仓库在与用户编辑相同的互斥锁内，合并**最新本地笔记**和 `SyncResult.notes` 后原子持久化。不能直接用网络开始时的快照替换本地文件。

示意集成：

```kotlin
val before = repository.snapshot()
val encryptionKey = SyncEncryptionKey.parse(recoveryKey)
val result = SyncEngine(config, encryptionKey = encryptionKey).sync(before)
repository.updateAtomically { latest ->
    MergeEngine.merge(latest, result.notes).notes
}
```

`updateAtomically` 是仓库需实现的事务语义示意，不是核心模块接口。网络期间的本地新编辑会保留，并在下一次同步上传；后台调度与“立即同步”由 Android 层管理。不同同步任务应由仓库串行执行。仅在远端返回响应成功后才标记同步完成；网络中断导致写入结果不明时，下次同步重新读取再合并，不能假定远端未收到请求。

读取响应在 OkHttp 的工作线程进行；协程取消时会取消对应 HTTP Call，读取和关闭响应也受此取消影响。连接超时 15 秒，单次调用最多 45 秒；正文读取/写入超时 30 秒。大文件流式检查长度，超过上限即停止接收。远端服务必须遵守原子条件写入语义；客户端无法仅通过只读操作证明有缺陷的服务器正确实现该语义。

“测试连接”只执行 GET 并解密、验证已存在的文件，不创建或修改对象。404 返回“尚无同步文件”；这只能证明此地址返回了缺失响应，不能证明父目录存在、拥有写权限或条件写入可用，也不能验证该恢复密钥与其他设备一致。实际首次同步才能检验写入权限。

## S3 签名

使用 AWS SigV4 的 `AWS4-HMAC-SHA256`，服务固定为 `s3`。发送实际请求正文（加密信封）的 SHA-256（不使用 `UNSIGNED-PAYLOAD`），签名覆盖主机、日期、内容摘要、条件头、显式 Content-Type 与 session token（如有）。S3 对象路径只编码一次，保留路径分隔符，空格使用 `%20`，Unicode 按 UTF-8 字节编码；端口纳入 Host 签名。当前配置不允许 endpoint 查询参数，但签名帮助函数支持排序和重复查询参数。

HTTPS 继续保护服务凭据、请求和响应。同步使用标准 HTTP，不依赖浏览器，因此不要求为 Android 原生客户端配置 CORS。

## 安全边界与恢复

存储服务只收到密文，仍能观察对象名、长度、更新时间和访问模式。认证加密检测内容篡改，但不提供消息新鲜度或设备身份认证；拥有同一恢复密钥的设备都可以生成有效密文。服务器可以拒绝服务、删除对象、重放过去的有效密文或为设备展示不同历史。修订历史和强 ETag 处理正常并发，不能证明恶意服务器未回滚。没有自动密钥轮换、前向保密或单设备密钥撤销机制。

恢复密钥需在服务器之外单独安全保存。若密钥和所有已配置设备均丢失，无法解密远端；服务密码、API 密钥和维护者不能恢复它。直接改填另一密钥会导致原目标认证失败，不会自动重新加密原对象。更换密钥需先取得完整本地数据，再上传到新空目标并更新其他设备配置。旧密钥仍能解密用它加密的旧快照；旧明文、服务端版本历史和已导出文件由用户自行处理。

本地数据库、明文手动备份、通知以及已解锁或被攻陷的设备不在此保护范围内。详细说明见 [安全与隐私](../SECURITY.md)。

## 测试与依据

JVM 测试覆盖多次离线编辑、时钟偏差、多个设备的冲突与收敛、删除/恢复/永久删除、网络期间的新编辑、严格格式校验、AWS 已公布的 S3 GET 签名向量，以及 MockWebServer 上的 WebDAV/S3 真实 HTTP 请求、条件写入重试、认证失败、无效 ETag、重定向阻断和取消。加密测试包括 AES-GCM 已知答案与独立 OpenSSL 信封向量、随机 nonce、完整白板及修订信息往返、错误密钥、篡改、AAD 不匹配、拒绝明文和歧义信封、规范 Base64、16 MiB 边界，以及 HTTP 上错误数据不触发覆盖、加密正文的 S3 签名和首次空快照创建。模拟测试不等同于已用用户真实 Nextcloud、AWS S3 或 R2 账号完成端到端联调。

实现时核对的文档（2026-10-17）：

- [RFC 9110 §13.1 条件请求](https://www.rfc-editor.org/rfc/rfc9110.html#name-preconditions)：强 ETag 与 `If-Match` / `If-None-Match`。
- [RFC 4918 §8.6 WebDAV ETag](https://www.rfc-editor.org/rfc/rfc4918.html#section-8.6)：分布式编辑避免覆盖的依据。
- [AWS 创建 SigV4 签名请求](https://docs.aws.amazon.com/IAM/latest/UserGuide/reference_sigv-create-signed-request.html)：规范请求、URI 编码、HMAC 密钥派生及签名头。
- [AWS S3 条件写入](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)：PUT 的条件头和 409 / 412 / 404 竞态语义。
- [AWS GetObject 权限](https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html)：缺失对象的 403 / 404 与 `s3:ListBucket` 权限的关系。
- [Cloudflare R2 S3 API 兼容性](https://developers.cloudflare.com/r2/api/s3/api/)：region=`auto`、条件 PutObject 原子性与支持表（页面更新于 2026-10-14）。
- [Cloudflare R2 API tokens](https://developers.cloudflare.com/r2/api/tokens/)：权限、S3 密钥和 jurisdiction endpoint。
- [AWS SDK JavaScript SigV4 测试](https://github.com/aws/aws-sdk-js/blob/master/test/signers/v4.spec.js)：S3 路径只编码一次的兼容性案例。
- [OkHttp 4.12 Call 接口](https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/Call.kt)：异步请求、取消及响应关闭语义。

白板扩展核对的文档（2026-10-18）：

- [kotlinx.serialization EncodeDefault](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/-encode-default/)：`NEVER` 覆盖全局 `encodeDefaults` 设置，省略默认空白板字段。
- [kotlinx.serialization ignoreUnknownKeys](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html)：严格拒绝未知字段，防止旧客户端静默丢失新内容。

端到端加密核对的官方文档（2026-10-18）：

- [Android 密码学建议](https://developer.android.com/privacy-and-security/cryptography)：推荐 AES/GCM/NoPadding 与 256 位密钥，使用合适的平台密码学实现。
- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)：设备密钥的不可导出属性，以及设备或进程被攻陷时的保护边界。
- [Java 17 Cipher](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/Cipher.html)：GCM 的 IV 唯一性要求、AAD 调用顺序和认证标签附加于密文的语义。
- [Java 17 GCMParameterSpec](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/javax/crypto/spec/GCMParameterSpec.html)：IV 与以位为单位的认证标签长度参数。
- [RFC 5116 §5.1–5.2](https://www.rfc-editor.org/rfc/rfc5116.html#section-5.2)：AEAD_AES_256_GCM 的 32 字节密钥、12 字节 nonce、16 字节标签及 nonce 复用风险；§1.2 明确 AEAD 不负责防重放。
- [RFC 4648 §3.5、§4、§5](https://www.rfc-editor.org/rfc/rfc4648.html#section-3.5)：规范 Base64 pad bits、标准及 URL-safe 字母表。

附件与笔型扩展核对的官方文档（2026-10-19）：

- [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files)：系统文件选择器与按 URI 读取选定文件；选取后复制完整内容，不依赖另一设备的本机 URI。
- [Android 安全共享文件](https://developer.android.com/training/secure-file-sharing/share-file)：FileProvider、限定目录、临时只读 URI 授权。
- [kotlinx.serialization EncodeDefault](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/-encode-default/) 与 [ignoreUnknownKeys](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json-builder/ignore-unknown-keys.html)：省略默认字段、严格拒绝不支持的数据。
- [SVG 2 绘制](https://www.w3.org/TR/SVG2/painting.html)：透明度、圆形／方形笔帽和斜角连接。
