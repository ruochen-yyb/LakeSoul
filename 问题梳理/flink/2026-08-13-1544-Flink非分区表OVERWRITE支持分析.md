# LakeSoul Flink Connector 对非分区表 OVERWRITE 支持分析

> 分析时间：2026-08-13 15:44（Asia/Shanghai）
> 问题：LakeSoul Flink connector 对非分区表的 OVERWRITE 是否支持？

## 结论

**不支持。** Flink connector 虽然实现了 Flink 的 `SupportsOverwrite` 接口（因此 `INSERT OVERWRITE` SQL 能正常解析、不报错），但 overwrite 标志被接收后从未被消费，实际提交始终为 `AppendCommit`，语义等同于追加写。该问题对分区表与非分区表同样存在，非分区表无任何 overwrite 语义。

## 分析依据（数据流）

### 1. 入口：`LakeSoulTableSink` 实现了 `SupportsOverwrite`

文件：`lakesoul-flink/src/main/java/org/apache/flink/lakesoul/table/LakeSoulTableSink.java`

- L50-51：`implements DynamicTableSink, SupportsPartitioning, SupportsOverwrite, ...`
- L62：`private boolean overwrite;`
- L198-200：`applyOverwrite(boolean newOverwrite)` 仅把标志存入字段

### 2. 关键缺陷：`overwrite` 字段是"死字段"

- 全模块检索 `overwrite`（大小写不敏感），`LakeSoulTableSink.overwrite` 只有两处写入（`applyOverwrite`、拷贝构造函数），**没有任何读取点**。
- `createStreamingSink()`（L125-172）构建 sink 时完全不参考该字段，也未将其写入 `flinkConf`。
- `applyStaticPartition(Map)`（L203-204）为空实现，静态分区 overwrite 同样无效。

### 3. 提交层：只产生 Append/Update/Delete，绝无 OverwriteCommit

文件：`lakesoul-flink/src/main/java/org/apache/flink/lakesoul/sink/committer/LakeSoulSinkCommitter.java`（L150-158）

```java
if (DELETE.equals(dmlType))            -> UpdateCommit
else if (PARTITION_DELETE.equals(...)) -> DeleteCommit
else if (UPDATE.equals(...) && 无主键)  -> UpdateCommit
else                                   -> AppendCommit   // INSERT OVERWRITE 走这里
```

`DataCommitInfo` 中所有文件均为 `FileOp.add`，没有覆盖/替换旧文件的逻辑。

### 4. 对比：Spark 侧的 overwrite 是如何实现的

- Spark 通过 `LakeSoulOptions`（`replaceWhere` / `overwriteSchema`）+ `OverwriteCommit` 提交类型实现覆盖写；
- Flink 侧虽拷贝了 `LakeSoulOptions.scala`，但 sink 链路中未引用这些选项。

### 5. 测试现状的误导性

`LakeSoulLookupJoinCase.java` L544-556 对**非分区非哈希表** `bounded_table1` 执行了 `insert overwrite`，但仅向空表写入一次，无法区分 append 与 overwrite 语义，测试通过不代表功能可用。

## 实际影响

| 表类型 | `INSERT OVERWRITE` 行为 |
|---|---|
| 非分区、无主键 | 数据重复追加（旧数据不删除），结果错误 |
| 非分区、有主键 | 追加后 merge-on-read 按主键去重，表面看似覆盖，但旧文件未清理，非主键语义数据仍残留 |
| 分区表 | 同样只是追加，分区不会被清空 |

## 数据流示意

```
INSERT OVERWRITE SQL
   │ Flink Planner: 检查 SupportsOverwrite → 通过
   ▼
LakeSoulTableSink.applyOverwrite(true) ──► overwrite=true（死字段，无人读取）
   ▼
createStreamingSink() ──► LakeSoulMultiTablesSink（与 append 完全相同的路径）
   ▼
LakeSoulSinkCommitter.commit()
   └── DML_TYPE 为空 ──► CommitOp = AppendCommit（FileOp.add）
   ▼
PostgreSQL 元数据：新文件追加进快照，旧文件依然有效
```

## 不确定项 / 假设

- 未实际运行作业验证（需 PostgreSQL + Flink 环境），结论基于静态代码分析，证据链完整、风险低。
- 未检查 native Rust 层是否存在其他覆盖入口；Flink Java 侧 commit 路径唯一，可排除。

## 后续可选方向

1. 在 `LakeSoulTableSink` 消费 `overwrite` 标志，传递至 committer，提交 `OverwriteCommit`（或先 `DeleteCommit` 清理旧分区再追加）；
2. 若暂不支持，则在 `applyOverwrite` 中显式抛异常，避免静默产生错误数据；
3. 补充非分区表多次 `INSERT OVERWRITE` 的断言测试。
