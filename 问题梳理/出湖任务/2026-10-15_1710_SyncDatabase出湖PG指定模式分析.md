# SyncDatabase 出湖 PG 是否能够指定模式（Schema）分析

- 分析时间：2026-10-15 17:10
- 问题分类：出湖任务 / PostgreSQL Sink
- 涉及代码：`lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/SyncDatabase.java`

## 一、结论

**当前代码不支持通过独立参数显式指定 PG 模式（schema）**，但存在不改代码的变通方案（JDBC URL `currentSchema`）。
若需要一等公民式的 schema 支持，需要改动代码新增参数。

## 二、现状代码分析

`xsyncToPg` 方法（SyncDatabase.java:314-384）中与 schema 相关的关键路径：

1. **JDBC URL 拼接**（L325）：
   ```java
   String jdbcUrl = url + targetDatabase;
   ```
   URL 仅拼接库名，无任何 schema 相关处理。

2. **自动建表 DDL**（L334 + `pgAndMsqlCreateTableSql` L149-167）：
   ```java
   CREATE TABLE IF NOT EXISTS <targetTableName> (...)
   ```
   使用裸表名，不带 schema 限定，落在连接用户 `search_path` 的第一个 schema（默认 `public`）。

3. **Flink JDBC Sink DDL**（L363-378）：
   ```
   'connector' = 'jdbc', 'url' = '...', 'table-name' = '<targetTableName>'
   ```
   `table-name` 同样是裸表名，由 PG 驱动按 `search_path` 解析。

4. **参数定义**（`LakeSoulSinkDatabasesOptions.java`）：
   全部参数中**没有任何 schema 相关选项**（无 `target_db.schema_name` 之类）。

## 三、变通方案（不改代码）

### 方案 A：利用 PG JDBC 驱动的 `currentSchema` 参数

`targetDatabase` 直接拼接在 URL 末尾，因此可以传：

```
--target_db.db_name "mydb?currentSchema=myschema"
```

拼出的 JDBC URL 为 `jdbc:postgresql://host:5432/mydb?currentSchema=myschema`，效果：

- 原生 JDBC 建表（`CREATE TABLE IF NOT EXISTS <裸表名>`）落在 `myschema`；
- Flink JDBC Sink 生成的 `INSERT INTO "table"` 同样经 `search_path` 解析到 `myschema`。

**限制：**
- 目标 schema 必须**预先存在**（代码不会执行 `CREATE SCHEMA`）；
- 属于隐式行为，依赖 PG 驱动参数，不够直观；
- MySQL 路径中 `targetDatabase` 还用于血缘命名（L413），传带 `?` 的值会污染血缘名称（PG 路径目前无血缘逻辑，暂不受影响）。

### 方案 B：`table_name` 传 `schema.table` —— 不可行

- 自动建表 DDL `CREATE TABLE IF NOT EXISTS myschema.mytable (...)` 本身合法；
- 但 Flink DDL `create table myschema.mytable (...)` 会把 `myschema` 当作默认 catalog 下的 database 解析，该 database 不存在，DDL 直接失败。

故不可行。

## 四、如需正式支持（改代码方案）

新增参数 `target_db.schema_name`（可选，默认空），在 `xsyncToPg` 中：

1. 参数定义：`LakeSoulSinkDatabasesOptions` 增加 `TARGET_DB_SCHEMA_NAME`；
2. 建表前执行 `CREATE SCHEMA IF NOT EXISTS <schema>`；
3. 自动建表 DDL 使用限定名 `schema.table`；
4. Flink JDBC DDL 的 `'table-name'` 使用 `schema.table`（Flink JDBC connector 支持该格式）；
5. Flink 侧临时表名仍用裸表名（避免方案 B 的 catalog 解析问题）；
6. 文档 `14-export-to-databases.md`（中英文两份）补充说明。

## 五、顺带发现的疑似缺陷（与本次问题无直接关系）

- `SyncDatabase.main` L74 判断 `dbType.equals("postgresql")`，而 switch（L128）是 `case "postgres"`。若用户传 `postgresql`，`-D` 参数会被解析但 switch 抛 `not supported the database`。文档示例使用 `postgres`，建议统一。

## 六、待确认事项

1. 是否只需要方案 A（文档说明即可），还是需要正式实现方案四（新增参数）？
2. 若实现，schema 不存在时是自动创建还是报错退出？
3. 顺带发现的 `postgres`/`postgresql` 不一致是否一并修复？

## 七、结论与决策记录（2026-10-15 17:10）

- 用户决策：**暂不处理**，不改代码、不补文档，仅保留本分析文档；
- `postgres`/`postgresql` 不一致问题：**不修复**，保持现状；
- 后续如需指定 schema，可临时使用方案 A（`currentSchema`），或重新评估实施方案四。
