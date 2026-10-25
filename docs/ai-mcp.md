# 本地 AI 笔记服务

彩笺可作为 Codex 等本机 AI agent 的 MCP server。打开侧栏「本地 AI 服务」，开启服务，复制本次访问令牌。地址固定为 `http://127.0.0.1:39451/mcp`，只允许当前设备上的客户端连接。

服务默认关闭，仅由应用前台的用户操作启动。运行期间显示前台服务通知，通知和设置页均可停止服务。移除应用任务、强制停止或进程退出会关闭服务，不会自动恢复；再次开启生成新的 256 位随机令牌。通知权限被拒绝时，Android 仍可能允许前台服务运行，可从应用设置页或系统运行中的应用列表停止。

## 笔记可见性

- 编辑页底部三点按钮右侧的「AI」眼睛按钮显示当前可见性，便签、白板和回收站中均可设置。为适应窄屏，「设置提醒」位于三点菜单中。
- 已有笔记、导入到新设备的笔记和手动新建的笔记默认 **AI 不可见**。新笔记可在保存前选择授权，保存成功后生效；AI 创建的新便签自动在当前设备获得授权。
- 已保存笔记的授权和隐藏立即写入数据库，不依赖正文保存；放弃正文草稿不会撤销权限操作。权限加载失败时，界面禁止授权和启动服务，并关闭正在运行的服务。
- 可见性是**当前设备的整条笔记权限**，包含标题、正文、白板和附件元数据。允许访问也允许编辑和管理；不提供只读令牌。可见性保存在独立 Room 表，不参与笔记修订、同步、JSON 备份或 Markdown 导出。其他设备必须单独授权；冲突副本默认不可见。
- 隐藏后，该 ID 的读取、修改、置顶、归档、移入回收站及恢复全部拒绝。搜索、分页和结果数量仅涉及已授权笔记，不披露隐藏笔记的标题、摘要、ID 或存在性。不存在与不可见笔记均返回 `Note unavailable.`。
- 服务每次操作都在与用户编辑、权限更改、同步、导入共用的锁和数据库事务中检查最新权限。旧草稿、旧版本号或先前读取过该 ID 不构成授权。AI 无权更改可见性；永久删除会撤销本机授权。
- 隐藏或停止服务不会撤回已经发给 AI 的数据，也不会回滚此前已经提交的操作。同步到已授权笔记的新内容继续属于该笔记的授权范围；本地隐藏不会影响用户自己的同步、提醒或导出。

## Codex 配置

将下列内容加入运行于同一台 Android 设备（例如 Termux）的 Codex 配置 `~/.codex/config.toml`：

```toml
[mcp_servers.colormemo]
url = "http://127.0.0.1:39451/mcp"
bearer_token_env_var = "COLORMEMO_MCP_TOKEN"
```

在启动 Codex 的终端中输入令牌。以下命令适用于 Termux 的 Bash；令牌通过隐藏输入提供，不进入命令历史：

```bash
read -r -s -p '彩笺本次访问令牌: ' COLORMEMO_MCP_TOKEN
printf '\n'
export COLORMEMO_MCP_TOKEN
codex
unset COLORMEMO_MCP_TOKEN
```

随后可让 agent「列出彩笺中 AI 可见的笔记」「新建一条采购清单」「读取刚创建的笔记并更新内容」。修改前须读取最新 `revision`，以 `expectedRevision` 提交；版本不匹配时重新读取，不会强制覆盖。更换令牌后须重启客户端，让它重新读取环境变量。

令牌只通过用户点击复制进入系统剪贴板，设置了敏感剪贴板标记；不会保存在应用设置、通知、状态恢复、备份或日志中。粘贴后请清理剪贴板。不要将令牌写入仓库、工单或聊天上下文。

此地址属于 Android 设备自身的网络回环接口。运行于远程服务器、云端或电脑上的 agent 不能直接访问它。本功能不自动配置转发或开放局域网端口。

## 工具

| 工具 | 参数与范围 |
| --- | --- |
| `list_notes` | `query` 搜索标题、正文及白板文字；`status` 为 `active`（默认）、`archived`、`trash`、`all`；`limit` 1–50，默认 20；`offset` 默认 0。返回按 ID 排序的可见笔记元数据和可选 `nextOffset`，并发增删时重新分页。 |
| `read_note` | `id`；返回标题、Markdown 正文、状态、当前版本、白板数据及附件元数据。 |
| `create_note` | `title`、`content`、`color`；标题或正文至少一项非空，创建文字便签。ID 由服务生成。 |
| `update_note` | `id`、`expectedRevision`，及至少一个 `title`、`content`、`color`；只能修改非回收站笔记，保留白板、附件和其他状态。 |
| `manage_note` | `id`、`expectedRevision`、`action`；支持 `pin`、`unpin`、`archive`、`unarchive`、`trash`、`restore`。回收站中只允许回收/恢复。 |

白板可读取并管理、修改标题与说明，暂不通过 MCP 绘制笔画或传输图片/附件文件。没有文件系统、任意 URL 请求、同步设置、备份、整库导出、永久删除、批量管理或权限工具。所有工具拒绝未知参数；标题最多 500 字符、正文最多 100,000 字符，写入检查现有同步文档的数量和大小上限。AI 修改沿用正常笔记修订、提醒协调和已配置的自动同步机制。

## 传输与安全边界

- 实现 MCP `2025-06-18` 的无状态 Streamable HTTP。初始化协商版本；不支持的版本选择 `2025-06-18`，由客户端决定是否继续。后续不支持的 `MCP-Protocol-Version` 返回 400。不声明支持需要 JSON-RPC 批处理的旧协议版本。
- POST 每次一个 UTF-8 JSON-RPC 消息，返回 `application/json`；通知返回空 202，通知形式的工具调用不执行。GET/DELETE 返回 405，不提供 SSE、旧 HTTP+SSE、会话、资源订阅、sampling 或 roots。
- 使用预共享 Bearer 令牌配对本机原生客户端，不实现 OAuth 登录流程。`initialize`、发现和调用均须携带认证；令牌不放在 URL 中。固定比较认证字节，不回显令牌或底层异常。
- 只绑定 IPv4 `127.0.0.1`；严格检查 `Host` 为带实际端口的 `127.0.0.1` / `localhost`。拒绝所有带 `Origin` 的浏览器请求，不提供 CORS，也不接受代理转发地址。不要把服务绑定到 `0.0.0.0` 或放在公开代理后。
- 仅接受 HTTP/1.1、明确的 Content-Length、JSON Content-Type 和 MCP 要求的 Accept 类型；拒绝重复请求头、chunked、压缩请求、非法 UTF-8 和过深 JSON。请求体上限 1 MiB、响应上限 2 MiB、请求头总计 16 KiB、每行 8 KiB；4 个处理线程和最多 4 个排队连接，每条连接总时限 10 秒。超时关闭连接并取消等待中的操作，写入前检查取消状态；响应禁止缓存，关闭连接后不复用。创建不是幂等操作，断线后应先检查笔记列表再决定是否重试。
- Android 服务不导出，不提供可让其他应用启动服务或修改权限的 Binder、广播或深层链接。现有 FileProvider 不增加任何访问范围。Android 私有应用沙箱继续隔离数据库与凭据。
- 本机回环网络不是应用身份验证；持有本次令牌的本机程序可以访问**所有已授权笔记**。此边界无法保护已被 root/调试权限读取的应用数据、用户明确共享的导出文件或 AI 已取得的副本。笔记内容属于不可信数据，MCP 初始化和工具说明提醒 agent 不得把笔记正文当作指令。

## 验证与官方依据

纯 JVM 测试覆盖实际 TCP/HTTP 初始化、发现、认证、Origin/Host、版本、请求限额、畸形输入、停止与令牌轮换；仓库测试覆盖默认隐藏、所有管理操作拒绝、即时撤权、旧草稿和同步冲突、版本冲突、分页及白板附件保留。Room v1→v2 只新增空授权表，保留笔记表，现有笔记默认不授权。

```sh
python3 tools/verify-termux.py --core-only
python3 tools/verify-termux.py --aapt2 /data/data/com.termux/files/usr/bin/aapt2
```

这些命令不生成 APK。Android 真机验收应检查后台切换、通知停止、任务移除、进程重启、令牌失效、窄屏按钮及 TalkBack；APK 由获授权的 GitHub Actions 工作流生成。

官方资料（2026-10-25 核对）：

- [MCP 2025-06-18 Transports](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports)：回环绑定、Origin、JSON 响应、通知、GET 405 与协议头。
- [Codex MCP Streamable HTTP 配置](https://learn.chatgpt.com/docs/extend/mcp#streamable-http-servers)：`url` 与 `bearer_token_env_var`。
- [Android foreground service types — specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types#special-use)：用户主动开启、持续提供本机 MCP 服务所用的前台服务类型、权限和用途声明；不使用有时限的数据同步服务类型。
