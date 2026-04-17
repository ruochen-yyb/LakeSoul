# UZS Scheduler API

## 1. 基本说明
- 基础地址：`http://{host}:{port}`
- 当前无鉴权、无签名、无分页
- 默认返回 `application/json`
- 时间字段统一为毫秒时间戳
- 当前实现接口均为内部接口，面向调度器、worker、运维脚本和下游工具

## 2. 通用状态码
- `200`：请求成功
- `400`：参数不合法
- `404`：查询接口未找到目标对象
- `409`：冲突，如 facts 已在运行、claimToken 不匹配或任务已释放
- `500`：服务内部错误
- `503`：数据库不可用，仅用于 `GET /internal/health/database`

## 3. Health

### `GET /internal/health/database`
- 用途：检查数据库连通性
- 响应字段：
  - `available`：是否可用
  - `ping`：探测耗时，毫秒
  - `checkedAt`：检查时间
  - `databaseTimeMillis`：数据库当前时间毫秒值
  - `errorMessage`：失败时错误信息

### `GET /actuator/health`
- 用途：应用健康检查
- 说明：Spring Boot Actuator 标准响应，包含自定义数据库健康项

## 4. Facts

### `POST /internal/facts/refresh`
- 用途：全量执行 facts 刷新
- 参数：无
- 返回：`FactsRefreshResult`

### `POST /internal/facts/refresh/table`
- 用途：按表刷新 facts
- 参数：
  - `tableId`：必填
- 返回：`FactsRefreshResult`

### `POST /internal/facts/refresh/partition`
- 用途：按分区刷新 facts
- 参数：
  - `tableId`：必填
  - `partitionDesc`：必填
- 返回：`FactsRefreshResult`

### `GET /internal/facts/status`
- 用途：查询 facts 运行状态
- 返回：`FactsRefreshStatus`

### `FactsRefreshResult`
- `outcome`：`SUCCESS | REJECTED_ALREADY_RUNNING | FAILED`
- `trigger`：触发来源，如 `manual`、`scheduled`
- `scope`：`all | table | partition`
- `tableId`
- `partitionDesc`
- `startedAt`
- `finishedAt`
- `durationMs`
- `checkpoints`：数组，元素字段：
  - `checkpointName`
  - `checkpointValue`
- `errorMessage`

### `FactsRefreshStatus`
- `running`
- `runningTrigger`
- `runningScope`
- `runningTableId`
- `runningPartitionDesc`
- `runningStartedAt`
- `lastResult`：`FactsRefreshResult`

## 5. Facts Query

### `GET /internal/table/{tableId}`
- 用途：查询表事实与平台控制配置
- 路径参数：
  - `tableId`
- 返回：`TableFactStatus`

### `GET /internal/partition`
- 用途：查询分区事实
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`PartitionFactStatus`

### `TableFactStatus`
- `tableId`
- `tableName`
- `tableNamespace`
- `deleted`
- `partitionTable`
- `compactionEnabled`
- `compactionQuietPeriodMs`
- `compactionMaxRetry`
- `compactionBackoffMs`
- `transferEnabled`
- `transferTargetTableName`
- `transferTargetTableNamespace`
- `transferSqlTemplate`
- `transferDelayMs`
- `transferMaxRetry`
- `transferBackoffMs`
- `clearEnabled`
- `clearDelayMs`
- `clearMaxRetry`
- `clearBackoffMs`
- `clearMode`
- `createTime`
- `updateTime`

### `PartitionFactStatus`
- `tableId`
- `partitionDesc`
- `currentVersion`
- `lastCommitOp`
- `lastPartitionTimestamp`
- `lastSnapshot`：字符串数组
- `deleted`
- `discoveredAt`
- `lastSeenAt`
- `createTime`
- `updateTime`

## 6. Compaction

### `POST /internal/tasks/compaction/refresh`
- 用途：全量刷新 compaction 当前任务表
- 参数：无
- 返回：`CompactionRefreshResult`

### `POST /internal/tasks/compaction/refresh/table`
- 参数：
  - `tableId`
- 返回：`CompactionRefreshResult`

### `POST /internal/tasks/compaction/refresh/partition`
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`CompactionRefreshResult`

### `GET /internal/tasks/compaction`
- 用途：查询指定分区当前 compaction 任务
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`CompactionTaskStatus`

### `POST /internal/tasks/compaction/claim`
- 用途：领取一个 compaction 任务
- 参数：
  - `workerId`
  - `leaseMs`
- 返回：`CompactionClaimResult`

### `POST /internal/tasks/compaction/success`
- 用途：提交 compaction 成功
- 参数：
  - `tableId`
  - `partitionDesc`
  - `claimToken`
- 返回：`CompactionSubmitResult`

### `POST /internal/tasks/compaction/failure`
- 用途：提交 compaction 失败
- 参数：
  - `tableId`
  - `partitionDesc`
  - `claimToken`
  - `errorMessage`
- 返回：`CompactionSubmitResult`

### `POST /internal/tasks/compaction/recover-expired-claims`
- 用途：回收过期 claim
- 参数：无
- 返回：`CompactionRecoverResult`

### `CompactionRefreshResult`
- `trigger`
- `scope`
- `tableId`
- `partitionDesc`
- `refreshedAt`

### `CompactionTaskStatus`
- `tableId`
- `partitionDesc`
- `currentVersion`
- `runVersion`
- `lastSuccessVersion`
- `versionChangedAt`
- `hasVersionChangeDuringRun`
- `deleted`
- `taskStatus`
- `discoveredAt`
- `readyAt`
- `claimedBy`
- `claimToken`
- `claimAt`
- `claimExpireAt`
- `retryCount`
- `nextRetryAt`
- `lastError`
- `execCount`
- `startExecAt`
- `finishAt`
- `lastCompactionTime`
- `createTime`
- `updateTime`

### `CompactionClaimResult`
- `claimed`
- `taskType`：固定为 `compaction`
- `task`：为空表示当前无任务；有值时字段：
  - `tableId`
  - `partitionDesc`
  - `currentVersion`
  - `runVersion`
  - `claimedBy`
  - `claimToken`
  - `claimAt`
  - `claimExpireAt`

### `CompactionSubmitResult`
- `updated`
- `action`：`success | failure`
- `tableId`
- `partitionDesc`
- `currentVersion`
- `lastSuccessVersion`
- `retryCount`
- `nextRetryAt`
- `taskStatus`
- `errorMessage`

### `CompactionRecoverResult`
- `recoveredCount`
- `tasks`：数组，元素字段：
  - `tableId`
  - `partitionDesc`
  - `taskStatus`

## 7. Transfer

### `POST /internal/tasks/transfer/refresh`
- 用途：全量刷新 transfer 当前任务表
- 参数：无
- 返回：`TransferRefreshResult`

### `POST /internal/tasks/transfer/refresh/table`
- 参数：
  - `tableId`
- 返回：`TransferRefreshResult`

### `POST /internal/tasks/transfer/refresh/partition`
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`TransferRefreshResult`

### `GET /internal/tasks/transfer`
- 用途：查询指定分区当前 transfer 任务
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`TransferTaskStatus`

### `POST /internal/tasks/transfer/claim`
- 参数：
  - `workerId`
  - `leaseMs`
- 返回：`TransferClaimResult`

### `POST /internal/tasks/transfer/success`
- 参数：
  - `tableId`
  - `partitionDesc`
  - `claimToken`
- 返回：`TransferSubmitResult`

### `POST /internal/tasks/transfer/failure`
- 参数：
  - `tableId`
  - `partitionDesc`
  - `claimToken`
  - `errorMessage`
- 返回：`TransferSubmitResult`

### `POST /internal/tasks/transfer/recover-expired-claims`
- 参数：无
- 返回：`TransferRecoverResult`

### `TransferRefreshResult`
- `trigger`
- `scope`
- `tableId`
- `partitionDesc`
- `refreshedAt`

### `TransferTaskStatus`
- `tableId`
- `partitionDesc`
- `currentVersion`
- `requiredCompactionVersion`
- `lastSuccessVersion`
- `deleted`
- `taskStatus`
- `targetTableName`
- `targetTableNamespace`
- `transferSqlTemplate`
- `discoveredAt`
- `readyAt`
- `claimedBy`
- `claimToken`
- `claimAt`
- `claimExpireAt`
- `retryCount`
- `nextRetryAt`
- `lastError`
- `execCount`
- `startExecAt`
- `finishAt`
- `lastTransferTime`
- `createTime`
- `updateTime`

### `TransferClaimResult`
- `claimed`
- `taskType`：固定为 `transfer`
- `task`：为空表示当前无任务；有值时字段：
  - `tableId`
  - `partitionDesc`
  - `currentVersion`
  - `requiredCompactionVersion`
  - `targetTableName`
  - `targetTableNamespace`
  - `transferSqlTemplate`
  - `claimedBy`
  - `claimToken`
  - `claimAt`
  - `claimExpireAt`

### `TransferSubmitResult`
- `updated`
- `action`
- `tableId`
- `partitionDesc`
- `currentVersion`
- `lastSuccessVersion`
- `retryCount`
- `nextRetryAt`
- `taskStatus`
- `errorMessage`

### `TransferRecoverResult`
- `recoveredCount`
- `tasks`：数组，元素字段：
  - `tableId`
  - `partitionDesc`
  - `taskStatus`

## 8. Clear

### `POST /internal/tasks/clear/refresh`
- 用途：全量刷新 clear 当前任务表
- 参数：无
- 返回：`ClearRefreshResult`

### `POST /internal/tasks/clear/refresh/table`
- 参数：
  - `tableId`
- 返回：`ClearRefreshResult`

### `POST /internal/tasks/clear/refresh/partition`
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`ClearRefreshResult`

### `GET /internal/tasks/clear`
- 用途：查询指定分区当前 clear 任务
- 参数：
  - `tableId`
  - `partitionDesc`
- 返回：`ClearTaskStatus`

### `POST /internal/tasks/clear/claim`
- 参数：
  - `workerId`
  - `leaseMs`
- 返回：`ClearClaimResult`

### `POST /internal/tasks/clear/success`
- 参数：
  - `tableId`
  - `partitionDesc`
  - `claimToken`
  - `clearedFileCount`：可选，默认 `0`
  - `clearedCommitCount`：可选，默认 `0`
  - `clearedVersionCount`：可选，默认 `0`
- 返回：`ClearSubmitResult`

### `POST /internal/tasks/clear/failure`
- 参数：
  - `tableId`
  - `partitionDesc`
  - `claimToken`
  - `errorMessage`
- 返回：`ClearSubmitResult`

### `POST /internal/tasks/clear/recover-expired-claims`
- 参数：无
- 返回：`ClearRecoverResult`

### `ClearRefreshResult`
- `trigger`
- `scope`
- `tableId`
- `partitionDesc`
- `refreshedAt`

### `ClearTaskStatus`
- `tableId`
- `partitionDesc`
- `startVersion`
- `endVersion`
- `endCommitOp`
- `endPartitionTimestamp`
- `endSnapshot`：字符串数组
- `versionCount`
- `clearDone`
- `requiredCompactionVersion`
- `deleted`
- `taskStatus`
- `discoveredAt`
- `readyAt`
- `claimedBy`
- `claimToken`
- `claimAt`
- `claimExpireAt`
- `retryCount`
- `nextRetryAt`
- `lastError`
- `execCount`
- `startExecAt`
- `finishAt`
- `clearedFileCount`
- `clearedCommitCount`
- `clearedVersionCount`
- `lastClearTime`
- `createTime`
- `updateTime`

### `ClearClaimResult`
- `claimed`
- `taskType`：固定为 `clear`
- `task`：为空表示当前无任务；有值时字段：
  - `tableId`
  - `partitionDesc`
  - `startVersion`
  - `endVersion`
  - `endCommitOp`
  - `endPartitionTimestamp`
  - `endSnapshot`：字符串数组
  - `versionCount`
  - `requiredCompactionVersion`
  - `claimedBy`
  - `claimToken`
  - `claimAt`
  - `claimExpireAt`

### `ClearSubmitResult`
- `updated`
- `action`
- `tableId`
- `partitionDesc`
- `startVersion`
- `endVersion`
- `retryCount`
- `nextRetryAt`
- `clearDone`
- `taskStatus`
- `errorMessage`

### `ClearRecoverResult`
- `recoveredCount`
- `tasks`：数组，元素字段：
  - `tableId`
  - `partitionDesc`
  - `taskStatus`

## 9. 调用建议
- 手工或系统入口统一从 `facts refresh` 开始，自动链路为：`facts -> compaction -> transfer -> clear`
- worker 提交成功/失败时必须带原始 `claimToken`
- 对 `GET /internal/tasks/*` 建议在 worker 执行前做一次读取，用于排障与状态确认
- `clear` 只有在仍存在历史版本时才会进入 `ready/claim`
