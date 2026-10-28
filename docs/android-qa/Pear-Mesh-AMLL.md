# 封面网状渐变与 AMLL 接入

日期：2026-10-28。新版主程序已安装到 HONOR MLY-AN00（API 37）及手机模拟器。

APK：`app/build/outputs/apk/debug/app-debug.apk`。

SHA-256：`DF4AD0992814929B4C50F496C72DE003E19A67F8E0C5004E23E969001B2D455E`。

## Pear-Wall 代码阅读结论

阅读版本：`b5dfe87ddf1b06dbab511985dc39310311b75963`。

重点代码：

- [PearMeshMesh](https://github.com/Nevodev/Pear-Wall/blob/b5dfe87ddf1b06dbab511985dc39310311b75963/app/src/main/java/com/nevoit/pearwall/pearmesh/gl/PearMeshMesh.kt)：控制网格通过 Catmull-Clark 细分，生成两个形态之间的变形网格。
- [PearMeshGlRenderer](https://github.com/Nevodev/Pear-Wall/blob/b5dfe87ddf1b06dbab511985dc39310311b75963/app/src/main/java/com/nevoit/pearwall/pearmesh/gl/PearMeshGlRenderer.kt)：封面纹理切换、形变、旋转、模糊及最终材质处理。
- [材质着色器](https://github.com/Nevodev/Pear-Wall/blob/b5dfe87ddf1b06dbab511985dc39310311b75963/app/src/main/assets/pearmesh/shaders/material.frag)：饱和度/亮度处理和抖动，不是简单的封面平均色。
- [状态与默认参数](https://github.com/Nevodev/Pear-Wall/blob/b5dfe87ddf1b06dbab511985dc39310311b75963/app/src/main/java/com/nevoit/pearwall/pearmesh/PearMeshState.kt)：默认渲染比例 0.25、目标 30 FPS，并有暂停播放时冻结流动的策略。

参考价值是多色流动、分层合成、降低背景渲染分辨率、暂停/不可见时停止更新。它本身是带 EGL/OpenGL ES 渲染管线的动态壁纸应用，不能直接把服务及音频采集模块放进本 App。

本次独立实现适合 Compose 页面背景的四控制点颜色场，不是完整 PearMesh 网格算法移植。没有复制壁纸服务、音频采集、纹理资源或项目字体；无需麦克风/壁纸权限。Pear-Wall 使用 Apache-2.0，项目来源在本报告中致谢。Android 实现参考 [ShaderBrush/AGSL 官方说明](https://developer.android.com/develop/ui/compose/graphics/draw/brush)。

## 实现及使用方式

入口：**我的 → 个性化 → 跟随封面**；新增“动画网状渐变”开关。

- 继续保留整张封面的精确 RGB 均值作为主题基色，不把平均色算法改成近似结果。
- 在后台把封面采样到 48×48，做确定性的四色聚类，缓存到弱引用表；不会在每帧扫描原图。
- 从封面多色生成控制点，使用缓慢位移及坐标形变混合，形成类似参考图的柔和云状渐变。
- Android 13+ 使用 RuntimeShader；旧版本或着色器不可用时使用原生渐变绘制降级。背景先以宽高各 25% 的离屏图层绘制，再放大；文字、图标、歌词不降分辨率。
- 动画约 30 FPS，只在绘制阶段读取时间。暂停、离开前台或开启“减少动态效果”时冻结；换封面通常用 1.4 秒颜色过渡，若旧颜色与新文字不兼容则直接采用可读的新配色。
- 根据主文字颜色约束渐变控制点，使主文字在插值区域也保持对比度。按钮/设置卡片继续使用现有实色控件配色和选中标识。
- 保留现有歌词封面/自定义图片选择、模糊程度、描边和排版。歌词背景另增“网状渐变”；整页已使用网状背景时歌词区直接透出该背景，避免重复渲染。
- 设置字段 `coverMesh` 与 `background=mesh` 可本地保存，并随本地备份及 GitHub 快照恢复；不改变凭证处理。

实现文件：`MeshColors.kt`、`CoverMesh.kt`，接入 `FlowTheme.kt`、`FlowApp.kt`、`Artwork.kt` 和个性化页面。

[原生渲染预览](./mesh-amll/mesh-preview.png)、[真机阅读页面](./mesh-amll/real-profile-after.png)。预览是实际 Android 渲染结果。

## AMLL 仓库接入方案

阅读版本：`b8834a5306c0802a6357a1caf645fe9282904e5c`。

- [上游 README / 数据使用说明](https://github.com/amll-dev/amll-ttml-db/blob/b8834a5306c0802a6357a1caf645fe9282904e5c/README.md)
- [元数据索引](https://github.com/amll-dev/amll-ttml-db/blob/b8834a5306c0802a6357a1caf645fe9282904e5c/metadata/raw-lyrics-index.jsonl)
- [TTML 文件规范](https://github.com/amll-dev/amll-ttml-db/blob/b8834a5306c0802a6357a1caf645fe9282904e5c/instructions/ttml-specification-en.md)

接入的是公开仓库，不需要登录 GitHub，也不使用 GitHub 同步 Token。

1. 读取 `metadata/raw-lyrics-index.jsonl`，检查时约 1.65 MB、3370 条原始记录。本地检索名称、艺人、专辑、别名，支持有界近似搜索和分页；不会为每个关键词调用 GitHub 搜索 API。
2. 索引按旧到新列出历史稿件。本次检查有 1935 个平台 ID 出现过多次，最多 7 份记录。搜索优先最新平台记录，平台 ID 绑定也优先最新稿；用户显式填写旧原始文件名时仍能读取旧稿。
3. 选中后只下载对应 `raw-lyrics/<file>.ttml`。支持索引内的网易云/QQ/Spotify/Apple Music ID，及 AMLL 原始文件名；部分平台歌曲链接可提取 ID。不同平台数字碰撞时不静默绑定错误曲目。
4. 官方地址为 GitHub Raw；网络连接失败时使用上游 README 列出的 `https://amlldb.bikonoo.com/` 镜像。模拟器首次直连确实失败，加入备用地址后成功获取真实索引和 TTML。来源说明已在匹配页显示。
5. 磁盘索引/TTML 新鲜缓存 24 小时，内存索引最多保留 24 小时后检查；失败可用 14 天内旧缓存。网络路径固定、HTTPS、不重定向、不发送认证信息；每个地址请求上限 5.5 秒，至多尝试两个固定地址。
6. 索引上限 8 MB / 20000 条，元数据字段数上限 100、每字段值数上限 1000；TTML 上限 2 MB / 10000 行，XML 拒绝实体、DTD 及过深嵌套。真实库有一条记录包含 238 个专辑值，最初的 100 值限制会拒绝整个索引，已修正并加入回归。

元数据索引没有音频总时长，因此 AMLL 候选不会伪造 duration。自动绑定检查完整歌名、歌手别名及版本；取得 TTML 后，若时间轴超出当前播放时长 5 秒则拒绝自动使用。不能据此宣称已经校验所有跨平台录音与偏移，上游也提示其主要按网易云音源保证时间轴，必要时可用现有时间校准。

共享协议按上游说明：自主编写部分采用 CC0，外来数据沿用原提供方协议。本次未把歌词库整包加入 APK，按需缓存并保留来源，感谢原作者与贡献者。

## TTML 解析

新增 `AmllTtml`，与原有导入解析分开，防止改变旧 TTML 的相对时间兼容行为。

- AMLL 的 p、span、div 使用歌曲绝对时间，不能再次累加 div 的 begin。
- `x-translation` 单独进入译文，优先简体中文，其次其他中文/未声明语言；不把明确英文译文当作中文显示。
- `x-roman` 单独进入罗马音，不拼进原文或原文的逐字节点。
- 保留显式单词间空格、原始逐词时间；逐行文本不生成假的逐字时间。
- `x-bg` 作为带括号的独立背景人声行，保留自身时间和译文。当前仍使用 LyricsFlow 原有排版/激活规则，没有移植 AMLL 完整对唱/背景人声动画引擎。

历史投稿不一定符合新规范，例如缺失英文空格；不凭空猜测补全全部旧稿。未支持的格式会报错/换源，不执行 XML 外部资源。

## 自动来源顺序

新歌及自动刷新：**QQ 音乐 → 网易云音乐 → AMLL → LRCLIB**。上一首实际来源不会改变自动顺序。手动选择某来源只查询该来源；关闭自动尝试时也只查当前来源。

QQ/网易云的有效缓存和同步歌词仍优先。AMLL 不提供评论；评论仍独立选 QQ/网易云。自动匹配整体预算 40 秒，通常单来源 12 秒；AMLL 首次索引较大，给 16 秒协程预算。严重网络阻塞仍可能使整体预算耗尽，不保证所有故障场景都能访问完四个服务。

首页来源选择器、匹配搜索、手动 ID 输入、本地存储、备份中的 Provider 已加入 `AMLL`。

## 验证

- 最终构建通过，JVM **38 项通过**，Lint **0 错误 / 38 警告**。[构建](./mesh-amll/build.log)、[Lint](./mesh-amll/lint.txt)。
- 模拟器默认 **45 项回归**，包含客户端缓存/历史版本/近似搜索/恶意链接/取消、渐变绘制不重组、暂停静止、设置备份恢复，以及已有播放/歌词/导入/控件/GitHub 回归。[回归日志](./mesh-amll/regression.log)。
- 专项 **8 项通过**，其中真实网络 `Avid` 匹配 `1719909673586-50987405-a5e4aad1.ttml`，取得 **38 行 / 331 词 / 中文译文 / 罗马音**，网易云 ID 186016 解析为“晴天”。冷索引/网络合计约 12.6 秒；该值不代表缓存速度或全部网络。见 [专项日志](./mesh-amll/feature-tests.log)。
- [第一次网络/测试签名失败](./mesh-amll/amll-first-failure.log)、[索引限制失败](./mesh-amll/index-limit-failure.log) 保留，均已修复后复验，不计入通过数。
- 真机完成新版主程序安装及阅读画面检查，未重试之前被拒绝的测试 APK 安装。本轮新自动化主要在模拟器执行，没有宣称完成完整 61 项联网回归。

### 渐变性能复测

模拟器固定歌词样本：预热 5 秒，再采集 25 秒；同一场景只改变全分辨率/离屏绘制。软件/虚拟 GPU 的绝对值不能推导实体机表现，但暴露了初版额外开销。

| 指标 | 全分辨率 | 25% 离屏渲染 |
| --- | ---: | ---: |
| 帧数 | 1034 | 1257 |
| Janky frames | 387 / 37.43% | 220 / 17.50% |
| P95 | 81 ms | 61 ms |

[之前](./mesh-amll/emulator-gfxinfo.txt)、[之后](./mesh-amll/emulator-offscreen-gfxinfo.txt)。离屏版本降低开销，但模拟器仍不能算通过流畅度发布验收；没有隐藏这些数据。

用户同意让真机首页保持播放后，各采集 30 秒。设置为跟随封面、动画渐变开启、减少动态效果关闭、歌词仍使用封面，见 [设置记录](./mesh-amll/real-profile-settings.json)。

| 指标 | 真机全分辨率 | 真机离屏版 |
| --- | ---: | ---: |
| 帧数 | 1808 | 1805 |
| Janky frames | 0 | 1 / 0.06% |
| P95 | 6 ms | 7 ms |
| P99 | 8 ms | 8 ms |

[之前](./mesh-amll/real-full-resolution-gfxinfo.txt)、[之后](./mesh-amll/real-offscreen-gfxinfo.txt)。歌曲自然向前播放，两次不是同一段歌词，也没有测量长期能耗；两种版本在该机均较流畅，不声称真机离屏版帧耗时更低。减少的是背景着色区域，文字仍按原分辨率绘制。图像已分别检查：[之前](./mesh-amll/real-profile-before.png)、[之后](./mesh-amll/real-profile-after.png)。
