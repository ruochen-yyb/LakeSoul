// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.flink.lakesoul.entry;

import com.dmetasoul.lakesoul.meta.DBManager;
import com.dmetasoul.lakesoul.meta.DBUtil;
import com.dmetasoul.lakesoul.meta.entity.TableInfo;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.mongodb.sink.MongoSink;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.lakesoul.entry.sql.flink.LakeSoulInAndOutputJobListener;
import org.apache.flink.lakesoul.metadata.LakeSoulCatalog;
import org.apache.flink.lakesoul.tool.JobOptions;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.table.api.Table;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamStatementSet;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.catalog.Catalog;
import org.apache.flink.table.types.DataType;
import org.apache.flink.table.types.logical.*;
import org.apache.flink.types.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.flink.lakesoul.entry.MongoSinkUtils.*;
import static org.apache.flink.lakesoul.tool.JobOptions.JOB_CHECKPOINT_INTERVAL;
import static org.apache.flink.lakesoul.tool.LakeSoulSinkDatabasesOptions.*;

public class SyncDatabase {

    private static final Logger LOG = LoggerFactory.getLogger(SyncDatabase.class);
    /**
     * 时间格式与 LakeSoul Flink SQL hint 一致：yyyy-MM-dd HH:mm:ss
     *
     * <p>注意：该格式校验仅用于启动参数合法性检查，不改变 LakeSoul 侧的解析逻辑。</p>
     */
    private static final DateTimeFormatter SOURCE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 以 source.* 前缀承载 LakeSoul source 查询 hint，避免与现有参数冲突
    private static final String SOURCE_READ_TYPE_KEY = "source.readtype";
    private static final String SOURCE_READ_START_TIME_KEY = "source.readstarttime";
    private static final String SOURCE_READ_END_TIME_KEY = "source.readendtime";
    private static final String SOURCE_TIMEZONE_KEY = "source.timezone";

    static String targetTableName;
    static String dbType;
    static String sourceDatabase;
    static String sourceTableName;
    static String targetDatabase;
    static String url;
    static String username;
    static String password;
    static boolean useBatch;
    static int sinkParallelism;
    static String jdbcOrDorisOptions;
    static int checkpointInterval;
    static LakeSoulInAndOutputJobListener listener;
    static String lineageUrl = null;

    /**
     * LakeSoul source 读取 hint（仅在流式出湖时生效）。
     *
     * <p>通过 Flink SQL 的 {@code /*+ OPTIONS('k'='v') *\/} 传递至 LakeSoul connector，
     * 用于控制增量读起点（readstarttime）、读类型（readtype）等。</p>
     */
    @Nullable
    static String lakesoulSourceSelectSql;

    public static void main(String[] args) throws Exception {
        StringBuilder connectorOptions = new StringBuilder();
        ParameterTool parameter = ParameterTool.fromArgs(args);
        sourceDatabase = parameter.get(SOURCE_DB_DB_NAME.key());
        sourceTableName = parameter.get(SOURCE_DB_LAKESOUL_TABLE.key()).toLowerCase();
        dbType = parameter.get(TARGET_DATABASE_TYPE.key());
        targetDatabase = parameter.get(TARGET_DB_DB_NAME.key());
        targetTableName = parameter.get(TARGET_DB_TABLE_NAME.key()).toLowerCase();
        url = parameter.get(TARGET_DB_URL.key());
        checkpointInterval = parameter.getInt(JOB_CHECKPOINT_INTERVAL.key(), JOB_CHECKPOINT_INTERVAL.defaultValue());
        if (dbType.equals("mysql") || dbType.equals("postgresql") || dbType.equals("doris")){
            for (int i = 0; i < args.length; i++) {
                if ( args[i].startsWith("--D")){
                    connectorOptions.append("'")
                            .append(args[i].substring(3))
                            .append("'")
                            .append("=")
                            .append("'")
                            .append(args[i+1])
                            .append("'")
                            .append(",");
                }
            }
            if (connectorOptions.length()>0){
                jdbcOrDorisOptions = connectorOptions.substring(0, connectorOptions.length() - 1);
            }
        }
        if (!dbType.equals("mongodb")) {
            username = parameter.get(TARGET_DB_USER.key());
            password = parameter.get(TARGET_DB_PASSWORD.key());
        }
        sinkParallelism = parameter.getInt(SINK_PARALLELISM.key(), SINK_PARALLELISM.defaultValue());
        useBatch = parameter.getBoolean(BATHC_STREAM_SINK.key(), BATHC_STREAM_SINK.defaultValue());

        // 构造 LakeSoul source 的 SELECT SQL（可选携带读取 hint），并做必要参数校验
        lakesoulSourceSelectSql = buildLakeSoulSourceSelectSql(parameter, useBatch, sourceDatabase, sourceTableName);

        Configuration conf = new Configuration();
        conf.setString(RestOptions.BIND_PORT, "8081-8089");
        StreamExecutionEnvironment env = null;
        lineageUrl = System.getenv("LINEAGE_URL");
        if (lineageUrl != null) {
            conf.set(ExecutionCheckpointingOptions.ENABLE_CHECKPOINTS_AFTER_TASKS_FINISH, true);
            conf.set(JobOptions.transportTypeOption, "http");
            conf.set(JobOptions.urlOption, lineageUrl);
            conf.set(JobOptions.execAttach, true);
            env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
            String appName = env.getConfiguration().get(JobOptions.KUBE_CLUSTER_ID);
            String namespace = System.getenv("LAKESOUL_CURRENT_DOMAIN");
            if (namespace == null) {
                namespace = "public";
            }
            if (useBatch) {
                listener = new LakeSoulInAndOutputJobListener(lineageUrl, "BATCH");
            } else {
                listener = new LakeSoulInAndOutputJobListener(lineageUrl);
            }
            listener.jobName(appName, namespace);
            env.registerJobListener(listener);
        } else {
            env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
        }
        env.setParallelism(sinkParallelism);
        switch (dbType) {
            case "mysql":
                xsyncToMysql(env);
                break;
            case "postgres":
                xsyncToPg(env);
                break;
            case "doris":
                String fenodes = parameter.get(DORIS_FENODES.key(), DORIS_FENODES.defaultValue());
                xsyncToDoris(env, fenodes);
                break;
            case "mongodb":
                String uri = parameter.get(MONGO_DB_URI.key());
                int batchSize = parameter.getInt(BATCH_SIZE.key(), BATCH_SIZE.defaultValue());
                int batchIntervalMs = parameter.getInt(BATCH_INTERVAL_MS.key(), BATCH_INTERVAL_MS.defaultValue());
                xsyncToMongodb(env, uri, batchSize, batchIntervalMs);
                break;
            default:
                throw new RuntimeException("not supported the database: " + dbType);
        }
    }

    /**
     * 构造形如 {@code select * from lakeSoul.`db`.`table` /*+ OPTIONS(...) *\/} 的查询。
     *
     * <p>兼容性策略：</p>
     * <ul>
     *   <li>批模式（useBatch=true）永远不拼接 hints；即使用户传了 source.*，也只 warn 并忽略。</li>
     *   <li>流模式（useBatch=false）且用户传入任一 source.* 时启用 hints；未指定 readtype 时默认补 incremental。</li>
     *   <li>流模式但未传任何 source.* 时保持原行为（不拼 hints）。</li>
     * </ul>
     */
    @Nullable
    private static String buildLakeSoulSourceSelectSql(
            ParameterTool parameter,
            boolean useBatch,
            String sourceDatabase,
            String sourceTableName) {
        // base：建议对 db/table 都加反引号，避免关键字/特殊字符导致 SQL 解析失败
        final String baseFrom = "lakeSoul.`" + sourceDatabase + "`.`" + sourceTableName + "`";

        final String readType = trimToEmpty(parameter.get(SOURCE_READ_TYPE_KEY, ""));
        final String readStartTime = trimToEmpty(parameter.get(SOURCE_READ_START_TIME_KEY, ""));
        final String readEndTime = trimToEmpty(parameter.get(SOURCE_READ_END_TIME_KEY, ""));
        final String timezone = trimToEmpty(parameter.get(SOURCE_TIMEZONE_KEY, ""));

        final boolean anySourceOptionProvided =
                !readType.isEmpty() || !readStartTime.isEmpty() || !readEndTime.isEmpty() || !timezone.isEmpty();

        if (useBatch) {
            if (anySourceOptionProvided) {
                LOG.warn("检测到 source.* 读取参数，但当前为批出湖(use_batch=true)，将忽略 source.*：{}={} {}={} {}={} {}={}",
                        SOURCE_READ_TYPE_KEY, readType,
                        SOURCE_READ_START_TIME_KEY, readStartTime,
                        SOURCE_READ_END_TIME_KEY, readEndTime,
                        SOURCE_TIMEZONE_KEY, timezone);
            }
            // 维持原有批模式行为
            return "select * from " + baseFrom;
        }

        // 流模式：只有当用户显式提供任一 source.* 才启用 hints，避免改变历史默认行为
        if (!anySourceOptionProvided) {
            return "select * from " + baseFrom;
        }

        // 校验 timezone（如果传入）
        if (!timezone.isEmpty() && !isValidTimeZoneId(timezone)) {
            throw new IllegalArgumentException(
                    String.format("参数 --%s=%s 非法：timezone 不存在。示例：--%s=Asia/Shanghai",
                            SOURCE_TIMEZONE_KEY, timezone, SOURCE_TIMEZONE_KEY));
        }

        // 校验时间格式（如果传入）
        if (!readStartTime.isEmpty()) {
            validateDateTime(readStartTime, SOURCE_READ_START_TIME_KEY);
        }
        if (!readEndTime.isEmpty()) {
            validateDateTime(readEndTime, SOURCE_READ_END_TIME_KEY);
        }

        // 若同时提供 start/end，则校验 start <= end
        if (!readStartTime.isEmpty() && !readEndTime.isEmpty()) {
            final LocalDateTime start = LocalDateTime.parse(readStartTime, SOURCE_TIME_FORMATTER);
            final LocalDateTime end = LocalDateTime.parse(readEndTime, SOURCE_TIME_FORMATTER);
            if (start.isAfter(end)) {
                throw new IllegalArgumentException(
                        String.format("参数 --%s=%s 与 --%s=%s 冲突：readstarttime 必须 <= readendtime",
                                SOURCE_READ_START_TIME_KEY, readStartTime,
                                SOURCE_READ_END_TIME_KEY, readEndTime));
            }
        }

        // 组装 OPTIONS。启用 hints 且用户未传 readtype 时，默认补 incremental
        final Map<String, String> options = new LinkedHashMap<>();
        final String effectiveReadType = readType.isEmpty() ? "incremental" : readType;
        options.put("readtype", effectiveReadType);
        if (!readStartTime.isEmpty()) {
            options.put("readstarttime", readStartTime);
        }
        if (!readEndTime.isEmpty()) {
            options.put("readendtime", readEndTime);
        }
        if (!timezone.isEmpty()) {
            options.put("timezone", timezone);
        }

        final String optionsHint = buildOptionsHint(options);
        LOG.info("启用 LakeSoul source 读取 hint：table={}.{}，options={}", sourceDatabase, sourceTableName, options);
        return "select * from " + baseFrom + " " + optionsHint;
    }

    private static String buildOptionsHint(Map<String, String> options) {
        // 形如：/*+ OPTIONS('k'='v','k2'='v2')*/
        // 注意：这里不做复杂转义；时间/时区等参数不应包含单引号
        StringBuilder sb = new StringBuilder("/*+ OPTIONS(");
        boolean first = true;
        for (Map.Entry<String, String> e : options.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("'").append(e.getKey()).append("'")
                    .append("=")
                    .append("'").append(e.getValue()).append("'");
        }
        sb.append(")*/");
        return sb.toString();
    }

    private static void validateDateTime(String value, String key) {
        try {
            LocalDateTime.parse(value, SOURCE_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    String.format("参数 --%s=%s 非法：时间格式必须为 yyyy-MM-dd HH:mm:ss，例如 2026-01-14 00:00:00",
                            key, value),
                    e);
        }
    }

    private static boolean isValidTimeZoneId(String timezone) {
        // 与 connector 内部使用方式保持一致：基于可用时区 ID 列表判断
        return Arrays.asList(TimeZone.getAvailableIDs()).contains(timezone);
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    public static String pgAndMsqlCreateTableSql(String[] stringFieldTypes, String[] fieldNames, String targetTableName, String pk) {
        StringBuilder createTableQuery = new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                .append(targetTableName)
                .append(" (");
        for (int i = 0; i < fieldNames.length; i++) {
            String dataType = stringFieldTypes[i];
            //String nullable = stringFieldTypes[i].contains("NULL") ? "" : " NOT NULL";
            createTableQuery.append(fieldNames[i]).append(" ").append(dataType);
            if (i != fieldNames.length - 1) {
                createTableQuery.append(", ");
            }
        }
        if (pk != null) {
            createTableQuery.append(" ,PRIMARY KEY(").append(pk);
            createTableQuery.append(")");
        }
        createTableQuery.append(")");
        return createTableQuery.toString();
    }

    public static String[] getMysqlFieldsTypes(DataType[] fieldTypes, String[] fieldNames, String pk) {
        String[] stringFieldTypes = new String[fieldTypes.length];
        for (int i = 0; i < fieldTypes.length; i++) {
            if (fieldTypes[i].getLogicalType() instanceof VarCharType) {
                String mysqlType = "TEXT";
                if (pk != null) {
                    if (pk.contains(fieldNames[i])) {
                        mysqlType = "VARCHAR(255)";
                    }
                }
                stringFieldTypes[i] = mysqlType;
            } else if (fieldTypes[i].getLogicalType() instanceof DecimalType) {
                stringFieldTypes[i] = "FLOAT";
            } else if (fieldTypes[i].getLogicalType() instanceof BinaryType) {
                stringFieldTypes[i] = "BINARY";
            } else if (fieldTypes[i].getLogicalType() instanceof LocalZonedTimestampType | fieldTypes[i].getLogicalType() instanceof TimestampType) {
                stringFieldTypes[i] = "TIMESTAMP";
            } else if (fieldTypes[i].getLogicalType() instanceof BooleanType) {
                stringFieldTypes[i] = "BOOLEAN";
            } else if (fieldTypes[i].getLogicalType() instanceof VarBinaryType) {
                stringFieldTypes[i] = "BLOB";
            } else {
                stringFieldTypes[i] = fieldTypes[i].toString();
            }
        }
        return stringFieldTypes;
    }

    public static String[] getPgFieldsTypes(DataType[] fieldTypes, String[] fieldNames, String pk) {
        String[] stringFieldTypes = new String[fieldTypes.length];

        for (int i = 0; i < fieldTypes.length; i++) {
            if (fieldTypes[i].getLogicalType() instanceof VarCharType) {
                String mysqlType = "TEXT";
                if (pk != null) {
                    if (pk.contains(fieldNames[i])) {
                        mysqlType = "VARCHAR(255)";
                    }
                }
                stringFieldTypes[i] = mysqlType;
            } else if (fieldTypes[i].getLogicalType() instanceof DoubleType) {
                stringFieldTypes[i] = "FLOAT8";
            } else if (fieldTypes[i].getLogicalType() instanceof FloatType) {
                stringFieldTypes[i] = "FLOAT4";
            } else if (fieldTypes[i].getLogicalType() instanceof BinaryType) {
                stringFieldTypes[i] = "BYTEA";
            } else if (fieldTypes[i].getLogicalType() instanceof LocalZonedTimestampType | fieldTypes[i].getLogicalType() instanceof TimestampType) {
                stringFieldTypes[i] = "TIMESTAMP";
            } else if (fieldTypes[i].getLogicalType() instanceof VarBinaryType) {
                stringFieldTypes[i] = "BYTEA";
            } else {
                stringFieldTypes[i] = fieldTypes[i].toString();
            }
        }
        return stringFieldTypes;
    }

    public static String[] getDorisFieldTypes(DataType[] fieldTypes) {
        String[] stringFieldTypes = new String[fieldTypes.length];
        for (int i = 0; i < fieldTypes.length; i++) {
            if (fieldTypes[i].getLogicalType() instanceof TimestampType) {
                stringFieldTypes[i] = "TIMESTAMP";
            } else if (fieldTypes[i].getLogicalType() instanceof VarCharType) {
                stringFieldTypes[i] = "VARCHAR";
            } else if (fieldTypes[i].getLogicalType() instanceof LocalZonedTimestampType ) {
                stringFieldTypes[i] = "TIMESTAMP";
            } else {
                stringFieldTypes[i] = fieldTypes[i].toString();
            }
        }
        return stringFieldTypes;
    }

    public static String getTablePk(String sourceDataBae, String sourceTableName) {
        DBManager dbManager = new DBManager();
        TableInfo tableInfo = dbManager.getTableInfoByNameAndNamespace(sourceTableName, sourceDataBae);
        String partitions = tableInfo.getPartitions();
        DBUtil.TablePartitionKeys keys = DBUtil.parseTableInfoPartitions(partitions);

        List<String> primaryKeys = keys.primaryKeys;
        StringBuilder stringBuilder = new StringBuilder();
        for (int i = 0; i < primaryKeys.size(); i++) {
            stringBuilder.append(primaryKeys.get(i));
            if (i < primaryKeys.size() - 1) {
                stringBuilder.append(",");
            }
        }
        return primaryKeys.size() == 0 ? null : stringBuilder.toString();
    }

    public static String getTableDomain(String sourceDataBae, String sourceTableName) {
        DBManager dbManager = new DBManager();
        TableInfo tableInfo = dbManager.getTableInfoByNameAndNamespace(sourceTableName, sourceDataBae);
        return tableInfo.getDomain();
    }

    public static void xsyncToPg(StreamExecutionEnvironment env) throws SQLException {
        if (useBatch) {
            env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        } else {
            env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            env.enableCheckpointing(checkpointInterval, CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        }
        StreamTableEnvironment tEnvs = StreamTableEnvironment.create(env);
        Catalog lakesoulCatalog = new LakeSoulCatalog();
        tEnvs.registerCatalog("lakeSoul", lakesoulCatalog);
        String jdbcUrl = url + targetDatabase;
        Connection conn = DriverManager.getConnection(jdbcUrl, username, password);

        Table lakesoulTable = tEnvs.from("`lakeSoul`.`" + sourceDatabase + "`.`" + sourceTableName + "`");
        DataType[] fieldDataTypes = lakesoulTable.getSchema().getFieldDataTypes();
        String[] fieldNames = lakesoulTable.getSchema().getFieldNames();
        String tablePk = getTablePk(sourceDatabase, sourceTableName);
        String[] stringFieldsTypes = getPgFieldsTypes(fieldDataTypes, fieldNames, tablePk);

        String createTableSql = pgAndMsqlCreateTableSql(stringFieldsTypes, fieldNames, targetTableName, tablePk);
        Statement statement = conn.createStatement();
        statement.executeUpdate(createTableSql.toString());
        StringBuilder coulmns = new StringBuilder();
        for (int i = 0; i < fieldDataTypes.length; i++) {
            switch (stringFieldsTypes[i]) {
                case "BYTEA":
                    coulmns.append("`").append(fieldNames[i]).append("` ").append("BYTES");
                    break;
                case "TEXT":
                    coulmns.append("`").append(fieldNames[i]).append("` ").append("VARCHAR");
                    break;
                case "FLOAT8":
                    coulmns.append("`").append(fieldNames[i]).append("` ").append("DOUBLE");
                    break;
                default:
                    coulmns.append("`").append(fieldNames[i]).append("` ").append(stringFieldsTypes[i]);
                    break;
            }
            if (i < fieldDataTypes.length - 1) {
                coulmns.append(",");
            }
        }
        String sql;
        if (jdbcOrDorisOptions==null){
            if (tablePk != null) {
                sql = String.format(
                        "create table %s(%s ,PRIMARY KEY (%s) NOT ENFORCED) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s')",
                        targetTableName, coulmns, tablePk, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism);
            } else {
                sql = String.format("create table %s(%s) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s')",
                        targetTableName, coulmns, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism);
            }
        }else {
            if (tablePk != null) {
                sql = String.format(
                        "create table %s(%s ,PRIMARY KEY (%s) NOT ENFORCED) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s', %s)",
                        targetTableName, coulmns, tablePk, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism, jdbcOrDorisOptions);
            } else {
                sql = String.format("create table %s(%s) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s', %s)",
                        targetTableName, coulmns, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism, jdbcOrDorisOptions);
            }
        }
        tEnvs.executeSql(sql);
        // 统一通过 lakesoulSourceSelectSql 进行 source 读取（可选带 hints）
        tEnvs.executeSql("insert into " + targetTableName + " " + lakesoulSourceSelectSql);
        statement.close();
        conn.close();
    }


    public static void xsyncToMysql(StreamExecutionEnvironment env) throws SQLException {
        if (useBatch) {
            env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        } else {
            env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            env.enableCheckpointing(checkpointInterval, CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        }
        StreamTableEnvironment tEnvs = StreamTableEnvironment.create(env);
        Catalog lakesoulCatalog = new LakeSoulCatalog();
        tEnvs.registerCatalog("lakeSoul", lakesoulCatalog);
        String jdbcUrl = url + targetDatabase;
        Table lakesoulTable = tEnvs.from("`lakeSoul`.`" + sourceDatabase + "`.`" + sourceTableName + "`");
        DataType[] fieldDataTypes = lakesoulTable.getSchema().getFieldDataTypes();
        String[] fieldNames = lakesoulTable.getSchema().getFieldNames();
        String tablePk = getTablePk(sourceDatabase, sourceTableName);
        String[] stringFieldsTypes = getMysqlFieldsTypes(fieldDataTypes, fieldNames, tablePk);
        String createTableSql = pgAndMsqlCreateTableSql(stringFieldsTypes, fieldNames, targetTableName, tablePk);

        Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
        Statement statement = conn.createStatement();
        // Create the target table in MySQL
        statement.executeUpdate(createTableSql.toString());
        StringBuilder coulmns = new StringBuilder();
        for (int i = 0; i < fieldDataTypes.length; i++) {
            if (stringFieldsTypes[i].equals("BLOB")) {
                coulmns.append("`").append(fieldNames[i]).append("` ").append("BYTES");
            } else if (stringFieldsTypes[i].equals("TEXT")) {
                coulmns.append("`").append(fieldNames[i]).append("` ").append("VARCHAR");

            } else {
                coulmns.append("`").append(fieldNames[i]).append("` ").append(stringFieldsTypes[i]);
            }
            if (i < fieldDataTypes.length - 1) {
                coulmns.append(",");
            }
        }
        String sql;
        if (jdbcOrDorisOptions==null){
            if (tablePk != null) {
                sql = String.format(
                        "create table %s(%s ,PRIMARY KEY (%s) NOT ENFORCED) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s')",
                        targetTableName, coulmns, tablePk, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism);
            } else {
                sql = String.format("create table %s(%s) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s')",
                        targetTableName, coulmns, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism);
            }
        }else {
            if (tablePk != null) {
                sql = String.format(
                        "create table %s(%s ,PRIMARY KEY (%s) NOT ENFORCED) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s', %s)",
                        targetTableName, coulmns, tablePk, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism, jdbcOrDorisOptions);
            } else {
                sql = String.format("create table %s(%s) with ('connector' = '%s', 'url' = '%s', 'table-name' = '%s', 'username' = '%s', 'password' = '%s', 'sink.parallelism' = '%s', %s)",
                        targetTableName, coulmns, "jdbc", jdbcUrl, targetTableName, username, password, sinkParallelism, jdbcOrDorisOptions);
            }
        }

        tEnvs.executeSql(sql);
        // 统一通过 lakesoulSourceSelectSql 进行 source 读取（可选带 hints）
        tEnvs.executeSql("insert into " + targetTableName + " " + lakesoulSourceSelectSql);

        statement.close();
        conn.close();
    }

    public static void xsyncToDoris(StreamExecutionEnvironment env, String fenodes) throws Exception {
        if (useBatch) {
            env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        } else {
            env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            env.enableCheckpointing(checkpointInterval, CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        }
        StreamTableEnvironment tEnvs = StreamTableEnvironment.create(env);
        Catalog lakesoulCatalog = new LakeSoulCatalog();
        tEnvs.registerCatalog("lakeSoul", lakesoulCatalog);
        String jdbcUrl = url + targetDatabase;
        Table lakesoulTable = tEnvs.from("`lakeSoul`.`" + sourceDatabase + "`.`" + sourceTableName + "`");
        DataType[] fieldDataTypes = lakesoulTable.getSchema().getFieldDataTypes();
        String[] fieldNames = lakesoulTable.getSchema().getFieldNames();
        String[] dorisFieldTypes = getDorisFieldTypes(fieldDataTypes);
        if (lineageUrl != null) {
            String inputName = "lakeSoul." + sourceDatabase + "." + sourceTableName;
            String inputnNamespace = getTableDomain(sourceDatabase,sourceTableName);
            String[] inputTypes = Arrays.stream(fieldDataTypes).map(type -> type.toString()).collect(Collectors.toList()).toArray(new String[0]);
            listener.inputFacets(inputName,inputnNamespace,fieldNames,inputTypes);
            String targetName = "doris." + targetDatabase + "." + targetTableName;
            listener.outputFacets(targetName,"lake-public",fieldNames,dorisFieldTypes);
        }
        StringBuilder coulmns = new StringBuilder();
        for (int i = 0; i < fieldDataTypes.length; i++) {
            coulmns.append("`").append(fieldNames[i]).append("` ").append(dorisFieldTypes[i]);
            if (i < fieldDataTypes.length - 1) {
                coulmns.append(",");
            }
        }
        String sql;
        if (jdbcOrDorisOptions == null){
            sql = String.format(
                    "create table %s(%s) with ('connector' = '%s'," +
                            " 'jdbc-url' = '%s'," +
                            " 'fenodes' = '%s'," +
                            " 'table.identifier' = '%s'," +
                            " 'username' = '%s'," +
                            " 'password' = '%s'," +
                            " 'sink.properties.format' = 'json'," +
                            " 'sink.properties.read_json_by_line' = 'true')",
                    targetTableName, coulmns, "doris", jdbcUrl, fenodes, targetDatabase + "." + targetTableName, username, password);
        }else {
            sql = String.format(
                    "create table %s(%s) with ('connector' = '%s'," +
                            " 'jdbc-url' = '%s'," +
                            " 'fenodes' = '%s'," +
                            " 'table.identifier' = '%s'," +
                            " 'username' = '%s'," +
                            " 'password' = '%s'," +
                            " 'sink.properties.format' = 'json'," +
                            " 'sink.properties.read_json_by_line' = 'true'," +
                            "  %s)",
                    targetTableName, coulmns, "doris", jdbcUrl, fenodes, targetDatabase + "." + targetTableName, username, password, jdbcOrDorisOptions);
        }

        tEnvs.executeSql(sql);
        if (lineageUrl != null){
            String insertsql = "insert into " + targetTableName + " select * from lakeSoul.`" + sourceDatabase + "`." + sourceTableName;
            StreamStatementSet statements =  tEnvs.createStatementSet();
            statements.addInsertSql(insertsql);
            statements.attachAsDataStream();
            env.execute();
        }else{
            tEnvs.executeSql("insert into " + targetTableName + " select * from lakeSoul.`" + sourceDatabase + "`." + sourceTableName);

        }
    }

    public static void xsyncToMongodb(StreamExecutionEnvironment env,
                                      String uri,
                                      int batchSize,
                                      int batchInservalMs) throws Exception {
        createMongoColl(targetDatabase, targetTableName, uri);
        if (useBatch) {
            env.setRuntimeMode(RuntimeExecutionMode.BATCH);
        } else {
            env.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            env.enableCheckpointing(checkpointInterval, CheckpointingMode.EXACTLY_ONCE);
            env.getCheckpointConfig().setExternalizedCheckpointCleanup(CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
        }
        StreamTableEnvironment tEnvs = StreamTableEnvironment.create(env);
        Catalog lakesoulCatalog = new LakeSoulCatalog();
        tEnvs.registerCatalog("lakeSoul", lakesoulCatalog);
        coll = tEnvs.sqlQuery("select * from lakeSoul.`" + sourceDatabase + "`.`" + sourceTableName + "`");
        tEnvs.registerTable("mongodbTbl", coll);
        Table table = tEnvs.sqlQuery("select * from mongodbTbl");
        DataStream<Tuple2<Boolean, Row>> rowDataStream = tEnvs.toRetractStream(table, Row.class);
        MongoSink<Tuple2<Boolean, Row>> sink = MongoSink.<Tuple2<Boolean, Row>>builder()
                .setUri(uri)
                .setDatabase(targetDatabase)
                .setCollection(targetTableName)
                .setBatchSize(batchSize)
                .setBatchIntervalMs(batchInservalMs)
                .setMaxRetries(3)
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .setSerializationSchema(new MyMongoSerializationSchema())
                .build();
        rowDataStream.sinkTo(sink).setParallelism(sinkParallelism);
        env.execute();
    }
}
