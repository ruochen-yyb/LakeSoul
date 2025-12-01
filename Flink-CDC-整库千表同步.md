### LakeSoul Flink CDC 整库千表同步指引（含命名规范扩展）

本文档说明如何使用 LakeSoul Flink CDC 实现整库/多表实时入湖，并介绍可配置的 ODS 命名规范扩展参数与常见问题处理。

参考官方文档（Flink 1.17）：
- LakeSoul Flink CDC 整库千表同步入湖：[文档链接](https://lakesoul-io.github.io/zh-Hans/docs/Usage%20Docs/flink-cdc-sync)

## 前置条件
- Flink 集群（1.17）已部署，客户端可执行 `flink run`。
- LakeSoul Flink Jar 已准备（建议使用官方发布版本）。
- Flink 集群 classpath 需包含所需的 CDC Connector 依赖（推荐全部放入以避免类加载错误）：
  - flink-connector-mysql-cdc-2.4.x.jar
  - flink-connector-postgres-cdc-2.4.x.jar
  - flink-connector-oracle-cdc-2.4.x.jar
  - flink-connector-sqlserver-cdc-2.4.x.jar
  - flink-connector-mongodb-cdc-2.4.x.jar
  将上述 jar 置于 `$FLINK_HOME/lib/`，并重启集群。

## LakeSoul 元数据库环境变量（必须）
Session 模式提交作业时需在 Client 环境也导出以下变量（JM/TM 也需配置）：

```bash
export LAKESOUL_PG_DRIVER=com.lakesoul.shaded.org.postgresql.Driver
export LAKESOUL_PG_URL=jdbc:postgresql://<meta_host>:5432/<meta_db>?stringtype=unspecified
export LAKESOUL_PG_USERNAME=<meta_user>
export LAKESOUL_PG_PASSWORD=<meta_pass>
```

## 命名规范扩展（仅 ODS 层，通用化配置）
- 目标：CDC 作业仅负责 ODS 层；允许自定义命名空间与表名模板。
- 占位符：
  - `{db}`：MySQL 为 catalog（库名），Postgres/Oracle 为 schema，MongoDB 为 database
  - `{table}`：源表名
- 参数：
  - `--naming.enable true|false`：启用命名扩展
  - `--naming.target_namespace <命名空间>`：如 `ods`、`ods123`、`ods_new`
  - `--naming.table_format <模板>`：如 `ods_{db}_plantA_{table}`（可内联厂区名等）
  - `--naming.case preserve|lower|upper`：表名大小写处理（默认 `preserve`）

生成路径：`{warehouse_path}/{target_namespace}/{render(table_format)}`。

示例：
- 源端：`ventilation.FanEquipment`（Postgres schema=ventilation 或 MySQL db=ventilation）
- 参数：`--naming.enable true --naming.target_namespace ods_new --naming.table_format ods_{db}_plantA_{table}`
- 结果：写入 `.../ods_new/ods_ventilation_plantA_FanEquipment`。

## 运行示例（PostgreSQL，多表）

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC $LAKESOUL_FLINK_JAR \
  --source_db.db_type "postgres" \
  --source_db.host "<host>" \
  --source_db.port 5432 \
  --source_db.db_name "<database>" \
  --source_db.user "<user>" \
  --source_db.password "<pass>" \
  --source_db.schemaList "public" \
  --source_db.schema_tables "public.tbl_a,public.tbl_b" \
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

MySQL 示例（整库）：

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC $LAKESOUL_FLINK_JAR \
  --source_db.db_type "mysql" \
  --source_db.host "<host>" \
  --source_db.port 3306 \
  --source_db.db_name "<database>" \
  --source_db.user "<user>" \
  --source_db.password "<pass>" \
  --source.parallelism 4 \
  --sink.parallelism 4 \
  --job.checkpoint_interval 60000 \
  --warehouse_path s3://<bucket>/lakesoul/flink/data \
  --flink.checkpoint s3://<bucket>/lakesoul/flink/checkpoints \
  --flink.savepoint  s3://<bucket>/lakesoul/flink/savepoints \
  --naming.enable true \
  --naming.target_namespace ods \
  --naming.table_format ods_{db}_plantB_{table} \
  --naming.case preserve
```

## 常见问题与排查
- 启动时报 `NoClassDefFoundError`（如 `OracleSourceBuilder`）：
  - 原因：`JdbcCDC` 对各 CDC connector 有编译期引用；若 `$FLINK_HOME/lib` 缺少任一 connector jar，JVM 类加载可能失败。
  - 处理：将所需 CDC connector jar 放入 `$FLINK_HOME/lib` 并重启集群。

- 作业初始化失败，Checkpoint Storage 创建失败（S3 SAX Parser 相关）：
  - 报错示例：`SAXNotSupportedException` / `oracle.xml.parser.v2.SAXParser` / `Couldn't initialize a SAX driver`
  - 原因：类路径存在 Oracle XML 解析器（如 `xmlparserv2.jar`）覆盖 JDK 解析器，AWS SDK 解析 S3 XML 失败。
  - 处理：删除 `$FLINK_HOME/lib` 中的 Oracle XML 解析相关 jar；或在 `flink-conf.yaml` 指定 JDK SAX 工厂：
    - `env.java.opts: -Djavax.xml.parsers.SAXParserFactory=com.sun.org.apache.xerces.internal.jaxp.SAXParserFactoryImpl`
  - 重启 Flink 后重试。

- Session 模式环境变量未生效：
  - `flink run` 作为 Client 不读取 JM/TM 环境变量，需在提交端也 `export LAKESOUL_PG_*`。

## 参数速查
- 源端：`--source_db.*`（类型、地址、库/Schema、表、用户密码等）
- Flink：`--source.parallelism`、`--sink.parallelism`、`--job.checkpoint_interval`、`--flink.checkpoint`、`--flink.savepoint`
- LakeSoul：`--warehouse_path`、元数据库环境变量 `LAKESOUL_PG_*`
- 命名扩展（ODS）：`--naming.enable`、`--naming.target_namespace`、`--naming.table_format`、`--naming.case`

## 说明
- CDC 作业仅负责 ODS 层落湖；DWD/DWS/ADS 建议由下游作业按同一命名规范生成。
- 命名扩展在反序列化阶段生效，保持 Exactly Once 语义。


