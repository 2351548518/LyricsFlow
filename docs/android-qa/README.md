# Android 开发版验收记录

2026-10-28 来源 UI：[横向单行来源、并排匹配操作及重复分页修复](./Source-Layout.md)。JVM 42 项、默认设备回归 64 项通过；已安装并检查真机两页。完整可选 81 项未全跑。

2026-10-28 切源与逐曲选择：[成功确认后保存、固定记录刷新、选择快照同步恢复](./Song-Selection.md)。JVM 42 项、默认设备回归 61 项、来源/自动匹配专项 6 项通过。完整可选 78 项未全跑；GitHub 数据往返使用内存假服务验证，未上传真实用户资料。

2026-10-28 LRCLIB 原文补充：[Sparkle 罗马音误选与旧缓存升级](./LRCLIB-Original.md)。JVM 42 项、默认设备回归 51 项、原文联网专项 2 项通过；完整可选 68 项联网回归未全跑。

2026-10-28 封面过渡更新：[中心清晰、四周柔化与页面渐变融合](./Artwork-Blend.md)。默认设备回归 47 项、JVM 40 项通过；保留下方各阶段历史记录。

2026-10-28 更新：[封面网状渐变与 AMLL](./Pear-Mesh-AMLL.md)。自动顺序为 QQ → 网易云 → AMLL → LRCLIB，默认设备回归现为 45 项，JVM 38 项；下文保留各阶段历史记录。

2026-10-28 补充：[LRCLIB 搜索/日文原文选择与滚动渲染](./LRCLIB-Rendering.md)。最新默认设备回归 38 项、JVM 29 项通过；下方历史数字保留为当时记录。

日期：2026-10-26。参考 [PRD](../LyricsFlow-PRD.md)、[原型](../../prototype/lyricsflow-web/README.md) 和 [实现说明](../Android-Implementation.md)。本记录区分已测试行为与仍未覆盖的发布要求。

2026-10-27 的配色、全宽封面、可调模糊、译文放大、刷新闪烁修复及在线翻译每日不限量，见 [最新 UI 专项记录](./Flow-UI.md)；下文保留首轮验收历史。

后续的按钮选中状态、开关、卡片与弹窗配色统一，见 [控件可读性专项](./Control-Readability.md)。

最新匹配/来源间距、GitHub 头像及同步时间、当前行放大、悬浮窗同色及 0% 背景修订，见 [阅读细节专项](./Reader-Polish.md)。模拟器 27 项回归、真机 2 项新增阅读测试通过，并完成真实头像/来源/搜索/悬浮窗视觉检查。

后续加入悬浮歌词颜色设置（预设、RGB、十六进制及备份恢复），真机 3 项专项、模拟器 28 项回归通过，见 [悬浮颜色专项](./Overlay-Text-Color.md)。

2026-10-27 新增 GitHub 登录与个人私有仓库手动快照：初始模拟器记录见 [GitHub 同步专项](./GitHub-Sync.md)。后续已在 HONOR 真机补测真实登录、私有仓库上传与下载预览，以及 12 项协议/界面/超时测试；最新记录见 [GitHub 真机验证](./Real-Device-GitHub.md)。当前 Android CLI 脚本默认 25 项设备测试，额外网络专项共 39 项。

## 环境

| 设备 | AVD | Android API | 屏幕 | 密度 |
| --- | --- | --- | --- | --- |
| 手机 `emulator-5554` | Medium_Phone_API_37.0 | 37 | 1080 × 2400 | 420 dpi |
| 平板 `emulator-5556` | Medium_Tablet | 35 | 2560 × 1600 | 320 dpi |

使用 Android CLI 启动现有 AVD、构建、安装、运行、读取布局和截图。交互坐标来自布局树，截图逐张实际查看。手机与平板均已安装开发 APK。

## 构建与自动化证据

- 构建、JVM 单元测试、Lint：[构建日志](./build-final.log)。
- 时间轴／解析器／安全策略共 **10 项单测，0 失败**：[XML 结果](./unit-tests.xml)。包括多时间戳、偏移、YRC、增强 LRC、QRC、TTML、暂停／倍速／未知进度、XML 实体拒绝、5 分钟边界及中文／日文判定。
- Lint **0 错误，37 警告**：[明细](./lint-final.txt)。警告包括模板未用资源、可更新依赖与 API 用法提示，未隐藏检查或关闭失败规则。
- 两端均完成 **7 项设备集成测试**：系统 MediaSession、Compose 页面流、2 项导入／Schema 安全测试、QQ／网易云真实接口及备份验证。
- [手机集成日志](./integration-emulator-5554.log)、[平板集成日志](./integration-emulator-5556.log)。网络用例与本地核心用例分开，可用 [脚本](../../scripts/Run-AndroidQa.ps1) 复现。
- 最后的未知进度处理修复后，两端各 **4 项核心设备回归全部通过**：[手机](./instrumentation-emulator-5554.log)、[平板](./instrumentation-emulator-5556.log)。第三方提供者代码未随该修复改变。
- 按应用 UID 读取 crash 缓冲，未发现 LyricsFlow 崩溃条目：[手机](./lyricsflow-crashes-emulator-5554.log)、[平板](./lyricsflow-crashes-emulator-5556.log)。原始系统缓冲中的蓝牙／Hotword 系统进程异常没有算成本应用崩溃。

首轮开发交付 APK SHA-256：`978DCFE9171096AB30E555A0F6537808ED388582547E13BB2454E5BEEB48E59E`，大小 12,285,758 字节。当时的原生预览截图：[手机](./phone-final.png)、[平板](./tablet-final.png)。DeepSeek 适配后的新构建见文末补充记录。

设备测试使用受控系统 MediaSession 验证切歌、暂停、seek、倍速；并未伪装成已登录的 Spotify 客户端。生产音乐应用允许列表不含测试包。

## 手动使用流程

| 操作 | 结果／证据 |
| --- | --- |
| 未读取通知权限时启动 | 正确呈现等待与授权／搜索／预览入口；没有假装真实音乐正在播放 |
| 主动进入预览 | 自有测试歌词与模式标记可见；模拟进度仅在预览中出现 |
| 网易云 ID `186016` 读取 → 候选确认 → 歌词 → 评论 | 成功；[真实热评截图](./phone-netease-comments.png) |
| QQ MID `0039MnYb0qxYhV` 读取 → 候选确认 → 歌词 → 评论 | 成功；[最终 QQ 热评截图](./phone-qq-comments.png)、[布局](./phone-qq-comments.json) |
| 评论来源切换 | QQ／网易云独立选择；没有改变首页当前歌词的来源 |
| 个性化、返回、歌词库 | 仪器测试验证自由排版、深色、保存、返回上级和从歌词库打开；测试结束恢复原先显示偏好 |
| 平板双栏 | 封面尺寸限制修复后不再遮挡标题／工具区；[最终平板截图](./tablet-final.png) |
| 真实系统悬浮授权 | 从应用跳转系统授权列表，进入本应用并允许；返回主动开启 |
| Android 通知授权 | 弹出系统通知对话框，允许后显示前台服务通知 |
| 悬浮窗拖动与缩放 | 位置变化、宽度变化均在 UI 树核对；[悬浮截图](./phone-overlay.png) |
| 锁定穿透 | `NOT_TOUCHABLE`、窗口 alpha=0.75；覆盖首页“关于”按钮后，可穿透打开其对话框；[布局证据](./overlay-passthrough-confirmed.json) |
| 熄屏隐藏 | WindowManager 检查窗口 `mViewVisibility=0x8`，无可显示表面 |
| 暂停超过 5 分钟 → 恢复 | 真实等待后窗口隐藏；点击恢复预览，窗口恢复 `mViewVisibility=0x0`；精确 299999／300000 ms 边界另有单元测试 |
| 应用内解锁与关闭 | 在“播放与悬浮窗”页面解锁后句柄恢复，关闭开关可移除服务；[解锁布局](./overlay-unlocked.json) |

## 网络波动与修复记录

- QQ／网易云接口成功返回真实歌词、热评和最新评论；统计见 [手机日志](./providers-phone.log)、[平板日志](./providers-tablet.log)。
- 平板一次网易云请求出现 DNS `UnknownHostException`，完整失败记录保留在 [日志](./instrumentation-tablet-dns-failure.log)，同一版本重试成功。没有把该失败计作成功或用示例评论填充。
- 模板 Espresso 3.5.1 在 API 37 调用旧 InputManager 方法失败；按 [AndroidX Test 官方版本记录](https://developer.android.com/jetpack/androidx/releases/test) 更新 Espresso 3.7.0／JUnit 扩展 1.3.0 后通过。
- Android Harmony XML 解析器不支持桌面 Xerces 的部分 feature 名称；改用强制 DTD／实体预检、SAX 深度检查和拒绝外部实体的 resolver，Android 与 JVM 往返测试通过。
- 自动滚动曾误触发手动浏览状态，已改为检测用户拖动；平板封面溢出、重复旧 Snackbar 与评论刷新游标判断也已修复。

## 未完成的发布验收

以下项目仍不能由已有结果推导为通过：连续十首真实音乐及 60 分钟压力测试；不同实体机／厂商后台限制；Android 24–34、36；全部 TTML 方言；QQ 加密逐字解码；自建网关实例；整首长歌词及其他翻译服务；2 倍字体和完整 TalkBack 旅程；正式签名发布。Spotify／QQ／网易云真实播放器联调已补充，见文末真机记录。

本次也没有执行完整 PRD 44 条发布验收。可运行开发版与正式发布结论分开，工程差异见 [实现说明](../Android-Implementation.md)。

## DeepSeek 补充验证

取得产品负责人提供的测试凭证后，已完成真实 DeepSeek 手动翻译、自动翻译、中文跳过和缓存复用；收费调用共 2 次，专项 4 项测试通过。具体配置、截图与凭证清理记录见 [专项报告](./DeepSeek-Translation.md)。原有构建／集成日志保留为当时证据，更新构建另存为 `deepseek-build.log`。

DeepSeek 阶段 APK SHA-256：`8FBE427B675F56BC138CB2ADCA93473F54AE3D171694F25B6F6769A4FB99D50B`。该构建通过 10 项单测，Lint 0 错误／37 警告；手机原有 4 项核心回归通过，平板 7 项协议与核心回归通过，详见 [平板回归日志](./deepseek-tablet-regression.log)。平板没有再次调用收费翻译服务。

## 真机播放器补充验证（2026-10-27）

HONOR MLY-AN00 上的 Spotify、QQ 音乐和网易云音乐均通过真实本机播放会话、播放／暂停／seek／恢复与重连检查；Spotify 与网易云还通过时间歌词同步检查。修复了 QQ 蓝牙歌词覆盖曲名，以及暂停后跳回旧 Spotify 会话的问题。QQ 当前测试歌曲未自动匹配歌词，连接成功与歌词匹配分别记录。

完整日志、截图、复现脚本、未完成范围与最新 APK 哈希见 [真机专项报告](./Real-Device-Playback.md)。当前普通 QA 脚本增加 2 项元数据回归，默认 6 项、带网络 9 项；真机控制流程单独显式启动，不会随普通测试自动执行。

## QQ 译文与搜索补充（2026-10-27）

已改用有译文的 `GetPlayLyricInfo`，旧 QQ 缓存自动升级；完整搜索替代仅约 4 条的建议降级，支持 30 条分页、译名扩展与近似候选排序。先前未匹配的 QQ 日语歌已匹配，真机可显示 QQ 中文译文；Spotify 连接回归通过。

新增 5 项来源／缓存专项和 1 项搜索界面检查，当前脚本默认 6 项、带网络 **15 项**；最终 15 项全部通过，JVM **15 项**通过（业务 14 项＋模板 1 项），Lint 0 错误／37 警告。详细根因、接口参数、截图、失败记录与最新 APK 哈希见 [QQ 专项报告](./QQ-Lyrics-Search.md)。上方日志与哈希保留为各开发阶段证据。

## 切歌匹配与 LRCLIB 入口补充（2026-10-27）

修复合作歌手／括号别名、多专辑同录音判断、时长晚到后不重试、新歌只查 QQ 和匹配窗漏掉 LRCLIB 的问题。可自动尝试其余歌词源并显示换源或失败原因；优先来源会用于新曲目。Avid／ホロネス已验证，真机 LRCLIB 搜索 Avid 返回 20 条。

当前 JVM **21 项**、带网络设备测试 **20 项**；默认核心设备测试仍为 6 项。完整证据、截图、夹具修复与最新 APK 哈希见 [匹配专项报告](./Automatic-Matching.md)。

## 校准归零修复（2026-10-27）

修复元数据等待误调用同名校准方法、自动写入 300 ms 的问题；真机已有误写记录一次性归零，窗口实际显示 0 ms。专项及匹配回归 6 项通过，详细证据与当前 APK 哈希见 [校准记录](./Calibration-Zero.md)。QA 脚本现为默认 8 项、含网络 22 项，本轮只执行上述专项回归。
