# NewCleanJob 当前实现梳理

本文按当前 `lakesoul-flink` 代码事实整理 `NewCleanJob` 的两条清理链路，补充涉及的表、SQL 与执行逻辑；对外部组件和表级 TTL 不做推断。

## 1. 作业入口与整体流程

入口类：`src/main/java/org/apache/flink/lakesoul/entry/clean/NewCleanJob.java`

`NewCleanJob` 同时跑两条清理链路：

1. `TickSource -> TickTriggeringCleaner.flatMap1(...) -> CleanUtils.cleanDiscardFile(...)`
   - 定时扫描并清理 `discard_compressed_file_info` 中过期的废弃文件记录。
2. `public.partition_info` CDC -> `PartitionInfoRecordGets.metaMapper(...)` -> `ProcessClean`
   - 跟踪分区版本变化，按当前状态和时间窗口清理历史 snapshot 对应的文件与元数据。

两条链路共享同一个作业级 `dataExpiredTime` 参数。当前 Flink 代码中，`targetTableName` 只影响 `partition_info` CDC 过滤，不影响 `discard_compressed_file_info` 清理范围。

## 2. 关键参数与当前实际含义

- `ontimer_interval`
  - 处理时间定时器周期，单位分钟。
  - 代码中会乘以 `60000` 转成毫秒。
- `dataExpiredTime`
  - 作业级全局过期时间。
  - 当值 `< 10` 时按天解释并乘以 `86400000`；否则直接按毫秒使用。
- `discardBatchSize`
  - `discard_compressed_file_info` 清理分页大小，默认 `1000`。
- `targetTableName`
  - 先查 `table_name_id` 得到 `table_id` 列表，再过滤 `partition_info` CDC 事件。
- `source.parallelism`
  - 仅作用于 `partition_info` CDC source 并行度。

当前 CDC 固定监听表：`public.partition_info`。

## 3. 链路一：discard 文件清理

### 3.1 触发方式

- `TickSource` 按 `ontimer_interval` 生成 tick。
- `TickTriggeringCleaner.flatMap1(...)` 每次收到 tick 都新建一次 JDBC 连接，调用 `CleanUtils.cleanDiscardFile(...)`。

### 3.2 涉及表

- `discard_compressed_file_info`

### 3.3 处理流程

1. 计算过期阈值：`System.currentTimeMillis() - dataExpiredTime`。
2. 从 `discard_compressed_file_info` 按 `(timestamp, file_path)` 做 keyset 分页读取过期记录。
3. 对每个 `file_path` 调用 Flink `FileSystem` 删除文件。
4. 文件删除成功，或文件已不存在时，删除对应 `discard_compressed_file_info` 记录。
5. 文件删除失败时，保留记录，等待下次 tick 重试。

### 3.4 SQL 与用途

1. 首次分页读取过期记录

```sql
SELECT file_path, timestamp
FROM discard_compressed_file_info
WHERE timestamp < ?
ORDER BY timestamp, file_path
LIMIT ?
```

用途：读取第一页待清理的废弃文件记录。

2. 后续分页续读

```sql
SELECT file_path, timestamp
FROM discard_compressed_file_info
WHERE timestamp < ?
  AND (timestamp > ? OR (timestamp = ? AND file_path > ?))
ORDER BY timestamp, file_path
LIMIT ?
```

用途：基于上一页最后一条 `(timestamp, file_path)` 继续拉取下一页，避免全量扫描常驻内存。

3. 删除已成功清理的 discard 记录

```sql
DELETE FROM discard_compressed_file_info
WHERE file_path = ?
```

用途：仅在文件删除成功，或确认对象已不存在时，删除对应元数据记录。

### 3.5 文件删除语义

- 入口：`deleteFile(...) -> deleteFiles(...) -> deleteByFlinkFS(...)`
- 使用 Flink `FileSystem`，复用 Flink 集群的文件系统插件和配置。
- 对 `s3` / `s3a` / `s3n` 路径，会先做 bucket 连通性检查，并按 bucket 缓存检查结果。
- 若对象存储不可达，则本次删除失败，元数据不删。
- 若文件已不存在，当前实现按“已清理”处理，继续删除元数据。

### 3.6 失败与重试

- 单条文件删除失败，不影响同批其他记录继续处理。
- 失败记录留在 `discard_compressed_file_info`，下次 tick 再尝试。
- 这条链路不会触碰 `partition_info` 或 `data_commit_info`。

## 4. 链路二：snapshot 历史版本清理

### 4.1 触发方式

1. CDC 监听 `public.partition_info`。
2. `PartitionInfoRecordGets.metaMapper(...)` 解析 Debezium 事件。
3. 仅处理非 delete 事件，并提取：
   - `table_id`
   - `partition_desc`
   - `commit_op`
   - `version`
   - `timestamp`
   - `snapshot`
4. 经过 `keyBy(table_id + "/" + partition_desc)` 后进入 `ProcessClean`。

### 4.2 涉及表

- `partition_info`
- `data_commit_info`
- `table_name_id`

### 4.3 状态与作用

- `compactNewState`
  - 记录当前分区已知的最新压缩基线时间，key 为 `table_id/partition_desc`。
  - 只有被识别为“单快照 compaction / update”的版本才会更新它。
- `willState`
  - 暂存尚未到达清理条件，或清理失败待重试的历史版本，key 为 `table_id/partition_desc/version`。
  - `AppendCommit`、`MergeCommit`、旧 compaction 版本、并发 compaction 版本都可能进入这里。
- `compactionVersionState`
  - 记录当前 key 下后续清理时是否按旧版 compaction 目录逻辑删除。
  - `UpdateCommit` 默认视为旧版 compaction；`CompactionCommit` 会进一步读取 `snapshot -> data_commit_info.file_ops` 判断路径中是否包含 `compact_`。
- `timerInitializedState`
  - 保证每个 key 只注册一次处理时间定时器。

### 4.4 处理逻辑

`ProcessClean` 实际按 `commit_op` 与 `snapshot.size()` 把历史版本分成几类处理，过期判断统一使用 `partition_info.timestamp`。

这里的 `timestamp` 不是旧文件时间，也不是 `data_commit_info.timestamp`，而是 `partition_info` 版本行写入 PG 的时间；因此 snapshot 清理本质上是“按历史分区版本入库时间”判断是否过期。

1. `AppendCommit` / `MergeCommit`
   - 这两类版本本身不会成为新的压缩基线。
   - 如果当前分区还没有 `compactNewState`，说明还没有观察到可作为基线的 compaction 版本，此时不会立即清理，而是先写入 `willState`。
   - 如果当前分区已有压缩基线，且 `partition_info.timestamp < compactTime - dataExpiredTime`，则立即调用 `cleanSnapshotAndPartitionInfo(...)` 尝试清理。
   - 如果未过期，则继续保留在 `willState`，等待后续定时器或新的 compaction 基线触发。

2. `CompactionCommit` / `UpdateCommit` 且 `snapshot.size() == 1`
   - 这类版本会被视为“单快照 compaction 版本”，是新的压缩基线候选。
   - 进入分支后，先判断是否需要按旧版 compaction 删除：
     - `UpdateCommit` 直接视为旧版 compaction。
     - `CompactionCommit` 通过读取该版本的 `snapshot`，再查询 `data_commit_info.file_ops`，若路径包含 `compact_` 则视为旧版 compaction。
   - 如果该版本时间比当前 `compactNewState` 更新，则刷新 `compactNewState`，把自己作为新的“最新压缩基线”，同时当前版本仍写入 `willState`。
   - 如果该版本不是最新基线，但已经满足 `timestamp < compactTime - dataExpiredTime`，则把它视为旧的 compaction 历史版本，直接尝试清理。
   - 如果还未过期，则也先保留在 `willState`。

3. `CompactionCommit` / `UpdateCommit` 且 `snapshot.size() > 1`
   - 当前实现把这类版本视为“并发 compaction”。
   - 这类版本不会更新 `compactNewState`，也就是不会成为新的压缩基线。
   - 但它仍可能作为历史版本被清理：如果当前分区已经存在压缩基线，且它满足 `timestamp < compactTime - dataExpiredTime`，则会直接调用清理。
   - 如果没有基线，或尚未过期，则继续进入 `willState` 等待。

4. 未匹配的其他 `commit_op`
   - 当前 `ProcessClean` 仅对 `AppendCommit`、`MergeCommit`、`CompactionCommit`、`UpdateCommit` 做了显式处理。
   - 其他类型不会进入 snapshot 历史版本清理主分支。
   - 另外，CDC delete 事件已在 `PartitionInfoRecordGets.metaMapper(...)` 中被过滤，因此这里处理的都是非 delete 版本行。

5. 定时器重试
   - `onTimer(...)` 会周期扫描 `willState`。
   - 只有当该历史版本所属分区与当前 `compactNewState` key 匹配，且 `willState.timestamp < compactNewState.commitTime - dataExpiredTime` 时，才会再次尝试清理。
   - 清理成功后，才从 `willState` 中移除；失败则保留，等待下次定时器继续重试。
   - 如果发现该分区在 `partition_info` 中已经不存在，则清空当前 key 下 `compactNewState` 和 `willState`。

6. 如何理解“基线”
   - 这条链路不是简单地“版本过期就删”，而是以最新 compaction 版本作为锚点，判断更早的历史版本是否已经落在过期窗口之外。
   - 因此单快照 `CompactionCommit` / `UpdateCommit` 是最关键的类型，因为它决定了后续历史版本何时可以被删。

### 4.5 SQL 与用途

1. 根据目标表名查询 `table_id`

```sql
SELECT table_id
FROM table_name_id
WHERE table_name = ?
  AND table_namespace = ?
```

用途：把启动参数 `targetTableName` 转成 `table_id` 列表，用于过滤 `partition_info` CDC 事件。

2. 检查分区是否仍存在

```sql
SELECT 1
FROM partition_info
WHERE table_id = ?
  AND partition_desc = ?
LIMIT 1
```

用途：定时器重试前判断该分区是否仍存在；若不存在，则清空该分区状态。

3. 读取某个版本的 snapshot

```sql
SELECT snapshot
FROM partition_info
WHERE table_id = ?
  AND partition_desc = ?
  AND version = ?
```

用途：`getCompactVersion(...)` 中先取出该版本绑定的 snapshot commit 列表。

4. 读取 snapshot 对应的 `file_ops`

```sql
SELECT unnest(file_ops) AS op
FROM data_commit_info
WHERE commit_id = ANY(?)
```

用途：`getCompactVersion(...)` 通过首个 `file_ops` 判断路径里是否包含 `compact_`，从而识别旧版 compaction；同时 snapshot 清理时也会依赖这些 `file_ops` 找到待删文件。

5. 按 commit 查询待删文件路径

```sql
SELECT file_op.path
FROM data_commit_info dci,
     unnest(dci.file_ops) AS file_op
WHERE dci.table_id = ?
  AND dci.partition_desc = ?
  AND dci.commit_id = ?
```

用途：`collectDeleteTargets(...)` 收集 snapshot 内每个 commit 对应的文件路径，旧版 compaction 时会把文件路径折算到 `compact_` 目录级别。

6. 删除 `data_commit_info`

```sql
DELETE FROM data_commit_info
WHERE table_id = ?
  AND commit_id = ?
  AND partition_desc = ?
```

用途：文件删除全部成功后，在事务中删除该 snapshot 对应的所有 `data_commit_info` 元数据。

7. 删除 `partition_info`

```sql
DELETE FROM partition_info
WHERE table_id = ?
  AND partition_desc = ?
  AND version = ?
```

用途：在同一个事务中删除当前历史版本对应的 `partition_info` 元数据。

### 4.6 清理顺序与一致性

`cleanSnapshotAndPartitionInfo(...)` 的执行顺序是：

1. 遍历 `snapshot`，按 commit 收集所有待删文件路径。
2. 对收集出的文件路径做去重。
3. 若当前版本被识别为旧版 compaction，则把路径折算到 `compact_` 目录级别删除；否则按 `file_ops.path` 删除具体文件。
4. 先删文件，文件全部成功后才进入元数据删除事务。
5. 事务内先删 `data_commit_info`，再删 `partition_info`。
6. 任一步失败则回滚事务，并向上返回失败结果。

因此当前语义是：文件删除成功是删元数据的前置条件；元数据删除失败不会部分提交；同一个历史版本不区分 `Append/Merge/Compaction/Update` 走不同删除事务，差异主要体现在“何时进入清理”和“删目录还是删文件”。

### 4.7 失败与重试

- 查询 `file_ops` 失败：返回失败，本次不删元数据。
- 文件删除失败：返回失败，本次不删元数据。
- 开启事务失败：返回失败。
- 删除 `data_commit_info` 或 `partition_info` 失败：回滚事务。
- 失败后 `ProcessClean` 会把对应版本保留在 `willState` 中，等待后续定时器重试。

## 5. 两条链路的关系与当前边界

- 两条链路共享同一个全局 `dataExpiredTime` 参数，但处理对象不同。
- discard 链路只处理 `discard_compressed_file_info` 与对象存储文件。
- snapshot 链路处理 `partition_info`、`data_commit_info` 与 snapshot 对应文件。
- snapshot 链路判断过期所使用的时间是 `partition_info.timestamp`，也就是分区版本行入库时间。
- `targetTableName` 只影响 snapshot 链路的 CDC 过滤，不影响 discard 链路。
- `discard_compressed_file_info` 更偏向辅助文件回收，不是 snapshot 历史版本清理的主真相来源。
- 当前代码中，未看到按表级 TTL 动态读取和应用的实现；文档仅记录现有作业级过期时间逻辑。
