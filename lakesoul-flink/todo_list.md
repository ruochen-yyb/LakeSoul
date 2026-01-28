- [x] 1. 确认参数与行为：白名单/黑名单的参数名、分隔符、优先级（白名单优先，且支持“白名单-黑名单”差集）
- [x] 2. 新增配置项：增加 `source_db.include_tables`（逗号分隔、非正则、表名或 db.table）
- [x] 3. 修改 `MysqlCdc` 同步逻辑：
  - 读取 include/exclude 参数
  - 白名单：仅同步指定表（可再排除黑名单）
  - 仅黑名单：通过 JDBC 枚举库内所有表后做差集，生成显式 tableList（不使用 `db.*`）
- [x] 4. 更新根目录 `README.md`：补充新增参数的使用方式与示例（千表同步场景）

- [x] 5. 确认新参数与行为：入湖命名规范（沿用现有 `naming.*`，保持统一）
  - 目标入湖库名：`--naming.target_namespace`
  - 表名前缀：`--naming.table_format "prefix_{table}"`
  - `--naming.enable`：可选；若仅配置了 `naming.target_namespace` / `naming.table_format`，则自动启用

- [x] 6. 修改 `MysqlCdc`：接入并生效 `naming.*`
  - 读取并写入 `conf`：`naming.enable / naming.target_namespace / naming.table_format / naming.case`
  - `BinaryDebeziumDeserializationSchema` 使用带 `conf` 的构造方法，确保命名规则生效
  - `importOrSyncLakeSoulNamespace` 使用最终的 target namespace（否则会创建到 source namespace）

- [x] 7. 更新根目录 `README.md`：补充 `naming.*` 的使用方式与示例（目标库名 + 表前缀）

