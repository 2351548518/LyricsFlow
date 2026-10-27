# DeepSeek 真实翻译验证

日期：2026-10-26。使用产品负责人指定的本地 API.md 凭证文档。凭证内容没有复制到本文、源代码或 APK。

## 配置与兼容修复

- Base URL：`https://api.deepseek.com`。
- 模型：`deepseek-flash`；由账户模型列表实际确认可用。
- 路由：`/chat/completions`，通过应用现有 HTTPS 和 Bearer 鉴权链路请求。
- 按 [首次调用说明](https://api-docs.deepseek.com/zh-cn/) 和 [Chat Completions 参数](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/) 使用当前模型名称。官方主机请求显式传 `thinking.type=disabled`、JSON 输出格式与 `stream=false`；这些扩展不会发送给其他兼容 API 主机。
- 根据批次输入长度设置 512～8192 的输出 token 上限；本次短句批次均为 512。翻译读取超时为 45 秒，歌词／评论仍保留原先 12 秒设置。
- 拒绝截断或中断的 completion；行 ID 必须是非负整数，不能重复、缺失或越界；空译文、非文本译文和过长译文均不写入。

## 手机端结果

Android API 37 手机运行 [4 项翻译专项测试](./deepseek-emulator-5554.log)，全部通过：3 项协议边界测试，1 项真实服务集成测试。

真实集成测试共发起 **2 次翻译请求**，只使用自有短句：

1. 未授权时调用被拒绝，请求计数不增加。
2. 授权后手动翻译两行英／日文，返回非空中文；第三行纯中文保持原文，不重复请求翻译。
3. 原文、行顺序和时间戳保持一致，译文标记为 `machine`，记录模型名并写入本地歌词缓存。
4. 再次调用翻译直接复用已完成译文，计数没有增加。
5. 开启自动翻译后，一行新的英文自动完成翻译并落库；累计请求计数为 2。
6. 实际页面显示原文 `Let the morning find us slowly` 与译文“让清晨慢慢找到我们”：见 [截图](./deepseek-phone.png)、[UI 树](./deepseek-phone-layout.json)。
7. 测试期间导出备份，检查解压内容不含 Key；结束后恢复原有服务配置、授权开关和加密凭证，删除本次测试缓存与临时凭证文件。

测试验证的是短句可用性、对齐和缓存行为，不能据此推导所有语言、整首长歌词、翻译质量、日限额跨日或长期服务可用性全部通过。请求次数有记录；没有查询账户余额或将输出上限当作实际计费 token 用量。

## 凭证隔离

凭证从外部文档读取后，经子进程 stdin 传到所选模拟器的应用私有目录，不作为 ADB 参数或环境变量传递。仪器测试读取后立即删除明文临时文件，临时配置使用应用现有 Keystore 加密存储，最后恢复原配置。

额外扫描了 130 个工程文本文件和 2 个解压后的 APK，没有发现该 Key；检查确认私有临时文件已删除。默认 QA 脚本不运行这项收费服务测试；即使直接运行全部仪器测试，没有显式 opt-in 也会跳过真实调用。

## 复现

```powershell
.\scripts\Run-DeepSeekTranslationQa.ps1 `
  -ApiConfigPath '外部凭证文档的绝对路径\API.md' `
  -Device emulator-5554
```

API.md 使用 `URL:` 和 `Key:` 两个字段。脚本仅接受官方 HTTPS 主机；默认选择 `deepseek-flash`。每次执行会进行两次小批次真实请求，需有可用账户额度。`-CaptureWindow` 会在测试完成时保留约 20 秒界面，供 Android CLI 截图。

普通回归仍使用 `Run-AndroidQa.ps1`；专项测试类为 `TranslationProtocolTest` 和 `DeepSeekTranslationIntegrationTest`。

## 更新后的构建回归

10 项 JVM 单元测试通过；手机 4 项原有核心回归、平板 7 项协议与核心回归通过。构建及 Lint 仍为 0 错误，37 警告；见 [构建日志](./deepseek-build.log)、[Lint](./deepseek-lint.txt)、[平板回归](./deepseek-tablet-regression.log)。新 APK 已安装到两台虚拟机。
