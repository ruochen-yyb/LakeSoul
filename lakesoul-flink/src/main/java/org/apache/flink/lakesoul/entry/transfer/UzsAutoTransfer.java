// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package org.apache.flink.lakesoul.entry.transfer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.core.execution.JobClient;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.TableResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UzsAutoTransfer {

    private static final Logger LOG = LoggerFactory.getLogger(UzsAutoTransfer.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String CLAIM_TYPE = "FLINK";

    private static final String API_BASE_URL = "transfer.api-base-url";
    private static final String CLAIMED_BY = "transfer.claimed-by";
    private static final String NO_TASK_SLEEP_MS = "transfer.no-task-sleep-ms";
    private static final String RETRYABLE_FAIL_SLEEP_MS = "transfer.retryable-fail-sleep-ms";
    private static final String MAX_CONSECUTIVE_FAILS = "transfer.max-consecutive-fails";
    private static final String CIRCUIT_BREAKER_SLEEP_MS = "transfer.circuit-breaker-sleep-ms";
    private static final String DONE_RETRY_MAX_ATTEMPTS = "transfer.done-retry-max-attempts";
    private static final String DONE_RETRY_SLEEP_MS = "transfer.done-retry-sleep-ms";
    private static final String SQL_TIMEOUT_MS = "transfer.sql-timeout-ms";
    private static final String HTTP_TIMEOUT_MS = "transfer.http-timeout-ms";
    private static final String CATALOG_NAME = "transfer.lakesoul.catalog-name";
    private static final String CATALOG_WAREHOUSE = "transfer.lakesoul.warehouse";
    private static final String CATALOG_S3_ENDPOINT = "transfer.lakesoul.s3.endpoint";
    private static final String CATALOG_S3_ACCESS_KEY = "transfer.lakesoul.s3.access-key";
    private static final String CATALOG_S3_SECRET_KEY = "transfer.lakesoul.s3.secret-key";
    private static final String CATALOG_S3_PATH_STYLE_ACCESS = "transfer.lakesoul.s3.path-style-access";

    private static final long DEFAULT_NO_TASK_SLEEP_MS = 10_000L;
    private static final long DEFAULT_RETRYABLE_FAIL_SLEEP_MS = 300_000L;
    private static final int DEFAULT_MAX_CONSECUTIVE_FAILS = 10;
    private static final long DEFAULT_CIRCUIT_BREAKER_SLEEP_MS = 60_000L;
    private static final long DEFAULT_DONE_RETRY_SLEEP_MS = 5_000L;
    private static final int DEFAULT_DONE_RETRY_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_SQL_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long DEFAULT_HTTP_TIMEOUT_MS = 10_000L;
    private static final String DEFAULT_CATALOG_NAME = "lakesoul";
    private static final String DEFAULT_CATALOG_S3_PATH_STYLE_ACCESS = "true";

    private static final String PLACEHOLDER_SRC_NS = "src_ns";
    private static final String PLACEHOLDER_SRC_TABLE = "src_table";
    private static final String PLACEHOLDER_DST_NS = "dst_ns";
    private static final String PLACEHOLDER_DST_TABLE = "dst_table";
    private static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            PLACEHOLDER_SRC_NS,
            PLACEHOLDER_SRC_TABLE,
            PLACEHOLDER_DST_NS,
            PLACEHOLDER_DST_TABLE
    );

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");
    private static final Pattern WHERE_PATTERN = Pattern.compile("(?i)\\bwhere\\b");
    private static final Pattern FORBIDDEN_TOKEN_PATTERN = Pattern.compile("(;|--|/\\*|\\*/)");
    private static final Pattern FORBIDDEN_KEYWORD_PATTERN = Pattern.compile("(?i)\\b(drop|alter|truncate|create)\\b");
    private static final Pattern SINGLE_DML_PATTERN = Pattern.compile("(?is)^\\s*insert\\s+into\\b.+\\bselect\\b.+\\bfrom\\b.+");

    public static void main(String[] args) throws Exception {
        ParameterTool parameter = ParameterTool.fromArgs(args);
        String apiBaseUrl = requireArg(parameter, API_BASE_URL);
        String claimedBy = requireArg(parameter, CLAIMED_BY);
        String catalogName = parameter.get(CATALOG_NAME, DEFAULT_CATALOG_NAME);
        String warehouse = requireArg(parameter, CATALOG_WAREHOUSE);
        String s3Endpoint = requireArg(parameter, CATALOG_S3_ENDPOINT);
        String s3AccessKey = requireArg(parameter, CATALOG_S3_ACCESS_KEY);
        String s3SecretKey = requireArg(parameter, CATALOG_S3_SECRET_KEY);
        String s3PathStyleAccess = parameter.get(CATALOG_S3_PATH_STYLE_ACCESS, DEFAULT_CATALOG_S3_PATH_STYLE_ACCESS);

        long noTaskSleepMs = parameter.getLong(NO_TASK_SLEEP_MS, DEFAULT_NO_TASK_SLEEP_MS);
        long retryableFailSleepMs = parameter.getLong(RETRYABLE_FAIL_SLEEP_MS, DEFAULT_RETRYABLE_FAIL_SLEEP_MS);
        int maxConsecutiveFails = parameter.getInt(MAX_CONSECUTIVE_FAILS, DEFAULT_MAX_CONSECUTIVE_FAILS);
        long circuitBreakerSleepMs = parameter.getLong(CIRCUIT_BREAKER_SLEEP_MS, DEFAULT_CIRCUIT_BREAKER_SLEEP_MS);
        int doneRetryMaxAttempts = parameter.getInt(DONE_RETRY_MAX_ATTEMPTS, DEFAULT_DONE_RETRY_MAX_ATTEMPTS);
        long doneRetrySleepMs = parameter.getLong(DONE_RETRY_SLEEP_MS, DEFAULT_DONE_RETRY_SLEEP_MS);
        long sqlTimeoutMs = parameter.getLong(SQL_TIMEOUT_MS, DEFAULT_SQL_TIMEOUT_MS);
        long httpTimeoutMs = parameter.getLong(HTTP_TIMEOUT_MS, DEFAULT_HTTP_TIMEOUT_MS);

        validateStartupArgs(doneRetryMaxAttempts, noTaskSleepMs, retryableFailSleepMs, maxConsecutiveFails,
                circuitBreakerSleepMs, doneRetrySleepMs, sqlTimeoutMs, httpTimeoutMs);
        Stats stats = new Stats();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(httpTimeoutMs))
                .build();
        TransferApiClient apiClient = new TransferApiClient(apiBaseUrl, claimedBy, httpClient, httpTimeoutMs, stats.callbackFailed);

        EnvironmentSettings settings = EnvironmentSettings.newInstance().inBatchMode().build();
        TableEnvironment tableEnv = TableEnvironment.create(settings);
        initLakeSoulCatalog(tableEnv, catalogName, warehouse, s3Endpoint, s3AccessKey, s3SecretKey, s3PathStyleAccess);
        int consecutiveFails = 0;

        LOG.info("UzsAutoTransfer started: apiBaseUrl={}, claimedBy={}", apiBaseUrl, claimedBy);
        LOG.info("UzsAutoTransfer config: noTaskSleepMs={}, retryableFailSleepMs={}, maxConsecutiveFails={}, circuitBreakerSleepMs={}, doneRetryMaxAttempts={}, doneRetrySleepMs={}, sqlTimeoutMs={}, httpTimeoutMs={}",
                noTaskSleepMs, retryableFailSleepMs, maxConsecutiveFails, circuitBreakerSleepMs,
                doneRetryMaxAttempts, doneRetrySleepMs, sqlTimeoutMs, httpTimeoutMs);
        while (true) {
            try {
                TransferTask task = apiClient.getTransferTask();
                if (task == null) {
                    LOG.info("transfer task stage=no-task sleepMs={}", noTaskSleepMs);
                    sleep(noTaskSleepMs, "no task");
                    continue;
                }
                stats.claimed.incrementAndGet();
                LOG.info("transfer task stage=claimed summary={}", summarizeTask(task));
                ProcessResult result = processTask(task, apiClient, tableEnv, sqlTimeoutMs, doneRetryMaxAttempts, doneRetrySleepMs);
                stats.totalElapsedMs.addAndGet(result.elapsedMs);
                if (result.success) {
                    stats.success.incrementAndGet();
                    consecutiveFails = 0;
                } else {
                    stats.failed.incrementAndGet();
                    consecutiveFails++;
                    if (result.retryable) {
                        long retrySleep = computeRetrySleepMs(retryableFailSleepMs, consecutiveFails);
                        sleep(retrySleep, "retryable task error");
                    }
                    if (consecutiveFails >= maxConsecutiveFails) {
                        LOG.warn("circuit breaker open, consecutiveFails={}, sleep={}ms", consecutiveFails, circuitBreakerSleepMs);
                        sleep(circuitBreakerSleepMs, "circuit breaker");
                    }
                }
                logStats(stats, consecutiveFails);
            } catch (Throwable t) {
                LOG.error("poll transfer task failed, continue loop", t);
                sleep(noTaskSleepMs, "poll failed");
            }
        }
    }

    private static ProcessResult processTask(TransferTask task,
                                             TransferApiClient apiClient,
                                             TableEnvironment tableEnv,
                                             long sqlTimeoutMs,
                                             int doneRetryMaxAttempts,
                                             long doneRetrySleepMs) {
        String taskTag = task.tableId + "|" + safe(task.partitionDesc) + "|" + task.version;
        long startMs = System.currentTimeMillis();
        try {
            logTaskStage(taskTag, "validate-start", "summary=" + summarizeTask(task));
            validateTask(task);
            logTaskStage(taskTag, "validate-done", "summary=" + summarizeTask(task));
            String sql = renderAndValidateSql(task);
            String sqlDigest = Integer.toHexString(sql.hashCode());
            LOG.info("start transfer task={}, src={}.{}, dst={}.{}, sqlDigest={}, sqlLength={}",
                    taskTag, task.tableNamespace, task.tableName, task.archiveTargetTableNamespace, task.archiveTargetTableName, sqlDigest, sql.length());
            logTaskStage(taskTag, "sql-rendered", "sqlDigest=" + sqlDigest + ", sqlLength=" + sql.length());
            LOG.info("transfer sql task={}, isPartitionTable={}, partitionDesc={}, sql=\n{}",
                    taskTag, task.isPartitionTable, safe(task.partitionDesc), sql);

            logTaskStage(taskTag, "flink-submit-start", "executeSql begin");
            TableResult tableResult = tableEnv.executeSql(sql);
            Optional<JobClient> jobClient = tableResult.getJobClient();
            if (jobClient.isPresent()) {
                logTaskStage(taskTag, "flink-submit-done", "jobId=" + jobClient.get().getJobID());
            } else {
                logTaskStage(taskTag, "flink-submit-done", "jobId=<empty>");
            }
            logTaskStage(taskTag, "flink-await-start", "timeoutMs=" + sqlTimeoutMs + ", resultKind=" + tableResult.getResultKind());
            tableResult.await(sqlTimeoutMs, TimeUnit.MILLISECONDS);
            logTaskStage(taskTag, "flink-await-done", "resultKind=" + tableResult.getResultKind());

            logTaskStage(taskTag, "callback-done-start", "attempts=" + doneRetryMaxAttempts);
            callbackDoneWithRetry(task, apiClient, doneRetryMaxAttempts, doneRetrySleepMs);
            logTaskStage(taskTag, "callback-done-finished", "done");
            long elapsed = System.currentTimeMillis() - startMs;
            LOG.info("transfer task done={}, costMs={}", taskTag, elapsed);
            return ProcessResult.success(elapsed);
        } catch (Throwable taskError) {
            LOG.error("transfer task failed={}, retryable={}", taskTag, isRetryableTaskError(taskError), taskError);
            try {
                logTaskStage(taskTag, "callback-err-start", "message=" + buildErrorMessage(taskError));
                apiClient.setTaskErr(task, buildErrorMessage(taskError));
                logTaskStage(taskTag, "callback-err-finished", "reported");
            } catch (Throwable reportError) {
                LOG.error("setTaskErr failed for task={}", taskTag, reportError);
            }
            long elapsed = System.currentTimeMillis() - startMs;
            return ProcessResult.failed(elapsed, isRetryableTaskError(taskError));
        }
    }

    private static void callbackDoneWithRetry(TransferTask task,
                                              TransferApiClient apiClient,
                                              int maxAttempts,
                                              long retrySleepMs) throws Exception {
        Throwable latest = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                LOG.info("setTaskDone attempt {}/{} task={}", i, maxAttempts, summarizeTask(task));
                apiClient.setTaskDone(task);
                LOG.info("setTaskDone success attempt {}/{} task={}", i, maxAttempts, summarizeTask(task));
                return;
            } catch (Throwable t) {
                latest = t;
                if (!isRetryableCallbackError(t) || i == maxAttempts) {
                    break;
                }
                LOG.warn("setTaskDone retry {}/{} for task={} because: {}",
                        i, maxAttempts, task.tableId, t.getMessage());
                sleep(retrySleepMs, "setTaskDone retry");
            }
        }
        throw new Exception("setTaskDone failed after retries", latest);
    }

    private static String renderAndValidateSql(TransferTask task) {
        String template = task.archiveSqlTemplate;
        validateTemplate(template);
        String rendered = template;
        rendered = replacePlaceholder(rendered, PLACEHOLDER_SRC_NS, escapeIdentifier(task.tableNamespace));
        rendered = replacePlaceholder(rendered, PLACEHOLDER_SRC_TABLE, escapeIdentifier(task.tableName));
        rendered = replacePlaceholder(rendered, PLACEHOLDER_DST_NS, escapeIdentifier(task.archiveTargetTableNamespace));
        rendered = replacePlaceholder(rendered, PLACEHOLDER_DST_TABLE, escapeIdentifier(task.archiveTargetTableName));

        if (Boolean.TRUE.equals(task.isPartitionTable)) {
            String partitionPredicate = buildPartitionPredicate(task.partitionDesc);
            rendered = appendPredicate(rendered, partitionPredicate);
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(rendered);
        if (matcher.find()) {
            throw new IllegalArgumentException("template still contains placeholder: " + matcher.group(0));
        }
        validateRenderedSql(rendered);
        return rendered;
    }

    private static void validateTask(TransferTask task) {
        if (task == null) {
            throw new IllegalArgumentException("task is null");
        }
        requireNotBlank(task.tableId, "tableId");
        requireNotBlank(task.tableName, "tableName");
        requireNotBlank(task.tableNamespace, "tableNamespace");
        requireNotBlank(task.archiveTargetTableName, "archiveTargetTableName");
        requireNotBlank(task.archiveTargetTableNamespace, "archiveTargetTableNamespace");
        requireNotBlank(task.archiveSqlTemplate, "archiveSqlTemplate");
        if (task.isPartitionTable == null) {
            throw new IllegalArgumentException("isPartitionTable is null");
        }
    }

    private static void validateTemplate(String template) {
        if (StringUtils.isBlank(template)) {
            throw new IllegalArgumentException("archiveSqlTemplate is blank");
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1).trim();
            if (!ALLOWED_PLACEHOLDERS.contains(key)) {
                throw new IllegalArgumentException("unknown placeholder: " + key);
            }
        }
        validateRenderedSql(template);
    }

    private static void validateRenderedSql(String sql) {
        if (FORBIDDEN_TOKEN_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("sql contains forbidden tokens");
        }
        if (FORBIDDEN_KEYWORD_PATTERN.matcher(sql).find()) {
            throw new IllegalArgumentException("sql contains forbidden keywords");
        }
        if (!SINGLE_DML_PATTERN.matcher(sql).matches()) {
            throw new IllegalArgumentException("only single DML INSERT INTO ... SELECT ... FROM ... is allowed");
        }
    }

    private static String replacePlaceholder(String template, String key, String replacement) {
        String placeholderRegex = "\\{\\{\\s*" + Pattern.quote(key) + "\\s*}}";
        return template.replaceAll(placeholderRegex, Matcher.quoteReplacement(replacement));
    }

    private static String escapeIdentifier(String identifier) {
        requireNotBlank(identifier, "identifier");
        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException("invalid identifier: " + identifier);
        }
        return "`" + identifier + "`";
    }

    private static String buildPartitionPredicate(String partitionDesc) {
        if (StringUtils.isBlank(partitionDesc)) {
            throw new IllegalArgumentException("partition task requires partitionDesc");
        }
        String[] segments = partitionDesc.split(",");
        List<String> predicates = new ArrayList<>();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("partitionDesc contains empty segment");
            }
            int eqIndex = trimmed.indexOf('=');
            if (eqIndex <= 0 || eqIndex == trimmed.length() - 1) {
                throw new IllegalArgumentException("partitionDesc segment invalid: " + trimmed);
            }
            String key = trimmed.substring(0, eqIndex).trim();
            String value = trimmed.substring(eqIndex + 1).trim();
            if (key.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("partitionDesc segment invalid: " + trimmed);
            }
            String escapedKey = escapeIdentifier(key);
            String escapedValue = escapeSqlLiteral(value);
            predicates.add(escapedKey + " = '" + escapedValue + "'");
        }
        return String.join(" AND ", predicates);
    }

    private static String appendPredicate(String sql, String predicate) {
        String trimmedSql = sql.trim();
        if (WHERE_PATTERN.matcher(trimmedSql).find()) {
            return trimmedSql + " AND " + predicate;
        }
        return trimmedSql + " WHERE " + predicate;
    }

    private static boolean isRetryableTaskError(Throwable t) {
        return !(t instanceof IllegalArgumentException);
    }

    private static boolean isRetryableCallbackError(Throwable t) {
        if (t instanceof ApiRequestException) {
            ApiRequestException ex = (ApiRequestException) t;
            return ex.statusCode >= 500 || ex.statusCode < 0;
        }
        return t instanceof IOException || t.getCause() instanceof IOException;
    }

    private static String buildErrorMessage(Throwable t) {
        String message = t.getClass().getSimpleName() + ": " + safe(t.getMessage());
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private static void sleep(long ms, String reason) {
        if (ms <= 0L) {
            return;
        }
        try {
            LOG.info("sleep {} ms because {}", ms, reason);
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("sleep interrupted", e);
        }
    }

    private static void validateStartupArgs(int doneRetryMaxAttempts,
                                            long noTaskSleepMs,
                                            long retryableFailSleepMs,
                                            int maxConsecutiveFails,
                                            long circuitBreakerSleepMs,
                                            long doneRetrySleepMs,
                                            long sqlTimeoutMs,
                                            long httpTimeoutMs) {
        if (doneRetryMaxAttempts < 1) {
            throw new IllegalArgumentException("--" + DONE_RETRY_MAX_ATTEMPTS + " must be >= 1");
        }
        if (maxConsecutiveFails < 1) {
            throw new IllegalArgumentException("--" + MAX_CONSECUTIVE_FAILS + " must be >= 1");
        }
        requirePositive(noTaskSleepMs, NO_TASK_SLEEP_MS);
        requirePositive(retryableFailSleepMs, RETRYABLE_FAIL_SLEEP_MS);
        requirePositive(circuitBreakerSleepMs, CIRCUIT_BREAKER_SLEEP_MS);
        requirePositive(doneRetrySleepMs, DONE_RETRY_SLEEP_MS);
        requirePositive(sqlTimeoutMs, SQL_TIMEOUT_MS);
        requirePositive(httpTimeoutMs, HTTP_TIMEOUT_MS);
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0L) {
            throw new IllegalArgumentException("--" + key + " must be > 0");
        }
    }

    private static long computeRetrySleepMs(long maxSleepMs, int consecutiveFails) {
        int power = Math.max(0, Math.min(10, consecutiveFails - 1));
        long sleepMs = 10_000L * (1L << power);
        return Math.min(maxSleepMs, sleepMs);
    }

    private static void logStats(Stats stats, int consecutiveFails) {
        long claimed = stats.claimed.get();
        long success = stats.success.get();
        long failed = stats.failed.get();
        long avgCost = claimed == 0L ? 0L : stats.totalElapsedMs.get() / claimed;
        LOG.info("transfer stats claimed={}, success={}, failed={}, callbackFailed={}, avgCostMs={}, consecutiveFails={}",
                claimed, success, failed, stats.callbackFailed.get(), avgCost, consecutiveFails);
    }

    private static void logTaskStage(String taskTag, String stage, String detail) {
        LOG.info("transfer task stage={} task={} detail={}", stage, taskTag, safe(detail));
    }

    private static String summarizeTask(TransferTask task) {
        if (task == null) {
            return "task=<null>";
        }
        return "tableId=" + safe(task.tableId)
                + ", src=" + safe(task.tableNamespace) + "." + safe(task.tableName)
                + ", dst=" + safe(task.archiveTargetTableNamespace) + "." + safe(task.archiveTargetTableName)
                + ", isPartitionTable=" + task.isPartitionTable
                + ", partitionDesc=" + safe(task.partitionDesc)
                + ", version=" + task.version;
    }

    private static String summarizePayload(Map<String, Object> payload) {
        List<String> parts = new ArrayList<>();
        payload.forEach((key, value) -> parts.add(key + "=" + abbreviate(String.valueOf(value), 200)));
        Collections.sort(parts);
        return String.join(", ", parts);
    }

    private static String abbreviate(String value, int maxLen) {
        String normalized = safe(value).replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...(truncated)";
    }

    private static void initLakeSoulCatalog(TableEnvironment tableEnv,
                                            String catalogName,
                                            String warehouse,
                                            String s3Endpoint,
                                            String s3AccessKey,
                                            String s3SecretKey,
                                            String s3PathStyleAccess) {
        String escapedCatalog = escapeIdentifier(catalogName);
        String createCatalogSql = "CREATE CATALOG " + escapedCatalog + " WITH ("
                + "'type' = 'lakesoul',"
                + "'warehouse' = '" + escapeSqlLiteral(warehouse) + "',"
                + "'s3a.endpoint' = '" + escapeSqlLiteral(s3Endpoint) + "',"
                + "'s3a.access-key' = '" + escapeSqlLiteral(s3AccessKey) + "',"
                + "'s3a.secret-key' = '" + escapeSqlLiteral(s3SecretKey) + "',"
                + "'s3a.path.style.access' = '" + escapeSqlLiteral(s3PathStyleAccess) + "'"
                + ")";
        tableEnv.executeSql(createCatalogSql);
        tableEnv.executeSql("USE CATALOG " + escapedCatalog);
        LOG.info("init lakesoul catalog done, catalog={}, warehouse={}", catalogName, warehouse);
    }

    private static String requireArg(ParameterTool parameterTool, String key) {
        String value = parameterTool.get(key);
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException("missing required argument: --" + key);
        }
        return value.trim();
    }

    private static void requireNotBlank(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escapeSqlLiteral(String value) {
        return safe(value).replace("'", "''");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiResponse<T> {
        public int code;
        public String message;
        public T data;
    }

    private static class ProcessResult {
        private final boolean success;
        private final boolean retryable;
        private final long elapsedMs;

        private ProcessResult(boolean success, boolean retryable, long elapsedMs) {
            this.success = success;
            this.retryable = retryable;
            this.elapsedMs = elapsedMs;
        }

        private static ProcessResult success(long elapsedMs) {
            return new ProcessResult(true, false, elapsedMs);
        }

        private static ProcessResult failed(long elapsedMs, boolean retryable) {
            return new ProcessResult(false, retryable, elapsedMs);
        }
    }

    private static class Stats {
        private final AtomicLong claimed = new AtomicLong(0L);
        private final AtomicLong success = new AtomicLong(0L);
        private final AtomicLong failed = new AtomicLong(0L);
        private final AtomicLong callbackFailed = new AtomicLong(0L);
        private final AtomicLong totalElapsedMs = new AtomicLong(0L);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ApiErrorData {
        public String code;
        public String message;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TransferTask {
        public String tableId;
        public String tableName;
        public String tableNamespace;
        public Boolean isPartitionTable;
        public String partitionDesc;
        public Long version;
        public String archiveTargetTableName;
        public String archiveTargetTableNamespace;
        public String archiveSqlTemplate;
    }

    private static class TransferApiClient {
        private final String apiBaseUrl;
        private final String claimedBy;
        private final HttpClient client;
        private final long timeoutMs;
        private final AtomicLong callbackFailed;

        private TransferApiClient(String apiBaseUrl, String claimedBy, HttpClient client, long timeoutMs, AtomicLong callbackFailed) {
            this.apiBaseUrl = trimTrailingSlash(apiBaseUrl);
            this.claimedBy = claimedBy;
            this.client = client;
            this.timeoutMs = timeoutMs;
            this.callbackFailed = callbackFailed;
        }

        private TransferTask getTransferTask() throws IOException, InterruptedException {
            String query = "claimedBy=" + URLEncoder.encode(claimedBy, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl + "/getTransferTask?" + query))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET()
                    .build();
            String body = sendRequest(request, "claimedBy=" + claimedBy);
            ApiResponse<TransferTask> response = parseBody(body, new TypeReference<ApiResponse<TransferTask>>() {
            });
            if (response.code != 0) {
                throw new ApiRequestException(-1, "getTransferTask failed: " + response.message);
            }
            if (response.data == null) {
                LOG.info("getTransferTask result: no task, message={}", safe(response.message));
            } else {
                LOG.info("getTransferTask result: {}", summarizeTask(response.data));
            }
            return response.data;
        }

        private void setTaskDone(TransferTask task) throws IOException, InterruptedException {
            Map<String, Object> payload = buildCallbackPayload(task, null);
            callSetTask("/setTaskDone", payload);
        }

        private void setTaskErr(TransferTask task, String errorMessage) throws IOException, InterruptedException {
            Map<String, Object> payload = buildCallbackPayload(task, errorMessage);
            callSetTask("/setTaskErr", payload);
        }

        private void callSetTask(String path, Map<String, Object> payload) throws IOException, InterruptedException {
            try {
                String requestBody = OBJECT_MAPPER.writeValueAsString(payload);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(apiBaseUrl + path))
                        .timeout(Duration.ofMillis(timeoutMs))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                        .build();
                String body = sendRequest(request, summarizePayload(payload));
                ApiResponse<ApiErrorData> response = parseBody(body, new TypeReference<ApiResponse<ApiErrorData>>() {
                });
                if (response.code != 0) {
                    String detailCode = response.data == null ? "" : safe(response.data.code);
                    String detailMessage = response.data == null ? "" : safe(response.data.message);
                    throw new ApiRequestException(-1, path + " failed: " + response.message
                            + ", code=" + detailCode + ", detail=" + detailMessage);
                }
            } catch (IOException | InterruptedException e) {
                callbackFailed.incrementAndGet();
                throw e;
            }
        }

        private Map<String, Object> buildCallbackPayload(TransferTask task, String errorMessage) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("claimType", CLAIM_TYPE);
            payload.put("tableId", task.tableId);
            payload.put("partitionDesc", task.partitionDesc);
            payload.put("version", task.version);
            if (StringUtils.isNotBlank(errorMessage)) {
                payload.put("errorMessage", errorMessage);
            }
            return payload;
        }

        private String sendRequest(HttpRequest request, String requestSummary) throws IOException, InterruptedException {
            long startMs = System.currentTimeMillis();
            LOG.info("transfer api request method={} uri={} summary={}",
                    request.method(), request.uri(), abbreviate(requestSummary, 300));
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = response.statusCode();
            long costMs = System.currentTimeMillis() - startMs;
            LOG.info("transfer api response method={} uri={} status={} costMs={} body={}",
                    request.method(), request.uri(), statusCode, costMs, abbreviate(response.body(), 500));
            if (statusCode < 200 || statusCode >= 300) {
                throw new ApiRequestException(statusCode, "http status " + statusCode + ", body=" + response.body());
            }
            return response.body();
        }

        private <T> T parseBody(String body, TypeReference<T> typeReference) throws JsonProcessingException {
            return OBJECT_MAPPER.readValue(body, typeReference);
        }
    }

    private static class ApiRequestException extends IOException {
        private final int statusCode;

        private ApiRequestException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }
}
