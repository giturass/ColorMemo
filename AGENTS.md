# ColorMemo agent rules

- 本项目面向 Android，当前维护环境为 Termux；使用兼容 Termux 的命令。
- 未经用户明确允许，禁止在本机构建任何 APK，包括 Debug 和 Release。APK 构建使用已获授权的 GitHub Actions 工作流。
- 本机可执行不生成 APK 的源码检查、Lint 和单元测试。不要将 `assemble`、`bundle`、`install`、`build` 或 APK 打包任务加入本机验证命令。
- 临时下载、日志和缓存放在 `~/tmp`；使用 `tools/verify-termux.py` 可完成无 APK 验证。
- 修改平台、同步协议或发布流程时核对官方文档。不得提交 keystore、密码、访问令牌或私人同步配置。
- 正式发行必须保留既定签名证书；详细流程和公开指纹见 `docs/releasing.md`。
