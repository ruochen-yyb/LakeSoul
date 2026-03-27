// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0
package org.apache.flink.lakesoul.entry.clean;

import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.core.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CleanUtils {

    private static final Logger logger = LoggerFactory.getLogger(CleanUtils.class);
    private final Map<String, Boolean> storageReachableCache = new HashMap<>();

    public enum FailureType {
        NONE,
        STORAGE_UNREACHABLE,
        QUERY_FILE_OPS_FAILED,
        FILE_DELETE_FAILED,
        TRANSACTION_SETUP_FAILED,
        DELETE_DATA_COMMIT_INFO_FAILED,
        DELETE_PARTITION_INFO_FAILED,
        METADATA_ROLLBACK_FAILED,
        CONNECTION_STATE_RESTORE_FAILED
    }

    public static final class CleanupResult {
        private final boolean success;
        private final FailureType failureType;
        private final String message;

        private CleanupResult(boolean success, FailureType failureType, String message) {
            this.success = success;
            this.failureType = failureType;
            this.message = message;
        }

        public static CleanupResult success() {
            return new CleanupResult(true, FailureType.NONE, "success");
        }

        public static CleanupResult failure(FailureType failureType, String message) {
            return new CleanupResult(false, failureType, message);
        }

        public boolean isSuccess() {
            return success;
        }

        public FailureType getFailureType() {
            return failureType;
        }

        public String getMessage() {
            return message;
        }
    }

    private static final class StorageUnreachableException extends IOException {
        private StorageUnreachableException(String message) {
            super(message);
        }
    }

    private static final class DiscardFileRecord {
        private final long timestamp;
        private final String filePath;

        private DiscardFileRecord(long timestamp, String filePath) {
            this.timestamp = timestamp;
            this.filePath = filePath;
        }
    }

    public void write(String record) {
        String filePath = "./record.txt";
        try (FileWriter writer = new FileWriter(filePath, true)) {
            writer.write(record + "\n"); // 将内容写入文件
            System.out.println("内容已成功写入文件: " + filePath);
        } catch (IOException e) {
            System.err.println("写入文件时发生错误: " + e.getMessage());
        }
    }

    //实现从pg里删除记录
    public void deleteDataCommitInfo(String table_id, String commit_id, String partition_desc) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/lakesoul_test", "lakesoul_test", "lakesoul_test");
        String sql = "DELETE FROM data_commit_info where table_id= '" + table_id +
                "' and commit_id= '" + commit_id +
                "' and partition_desc ='" + partition_desc + "'";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            // 执行删除操作
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            // 处理SQL异常
            e.printStackTrace();
            logger.info("删除data_commit_info数据异常");
        }
    }

    public boolean partitionExist(String tableId, String partitionDesc, Connection connection) {
        String sql = "SELECT 1 FROM partition_info WHERE table_id = ? AND partition_desc = ? LIMIT 1";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tableId);
            ps.setString(2, partitionDesc);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }


    public void deletePartitionInfo(String table_id, String partition_desc, String commit_id) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost:5432/lakesoul_test", "lakesoul_test", "lakesoul_test");
        String sql = "DELETE FROM partition_info where table_id= '" + table_id +
                "' and partition_desc ='" + partition_desc + "' and '" + commit_id + "' = ANY(snapshot)";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            logger.info("删除partition_info数据异常");
        }
    }

    public boolean cleanPartitionInfo(String table_id, String partition_desc, int version, Connection connection) {
        UUID id = UUID.randomUUID();
        logger.info("[Clean-{}]: begin", id);
        String sql = "DELETE FROM partition_info where table_id= '" + table_id +
                "' and partition_desc ='" + partition_desc + "' and version = '" + version + "'";
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.executeUpdate();
            logger.info(sql);
            logger.info("[Clean-{}]: success",id);
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            logger.info("删除partition_info数据异常");
            logger.info("[Clean-{}]: fail",id);
            return false;
        }
    }

    /**
     * Delete files via Flink {@link FileSystem} so the job can reuse Flink cluster's filesystem plugins/config
     * (e.g. flink-s3-fs-hadoop + s3.* for Ceph/S3-compatible storage).
     *
     * @return true if all deletions succeed (or files do not exist); false otherwise.
     */
    public boolean deleteFile(List<String> filePathList) {
        return deleteFiles(filePathList).isSuccess();
    }

    private CleanupResult deleteFiles(List<String> filePathList) {
        UUID id = UUID.randomUUID();
        logger.info("[Clean-{}]: begin", id);
        for (String filePath : filePathList) {
            try {
                deleteByFlinkFS(filePath);
            } catch (StorageUnreachableException e) {
                logger.error("[Clean-{}]: storage unreachable for path: {}", id, filePath, e);
                return CleanupResult.failure(FailureType.STORAGE_UNREACHABLE,
                        "storage unreachable: " + filePath);
            } catch (Exception e) {
                logger.error("[Clean-{}]: fail to delete path: {}", id, filePath, e);
                return CleanupResult.failure(FailureType.FILE_DELETE_FAILED,
                        "file delete failed: " + filePath);
            }
        }
        logger.info("[Clean-{}]: success", id);
        return CleanupResult.success();
    }

    private void deleteByFlinkFS(String filePath) throws IOException {
        Path path = new Path(filePath);
        URI uri = path.toUri();
        FileSystem fs = path.getFileSystem();
        if (!ensureStorageReachable(path, fs)) {
            throw new StorageUnreachableException("storage unreachable for path: " + filePath);
        }
        if (pathExists(fs, path)) {
            // false: delete a single file only (non-recursive)
            try {
                boolean deleted = fs.delete(path, false);
                if (!deleted && pathExists(fs, path)) {
                    throw new IOException("Flink FS returned false when deleting: " + filePath);
                }
            } catch (IOException e) {
                if (isNotFoundAfterReachable(e)) {
                    logger.info("=============================Flink FS 文件不存在，按已清理处理: {} (scheme={})",
                            filePath, uri.getScheme());
                    return;
                }
                throw e;
            }
            logger.info("=============================Flink FS 文件已删除: {} (scheme={})", filePath, uri.getScheme());
        } else {
            logger.info("=============================Flink FS 文件不存在: {} (scheme={})", filePath, uri.getScheme());
        }
    }

    private boolean ensureStorageReachable(Path path, FileSystem fs) {
        URI uri = path.toUri();
        String scheme = uri.getScheme();
        if (!isObjectStorageScheme(scheme)) {
            return true;
        }
        String authority = uri.getAuthority();
        if (authority == null || authority.isEmpty()) {
            logger.warn("对象存储路径缺少 bucket/authority，跳过删除: {}", path);
            return false;
        }
        String bucketKey = scheme + "://" + authority;
        Boolean cached = storageReachableCache.get(bucketKey);
        if (Boolean.TRUE.equals(cached)) {
            return true;
        }
        Path bucketPath = new Path(bucketKey + "/");
        try {
            fs.exists(bucketPath);
            storageReachableCache.put(bucketKey, true);
            logger.info("对象存储桶连通性检查成功: {}", bucketKey);
            return true;
        } catch (IOException e) {
            logger.error("对象存储桶连通性检查失败: {}", bucketKey, e);
            return false;
        }
    }

    private boolean pathExists(FileSystem fs, Path path) throws IOException {
        try {
            return fs.exists(path);
        } catch (FileNotFoundException e) {
            return false;
        }
    }

    private boolean isObjectStorageScheme(String scheme) {
        return "s3".equalsIgnoreCase(scheme)
                || "s3a".equalsIgnoreCase(scheme)
                || "s3n".equalsIgnoreCase(scheme);
    }

    private boolean isNotFoundAfterReachable(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof FileNotFoundException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lowerMessage = message.toLowerCase(Locale.ROOT);
                if (lowerMessage.contains("404")
                        || lowerMessage.contains("not found")
                        || lowerMessage.contains("nosuchkey")
                        || lowerMessage.contains("path does not exist")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }


    public boolean getCompactVersion(String tableId, String partitionDesc, int version, Connection connection) throws SQLException {
        String snapshotSql = "SELECT snapshot FROM partition_info " +
                "WHERE table_id = ? AND partition_desc = ? AND version = ?";

        List<UUID> snapshotCommitIds = new ArrayList<>();

        try (PreparedStatement ps = connection.prepareStatement(snapshotSql)) {
            ps.setString(1, tableId);
            ps.setString(2, partitionDesc);
            ps.setInt(3, version);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Array snapshotArray = rs.getArray("snapshot");
                    if (snapshotArray != null) {
                        UUID[] snapshot = (UUID[]) snapshotArray.getArray();
                        snapshotCommitIds.addAll(Arrays.asList(snapshot));
                    }
                }
            }
        }

        String fileSql = "SELECT unnest(file_ops) AS op FROM data_commit_info WHERE commit_id = ANY(?)";

        try (PreparedStatement ps = connection.prepareStatement(fileSql)) {
            Array uuidArray = connection.createArrayOf("uuid", snapshotCommitIds.toArray());
            ps.setArray(1, uuidArray);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()){
                    Object op = rs.getObject("op");
                    String path = op.toString();
                    logger.info("当前压缩的文件目录：" + path);
                    logger.info("oldCompaction: " + path.contains("compact_"));
                    return path.contains("compact_");
                }
            }
        }
        return true;
    }


    /**
     * Delete files (if needed) and then delete PG metadata.
     * Consistency rule: only delete metadata if file deletions succeed.
     *
     * @return true if all commits in snapshot are cleaned successfully; false otherwise.
     */
    public CleanupResult cleanSnapshotAndPartitionInfo(List<String> snapshot,
                                                       String tableId,
                                                       String partitionDesc,
                                                       int version,
                                                       Connection connection,
                                                       Boolean oldCompaction) {
        LinkedHashSet<String> deleteTargets = new LinkedHashSet<>();
        for (String commitId : snapshot) {
            List<String> commitDeleteTargets = collectDeleteTargets(connection, tableId, partitionDesc, commitId,
                    Boolean.TRUE.equals(oldCompaction));
            if (commitDeleteTargets == null) {
                return CleanupResult.failure(FailureType.QUERY_FILE_OPS_FAILED,
                        String.format("query file_ops failed: tableId=%s, partitionDesc=%s, commitId=%s",
                                tableId, partitionDesc, commitId));
            }
            deleteTargets.addAll(commitDeleteTargets);
        }

        CleanupResult deleteResult = deleteFiles(new ArrayList<>(deleteTargets));
        if (!deleteResult.isSuccess()) {
            return deleteResult;
        }

        return deleteMetadataInTransaction(snapshot, tableId, partitionDesc, version, connection);
    }

    public boolean deleteFileAndDataCommitInfo(List<String> snapshot,
                                              String tableId,
                                              String partitionDesc,
                                              Connection connection,
                                              Boolean oldCompaction) {
        boolean allOk = true;
        for (String commitId : snapshot) {
            boolean commitOk = true;
            List<String> deleteTargets = collectDeleteTargets(connection, tableId, partitionDesc, commitId,
                    Boolean.TRUE.equals(oldCompaction));
            if (deleteTargets == null) {
                commitOk = false;
            } else if (!deleteTargets.isEmpty()) {
                boolean deleted = deleteFile(deleteTargets);
                if (!deleted) {
                    commitOk = false;
                    logger.warn("文件删除失败，跳过删除元数据。tableId={}, partitionDesc={}, commitId={}",
                            tableId, partitionDesc, commitId);
                }
            }

            if (commitOk) {
                String deleteDataCommitInfoSql = "DELETE FROM data_commit_info \n" +
                        "WHERE table_id = '" + tableId + "' \n" +
                        "AND commit_id = '" + commitId + "' \n" +
                        "AND partition_desc = '" + partitionDesc + "'";
                try (PreparedStatement preparedStatement = connection.prepareStatement(deleteDataCommitInfoSql)) {
                    logger.info(deleteDataCommitInfoSql);
                    preparedStatement.executeUpdate();
                } catch (SQLException e) {
                    commitOk = false;
                    logger.error("删除 data_commit_info 失败。tableId={}, partitionDesc={}, commitId={}",
                            tableId, partitionDesc, commitId, e);
                }
            }

            allOk = allOk && commitOk;
        }
        return allOk;
    }

    private CleanupResult deleteMetadataInTransaction(List<String> snapshot,
                                                      String tableId,
                                                      String partitionDesc,
                                                      int version,
                                                      Connection connection) {
        CleanupResult result = CleanupResult.success();
        boolean originalAutoCommit;
        try {
            originalAutoCommit = connection.getAutoCommit();
            if (originalAutoCommit) {
                connection.setAutoCommit(false);
            }
        } catch (SQLException e) {
            logger.error("开启元数据删除事务失败。tableId={}, partitionDesc={}, version={}",
                    tableId, partitionDesc, version, e);
            return CleanupResult.failure(FailureType.TRANSACTION_SETUP_FAILED,
                    String.format("transaction setup failed: tableId=%s, partitionDesc=%s, version=%s",
                            tableId, partitionDesc, version));
        }

        try {
            String deleteDataCommitInfoSql = "DELETE FROM data_commit_info \n" +
                    "WHERE table_id = ? \n" +
                    "AND commit_id = ? \n" +
                    "AND partition_desc = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(deleteDataCommitInfoSql)) {
                for (String commitId : snapshot) {
                    preparedStatement.setString(1, tableId);
                    preparedStatement.setObject(2, UUID.fromString(commitId));
                    preparedStatement.setString(3, partitionDesc);
                    preparedStatement.executeUpdate();
                }
            } catch (SQLException e) {
                logger.error("删除 data_commit_info 失败，准备回滚。tableId={}, partitionDesc={}, version={}",
                        tableId, partitionDesc, version, e);
                result = rollbackAndBuildResult(connection,
                        FailureType.DELETE_DATA_COMMIT_INFO_FAILED,
                        String.format("delete data_commit_info failed: tableId=%s, partitionDesc=%s, version=%s",
                                tableId, partitionDesc, version));
                return result;
            }

            String deletePartitionInfoSql = "DELETE FROM partition_info \n" +
                    "WHERE table_id = ? \n" +
                    "AND partition_desc = ? \n" +
                    "AND version = ?";
            try (PreparedStatement preparedStatement = connection.prepareStatement(deletePartitionInfoSql)) {
                preparedStatement.setString(1, tableId);
                preparedStatement.setString(2, partitionDesc);
                preparedStatement.setInt(3, version);
                preparedStatement.executeUpdate();
            } catch (SQLException e) {
                logger.error("删除 partition_info 失败，准备回滚。tableId={}, partitionDesc={}, version={}",
                        tableId, partitionDesc, version, e);
                result = rollbackAndBuildResult(connection,
                        FailureType.DELETE_PARTITION_INFO_FAILED,
                        String.format("delete partition_info failed: tableId=%s, partitionDesc=%s, version=%s",
                                tableId, partitionDesc, version));
                return result;
            }

            connection.commit();
            logger.info("元数据删除事务提交成功。tableId={}, partitionDesc={}, version={}, snapshotSize={}",
                    tableId, partitionDesc, version, snapshot.size());
            result = CleanupResult.success();
        } catch (SQLException e) {
            logger.error("提交元数据删除事务失败，准备回滚。tableId={}, partitionDesc={}, version={}",
                    tableId, partitionDesc, version, e);
            result = rollbackAndBuildResult(connection,
                    FailureType.DELETE_PARTITION_INFO_FAILED,
                    String.format("metadata commit failed: tableId=%s, partitionDesc=%s, version=%s",
                            tableId, partitionDesc, version));
        } finally {
            try {
                if (originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
            } catch (SQLException e) {
                logger.error("恢复 JDBC autoCommit 失败。tableId={}, partitionDesc={}, version={}",
                        tableId, partitionDesc, version, e);
                if (result.isSuccess()) {
                    result = CleanupResult.failure(FailureType.CONNECTION_STATE_RESTORE_FAILED,
                            String.format("connection state restore failed: tableId=%s, partitionDesc=%s, version=%s",
                                    tableId, partitionDesc, version));
                }
            }
        }
        return result;
    }

    private CleanupResult rollbackAndBuildResult(Connection connection,
                                                 FailureType failureType,
                                                 String failureMessage) {
        try {
            connection.rollback();
            logger.warn("元数据删除事务已回滚: failureType={}, detail={}", failureType, failureMessage);
            return CleanupResult.failure(failureType, failureMessage);
        } catch (SQLException rollbackException) {
            logger.error("元数据删除事务回滚失败: failureType={}, detail={}",
                    failureType, failureMessage, rollbackException);
            return CleanupResult.failure(FailureType.METADATA_ROLLBACK_FAILED,
                    failureMessage + "; rollback failed");
        }
    }

    private List<String> collectDeleteTargets(Connection connection,
                                              String tableId,
                                              String partitionDesc,
                                              String commitId,
                                              boolean oldCompaction) {
        String sql = "SELECT file_op.path \n" +
                "FROM data_commit_info dci, \n" +
                "    unnest(dci.file_ops) AS file_op \n" +
                "WHERE dci.table_id = ? \n" +
                "  AND dci.partition_desc = ? \n" +
                "  AND dci.commit_id = ?";
        LinkedHashSet<String> deleteTargets = new LinkedHashSet<>();
        try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
            preparedStatement.setString(1, tableId);
            preparedStatement.setString(2, partitionDesc);
            preparedStatement.setObject(3, UUID.fromString(commitId));
            ResultSet pathSet = preparedStatement.executeQuery();
            while (pathSet.next()) {
                String path = pathSet.getString("path");
                String deleteTarget = oldCompaction ? tryResolveCompactDirectory(path) : path;
                deleteTargets.add(deleteTarget == null ? path : deleteTarget);
            }
            if (oldCompaction) {
                logger.info("清理旧版压缩数据，删除目标数: {}", deleteTargets.size());
            }
            return new ArrayList<>(deleteTargets);
        } catch (Exception e) {
            logger.error("查询 data_commit_info.file_ops 失败，跳过删除元数据。tableId={}, partitionDesc={}, commitId={}",
                    tableId, partitionDesc, commitId, e);
            return null;
        }
    }

    private String tryResolveCompactDirectory(String filePath) {
        int compactIndex = filePath.lastIndexOf("compact_");
        if (compactIndex < 0) {
            return filePath;
        }
        int nextDirectoryIndex = filePath.indexOf("/", compactIndex);
        if (nextDirectoryIndex < 0) {
            return filePath;
        }
        return filePath.substring(0, nextDirectoryIndex);
    }

    public void cleanDiscardFile(long expiredTime, int batchSize, Connection connection) throws SQLException {
        logger.info("expiredTime: " + expiredTime);
        logger.info("从discard_compressed_file_info表中清理过期数据");
        System.out.println("从discard_compressed_file_info表中清理过期数据");
        long expiredThreshold = System.currentTimeMillis() - expiredTime;
        int effectiveBatchSize = Math.max(batchSize, 1);
        String deleteSql = "DELETE FROM discard_compressed_file_info WHERE file_path = ?";
        try (PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)) {
            DiscardFileRecord lastRecord = null;
            while (true) {
                List<DiscardFileRecord> batch = fetchDiscardBatch(connection, expiredThreshold, effectiveBatchSize, lastRecord);
                if (batch.isEmpty()) {
                    break;
                }
                cleanDiscardBatch(batch, deleteStmt);
                lastRecord = batch.get(batch.size() - 1);
            }
        }
    }

    private List<DiscardFileRecord> fetchDiscardBatch(Connection connection, long expiredThreshold,
                                                      int batchSize, DiscardFileRecord lastRecord) throws SQLException {
        String firstPageSql = "SELECT file_path, timestamp FROM discard_compressed_file_info " +
                "WHERE timestamp < ? ORDER BY timestamp, file_path LIMIT ?";
        String nextPageSql = "SELECT file_path, timestamp FROM discard_compressed_file_info " +
                "WHERE timestamp < ? AND (timestamp > ? OR (timestamp = ? AND file_path > ?)) " +
                "ORDER BY timestamp, file_path LIMIT ?";
        List<DiscardFileRecord> batch = new ArrayList<>(batchSize);
        try (PreparedStatement selectStmt = connection.prepareStatement(lastRecord == null ? firstPageSql : nextPageSql)) {
            selectStmt.setFetchSize(batchSize);
            selectStmt.setLong(1, expiredThreshold);
            if (lastRecord == null) {
                selectStmt.setInt(2, batchSize);
            } else {
                selectStmt.setLong(2, lastRecord.timestamp);
                selectStmt.setLong(3, lastRecord.timestamp);
                selectStmt.setString(4, lastRecord.filePath);
                selectStmt.setInt(5, batchSize);
            }
            try (ResultSet resultSet = selectStmt.executeQuery()) {
                while (resultSet.next()) {
                    batch.add(new DiscardFileRecord(
                            resultSet.getLong("timestamp"),
                            resultSet.getString("file_path")));
                }
            }
        }
        return batch;
    }

    private void cleanDiscardBatch(List<DiscardFileRecord> batch, PreparedStatement deleteStmt) throws SQLException {
        int metadataDeleteCount = 0;
        int failedCount = 0;
        for (DiscardFileRecord record : batch) {
            boolean ok = deleteFile(Collections.singletonList(record.filePath));
            if (ok) {
                deleteStmt.setString(1, record.filePath);
                deleteStmt.executeUpdate();
                metadataDeleteCount++;
            } else {
                failedCount++;
                logger.warn("discard_compressed_file_info 文件删除失败，保留元数据以便重试: {}", record.filePath);
            }
        }
        logger.info("discard_compressed_file_info 当前批处理完成，batchSize={}, metadataDeleteCount={}, failedCount={}",
                batch.size(), metadataDeleteCount, failedCount);
    }


    public String[] parseFileOpsString(String fileOPs) {
        String[] fileInfo = new String[2];
        // 正则表达式匹配文件路径和其他信息
        Pattern pattern = Pattern.compile("\\(([^,]+),([^,]+),([^,]+),(\"[^\"]*\"|[^,)]+)\\)");
        Matcher matcher = pattern.matcher(fileOPs);
        if (matcher.find()) {
            String filePath = matcher.group(1);
            fileInfo[0] = filePath; // 文件路径
            fileInfo[1] = matcher.group(4); // 其他信息（如字段列表）
        } else {
            logger.info("=============================未找到匹配的文件路径!");
        }
        return fileInfo;
    }

    public List<String> getTableIdByTableName(String tableNames, Connection connection) throws SQLException {
        if (tableNames == null) {
            return null;
        }
        List<String> tableList = new ArrayList<>();
        for (String table : tableNames.split(",")) {
            String dbName = table.split("\\.")[0];
            String tableName = table.split("\\.")[1];
            String sql = "select table_id from table_name_id where table_name = ? and table_namespace = ?";
            PreparedStatement preparedStatement = connection.prepareStatement(sql);
            preparedStatement.setString(1,tableName);
            preparedStatement.setString(2, dbName);
            ResultSet resultSet = preparedStatement.executeQuery();
            while (resultSet.next()){
                String tableId = resultSet.getString("table_id");
                tableList.add(tableId);
            }

        }
        connection.close();
        return tableList;

    }

}

