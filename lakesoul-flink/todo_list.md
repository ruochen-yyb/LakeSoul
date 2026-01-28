- [ ] 1. 确认参数与行为：白名单/黑名单的参数名、分隔符、优先级（白名单优先，且支持“白名单-黑名单”差集）
- [x] 2. 新增配置项：增加 `source_db.include_tables`（逗号分隔、非正则、表名或 db.table）
- [x] 3. 修改 `MysqlCdc` 同步逻辑：
  - 读取 include/exclude 参数
  - 白名单：仅同步指定表（可再排除黑名单）
  - 仅黑名单：通过 JDBC 枚举库内所有表后做差集，生成显式 tableList（不使用 `db.*`）
- [x] 4. 更新根目录 `README.md`：补充新增参数的使用方式与示例（千表同步场景）
- [x] 5. 编译校验：`mvn -DskipTests package` 确认无编译错误

