# 应用内更新验证 · 2026-11-01

## 实现

- “我的 → 数据与支持 → 检查更新”，沿用设置卡片样式，展示当前版本。
- 匿名查询官方 GitHub 最新正式版，发现较高版本即下载，随后校验并交给系统安装器。
- 处理 GitHub 公共 API 限流，备用路径读取官方 latest 重定向、SHA256SUMS 与 APK 大小。
- 附件 API 可直接跳转 CDN；失败时回退同一 Release 的下载链接。无 OAuth Token，无第三方镜像。
- 限定 HTTPS 官方主机、重定向次数和文件大小；校验完整哈希、包名、versionName、递增 versionCode、最低 Android 版本及签名。
- 文件仅保存在私有缓存，独立 FileProvider 只暴露 updates 子目录。

## 验证结果

- JVM：44 项测试通过，包含数字版本比较、旧版本及预发行版本处理。
- 正式构建、签名验证和 Release Lint 通过：0 错误，33 个既有警告；更新新增代码没有 Lint 警告。
- 更新专项 instrumentation：11 项通过（8 个数据/网络用例与 3 个 Compose UI 用例），显式开启 `updateNetwork=true`，包含真实 GitHub Release 检查、APK 下载与 SHA-256 校验。
- 另一个安装专项用例通过：使用本地高版本、同调试签名的 APK，经更新管理器下载和校验，再通过真实 FileProvider 交给系统安装器。模拟器出现“Update this app?”，确认后包版本由 1.0.2 / code 3 升为测试版本 1.0.3 / code 4。测试包只在模拟器使用，没有发布或装到用户真机。
- 覆盖错误状态、超时后重试、取消下载和清理、不完整/损坏 APK、站外跳转、缺少校验和、FileProvider 目录隔离、API 限流备用路径以及附件 API 回退。
- 真机：HONOR MLY-AN00，正式签名 v1.0.1 覆盖升级到 v1.0.2，未卸载；GitHub 登录及逐曲歌词选择仍可读取。入口显示 v1.0.2，点击检查返回“当前已是最新版本”。当时 GitHub 公开最新版本为 v1.0.1。

初次模拟器实网下载曾因无法连接 github.com 超时；改用官方附件 API 后，包含真实下载的 11 项专项测试全部通过。

## 复现命令

```powershell
android build :app:assembleDebug -- :app:assembleDebugAndroidTest :app:testDebugUnitTest --console=plain
adb -s emulator-5554 shell am instrument -w -e updateNetwork true -e class com.example.lyricsflow.AppUpdatesTest,com.example.lyricsflow.AppUpdateUiTest com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
```

可选安装专项需将高版本同签名 APK 放入测试应用私有文件目录，使用 `-e updateFixture <绝对路径> -e updateVersion <版本>`；`-e openInstaller true` 打开系统确认界面供人工检查。没有提供 fixture 或网络开关时，对应外部依赖用例会跳过。

系统安装界面证据：[system-installer.png](./update-v1.0.2/system-installer.png)；最终真机检查结果：[real-result.png](./update-v1.0.2/real-result.png)。验证后的代码与正式构建随 v1.0.2 发布。

检查更新专项构建 APK SHA-256：`161fa0e31635eea68baa89de17342cd4afc7d886c263d2fe121fbb30c69d007b`（加入全屏歌词前的验证构建；后续构建以 `dist/v1.0.2/SHA256SUMS.txt` 为准）。
