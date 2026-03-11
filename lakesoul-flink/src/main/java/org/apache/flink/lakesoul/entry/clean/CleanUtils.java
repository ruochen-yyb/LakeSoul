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
        UUID id = UUID.randomUUID();
        logger.info("[Clean-{}]: begin", id);
        boolean hasError = false;
        for (String filePath : filePathList) {
            try {
                deleteByFlinkFS(filePath);
            } catch (Exception e) {
                hasError = true;
                logger.error("[Clean-{}]: fail to delete path: {}", id, filePath, e);
            }
        }
        if (!hasError) {
            logger.info("[Clean-{}]: success", id);
        }
        return !hasError;
    }

    private void deleteByFlinkFS(String filePath) throws IOException {
        Path path = new Path(filePath);
        URI uri = path.toUri();
        FileSystem fs = path.getFileSystem();
        if (!ensureStorageReachable(path, fs)) {
            throw new IOException("storage unreachable for path: " + filePath);
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

    public void cleanDiscardFile(long expiredTime, Connection connection) throws SQLException {
        logger.info("expiredTime: " + expiredTime);
        logger.info("从discard_compressed_file_info表中清理过期数据");
        System.out.println("从discard_compressed_file_info表中清理过期数据");
        long currentTimeMillis = System.currentTimeMillis();
        String querySql = "SELECT file_path FROM discard_compressed_file_info WHERE timestamp < ?";
        String deleteSql = "DELETE FROM discard_compressed_file_info WHERE file_path = ?";
        try (
                PreparedStatement selectStmt = connection.prepareStatement(querySql);
                PreparedStatement deleteStmt = connection.prepareStatement(deleteSql)
        ) {
            selectStmt.setLong(1, currentTimeMillis - expiredTime);
            ResultSet resultSet = selectStmt.executeQuery();

            while (resultSet.next()) {
                String filePath = resultSet.getString("file_path");
                boolean ok = deleteFile(Collections.singletonList(filePath));
                if (ok) {
                    deleteStmt.setString(1, filePath);
                    deleteStmt.executeUpdate();
                } else {
                    logger.warn("discard_compressed_file_info 文件删除失败，保留元数据以便重试: {}", filePath);
                }
            }
        }

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

