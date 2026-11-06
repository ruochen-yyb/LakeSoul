# JdbcCDC 同步 PG 到 LakeSoul 时如何指定 catalog 和数据库

- 时间：2026-11-06 16:43
- 问题类型：Flink CDC 数据同步（使用咨询）
- 涉及入口：`org.apache.flink.lakesoul.entry.JdbcCDC`

## 结论（先行）

1. **JdbcCDC 没有 "catalog" 参数**。该入口不经过 Flink Catalog（如 LakeSoulCatalog），而是直接写 LakeSoul 元数据 + 数据文件，因此不存在 catalog 概念。
2. **目标 LakeSoul 数据库（namespace）通过 `--sink_db_name` 指定**；不传时默认取源库 `--source_db.db_name` 的值（JdbcCDC.java L121-L123）。
3. **数据落地路径由 `--warehouse_path`（或全局配置 `flink.warehouse.dir`）决定**，最终表路径为 `warehouse_path / <sink_db_name> / <table_name>`。

## 代码依据

### 1. 参数解析（JdbcCDC.main）

[JdbcCDC.java](file:///e:/workspace/workspace/LakeSoul/lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcCDC.java)

- L78：`String sinkDBName = parameter.get(SINK_DBNAME.key(), SINK_DBNAME.defaultValue());`
- L121-L123：`sinkDBName == null` 时回退为源库 `dbName`。

参数定义在 [LakeSoulDDLSinkOptions.java](file:///e:/workspace/workspace/LakeSoul/lakesoul-flink/src/main/java/org/apache/flink/lakesoul/tool/LakeSoulDDLSinkOptions.java#L62-L66)：

```java
public static final ConfigOption<String> SINK_DBNAME = ConfigOptions
        .key("sink_db_name")
        .stringType()
        .defaultValue(null)
        .withDescription("Database for Data into LakeSoul");
```

### 2. sinkDBName 的作用链

```
--sink_db_name
   ├── postgresCdc(..., sinkDBName)                              (JdbcCDC L208)
   ├── new BinaryDebeziumDeserializationSchema(convert, warehousePath, sinkDBName)  (L285)
   │      └── BinarySourceRecord 中 tableId.schema() = sinkDBName (BinarySourceRecord L67-L81)
   ├── manager.importOrSyncLakeSoulNamespace(sinkDBName)          (L293，创建 LakeSoul namespace)
   └── 表路径 = warehouse_path / sinkDBName / table_name          (BinarySourceRecord L107-L111)
```

### 3. 关于 "catalog"

- JdbcCDC 整条链路（Source → HashPartition → LakeSoulMultiTableSinkStreamBuilder）不注册、不使用任何 Flink Catalog。
- LakeSoul 的 Flink Catalog（`LakeSoulCatalog`）仅在 SQL/Table API 查询时使用。查询侧指定方式：
  ```sql
  CREATE CATALOG lakesoul WITH ('type'='lakesoul', 'warehouse'='...');
  USE CATALOG lakesoul;
  USE <sink_db_name>;   -- 即同步时传入的 sink_db_name
  ```
- 也就是说：**同步任务里用 `sink_db_name` 决定目标库；查询时用 `USE CATALOG/DATABASE` 对应访问**，二者通过元数据中的 namespace 关联。

## 使用示例（PG → LakeSoul）

```bash
flink run -c org.apache.flink.lakesoul.entry.JdbcCDC lakesoul-flink.jar \
  --source_db.type postgres \
  --source_db.host 127.0.0.1 \
  --source_db.port 5432 \
  --source_db.db_name source_db \
  --source_db.user postgres \
  --source_db.password xxx \
  --source_db.schema_tables public.table1,public.table2 \
  --sink_db_name target_db \        # 指定 LakeSoul 目标数据库（namespace）
  --warehouse_path file:///tmp/lakesoul \  # 指定 LakeSoul 仓库存储根路径
  --flink.checkpoint file:///tmp/flink/checkpoint \
  --source.parallelism 1 \
  --bucket.parallelism 1 \
  --job.checkpoint_interval 10000
```

关键点：
- `--sink_db_name target_db` → LakeSoul 中的数据库名为 `target_db`
- 不传 `--sink_db_name` → 默认等于 `--source_db.db_name`（即 `source_db`）
- 无需（也无法）指定 catalog

## 待确认 / 下一步

- 是否需要我进一步验证 `BinaryDebeziumDeserializationSchema` 中多 schema（如 PG 多 schema）映射到目标库时表名前缀（`s_<schema>_<table>`）的行为？
- 若你希望在同步后通过 Flink SQL / Spark 查询，是否需要补充 LakeSoulCatalog 的建 catalog 示例？
- 无需改动任何项目文件。
