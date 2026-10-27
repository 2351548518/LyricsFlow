# Android 实现与运行说明

核实日期：2026-10-27。工程包名 `com.example.lyricsflow`；`minSdk 24`、`targetSdk / compileSdk 37`。这是可运行的原生 Android 开发版本，正式发布仍以 PRD 第 23 节为门槛。

## 运行

在工程根目录使用 Android CLI：

```powershell
android emulator list
android emulator start Medium_Phone_API_37.0
android emulator start Medium_Tablet
android build :app:assembleDebug -- --console=plain
android run --device=emulator-5554 --apks=app/build/outputs/apk/debug/app-debug.apk
android run --device=emulator-5556 --apks=app/build/outputs/apk/debug/app-debug.apk
```

APK：[app-debug.apk](../app/build/outputs/apk/debug/app-debug.apk)。安装使用调试签名，应用内没有音乐下载或音频播放器。

首次启动有两条路径：授权通知使用权后，在支持的音乐 App 中播放；或主动进入原创内容预览。也可直接搜索歌曲、填写网易云数字 ID／QQ songmid，浏览真实歌词与评论。手动浏览与预览都有明确标记。

## 已实现

| 模块 | 实际行为 |
| --- | --- |
| 原生界面 | Jetpack Compose；柔和渐变与水流曲线、轻量浮动 Dock；首页／我的去除冗余品牌栏；歌名 25 sp、歌手 15 sp，单行溢出向左滚动；手机单栏、平板双栏 |
| 播放监听 | 用户授权 NotificationListenerService 后访问 MediaSession；允许列表过滤；优先正在播放的 Spotify，可固定 QQ／网易云等；前台每 15 秒校准 |
| 时间轴 | 基于单调时钟重建进度；暂停、seek、倍速；缺进度时不选中同步行；逐曲／逐来源偏移，正值表示延后 |
| QQ 直连 | 完整关键词搜索、每页 30 条及后续页，失败时降级 Desktop／Smartbox 并提示；区分 MID／数字 ID；GetPlayLyricInfo 原文＋译文，旧逐行接口降级；新版热评及最新评论 |
| 搜索与匹配 | 三来源匹配窗口；译名／合作歌手别名、近似候选排序、分页去重；最多 3 种查询／300 首展示；优先来源可自动回退其余源；同录音多发行按专辑／时长选择，保留主唱、时长、版本保护和具体失败原因 |
| 网易云直连 | 搜索、详情、原文、译文、来源罗马音、热门／最新评论；可选 Enhanced 协议 HTTPS 网关 |
| 补充来源 | LRCLIB 搜索及歌词接口；没有评论能力 |
| 歌词格式 | LRC、多时间戳、offset、增强 LRC、YRC、基础 TTML、明文 QRC；不生成虚假逐字时间戳；拒绝 DTD、实体及过深 XML |
| 评论 | 只读双来源；进入时固定歌曲；独立匹配、来源偏好、热门／最新；按 ID 去重；QQ 用 PageNum＋末条 SeqNo 续页，游标不前进时停止 |
| 渲染 | 经典滚动、手动滚动后的跟随恢复、有限分区自由排版、CJK 竖排、来源词时间高亮；支持减少动态效果 |
| 个性化 | 四种固定配色＋跟随封面 RGB 均色、亮／暗／系统、字号、译文与注音开关、清晰封面／私有背景图及反色歌词；取色后台计算并缓存；主题预览后应用；首页与悬浮窗独立目标覆盖 |
| 声明式主题 | 以 [JSON Schema](./lyricsflow.style.schema.json) 校验；打包同一文件为资产；字体、字重、颜色、布局、透明度及白名单关键帧；没有脚本执行器 |
| 悬浮窗 | 系统 Overlay Window＋specialUse 前台服务；拖动、宽度缩放、锁定穿透；通知及应用内解锁／关闭；熄屏／锁屏隐藏；暂停 5 分钟隐藏，恢复后显示 |
| 翻译 | 用户自配 HTTPS OpenAI 兼容 Base URL／Key／模型；确认发送与费用后可自动翻译缺失译文；中文判定避免重复翻译；应用内每日不限字符数和请求次数，保留每批 30 行／4000 字符及服务端冷却；验证行 ID 后缓存 |
| 数据 | 平台 SQLite 事务存储＋SharedPreferences；Keystore AES-GCM 加密 Key；100 MiB 网络响应缓存；歌词库保存／移除；SAF 导入导出和 ZIP 备份 |
| 文件安全 | 文件字节上限、JSON 深度上限、主题字段校验、ZIP 路径白名单、重复条目拒绝；背景图压缩为私有副本并原子替换；凭证排除系统和手工备份 |

## 工程分层

- `core/`：共享状态、规范歌词／曲目、时钟、解析器、主题时间轴、悬浮窗显示策略。
- `playback/`：音乐会话访问、回调与仲裁。不读取聊天通知正文。
- `data/`：HTTPS 提供者、缓存、SQLite、加密凭证、Schema 校验、文件传输。
- `overlay/`：原生 TextView 悬浮窗与前台通知；与首页共用播放快照和歌词。
- `ui/`：Compose 页面、响应式布局、歌词渲染与本地插画。
- `src/test/`、`src/androidTest/`：时间轴、安全边界、真实提供者、受控系统 MediaSession 和 Compose 使用流程。

独立 Web 原型仍保留在 `prototype/lyricsflow-web/`；Android 没有嵌入 WebView。

## 与 PRD 的差异和发布前事项

1. 当前持久化使用 Android 自带 SQLiteOpenHelper／SharedPreferences，实现事务和凭证隔离；尚未切换到建议的 Room／DataStore。数据库升级采用显式迁移策略，不能删库重建。
2. TTML 仅实现时钟／秒／毫秒及基础父级时间语义，不声称覆盖 TTML 全部规范。QQ 加密 QRC 未实现解密，网络歌词使用同曲的可用逐行接口。
3. QQ 直连接入已验证；QQ 的 Sansenjian／jsososo 两种可选网关尚未接入设置页。网易云网关路由已实现，但没有用户服务端地址，未完成服务端联调。
4. 翻译服务配置、授权、批次、限额、响应检查和缓存已实现。获得产品负责人提供的临时测试凭证后，已在 Android 手机通过 DeepSeek 真实手动／自动翻译和缓存验证，详见 [专项记录](./android-qa/DeepSeek-Translation.md)。测试完成恢复原配置；应用不内置共享 Key。整首长歌词、其他服务及跨日限额仍需扩大验证。
5. 自由布局采用有限分区避免互相覆盖，长文本可省略；高级主题在极端字号／复杂 XML 下仍需扩大样本验证。主题导入先显示静态预览，再在原生页面实际运行时间轴。
6. 网络响应缓存按容量清理，歌词库与导入资料保留。备份包有 20 MB 读取限制，数据正文最多 16 MB／3000 项；采用 `manifest.json`、`data.json`、`settings.json` 的开发版布局，不能当作 PRD 中完整互操作备份规范的最终定稿。
7. 已在用户提供的 HONOR 真机完成 Spotify／QQ／网易云真实本机播放器联调，初次修复见 [真机记录](./android-qa/Real-Device-Playback.md)。后续补齐完整搜索和 QQ 来源译文，之前未匹配的 QQ 日语歌已匹配成功；最终 QQ 时间歌词及 Spotify 连接回归通过，见 [QQ 专项记录](./android-qa/QQ-Lyrics-Search.md)。十首真歌、厂商后台策略和 60 分钟压力验收仍未完成。
8. Android 35 平板模拟器、Android 37 手机模拟器和一台上报 API 37 的 HONOR 真机已测试；Android 24–34、36 和其他实体机型尚未覆盖。应用包名、正式签名和公开发布配置仍待发布阶段确定。
9. GitHub 登录同步、远程 Spotify、自动生成罗马音属于已确认的后续范围。

本次没有把 PRD 的 44 条发布验收全部标为通过。准确证据见 [Android 验收记录](./android-qa/README.md)。

## 重复验证

```powershell
# 默认运行 12 项不依赖第三方网络的设备测试，并执行单元测试、Lint 和构建。
.\scripts\Run-AndroidQa.ps1 -Device emulator-5554
# 额外运行来源接口、译文、分页、缓存、自动匹配和 LRCLIB 界面，共 26 项设备测试。
.\scripts\Run-AndroidQa.ps1 -Device emulator-5556 -IncludeNetwork
```

脚本会为所选 QA 设备授予本应用通知使用权，以运行受控媒体会话测试。测试类通过构造参数允许测试会话；生产允许列表不包含测试包，不存在对外的假播放广播入口。公开接口受网络、地区和平台变更影响；网络测试失败时应检查日志，不能用演示数据替代成功结果。

自动匹配与 LRCLIB 入口的后续修复、样本及限制见 [匹配专项](./android-qa/Automatic-Matching.md)。

后续修正等待函数与校准设置同名的问题：校准默认保持 0 ms，首次升级清理误写的 300 ms，见 [校准修复记录](./android-qa/Calibration-Zero.md)。

2026-10-27 UI 后续修订：跟随封面使用完整均色色调；歌词背景直角、手机左右铺满，文字继续与标题对齐；稳定取色状态及短暂元数据缺失，避免刷新／切歌时闪回默认主题。实现、耗时、回归与已知原图分辨率限制见 [UI 专项记录](./android-qa/Flow-UI.md)。
