# GitHub 登录与同步专项验收

日期：2026-10-27。范围：[注册与协议](../GitHub-Sync.md)、[PRD 第 18 节](../LyricsFlow-PRD.md#18-github-账户与同步方案手动快照)。

本文保留最初模拟器验证证据。后续用户已提供 Client ID 并完成真机登录，最新真实仓库读写与超时修订见 [真机记录](./Real-Device-GitHub.md)，下文“没有真实 Client ID”仅指初始验证阶段。

## 结论与限制

实现可选 OAuth Device Flow、到期刷新、个人私有仓库创建/连接、手动上传、下载预览与恢复副本。手机 23 项本地设备回归、平板 10 项 GitHub 专项均通过；25 项 JVM 单测通过，Lint 0 错误、38 警告。

**没有真实 OAuth Client ID，未完成真实 GitHub 账号授权、真实建仓、真实远端读写和两设备账号同步联调。** 专项协议测试使用独立内存假服务与假 Token，不访问 GitHub，不使用用户现有凭证。截图中的 `qa-user` 是测试账号文字，不代表已登录真实 GitHub。真机未连接，本轮仅在两个模拟器安装调试。

## 环境与证据

| 设备 | API | 本轮验证 |
| --- | --- | --- |
| 手机 `emulator-5554` | 37 | 23 项设备回归；实际 App 我的页 → GitHub 同步导航与未登录页面检查 |
| 平板 `emulator-5556` | 35 | 8 项协议/恢复测试 + 2 项 Compose UI 测试 |

- [构建日志](./github-sync/build.log)：Android CLI 编译主 APK、测试 APK、单元测试及 Lint。
- [手机 23 项日志](./github-sync/phone-tests.log)、[平板 10 项日志](./github-sync/tablet-tests.log)。
- [Lint 明细](./github-sync/lint.txt)，保留现有提示；凭证与同步基线需可靠落盘的同步写入，代码在 IO 线程执行并有说明，未关闭整体 Lint。
- [正式 App 未登录入口截图](./github-sync/phone/app-login.png)、[手机注册指引](./github-sync/phone/registration.png)、[手机上传预览](./github-sync/phone/upload-preview.png)、[平板恢复预览](./github-sync/tablet/download-preview.png)。截图已实际查看。

最终 APK：`app/build/outputs/apk/debug/app-debug.apk`，SHA-256：`7AA982D66D1E8B069CE26B23D35813125D3CB2225277F3D65DA1B85C624F0E1F`。已安装两台模拟器，测试完成后重启为生产入口，未保留假账号登录界面。

## 协议与安全用例（GitHubSyncTest，共 8 项）

| 用例 | 验证内容 | 结果 |
| --- | --- | --- |
| 私有创建与公开拒绝 | GET 404 后 POST `private=true` + `auto_init=true`；现有公开仓库拒绝，不修改可见性 | 通过 |
| 完整上传与下载 | 三文件生成一个提交，base tree 保留其他目录；固定 commit 读取恢复；相同内容不重复提交 | 通过 |
| 未接收版本与并发写入 | 基线不符不上传；上传期间其他设备更新时 force=false 非快进被拒绝，远端未被覆盖 | 通过 |
| 目录与版本安全 | 未知/不安全路径与不兼容 manifest 在恢复前拒绝 | 通过 |
| 设备轮询、刷新与隔离 | pending → slow_down → 成功；等待 5、5、10 秒（注入时钟等待，非真实耗时）；到期刷新无 Secret；校验用户；加密存储；退出不清其他 Key；导出无凭证 | 通过 |
| 拒绝授权 | 首次拒绝即终止轮询，清验证码，保留未登录状态 | 通过 |
| 恢复与本机回退 | 确认前不写本机；同 ID 云端覆盖、本地独有保留；保存恢复前原记录；字号恢复；付费同意及自动翻译关闭 | 通过 |
| 预览后云端变化 | 再检查 commit，不一致停止；本机偏好不变、不生成不必要的恢复副本 | 通过 |

## UI 用例（GitHubUiTest，共 2 项）

1. 未填写 Client ID 时登录按钮不可用；可展开/收起注册指引；填写公开 ID 后按钮可用。
2. 登录后未连接仓库时不能上传；连接后先显示上传预览，确认前没有写远端；确认才提交；下载后先显示恢复确认，取消不恢复。

测试使用独立 GitHub 状态文件、独立加密槽和独立恢复文件，测试后清理；恢复测试归还原显示偏好。并未创建对外可触发的假登录入口。

## 复现

```powershell
# 构建、25 JVM 单测、Lint，手机 23 项不依赖第三方网络的设备测试
.\scripts\Run-AndroidQa.ps1 -Device emulator-5554
# 平板只跑 GitHub 专项（先安装主包和测试包）
adb -s emulator-5556 shell am instrument -w -r -e disableAnalytics true -e class com.example.lyricsflow.GitHubSyncTest,com.example.lyricsflow.GitHubUiTest com.example.lyricsflow.test/androidx.test.runner.AndroidJUnitRunner
```

`-IncludeNetwork` 现为 37 项；本轮没有重跑歌词第三方网络专项，提供者逻辑未修改。

## 注册后待完成的真实联调

- GitHub OAuth App 启用 Device Flow；浏览器同机授权、返回 App、取消、过期、撤权、Token 刷新。
- 在真实个人账号创建默认私有仓库，检查实际隐私与目录；上传主题、歌词、配置、可选图片，另一设备下载恢复。
- 验证默认分支名称不是 main 时的行为、仓库规则集拒绝、限流、网络中断和并发提交；确保报错不强推、不丢本机资料。
- 对较大快照验证内存、网络耗时与磁盘不足；当前仅有格式/大小边界和小快照测试，未将 20 MB 极限压力视为通过。
- 当前为手动快照合并，不把三方自动合并、删除传播或后台同步算作已完成。
