## lakesoul-flink

### Mysql CDC 多表同步（白名单 / 黑名单）

`JdbcCDC` 入口（`--source_db.db_type mysql`）支持通过**显式表清单**控制捕获范围（不使用正则表达式；参数为逗号分隔表名）。

- **白名单**：只同步指定表
- **黑名单**：排除指定表
- **同时配置**：最终表集合 = 白名单 - 黑名单

说明：旧入口 `MysqlCdc` 已标注弃用（仅为兼容保留），后续建议统一使用 `JdbcCDC`。

### 入湖命名规范（目标库名 / 表名前缀）

`JdbcCDC`（mysql 分支）支持通过现有的 `naming.*` 参数统一规范入湖的 **namespace（库名）** 和 **表名**：

- **`--naming.enable`**：开启命名规则（可选；若仅配置了 `naming.target_namespace` / `naming.table_format`，也会自动启用）
- **`--naming.target_namespace`**：目标入湖库名（namespace）
- **`--naming.table_format`**：目标入湖表名格式，支持占位符 `{db}`、`{table}`
  - 表名前缀可用：`--naming.table_format "prefix_{table}"`
- **`--naming.case`**：表名大小写（`preserve|lower|upper`）

#### 参数

- **`--source_db.include_tables`**：白名单，逗号分隔；支持 `table` 或 `db.table`
- **`--source_db.exclude_tables`**：黑名单，逗号分隔；支持 `table` 或 `db.table`

#### 示例

- **只同步指定表（白名单）**

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink-*.jar \
  --source_db.db_type mysql \
  --source_db.db_name mydb \
  --source_db.user root \
  --source_db.password 123456 \
  --source_db.host 127.0.0.1 \
  --source_db.port 3306 \
  --warehouse_path file:///tmp/lakesoul \
  --server_time_zone Asia/Shanghai \
  --source_parallelism 4 \
  --bucket_parallelism 4 \
  --source_db.include_tables t1,t2,t3
```

- **同步全库但排除部分表（黑名单）**

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink-*.jar \
  --source_db.db_type mysql \
  --source_db.db_name mydb \
  --source_db.user root \
  --source_db.password 123456 \
  --source_db.host 127.0.0.1 \
  --source_db.port 3306 \
  --warehouse_path file:///tmp/lakesoul \
  --server_time_zone Asia/Shanghai \
  --source_parallelism 4 \
  --bucket_parallelism 4 \
  --source_db.exclude_tables t_tmp,t_backup
```

- **规范入湖库名 + 表名前缀（千表同步场景）**

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink-*.jar \
  --source_db.db_type mysql \
  --source_db.db_name mydb \
  --source_db.user root \
  --source_db.password 123456 \
  --source_db.host 127.0.0.1 \
  --source_db.port 3306 \
  --warehouse_path file:///tmp/lakesoul \
  --server_time_zone Asia/Shanghai \
  --source_parallelism 4 \
  --bucket_parallelism 4 \
  --naming.target_namespace ods \
  --naming.table_format mysql_{table} \
  --naming.case lower
```

### MongoDB CDC 多表同步

`JdbcCDC` 入口（`--source_db.db_type mongodb`）会基于 `MongoDBSource` 构建 CDC Source，并将变更写入 LakeSoul。

#### 参数

- **`--source_db.schema_tables`**：逗号分隔的 collection 列表（会原样传给 `MongoDBSource.collectionList(...)`）。建议使用 `db.collection` 形式。
- **`--source_db.host`**：传给 `MongoDBSource.hosts(...)`；建议写成 `host:port`（如 `127.0.0.1:27017`）。
- **`--batchSize`**：Mongo source 的 batch size（对应 key=`batchSize`）。
- **入湖命名规范（同 MySQL）**：支持 `naming.*`（`target_namespace/table_format/case`）。其中 MongoDB 场景 `{db}`=database，`{table}`=collection。

#### 示例

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink-*.jar \
  --source_db.db_type mongodb \
  --source_db.db_name mydb \
  --source_db.user myuser \
  --source_db.password 'mypassword' \
  --source_db.host '127.0.0.1:27017' \
  --source_db.schema_tables 'mydb.collection1,mydb.collection2' \
  --batchSize 1024 \
  --warehouse_path file:///tmp/lakesoul \
  --server_time_zone Asia/Shanghai \
  --source_parallelism 4 \
  --bucket_parallelism 4 \
  --flink.checkpoint file:///tmp/flink-checkpoints \
  --job.checkpoint_interval 600000
```

- **规范入湖库名 + 表名前缀（MongoDB 场景）**

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink-*.jar \
  --source_db.db_type mongodb \
  --source_db.db_name mydb \
  --source_db.user myuser \
  --source_db.password 'mypassword' \
  --source_db.host '127.0.0.1:27017' \
  --source_db.schema_tables 'mydb.collection1,mydb.collection2' \
  --batchSize 1024 \
  --warehouse_path file:///tmp/lakesoul \
  --server_time_zone Asia/Shanghai \
  --source_parallelism 4 \
  --bucket_parallelism 4 \
  --naming.target_namespace ods \
  --naming.table_format mongo_{table} \
  --naming.case lower \
  --flink.checkpoint file:///tmp/flink-checkpoints \
  --job.checkpoint_interval 600000
```
