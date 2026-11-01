# 全屏歌词验证 · 2026-11-01

## 行为

- 轻点首页歌词区域，收起收藏、来源、操作栏、进度与底部导航，只保留歌名、歌手和歌词。
- 同一 Home / LyricStage 实例向下扩展，继续使用既有封面柔化、动态渐变、自定义背景与歌词主题。
- 再次轻点歌词或系统返回退出；约 380 ms 的展开与返回动画，减少动态效果设置下立即切换。
- 不改变播放状态、译文开关或歌词选择；支持手动滚动、恢复跟随、切歌和页面状态恢复。
- 轻点手势不消费触摸事件，并过滤超过滑动阈值的手势；内部“回到当前歌词”按钮优先处理点击。

## 专项验证

模拟器 API 37：5 个 `FullscreenLyricsTest` 用例通过：

1. 展开后歌词区域增高超过 100 dp，歌名顶部位置保持；操作栏、收藏、底栏隐藏，背景仍存在，返回后恢复。
2. 真实触摸滑动不触发展开或退出，真实轻点可进入与返回。
3. 保存/恢复页面状态后仍在全屏；切换曲目后标题更新，全屏模式保持。
4. 自由排版与减少动态效果设置下能正常展开和返回。
5. 展开、返回的中间帧高度介于起止高度之间，返回后高度与原布局一致。

现有 `FlowAppearanceTest#fluidHomeAndAppearanceJourney` 同时通过，确认正常首页排版、主题背景和设置流程没有回归。

真机 MLY-AN00 已覆盖安装并验证：歌词区域由 `[84,457][1152,1691]` 扩展为 `[84,457][1152,2636]`，歌名和歌手位置不变，底部控件隐藏。展开时沿用现有歌词锚点与滚动组件。本轮全屏验证构建 APK SHA-256：`7781cff5d6ea72bc5ecc8c551e64bb3e1e91eb4b7dd0b205e51058cc6ca636ef`。截图：[real-expanded.png](./fullscreen-lyrics/real-expanded.png)。

```powershell
android build :app:assembleDebug -- :app:assembleDebugAndroidTest --console=plain
adb -s emulator-5554 shell am instrument -w -e class 'com.example.lyricsflow.FullscreenLyricsTest,com.example.lyricsflow.FlowAppearanceTest#fluidHomeAndAppearanceJourney' com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
```
