# Release 构建与维护

首个正式版：**v1.0.0**，`versionCode = 1`，包名保持 `com.example.lyricsflow`。后续每次发行递增 versionCode，始终沿用首次发布的正式签名。

当前正式版本：**v1.0.5**，`versionCode = 6`，优化切歌时的网络取消与 LRCLIB 提示缓存，修复限流期间读取缓存，并将候选时长统一为分秒格式；沿用首版签名。脚本按构建输出中的版本号生成对应 `dist/v<版本>/`，历史版本不覆盖。

## 应用内更新发布约定

“我的 → 检查更新”匿名请求 `GET /repos/2351548518/LyricsFlow/releases/latest`，仅接受已发布的正式版本。每次发行必须递增 `versionCode` 和数字版本号，标签为 `v<版本号>`，通用 APK 附件名为 `LyricsFlow-v<版本号>.apk`。附件上传完成后再公开 Release；GitHub API 的附件 `digest` 必须提供 `sha256:` 校验值。草稿、预发行、缺少 APK 或校验信息的版本不会用于安装。

公共 API 限流或暂时不可用时，改用官方 `releases/latest` 重定向读取版本，再从同一 Release 的 `SHA256SUMS.txt` 和 APK HEAD 响应获取哈希与大小；因此发行附件必须始终包含校验文件。备用路径不使用第三方镜像。

从 API 取得附件 ID 后，下载优先走官方 Release asset API，以 `Accept: application/octet-stream` 直接取得文件或跳转 CDN；该路径失败时使用同一官方 Release 的浏览器下载地址。

下载仅允许 GitHub 官方 HTTPS 文件服务；校验大小和 SHA-256 后，再检查 APK 包名、较大的 versionCode、匹配标签的 versionName、Android 最低版本和与已安装应用相同的签名。安装包暂存在应用私有缓存中，通过仅暴露 `updates/` 的 FileProvider 交给系统安装器。下载不使用 GitHub 登录凭证，也不申请存储权限。Android 8+ 首次使用需允许安装来源；系统安装步骤始终由用户确认。取消安装后可继续安装，取消下载会清除未完成文件。此更新器不会自动后台检查或静默安装。

参考：[GitHub 最新 Release API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)、[Android FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)、[安装来源授权](https://developer.android.com/reference/android/content/pm/PackageManager#canRequestPackageInstalls())。

## Windows 本机签名

使用 PowerShell 7、JDK 21、Android SDK 37 与 Android CLI。

```powershell
# 仅在首次发行、尚无密钥时执行一次
./scripts/Build-Release.ps1 -InitializeSigning

# 后续构建直接复用签名
./scripts/Build-Release.ps1
```

脚本默认将签名材料放在 `%USERPROFILE%/.android/lyricsflow-release/`，不在仓库中：

- `lyricsflow-release.p12`：RSA 4096 位正式私钥库。
- `signing.credentials.clixml`：经 Windows DPAPI 加密的签名密码，只能由当前 Windows 账户在当前机器解密。

**请备份正式私钥，并将解密后的密码单独存入密码管理器。** 只复制 `.clixml` 到另一台机器不能恢复密码。不要重新生成或覆盖已发布应用的签名。私钥和密码不上传到 GitHub，不包含在 APK 或发行附件中。

其他构建环境可通过以下进程环境变量提供自己的正式签名，再调用 Gradle/Android CLI：

| 变量 | 值 |
| --- | --- |
| `LYRICSFLOW_KEYSTORE_FILE` | 私钥库绝对路径 |
| `LYRICSFLOW_STORE_PASSWORD` | 库密码 |
| `LYRICSFLOW_KEY_ALIAS` | 密钥别名 |
| `LYRICSFLOW_KEY_PASSWORD` | 密钥密码 |

Release 构建启用 R8 优化与压缩，并禁用 Gradle 配置缓存，避免把签名密码写入配置缓存。脚本同时运行默认 JVM 单测和 Release Lint，构建后检查签名与 APK 元数据、拒绝 debuggable 包。

## 输出与发布

`dist/v1.0.0/` 默认包含：

- `LyricsFlow-v1.0.0.apk`：正式签名的通用 APK。
- `SHA256SUMS.txt`：APK SHA-256 校验和。
- `LyricsFlow-signing-certificate.cer`：仅含公钥的签名证书，用于核验，无需安装到手机。
- `signature.txt`、`apk-metadata.txt`：本地校验证据。

`dist/`、私钥文件和凭据文件均被 Git 忽略。发布前提交源码、将 `v1.0.0` 标签指向实际构建的提交，再把 APK、校验和和公钥证书作为 GitHub Release 附件。发布说明保存在 [docs/releases/v1.0.0.md](./releases/v1.0.0.md)。

```powershell
# 发布内容与附件全部核对后执行
gh release create v1.0.0 --verify-tag --title 'LyricsFlow v1.0.0' --notes-file docs/releases/v1.0.0.md dist/v1.0.0/LyricsFlow-v1.0.0.apk dist/v1.0.0/SHA256SUMS.txt dist/v1.0.0/LyricsFlow-signing-certificate.cer
```

## 安装、迁移与升级

首次正式版与此前 Android Studio 调试版签名不同。请先在调试版导出应用备份，或完成 GitHub 手动同步，再卸载调试版、安装正式版、恢复数据。API Key、GitHub 登录凭证、通知使用权和悬浮窗权限需要重新配置。不要为安装测试擅自卸载用户真机上的调试版。

后续正式版必须保留相同签名与包名，并递增 versionCode；可直接覆盖安装。发行验证应至少包含独立测试设备的首次安装、启动、核心阅读与保存、相同正式签名覆盖安装及数据保留检查。

官方参考：[Android 应用签名](https://developer.android.com/studio/publish/app-signing)、[自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)、[GitHub Release CLI](https://cli.github.com/manual/gh_release_create)。
