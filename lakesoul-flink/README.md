## LakeSoul Flink Connector 简要说明

- **参数 `source_db.table.list`**

  - **配置项**: `source_db.table.list`
  - **类型**: 字符串
  - **作用**: 指定 MySQL CDC 任务需要捕获的表列表（仅表名或表名正则），支持逗号分隔多个规则。
  - **默认行为**: 不配置或配置为空字符串时，等价于捕获当前库 `source_db.db_name` 下的所有表，即使用模式 `dbName + ".*"`。
  - **匹配规则**:
    - 单个条目可以是表名或表名的正则表达式（例如 `user_.*`、`order_(2023|2024)_.*`）。
    - 若条目中不包含点号 (`.`)，系统会自动补全为 `dbName + "." + 条目`，只作用于当前配置的 `source_db.db_name`。
    - 若条目中已经包含点号（形如 `db1.user_.*`），则会按其完整形式直接透传给 CDC 源（高级用法，一般情况下无需指定库名）。
  - **示例**:
    - 捕获当前库下所有以 `user_` 开头和 `order_` 开头的表：
      - `--source_db.table.list='user_.*,order_.*'`
    - 捕获当前库下 `special` 单表：
      - `--source_db.table.list='special'`

- **参数 `source_db.exclude_tables`**

  - **配置项**: `source_db.exclude_tables`
  - **类型**: 字符串
  - **作用**: 指定 MySQL CDC 任务需要排除的表列表（黑名单，逗号分隔），支持表名或正则模式。
  - **互斥规则**: `source_db.exclude_tables` 与 `source_db.table.list` **不可同时配置**，需分开使用。
  - **生效机制**: 黑名单模式下会在任务启动时通过 JDBC 枚举 `source_db.db_name` 下的所有表名并执行过滤，生成最终 include 的 `tableList` 交给 Source（用于避免 chunk splitting 阶段仍分析被排除表）。
  - **注意**: 需要账号具备读取库表元数据的权限（例如 `information_schema` / `SHOW TABLES`）。
  - **匹配规则**:
    - 若条目中不包含点号 (`.`)，系统会自动补全为 `dbName + "." + 条目`，只作用于当前配置的 `source_db.db_name`。
    - 若条目中已包含点号（形如 `db1.tmp_.*`），则按完整形式透传给 CDC 源（高级用法）。
  - **示例**:
    - 捕获当前库下所有表，但排除临时表与归档表：
      - `--source_db.exclude_tables='tmp_.*,archive_.*'`
    - 捕获当前库下所有表，但排除单表 `audit_log`：
      - `--source_db.exclude_tables='audit_log'`

- **参数 `sink_db_name`（目标库名 / Namespace）**

  - **配置项**: `sink_db_name`
  - **类型**: 字符串
  - **作用**: 指定入湖目标库名（LakeSoul namespace）。
  - **默认行为**: 不配置或为空时，使用源库名 `source_db.db_name`。

- **参数 `sink_table_prefix`（目标表前缀）**
  - **配置项**: `sink_table_prefix`
  - **类型**: 字符串
  - **作用**: 指定入湖目标表名前缀，入湖表名规则为 `${prefix}_${sourceTable}`（不包含源库名）。
  - **默认行为**: 不配置时保持历史行为（表名为 `s_${sourceDb}_${sourceTable}`）；配置时启用新规则。
  - **示例**:
    - 以 `ods_` 作为前缀写入（例如源表 `user` → 目标表 `ods_user`）：
      - `--sink_table_prefix='ods'`

### 使用示例（MySQL 千表同步）

> 下面示例仅展示关键参数，其他 Flink/Checkpoint 参数请按部署环境补齐。

- **入口 1：`org.apache.flink.lakesoul.entry.MysqlCdc`**

```bash
flink run -c org.apache.flink.lakesoul.entry.MysqlCdc lakesoul-flink.jar \
  --source_db.db_name=mydb \
  --source_db.user=root \
  --source_db.password=xxx \
  --source_db.host=127.0.0.1 \
  --source_db.port=3306 \
  --warehouse_path=file:///tmp/lakesoul \
  --source_db.table.list='user_.*,order_.*' \
  --sink_db_name=lake_ods \
  --sink_table_prefix=ods
```

- **入口 1（黑名单用法）：`org.apache.flink.lakesoul.entry.MysqlCdc`**

```bash
flink run -c org.apache.flink.lakesoul.entry.MysqlCdc lakesoul-flink.jar \
  --source_db.db_name=mydb \
  --source_db.user=root \
  --source_db.password=xxx \
  --source_db.host=127.0.0.1 \
  --source_db.port=3306 \
  --warehouse_path=file:///tmp/lakesoul \
  --source_db.exclude_tables='tmp_.*,audit_log' \
  --sink_db_name=lake_ods \
  --sink_table_prefix=ods
```

- **入口 2：`org.apache.flink.lakesoul.entry.JdbcCDC`（db_type=mysql）**

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink.jar \
  --source_db.db_type=mysql \
  --source_db.db_name=mydb \
  --source_db.user=root \
  --source_db.password=xxx \
  --source_db.host=127.0.0.1 \
  --source_db.port=3306 \
  --warehouse_path=file:///tmp/lakesoul \
  --sink_db_name=lake_ods \
  --sink_table_prefix=ods
```
