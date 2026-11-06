# LakeSoul 对 TRUNCATE TABLE 的支持分析

> 分析时间：2026-08-13 16:07（Asia/Shanghai）
> 问题：在 LakeSoul 中执行 `TRUNCATE TABLE` 是否可行？

## 结论

**可以，但仅限 Spark 侧。** LakeSoul Spark 模块完整实现了 `TRUNCATE TABLE`（SQL 与 API 两种入口，分区表/非分区表均支持，有测试覆盖）。Flink、Presto 侧没有 truncate 入口。

## 支持入口

### 1. SQL（Spark）

```sql
TRUNCATE TABLE lakesoul_test
```

调用链：Spark Catalyst → `LakeSoulTableV2.truncateTable()`（[LakeSoulTableV2.scala](file:///e:/workspace/workspace/LakeSoul/lakesoul-spark/src/main/scala/org/apache/spark/sql/lakesoul/catalog/LakeSoulTableV2.scala#L222-L224)）→ `TruncateTableCommand.run`。

### 2. API（Spark）

```scala
LakeSoulTable.forPath(path).truncateTable()
```

[LakeSoulTable.scala](file:///e:/workspace/workspace/LakeSoul/lakesoul-spark/src/main/scala/com/dmetasoul/lakesoul/tables/LakeSoulTable.scala#L556-L558) → [LakeSoulTableOperations.scala](file:///e:/workspace/workspace/LakeSoul/lakesoul-spark/src/main/scala/com/dmetasoul/lakesoul/tables/execution/LakeSoulTableOperations.scala#L233-L240)。

### 3. 测试覆盖

[DDLSuite.scala](file:///e:/workspace/workspace/LakeSoul/lakesoul-spark/src/test/scala/org/apache/spark/sql/lakesoul/DDLSuite.scala#L486-L530)：`Truncate TABLE`（非分区）与 `Truncate TABLE with partitions`（分区）均验证 truncate 后查询为空。

## 实现行为

[DropTableCommand.scala](file:///e:/workspace/workspace/LakeSoul/lakesoul-spark/src/main/scala/org/apache/spark/sql/lakesoul/commands/DropTableCommand.scala#L47-L61) `TruncateTableCommand.run`：

1. `sleep(DROP_TABLE_WAIT_SECONDS)` 等待元数据缓存失效；
2. `dropPartitionInfoByTableId` —— 清空 PostgreSQL 中该表所有分区版本记录；
3. `dropDataInfoData` —— 清空 data_info 文件记录；
4. 使 Snapshot 缓存失效；
5. `fs.delete(表路径, recursive=true)` —— **物理删除整个表目录**。

注意：**保留 `table_info`（表结构/属性）**，truncate 后表仍存在、可继续写入；与 `DropTableCommand` 的区别仅在于不删 table_info / short_table_name。

## 风险与注意事项

| 风险 | 说明 |
|---|---|
| 并发不安全 | truncate 直接物理删文件，若此时有 Flink 流作业写入或 Presto/Spark 查询在跑，会读失败或写冲突。**执行前需停写停查** |
| 历史不可恢复 | partition_info 全清 + 文件物理删除，time travel / 回滚能力全部丧失，无回收站机制 |
| 无逐分区 truncate | 语法上是整表清空；只想清单个分区应使用 `DropPartitionCommand`（仅加空快照版本、不删文件）或删除谓词 |
| Flink/Presto 无入口 | Flink SQL `TRUNCATE TABLE` 未实现（模块内无任何 truncate 代码）；Presto 同理。跨引擎场景只能走 Spark |
| 与 V2 `SupportsTruncate` 区分 | `LakeSoulTableV2` 的 `truncate()` 是 DataFrameWriterV2 `writeTo(...).truncate()` 的覆盖写语义，与 SQL TRUNCATE 路径不同 |

## 数据流示意

```
TRUNCATE TABLE t (Spark SQL)
   │
   ▼
LakeSoulTableV2.truncateTable()
   ▼
TruncateTableCommand.run(snapshot)
   ├── PG: 清空 partition_info（所有版本）+ data_info
   ├── 失效 Snapshot 缓存
   └── 文件系统: 删除表目录全部文件（table_info 保留）
   ▼
表结构保留，数据与历史版本清空，可继续写入
```

## 不确定项 / 假设

- 未实测 truncate 与正在运行的 Flink sink / 进行中 compaction 的并发行为，从实现看无并发保护，属于人为运维约束；
- `DROP_TABLE_WAIT_SECONDS` 的具体取值未核对（来自 `MetaUtils`），影响 truncate 耗时。

## 后续可选方向

1. 结合前篇（Presto 查询变慢场景）评估：是否可用 truncate 代替 compaction 做"全量重灌"运维；
2. 验证并发场景行为（写一份测试）；
3. 其他指定方向。
