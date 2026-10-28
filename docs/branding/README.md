# LyricsFlow 品牌资源

图形把歌词的三条文本行与声波、水流结合起来。三条线完全等长、曲线一致、间距一致；浅蓝色渐变背景配深蓝波线，保证桌面小图标的辨识度。

| 资源 | 用途 |
| --- | --- |
| [logo.svg](./logo.svg) | 可缩放的图标源文件，108 × 108 视口 |
| [logo.png](./logo.png) | 1024 × 1024 PNG，适用于展示和分发 |
| [banner.svg](./banner.svg) | README 的矢量横幅 |
| [banner.png](./banner.png) | 1280 × 400 横幅 PNG |

Android 启动图标使用 `launcher_background_flow.xml` 与 `launcher_foreground_flow.xml` 分层，适应系统圆形、圆角矩形等遮罩；前景位于中心安全区域。`ic_launcher_monochrome.xml` 用于系统主题图标，`ic_flow.xml` 为通知单色标记。API 24–25 使用各密度的 PNG；普通与圆形图标均已提供。

配色：背景 `#E2F4FF` → `#ACD0F1`；波线依次为 `#193F65`、`#275D86`、`#3D7899`。矢量 SVG 与 Android 图形为项目内直接绘制的资源，无外部图片、字体或运行时网络依赖。
