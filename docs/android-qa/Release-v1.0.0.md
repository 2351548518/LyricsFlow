# v1.0.0 正式包验证

日期：2026-10-28。首个正式版，`com.example.lyricsflow`，versionCode 1，versionName 1.0.0，minSdk 24，targetSdk 37。正式签名与此前调试签名独立；未卸载或清除真机的现有应用与数据。

## 构建与签名

- [Release 构建日志](./release-v1.0.0/build.log)：R8 优化、正式签名、42 项默认 JVM 单测和 lintRelease 通过。
- [Release Lint](./release-v1.0.0/lint.txt)：0 errors，33 warnings。
- APK 大小 1,765,212 字节（约 1.68 MiB），[元数据](./release-v1.0.0/apk-metadata.txt) 中没有 application-debuggable。
- [签名校验](./release-v1.0.0/signature.txt)：apksigner verify 通过，APK Signature Scheme v2，RSA 4096 位。
- 公钥证书 SHA-256：`9a3ce1a6d40cd515ae7870ed9dcebf477bb464dee3e76316ccee9ab827104950`。
- APK SHA-256：`810f2961d885841cfd1c047ae027dbcf21f36a0601ddbcde215088fafe649f4a`，见 [校验和](./release-v1.0.0/SHA256SUMS.txt)。

正式私钥保存在本机用户目录，未进入 Git 或发行附件。发行的 `.cer` 仅含公钥。

## 正式包交互结果

设备：独立 `emulator-5554`，API 37，1080 × 2400。以下操作使用实际 R8 压缩后的正式 APK，没有替换为 debug APK，也没有通过反射或 run-as 修改其数据。

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 首次安装和冷启动 | 安装成功，欢迎页正常，未闪退 | first-launch.json |
| 预览与原文/译文显示 | 原创预览歌词与中文正确显示 | preview.json |
| 保存歌词 | 本地库出现 Somewhere, softer，8 行 | library-before-update.json |
| 字号设置 | 通过界面设为 27 sp | appearance-set.json |
| 同签名覆盖安装、停止进程再冷启动 | adb install -r 成功；收藏与 27 sp 均保留 | reinstall.log、library-after-update.json、appearance-after-update.json |
| 版本展示 | 关于页正确显示 1.0.0，不再标为开发版 | about.json |
| 网络歌曲信息 | 网易云手动输入 ID 186016，返回晴天／周杰伦／叶惠美 | net-resolve-result.json |
| 网络歌词 | 选中记录后读取真实歌词，浏览模式无音频播放 | net-lyrics.json |
| 网络热评 | 真实热门评论、作者、时间、赞数显示正常 | [评论截图](./release-v1.0.0/release-comments.png)、net-comments.json |
| 安装图标 | 系统应用详情中显示淡蓝渐变、三条等长波线的自适应图标 | [图标截图](./release-v1.0.0/release-icon.png) |

原始布局保存在 [release-v1.0.0](./release-v1.0.0/) 目录。覆盖验证使用同一个 1.0.0 APK，确认签名一致时重装和进程重启保留数据；没有把它描述为不同 versionCode 的跨版本升级测试。后续发行需递增 versionCode 并沿用相同签名。

此前完整的 70 项设备回归及真机来源切换、刷新、悬浮窗测试见 [代码审查报告](./Code-Audit.md)。本次发布主要变更为品牌、版本、签名与 Release 优化；没有声称重新完成全部 87 项联网测试或所有 Android 版本/厂商组合。正式 APK 的新签名不能直接覆盖用户真机上旧的调试签名，因此本轮没有为安装正式包而卸载真机应用。
