# UZS Scheduler API

## 1. 基本说明
- 基础地址：`http://{host}:{port}`
- 当前无鉴权、无签名、无分页。
- 默认返回 `application/json`。
- 时间字段统一为毫秒时间戳。
- 当前接口均为内部接口，面向调度器、worker、运维脚本和下游工具。
- 除 `GET /actuator/health` 外，本文档内容均基于当前项目代码实现整理。

## 2. 请求参数约定
- `GET` 接口使用 `path` 或 `query` 参数。
- `POST` 接口当前全部通过 Spring `@RequestParam` 接收参数，调用时建议使用 query string 或表单参数，不要按 JSON body 传参。
- 常用参数示例：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `path/query` | `string` | 是/按接口而定 | `user_profile` | LakeSoul 表唯一标识 |
| `partitionDesc` | `query` | `string` | 是/按接口而定 | `dt=2026-04-16/country=cn` | LakeSoul 分区描述 |
| `workerId` | `query` | `string` | 是 | `worker-01` | worker 实例标识 |
| `leaseMs` | `query` | `long` | 是 | `60000` | claim 租约时长，必须大于 `0` |
| `claimToken` | `query` | `string` | 是 | `c6a8b566-23f5-4ab2-a4fa-18b1ff3c10f9` | 领取任务后返回的令牌，提交结果时必须原样带回 |
| `errorMessage` | `query` | `string` | 失败接口必填 | `spark job failed` | worker 失败原因 |
| `clearedFileCount` | `query` | `integer` | 否 | `12` | clear 成功时已清理文件数，默认按 `0` 处理 |
| `clearedCommitCount` | `query` | `integer` | 否 | `3` | clear 成功时已清理 commit 数，默认按 `0` 处理 |
| `clearedVersionCount` | `query` | `integer` | 否 | `2` | clear 成功时已清理版本数，默认按 `0` 处理 |

## 3. 通用状态码与返回约定

| 状态码 | 含义 | 常见场景 | 返回体 |
| --- | --- | --- | --- |
| `200` | 请求成功 | 查询成功、刷新成功、claim 成功或当前无任务 | JSON |
| `400` | 参数不合法 | 缺少参数、空字符串、`leaseMs <= 0` | `facts` 返回 JSON；其余大多返回纯文本错误消息 |
| `404` | 未找到对象 | 表事实、分区事实、指定任务不存在 | 空响应体 |
| `409` | 状态冲突 | `facts` 正在运行、`claimToken` 不匹配、任务已释放 | JSON |
| `500` | 服务内部错误 | `facts refresh` 执行失败 | JSON |
| `503` | 依赖不可用 | 数据库不可用 | JSON |

### 3.1 常见返回差异
- `facts` 刷新接口在 `400/409/500` 时仍返回 `FactsRefreshResult` JSON。
- `compaction / transfer / clear` 的参数校验失败通常直接返回纯文本字符串，例如：

```json
"tableId must not be blank"
```

- `GET /internal/table/{tableId}`、`GET /internal/partition`、`GET /internal/tasks/*` 查询不到对象时返回 `404` 且响应体为空。

## 4. Health

### 4.1 `GET /internal/health/database`
- 用途：检查 scheduler 与数据库连通性。
- 请求参数：无。
- 成功响应示例（`200`）：

```json
{
  "available": true,
  "ping": 8,
  "checkedAt": 1776412800123,
  "databaseTimeMillis": 1776412800118,
  "errorMessage": null
}
```

- 失败响应示例（`503`）：

```json
{
  "available": false,
  "ping": 0,
  "checkedAt": 1776412800123,
  "databaseTimeMillis": 0,
  "errorMessage": "Connection refused"
}
```

### 4.2 `GET /actuator/health`
- 用途：应用健康检查。
- 请求参数：无。
- 说明：标准 Spring Boot Actuator 响应，字段可能随依赖配置变化。
- 响应示例（`200`）：

```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    }
  }
}
```

## 5. Facts

### 5.1 `POST /internal/facts/refresh`
- 用途：全量刷新 facts。
- 请求参数：无。
- 成功响应示例（`200`）：

```json
{
  "outcome": "SUCCESS",
  "trigger": "manual",
  "scope": "all",
  "tableId": null,
  "partitionDesc": null,
  "startedAt": 1776412800000,
  "finishedAt": 1776412801250,
  "durationMs": 1250,
  "checkpoints": [
    {
      "checkpointName": "fact_scan",
      "checkpointValue": 1776412801000
    }
  ],
  "errorMessage": null
}
```

- 冲突响应示例（`409`）：

```json
{
  "outcome": "REJECTED_ALREADY_RUNNING",
  "trigger": "manual",
  "scope": "all",
  "tableId": null,
  "partitionDesc": null,
  "startedAt": 1776412800000,
  "finishedAt": 1776412800000,
  "durationMs": 0,
  "checkpoints": [],
  "errorMessage": "facts refresh is already running"
}
```

### 5.2 `POST /internal/facts/refresh/table`
- 用途：按表刷新 facts。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |

- 请求示例：`POST /internal/facts/refresh/table?tableId=user_profile`
- 成功响应示例（`200`）：

```json
{
  "outcome": "SUCCESS",
  "trigger": "manual",
  "scope": "table",
  "tableId": "user_profile",
  "partitionDesc": null,
  "startedAt": 1776412800000,
  "finishedAt": 1776412800320,
  "durationMs": 320,
  "checkpoints": [
    {
      "checkpointName": "fact_scan",
      "checkpointValue": 1776412800300
    }
  ],
  "errorMessage": null
}
```

- 参数错误示例（`400`）：

```json
{
  "outcome": "FAILED",
  "trigger": "manual",
  "scope": "table",
  "tableId": "",
  "partitionDesc": null,
  "startedAt": 1776412800000,
  "finishedAt": 1776412800000,
  "durationMs": 0,
  "checkpoints": [],
  "errorMessage": "tableId must not be blank"
}
```

### 5.3 `POST /internal/facts/refresh/partition`
- 用途：按分区刷新 facts。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 请求示例：`POST /internal/facts/refresh/partition?tableId=user_profile&partitionDesc=dt=2026-04-16/country=cn`
- 成功响应示例（`200`）：

```json
{
  "outcome": "SUCCESS",
  "trigger": "manual",
  "scope": "partition",
  "tableId": "user_profile",
  "partitionDesc": "dt=2026-04-16/country=cn",
  "startedAt": 1776412800000,
  "finishedAt": 1776412800188,
  "durationMs": 188,
  "checkpoints": [
    {
      "checkpointName": "fact_scan",
      "checkpointValue": 1776412800150
    }
  ],
  "errorMessage": null
}
```

### 5.4 `GET /internal/facts/status`
- 用途：查询 facts 当前运行状态和最近一次结果。
- 请求参数：无。
- 运行中响应示例（`200`）：

```json
{
  "running": true,
  "runningTrigger": "manual",
  "runningScope": "partition",
  "runningTableId": "user_profile",
  "runningPartitionDesc": "dt=2026-04-16/country=cn",
  "runningStartedAt": 1776412800000,
  "lastResult": {
    "outcome": "SUCCESS",
    "trigger": "manual",
    "scope": "all",
    "tableId": null,
    "partitionDesc": null,
    "startedAt": 1776412700000,
    "finishedAt": 1776412701200,
    "durationMs": 1200,
    "checkpoints": [
      {
        "checkpointName": "fact_scan",
        "checkpointValue": 1776412701000
      }
    ],
    "errorMessage": null
  }
}
```

- 首次启动且尚未执行过时，`lastResult` 可能为 `null`，`runningStartedAt` 为 `0`。

## 6. Facts Query

### 6.1 `GET /internal/table/{tableId}`
- 用途：查询表事实与平台控制配置。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `path` | `string` | 是 | `user_profile` | 表标识 |

- 请求示例：`GET /internal/table/user_profile`
- 成功响应示例（`200`）：

```json
{
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "deleted": false,
  "partitionTable": true,
  "compactionEnabled": true,
  "compactionQuietPeriodMs": 300000,
  "compactionMaxRetry": 3,
  "compactionBackoffMs": 60000,
  "transferEnabled": true,
  "transferTargetTableName": "user_profile_dwd",
  "transferTargetTableNamespace": "dw",
  "transferSqlTemplate": "insert into dw.user_profile_dwd select * from lakehouse.user_profile where ${partition_filter}",
  "transferDelayMs": 60000,
  "transferMaxRetry": 3,
  "transferBackoffMs": 60000,
  "clearEnabled": true,
  "clearDelayMs": 600000,
  "clearMaxRetry": 3,
  "clearBackoffMs": 60000,
  "clearMode": "version_range_compaction_base",
  "createTime": 1776412000000,
  "updateTime": 1776412600000
}
```

- 未找到时返回 `404`，响应体为空。

### 6.2 `GET /internal/partition`
- 用途：查询分区事实。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 表标识 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 分区描述 |

- 请求示例：`GET /internal/partition?tableId=user_profile&partitionDesc=dt=2026-04-16/country=cn`
- 成功响应示例（`200`）：

```json
{
  "tableId": "user_profile",
  "partitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "lastCommitOp": "AppendCommit",
  "lastPartitionTimestamp": 1776412500000,
  "lastSnapshot": [
    "oss://bucket/user_profile/dt=2026-04-16/country=cn/part-0001.parquet",
    "oss://bucket/user_profile/dt=2026-04-16/country=cn/part-0002.parquet"
  ],
  "deleted": false,
  "discoveredAt": 1776412000000,
  "lastSeenAt": 1776412600000,
  "createTime": 1776412000000,
  "updateTime": 1776412600000
}
```

- 未找到时返回 `404`，响应体为空。

## 7. Compaction

### 7.1 `POST /internal/tasks/compaction/refresh`
- 用途：全量刷新 compaction 任务表。
- 请求参数：无。
- 成功响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "all",
  "tableId": null,
  "partitionDesc": null,
  "refreshedAt": 1776412800000
}
```

### 7.2 `POST /internal/tasks/compaction/refresh/table`
- 用途：按表刷新 compaction 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |

- 请求示例：`POST /internal/tasks/compaction/refresh/table?tableId=user_profile`
- 成功响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "table",
  "tableId": "user_profile",
  "partitionDesc": null,
  "refreshedAt": 1776412800123
}
```

### 7.3 `POST /internal/tasks/compaction/refresh/partition`
- 用途：按分区刷新 compaction 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "partition",
  "tableId": "user_profile",
  "partitionDesc": "dt=2026-04-16/country=cn",
  "refreshedAt": 1776412800188
}
```

### 7.4 `GET /internal/tasks/compaction`
- 用途：查询指定分区当前 compaction 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "runVersion": 18,
  "lastSuccessVersion": 17,
  "versionChangedAt": 1776412500000,
  "hasVersionChangeDuringRun": false,
  "deleted": false,
  "taskStatus": "ready",
  "discoveredAt": 1776412000000,
  "readyAt": 1776412600000,
  "claimedBy": null,
  "claimToken": null,
  "claimAt": null,
  "claimExpireAt": null,
  "retryCount": 0,
  "nextRetryAt": null,
  "lastError": null,
  "execCount": 1,
  "startExecAt": null,
  "finishAt": 1776412400000,
  "lastCompactionTime": 1776412400000,
  "createTime": 1776412000000,
  "updateTime": 1776412600000
}
```

### 7.5 `POST /internal/tasks/compaction/claim`
- 用途：领取一个 compaction 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `workerId` | `query` | `string` | 是 | `worker-01` | 不能为空 |
| `leaseMs` | `query` | `long` | 是 | `60000` | 必须大于 `0` |

- 请求示例：`POST /internal/tasks/compaction/claim?workerId=worker-01&leaseMs=60000`
- 成功响应示例（有任务，`200`）：

```json
{
  "claimed": true,
  "taskType": "compaction",
  "task": {
    "tableId": "user_profile",
    "tableName": "user_profile",
    "tableNamespace": "lakehouse",
    "isPartitionTable": true,
    "partitionDesc": "dt=2026-04-16/country=cn",
    "currentVersion": 18,
    "runVersion": 18,
    "claimedBy": "worker-01",
    "claimToken": "c6a8b566-23f5-4ab2-a4fa-18b1ff3c10f9",
    "claimAt": 1776412800000,
    "claimExpireAt": 1776412860000
  }
}
```

- 当前无可领取任务时仍返回 `200`：

```json
{
  "claimed": false,
  "taskType": "compaction",
  "task": null
}
```

### 7.6 `POST /internal/tasks/compaction/success`
- 用途：提交 compaction 成功。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |
| `claimToken` | `query` | `string` | 是 | `c6a8b566-23f5-4ab2-a4fa-18b1ff3c10f9` | 必须与 claim 返回一致 |

- 成功响应示例（`200`）：

```json
{
  "updated": true,
  "action": "success",
  "taskType": "compaction",
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "lastSuccessVersion": 18,
  "retryCount": 0,
  "nextRetryAt": null,
  "taskStatus": "success",
  "errorMessage": null
}
```

- claim 不匹配响应示例（`409`）：

```json
{
  "updated": false,
  "action": "success",
  "taskType": "compaction",
  "tableId": "user_profile",
  "tableName": null,
  "tableNamespace": null,
  "isPartitionTable": null,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": null,
  "lastSuccessVersion": null,
  "retryCount": null,
  "nextRetryAt": null,
  "taskStatus": null,
  "errorMessage": "claim token not found or task already released"
}
```

### 7.7 `POST /internal/tasks/compaction/failure`
- 用途：提交 compaction 失败。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |
| `claimToken` | `query` | `string` | 是 | `c6a8b566-23f5-4ab2-a4fa-18b1ff3c10f9` | 必须与 claim 返回一致 |
| `errorMessage` | `query` | `string` | 是 | `spark compact failed` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "updated": true,
  "action": "failure",
  "taskType": "compaction",
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "lastSuccessVersion": 17,
  "retryCount": 1,
  "nextRetryAt": 1776413100000,
  "taskStatus": "retry_wait",
  "errorMessage": "spark compact failed"
}
```

### 7.8 `POST /internal/tasks/compaction/recover-expired-claims`
- 用途：回收过期 compaction claim。
- 请求参数：无。
- 响应示例（`200`）：

```json
{
  "recoveredCount": 1,
  "tasks": [
    {
      "tableId": "user_profile",
      "partitionDesc": "dt=2026-04-16/country=cn",
      "taskStatus": "ready"
    }
  ]
}
```

## 8. Transfer

### 8.1 `POST /internal/tasks/transfer/refresh`
- 用途：全量刷新 transfer 任务表。
- 请求参数：无。
- 响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "all",
  "tableId": null,
  "partitionDesc": null,
  "refreshedAt": 1776412800000
}
```

### 8.2 `POST /internal/tasks/transfer/refresh/table`
- 用途：按表刷新 transfer 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |

- 响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "table",
  "tableId": "user_profile",
  "partitionDesc": null,
  "refreshedAt": 1776412800123
}
```

### 8.3 `POST /internal/tasks/transfer/refresh/partition`
- 用途：按分区刷新 transfer 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "partition",
  "tableId": "user_profile",
  "partitionDesc": "dt=2026-04-16/country=cn",
  "refreshedAt": 1776412800188
}
```

### 8.4 `GET /internal/tasks/transfer`
- 用途：查询指定分区当前 transfer 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "requiredCompactionVersion": 18,
  "lastSuccessVersion": 17,
  "deleted": false,
  "taskStatus": "ready",
  "targetTableName": "user_profile_dwd",
  "targetTableNamespace": "dw",
  "targetPartitionDesc": "dt=2026-04-16/country=cn",
  "transferSqlTemplate": "insert into dw.user_profile_dwd select * from lakehouse.user_profile where ${partition_filter}",
  "discoveredAt": 1776412000000,
  "readyAt": 1776412600000,
  "claimedBy": null,
  "claimToken": null,
  "claimAt": null,
  "claimExpireAt": null,
  "retryCount": 0,
  "nextRetryAt": null,
  "lastError": null,
  "execCount": 1,
  "startExecAt": null,
  "finishAt": 1776412400000,
  "lastTransferTime": 1776412400000,
  "createTime": 1776412000000,
  "updateTime": 1776412600000
}
```

### 8.5 `POST /internal/tasks/transfer/claim`
- 用途：领取一个 transfer 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `workerId` | `query` | `string` | 是 | `worker-02` | 不能为空 |
| `leaseMs` | `query` | `long` | 是 | `60000` | 必须大于 `0` |

- 成功响应示例（有任务，`200`）：

```json
{
  "claimed": true,
  "taskType": "transfer",
  "task": {
    "tableId": "user_profile",
    "tableName": "user_profile",
    "tableNamespace": "lakehouse",
    "isPartitionTable": true,
    "partitionDesc": "dt=2026-04-16/country=cn",
    "currentVersion": 18,
    "requiredCompactionVersion": 18,
    "targetTableName": "user_profile_dwd",
    "targetTableNamespace": "dw",
    "targetPartitionDesc": "dt=2026-04-16/country=cn",
    "transferSqlTemplate": "insert into dw.user_profile_dwd select * from lakehouse.user_profile where ${partition_filter}",
    "claimedBy": "worker-02",
    "claimToken": "b4248f7e-230b-4d6f-8e72-7ca9c93ff3d3",
    "claimAt": 1776412800000,
    "claimExpireAt": 1776412860000
  }
}
```

- 当前无可领取任务时：

```json
{
  "claimed": false,
  "taskType": "transfer",
  "task": null
}
```

### 8.6 `POST /internal/tasks/transfer/success`
- 用途：提交 transfer 成功。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |
| `claimToken` | `query` | `string` | 是 | `b4248f7e-230b-4d6f-8e72-7ca9c93ff3d3` | 必须与 claim 返回一致 |

- 成功响应示例（`200`）：

```json
{
  "updated": true,
  "action": "success",
  "taskType": "transfer",
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "targetTableName": "user_profile_dwd",
  "targetTableNamespace": "dw",
  "targetPartitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "lastSuccessVersion": 18,
  "retryCount": 0,
  "nextRetryAt": null,
  "taskStatus": "success",
  "errorMessage": null
}
```

### 8.7 `POST /internal/tasks/transfer/failure`
- 用途：提交 transfer 失败。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |
| `claimToken` | `query` | `string` | 是 | `b4248f7e-230b-4d6f-8e72-7ca9c93ff3d3` | 必须与 claim 返回一致 |
| `errorMessage` | `query` | `string` | 是 | `flink submit failed` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "updated": true,
  "action": "failure",
  "taskType": "transfer",
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "targetTableName": "user_profile_dwd",
  "targetTableNamespace": "dw",
  "targetPartitionDesc": "dt=2026-04-16/country=cn",
  "currentVersion": 18,
  "lastSuccessVersion": 17,
  "retryCount": 1,
  "nextRetryAt": 1776413100000,
  "taskStatus": "retry_wait",
  "errorMessage": "flink submit failed"
}
```

### 8.8 `POST /internal/tasks/transfer/recover-expired-claims`
- 用途：回收过期 transfer claim。
- 请求参数：无。
- 响应示例（`200`）：

```json
{
  "recoveredCount": 1,
  "tasks": [
    {
      "tableId": "user_profile",
      "partitionDesc": "dt=2026-04-16/country=cn",
      "taskStatus": "ready"
    }
  ]
}
```

## 9. Clear

### 9.1 `POST /internal/tasks/clear/refresh`
- 用途：全量刷新 clear 任务表。
- 请求参数：无。
- 响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "all",
  "tableId": null,
  "partitionDesc": null,
  "refreshedAt": 1776412800000
}
```

### 9.2 `POST /internal/tasks/clear/refresh/table`
- 用途：按表刷新 clear 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |

- 响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "table",
  "tableId": "user_profile",
  "partitionDesc": null,
  "refreshedAt": 1776412800123
}
```

### 9.3 `POST /internal/tasks/clear/refresh/partition`
- 用途：按分区刷新 clear 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 响应示例（`200`）：

```json
{
  "trigger": "manual",
  "scope": "partition",
  "tableId": "user_profile",
  "partitionDesc": "dt=2026-04-16/country=cn",
  "refreshedAt": 1776412800188
}
```

### 9.4 `GET /internal/tasks/clear`
- 用途：查询指定分区当前 clear 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "startVersion": 15,
  "endVersion": 17,
  "endCommitOp": "CompactionCommit",
  "endPartitionTimestamp": 1776412500000,
$$  "versionCount": 3,
  "clearDone": false,
  "requiredCompactionVersion": 18,
  "deleted": false,
  "taskStatus": "ready",
  "discoveredAt": 1776412000000,
  "readyAt": 1776412600000,
  "claimedBy": null,
  "claimToken": null,
  "claimAt": null,
  "claimExpireAt": null,
  "retryCount": 0,
  "nextRetryAt": null,
  "lastError": null,
  "execCount": 0,
  "startExecAt": null,
  "finishAt": null,
  "clearedFileCount": 0,
  "clearedCommitCount": 0,
  "clearedVersionCount": 0,
  "lastClearTime": null,
  "createTime": 1776412000000,
  "updateTime": 1776412600000
}
```

### 9.5 `POST /internal/tasks/clear/claim`
- 用途：领取一个 clear 任务。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `workerId` | `query` | `string` | 是 | `worker-03` | 不能为空 |
| `leaseMs` | `query` | `long` | 是 | `60000` | 必须大于 `0` |

- 成功响应示例（有任务，`200`）：

```json
{
  "claimed": true,
  "taskType": "clear",
  "task": {
    "tableId": "user_profile",
    "tableName": "user_profile",
    "tableNamespace": "lakehouse",
    "isPartitionTable": true,
    "partitionDesc": "dt=2026-04-16/country=cn",
    "startVersion": 15,
    "endVersion": 17,
    "endCommitOp": "CompactionCommit",
    "endPartitionTimestamp": 1776412500000,
    "versionCount": 3,
    "requiredCompactionVersion": 18,
    "claimedBy": "worker-03",
    "claimToken": "8e1827c1-0d73-47e8-85f7-2fcadcb2c41e",
    "claimAt": 1776412800000,
    "claimExpireAt": 1776412860000
  }
}
```

- 当前无可领取任务时：

```json
{
  "claimed": false,
  "taskType": "clear",
  "task": null
}
```

### 9.6 `POST /internal/tasks/clear/success`
- 用途：提交 clear 成功。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |
| `claimToken` | `query` | `string` | 是 | `8e1827c1-0d73-47e8-85f7-2fcadcb2c41e` | 必须与 claim 返回一致 |
| `clearedFileCount` | `query` | `integer` | 否 | `12` | 未传时按 `0` 处理 |
| `clearedCommitCount` | `query` | `integer` | 否 | `3` | 未传时按 `0` 处理 |
| `clearedVersionCount` | `query` | `integer` | 否 | `2` | 未传时按 `0` 处理 |

- 成功响应示例（`200`）：

```json
{
  "updated": true,
  "action": "success",
  "taskType": "clear",
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "startVersion": 15,
  "endVersion": 17,
  "retryCount": 0,
  "nextRetryAt": null,
  "clearDone": true,
  "taskStatus": "success",
  "errorMessage": null
}
```

### 9.7 `POST /internal/tasks/clear/failure`
- 用途：提交 clear 失败。
- 请求参数：

| 参数名 | 位置 | 类型 | 必填 | 示例值 | 说明 |
| --- | --- | --- | --- | --- | --- |
| `tableId` | `query` | `string` | 是 | `user_profile` | 不能为空 |
| `partitionDesc` | `query` | `string` | 是 | `dt=2026-04-16/country=cn` | 不能为空 |
| `claimToken` | `query` | `string` | 是 | `8e1827c1-0d73-47e8-85f7-2fcadcb2c41e` | 必须与 claim 返回一致 |
| `errorMessage` | `query` | `string` | 是 | `delete old files failed` | 不能为空 |

- 成功响应示例（`200`）：

```json
{
  "updated": true,
  "action": "failure",
  "taskType": "clear",
  "tableId": "user_profile",
  "tableName": "user_profile",
  "tableNamespace": "lakehouse",
  "isPartitionTable": true,
  "partitionDesc": "dt=2026-04-16/country=cn",
  "startVersion": 15,
  "endVersion": 17,
  "retryCount": 1,
  "nextRetryAt": 1776413100000,
  "clearDone": false,
  "taskStatus": "retry_wait",
  "errorMessage": "delete old files failed"
}
```

### 9.8 `POST /internal/tasks/clear/recover-expired-claims`
- 用途：回收过期 clear claim。
- 请求参数：无。
- 响应示例（`200`）：

```json
{
  "recoveredCount": 1,
  "tasks": [
    {
      "tableId": "user_profile",
      "partitionDesc": "dt=2026-04-16/country=cn",
      "taskStatus": "ready"
    }
  ]
}
```

## 10. 字段补充说明

### 10.1 `FactsRefreshResult`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `outcome` | `string` | `SUCCESS / FAILED / REJECTED_ALREADY_RUNNING` |
| `trigger` | `string` | 触发来源，当前常见值：`manual`、`scheduled` |
| `scope` | `string` | 刷新范围：`all / table / partition` |
| `tableId` | `string?` | 表级、分区级刷新时有值 |
| `partitionDesc` | `string?` | 分区级刷新时有值 |
| `startedAt` | `long` | 开始时间 |
| `finishedAt` | `long` | 结束时间 |
| `durationMs` | `long` | 耗时毫秒 |
| `checkpoints` | `array` | checkpoint 列表，失败时通常为空 |
| `errorMessage` | `string?` | 失败或冲突时错误说明 |

### 10.2 `TableFactStatus`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deleted` | `boolean` | 是否已被标记删除 |
| `partitionTable` | `boolean` | 是否为分区表 |
| `compactionEnabled` | `boolean` | 是否开启 compaction |
| `transferEnabled` | `boolean` | 是否开启 transfer |
| `clearEnabled` | `boolean` | 是否开启 clear |
| `clearMode` | `string` | 当前 DDL 默认值为 `version_range_compaction_base` |

### 10.3 `PartitionFactStatus`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `currentVersion` | `integer?` | 当前最新版本，未扫描到时可能为空 |
| `lastCommitOp` | `string?` | 最新提交操作类型 |
| `lastPartitionTimestamp` | `long?` | 最新分区时间 |
| `lastSnapshot` | `string[]` | 最新快照文件列表 |

### 10.4 任务状态字段
- `tableName / tableNamespace / isPartitionTable`：任务源表上下文；worker 仅调用 `claim` 即可拿到执行所需表级信息。
- `targetPartitionDesc`：仅 `transfer` 任务返回，当前与源 `partitionDesc` 保持一致，表示目标表对应分区。
- `startVersion / endVersion`：`clear` 任务只返回待清理版本区间；worker 需据此自行查询 LakeSoul 元数据获取 snapshot、commit 和文件列表。
- `taskStatus` 当前常见值：
  - `ready`：可被 worker 领取
  - `running`：已被 worker 领取且尚未提交结果
  - `retry_wait`：上次执行失败，等待重试时间到达
  - `success`：最近一次执行成功
  - `failed`：已失败且不再自动重试
  - `skipped`：当前条件不满足，任务被跳过
- `trigger` 当前常见值：
  - facts：`manual`、`scheduled`
  - compaction：`manual`、`facts_chain`
  - transfer：`manual`、`compaction_chain`
  - clear：`manual`、`transfer_chain`

## 11. 调用建议
- 手工或系统入口统一从 `facts refresh` 开始，自动链路为：`facts -> compaction -> transfer -> clear`。
- worker 提交成功/失败时必须带原始 `claimToken`。
- 通常只调用一次 `claim` 即可拿到完整执行信息；`GET /internal/tasks/*` 更适合排障与状态确认。
- `clear` 只有在仍存在历史版本时才会进入 `ready/claim`。
