## lakesoul-flink

### Mysql CDC 多表同步（白名单 / 黑名单）

`MysqlCdc` 入口支持通过**显式表清单**控制捕获范围（不使用正则表达式；参数为逗号分隔表名）。

- **白名单**：只同步指定表
- **黑名单**：排除指定表
- **同时配置**：最终表集合 = 白名单 - 黑名单

### 入湖命名规范（目标库名 / 表名前缀）

`MysqlCdc` 支持通过现有的 `naming.*` 参数统一规范入湖的 **namespace（库名）** 和 **表名**：

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
flink run -c org.apache.flink.lakesoul.entry.MysqlCdc lakesoul-flink-*.jar \
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
flink run -c org.apache.flink.lakesoul.entry.MysqlCdc lakesoul-flink-*.jar \
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
flink run -c org.apache.flink.lakesoul.entry.MysqlCdc lakesoul-flink-*.jar \
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
