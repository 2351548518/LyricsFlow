# 大备份 ZIP 与完整校验测量

这是优化前的历史基线。后续实现与同轮对照结果见[大备份同步编解码优化与复测](./Backup-Codec-Optimization.md)。

日期：2026-11-04。结论：重复编解码值得优化；优先复用首次完整校验的解析结果，单纯减少 ZIP 压缩不是主要收益点。

## 测试条件

- 真机 HONOR MLY-AN00，Android API 37；独立 `com.example.lyricsflow.qa` Debug 仪器测试包，Java 最大堆 384 MiB。
- 数据由固定随机种子生成，包含原文、整句翻译、罗马音、词组对应及逐字时间戳。不读取正式应用的数据库、凭证或真实备份，不连接网络。
- 三份快照分别有 22／211／633 条歌词记录，共 704／6,752／20,256 行；三个固定快照文件与生产格式相同。不包含图片，随机文本的压缩率不代表所有真实歌词。
- 每个数据量、每个步骤先预热一次，再测 5 次，轮换步骤顺序；共 75 个记录。使用实际生产函数 `SnapshotFiles.zip/unzip` 与 `Transfer.validateBackup`，不改生产流程。
- 每次测量前请求 GC 并等待 120 ms，不计入耗时；以 5 ms 间隔采样已用 Java 堆，记录 ART 分配计数及 GC 次数。采样可能漏掉极短峰值，累计分配包括已回收对象，并非常驻内存；堆指标不是进程 PSS。
- 只测本地编解码／数据校验。SQLite 查询、备份前图像读取、GitHub 网络与服务器提交不计入，因此不是完整同步耗时。Debug 构建及采样也不能代表 Release 的绝对时延。

## 本地上传预览链路

生产路径本地部分相当于：

1. `Transfer.backup` 形成 ZIP；测量时以同格式的 `SnapshotFiles.zip(files)` 代替数据库读取及组装。
2. `SnapshotFiles.unzip` 先调用 `validateBackup` 解压并解析全部记录，再解压一次得到文件字节。
3. 预览摘要 `describe(files)` 再执行 `validateBackup(SnapshotFiles.zip(files))`。

合计两次压缩、三次解压、两轮完整 JSON／歌词校验。测量返回准备好的文件映射，解析结果仅用于消费记录数量；不把解析结果额外保留为待上传数据。

所有值取 5 次中位数。数据量使用十进制 MB；内存使用 MiB。

| 未压缩数据 | ZIP 文件 | 本地链耗时 | 线程 CPU 时间 | 累计分配 | 额外 Java 堆峰值估计 | 步骤内 GC |
| --- | --- | --- | --- | --- | --- | --- |
| 0.52 MB | 0.14 MB | 316.55 ms | 302.22 ms | 18.0 MiB | 9.1 MiB | 2 |
| 5.01 MB | 1.30 MB | 2,657.13 ms | 2,550.60 ms | 192.4 MiB | 64.7 MiB | 8 |
| 15.02 MB | 3.91 MB | 8,266.12 ms | 7,681.42 ms | 525.2 MiB | 88.6 MiB | 22 |

大组的已用 Java 堆峰值估计中位数为 109.2 MiB，包含约 20.6 MiB 的步骤前基线；没有发生 OOM。不能据此保证所有设备及含图片的真实快照均不会 OOM。

## 各步骤拆分

| 实际调用 | 0.52 MB | 5.01 MB | 15.02 MB |
| --- | --- | --- | --- |
| 单次 ZIP 压缩 | 28.13 ms | 257.55 ms | 798.30 ms |
| ZIP 解压＋完整校验 | 127.72 ms | 1,057.89 ms | 3,141.49 ms |
| 生产 `unzip`：完整校验＋再次解压 | 137.34 ms | 1,093.60 ms | 3,350.39 ms |
| 文件映射压缩＋完整校验 | 161.64 ms | 1,295.75 ms | 3,975.83 ms |
| 上传预览本地编解码链 | 316.55 ms | 2,657.13 ms | 8,266.12 ms |

15 MB 组单次完整校验累计分配约 222.3 MiB，单次压缩约 14.1 MiB。由拆分结果可判断，反复展开 JSON 并构建歌词对象的完整校验，比压缩本身更值得优先消除重复。这是分步测量推断，没有采集 Simpleperf 栈样本，不能再细分 JSON 库、字串与具体歌词字段各占多少 CPU。

## 波动与后续方向

上传预览本地链的 5 次范围：小组 313.08–335.49 ms，中组 2,624.28–2,804.48 ms，大组 5,035.17–8,534.58 ms。结束后设备报告 Thermal Status 2；没有测试前温控基线，不能把变化单独归因于温控、CPU 调度或 ART。大组绝对耗时存在明显波动，勿当作固定延迟。

建议按以下顺序改造，再用相同数据复测：

1. 第一次校验同时返回文件映射、已解析记录和摘要计数；摘要、上传及恢复复用该结果。外部文件发生变化后必须重新校验，不能直接删除安全检查。
2. 为已在内存中的快照文件映射提供直接校验入口，避免为了校验而再次 ZIP 压缩／解压。
3. ZIP 仅在真正导出或保存恢复文件时编码，并考虑流式写入减少大 ByteArray 的临时复制。

本次只测量，没有实施以上生产重构，因此没有“优化后提升百分比”。

## 复现与证据

测试类：[BackupCodecBenchmark.kt](../../app/src/androidTest/java/com/example/lyricsflow/BackupCodecBenchmark.kt)。默认跳过，需同时使用 `.qa` 包和显式参数，避免普通回归意外运行压力测试：

```powershell
adb -s <serial> shell am instrument -w -r `
  -e backupCodecBenchmark true `
  -e class com.example.lyricsflow.BackupCodecBenchmark `
  com.example.lyricsflow.qa.test/androidx.test.runner.AndroidJUnitRunner
```

不传参数会以 JUnit assumption 跳过，已实际验证。三个用例的原始指标见 [results.json](./backup-codec-20261104/results.json)、[中位数汇总](./backup-codec-20261104/summary.json)和 [75 条 CSV 样本](./backup-codec-20261104/samples.csv)。完整仪器日志、阶段间 meminfo 和测试后的 thermalservice 输出保存在忽略提交的 `dist/backup-benchmark-20261104/`；阶段间 meminfo 不作为峰值 PSS。独立测试包已卸载，正式应用保留。
