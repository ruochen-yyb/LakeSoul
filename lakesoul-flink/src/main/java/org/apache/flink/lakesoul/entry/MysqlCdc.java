// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.flink.lakesoul.entry;

import com.dmetasoul.lakesoul.meta.external.mysql.MysqlDBManager;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.source.MySqlSourceBuilder;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.GlobalConfiguration;
import org.apache.flink.lakesoul.sink.LakeSoulMultiTableSinkStreamBuilder;
import org.apache.flink.lakesoul.tool.LakeSoulSinkOptions;
import org.apache.flink.lakesoul.types.BinaryDebeziumDeserializationSchema;
import org.apache.flink.lakesoul.types.BinarySourceRecord;
import org.apache.flink.lakesoul.types.BinarySourceRecordSerializer;
import org.apache.flink.lakesoul.types.LakeSoulRecordConvert;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.apache.flink.lakesoul.tool.JobOptions.FLINK_CHECKPOINT;
import static org.apache.flink.lakesoul.tool.JobOptions.JOB_CHECKPOINT_INTERVAL;
import static org.apache.flink.lakesoul.tool.JobOptions.JOB_CHECKPOINT_MODE;
import static org.apache.flink.lakesoul.tool.LakeSoulDDLSinkOptions.*;

public class MysqlCdc {

        public static void main(String[] args) throws Exception {
                ParameterTool parameter = ParameterTool.fromArgs(args);

                String dbName = parameter.get(SOURCE_DB_DB_NAME.key());
                String userName = parameter.get(SOURCE_DB_USER.key());
                String passWord = parameter.get(SOURCE_DB_PASSWORD.key());
                String host = parameter.get(SOURCE_DB_HOST.key());
                int port = parameter.getInt(SOURCE_DB_PORT.key(), MysqlDBManager.DEFAULT_MYSQL_PORT);
                String sinkDBName = parameter.get(SINK_DBNAME.key(), SINK_DBNAME.defaultValue());
                String sinkTablePrefix = parameter.get(SINK_TABLE_PREFIX.key(), "s");
                String databasePrefixPath = parameter.get(WAREHOUSE_PATH.key());
                String serverTimezone = parameter.get(SERVER_TIME_ZONE.key(), SERVER_TIME_ZONE.defaultValue());
                int sourceParallelism = parameter.getInt(SOURCE_PARALLELISM.key());
                int bucketParallelism = parameter.getInt(BUCKET_PARALLELISM.key());
                int checkpointInterval = parameter.getInt(JOB_CHECKPOINT_INTERVAL.key(),
                                JOB_CHECKPOINT_INTERVAL.defaultValue()); // mill second

                String tableListStr = parameter.get(SOURCE_DB_TABLE_LIST.key(), null);
                String excludeTablesStr = parameter.get(SOURCE_DB_EXCLUDE_TABLES.key(), null);

                boolean hasTableList = tableListStr != null && !tableListStr.trim().isEmpty();
                boolean hasExcludeTables = excludeTablesStr != null && !excludeTablesStr.trim().isEmpty();
                if (hasTableList && hasExcludeTables) {
                        throw new IllegalArgumentException(
                                        "参数冲突：`source_db.table.list`（白名单）与 `source_db.exclude_tables`（黑名单）不可同时配置，请分开使用");
                }

                MysqlDBManager mysqlDBManager = new MysqlDBManager(dbName,
                                userName,
                                passWord,
                                host,
                                Integer.toString(port),
                                new HashSet<>(),
                                databasePrefixPath,
                                bucketParallelism,
                                true);

                String targetNamespace = sinkDBName != null && !sinkDBName.trim().isEmpty() ? sinkDBName : dbName;
                mysqlDBManager.importOrSyncLakeSoulNamespace(targetNamespace);
                Configuration globalConfig = GlobalConfiguration.loadConfiguration();
                String warehousePath = databasePrefixPath == null ? globalConfig.getString(WAREHOUSE_PATH.key(), null)
                                : databasePrefixPath;
                Configuration conf = new Configuration();

                // parameters for mutil tables ddl sink
                conf.set(SOURCE_DB_DB_NAME, dbName);
                conf.set(SOURCE_DB_USER, userName);
                conf.set(SOURCE_DB_PASSWORD, passWord);
                conf.set(SOURCE_DB_HOST, host);
                conf.set(SOURCE_DB_PORT, port);
                conf.set(WAREHOUSE_PATH, warehousePath);
                conf.set(SERVER_TIME_ZONE, serverTimezone);
                if (tableListStr != null) {
                        conf.set(SOURCE_DB_TABLE_LIST, tableListStr);
                }
                if (excludeTablesStr != null) {
                        conf.set(SOURCE_DB_EXCLUDE_TABLES, excludeTablesStr);
                }
                conf.set(SINK_TABLE_PREFIX, sinkTablePrefix);

                // parameters for mutil tables dml sink
                conf.set(LakeSoulSinkOptions.USE_CDC, true);
                conf.set(LakeSoulSinkOptions.isMultiTableSource, true);
                conf.set(LakeSoulSinkOptions.WAREHOUSE_PATH, warehousePath);
                conf.set(LakeSoulSinkOptions.SOURCE_PARALLELISM, sourceParallelism);
                conf.set(LakeSoulSinkOptions.BUCKET_PARALLELISM, bucketParallelism);
                conf.set(LakeSoulSinkOptions.HASH_BUCKET_NUM, bucketParallelism);
                conf.set(ExecutionCheckpointingOptions.ENABLE_CHECKPOINTS_AFTER_TASKS_FINISH, true);

                StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(conf);
                env.getConfig().registerTypeWithKryoSerializer(BinarySourceRecord.class,
                                BinarySourceRecordSerializer.class);

                ParameterTool pt = ParameterTool.fromMap(conf.toMap());
                env.getConfig().setGlobalJobParameters(pt);

                env.enableCheckpointing(checkpointInterval);
                env.getCheckpointConfig().setMinPauseBetweenCheckpoints(4023);

                CheckpointingMode checkpointingMode = CheckpointingMode.EXACTLY_ONCE;
                if (parameter.get(JOB_CHECKPOINT_MODE.key(), JOB_CHECKPOINT_MODE.defaultValue())
                                .equals("AT_LEAST_ONCE")) {
                        checkpointingMode = CheckpointingMode.AT_LEAST_ONCE;
                }
                env.getCheckpointConfig().setTolerableCheckpointFailureNumber(5);
                env.getCheckpointConfig().setCheckpointingMode(checkpointingMode);
                env.getCheckpointConfig()
                                .setExternalizedCheckpointCleanup(
                                                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);

                env.getCheckpointConfig().setCheckpointStorage(parameter.get(FLINK_CHECKPOINT.key()));
                env.setRestartStrategy(RestartStrategies.failureRateRestart(
                                3, // max failures per interval
                                Time.of(10, TimeUnit.MINUTES), // time interval for measuring failure rate
                                Time.of(20, TimeUnit.SECONDS) // delay
                ));

                String[] tableList;
                if (hasExcludeTables) {
                        tableList = buildIncludedTableListByBlacklist(dbName, host, port, userName, passWord,
                                        excludeTablesStr);
                } else if (!hasTableList) {
                        tableList = new String[] { dbName + ".*" };
                } else {
                        tableList = Arrays.stream(tableListStr.split(","))
                                        .map(String::trim)
                                        .filter(s -> !s.isEmpty())
                                        .map(t -> t.contains(".") ? t : dbName + "." + t)
                                        .toArray(String[]::new);
                        if (tableList.length == 0) {
                                tableList = new String[] { dbName + ".*" };
                        }
                }

                MySqlSourceBuilder<BinarySourceRecord> sourceBuilder = MySqlSource.<BinarySourceRecord>builder()
                                .hostname(host)
                                .port(port)
                                .databaseList(dbName) // set captured database
                                .tableList(tableList) // set captured table(s)
                                .serverTimeZone(serverTimezone) // default -- Asia/Shanghai
                                // .scanNewlyAddedTableEnabled(true)
                                .username(userName)
                                .password(passWord);

                LakeSoulRecordConvert lakeSoulRecordConvert = new LakeSoulRecordConvert(conf,
                                conf.getString(SERVER_TIME_ZONE));
                sourceBuilder.deserializer(new BinaryDebeziumDeserializationSchema(lakeSoulRecordConvert,
                                conf.getString(WAREHOUSE_PATH), sinkDBName, sinkTablePrefix));
                Properties jdbcProperties = new Properties();
                jdbcProperties.put("allowPublicKeyRetrieval", "true");
                jdbcProperties.put("useSSL", "false");
                sourceBuilder.jdbcProperties(jdbcProperties);
                MySqlSource<BinarySourceRecord> mySqlSource = sourceBuilder.build();

                LakeSoulMultiTableSinkStreamBuilder.Context context = new LakeSoulMultiTableSinkStreamBuilder.Context();
                context.env = env;
                context.conf = (Configuration) env.getConfiguration();
                LakeSoulMultiTableSinkStreamBuilder builder = new LakeSoulMultiTableSinkStreamBuilder(mySqlSource,
                                context, lakeSoulRecordConvert);
                DataStreamSource<BinarySourceRecord> source = builder.buildMultiTableSource("MySQL Source");

                DataStream<BinarySourceRecord> stream = builder.buildHashPartitionedCDCStream(source);
                builder.buildLakeSoulDMLSink(stream);
                env.execute("LakeSoul CDC Sink From MySQL Database " + dbName);
        }

        private static String[] buildIncludedTableListByBlacklist(
                        String dbName,
                        String host,
                        int port,
                        String user,
                        String password,
                        String excludeTablesStr) {
                List<Pattern> excludePatterns = parseExcludeTablePatterns(dbName, excludeTablesStr);
                excludePatterns.add(Pattern.compile("sys_config"));

                List<String> included = new ArrayList<>();
                Properties props = new Properties();
                props.put("user", user);
                props.put("password", password);
                props.put("useSSL", "false");
                props.put("allowPublicKeyRetrieval", "true");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                                + "?useSSL=false&allowPublicKeyRetrieval=true";
                try (Connection connection = DriverManager.getConnection(url, props)) {
                        DatabaseMetaData dmd = connection.getMetaData();
                        try (ResultSet tables = dmd.getTables(null, null, null, new String[] { "TABLE" })) {
                                while (tables.next()) {
                                        String tableCat = tables.getString("TABLE_CAT");
                                        if (tableCat != null && !dbName.equalsIgnoreCase(tableCat)) {
                                                continue;
                                        }
                                        String tableName = tables.getString("TABLE_NAME");
                                        if (tableName == null || tableName.trim().isEmpty()) {
                                                continue;
                                        }
                                        if (isExcluded(excludePatterns, tableName)) {
                                                continue;
                                        }
                                        included.add(dbName + "." + tableName);
                                }
                        }
                } catch (SQLException e) {
                        throw new RuntimeException(
                                        "黑名单模式下枚举 MySQL 表失败，请确认账号有读取元数据权限（information_schema/SHOW TABLES）："
                                                        + dbName,
                                        e);
                }

                if (included.isEmpty()) {
                        throw new IllegalArgumentException("黑名单过滤后无可捕获表，请检查 `source_db.exclude_tables`："
                                        + excludeTablesStr);
                }
                return included.toArray(String[]::new);
        }

        private static boolean isExcluded(List<Pattern> excludePatterns, String tableName) {
                for (Pattern p : excludePatterns) {
                        if (p.matcher(tableName).matches()) {
                                return true;
                        }
                }
                return false;
        }

        private static List<Pattern> parseExcludeTablePatterns(String dbName, String excludeTablesStr) {
                List<Pattern> patterns = new ArrayList<>();
                if (excludeTablesStr == null || excludeTablesStr.trim().isEmpty()) {
                        return patterns;
                }
                for (String raw : excludeTablesStr.split(",")) {
                        String t = raw == null ? "" : raw.trim();
                        if (t.isEmpty()) {
                                continue;
                        }
                        if (t.contains(".")) {
                                String[] parts = t.split("\\.", 2);
                                String db = parts[0];
                                String tblPattern = parts.length == 2 ? parts[1] : "";
                                if (!dbName.equalsIgnoreCase(db) && !"*".equals(db) && !".*".equals(db)) {
                                        continue;
                                }
                                t = tblPattern;
                        }
                        if (!t.isEmpty()) {
                                patterns.add(Pattern.compile(t));
                        }
                }
                return patterns;
        }
}
