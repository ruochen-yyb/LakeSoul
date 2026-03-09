# uzs-lakesoul-archive

## 当前职责
- 仅维护 `table_archive_info`（tai）和 `partition_archive_status`（pas）
- 不提交 Spark/Flink 作业；由外部 worker 拉取任务并回写结果
- 启动后立即执行一次同步；之后按定时器或手动 `reload` 同步

## 配置项
- `spring.datasource.*`：同库连接（LakeSoul 元数据 + 本服务业务表）
- `archive.scheduler.auto-interval-ms`：定时同步间隔（默认 `300000`）
- `archive.lock.sync-key`：同步阶段 `pg_try_advisory_lock` 锁键
- `archive.task.lease-ms`：任务租约时长，默认 `1800000`（30 分钟）

## 接口

### 1) 触发同步
```bash
curl -X POST "http://127.0.0.1:8080/reload"
```

### 2) 领取 Spark 快照任务
```bash
curl "http://127.0.0.1:8080/getCompactionTask?claimedBy=spark-worker-1"
```

返回字段：`tableId/tableName/tableNamespace/isPartitionTable/partitionDesc/version`

### 3) 领取 Flink 转储任务
```bash
curl "http://127.0.0.1:8080/getTransferTask?claimedBy=flink-worker-1"
```

返回字段：`tableId/tableName/tableNamespace/isPartitionTable/partitionDesc/version/archiveTargetTableName/archiveTargetTableNamespace/archiveSqlTemplate`

### 4) 任务完成回写
```bash
curl -X POST "http://127.0.0.1:8080/setTaskDone" \
  -H "Content-Type: application/json" \
  -d '{
    "claimType":"SPARK",
    "tableId":"table_1",
    "partitionDesc":"pt=2026-03-09",
    "version":12
  }'
```

### 5) 任务失败快速释放
```bash
curl -X POST "http://127.0.0.1:8080/setTaskErr" \
  -H "Content-Type: application/json" \
  -d '{
    "claimType":"FLINK",
    "tableId":"table_1",
    "partitionDesc":"pt=2026-03-09",
    "version":12
  }'
```

## 统一响应结构
- 统一 JSON：`{"code":<int>,"message":"<text>","data":<object|null>}`
- 成功：`code=0`
- 失败：`code` 为 HTTP 对应业务码（当前主要 `404/409/400`）

## HTTP 状态与业务码映射
- `200`：成功或幂等成功（`UPDATED`、`IDEMPOTENT`）
- `404`：任务不存在（`NOT_FOUND`）
- `409`：状态冲突（`VERSION_MISMATCH`、`CLAIM_MISMATCH`、`CLAIM_EXPIRED`、`INVALID_STATE`、`CONFLICT`）
- `400`：请求体非法或未覆盖的业务分支

## setTaskDone/setTaskErr 业务错误码
- `UPDATED`：本次更新已生效
- `IDEMPOTENT`：重复回调，已完成状态保持不变
- `NOT_FOUND`：`tableId+partitionDesc` 不存在
- `VERSION_MISMATCH`：回写版本不等于 `pas.version`
- `CLAIM_MISMATCH`：任务未被该 `claimType` 领取
- `CLAIM_EXPIRED`：租约过期（默认 30 分钟）
- `INVALID_STATE`：非法状态（如分区已删除，或 Flink 回写前 Spark 未完成）
- `CONFLICT`：并发竞争导致状态已变化

## 典型响应示例

### 领取到任务
```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "tableId": "table_1",
    "tableName": "dwd_order",
    "tableNamespace": "lake_ns",
    "isPartitionTable": true,
    "partitionDesc": "pt=2026-03-09",
    "version": 12,
    "archiveTargetTableName": null,
    "archiveTargetTableNamespace": null,
    "archiveSqlTemplate": null
  }
}
```

### 无任务可领
```json
{
  "code": 0,
  "message": "no task",
  "data": null
}
```

### 回写成功
```json
{
  "code": 0,
  "message": "快照完成",
  "data": null
}
```

### 回写冲突（示例：版本不匹配）
```json
{
  "code": 409,
  "message": "版本不匹配",
  "data": {
    "code": "VERSION_MISMATCH",
    "message": "版本不匹配"
  }
}
```

## 回写约束
- `setTaskDone/setTaskErr` 必须按 `table_id + partition_desc + version + claim_type` 精确匹配
- 租约超时、版本不匹配、claim 类型不匹配时返回 `409`
- `setTaskDone(SPARK)` 仅在 `compaction_done=false` 时置 `true` 并 `archive_count + 1`
- `setTaskDone(FLINK)` 仅在 `compaction_done=true` 且 `transfer_done=false` 时置 `transfer_done=true`
- `setTaskErr` 仅释放 claim（清空 `claim_*`），用于立即重派发
