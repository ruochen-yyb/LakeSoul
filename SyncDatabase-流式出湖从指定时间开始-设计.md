<!--
SPDX-FileCopyrightText: 2026 LakeSoul Contributors

SPDX-License-Identifier: Apache-2.0
-->

## 背景

当前用户通过 `org.apache.flink.lakesoul.entry.SyncDatabase` 将 LakeSoul 表数据同步到外部数据库（MySQL/Postgres）。

`SyncDatabase` 在 MySQL/Postgres 场景下执行的写入 SQL 形态固定为：

- `INSERT INTO <target> SELECT * FROM lakeSoul.<db>.<table>`

但目前 source 侧查询未携带 LakeSoul Flink Connector 的读取 hint（`/*+ OPTIONS('readtype'='..','readstarttime'='..','timezone'='..')*/`），因此在 **流式出湖**（`--use_batch false`）时无法控制“从指定时间开始读取”。

## 目标

- 在 `SyncDatabase` 的 **流式出湖**模式下（`--use_batch false`），支持用户指定 **从某个时间点开始**读取 LakeSoul 表数据，并持续追增量。
- **向后兼容**：不传新参数时，保持现有行为不变。
- 适配范围：本次优先覆盖 **MySQL** 与 **Postgres** 出湖路径。

## 方案概述

通过为 `SyncDatabase` 增加一组可选的 `source.*` 参数，将其拼接为 LakeSoul source 查询的 SQL hints：

`SELECT * FROM lakeSoul.\`<db>\`.\`<table>\` /*+ OPTIONS('readtype'='incremental','readstarttime'='yyyy-MM-dd HH:mm:ss','timezone'='Asia/Shanghai')*/`

并将该查询用于 `INSERT INTO <target> <select>`。

## 参数设计（定稿）

新增 4 个可选参数（统一加 `source.` 前缀）：

- `--source.readtype`：`incremental | snapshot | fullread`
  - 默认：空（不启用 hints）
- `--source.readstarttime`：`yyyy-MM-dd HH:mm:ss`
  - 默认：空
- `--source.readendtime`：`yyyy-MM-dd HH:mm:ss`
  - 默认：空（流式通常不传）
- `--source.timezone`：IANA 时区（如 `Asia/Shanghai`）
  - 默认：空（按作业运行机器本地时区解释时间）

## 生效规则（兼容性）

定义 `enableSourceHints`：

- `enableSourceHints = (use_batch == false) AND (任一 source.* 参数非空)`

行为：

- `enableSourceHints=false`：保持现状（source SQL 不带 hints）
- `enableSourceHints=true`：在 LakeSoul 表名后拼接 `/*+ OPTIONS(...) */`

### 自动补全规则

当 `enableSourceHints=true` 且用户未提供 `--source.readtype`：

- 自动补 `readtype='incremental'`

理由：该功能主要用于“从某个时间点开始追增量”，默认增量读最符合直觉；且仅在用户显式启用（传入任一 `source.*`）时才会生效，不影响历史默认行为。

## 参数校验（enableSourceHints=true 时）

- 时间格式校验：
  - `source.readstarttime/source.readendtime` 必须匹配 `yyyy-MM-dd HH:mm:ss`
- 时间范围校验：
  - 若同时提供 start/end，则 `start <= end`
- 时区校验：
  - 若提供 `source.timezone`，要求在 `TimeZone.getAvailableIDs()` 内
- 模式校验：
  - 若 `--use_batch true` 且用户传了任一 `source.*`，忽略并打印 warn（不报错，避免误用导致任务无法启动）

## 影响范围（MySQL/Postgres）

在 `SyncDatabase.java` 中将以下 SQL 使用统一的 source select 构造方法：

- `xsyncToMysql()`：`INSERT INTO <mysql_target> <source_select>`
- `xsyncToPg()`：`INSERT INTO <pg_target> <source_select>`

建议将 source select 的构造封装为一个私有方法，避免重复与未来扩展成本。

## 运行示例

从“昨天 00:00（上海时区）”开始流式出湖（示例时间需按实际填写）：

```bash
./bin/flink run -c org.apache.flink.lakesoul.entry.SyncDatabase <jar> \
  --use_batch false \
  --target_db.db_type mysql \
  --source_db.db_name <lakesoul库> \
  --source_db.table_name <lakesoul表> \
  ...目标库参数... \
  --source.readstarttime "2026-01-14 00:00:00" \
  --source.timezone "Asia/Shanghai"
```

注：如未指定 `--source.readtype`，在启用 hints 时会自动按 `incremental` 生效。

