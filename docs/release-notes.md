# 彩笺 · ColorMemo 0.1.0

首个开源版本，支持 Android 8.0 及以上版本。

- 11 种颜色卡片、瀑布流/列表、置顶、搜索和颜色筛选。
- Markdown 编辑与预览、归档、回收站和恢复。
- 本地通知提醒、重启恢复，可选精确闹钟权限。
- WebDAV、Amazon S3、Cloudflare R2 同步；手动/自动同步、冲突副本和删除传播。
- Android Keystore 加密同步凭据；JSON 备份导入和 Markdown ZIP 导出。
- 浅色/深色主题，简体中文界面，Apache-2.0 许可证。

下载 `ColorMemo-v0.1.0.apk` 安装。APK 由 GitHub Actions 构建，使用与 MDTerm Release 相同的签名证书。应用包名为 `dev.colormemo.app`，可与 MDTerm 同时安装。

签名证书 SHA-256：

```
4bea1b63b90233d46ed951d66bae2927440c595323234556da7d2038aec25e89
```

同步默认关闭，无需注册账号。各设备须配置同一私有 WebDAV 目录或 S3/R2 对象位置。当前仅支持文本笔记，单个同步文档最多 16 MiB；远端为可读 JSON，不提供端到端加密。Android 省电策略可能延迟提醒和后台同步。

首次使用可先创建笔记并设置提醒，再按 [README](https://github.com/giturass/ColorMemo#配置同步) 配置云端同步。已有备份建议先保留副本。
