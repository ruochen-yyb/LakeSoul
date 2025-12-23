### LakeSoul Flink CDC Postgres 整库/整 schema 模式设计

本设计文档描述在 `JdbcCDC` 中为 Postgres 源增加整库/整 schema 捕获能力的技术方案与实现要点，目标是：

- **支持在 Postgres 下仅通过 `schemaList` 即可捕获其下所有用户表**，减少逐表维护 `schema_tables` 的成本；
- **保持对现有参数与行为的兼容**，不影响 MySQL / Oracle / SQLServer / MongoDB 的现有使用方式。

---

## 一、背景与现状

- **MySQL**：当前已采用“整库 + 通配表名”的方式：
  - `databaseList(dbName)` + `tableList(dbName + ".*")`。
- **Postgres**：当前实现依赖显式表名列表：

```209:215:lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcCDC.java
JdbcIncrementalSource<BinarySourceRecord> pgSource = PostgresSourceBuilder.PostgresIncrementalSource.<BinarySourceRecord>builder()
        .hostname(host)
        .schemaList(schemaList)
        .tableList(tableList)
        .database(dbName)
        ...
```

- `tableList` 来源（原始实现）：

```68:76:lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcCDC.java
if (dbType.equalsIgnoreCase("oracle") || dbType.equalsIgnoreCase("postgres") ) {
    schemaList = parameter.get(SOURCE_DB_SCHEMA_LIST.key()).split(",");
    String[] tables = parameter.get(SOURCE_DB_SCHEMA_TABLES.key()).split(",");
    tableList = new String[tables.length];
    for (int i = 0; i < tables.length; i++) {
        tableList[i] = tables[i].toUpperCase();
    }
    splitSize = parameter.getInt(SOURCE_DB_SPLIT_SIZE.key(), SOURCE_DB_SPLIT_SIZE.defaultValue());
}
```

**问题**：Postgres 必须手工维护 `source_db.schema_tables`，不支持“仅给 schema 自动整库”。

---

## 二、目标与范围

- **目标**：
  - 为 Postgres 新增“整库/整 schema 模式”，通过 `schemaList` 自动发现并捕获所有用户表；
  - 兼容旧行为：未开启整库模式时，仍以 `schema_tables` 白名单为准。
- **范围**：
  - 仅在 `--source_db.db_type=postgres` 时生效；
  - 一期只做“启动时扫描一次元数据”，不实现运行期自动发现新建表。

---

## 三、参数设计

### 3.1 新增 ConfigOption

在 `LakeSoulSinkOptions` 中新增两个参数：

```240:257:lakesoul-flink/src/main/java/org/apache/flink/lakesoul/tool/LakeSoulSinkOptions.java
public static final ConfigOption<Boolean> SOURCE_DB_CAPTURE_ALL_TABLES = ConfigOptions
        .key("source_db.capture_all_tables")
        .booleanType()
        .defaultValue(false)
        .withDescription("If true, capture all tables under the given schemaList for supported db types (initially postgres).");

public static final ConfigOption<Boolean> SOURCE_DB_AUTO_DISCOVER_NEW_TABLES = ConfigOptions
        .key("source_db.auto_discover_new_tables")
        .booleanType()
        .defaultValue(false)
        .withDescription("If true, periodically discover and capture newly created tables (initially only for postgres if supported).");
```

> 说明：
> - `SOURCE_DB_CAPTURE_ALL_TABLES`：用于控制是否启用整库/整 schema 表自动发现；
> - `SOURCE_DB_AUTO_DISCOVER_NEW_TABLES`：一期仅作为预留开关，当前实现不做运行期动态扩展表列表。

### 3.2 命令行参数

- `--source_db.capture_all_tables true|false`（默认 `false`）；
- `--source_db.auto_discover_new_tables true|false`（默认 `false`，暂不使用）；
- 仍沿用现有：
  - `--source_db.schemaList "<schema1,schema2,...>"`；
  - `--source_db.schema_tables "schema.tbl_a,schema.tbl_b,..."`。

---

## 四、行为设计（Postgres）

### 4.1 参数解析流程

在 `JdbcCDC.main` 中，对 Postgres 分支逻辑调整为：

```59:82:lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcCDC.java
String dbType = parameter.get(SOURCE_DB_TYPE.key(), SOURCE_DB_TYPE.defaultValue());
...
// Postgres / Oracle 场景公共参数解析
if (dbType.equalsIgnoreCase("oracle") || dbType.equalsIgnoreCase("postgres")) {
    schemaList = parameter.get(SOURCE_DB_SCHEMA_LIST.key()).split(",");
    splitSize = parameter.getInt(SOURCE_DB_SPLIT_SIZE.key(), SOURCE_DB_SPLIT_SIZE.defaultValue());
}
// Postgres 整库/整 schema 捕获模式与兼容行为
if (dbType.equalsIgnoreCase("postgres")) {
    // 是否开启整库/整 schema 表自动发现功能
    boolean captureAllTables = parameter.getBoolean(
            LakeSoulSinkOptions.SOURCE_DB_CAPTURE_ALL_TABLES.key(),
            LakeSoulSinkOptions.SOURCE_DB_CAPTURE_ALL_TABLES.defaultValue());
    if (captureAllTables) {
        // 开启整库模式：基于 schemaList 从 Postgres 元数据发现所有用户表
        tableList = discoverAllTablesFromPostgres();
    } else {
        // 兼容旧行为：仍然要求显式提供 schema_tables 白名单
        String rawTables = parameter.get(SOURCE_DB_SCHEMA_TABLES.key(), "").trim();
        if (rawTables.isEmpty()) {
            throw new IllegalArgumentException(
                    "Postgres CDC requires either non-empty --source_db.schema_tables "
                            + "or enabling --source_db.capture_all_tables=true");
        }
        String[] tables = rawTables.split(",");
        tableList = new String[tables.length];
        for (int i = 0; i < tables.length; i++) {
            tableList[i] = tables[i].toUpperCase();
        }
    }
}
// Oracle 仍然采用 schema_tables 白名单模式
if (dbType.equalsIgnoreCase("oracle")) {
    String rawTables = parameter.get(SOURCE_DB_SCHEMA_TABLES.key(), "").trim();
    if (rawTables.isEmpty()) {
        throw new IllegalArgumentException(
                "Oracle CDC requires non-empty --source_db.schema_tables configuration.");
    }
    String[] tables = rawTables.split(",");
    tableList = new String[tables.length];
    for (int i = 0; i < tables.length; i++) {
        tableList[i] = tables[i].toUpperCase();
    }
}
```

### 4.2 `discoverAllTablesFromPostgres` 元数据查询

新增私有方法，用于基于现有连接参数和 `schemaList` 自动发现所有用户表：

```289:337:lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcCDC.java
private static String[] discoverAllTablesFromPostgres() {
    // 简单的 JDBC 元数据查询实现，要求 Flink 集群 classpath 中存在 Postgres JDBC Driver。
    String jdbcUrl = String.format("jdbc:postgresql://%s:%d/%s", host, port, dbName);
    String[] schemas = schemaList != null ? schemaList : new String[0];
    if (schemas.length == 0) {
        throw new IllegalArgumentException(
                "Postgres CDC capture_all_tables is enabled, but source_db.schemaList is empty. "
                        + "Please configure at least one schema, e.g. --source_db.schemaList \"public\"");
    }
    // 构造 schema 过滤条件占位符
    StringBuilder schemaPlaceholders = new StringBuilder();
    for (int i = 0; i < schemas.length; i++) {
        if (i > 0) {
            schemaPlaceholders.append(",");
        }
        schemaPlaceholders.append("?");
    }
    String sql = "SELECT table_schema, table_name "
            + "FROM information_schema.tables "
            + "WHERE table_type = 'BASE TABLE' "
            + "AND table_schema IN (" + schemaPlaceholders + ") "
            + "ORDER BY table_schema, table_name";

    List<String> tables = new ArrayList<>();
    try (Connection conn = DriverManager.getConnection(jdbcUrl, userName, passWord);
         PreparedStatement ps = conn.prepareStatement(sql)) {
        // 绑定 schema 参数
        for (int i = 0; i < schemas.length; i++) {
            ps.setString(i + 1, schemas[i]);
        }
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String schema = rs.getString("table_schema");
                String table = rs.getString("table_name");
                // CDC 入口内部使用 schema.table 的形式，再在后续逻辑中统一转大写/命名处理
                tables.add((schema + "." + table).toUpperCase());
            }
        }
    } catch (SQLException e) {
        throw new RuntimeException("Failed to discover Postgres tables for schemas "
                + String.join(",", schemas) + " from " + jdbcUrl, e);
    }
    if (tables.isEmpty()) {
        throw new IllegalArgumentException(
                "No base tables found for Postgres schemas: " + String.join(",", schemas)
                        + ". Please check schemaList configuration and database metadata.");
    }
    return tables.toArray(new String[0]);
}
```

> 说明：
> - 使用 `information_schema.tables` 查询所有 `BASE TABLE`；
> - 过滤条件为 `table_schema IN (?)`，参数由 `schemaList` 提供；
> - 返回值使用大写的 `schema.table` 形式，与原有逻辑保持一致；
> - 若查询失败或无表，将抛出明确异常，提示检查配置与数据库状态。

---

## 五、兼容性与错误处理

- **兼容性**：
  - 不设置 `--source_db.capture_all_tables` 时，默认 `false`，行为与原版本完全一致；
  - 原有 `schema_tables` 配置继续生效，仅在整库模式开启时被忽略。
- **错误处理**：
  - `capture_all_tables=true` 但 `schemaList` 为空：
    - 抛出 `IllegalArgumentException`，提示补充 `--source_db.schemaList`（如 `public`）。
  - `capture_all_tables=false` 且 `schema_tables` 为空：
    - 抛出 `IllegalArgumentException`，提示必须提供非空 `schema_tables` 或开启整库模式。
  - `schemaList` 对应 schema 下无任何 `BASE TABLE`：
    - 抛出异常提示检查库内表及 schema 配置。

---

## 六、使用示例（汇总）

### 6.1 传统多表模式（显式表名）

详见 `Flink-CDC-整库千表同步.md` 中 “运行示例（PostgreSQL，多表）” 小节。

### 6.2 整库/整 schema 模式

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC $LAKESOUL_FLINK_JAR \
  --source_db.db_type "postgres" \
  --source_db.host "<host>" \
  --source_db.port 5432 \
  --source_db.db_name "<database>" \
  --source_db.user "<user>" \
  --source_db.password "<pass>" \
  --source_db.schemaList "public,dim" \
  --source_db.capture_all_tables true \
  --source_db.slot_name flinkcdc \
  --pluginName "pgoutput" \
  --source.parallelism 4 \
  --sink.parallelism 4 \
  --job.checkpoint_interval 60000 \
  --warehouse_path s3://<bucket>/lakesoul/flink/data \
  --flink.checkpoint s3://<bucket>/lakesoul/flink/checkpoints \
  --flink.savepoint  s3://<bucket>/lakesoul/flink/savepoints \
  --naming.enable true \
  --naming.target_namespace ods_new \
  --naming.table_format ods_{db}_plantA_{table} \
  --naming.case preserve
```

---

## 七、后续扩展方向（可选）

- 基于 `SOURCE_DB_AUTO_DISCOVER_NEW_TABLES`：
  - 结合 Flink CDC / Debezium 能力，支持运行期新建表自动发现；
  - 或由调度层周期性重启作业 + 重跑元数据发现。
- 丰富过滤能力：
  - 增加前缀/正则过滤，如 `source_db.table_name_pattern`；
  - 支持排除特定 schema / 表（黑名单）。




