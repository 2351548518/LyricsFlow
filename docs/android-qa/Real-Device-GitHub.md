# GitHub 真机联调与等待状态修订

日期：2026-10-27（Asia/Shanghai）。设备：HONOR MLY-AN00，序列号 `ABJR026916006401`，Android API 37。使用 Android CLI 安装、页面检查和截图，ADB 运行设备测试。没有清除应用数据、退出真实账号或撤销用户授权。

## 真实账号与私有仓库

- 产品负责人提供公开 Client ID `Ov23liBtXrG7lSZomxKz`，并在手机完成 GitHub 浏览器授权。
- App 显示账号 `2351548518`，仓库 `2351548518/lyricsflow-sync`，仓库 ID `1435776961`，默认分支 `main`。连接与上传代码从 GitHub 官方 API 校验 `private=true`、账号所有者和写入权限，不将私有仓库当作公开访问测试。
- 真实上传预览：主题 0 项、已保存歌词 3 首、歌词数据 197 项、绑定 197 项，约 2172 KB。当前手机没有可上传的自定义背景文件，因此即使勾选背景，预览仍准确显示不含背景。
- 已点击确认上传，完成 GitHub 提交；随后真实下载并校验完整快照，预览数量和体积一致。[上传预览](./github-sync/real-device/upload-preview.png)、[下载预览](./github-sync/real-device/download-preview.png)。
- 为保留手机当前资料及在线翻译授权，本轮对真实下载选择取消，没有把云端快照应用到真实用户数据。恢复前副本、合并和关闭付费授权的写入规则由下述隔离测试验证，不能称为真实用户恢复已实测。
- 升级并强制重启后登录仍保留。[重启后的登录页面](./github-sync/real-device/login-preserved.png)。同步记录中的基线提交为 `4e306b04af1eb3bc7ddbf5da0bd50bf44feace63`；没有读取、导出或记录 Token 明文。
- 最终修订 APK 再次连接真实仓库，私有性与快照检查通过，保留在正式账号页面：[最终连接状态](./github-sync/real-device/private-repository-connected.png)。

以上为真实单设备远端读写证据。两设备同账号同步、真实撤权/到期刷新、真实并发写入尚未补测。此前用户完成的初次建仓不作为助手逐步观察建仓过程的证据；本轮直接验证已存在的真实私有仓库。

## 本轮修订

1. **默认 Client ID**：将开发者提供的公开标识设为默认值，可直接登录，也保留自注册配置能力；不包含 Secret。
2. **初始申请可取消**：原界面只有拿到设备码后才显示取消按钮。现在申请验证码阶段即可取消，避免网络未响应时无退出入口。
3. **45 秒总截止时间**：仅连接/读取超时不足以覆盖厂商 DNS、排队与写入。HTTP 工作放入受限后台线程池，使用可取消 continuation + 总超时；取消时中断工作并在后台断开，迟到响应不交付。上传超时提示重新检查云端，不能保证服务端未提交。
4. **重启提示准确**：原状态即使恢复了有效账号，也沿用“尚未登录”的初始文案。现在显示“已登录 @账号；请重新连接私有仓库”。连接标记保持会话内校验，不假装已重验远端。
5. **真机截图兼容**：首次 UI 测试遇到系统 `UiAutomationService already registered / Not connected`，失败日志保留在 [首次记录](./github-sync/real-device/instrumentation-first-ui-service-conflict.log)。改用 Compose 根窗口 PixelCopy 采集测试图片后重跑通过；没有跳过测试断言，也未通过删除用户无障碍配置解决。

初次申请验证码时确实观察到长时间等待，随后用户完成授权。因现场网络状态发生变化，没有把这次等待直接归因于某个确定的网络协议故障；本轮明确修复的是不可取消等待、缺少总截止时间及错误初始文案。

## 验证结果

| 验证 | 结果 | 证据 |
| --- | --- | --- |
| 真实登录、私有仓库连接、上传、下载校验与预览 | 通过 | 上述真实页面、仓库身份及同步基线 |
| 真机 GitHub 专项 | 12 项通过 | [最终日志](./github-sync/real-device/instrumentation.log) |
| 模拟器本地回归 | 25 项通过 | [回归日志](./github-sync/real-device/emulator-regression.log)；最终仅重启文案及相应断言增量由真机回归覆盖 |
| JVM 单元测试 | 25 项通过 | Android CLI 构建任务执行 |
| 编译、主 APK / 测试 APK、Lint | 通过；0 错误、38 警告 | [构建](./github-sync/real-device/build.log)、[Lint](./github-sync/real-device/lint.txt) |
| 格式与 diff 空白检查 | 通过 | ktfmt dry-run、git diff --check |

真机 12 项包含：8 项原有同步协议/恢复测试、2 项界面测试、2 项新增网络策略测试。新增测试模拟不响应中断/断开的厂商 I/O，验证总截止时间及时返回并丢弃迟到结果；另验证设备码尚未返回即可取消。协议测试使用独立状态文件、加密槽和恢复文件，不使用真实账号写入假资料。真实账号操作仅通过正式 App 页面执行。

最终 APK SHA-256：`F23DDCDBE0A250B125BEE941336578C23A15F2CD5B421A781351469BEBA48759`。已安装真机，测试后重启正式入口，保留真实账号；未保留测试假账号界面。

```powershell
# 本地设备全套：25 项；加 -IncludeNetwork 为 39 项
.\scripts\Run-AndroidQa.ps1 -Device emulator-5554
# 真机专项：先安装 app-debug.apk 与 app-debug-androidTest.apk
adb -s ABJR026916006401 shell am instrument -w -r -e disableAnalytics true -e class com.example.lyricsflow.GitHubNetworkPolicyTest,com.example.lyricsflow.GitHubSyncTest,com.example.lyricsflow.GitHubUiTest com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
```
