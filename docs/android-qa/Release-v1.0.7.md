# v1.0.7 正式包验证

日期：2026-11-04。包名 `com.example.lyricsflow`，`versionName = 1.0.7`，`versionCode = 8`，Android 7.0（API 24）及以上。

## 构建与签名

- R8 正式构建、47 项 JVM 测试通过；Debug/Release Lint 均为 0 错误、37 项警告，未禁用检查。
- APK 非 debuggable，沿用首次正式发行的 RSA 4096 签名；证书 SHA-256 为 `9a3ce1a6d40cd515ae7870ed9dcebf477bb464dee3e76316ccee9ab827104950`，已与公开证书 DER 文件哈希核对。
- APK 大小 1,747,132 字节，SHA-256：`68005a018d8022e3d150b0530169e07d2e2aea524bc16e4055cbc8021599882b`。
- [元数据](./release-v1.0.7/apk-metadata.txt)、[签名](./release-v1.0.7/signature.txt)、[校验和](./release-v1.0.7/SHA256SUMS.txt)。私钥及凭证保存在工作区外，未作为发行附件。

## 自动回归与功能范围

版本代码 8 的当前源码重新运行默认 133 项模拟器回归，全部通过，耗时 127.233 秒；[完整日志](./release-v1.0.7/instrumentation.log)。仪器测试使用 Debug 包，包含词组对应、模型响应容错、字号持久化及迁移、请求取消与去重、备份/恢复、GitHub 冲突检查、来源选择、封面恢复及阅读界面。

功能阶段另外完成 14 项真机备份/同步专项、真实模型受控样例解码和 90 条大备份性能记录。详见[词组对应](./Word-Alignment.md)、[独立字号](./Independent-Font-Sizes.md)、[代码审查](./Code-Audit-20261104.md)、[备份优化](./Backup-Codec-Optimization.md)。本次发行未重跑可选的外部歌词服务联网测试；GitHub 往返使用内存假服务，不上传用户实际快照。模型读音和词义质量取决于所配置服务。

## 正式 APK 安装验证

独立模拟器 `emulator-5554`（API 37，1080 × 2400），使用实际正式签名 R8 包：

1. 安装已发布 v1.0.6，收藏自有示例 `Somewhere, softer`，确认 8 行歌词。
2. 用 v1.0.7 覆盖安装，停止进程后冷启动，收藏仍在，英文/中文阅读正常。
3. 在同一独立测试模拟器全新安装 v1.0.7，冷启动、进入预览、保存及本地歌词库检查正常。

[升级结果](./release-v1.0.7/upgrade-result.json)、[全新安装结果](./release-v1.0.7/fresh-result.json)。截图与 UI 树保存在忽略提交的 `dist/v1.0.7/`，已实际查看。

HONOR MLY-AN00 真机从同签名的 1.0.6 本地功能版直接覆盖安装 v1.0.7，未卸载或清除用户数据。冷启动成功，包管理器核验版本号 1.0.7/code 8；实际截图可见 Spotify 曲目、封面及注音/原文/中文三层对应排版。没有通过真机操作发起云端上传。[真机结果](./release-v1.0.7/phone-result.json)。
