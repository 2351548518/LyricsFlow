
参考 以下 的项目

[afeibukaixin/Lyrics-Plus: An open-source, customizable macOS Lyrics Companion｜支持桌面歌词、菜单栏歌词、歌词窗口与 Dynamic Island，兼容 Apple Music、Spotify 及系统媒体播放器。](https://github.com/afeibukaixin/Lyrics-Plus)

[cli/CustomApps/lyrics-plus/README.md 在主页 ·Spicetify/CLI](https://github.com/spicetify/cli/blob/main/CustomApps/lyrics-plus/README.md)

[WXRIW/Lyricify-App: Lyricify (/lɪ'rɪsəfaɪ/), a fantastic app to provide scroll lyrics for Spotify and other apps. 一款为 Spotify 等各种应用提供滚动歌词的软件。](https://github.com/WXRIW/Lyricify-App)

一个 Android 的 App， 用来 查看 歌词， 调用 Spotify 的 接口，得到 正在 播放的 歌曲 名称 和 时间， 然后 在 QQ音乐[Rain120/qq-music-api: QQ 音乐API koa2实现](https://github.com/Rain120/qq-music-api) 和 网易云 音乐[网易云音乐开放平台](https://developer.music.163.com/st/developer/document?docId=803202bd65bc469587d05b507dcd31e7) [网易云音乐(APP)API接口文档](https://apis.netstart.cn/music/#/) 和 [Lyrics.com](https://www.lyrics.com/)  和 [LRCLIB](https://lrclib.net/)  和 [快速开始 | LrcShare API](https://api.lrcshare.com/docs/guide/quickstart.html) 和 [Better Lyrics API Documentation](https://docs.betterlyrics.org/?utm_source=chatgpt.com)和 [lrc.red](https://lrc.red/?utm_source=chatgpt.com) 等 歌词平台 上 搜索歌词，之后 在 页面 进行 展示。 需要 支持 首页 展示 歌词，我的 页面 进行 设置 等操作， 还需要 支持 悬浮窗

## 页面

需要 有一个 首页 和 我的 页面，

---

首页： 

![ecf39e9f8b69b667bff90bf931bc9c78.jpg](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026193325333.jpg)

首页参考 上图， 需要 在 上方 显示 歌名 和 作者

下方区域 显示 歌词 和 翻译（如果 不是 中文）

底部显示 歌词源选择、拉取歌词按钮、桌面歌词开关、歌词翻译开关、评论页面按钮

---

歌名 和 作者 的 字体 要 加粗 并且 字号 要 大一些

---

展示 歌词 的 区域 需要 高度 支持 个性化，需要做到 类似于 html 和 css 和 js 的 关系，就是 内容 是 不变的，但是 展示 的 样式 可以 根据 类似于 css 和 js 的 文件 进行 调整控制。

预设 两种 样式：

一种 和 下面的 类似：

![843ee019d1fd23cef95d0d662b6352d8.jpg](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026193803075.jpg)

就是 展示 原始 歌词 和 翻译

第二种 类似于 下面的 样式：

![image.png](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026194034940.png)

就是 在 首页 的 随机 地方 竖向 或者 横向 展示 歌词，同时 歌词 的 颜色 和 大小 有变化

可以参考 下面的 视频：

【【花谱】#61 《毕生啊/畢生よ》原创歌曲-哔哩哔哩】 https://b23.tv/fjXrfpC


除了 以上两种 之外，用户 可以 导入 个性化 配置 文件 实现

---

评论 页面：

用户 点击 评论 按钮之后 ，展示 歌曲的 热门评论 页面，每一条 需要 评论 需要 显示 评论的 用户名、评论的 内容、评论的 来源

![febf05308fe6311fca058e79e47a5942.jpg](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026202818535.jpg)

歌曲的 评论 可以从 [网易云音乐开放平台](https://developer.music.163.com/st/developer/document?docId=c7ab2954a5a04fdd86d01c88d811786c) 或者 QQ 音乐 等平台 的 API 进行 获取

---

我的：

我的 页面 需要 有账户、桌面歌词设置、翻译设置、歌词源设置、个性化设置、帮助和反馈、关于 等 页面

参考 如下 图片

![02edbf4bbf0857138c2980ddc4962c6a.jpg](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026194623513.jpg)

需要 能 支持 使用 github 账户 登录，同时 将 个人资料 保存到 GitHub 上（比如 歌词 文件，个性化配置 文件 等），这是一个 可选的 功能

---

底部栏：

底部栏 需要 采用 类似于 IOS 的 液态 玻璃 的 dock 栏，

可以参考 下图：

![image.png](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026194747495.png)

不过 现在 只需要 首页 和 我的 两个

---

悬浮窗：

需要 支持 悬浮窗 

悬浮窗 怎么 开发 参考 Android 官方 文档

悬浮窗 需要 支持 手动调整 大小，
支持 同时 显示 中文歌词 和 英文歌词
同时 需要 高度 支持 个性化，需要做到 类似于 html 和 css 和 js 的 关系，就是 内容 是 不变的，但是 展示 的 样式 可以 根据 类似于 css 和 js 的 文件 进行 调整控制。

参考 下图，但 要按照 上述的 要求

![ea37c3bb58f731c49bfd049b84106e31.jpg](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026195025262.jpg)

## 功能

1. 翻译： 首页 和 悬浮窗 需要 支持 查看翻译（中文），对于 没有 中文翻译 的 歌词，尝试 调用 在线 翻译 服务 进行 翻译

可以 采用 下图  的 翻译服务， 也可以 自己 设置 OpenAI 格式 的 大模型 API URL 和 Key 调用 大模型 进行 翻译成 中文

![image.png](https://raw.githubusercontent.com/2351548518/images/main/20260908/20261026195505505.png)

2. 自定义： 和 之前说的 一样，需要 支持 高度的 个性化，需要做到 类似于 html 和 css 和 js 的 关系，就是 内容 是 不变的，但是 展示 的 样式 可以 根据 类似于 css 和 js 的 文件 进行 调整控制。 这个 文件 需要 支持 导入 和 导出，文件 的 格式 需要你 自己 考虑

3. 歌词： 需要 支持 每首 歌 的 歌词 都可以 选择 歌词 源， 同时 将 歌词源 保存在 本地。 每首歌 的 歌词 从 歌词 源 获取后， 保存在 本地（这样 就不用 每次 都从 歌词源 进行 拉取），用户 可以 点击 拉取歌词 按钮 重新 从 歌词源 获取

4. 首页 歌词 背景： 首页 歌词 背景 需要 支持 从 歌曲 的 封面 选取 纯色/ 歌曲封面 / 用户 自定义 图片 三种 方式。

## 实现 方式

最好是 使用 原生 的 Android 开发 实现，

需要 注重 美光 和 流畅，同时 兼顾 优化功耗。

要求 进行 解耦，符合 现代化 的 app 开发

