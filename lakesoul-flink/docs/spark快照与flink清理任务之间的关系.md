# Spark快照与Flink清理任务之间的关系

- Spark `UZSNewCompactionTask` 只拉任务并调用 `LakeSoulTable.uzsFullPartitionCompaction()`；真正快照提交在 `LakeSoulTable -> MetaCommit -> DBManager.commitData`。
- `partition_info.snapshot`：最终在 `DBManager.commitData(...)` 计算；`Append/Merge` 追加新 `commit_id`，`Compaction/Update` 通常替换为新 `commit_id`，若中间夹入 `Append/Merge` 会合并增量，因此可能大于 `1`。
- `partition_info.version`：由 `DBManager.commitData(...)` 基于当前最新版本自增；JDBC 路径新分区首版为 `0`。
- `partition_info.timestamp`：Spark 不显式写入，由 `partition_info` 表默认值 `now()` 毫秒在插入时生成，表示该版本行入库时间。
- `discard_compressed_file_info`：由 Spark compaction 成功后 `MetaCommit.recordDiscardFileInfo(...)` 写入；UZS 全分区压缩场景下，来源就是本次 compaction 输入的旧文件列表。
- Flink `NewCleanJob` 的 snapshot 清理链路监听 `partition_info` CDC，按 `partition_info.timestamp/version/snapshot` 判断哪些旧版本可删，再删旧 snapshot 对应文件、`data_commit_info`、`partition_info`。
- Flink 的 discard 链路定时扫描 `discard_compressed_file_info`，只删文件和该表记录，不删 `partition_info` / `data_commit_info`。
- 时间字段对照：
| 字段 | 生成位置 | 含义 |
| --- | --- | --- |
| `partition_info.timestamp` | PG 默认值 | 版本行入库时间 |
| `data_commit_info.timestamp` | Spark `setTimestamp(System.currentTimeMillis())` | 本次 commit 元数据写入时间 |
| `discard_compressed_file_info.timestamp` | Spark 取旧文件 `modification_time` | 被废弃旧文件的文件时间 |
- 结论：两条清理链路互补不互斥；文件目标可能重叠，但重复删除按“文件不存在也算成功”处理，主要差异在时间基准不同，可能出现“旧文件先删、旧 snapshot 元数据后删”的窗口。
