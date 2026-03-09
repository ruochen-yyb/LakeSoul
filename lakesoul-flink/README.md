# UzsAutoTransfer 使用说明

## 入口类
- `org.apache.flink.lakesoul.entry.transfer.UzsAutoTransfer`

## 必填参数
- `--transfer.api-base-url`：接口服务地址，例如 `http://127.0.0.1:8080`
- `--transfer.claimed-by`：worker 标识，例如 `flink-worker-1`
- `--transfer.lakesoul.warehouse`：LakeSoul warehouse，例如 `s3a://uzsdb`
- `--transfer.lakesoul.s3.endpoint`：S3 endpoint，例如 `http://172.24.0.166:90`
- `--transfer.lakesoul.s3.access-key`：S3 access key
- `--transfer.lakesoul.s3.secret-key`：S3 secret key

## 可选参数
- `--transfer.lakesoul.catalog-name`：catalog 名称，默认 `lakesoul`
- `--transfer.lakesoul.s3.path-style-access`：S3 path style access，默认 `true`
- `--transfer.no-task-sleep-ms`：无任务时轮询间隔，默认 `10000`
- `--transfer.retryable-fail-sleep-ms`：可重试失败后的等待时间，默认 `300000`
- `--transfer.max-consecutive-fails`：连续失败阈值，默认 `10`
- `--transfer.circuit-breaker-sleep-ms`：达到连续失败阈值后的熔断等待时间，默认 `60000`
- `--transfer.done-retry-max-attempts`：`setTaskDone` 最大重试次数，默认 `3`
- `--transfer.done-retry-sleep-ms`：`setTaskDone` 重试间隔，默认 `5000`
- `--transfer.sql-timeout-ms`：单任务 SQL 执行超时，默认 `1800000`
- `--transfer.http-timeout-ms`：HTTP 请求超时，默认 `10000`

## 运行示例
```bash
flink run -c org.apache.flink.lakesoul.entry.transfer.UzsAutoTransfer lakesoul-flink.jar \
  --transfer.api-base-url http://127.0.0.1:8080 \
  --transfer.claimed-by flink-worker-1 \
  --transfer.lakesoul.warehouse s3a://uzsdb \
  --transfer.lakesoul.s3.endpoint http://172.24.0.166:90 \
  --transfer.lakesoul.s3.access-key <access-key> \
  --transfer.lakesoul.s3.secret-key <secret-key> \
  --transfer.lakesoul.s3.path-style-access true \
  --transfer.no-task-sleep-ms 10000 \
  --transfer.retryable-fail-sleep-ms 300000
```
