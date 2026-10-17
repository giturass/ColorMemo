# 彩笺同步协议 v1

彩笺使用自己的 JSON 快照在设备间同步记事，不调用 Google Keep 的私有接口。每次同步先读取远端，再合并，最后使用条件写入提交；不能安全合并或服务器拒绝条件写入时，保留本地数据并报告错误。核心实现位于 `core/`，不依赖 Android，可单独运行 JVM 测试。

## 存储位置与配置

| 后端 | 设置 | 远端文件 |
| --- | --- | --- |
| WebDAV | 已存在的目录 URL、用户名、应用密码 | `<目录>/colormemo-v1.json` |
| S3 | HTTPS endpoint、bucket、region、Access Key ID、Secret Access Key、可选 session token 与 prefix | `<endpoint>/<bucket>/<prefix>/colormemo-v1.json` |
| Cloudflare R2 | `https://<ACCOUNT_ID>.r2.cloudflarestorage.com`、bucket、region=`auto`、R2 的 S3 密钥 | 默认 `<bucket>/colormemo/colormemo-v1.json` |

WebDAV 目录应提前在服务端创建。例如 Nextcloud 的目录 URL 可以是 `https://cloud.example.com/remote.php/dav/files/alice/ColorMemo/`。不填公开分享页地址。用户名和密码填写在独立字段中；目录地址不能含凭据、查询参数或片段。当前使用 HTTP Basic 身份验证，不支持交互式网页登录、Digest 或 OAuth。

S3 使用 path-style 寻址；普通 AWS S3 建议填写对应区域的 endpoint，例如 `https://s3.ap-southeast-1.amazonaws.com`，region 填实际区域。自建兼容服务必须支持 `GetObject`、`PutObject`、强 ETag 及原子的 `If-Match` / `If-None-Match` 条件写入。本协议不使用 AWS S3 Express directory bucket、对象 ACL、multipart upload 或桶创建接口。

AWS S3 至少需要目标对象的 `s3:GetObject`、`s3:PutObject`。首次不存在的对象只有在具有桶的 `s3:ListBucket` 权限时才返回 404；否则 AWS 返回 403，彩笺会将其当作权限错误，绝不把拒绝访问当成空远端。可授予该权限，或先自行创建有效的空文档 `{"schemaVersion":1,"notes":[]}`。R2 在目标桶上选择 **Object Read & Write**，使用其生成的 Access Key ID 和 Secret Access Key，不是 Cloudflare 通用 API Bearer token。存在 jurisdiction 的 R2 桶需使用控制台对应的 endpoint（如 `.eu.r2.cloudflarestorage.com`）。

正式地址强制 HTTPS，系统正常校验证书；核心模块仅对 `localhost`、`127.0.0.1`、`::1` 允许 HTTP，供 JVM 测试使用，Android 应用清单进一步禁止所有明文 HTTP。客户端不跟随重定向，不将凭据转发到新地址，不忽略证书错误。配置对象的 `toString()` 会隐藏凭据；凭据校验仅返回固定的 `SyncConfigurationException` 信息，远端错误响应正文不会拼入可见错误信息。

## 文档格式

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

快照最多 16 MiB UTF-8、10,000 条记录（包含回收站和永久删除标记），单条正文最多 100,000 个 Kotlin UTF-16 字符，标题最多 500 个字符，版本历史最多 100,000 个编号。正文和标题与编辑器限制一致，防止导入无法继续编辑的笔记。超限时停止写入，不静默裁切。重复笔记 ID、缺失身份/时间字段、未知版本或字段、无效 UTF-8、循环版本历史和同一版本号下不同内容都会被拒绝。未知字段失败是刻意设计，避免旧客户端重写并抹掉新版本数据。

笔记按 ID 排序，历史编号按字典序排序，使相同快照产生稳定 JSON。版本号与设备 ID 不存储密码。备份导入和 HTTP 下载共用 `SyncJson.decodeDocument()` / `validate()`，不可绕过校验直接信任 `Json.decodeFromString()`。

## 版本、冲突与删除

创建笔记和每次用户修改产生 UUID 版本号。修改必须从用户实际编辑的版本调用 `Note.copy(...).edited(deviceId, now)`；`parentRevision` 记录基础版本，`ancestorRevisions` 保存所有已包含的祖先/已合并版本。多次离线修改仍能识别较旧远端，不靠设备时间判断因果。时间仅在真正并发的版本之间用于稳定排序。

合并规则如下：

1. 单侧存在的记录保留；“某条记录没有出现在快照里”不表示删除。
2. 同一版本的正文等字段必须相同；其历史集合可以合并。
3. 一方包含另一方的版本历史时，保留其后继版本。
4. 真正并发且用户数据不同的版本保留两份。原 ID 的保留顺序为永久删除标记、软删除标记、更新时间、版本号；另一份产生确定性的冲突副本 ID。副本保留文字、颜色等数据，设置 `conflictOf` / `conflictRevision`，清除提醒时间以避免无意重复提醒。界面显示“冲突副本”，用户可以编辑、归档或删除它。
5. 合并将已经保存到副本的版本加入主记录历史。相同并发版本重试时复用确定性 ID，不反复创建副本。用户删除了冲突副本，其删除版本也会继续保留。

软删除只设置 `deletedAt` 并产生新版本；回收站内容仍在快照中。恢复笔记是删除版本的新后继。永久删除设置 `isPurged=true`、清空标题和正文、清空提醒，同时保留 ID 与版本历史。**当前不会自动清理删除标记**，否则长期离线设备上的旧笔记可能重新出现。若在一台设备删除的同时另一台设备离线修改，主记录保持删除状态，并保留对方修改的冲突副本，供用户确认。

永久删除不会擦除存储提供商已有的对象版本、备份或还未同步的离线设备。JSON 中仅保留已清空正文的标记；存储服务历史版本由用户按其备份策略管理。

## 网络事务

1. 从当前本地笔记取得快照，GET 远端对象。仅 404 表示尚未创建；401/403、损坏响应等均停止。
2. 对 200 响应读取 JSON 并验证强 ETag。缺失 ETag、未带引号或 `W/` 弱 ETag 均不能用于安全写入，因此停止。GET 使用 `Cache-Control: no-cache` 和 `Accept-Encoding: identity`。
3. 合并本地与远端版本。相同时不 PUT；两端都为空时也不创建空对象。
4. 更新现存对象使用 `If-Match: "<读取到的 ETag>"`；首次创建使用 `If-None-Match: *`。从不退回无条件覆盖。
5. 仅 PUT 的 200 / 201 / 204 表示成功。409 / 412 重新 GET、合并并重试；S3 中已存在对象在条件 PUT 时返回 404 也按并发删除重试。最多尝试四次，并保留每次合并的记录，避免重试途中丢失刚见到的远端内容。
6. 成功后，应用仓库在与用户编辑相同的互斥锁内，合并**最新本地笔记**和 `SyncResult.notes` 后原子持久化。不能直接用网络开始时的快照替换本地文件。

示意集成：

```kotlin
val before = repository.snapshot()
val result = SyncEngine(config).sync(before)
repository.updateAtomically { latest ->
    MergeEngine.merge(latest, result.notes).notes
}
```

`updateAtomically` 是仓库需实现的事务语义示意，不是核心模块接口。网络期间的本地新编辑会保留，并在下一次同步上传；后台调度与“立即同步”由 Android 层管理。不同同步任务应由仓库串行执行。仅在远端返回响应成功后才标记同步完成；网络中断导致写入结果不明时，下次同步重新读取再合并，不能假定远端未收到请求。

读取响应在 OkHttp 的工作线程进行；协程取消时会取消对应 HTTP Call，读取和关闭响应也受此取消影响。连接超时 15 秒，单次调用最多 45 秒；正文读取/写入超时 30 秒。大文件流式检查长度，超过上限即停止接收。远端服务必须遵守原子条件写入语义；客户端无法仅通过只读操作证明有缺陷的服务器正确实现该语义。

“测试连接”只执行 GET 并验证已存在的文件，不创建或修改对象。404 返回“尚无同步文件”；这只能证明此地址返回了缺失响应，不能证明父目录存在、拥有写权限或条件写入可用。实际首次同步才能检验写入权限。

## S3 签名

使用 AWS SigV4 的 `AWS4-HMAC-SHA256`，服务固定为 `s3`。发送实际请求正文的 SHA-256（不使用 `UNSIGNED-PAYLOAD`），签名覆盖主机、日期、内容摘要、条件头、显式 Content-Type 与 session token（如有）。S3 对象路径只编码一次，保留路径分隔符，空格使用 `%20`，Unicode 按 UTF-8 字节编码；端口纳入 Host 签名。当前配置不允许 endpoint 查询参数，但签名帮助函数支持排序和重复查询参数。

同步文档在服务器上是明文 JSON，依靠 HTTPS 保护传输，并受存储服务的访问控制/磁盘加密保护。当前未实现应用层端到端加密，不适合把存储服务管理员不可读作为威胁模型。同步使用标准 HTTP，不依赖浏览器，因此不要求为 Android 原生客户端配置 CORS。

## 测试与依据

JVM 测试覆盖多次离线编辑、时钟偏差、多个设备的冲突与收敛、删除/恢复/永久删除、网络期间的新编辑、严格格式校验、AWS 已公布的 S3 GET 签名向量，以及 MockWebServer 上的 WebDAV/S3 真实 HTTP 请求、条件写入重试、认证失败、无效 ETag、重定向阻断和取消。模拟测试不等同于已用用户真实 Nextcloud、AWS S3 或 R2 账号完成端到端联调。

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
