# v1.0.5 正式包验证

日期：2026-11-02。包名 `com.example.lyricsflow`，`versionName = 1.0.5`，`versionCode = 6`，Android 7.0（API 24）及以上。

## 构建与签名

- R8 正式包、47 项 JVM 测试、Release Lint 通过；Lint 0 错误、36 项警告。未禁用检查。
- APK 非 debuggable，沿用首次正式发行的 RSA 4096 签名。公钥证书 SHA-256：`9a3ce1a6d40cd515ae7870ed9dcebf477bb464dee3e76316ccee9ab827104950`。
- APK 大小 1,730,748 字节，SHA-256：`31eeadb94406ebcfcc17064967d92e29621582110ab888d0c72044fccec7e17a`。
- [APK 元数据](./release-v1.0.5/apk-metadata.txt)、[签名校验](./release-v1.0.5/signature.txt)、[校验和](./release-v1.0.5/SHA256SUMS.txt)。私钥和密码未进入仓库或发行附件。

## 正式 APK 的安装与数据保留

独立 QA 模拟器 `emulator-5554`（Android 17／API 37，1080 × 2400）使用实际正式 APK 验证：

1. 安装已发布的 v1.0.4，预览并收藏自有示例 `Somewhere, softer`，确认本地歌词库有 8 行歌词。
2. 用 v1.0.5 正式签名包覆盖安装成功；停止进程并冷启动。收藏仍在，打开后显示正确的英文及中文歌词。
3. 在同一独立模拟器清除测试安装后，全新安装 v1.0.5。冷启动、进入双语预览、保存到本地歌词库均成功。

[跨版本验证结果](./release-v1.0.5/upgrade-result.json)、[全新安装结果](./release-v1.0.5/fresh-result.json)。截图与 UI 树保存在忽略提交的 `dist/v1.0.5/`，已实际查看正式包阅读界面。

功能代码在前一阶段已完成 115 项模拟器回归、15 项真机受控测试、5 项真实联网测试，以及候选分秒格式的真机视觉核验。本次发行只更新版本信息，没有将上述历史测试计作 v1.0.5 的全量重跑。详见[网络及内存](./Network-Memory-Audit.md)和[候选时长](./Candidate-Duration.md)。

本次发行验证期间真机未连接；v1.0.5 正式包的安装、冷启动与跨版本升级在独立模拟器完成。真机上前次核验的是相同功能代码、版本号尚为 1.0.4 的本地包。
