# lakesoul-spark

## UZS 整分区聚合任务（NewCompaction 链路新增）

- 入口类：`com.dmetasoul.lakesoul.spark.compaction.UZSNewCompactionTask`
- 触发源：轮询 `POST /internal/tasks/compaction/claim`（不再依赖 PG `LISTEN/NOTIFY`）
- 行为：领取任务后执行 `LakeSoulTable.uzsFullPartitionCompaction`；成功回调 `POST /internal/tasks/compaction/success`，失败回调 `POST /internal/tasks/compaction/failure`
- 参数：
  - `--poll.base.url`：任务服务地址，默认 `http://127.0.0.1:8080`
  - `--worker.id`：worker 标识，默认 `spark-worker`；兼容旧参数 `--claimed.by`
  - `--lease.ms`：任务租约时长，默认 `60000`
  - `--no.task.interval.ms`：无任务时轮询间隔，默认 `10000`
  - `--execute.error.backoff.ms`：执行失败后退避时长，默认 `300000`（5分钟）
  - `--request.timeout.ms`：接口超时时间，默认 `10000`
  - `--done.retry.max`：`setTaskDone` 最大重试次数，默认 `6`
  - `--done.retry.interval.ms`：`setTaskDone` 重试间隔，默认 `10000`
  - `--callback.failure.backoff.ms`：`setTaskDone` 最终失败后的退避时长，默认 `60000`
  - `--err.retry.max`：`setTaskErr` 最大重试次数，默认 `3`
  - `--err.retry.interval.ms`：`setTaskErr` 重试间隔，默认 `10000`
