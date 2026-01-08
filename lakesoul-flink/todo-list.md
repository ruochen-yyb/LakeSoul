# todo-list

## mysql 千表同步优化

- [x] **MysqlCdc 增加黑名单过滤（与白名单互斥）**：支持 `source_db.exclude_tables`，通过 Debezium `table.exclude.list` 排除表；若同时配置 `source_db.table.list` 则直接报错
