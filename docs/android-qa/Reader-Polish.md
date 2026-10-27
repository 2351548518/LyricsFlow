# 阅读细节、账户卡片与悬浮窗修订

日期：2026-10-27（Asia/Shanghai）。依据用户提供的五张截图实现，并在 HONOR MLY-AN00 真机检查；未退出 GitHub、改绑歌曲或清理用户资料。

## 实际改动

- “寻找这首歌”：上方“加载更多候选”与第一张候选卡片新增 16 dp 留白，避免边框相接。
- “歌词与评论来源”：说明段与“搜索与手动匹配”、HTTPS 网关输入框与“保存来源设置”间距均为 16 dp。
- “我的”：账户卡片显示圆形 GitHub 头像；原副标题位置显示上次成功同步时间。未登录显示“数据保存在本地”；登录但未同步显示“上次同步：尚未同步”。移除原“属于你的歌词空间”和卡片底部独立说明。
- 头像通过 [GitHub 公开用户资料接口](https://docs.github.com/en/rest/users/users#get-a-user) 的 avatar_url 加载，仅接受官方 avatars.githubusercontent.com HTTPS 图片，不发送 Token、不跟随重定向。192×192 小图在 cacheDir 缓存 24 小时；响应上限 1 MB、解码尺寸受限；离线使用缓存，无缓存时保留人物图标。缓存不进入备份/同步。
- 当前播放组原文放大约 18%，译文约 10%，220 ms 平滑过渡；减少动态效果时直接切换。未知进度或没有时间戳不把所有行放大，同时间戳并行歌词一起强调；自由排版继续受分区尺寸上限约束。
- 悬浮窗原文与中文译文使用同一基础颜色；自定义主题采用原文色作为两者的颜色。两者共享主题时间轴的文字透明度。
- 悬浮背景不透明度范围 0%–100%；默认 85% 保留，读取、设置、绘制和本地/云端备份恢复均接受 0。仅背景变透明，文字不会跟随背景隐去；锁定穿透的系统整窗 alpha 限制保留。

## 真机检查

| 场景 | 结果 / 图片 |
| --- | --- |
| 登录账户头像与同步时间 | 真实账号 2351548518 的土星头像成功加载；时间显示 2026-10-27 22:26。[我的](./reader-polish/real-my.png) |
| 来源页间距 | 实际布局树：说明底部 y=939、入口顶部 y=995；输入框底部 y=2151、按钮顶部 y=2207，差值均为 56 px，即本机 16 dp。[来源](./reader-polish/real-sources.png) |
| 真实 QQ 搜索候选 | 搜索当前 Spotify 曲目，展示 59 条候选；按钮底部 y=1958、首卡顶部 y=2014，差值 56 px。[匹配](./reader-polish/real-search.png) |
| 当前行强调 | 真实 Spotify 曲目与 QQ 歌词显示，当前行明显大于相邻行且保持原左对齐。[阅读](./reader-polish/real-current-line.png) |
| 透明背景与同色译文 | 系统 Overlay Window 上原文和中文均为白色，背景 0%，设置栏显示 0%。检查前后用户设置均为 overlayAlpha=0、overlaySize=20.86852、overlayTranslation=true。[悬浮窗](./reader-polish/real-overlay-zero.png) |

截图已逐张查看。页面切换与播放由真实 App 完成，不用假平台评论或假 GitHub 头像作真实验收。搜索验证只读取候选，没有确认改绑当前歌曲。悬浮窗为现场用户已授权的窗口；没有更改系统锁屏/穿透安全规则。

## 自动化与构建

- Android CLI 编译主包/测试包、25 项 JVM 单测、Lint 通过：[构建](./reader-polish/build.log)。Lint 0 错误、38 警告：[明细](./reader-polish/lint.txt)。
- 手机模拟器 27 项本地设备回归通过：[日志](./reader-polish/emulator-tests.log)。包含原有播放、导入安全、主题、翻译、GitHub 功能与新增阅读测试。
- 真机新增 2 项 ReaderPolishTest 通过：[日志](./reader-polish/real-tests.log)。验证当前行字号更大、未知进度不全局放大；滑块可达 0/100、0 值重读持久化及备份/恢复不回弹到 20%。测试后恢复原显示偏好。
- 本轮没有重跑全套第三方网络测试或平板专项，相关历史记录不冒充本轮结果。真实 QQ 搜索与头像加载另以上述实际界面验证。

APK 已安装真机。SHA-256：`5114C3439E310636AFB043D323DE84D522E71B24BD7A0CD1D8A4F180DF6AE091`。最终头像缩放使用等价 AndroidX KTX 写法，重新编译/单测/Lint 后安装并完成真机视觉检查。

```powershell
# 27 项本地设备测试；加 -IncludeNetwork 为 41 项
.\scripts\Run-AndroidQa.ps1 -Device emulator-5554
# 真机新增测试，先安装主包与测试包
adb -s ABJR026916006401 shell am instrument -w -r -e disableAnalytics true -e class com.example.lyricsflow.ReaderPolishTest com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
```
