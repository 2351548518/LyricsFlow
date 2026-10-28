<div align="center">

<img src="docs/branding/banner.svg" width="100%" alt="LyricsFlow — Let the words flow." />

**让歌词，随音乐流动。**

为 Spotify 等本机音乐 App 带来同步歌词、中文翻译与音乐评论。

[![Release](https://img.shields.io/github/v/release/2351548518/LyricsFlow?style=flat-square&color=386b61)](https://github.com/2351548518/LyricsFlow/releases/latest)
![Android](https://img.shields.io/badge/Android-7.0%2B-386b61?style=flat-square)
![UI](https://img.shields.io/badge/UI-Jetpack_Compose-607d8b?style=flat-square)
[![Downloads](https://img.shields.io/github/downloads/2351548518/LyricsFlow/total?style=flat-square&color=386b61)](https://github.com/2351548518/LyricsFlow/releases)

[下载正式版](https://github.com/2351548518/LyricsFlow/releases/latest) · [快速开始](#快速开始) · [界面预览](#界面预览) · [反馈问题](https://github.com/2351548518/LyricsFlow/issues)

</div>

## 它能做什么

LyricsFlow 读取本机播放器的曲目与进度，把歌词放在你正在听的音乐旁边。它提供歌词与评论阅读，不播放或下载音乐，也不要求登录 Spotify。

| 功能 | 体验 |
| --- | --- |
| **四种歌词来源** | QQ 音乐 → 网易云 → AMLL → LRCLIB 自动匹配；可手动搜索、绑定具体记录，记住每首歌的选择 |
| **双语与逐字歌词** | 原文、中文译文、来源提供的罗马音；当前行突出显示，支持已有真实时间戳的逐字高亮与时间校准 |
| **在线翻译** | 接入自己的 OpenAI 兼容服务、API Key 与模型，按需补充缺失译文；应用内每日不限量，服务费用由对应提供者收取 |
| **音乐评论** | 阅读 QQ 音乐、网易云的热门与最新评论；评论与歌词可独立匹配，不支持发布或点赞 |
| **随封面变换的主题** | 封面取色、动态网状渐变、中心与边缘柔化过渡；支持调节模糊程度、字号、配色和声明式主题文件 |
| **桌面悬浮歌词** | 原文与译文同色；自定义字体颜色、字号、0%–100% 背景不透明度，支持拖动、调整宽度和锁定穿透 |
| **本地与 GitHub 数据** | 收藏歌词、导入导出、本地备份；可选 GitHub 登录，将主题、配置、歌词、逐曲选择和独立评论绑定手动同步到专用私有仓库 |

AMLL、LRCLIB 的收录范围和译文情况取决于社区记录；没有真实逐字时间戳时按行同步。

## 界面预览

<table>
  <tr>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222517452.jpg" alt="LyricsFlow 功能预览 1" width="260" /></td>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222601540.jpg" alt="LyricsFlow 功能预览 2" width="260" /></td>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222524041.jpg" alt="LyricsFlow 功能预览 3" width="260" /></td>
  </tr>
</table>

<details>
<summary>查看更多功能与个性化界面</summary>

<table>
  <tr>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222541320.png" alt="LyricsFlow 功能预览 4" width="260" /></td>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222549857.jpg" alt="LyricsFlow 功能预览 5" width="260" /></td>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222618436.jpg" alt="LyricsFlow 功能预览 6" width="260" /></td>
  </tr>
  <tr>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222629785.jpg" alt="LyricsFlow 个性化预览 1" width="260" /></td>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222639463.jpg" alt="LyricsFlow 个性化预览 2" width="260" /></td>
    <td><img src="https://raw.githubusercontent.com/2351548518/images/main/20260908/20261028222649347.jpg" alt="LyricsFlow 个性化预览 3" width="260" /></td>
  </tr>
</table>

截图中的歌词与封面来自对应音乐平台，仅用于展示阅读界面。

</details>

## 快速开始

1. 从 [GitHub Releases](https://github.com/2351548518/LyricsFlow/releases/latest) 下载 `LyricsFlow-v1.0.0.apk` 并安装，支持 **Android 7.0 / API 24 及以上**。
2. 在“我的 → 播放与悬浮窗”授予**通知使用权**，然后在 Spotify 等支持的音乐 App 中播放歌曲。
3. 返回首页查看歌词。若版本或歌曲不符，点击来源按钮或“歌曲匹配”，搜索并确认记录；选择会随歌曲保存。
4. 需要悬浮歌词时，授予“显示在其他应用上层”权限并主动开启；翻译与 GitHub 同步均可按需配置。

**从开发调试版迁移：** 正式版使用独立签名，不能直接覆盖旧调试版。请先在旧版导出本地备份，或完成 GitHub 同步，再卸载旧版、安装正式版并恢复。API Key、GitHub 登录凭证和系统权限需要重新配置。后续正式版保留同一签名，可覆盖升级。

## 数据与隐私

- **本地优先。** 不登录也能使用歌词阅读、评论、本地收藏与个性化。
- **只读取播放信息。** 通知使用权用于访问音乐 MediaSession 的曲目和进度，不读取聊天通知正文。
- **翻译由你选择。** 仅向你配置的服务发送待翻译歌词和行 ID；API Key 通过 Android Keystore 加密保存。
- **同步由你操作。** GitHub Device Flow 登录后，连接或创建专用私有仓库，再预览并上传或下载快照；不会自动后台上传。恢复时同 ID 采用云端版本，并保留恢复前备份。
- **凭证不随备份导出。** API Key、GitHub Token、系统权限和悬浮窗位置均不包含在应用备份中。

## 常见问题

**为什么偶尔没有歌词或提示 HTTP 503？**

来源的收录、网络和接口可用性会变化。可以重试、切换来源，或在歌曲匹配中手动指定记录；已有歌词缓存可继续阅读。QQ／网易云适配使用非官方公开接口。

**刷新和重新自动匹配有什么不同？**

刷新重新获取当前来源的当前记录。重新自动匹配按 QQ → 网易云 → AMLL → LRCLIB 查找，成功后解除本曲的固定选择。

**歌词进度不准，或换了音乐 App 后没有跟随？**

在“播放与悬浮窗”检查权限、优先播放器和连接状态；也可对当前歌曲、当前来源进行时间校准。同步精度取决于播放器提供的进度；远程 Spotify 设备播放不在本机连接支持范围内。

**GitHub 同步一定要注册自己的 OAuth App 吗？**

发行版已配置公开 Client ID，可直接发起设备登录。自行部署时可使用自己的 OAuth App，并启用 Device Flow，见 [注册与使用说明](docs/GitHub-Sync.md)。

## 构建与开发

原生 **Kotlin + Jetpack Compose**，使用 Gradle Wrapper、JDK 21、Android SDK 37。先配置 Android SDK 路径与 [Android CLI](https://developer.android.com/tools/android-cli)。

```powershell
# 构建调试版
android build :app:assembleDebug -- --console=plain

# 构建、单元测试与静态检查
android build :app:assembleDebug -- :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain

# 独立模拟器上的默认回归
./scripts/Run-AndroidQa.ps1 -Device emulator-5554 -SkipBuild
```

正式签名、Release 构建、校验和与升级验证见 [发行维护说明](docs/Release.md)。默认回归会在指定设备上安装测试 APK，请使用独立测试设备。

| 资料 | 内容 |
| --- | --- |
| [实现说明](docs/Android-Implementation.md) | 工程分层、运行方式与支持边界 |
| [验收记录](docs/android-qa/README.md) | 真机、模拟器、来源与数据验证证据 |
| [主题 Schema](docs/lyricsflow.style.schema.json) | 声明式主题格式 |
| [品牌资源](docs/branding/README.md) | SVG Logo、PNG 与 Android 图标 |
| [v1.0.0 发行说明](docs/releases/v1.0.0.md) | 首版功能与安装说明 |

## 致谢与参考

- [Pear-Wall](https://github.com/Nevodev/Pear-Wall)：随封面变化的动态网状渐变设计参考。
- [AMLL TTML DB](https://github.com/amll-dev/amll-ttml-db)：社区 TTML 歌词来源。
- [LRCLIB](https://lrclib.net/)：社区歌词检索与同步歌词。
- [Jetpack Compose](https://developer.android.com/compose)：Android 原生界面与图形能力。
- QQ 音乐、网易云音乐及参与歌词制作、翻译和维护的贡献者。

LyricsFlow 与上述音乐平台无隶属关系；第三方歌词、封面、评论及数据遵循各自权利与条款。
