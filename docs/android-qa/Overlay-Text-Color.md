# 悬浮歌词颜色设置验收

日期：2026-10-27（Asia/Shanghai）。入口：**我的 → 播放与悬浮窗 → 悬浮歌词颜色**。

## 行为

- 支持“跟随主题”和白色、黑色、暖金、薄荷、天蓝、粉色六个预设；选中项有勾选，当前色值与色块可见。
- “自定义颜色”弹窗提供 RGB 三个滑块（0–255）、`#RRGGBB` 输入，以及浅/深两种背景下的原文和译文预览。支持不带 # 输入，保存时转为大写标准格式；不完整或非法值禁用“应用颜色”。取消不会覆盖原设置。
- 预设立即生效；自定义确认后生效。手动颜色 > 主题原文色 > 默认白色。原文与中文译文共用同一颜色；保留逐字高亮的未唱部分透明度、主题时间轴透明度和系统锁定窗口 alpha 限制。
- `Preferences.overlayColor` 用空字符串表示跟随主题，其余为 `#RRGGBB`。SharedPreferences 持久化，本地/云端复用备份字段。恢复前校验；旧包缺字段回退为跟随主题，不影响读取旧数据。
- 背景透明度仍独立支持 0%–100%。手动颜色不被自动对比度算法改写，用户通过两种背景预览判断对比度。

## 真机检查

HONOR MLY-AN00 / Android API 37：已安装最新 APK。现场已有黑色 `#000000` 选择，保留该选择；悬浮文字实际为黑色，背景 0%。打开并取消自定义弹窗，没有更改用户所选颜色。

- [颜色设置与黑色选中](./overlay-text-color/real-settings.png)
- [RGB 和十六进制弹窗](./overlay-text-color/real-picker.png)

截图逐张查看。悬浮窗为真实独立系统窗口，可能覆盖设置页上方；未为截图伪造颜色或关闭系统安全限制。

## 验证

- Android CLI 构建、测试包、JVM **28 项单测**通过，其中 OverlayColorsTest 新增 3 项：RGB 规范化、黑色不误判为默认值、非法/Alpha 输入拒绝、手动覆盖与主题回退。
- 手机模拟器 **28 项设备回归**通过：[日志](./overlay-text-color/emulator-tests.log)。
- 真机 ReaderPolishTest **3 项通过**：[日志](./overlay-text-color/real-tests.log)。新增验证自定义输入错误不能应用、有效色值规范化、重启保存、备份携带/恢复及坏颜色包拒绝。
- Lint **0 错误、38 警告**：[明细](./overlay-text-color/lint.txt)；[构建日志](./overlay-text-color/build.log)。
- 格式检查与 git diff --check 通过。本轮未重跑第三方歌词源网络全套或平板专项。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`D5573BE963294107E8B358DF7C501128DFC80A5A00FDCAE7A0BFAE4E799AF5B6`，已安装真机。

复现：`scripts/Run-AndroidQa.ps1 -Device emulator-5554` 默认 28 项设备测试，附带 `-IncludeNetwork` 为 42 项。
