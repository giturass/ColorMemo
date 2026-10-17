# 彩笺 · ColorMemo 0.1.3

支持 Android 8.0 及以上版本，可覆盖安装旧版本升级。

- 移除空白页的「写下第一条便笺」按钮，通过右下角「写一条」创建便笺。
- 移除编辑页的字数和 Markdown 状态文本。
- 移除编辑页底部的 Markdown 帮助入口。
- 移除侧边栏底部的文字说明。
- 调小 WebDAV、S3、R2 服务选择按钮的字号，文字单行显示，三个按钮尺寸一致。

下载 `ColorMemo-v0.1.3.apk` 安装。APK 由 GitHub Actions 构建，使用与 MDTerm Release 相同的签名证书。应用包名为 `dev.colormemo.app`，可与 MDTerm 同时安装。

签名证书 SHA-256：

```
4bea1b63b90233d46ed951d66bae2927440c595323234556da7d2038aec25e89
```

同步默认关闭，无需注册账号。各设备须配置同一私有 WebDAV 目录或 S3/R2 对象位置。当前仅支持文本笔记，单个同步文档最多 16 MiB；远端为可读 JSON，不提供端到端加密。Android 省电策略可能延迟提醒和后台同步。

首次使用可先创建笔记并设置提醒，再按 [README](https://github.com/giturass/ColorMemo#配置同步) 配置云端同步。已有备份建议先保留副本。
