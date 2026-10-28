# 来源选择与搜索分页 UI 修订（2026-10-28）

两页统一使用 `FlowProviderRow`：QQ音乐、网易云、AMLL、LRCLIB 横向一行、等宽按钮，保留选中填色、边框、勾选标记和 RadioButton 语义。按钮高度至少 44 dp；较窄窗口或较大系统字体下通过横向滚动保持单行，不强行缩小系统字号。仅缩短这两个选择器的显示名称，不修改提供方枚举或已存数据。

“选择歌词来源”底部使用 `FlowMatchActions`，把“重新自动匹配”和“歌曲匹配”放在同一行，均使用同样的描边按钮、字号、圆角和内边距。实际匹配、记忆及同步行为不变。

## 重复加载按钮

旧 `SearchSheet` 在列表前后各渲染一次分页按钮；搜索全部失败时，失败游标仍有值，而 rows 为空，两个按钮因而连续出现。

- 只保留列表上方一个分页入口，与列表间仍有 16 dp 留白。
- 首次搜索失败且没有候选时不显示分页，用户可用“搜索歌曲”重新查询。
- 已有候选、后续加载失败时保留原列表和游标，仅显示一个“重试加载”。成功后正常继续分页，结果仍按提供方 ID 去重。
- 忙碌或无分页游标时不再重复发起翻页。
- 缺少具体说明的 `Failed requirement.` 显示可读中文提示；有具体错误的接口仍保留原错误原因。此修订不意味着服务端 503 或所有数据校验错误已被修复。

## 验证

- 构建、JVM **42 项通过**；Lint **0 错误 / 37 警告**：[构建](./source-layout/build.log)、[Lint](./source-layout/lint.txt)。
- 新增 **3 项专项通过**：两个选择器的单行坐标/短名称、底部按钮同一行与同高、初次错误无分页及可重试、翻页失败仅一处重试且原结果保留：[专项](./source-layout/targeted-tests.log)。
- 默认模拟器 **64 项回归通过**：[日志](./source-layout/regression.log)。首轮仅有旧可读性测试还按原按钮名称定位，已改为稳定 provider tag 后复验；保留 [旧断言日志](./source-layout/old-label-assertion-failure.log)。
- LRCLIB 入口/选中状态/记录 ID 输入额外 **1 项通过**：[日志](./source-layout/lrclib-entry-test.log)，此次未向真实服务发起检索。
- 已检查模拟器截图：[来源弹窗](./source-layout/source-dialog.png)、[匹配窗口](./source-layout/matching-sheet.png)、[首查失败](./source-layout/first-failure.png)、[翻页重试](./source-layout/page-retry.png)。搜索失败由注入的分页假响应复现，未向真实服务制造错误请求。
- 真机一度断开，用户重新连接后已安装新版。已目视确认 [真机来源弹窗](./source-layout/real-source-dialog.png)、[真机匹配窗口](./source-layout/real-matching-sheet.png) 四个来源均在一行、底部按钮并排且样式一致；检查后关闭匹配窗，没有改变当前歌曲的歌词绑定。

完整可选 81 项含网络用例未全跑。超大字号下保留横向滚动是单行布局的兼容设计，不代表所有四个按钮在任意窗口宽度都同时完整可见。

APK SHA-256：`002DCC7965E28759ACE549802E2721230BB98805CC03B84A16FA3A427EE12FF3`。
