# GitHub 登录与私有仓库同步

日期：2026-10-27。实现为可选登录、手动快照同步，无自建服务。入口：**我的 → GitHub 同步**。

开发者已提供公开 Client ID `Ov23liBtXrG7lSZomxKz`，当前 APK 默认填入，可直接登录，无需每位用户另行注册。支持手动换成自己注册的 Client ID；下文注册步骤用于自建应用或未来开源分发。真机已完成真实登录、私有仓库连接、上传和下载预览，见 [真机验证记录](./android-qa/Real-Device-GitHub.md)。

## 首次注册（只需注册 OAuth App，无需提供 Secret）

1. 登录 GitHub，进入 [注册 OAuth App](https://github.com/settings/applications/new)。菜单路径：Settings → Developer settings → OAuth Apps → New OAuth App。
2. Application name 填 `LyricsFlow`。Homepage URL 填你的项目主页或个人主页的公开 HTTPS 地址。
3. Authorization callback URL 可以填写同一主页的 HTTPS 地址。此实现采用 Device Flow，不接收 Web 回调，不需要搭建回调服务；该字段仍按 GitHub 注册表单要求填写。
4. 勾选 **Enable Device Flow**。可以保留默认的到期用户 Token 设置，客户端已支持到期刷新。
5. 注册完成，复制 **Client ID**。它是公开标识，不是密码。不要把 Client Secret 或 GitHub 密码填入 App。
6. 在 App 填 Client ID → 保存并登录 GitHub → 复制设备验证码并打开 GitHub 授权 → 输入验证码并确认 → 返回 App 等待结果。
7. 登录成功后，仓库名默认 `lyricsflow-sync`。点击“连接 / 创建私有仓库”；如果不存在会以 **private=true** 创建，存在则检查隐私、所有者、写入权限和默认分支。不会把公开仓库改为私有，也不会上传到公开仓库。

注册依据：[Creating an OAuth app](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app)。Device Flow 的服务端要求、轮询和错误处理依据：[Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps)。

最初开发阶段仅有假服务测试；后续用户提供 Client ID 并在真机完成浏览器授权，已补测真实远端读写。真实账户授权由用户操作，不代输 GitHub 密码或验证码。

## 授权范围与凭证

- 本阶段为满足“无后端、自动创建个人私有仓库”，采用 OAuth App Device Flow，申请 `repo` scope。此权限可读写该用户有权访问的公开和私有仓库，**不能由 OAuth scope 限制到唯一同步仓库**。App 在登录前明确告知；实际代码只操作选定个人仓库中的 `lyricsflow` 目录。
- 当前 GitHub 文档同时推荐细粒度 GitHub App；旧 PRD 的 GitHub App 方案保留为未来收窄授权的方向，当前不混称为 GitHub App 安装授权。若采用该方案，需另行实现仓库创建权限、安装和仓库选择链路。
- 公开资料通过 `GET /user` 校验身份；不申请 email、组织管理、删除仓库等额外 scope。`repo` 自身较广的权限不能被本机使用约束抵消。
- Token 与 refresh token 通过 Android Keystore AES-GCM 加密，独立于翻译 Key 保存；两种 Key 的清除互不影响。凭证文件和本机 GitHub 同步状态同时排除 Android 云备份和设备迁移。
- Device Flow 不包含 Client Secret。轮询遵循服务返回 interval，`slow_down` 后至少增加 5 秒；拒绝、取消、到期停止。进程终止后重新登录，不持久化设备码。
- 每个 HTTP 请求有 45 秒总截止时间，包含排队、DNS、连接、写入与读取。取消通过可取消 continuation 立即返回 UI，并在后台中断/断开连接，迟到响应不再交付。申请验证码的初始阶段也能取消；并不要求先收到验证码才允许取消。已发送的上传请求超时不代表服务端一定没提交，应重新检查云端。
- 对短期 Token，在操作前提前刷新，刷新接口省略 Secret；校验返回 Token 的账号 ID，再替换加密凭证。操作串行，不会并发刷新。刷新失效需退出重新登录；HTTP 401 清除本机凭证并提示重新授权。
- 退出只清本机授权，保留本地资料、恢复副本和远端仓库。可通过 App 内“查看或撤销 GitHub 授权”在 GitHub 撤销；App 不调用删仓、改可见性或远端文件删除接口。

权限依据：[OAuth scopes](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/scopes-for-oauth-apps)、[Create a repository for the authenticated user](https://docs.github.com/en/rest/repos/repos#create-a-repository-for-the-authenticated-user)。

## 上传哪些资料

```text
lyricsflow/
├── manifest.json    # lyricsflow.backup / schemaVersion 1.0
├── data.json        # binding、lyrics、saved、style 四种实体
└── settings.json    # 显示设置、逐曲来源/评论来源与偏移；可选背景图片
```

- 主题以原始声明式 JSON 保存于 `data.json` 的 style 记录，包含已导入主题、首页及悬浮窗的活动主题；不执行脚本。配置和数据复用本地备份格式，可重组为 `.lfbackup.zip` 通过同一校验器恢复。
- 歌词数据包含已存歌词和本地缓存的原文/译文，不仅限于收藏。上传前显示各类条目数、未压缩体积和背景是否包含，用户确认后提交。请确认这些内容适合保存在 GitHub。
- 自定义背景默认不上传，可手动开启；图片用 Base64 放入 settings。未包含图片时不删除设备现有背景，新设备缺失图片会按现有渲染逻辑回退。
- 不上传评论缓存、网络响应缓存、播放历史、翻译 API Key、GitHub Token、Client ID、API 服务地址、模型及授权同意、系统权限、悬浮窗位置。
- 私有仓库文件可被账号本人、被授予权限的人和平台访问，并非端到端加密；旧内容会留在 Git 历史中。关闭“包含背景”不会抹除旧提交的图片。
- 与本地备份一致：总读取/展开上限 20 MB，数据库正文上限 16 MB / 3000 项；背景需通过尺寸与解码检查。导出、下载、恢复均校验格式、路径、记录类型、歌词和主题 Schema，不接受任意共享偏好文件。

## 同步与冲突规则

1. 本机先连接仓库，读取默认分支及快照。首次连接有数据的仓库时，必须先下载并确认合并，才允许上传。
2. 上传先在本机生成预览；确认后用 Git Blobs、Trees、Commits API 创建完整提交，最后 **force=false** 更新分支。保留其他目录和文件。多文件快照在分支可见时已完整；失败的未引用对象不会被视作成功快照。
3. 本机保存最近成功同步/确认恢复的 commit SHA，并绑定账号 ID、仓库 ID、默认分支。远端有本机尚未接收的版本时阻止上传。提交期间有其他设备抢先写入，非 fast-forward 更新被拒绝，不强推。
4. 无内容变化时跳过提交。上传前重新检查仓库仍为私有、仓库身份和默认分支不变。若外部同时修改可见性，API 无法把隐私检查和提交做成同一事务；请保持专用仓库私有。
5. 下载从固定 commit SHA 对应的 tree/blob 读取全部文件，显示恢复预览，不立刻写本机。确认时重新检查远端版本，变化则要求重新下载。
6. 应用前在 `noBackupFilesDir/github-sync-recovery.zip` 原子保存最近一次本机备份。写入失败则停止恢复。通过“导出恢复前备份”，再用本地备份导入恢复。
7. 数据库以事务合并：本地独有项保留，同 ID 采用云端版本。显示配置按云端恢复；付费翻译同意和自动翻译关闭，Key 不覆盖。数据库、偏好、图片属于不同存储，整体恢复不宣称跨存储事务；异常时可使用恢复副本。
8. 同步错误不会删除本地歌词。目录含未知文件/符号链接、不兼容版本、路径冲突、公开仓库、所有者不符或规则集阻止提交时提示原因。网络/限流失败后由用户重试，不无限重试。

提交依据：[Git trees](https://docs.github.com/en/rest/git/trees#create-a-tree)、[Git blobs](https://docs.github.com/en/rest/git/blobs#get-a-blob)、[Update a reference](https://docs.github.com/en/rest/git/refs#update-a-reference)。

## 当前边界与后续

当前提供的是**手动快照上传 + 下载确认合并**。没有周期后台任务、自动双向三方合并、删除墓碑、同 ID 冲突保留双份或版本选择器；删除不跨设备传播。发生同 ID 冲突，可先导出本机备份，下载后确认是否接受远端，再上传合并结果。不宣称完整的多端自动同步协议已实现。

未提供 GitHub Enterprise、组织仓库、任意远端地址、PAT 旁路或 OAuth Web 回调。个人私有仓库满足当前需求。未来可迁移到安装级细粒度 GitHub App、内容寻址对象与显式冲突解决，但需兼容现有快照。

## 实现与验证

- `data/GitHubApi.kt`：固定 GitHub HTTPS 主机、无重定向、限长响应、仓库与快照读写、原子 Git 提交。
- `data/GitHubSync.kt`：串行设备授权/刷新、同步状态、预览、基线与恢复副本。
- `data/BackupRestore.kt`：与本地导入共用的配置恢复逻辑。
- `ui/GitHubSettings.kt`：注册指引、授权码、私有仓库、预览、恢复与退出。
- 测试与截图：[GitHub 验收记录](./android-qa/GitHub-Sync.md)。
