# LRCLIB 搜索、原文选择与歌词滚动修复

日期：2026-10-28。真机：HONOR MLY-AN00 / API 37；模拟器：Medium Phone API 37。

新版主程序已安装到真机。APK：`app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：`5416249A721A2C3829B58EA12520B02BADEACC90037D4796A1CC6113898B7E76`。

## 503 与慢搜索

本次桌面直接访问 `https://lrclib.net/api/get/18731361` 实际出现一次 HTTP 503，耗时约 2 秒，稍后恢复 200。可见故障能在 App 外复现。[接口元数据](./lrclib-render/api-metadata.json)、[后续请求](./lrclib-render/http-probes.json) 保留结果。该次失败没有保存响应体，不能确认是应用服务器还是边缘节点返回。

上游源码检查版本 `e1d863afc430392c8b2f97bea72139292d498432`：加载许可不足、排队超时会产生 `ServerOverloaded` / HTTP 503，并发送 `Retry-After: 1`。这为服务繁忙提供依据，不等于确认截图当时线上部署和内部故障。

- [过载响应](https://github.com/tranxuanthang/lrclib/blob/e1d863afc430392c8b2f97bea72139292d498432/server/src/errors.rs)
- [加载许可/排队](https://github.com/tranxuanthang/lrclib/blob/e1d863afc430392c8b2f97bea72139292d498432/server/src/state.rs)
- [搜索参数及歌词字段](https://github.com/tranxuanthang/lrclib/blob/e1d863afc430392c8b2f97bea72139292d498432/server/src/routes/search_lyrics.rs)

旧客户端也放大了延迟：只用 `q=歌名+歌手` 全文搜索，扩展关键词连续请求；搜索返回已含歌词，却再次请求 `/get/<id>`；503 没有重试/退避；手动搜索等所有别名完成才更新候选。

本次调整：

1. 当前曲目优先结构化 `track_name` + 首位 `artist_name`，保留版本信息；无法确认匹配时再尝试一次仅歌名查询。完整歌手、歌名/版本和 5 秒时长检查继续生效。
2. 搜索完整记录按 ID 缓存，随后取歌词直接复用，分页也复用相同响应。手动搜索首批成功就更新；LRCLIB 有结果后停止扩展，只在空结果时尝试别名；服务失败不继续换别名请求同一服务。
3. 同一客户端串行请求，重复查询复用成功缓存。先检查新鲜缓存，再检查限流；新鲜期 6 小时，连接/过载失败可回退到 7 天内旧缓存，搜索页提示缓存回退。
4. LRCLIB 连接超时 4 秒、读取超时 6 秒、每次 HTTP 总期限 8.5 秒；取消时后台断开连接。自动匹配器为单来源设置 12 秒协程预算；其他来源旧 HTTP 路径仍受自身阻塞 I/O 超时约束。
5. 网络错误及 502/503/504 最多再试一次，默认等 700 ms，遵守短 Retry-After；较长提示及 429 直接退避。持续失败至少暂停 20 秒、429 默认 60 秒，尊重更长的服务端等待值。有限重试，不使用未知镜像或规避限流。

服务持续故障无法由客户端消除。没有缓存仍会说明错误，可切换其他来源，不承诺永不出现 503。

## 罗马音：记录选择问题

直接 API 样本如下，只保存元数据和字符类型统计，没有将整首歌词复制进报告。

| 记录 | 时长 | syncedLyrics 假名数 | plainLyrics 假名数 | 说明 |
| --- | ---: | ---: | ---: | --- |
| 18139719 | 129 秒 | 0 | 0 | 两字段均为拉丁字母歌词 |
| 1962284 | 132 秒 | 0 | 0 | 两字段均为拉丁字母歌词 |
| 19932106 | 129.186667 秒 | 187 | 187 | 日文记录 |
| 18731361 | 131.666667 秒 | 搜索确认有日文 | 搜索确认有日文 | 最终匹配的同步记录，单独 GET 一度 503 |

样本没有单独 romanization 字段。`syncedLyrics` 表示有时间戳，不保证是原始书写形式；同曲目的社区记录可能是日文、罗马音或其他语言。旧匹配仅按元数据，可能选到罗马音，并非把原文/罗马音字段读反。

- 先通过歌曲身份、录音版本、时长检查，再优先含日文的同步记录。日文候选多个时优先同专辑，再选时长差接近最佳的一组（最佳差值 + 500 ms），交给原有匹配器确认。
- 当前歌曲标明 English/英语版本时不启用日文优先；不越过完整歌手或版本检查。
- 手动候选全部保留，提示“含日文原文”或“拉丁字母歌词（可能为罗马音）”，不把所有英文歌词断言为罗马音。[真机截图](./lrclib-render/real-search-language.png)。
- 旧 LRCLIB 缓存若无假名且 providerRevision < 2，会在自动加载时重新寻找，失败保留旧文本；主动刷新已有拉丁字母歌词也重新匹配。
- 若同步字段仅有拉丁字母、普通字段有日文，则显示日文普通字段。没有对应时间戳时显示无时间轴，不按行号伪造同步；自动换源仍优先其他可用同步歌词。
- 全目录只有罗马音时无法可靠还原日文，允许显示并手动换源，不让模型猜造原文。

Android API 回归最终选中 18731361，得到 23 行日文同步歌词。已有缓存下搜索约 388 ms、读取歌词约 8 ms；这不代表冷网络延迟（桌面样本约 2–3 秒）。最初联网测试发现两个日文候选时长相差超过旧歧义阈值而拒绝匹配，据此补上日文候选时长优先级。[最初失败记录](./lrclib-render/live-tests.log) 保留，没有计为通过。

## 滚动根因与实现

旧代码在 220 ms 字号动画的每一帧修改 fontSize/lineHeight，文字反复塑形、换行，列表高度持续变化，同时还在执行滚动。封面上每段文字用两套 Text 分别布局描边和正文。80 ms 播放时钟又更新整个 Home，主题时间轴只有约 12.5 Hz。

初步真机 Simpleperf：25 秒、2486 样本、丢失 0。measureAndLayout inclusive 约 11.99%、MultiParagraphLayoutCache.layoutWithConstraints 约 5.19%、LyricStage.group 约 5.03%。调用树占比有重叠，不可相加，也不是各函数独占时间。[采样报告](./lrclib-render/before-cpu.txt)。

保留 Compose，使用 Android 文字布局和硬件图层：

- 当前原文仍放大 18%、译文约 10%；一次布局到目标字号，过渡帧只做 graphicsLayer 缩放，停止逐帧重新换行。
- 描边复用正文 TextLayoutResult，不创建第二个文字布局。保留可访问性、字体、换行、逐字颜色、原有对齐。
- 时钟跟随显示帧；当前行通过 derivedStateOf 更新；时间标签按秒更新；主题运动在图层阶段读时钟；单词颜色仅在单词边界更新。
- 等目标字号布局完成后，用固定目标和 480 ms 缓动滚动；快速换行/seek 取消旧跟随，定位最新行。拖动暂停跟随 5 秒，仍可点击回到当前歌词；新曲目重建列表状态。

没有引入 JNI/C++：当前瓶颈是重复排版及更新范围，改变语言不能消除这些重复操作。实现参考 [Android Compose 阶段/延迟状态读取建议](https://developer.android.com/develop/ui/compose/performance/phases)。

## 同一真机性能对比

LyricRenderBenchmark：相同 HONOR 真机/debug，120 行日中歌词，25 sp，8 dp 模糊封面，每 2 秒换行。预热 5 秒，采集 25 秒 gfxinfo。无网络/真实播放器控制，不写用户偏好；修改前 1 次，修改后 2 次。

| 指标 | 之前 | 之后 | 复测 |
| --- | ---: | ---: | ---: |
| 帧数 | 452 | 1502 | 1502 |
| Janky frames | 45 / 9.96% | 0 / 0% | 0 / 0% |
| P50 | 12 ms | 7 ms | 7 ms |
| P90 | 20 ms | 8 ms | 8 ms |
| P95 | 21 ms | 9 ms | 8 ms |
| P99 | 36 ms | 12 ms | 11 ms |
| Slow UI thread | 41 | 0 | 0 |

原始报告：[之前](./lrclib-render/controlled-before-gfxinfo.txt)、[之后](./lrclib-render/controlled-after-gfxinfo.txt)、[复测](./lrclib-render/controlled-after-repeat-gfxinfo.txt)。帧数变化与时钟跟随显示帧有关，不能只看卡顿百分比；耗时分位数也同步下降。Legacy 统计仍有非零值，报告完整保留。这是固定短样本，不代表全部设备、复杂封面/主题、长逐字歌词或持续播放零卡顿。

真实 Spotify 播放页面也已检查放大、描边、译文、左对齐与封面保持。[阅读截图](./lrclib-render/real-reader.png)。

## 验证和限制

- 最终主程序/测试 APK 构建通过；JVM **29 项通过**；Lint **0 错误 / 38 警告**，无新增警告。[构建](./lrclib-render/build.log)、[Lint](./lrclib-render/lint.txt)。
- API 37 模拟器 **38 项通过**，含新增客户端 8 项、滚动 2 项；覆盖 503/旧缓存/退避/404/并发/取消/HTTP 超时断开/文字选择，以及锚点/seek/手动浏览/逐字颜色。[回归日志](./lrclib-render/emulator-tests.log)。
- 最后补齐未知播放进度时清除逐字颜色缓存、普通字段日文的候选提示；最终构建重新执行客户端/滚动 **10 项专项**，见 [复验日志](./lrclib-render/final-targeted-tests.log)。
- 真机 **11 项通过**：已有客户端 7、阅读设置 3、真实 LRCLIB API 1。[真机日志](./lrclib-render/real-tests.log)。
- 主程序升级成功。测试 APK 一次升级被系统拒绝（INSTALL_FAILED_ABORTED: User rejected permissions），未重试绕过。旧测试 APK 不含 LyricScrollTest，一次运行因此 ClassNotFound；随后只运行确实存在的 11 项并通过。新增第 8 项客户端/滚动专项在模拟器验证。[失败记录](./lrclib-render/real-old-test-apk-failure.log)。
- 默认 QA 脚本现为 38 项，可选联网共 53 项。本轮未运行完整 53 项，不将其标为通过；性能采集独立运行。
- 未调用收费翻译、未上传/恢复 GitHub、未修改真实歌曲绑定。手动搜索验证后已关闭匹配窗，回到实际播放页面。
