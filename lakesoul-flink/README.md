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


