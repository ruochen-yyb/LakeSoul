## lakesoul-flink

### Mysql CDC 多表同步（白名单 / 黑名单）

`MysqlCdc` 入口支持通过**显式表清单**控制捕获范围（不使用正则表达式；参数为逗号分隔表名）。

- **白名单**：只同步指定表
- **黑名单**：排除指定表
- **同时配置**：最终表集合 = 白名单 - 黑名单

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

