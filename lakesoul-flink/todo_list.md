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

---

## 8. 合并 MysqlCdc 逻辑进入 JdbcCDC（后续仅使用 JdbcCDC）

- [x] 8.1 对比差异（MysqlCdc vs JdbcCDC#mysqlCdc）
  - 捕获表范围：MysqlCdc 使用显式 `tableList(db.table...)` 且支持 `include/exclude`；JdbcCDC 目前为 `dbName + ".*"` 且不支持过滤
  - 千表同步：MysqlCdc 启动时 JDBC 枚举 base tables（仅表名），JdbcCDC 当前不枚举
  - 内部表过滤：MysqlCdc 默认排除 `sys_config`
  - 命名规则：MysqlCdc 支持 `naming.*` 自动启用；JdbcCDC 仅显式设置 `naming.enable` 才生效
  - namespace 同步：需要确保以最终 target namespace 同步（而非源库名）

- [x] 8.2 合并落地（JdbcCDC mysql 分支）
  - [x] 解析 mysql 的 `source_db.include_tables / source_db.exclude_tables` 写入 `conf`
  - [x] 构造显式捕获表清单（优先白名单；否则枚举全库 base tables；应用黑名单；默认排除 `sys_config`）
  - [x] `MySqlSourceBuilder.tableList(...)` 使用显式数组（不再用 `db.*`）
  - [x] `naming.*` 自动启用逻辑合并进 `JdbcCDC.main`
  - [x] namespace 同步使用最终 target namespace

- [x] 8.3 兼容与收敛
  - [x] 保留 `MysqlCdc` 入口类：加 `@Deprecated`，README 不再引用

- [x] 8.4 文档更新
  - [x] `README.md`：示例入口统一为 `org.apache.flink.lakesoul.entry.JdbcCDC --source_db.db_type mysql`

## 9. 确认

- [x] 9. 已确认：落地合并；保留 `MysqlCdc`，标注弃用

---

## 10. 行为确认：mysql 默认排除 sys_config

- [x] 10.1 确认：保持当前行为：`sys_config` 永远从捕获表集合中排除（即使显式写进 include_tables 也不生效）
- [ ] 10.2 备选：允许 include_tables 显式覆盖（仅当 include_tables 包含 sys_config 时才捕获）【不采用】
- [ ] 10.3 备选：新增开关（例如 `source_db.exclude_internal_tables`，默认 true）【不采用】

---

## 11. 补充文档：JdbcCDC MongoDB CDC 运行示例

- [x] 11.1 基于 `JdbcCDC#mongoCdc` 确认必须参数与默认行为（`db_type/db_name/host/schema_tables/batchSize` 等）
- [x] 11.2 产出一条可直接运行的 `flink run -c org.apache.flink.lakesoul.entry.JdbcCDC ...` 示例命令（含 checkpoint、并行度、warehouse_path）
- [x] 11.3 更新根目录 `README.md`：新增 “MongoDB CDC 多表同步” 小节与示例
