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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.apache.flink.lakesoul.tool.JobOptions.FLINK_CHECKPOINT;
import static org.apache.flink.lakesoul.tool.JobOptions.JOB_CHECKPOINT_INTERVAL;
import static org.apache.flink.lakesoul.tool.JobOptions.JOB_CHECKPOINT_MODE;
import static org.apache.flink.lakesoul.tool.LakeSoulDDLSinkOptions.*;

/**
 * @deprecated Use {@link JdbcCDC} with {@code --source_db.db_type mysql}.
 *             This entry will be kept for backward compatibility, but new jobs
 *             should use {@link JdbcCDC}.
 */
@Deprecated
public class MysqlCdc {

        public static void main(String[] args) throws Exception {
                ParameterTool parameter = ParameterTool.fromArgs(args);

                String dbName = parameter.get(SOURCE_DB_DB_NAME.key());
                String userName = parameter.get(SOURCE_DB_USER.key());
                String passWord = parameter.get(SOURCE_DB_PASSWORD.key());
                String host = parameter.get(SOURCE_DB_HOST.key());
                int port = parameter.getInt(SOURCE_DB_PORT.key(), MysqlDBManager.DEFAULT_MYSQL_PORT);
                String databasePrefixPath = parameter.get(WAREHOUSE_PATH.key());
                String serverTimezone = parameter.get(SERVER_TIME_ZONE.key(), SERVER_TIME_ZONE.defaultValue());
                int sourceParallelism = parameter.getInt(SOURCE_PARALLELISM.key());
                int bucketParallelism = parameter.getInt(BUCKET_PARALLELISM.key());
                int checkpointInterval = parameter.getInt(JOB_CHECKPOINT_INTERVAL.key(),
                                JOB_CHECKPOINT_INTERVAL.defaultValue()); // mill second

                // whitelist/blacklist (no regex; comma-separated)
                HashSet<String> excludeTables = parseTableNameSet(parameter.get(SOURCE_DB_EXCLUDE_TABLES.key(), ""));
                HashSet<String> includeTables = parseTableNameSet(parameter.get(SOURCE_DB_INCLUDE_TABLES.key(), ""));
                // Always filter internal tables
                excludeTables.add("sys_config");

                MysqlDBManager mysqlDBManager = new MysqlDBManager(dbName,
                                userName,
                                passWord,
                                host,
                                Integer.toString(port),
                                excludeTables,
                                includeTables,
                                databasePrefixPath,
                                bucketParallelism,
                                true);

                Configuration conf = new Configuration();

                // parameters for mutil tables ddl sink
                conf.set(SOURCE_DB_DB_NAME, dbName);
                conf.set(SOURCE_DB_USER, userName);
                conf.set(SOURCE_DB_PASSWORD, passWord);
                conf.set(SOURCE_DB_HOST, host);
                conf.set(SOURCE_DB_PORT, port);
                conf.set(WAREHOUSE_PATH, databasePrefixPath);
                conf.set(SERVER_TIME_ZONE, serverTimezone);
                conf.set(SOURCE_DB_EXCLUDE_TABLES, parameter.get(SOURCE_DB_EXCLUDE_TABLES.key(), ""));
                conf.set(SOURCE_DB_INCLUDE_TABLES, parameter.get(SOURCE_DB_INCLUDE_TABLES.key(), ""));

                // parameters for mutil tables dml sink
                conf.set(LakeSoulSinkOptions.USE_CDC, true);
                conf.set(LakeSoulSinkOptions.isMultiTableSource, true);
                conf.set(LakeSoulSinkOptions.WAREHOUSE_PATH, databasePrefixPath);
                conf.set(LakeSoulSinkOptions.SOURCE_PARALLELISM, sourceParallelism);
                conf.set(LakeSoulSinkOptions.BUCKET_PARALLELISM, bucketParallelism);
                conf.set(LakeSoulSinkOptions.HASH_BUCKET_NUM, bucketParallelism);
                conf.set(ExecutionCheckpointingOptions.ENABLE_CHECKPOINTS_AFTER_TASKS_FINISH, true);

                // naming rules for target namespace / table name
                // - keep unified option names (naming.*)
                // - if user sets naming.target_namespace or naming.table_format without
                // naming.enable,
                // auto-enable naming to make them effective
                boolean hasNamingEnable = parameter.has(LakeSoulSinkOptions.NAMING_ENABLE.key());
                boolean hasNamingDetails = parameter.has(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE.key())
                                || parameter.has(LakeSoulSinkOptions.NAMING_TABLE_FORMAT.key())
                                || parameter.has(LakeSoulSinkOptions.NAMING_CASE.key());
                if (hasNamingEnable) {
                        conf.set(LakeSoulSinkOptions.NAMING_ENABLE,
                                        Boolean.parseBoolean(parameter.get(LakeSoulSinkOptions.NAMING_ENABLE.key())));
                } else if (hasNamingDetails) {
                        conf.set(LakeSoulSinkOptions.NAMING_ENABLE, true);
                }
                if (parameter.has(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE.key())) {
                        conf.set(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE,
                                        parameter.get(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE.key()));
                }
                if (parameter.has(LakeSoulSinkOptions.NAMING_TABLE_FORMAT.key())) {
                        conf.set(LakeSoulSinkOptions.NAMING_TABLE_FORMAT,
                                        parameter.get(LakeSoulSinkOptions.NAMING_TABLE_FORMAT.key()));
                }
                if (parameter.has(LakeSoulSinkOptions.NAMING_CASE.key())) {
                        conf.set(LakeSoulSinkOptions.NAMING_CASE,
                                        parameter.get(LakeSoulSinkOptions.NAMING_CASE.key()));
                }

                // ensure target namespace exists in LakeSoul
                String targetNamespace = dbName;
                if (conf.getBoolean(LakeSoulSinkOptions.NAMING_ENABLE)
                                && conf.get(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE) != null
                                && !conf.get(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE).isEmpty()) {
                        targetNamespace = conf.get(LakeSoulSinkOptions.NAMING_TARGET_NAMESPACE);
                }
                mysqlDBManager.importOrSyncLakeSoulNamespace(targetNamespace);

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

                String[] capturedTables = buildCapturedTableList(
                                dbName,
                                host,
                                port,
                                userName,
                                passWord,
                                includeTables,
                                excludeTables);

                MySqlSourceBuilder<BinarySourceRecord> sourceBuilder = MySqlSource.<BinarySourceRecord>builder()
                                .hostname(host)
                                .port(port)
                                .databaseList(dbName) // set captured database
                                .tableList(capturedTables) // set captured table (explicit list; no regex from params)
                                .serverTimeZone(serverTimezone) // default -- Asia/Shanghai
                                // .scanNewlyAddedTableEnabled(true)
                                .username(userName)
                                .password(passWord);

                LakeSoulRecordConvert lakeSoulRecordConvert = new LakeSoulRecordConvert(conf,
                                conf.getString(SERVER_TIME_ZONE));
                sourceBuilder.deserializer(new BinaryDebeziumDeserializationSchema(lakeSoulRecordConvert,
                                conf.getString(WAREHOUSE_PATH), conf));
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

        /**
         * Parse comma-separated table identifiers into a set of "table" names (no
         * schema/db),
         * supporting both "table" and "db.table".
         */
        private static HashSet<String> parseTableNameSet(String raw) {
                HashSet<String> set = new HashSet<>();
                if (raw == null) {
                        return set;
                }
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                        return set;
                }
                String[] parts = trimmed.split(",");
                for (String p : parts) {
                        if (p == null) {
                                continue;
                        }
                        String s = p.trim();
                        if (s.isEmpty()) {
                                continue;
                        }
                        int dot = s.lastIndexOf('.');
                        if (dot >= 0 && dot + 1 < s.length()) {
                                s = s.substring(dot + 1);
                        }
                        if (!s.isEmpty()) {
                                set.add(s);
                        }
                }
                return set;
        }

        /**
         * Build the captured table list for MySQL CDC.
         * - If includeTables is non-empty: only capture those tables (minus
         * excludeTables if provided).
         * - Else: enumerate all base tables in the database, then exclude
         * excludeTables.
         *
         * Returned entries are qualified as "db.table".
         */
        private static String[] buildCapturedTableList(
                        String dbName,
                        String host,
                        int port,
                        String userName,
                        String passWord,
                        Set<String> includeTables,
                        Set<String> excludeTables) {
                LinkedHashSet<String> finalTables = new LinkedHashSet<>();

                if (includeTables != null && !includeTables.isEmpty()) {
                        for (String t : includeTables) {
                                if (t == null) {
                                        continue;
                                }
                                String table = t.trim();
                                if (table.isEmpty()) {
                                        continue;
                                }
                                if (excludeTables != null && excludeTables.contains(table)) {
                                        continue;
                                }
                                finalTables.add(dbName + "." + table);
                        }
                } else {
                        List<String> allTables = listAllBaseTables(dbName, host, port, userName, passWord);
                        for (String table : allTables) {
                                if (excludeTables != null && excludeTables.contains(table)) {
                                        continue;
                                }
                                finalTables.add(dbName + "." + table);
                        }
                }

                if (finalTables.isEmpty()) {
                        throw new IllegalArgumentException(
                                        "No tables to capture after applying include/exclude. "
                                                        + "include_tables="
                                                        + (includeTables == null ? "" : includeTables)
                                                        + ", exclude_tables="
                                                        + (excludeTables == null ? "" : excludeTables));
                }

                return finalTables.toArray(new String[0]);
        }

        /**
         * List all base tables in a MySQL database (table names only; no columns/PKs)
         * for thousand-table scale.
         */
        private static List<String> listAllBaseTables(
                        String dbName,
                        String host,
                        int port,
                        String userName,
                        String passWord) {
                String jdbcUrl = String.format(
                                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true",
                                host,
                                port,
                                dbName);
                List<String> tables = new ArrayList<>();
                try (Connection conn = DriverManager.getConnection(jdbcUrl, userName, passWord)) {
                        DatabaseMetaData meta = conn.getMetaData();
                        try (ResultSet rs = meta.getTables(dbName, null, null, new String[] { "TABLE" })) {
                                while (rs.next()) {
                                        String tableName = rs.getString("TABLE_NAME");
                                        if (tableName != null && !tableName.trim().isEmpty()) {
                                                tables.add(tableName);
                                        }
                                }
                        }
                } catch (SQLException e) {
                        throw new RuntimeException("Failed to list MySQL tables via JDBC url=" + jdbcUrl, e);
                }
                if (tables.isEmpty()) {
                        throw new IllegalArgumentException(
                                        "No base tables found in MySQL database " + dbName + " from " + host + ":"
                                                        + port);
                }
                return tables;
        }
}
