# Android 开发版验收记录

日期：2026-10-26。参考 [PRD](../LyricsFlow-PRD.md)、[原型](../../prototype/lyricsflow-web/README.md) 和 [实现说明](../Android-Implementation.md)。本记录区分已测试行为与仍未覆盖的发布要求。

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
