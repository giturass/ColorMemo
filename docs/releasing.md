# 发布

正式 APK 仅通过 [Release APK](https://github.com/giturass/ColorMemo/actions/workflows/release.yml) 工作流构建和发布。未经使用者明确允许，不在其本机执行 APK 构建；源码检查和 JVM 测试不生成 APK。

## 维护者流程

1. 更新 `app/build.gradle.kts` 的 `versionName` 和递增的 `versionCode`，更新 `docs/release-notes.md`。
2. 提交并推送到 `main`，确认 Android checks 通过。
3. 创建并推送与 `versionName` 对应的版本标签，如 `v0.1.0`。推送标签自动触发 Release APK。
4. 工作流在 GitHub 执行单元测试、Release Lint 和 R8 构建，检查包名、最低/目标 SDK、版本、不可调试属性及签名证书，再上传 APK、文件校验和与公开证书指纹到 GitHub Release。

需要重试时，在 Actions 手动运行工作流，并将 `release_tag` 填为已有标签。始终构建该标签指向的提交；已有 Release 的附件会被替换。修改构建代码后使用新的版本标签，不移动已经发布的标签。

## 签名

`giturass/ColorMemo` 的正式发行沿用 **MDTerm Release** 的固定签名身份。工作流使用以下仓库 Actions secrets，GitHub 只保存加密后的值：

| Secret | 内容 |
| --- | --- |
| `MDTERM_RELEASE_KEYSTORE_BASE64` | PKCS12 签名文件的 Base64 |
| `MDTERM_RELEASE_STORE_PASSWORD` | 签名文件密码 |
| `MDTERM_RELEASE_KEY_ALIAS` | 签名密钥别名 |
| `MDTERM_RELEASE_KEY_PASSWORD` | 签名密钥密码 |

签名文件只在临时 runner 中恢复，工作流结束时清理；不写入仓库或 artifact。Gradle 使用 `-PrequireReleaseSigning=true`，任一签名变量缺失即失败，不发布未签名 APK。

公开证书 SHA-256：

```
4bea1b63b90233d46ed951d66bae2927440c595323234556da7d2038aec25e89
```

此值已与 [MDTerm Release Actions 38974046018](https://github.com/giturass/MDTerm/actions/runs/38974046018) 及已发布 MDTerm 1.3 的证书核对。工作流会强制核验这一指纹。MDTerm 的公开开发 keystore 不是此签名身份。

Fork 维护者应配置自己的私有签名文件，并修改工作流的预期证书指纹；不能获得原项目的私钥。常规 PR 和 main 分支 CI 只运行源码检查，不需要签名 secrets。

## 下载核验

Release 中的 `.apk.sha256` 是 **APK 文件内容**的 SHA-256，可用 `sha256sum -c ColorMemo-v0.1.0.apk.sha256` 验证下载完整性。

使用 Android SDK 的 `apksigner verify --verbose --print-certs ColorMemo-v0.1.0.apk` 核对 **签名证书**的 SHA-256。文件校验和与证书指纹用途不同。上述操作只读取下载的 APK，不执行构建。

官方接口依据：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[APK Analyzer 命令行](https://developer.android.com/tools/apkanalyzer)、[apksigner](https://developer.android.com/tools/apksigner)、[GitHub Actions secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)、[GitHub CLI Release](https://cli.github.com/manual/gh_release_create)。
