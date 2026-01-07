# TODO List（LakeSoul 优化）

## mysql 千表同步优化

- [x] 增加目标库名参数 `sink_db_name`：namespace 以目标库为准（为空回退源库）
- [x] 增加目标表前缀参数 `sink_table_prefix`：入湖表名规则为 `${prefix}_${sourceTable}`（不含源库名）
- [ ] 设计：多 schema/多库同名表冲突的可选命名策略（如 `${prefix}_${schema}_${table}` / 映射表）
- [ ] 设计：补充参数文档与示例（含兼容性说明与升级指引）
