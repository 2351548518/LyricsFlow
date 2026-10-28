# v1.0.1 图标配色与覆盖升级

2026-10-28：按用户参考图将应用图标调整为明亮天蓝到蓝色的渐变，三条等长波线统一为浅冰蓝。同步 SVG、PNG、Android 自适应图标与各密度普通/圆形图标。功能代码没有变动。

- versionName 1.0.1，versionCode 2，包名不变。
- 正式签名公钥与 v1.0.0 完全一致，证书 SHA-256：`9a3ce1a6d40cd515ae7870ed9dcebf477bb464dee3e76316ccee9ab827104950`。
- R8 正式包构建、42 项 JVM 单测与 Release Lint 通过；Lint 0 错误、33 个现有警告。见 [构建日志](./release-v1.0.1/build.log)。
- APK SHA-256：`ef022d4b693fb3437d8442520f3619e261d5034f9aa4af6ac601079fd2c62c6a`，见 [校验和](./release-v1.0.1/SHA256SUMS.txt) 和 [签名验证](./release-v1.0.1/signature.txt)。
- 独立模拟器 emulator-5554（API 37）从正式版 1.0.0 直接 `adb install -r` 升级成功，再停止进程并冷启动。收藏歌词 Somewhere, softer（8 行）与 27 sp 字号均保留；布局和升级日志见 [原始记录](./release-v1.0.1/)。
- 系统应用详情中已实际查看 [新图标](./release-v1.0.1/icon.png)，天蓝渐变与浅色等长波线正确显示。

未卸载用户真机应用，未替换此前发布的 v1.0.0 APK。新版本仍使用原正式密钥，可供现有正式版用户覆盖升级。
