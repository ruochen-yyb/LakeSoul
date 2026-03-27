# auto clean 当前实现说明

本文仅根据当前 `lakesoul-flink` 仓库中的实现整理，不再展开无法在本仓库直接验证的外部 Spark 侧细节。

## 1. 结论

- `NewCleanJob` 当前包含两条清理链路：
  - 定时清理 `discard_compressed_file_info`
  - 基于 `partition_info` CDC 的历史版本清理
- 两条链路都使用作业级参数 `dataExpiredTime`，当前 Flink 代码**没有**读取表属性 `partition.ttl` / `compaction.ttl`。
- `discard_compressed_file_info` 清理只删除：
  - 废弃文件
  - 对应 `discard_compressed_file_info` 记录
- 历史版本清理会删除：
  - 旧文件
  - 对应 `data_commit_info`
  - 对应 `partition_info`
- 历史版本清理已做“一致性保护”：
  - 文件删除成功后，才会删除 PG 元数据
  - 元数据删除放在同一个 JDBC 事务里
  - 失败时回滚并保留状态，等待下次重试

## 2. 作业入口与参数

入口类：`src/main/java/org/apache/flink/lakesoul/entry/clean/NewCleanJob.java`

当前可确认的关键参数：

- `ontimer_interval`
  - 单位是分钟
  - 默认值是 `5`
  - 实际执行时会乘以 `60000`
- `dataExpiredTime`
  - 默认值是 `3`
  - 当值 `< 10` 时，按“天”解释并乘以 `86400000`
  - 当值 `>= 10` 时，直接按毫秒使用
- `targetTableName`
  - 可选
  - 用于先查 `table_name_id` 拿到 `table_id` 列表，再过滤 `partition_info` CDC 事件
  - **不影响** `discard_compressed_file_info` 的定时清理范围

当前固定监听：

- CDC 表：`public.partition_info`

## 3. 链路一：定时清理 discard 文件

调用路径：

1. `TickSource`
2. `TickTriggeringCleaner.flatMap1(...)`
3. `CleanUtils.cleanDiscardFile(...)`

实际行为：

- 每次 tick 到来时，新建 JDBC 连接执行一次清理。
- 查询 SQL：

```sql
SELECT file_path
FROM discard_compressed_file_info
WHERE timestamp < ?
```

- 阈值为：
  - `System.currentTimeMillis() - dataExpiredTime`
- 对每条记录逐个处理：
  - 先删文件
  - 文件删除成功后，再删 `discard_compressed_file_info` 记录
  - 文件删除失败时，保留元数据，等待后续重试

边界说明：

- 这条链路**不会**删除 `partition_info`。
- 这条链路**不会**删除 `data_commit_info`。
- 这条链路当前只按时间筛选，**不按表过滤**。

## 4. 链路二：基于 CDC 的历史版本清理

调用路径：

1. `public.partition_info` CDC
2. `PartitionInfoRecordGets.metaMapper(...)`
3. `TickTriggeringCleaner.flatMap2(...)`
4. `NewCleanJob.ProcessClean`

状态与判断逻辑：

- key 为 `table_id + "/" + partition_desc`。
- `compactNewState` 记录当前分区最新压缩时间。
- `willState` 暂存待判断、待清理的历史版本。
- `compactionVersionState` 用于区分是否按“旧版压缩目录”方式删除。

触发清理的核心条件：

- 对同一分区，如果某历史版本的 `timestamp < 最新压缩时间 - dataExpiredTime`，则该版本可进入清理。
- `AppendCommit`、`MergeCommit`、`CompactionCommit`、`UpdateCommit` 会走不同分支，但最终都围绕上面的时间窗口判断。
- `snapshot.size() > 1` 的压缩提交会被识别为并发提交，不会作为新的压缩基线写入 `compactNewState`。

定时器行为：

- 每个 key 首次到达时注册处理时间定时器。
- 定时器周期为 `ontimer_interval`。
- 定时器触发时，会再次扫描 `willState`，把已到期但上次未删的数据继续尝试清理。
- 如果发现对应分区已不存在，会清空当前 key 下的状态。

## 5. 文件删除与元数据删除顺序

实现位置：`src/main/java/org/apache/flink/lakesoul/entry/clean/CleanUtils.java`

历史版本清理使用：

- `cleanSnapshotAndPartitionInfo(...)`

处理顺序：

1. 从 `data_commit_info.file_ops` 收集待删路径
2. 通过 Flink `FileSystem` 删除文件
3. 文件全部删除成功后，开启 JDBC 事务删除元数据
4. 事务中依次删除：
   - `data_commit_info`
   - `partition_info`
5. 提交事务

失败处理：

- 查询 `file_ops` 失败：本次不删元数据
- 文件删除失败：本次不删元数据
- 删除 `data_commit_info` 或 `partition_info` 失败：事务回滚
- 回滚后，`ProcessClean` 保留状态，等待下一次 timer 重试

## 6. 文件删除实现细节

当前删除文件不是直接走本地文件 API，而是走 Flink `FileSystem`：

- 入口：`deleteFile(...)` -> `deleteFiles(...)` -> `deleteByFlinkFS(...)`
- 目的：复用 Flink 集群已加载的文件系统插件和配置

对象存储相关逻辑：

- 对 `s3` / `s3a` / `s3n` 路径，会先检查 bucket 连通性
- 连通性检查结果会按 bucket 缓存在内存中
- 如果存储不可达，则判定本次删除失败，不继续删元数据
- 如果文件已不存在，当前实现按“已清理”处理，不视为失败

旧版压缩目录处理：

- 当 `oldCompaction=true` 时，删除目标不是单个文件，而是把路径截到 `compact_` 目录级别后再删除

## 7. 当前实现边界

- 当前 Flink 代码中，`dataExpiredTime` 是**全局过期时间**。
- 当前 Flink 代码中，未看到读取 `table_info.properties` 中表级 TTL 的逻辑。
- `targetTableName` 只影响 `partition_info` CDC 过滤，不影响 `discard_compressed_file_info` 清理。
- `discard_compressed_file_info.timestamp` 在当前 Flink 代码里只被当作比较基准使用；其写入来源不在本仓库内。

## 8. 启动示例的实际含义

```bash
flink run \
  -c org.apache.flink.lakesoul.entry.clean.NewCleanJob \
  lakesoul-flink-xxx.jar \
  --source_db.host 127.0.0.1 \
  --source_db.port 5432 \
  --source_db.dbName lakesoul \
  --source_db.user lakesoul \
  --source_db.password xxxxxx \
  --slotName cleanjob_slot \
  --plugName pgoutput \
  --url jdbc:postgresql://127.0.0.1:5432/lakesoul \
  --ontimer_interval "1" \
  --dataExpiredTime "1"
```

这条命令的当前实际行为：

- 每 `1` 分钟触发一次 discard 文件清理
- `dataExpiredTime=1` 会按 `1 天` 解释
- 同时运行：
  - `discard_compressed_file_info` 定时清理
  - `partition_info` 历史版本清理
- 两条链路共享同一个全局过期时间 `1 天`

## 9. 2026-03 批量预取改造

- 变更点：`discard_compressed_file_info` 清理新增参数 `discardBatchSize`，默认 `1000`。
- 变更点：查询改为按 `(timestamp, file_path)` 做 keyset 分页，不再一次性读全量结果。
- 变更点：Java 侧按批顺序处理；查一批、删一批、再查下一批，不再使用双 list 预取。
- 原因：过期记录达到百万/千万级时，原实现会把结果集长期堆在 JDBC/堆内存，存在 OOM 风险。
- 影响：只改 discard 文件清理链路，不改变“删文件成功后再删 PG 记录、失败保留重试”的语义。
- 删元数据：文件已删除，或文件已不存在（含 reachable 后返回 `404/not found/nosuchkey`）时，删除 `discard_compressed_file_info` 记录。
- 不删元数据：对象存储不可达、`fs.delete(...)` 抛非 not found 异常、或 `fs.delete(...)` 返回 `false` 且文件仍存在时，保留记录待下次重试。
- 回滚：移除 `discardBatchSize` 参数透传，并恢复 `cleanDiscardFile` 的单次全量扫描实现。
- 验证：本次未执行编译或测试，由人工验证。
