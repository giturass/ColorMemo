# 贡献指南

欢迎提交修复、无障碍改进、翻译和功能建议。提交问题时附上 Android 版本、应用版本与复现步骤，不附带服务器凭据、完整同步文件或私人笔记。

项目使用 Kotlin 官方代码风格，UI 与后台工作通过应用状态及仓库接口连接。涉及数据修改时，应在 NoteRepository 的事务中操作，并通过 `Note.edited()` 生成新修订。同步返回的快照必须与最新本地数据再次合并；不能直接替换本地数据库。

提交前运行：

```sh
./gradlew -PcoreOnly=true :core:test
./gradlew :app:testDebugUnitTest :app:lintDebug
```

为协议、并发、删除语义或提醒规则的变化补充有实际行为断言的测试。不要为了视觉或文案修改添加镜像实现的测试。修改同步文件格式时提高协议版本并提供迁移策略；不要允许旧客户端静默丢弃未知字段。

源码和问题跟踪托管在 [giturass/ColorMemo](https://github.com/giturass/ColorMemo)，正式签名 APK 由 GitHub Actions 发布。贡献者不需要签名密钥，常规 CI 仅运行测试和 Lint。未经使用者明确允许，勿在其本机执行 APK 构建；维护者流程见 [发布说明](docs/releasing.md)。请勿在应用中添加分析 SDK、广告、明文网络凭据或忽略 TLS 错误的逻辑。
