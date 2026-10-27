# QQ 音乐 / 网易云 API 接入调研与适配依据

> 版本：1.1  
> 核实日期：2026-10-26（Asia/Shanghai）  
> 用途：为 [LyricsFlow PRD 1.1](./LyricsFlow-PRD.md) 的首版歌词及评论适配提供依据。  
> 研究范围：官方公开文档、社区项目文档与源码、小规模匿名只读请求。初次调研时未登录账户、未申请平台权限、未部署代理或实现 Android 适配器；后续原生实现验证补充在第 8 节。

## 1. 结论与证据等级

1. **QQ 与网易云都应列入 V1 优先适配**：需要搜索 / 详情、歌词、热门评论三个闭环，不能只预留评论空页面。
2. **存在无需自建服务的可行路径**：本次匿名直连拿到了 QQ 歌词、新版 QQ 热评，以及网易云搜索、歌词、普通评论与热评。成功仅限所测样本和当前网络，不代表获得正式开放平台授权或覆盖所有歌曲。
3. **QQ 评论应优先新版接口**：旧 `fcg_global_comment_h5.fcg` 多种已文档化参数组合本次返回 HTTP 500；`music.globalComment.CommentRead/GetHotCommentList` 返回成功业务码与真实评论。
4. **QQ 自动搜索仍需重点验证**：最小 Desktop 搜索请求返回成功业务码但候选为空。较新社区源码已经采用 Mobile 搜索；不能把 `code=0` 当作自动匹配成功。
5. **网关是可选兼容路径**：V1 支持指定社区 API 协议的 Base URL，供用户选用可信已有服务或自行部署；不自动寻找公共代理，也不把代理等同于接口一定可用。

| 等级 | 含义 | 本次达到情况 |
| --- | --- | --- |
| E1 文档 | 已读取项目维护者文档或源码，确认接口契约 | 下文列出的社区模块 |
| E2 单点请求 | 当前机器、当前网络、特定参数取得有效业务数据 | QQ 歌词 / 新热评；网易云搜索 / 歌词 / 评论 / 热评 |
| E3 产品链路 | 从 Spotify 元数据自动匹配同版本歌曲，再展示歌词与评论 | 未执行，纳入 PRD M0 / M2 |
| E4 实机兼容 | Android 网络、解析、缓存、翻页、错误恢复稳定通过 | 未执行，纳入首版验收 |

## 2. 官方渠道核实

### 2.1 QQ / 腾讯

[腾讯云物联网开发平台音乐服务](https://cloud.tencent.com/document/product/1081/67456) 提供 `window.h5PanelSdk.qqMusic` 下的搜索、歌曲详情和 `describeLyric` 等能力，文档要求相应登录授权，使用上下文是腾讯连连 H5 / IoT。该页未列出歌曲评论能力，不能将其直接等同于普通 Android 歌词伴侣可匿名调用的开放 REST API。

因此，官方渠道保留为有资格时的后续选项；当前 V1 先评估非官方只读协议。本文不假设存在一个已经取得权限、且涵盖 QQ 全部歌词和评论的官方应用 API。

### 2.2 网易云

原始需求提供的 [歌词相关页面](https://developer.music.163.com/st/developer/document?docId=803202bd65bc469587d05b507dcd31e7) 和 [评论相关页面](https://developer.music.163.com/st/developer/document?docId=c7ab2954a5a04fdd86d01c88d811786c) 在本次工具访问中未取得有效正文；一次直接访问发生 TLS EOF。无法据此确认当前申请条件、签名、配额和第三方歌词应用的展示权限。

以下网易云 API 方案依据社区项目和样本请求，明确标记为非官方协议，不以官方开放平台文档为其背书。

## 3. 社区项目选择

| 项目 | 作用 | 首版采用方式 |
| --- | --- | --- |
| [sansenjian/qq-music-api](https://github.com/sansenjian/qq-music-api) | Koa / TypeScript；公开搜索、歌词和评论文档，接续相关生态 | 固定网关协议候选；按固定版本源码校对参数和响应 |
| [jsososo/QQMusicApi](https://github.com/jsososo/QQMusicApi) | Express 项目；有歌词、搜索、评论及历史接口语义 | 兼容协议与旧接口参考，不默认使用作者托管服务 |
| [L-1124/QQMusicApi](https://github.com/L-1124/QQMusicApi) | 较新的 Python SDK；有 Mobile 搜索、新版热评和响应模型 | 新版 QQ 原生协议的主要研究依据；Android 不嵌入 Python 运行时 |
| [Rain120/qq-music-api](https://github.com/Rain120/qq-music-api) | 原始需求指定的 QQ 项目 | 保留参考；不能把其路由与其他同名项目混为一套契约 |
| [NeteaseCloudMusicApiEnhanced/api-enhanced](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced) | 搜索、歌词、YRC、评论的模块与文档完整 | 网易云固定网关协议及直连请求映射依据 |
| [Binaryify/NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) | 历史项目的 GitHub 仓库当前标记 archived，页面说明不再维护 | 只用于历史对照，不把仓库星数当作当前维护保证 |

同名 QQ 项目参数并不相同。不能仅替换 Base URL，就把 `/getComments`、`/comment` 和新版 musicu 模块视为相同接口。引用、复用或分发社区代码时记录版本与许可证；不因协议参考而假定所有项目都是 MIT。

## 4. QQ 接口与字段

### 4.1 平台标识

- `songmid` / `mid` 是字符串标识，常用于歌词与详情。
- 数字 `songid` / `id` 用于评论对象、详情等；在应用模型中同样按字符串保存，避免精度损失。
- 搜索应尽量同时取得两者；只有 MID 时，通过详情解析数字 ID，再请求评论。
- QQ 的 `biztype=1` / `BizType=1` 表示歌曲；不能直接复用网易云的 `type=0`。

### 4.2 原生只读适配目标

| 能力 | 地址 / 模块 | 关键输入 | 输出 / 状态 |
| --- | --- | --- | --- |
| 搜索首选研究路径 | `POST https://u.y.qq.com/cgi-bin/musicu.fcg`，`music.search.SearchCgiService/DoSearchForQQMusicMobile` | `query`、`search_type`、`num_per_page`、`page_num`、当前客户端公共参数 | 新 SDK 已使用，需按固定协议验证有效候选，不能照抄旧 Desktop 请求 |
| 搜索兼容 | `https://c.y.qq.com/soso/fcgi-bin/client_search_cp` | `w`、`p`、`n`、`t=0`、`format=json` | 历史搜索；本次观察到业务成功响应，但没有确认有效歌曲候选，另一次 TLS EOF |
| 歌曲详情 | musicu 的 `music.pf_song_detail_svr/get_song_detail_yqq` | `song_mid` 等与所选版本匹配的字段 | 解析 `track_info.id / mid`，不请求音频地址 |
| 行级歌词 | `https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg` | `songmid`、`format=json`；其余参数按固定版本维护 | `lyric`、`trans`；可能 Base64 或已解码文本，需按协议识别 |
| 歌词增强 | musicu 的 `music.musichallSong.PlayLyricInfo/GetPlayLyricInfo` | SDK 当前定义的 `songMid` / `songId`、`qrc`、`trans`、`roma` 等 | 逐字 / 注音候选路径；字段大小写与编码按版本固定，不能混用另一项目的 `songMID / songID` |
| 热评首选 | musicu 的 `music.globalComment.CommentRead/GetHotCommentList` | `BizType=1`、数字 `BizId`、`PageNum`、`PageSize`、`LastCommentSeqNo`、`HotType=1` | 本次拿到有效 `CommentList.Comments` |
| 旧评论兼容 | `https://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg` | `topid`、`biztype=1`、`cmd`、`pagenum`、`pagesize` 等 | 本次多次 HTTP 500，不作为唯一评论实现 |

新版搜索、热评和歌词在同一个 musicu HTTP 入口使用不同模块；外层 `code`、子模块 `code` 和业务 `SubCode` 都要检查。请求公共参数、签名 / 会话要求由传输适配器统一处理，不散落在页面；本次未完成最新 Mobile 搜索公共参数的完整复现。

主要依据：[新版搜索源码](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/search.py)、[新版评论源码](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/comment.py)、[新版歌词源码](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/lyric.py)、[旧歌词源码](https://github.com/jsososo/QQMusicApi/blob/master/routes/lyric.js)、[旧详情源码](https://github.com/jsososo/QQMusicApi/blob/master/routes/song.js)。

### 4.3 固定社区网关协议

| 协议 ID | 搜索 | 歌词 | 歌曲热评 | 注意 |
| --- | --- | --- | --- | --- |
| `qq-sansenjian-v1` | `GET /getSearchByKey?key=…&limit=20&page=1` | `GET /getLyric?songmid=…`，优先原始字符串模式 | `GET /getComments?id=<数字ID>&biztype=1&cmd=6&pagenum=0&pagesize=20`，后续页按控制器要求传根评论游标 | 文档 / 控制器对响应、排序和翻页的描述需实测对齐；`cmd=8` 路径也可能附带热评 |
| `qq-jsososo-v1` | `GET /search?key=…&t=0&pageNo=1&pageSize=20` | `GET /lyric?songmid=…` | `GET /comment?id=<数字ID>&biztype=1&type=1&pageNo=1&pageSize=20` | wrapper 通常使用 `result=100` 表示成功，不能按网易云 `code=200` 判断 |

依据：[Sansenjian 搜索](https://sansenjian.github.io/qq-music-api/api/search.html)、[歌词](https://sansenjian.github.io/qq-music-api/api/music.html)、[评论](https://sansenjian.github.io/qq-music-api/api/comments.html)、[评论控制器](https://github.com/sansenjian/qq-music-api/blob/main/src/controllers/getComments.ts)、[jsososo 评论实现](https://github.com/jsososo/QQMusicApi/blob/master/routes/comment.js)。

Sansenjian 控制器将 `id` 映射为上游 `topid`，默认 `pagenum=0`、`cmd=8`；后续页对 `rootcommentid` 有校验，并映射到 `lasthotcommentid`。不能只看到文档中的 reqtype 排序列表就假定能够正确取热评 / 翻页。网关透传了旧评论接口时，本次观察到的旧接口故障仍可能存在。

### 4.4 新版 QQ 评论规范化

从子模块 `data.CommentList.Comments[]` 提取：

| 上游字段 | 统一字段 |
| --- | --- |
| `CmId` | `commentId`，不透明字符串 |
| `SeqNo` | 翻页序列，不能与评论 ID 混用 |
| `Nick` | `authorName` |
| `Content` | `content`；安全处理富文本 / 表情标记 |
| `Avatar` | 可选头像 URL |
| `PraiseNum` | 可选点赞数 |
| `PubTime` | 按固定协议单位转为 `publishedAtMs` |
| `CommentList.HasMore` | `hasMore` |

新版社区分页实现同时递增 `PageNum` 并把末条 `SeqNo` 传回 `LastCommentSeqNo`。本次返回的 `NextOffset=0` 但 `HasMore=1`，因此不能仅靠 NextOffset 推断下一页；实际返回数量也可能超过 PageSize。应用按评论 ID 去重，连续一页无新评论或游标不前进就停止自动续页，允许手动重试。

依据：[响应模型](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/models/comment.py)、[分页策略](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/comment.py)。

### 4.5 本次成功的最小 QQ 热评请求

`POST https://u.y.qq.com/cgi-bin/musicu.fcg`，`Content-Type: application/json`，正常只读请求使用 `Referer: https://y.qq.com/`，不携带个人 Cookie。下列为本次探测的样本参数，不代表客户端公共参数永远有效：

```json
{
  "comm": { "ct": 24, "cv": 0, "format": "json" },
  "comments": {
    "module": "music.globalComment.CommentRead",
    "method": "GetHotCommentList",
    "param": {
      "BizType": 1,
      "BizId": "97773",
      "LastCommentSeqNo": "",
      "PageSize": 1,
      "PageNum": 0,
      "HotType": 1,
      "WithAirborne": 0,
      "PicEnable": 1
    }
  }
}
```

本次解析位置为 `comments.data.CommentList.Comments`；外层 `code`、`comments.code` 均为 0，列表实际有 2 条，并非请求的 1 条。`comments` 是请求批次别名，业务代码应按本机发出的别名定位子响应。

## 5. 网易云接口与字段

### 5.1 社区网关契约与上游映射

下表左列 `/search` 等是用户配置的社区网关路由，不是可以直接拼到 `music.163.com` 的官方开放平台路径。

| 能力 | 社区网关路由 | 主要参数 | 社区源码中的上游路径 |
| --- | --- | --- | --- |
| 搜索 | `GET /search` 或 `/cloudsearch` | `keywords`、`type=1`、`limit`、`offset` | `/api/search/get` / `/api/cloudsearch/pc` |
| 详情 | `GET /song/detail` | `ids=<数字ID>` | `/api/v3/song/detail` |
| 歌词 | `GET /lyric` | `id=<数字ID>` | `/api/song/lyric` |
| 逐字歌词 | `GET /lyric/new` | `id=<数字ID>` | `/api/song/lyric/v1` |
| 热评首选 | `GET /comment/hot` | `id`、`type=0`、`limit=20`、`offset` / `before` | `/api/v1/resource/hotcomments/R_SO_4_<id>` |
| 普通评论 / 附带热评 | `GET /comment/music` | `id`、`limit`、`offset` / `before` | `/api/v1/resource/comments/R_SO_4_<id>` |
| 新版排序兼容 | `GET /comment/new` | `id`、`type=0`、`sortType=2`、`pageNo`、`pageSize`、可选 cursor | `/api/v2/resource/comments` |

社区文档对普通歌词、旧歌曲评论与热门评论描述为无需登录，但实际仍可能受到网络、地区、频率、接口版本和会话状态影响。新版评论的 `sortType=1/2/3` 对应推荐 / 热度 / 时间；源码会把推荐映射为 99，并构造排序相关 cursor。不能把不同排序的 cursor 交叉复用。

直接传输先使用已验证的匿名只读形式；复杂 weapi / eapi 等模式需要完整请求协议实现或配置兼容网关，不能只改 URL。不要启用社区项目中的随机 IP、批量重试或其他非本产品所需行为。

依据：[维护者完整文档](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/public/docs/home.md)、[搜索](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/search.js)、[逐字歌词](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/lyric_new.js)、[热评](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/comment_hot.js)、[歌曲评论](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/comment_music.js)、[新版评论](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/blob/main/module/comment_new.js)。

### 5.2 歌词与评论映射

- 歌词保留 `lrc.lyric`、`tlyric.lyric`、`romalrc.lyric`；新版可提供 `yrc.lyric` 及对应翻译 / 注音字段。没有某字段是正常情况，不代表整次请求失败。
- YRC 行形如 `[startMs,durationMs](wordStartMs,wordDurationMs,flag)text`，词开始时间按已验证 YRC 语法为歌曲绝对毫秒，不二次叠加行起点；署名 JSON 行独立处理。
- 热评使用 `hotComments[]`；普通评论首屏也可能带此字段，允许在热评接口故障时作为真实热评降级，但不假装可翻遍完整热评列表。
- 新版排序响应的 `data.comments[]` 按所选固定版本解析，不与旧接口顶层 `comments[]` 混淆。
- `commentId` → 字符串 ID，`user.nickname` → 用户名，`content` → 正文，`user.avatarUrl` → 头像，`likedCount` → 点赞数，`time` → 毫秒时间戳。
- `more / hasMore`、时间 cursor、offset 只在所属端点和排序内有效；按评论 ID 去重。用户对象缺失时显示“匿名用户”，不补造用户资料。

### 5.3 本次成功的网易云匿名请求形式

以下均为本次从当前机器发出的上游只读 GET，不是官方开放平台签名契约，也不是社区网关根路径：

```text
GET https://music.163.com/api/search/get/web?s=<URL编码关键词>&type=1&limit=1&offset=0
GET https://music.163.com/api/song/lyric?id=186016&lv=-1&kv=-1&tv=-1
GET https://music.163.com/api/v1/resource/comments/R_SO_4_186016?limit=1&offset=0
GET https://music.163.com/api/v1/resource/hotcomments/R_SO_4_186016?limit=1&offset=0
```

使用正常的 `Referer: https://music.163.com/`，不携带用户 Cookie。验证业务 `code=200` 和目标字段，而不是仅看 HTTP 状态；这些样本不证明 `/lyric/new`、`/comment/new` 的直连协议也已验证成功。

## 6. 匿名只读探测记录

探测不携带用户 Cookie / 账号；不输出、存档或转录歌词及评论正文。下表是一次会话的结果，不是长期可用率与性能报告。

| 探测项 | 样本 / 参数概要 | 观察结果 | 能支持的结论 |
| --- | --- | --- | --- |
| QQ 行级歌词 | MID `0039MnYb0qxYhV`，旧歌词 HTTPS 端点 | HTTP 200，业务码 0，有非空 lyric 字段 | 当前匿名直连行级歌词有可行样本；编码与翻译需按版本验证 |
| QQ 旧评论 | 数字 ID `97773`；cmd 6，及补齐公共参数后的 cmd 6 / 8 | 均 HTTP 500 | 不能作为唯一评论路线，不能据此断言所有 QQ 评论接口失效 |
| QQ 新热评 | 数字 ID `97773`，`GetHotCommentList` | HTTP 200，外层 / 子模块码均 0，`Comments` 有 2 条；CmId / Nick / Content 齐全；请求 PageSize=1 | 新热评存在真实只读可行路径；数量和分页不能按请求值想当然 |
| QQ Desktop 搜索 | “晴天 周杰伦”及“晴天”，最小公共参数 | 业务码 0，但 `body.song.list=[]` | 尚未验证自动匹配；需要验证 Mobile 协议或有效网关 |
| QQ 旧搜索 | client_search_cp | 一次 HTTP 200 / code 0，未确认有效候选；另一次 TLS EOF | 不作为已完成搜索闭环证据 |
| 网易云搜索 | `/api/search/get/web`，关键词“晴天 周杰伦”，limit=1 | HTTP 200 / code 200，1 条歌曲候选 | 当前匿名搜索样本可行；该路径与网关 `/search` 不同 |
| 网易云歌词 | 数字 ID `186016`，`/api/song/lyric` | HTTP 200 / code 200，非空 lrc | 当前行级歌词样本可行 |
| 网易云普通评论 | `R_SO_4_186016`，limit=1 | HTTP 200 / code 200，1 条普通评论，另有 15 条 hotComments | 当前匿名歌曲评论及附带热评可行 |
| 网易云热评 | `R_SO_4_186016`，limit=1 | HTTP 200 / code 200，1 条 hotComments | 当前匿名热评样本可行 |

没有测试：最新 Mobile 搜索完整参数、QQ 逐字解码、网易云新歌词 / 新评论的直连传输、各社区网关的部署实例、Android 真机与跨平台自动匹配。不能把这些写成“已通过”。

## 7. V1 实施顺序与完成证据

1. 固定所参考项目的实际 commit / release，记录路径与响应样本结构；调研页中的 main / master 链接仅便于追溯，不能当作不可变版本锁定。
2. 两个平台分别实现 `CatalogAdapter`、`LyricsProvider`、`CommentProvider`；Transport 与业务提供者分离，避免换网关改变逐曲来源 ID。
3. 先落实已成功的网易云读链路和 QQ 新热评 / 行级歌词，再把 QQ 搜索与 MID / 数字 ID 详情补全设为 M0～M2 优先难点。
4. QQ 自动匹配失败时，允许用户粘贴官方歌曲链接或填写已知 ID / MID，由详情校验后绑定；只接受歌 / 单曲类型的白名单链接，不抓取任意网页或任意短链跳转。
5. V1 提供固定协议的可选网关配置与连接测试，便于在直连故障时切换；不得把 Cookie 发送到未经用户确认的公共服务。
6. QQ 与网易云分别证明：匹配或人工绑定 → 真实歌词 → 同平台同版本真实热评。把每个平台的成功、缺失、失败原因和翻页能力独立记录。
7. 发布目标是两边都可读；最低需要至少一个平台的真实热评链路通过。另一平台若被外部条件阻断，仍须交付适配器、测试、故障说明和后续修复任务，不能把两边都空白的页面算作评论适配完成。

PRD 第 8、11、12、16、17、23、24 节定义产品行为、模型和验收。后续 API 变动以复测记录更新本文，不能沿用一次成功无限期宣称稳定。

## 8. Android 开发阶段补充验证（2026-10-26）

本节补充前文调研阶段记录，不将尚未验证的 Mobile 搜索或社区网关写成已通过。原生实现和完整限制见 [Android 实现说明](./Android-Implementation.md)。

### QQ 搜索补充

Desktop 搜索为空或失败时，改用维护者源码列出的 [Smartbox 快速搜索](https://github.com/L-1124/QQMusicApi/blob/main/qqmusic_api/modules/search.py) 路径：

```text
GET https://c.y.qq.com/splcloud/fcgi-bin/smartbox_new.fcg
  ?key=<URL 编码的歌名与歌手>&format=json
Referer: https://y.qq.com/
```

使用“晴天 周杰伦”实际返回 `code=0`，`data.song.count=1`，`data.song.itemlist[0]` 提供数字 ID `97773` 和 MID `0039MnYb0qxYhV`。原生适配器随后请求歌曲详情，补全专辑与时长，再交给匹配或人工确认。候选未返回时仍显示未匹配，不把成功业务码当作候选。

### 手机与平板结果

| 链路 | 验证结果 |
| --- | --- |
| QQ 搜索＋详情 | Android 测试断言候选中包含上述真实 ID；手动 MID 读取也在手机界面完成确认 |
| QQ 歌词 | 样本解析 63 行；歌词与数字 ID／MID 指向相同歌曲 |
| QQ 热评／最新 | 实际返回 21／20 条；原生评论页已视觉核对，热门数量超出请求值时未裁成伪造分页 |
| 网易云搜索 | Android 返回 20 条候选 |
| 网易云歌词 | ID `186016` 实际解析 62 行 |
| 网易云热评／最新 | 实际返回 20／20 条；手机原生页面已显示真实用户名、正文、时间与点赞数 |

对应原始日志见 [手机](./android-qa/providers-phone.log)、[平板](./android-qa/providers-tablet.log) 和 [验收记录](./android-qa/README.md)。搜索／详情可以命中之前取得的真实缓存；设备测试中歌词及评论的强制刷新项会实际请求服务。

平板复测曾发生网易云 `UnknownHostException`，已保留 [失败日志](./android-qa/instrumentation-tablet-dns-failure.log)，随后同一 APK 重试成功。平台和网络可用性不是长期承诺；没有使用示例评论绕过失败。
