# 本轮任务步骤
- [x] 读取 `doc/compaction.sql`，确认入口 SQL
- [x] 定位 `LakeSoulTable.compaction` 对应源码入口与调用链
- [x] 梳理快照生成流程（事务/版本/快照可见性）
- [x] 梳理 compaction 文件块合并流程（候选文件、重写、提交）
- [x] 输出一页式结论与检查清单
- [x] 梳理 compaction 详细参数与提交方式（SQL/API/任务）
- [x] 评估 Linux cron 定时提交可行性并给出命令模板
- [x] 将 compaction 详细参数写入 `doc` 文档
- [x] 补充“回挂 Hive 分区”说明（含示例）

## uzs-lakesoul-spark-auto-compaction（本轮）
- 问题：`newCompaction` 在分区内仍按 level/阈值分批，难满足“整分区一次聚合”。
- 假设：保留通知监听链路，新增 UZS 前缀链路可避免影响现有任务行为。
- 方案：新增 `UZSNewCompactionTask` + `LakeSoulTable.uzsFullPartitionCompaction` + `UZSFullPartitionCompactBucketIO`。
- 影响：仅新增链路，旧 `NewCompactionTask` 与 `newCompaction` 不改动。
- 验证：确认按单分区触发时只走一次整分区读写与一次元数据提交。
- [x] 新增 `UZSNewCompactionTask`（复制通知监听链路）
- [x] 新增 `UZSFullPartitionCompactBucketIO`（整分区一次聚合）
- [x] 在 `LakeSoulTable` 新增 `uzsFullPartitionCompaction` 并接入新 IO
- [x] 更新 `README.md` 的新使用方式
- [x] 方案B：统一命名（`UZSPartitionCompactionTask` -> `UZSNewCompactionTask`）并同步文档

## 外部湖仓导入触发（待确认）
- 问题：`partition_insert()` 依赖版本阈值，外部直接拷贝元数据/文件时可能不触发通知。
- 方案A：新增“补偿扫描任务”按 `partition_info` 增量扫描并主动触发 UZS 整分区聚合（推荐）。
- 方案B：新增 `partition_snapshot_state` 状态表，触发器+状态表共同判重与节流后 notify。
- 方案C：保留触发器不变，导入后执行一次手工 SQL 批量 `pg_notify`。
- [ ] 你确认采用哪种触发策略（A/B/C）

## 状态表DDL评审（本轮）
- 目标：评估 `archive_init.sql` 两张表是否覆盖“自动快照入口 + Flink转储”。
- 结论：方向正确，但当前 `archive_init.sql` 字段偏少；`archive_init copy.sql` 更接近可用版本。
- 缺口：缺少统一状态机、重试阶段、任务ID、失败码/错误信息与可筛选索引。
- 建议：以 `copy` 版本为基线收敛为正式 DDL，再接入触发入口改造。
- [x] 完成两份 DDL 对比评审
- [ ] 你确认是否采用 `copy` 版字段集作为正式表结构

## UZS轮询触发改造（方案B，本轮）
- 问题：`UZSNewCompactionTask` 仍依赖 LISTEN/NOTIFY，未对接领取/回写接口。
- 假设：接口按 `tableId+partitionDesc+version+claimType` 做幂等与冲突校验。
- 方案：串行轮询 `getCompactionTask`，执行后 `setTaskDone`；失败走 `setTaskErr` 并退避。
- 影响：不再关注数据表通知，改由任务服务派发，链路改为 long-running polling worker。
- 验证：覆盖无任务、成功、回写冲突、执行失败、网络失败重试等分支。
- [x] 改造 `UZSNewCompactionTask` 为轮询接口触发
- [x] 增加 `setTaskDone` 优先回写与失败重试机制
- [x] 增加错误分级退避（失败5分钟、无任务短轮询）
- [x] 更新 `README.md` 的新参数与使用方式
