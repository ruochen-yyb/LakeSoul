# todo-list

## mysql 千表同步优化

- [x] **MysqlCdc 增加黑名单过滤（与白名单互斥）**：支持 `source_db.exclude_tables`，启动时先枚举库内表名并按黑名单过滤，再生成最终 include `tableList` 传给 MySQL Source；若同时配置 `source_db.table.list` 则直接报错
