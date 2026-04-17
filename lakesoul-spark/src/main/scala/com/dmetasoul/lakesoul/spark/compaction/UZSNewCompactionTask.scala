// SPDX-FileCopyrightText: 2023 LakeSoul Contributors
//
// SPDX-License-Identifier: Apache-2.0

package com.dmetasoul.lakesoul.spark.compaction

import com.dmetasoul.lakesoul.meta.MetaUtils
import com.dmetasoul.lakesoul.spark.ParametersTool
import com.dmetasoul.lakesoul.tables.LakeSoulTable
import com.google.gson.{JsonObject, JsonParser}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.lakesoul.catalog.LakeSoulCatalog

import java.io.BufferedInputStream
import java.net.{HttpURLConnection, URL, URLEncoder}
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import scala.io.Source

object UZSNewCompactionTask {

  val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
  val POLL_BASE_URL_PARAMETER = "poll.base.url"
  val WORKER_ID_PARAMETER = "worker.id"
  val CLAIMED_BY_PARAMETER = "claimed.by"
  val LEASE_MS_PARAMETER = "lease.ms"
  val NO_TASK_INTERVAL_MS_PARAMETER = "no.task.interval.ms"
  val EXECUTE_ERROR_BACKOFF_MS_PARAMETER = "execute.error.backoff.ms"
  val REQUEST_TIMEOUT_MS_PARAMETER = "request.timeout.ms"
  val DONE_RETRY_MAX_PARAMETER = "done.retry.max"
  val DONE_RETRY_INTERVAL_MS_PARAMETER = "done.retry.interval.ms"
  val CALLBACK_FAILURE_BACKOFF_MS_PARAMETER = "callback.failure.backoff.ms"
  val ERR_RETRY_MAX_PARAMETER = "err.retry.max"
  val ERR_RETRY_INTERVAL_MS_PARAMETER = "err.retry.interval.ms"

  var pollBaseUrl = "http://127.0.0.1:8080"
  var workerId = "spark-worker"
  var leaseMs = 60000L
  var noTaskIntervalMs = 10000L
  var executeErrorBackoffMs = 300000L
  var requestTimeoutMs = 10000
  var doneRetryMax = 6
  var doneRetryIntervalMs = 10000L
  var callbackFailureBackoffMs = 60000L
  var errRetryMax = 3
  var errRetryIntervalMs = 10000L
  val jsonParser = new JsonParser()

  case class CompactionTask(tableId: String,
                            tableName: String,
                            tableNamespace: String,
                            isPartitionTable: Boolean,
                            partitionDesc: String,
                            version: Int,
                            claimToken: String)

  case class TaskExecutionResult(success: Boolean,
                                 errorMessage: String)

  case class ApiResult(httpCode: Int,
                       code: Int,
                       message: String,
                       data: Option[JsonObject],
                       bizCode: String)

  def main(args: Array[String]): Unit = {
    val parameter = ParametersTool.fromArgs(args)
    pollBaseUrl = parameter.get(POLL_BASE_URL_PARAMETER, pollBaseUrl).stripSuffix("/")
    workerId = parameter.get(WORKER_ID_PARAMETER, parameter.get(CLAIMED_BY_PARAMETER, workerId))
    leaseMs = parameter.getLong(LEASE_MS_PARAMETER, leaseMs)
    noTaskIntervalMs = parameter.getLong(NO_TASK_INTERVAL_MS_PARAMETER, noTaskIntervalMs)
    executeErrorBackoffMs = parameter.getLong(EXECUTE_ERROR_BACKOFF_MS_PARAMETER, executeErrorBackoffMs)
    requestTimeoutMs = parameter.getInt(REQUEST_TIMEOUT_MS_PARAMETER, requestTimeoutMs)
    doneRetryMax = parameter.getInt(DONE_RETRY_MAX_PARAMETER, doneRetryMax)
    doneRetryIntervalMs = parameter.getLong(DONE_RETRY_INTERVAL_MS_PARAMETER, doneRetryIntervalMs)
    callbackFailureBackoffMs = parameter.getLong(CALLBACK_FAILURE_BACKOFF_MS_PARAMETER, callbackFailureBackoffMs)
    errRetryMax = parameter.getInt(ERR_RETRY_MAX_PARAMETER, errRetryMax)
    errRetryIntervalMs = parameter.getLong(ERR_RETRY_INTERVAL_MS_PARAMETER, errRetryIntervalMs)

    val builder = SparkSession.builder()
      .config("spark.sql.parquet.mergeSchema", value = true)
      .config("spark.sql.parquet.filterPushdown", value = true)
      .config("spark.sql.extensions", "com.dmetasoul.lakesoul.sql.LakeSoulSparkSessionExtension")
      .config("spark.sql.catalog.lakesoul", classOf[LakeSoulCatalog].getName)
      .config(SQLConf.DEFAULT_CATALOG.key, LakeSoulCatalog.CATALOG_NAME)

    val spark = builder.getOrCreate()
    spark.sparkContext.setLogLevel("WARN")

    runPollingTaskLoop()
    spark.stop()
  }

  private def runPollingTaskLoop(): Unit = {
    println(s"========== ${dateFormat.format(new Date())} start UZS polling worker: baseUrl=$pollBaseUrl workerId=$workerId leaseMs=$leaseMs ==========")
    while (true) {
      try {
        pollOneTask() match {
          case None =>
            Thread.sleep(noTaskIntervalMs)
          case Some(task) =>
            val executionResult = executeCompactionTask(task)
            if (executionResult.success) {
              val doneOk = reportDoneWithRetry(task)
              if (!doneOk) {
                println(s"[WARN] ${dateFormat.format(new Date())} compaction success callback failed after retries, wait ${callbackFailureBackoffMs}ms")
                Thread.sleep(callbackFailureBackoffMs)
              }
            } else {
              reportErrWithRetry(task, executionResult.errorMessage)
              println(s"[WARN] ${dateFormat.format(new Date())} task execution failed, wait ${executeErrorBackoffMs}ms")
              Thread.sleep(executeErrorBackoffMs)
            }
        }
      } catch {
        case e: Exception =>
          println(s"[ERROR] ${dateFormat.format(new Date())} polling loop exception: ${e.getMessage}")
          Thread.sleep(noTaskIntervalMs)
      }
    }
  }

  private def pollOneTask(): Option[CompactionTask] = {
    val url = s"$pollBaseUrl/internal/tasks/compaction/claim?workerId=${urlEncode(workerId)}&leaseMs=$leaseMs"
    val result = executeHttp(url, "POST")

    if (result.httpCode != 200 || result.code != 0) {
      println(s"[WARN] ${dateFormat.format(new Date())} compaction claim failed http=${result.httpCode} code=${result.code} msg=${result.message}")
      None
    } else {
      result.data.flatMap(parseClaimedTask)
    }
  }

  private def executeCompactionTask(task: CompactionTask): TaskExecutionResult = {
    val taskInfo = s"tableId=${task.tableId}, namespace=${task.tableNamespace}, table=${task.tableName}, partition=${task.partitionDesc}, version=${task.version}, isPartitionTable=${task.isPartitionTable}"
    println(s"========== ${dateFormat.format(new Date())} start task: $taskInfo ==========")
    try {
      val table = LakeSoulTable.forName(task.tableName, task.tableNamespace)
      if (!task.isPartitionTable || isEmptyPartition(task.partitionDesc)) {
        table.uzsFullPartitionCompaction()
      } else {
        table.uzsFullPartitionCompaction(partitionDescToCondition(task.partitionDesc))
      }
      println(s"========== ${dateFormat.format(new Date())} finish task success: $taskInfo ==========")
      TaskExecutionResult(success = true, errorMessage = "")
    } catch {
      case e: Exception =>
        val errorMessage = Option(e.getMessage).getOrElse(e.getClass.getSimpleName)
        println(s"[ERROR] ${dateFormat.format(new Date())} task execute failed: $taskInfo, err=$errorMessage")
        TaskExecutionResult(success = false, errorMessage = errorMessage)
    }
  }

  private def reportDoneWithRetry(task: CompactionTask): Boolean = {
    var attempt = 1
    while (attempt <= doneRetryMax) {
      val result = reportTaskSuccess(task)
      if (isCallbackSuccess(result)) {
        return true
      }
      if (!isRetryableCallbackFailure(result)) {
        println(s"[ERROR] ${dateFormat.format(new Date())} compaction success callback non-retryable failed, http=${result.httpCode}, code=${result.code}, bizCode=${result.bizCode}, msg=${result.message}")
        return false
      }
      println(s"[WARN] ${dateFormat.format(new Date())} compaction success callback retry attempt=$attempt/$doneRetryMax, http=${result.httpCode}, code=${result.code}, msg=${result.message}")
      attempt += 1
      if (attempt <= doneRetryMax) {
        Thread.sleep(doneRetryIntervalMs)
      }
    }
    false
  }

  private def reportErrWithRetry(task: CompactionTask, errorMessage: String): Unit = {
    var attempt = 1
    while (attempt <= errRetryMax) {
      val result = reportTaskFailure(task, errorMessage)
      if (isCallbackSuccess(result)) {
        println(s"[INFO] ${dateFormat.format(new Date())} compaction failure callback success tableId=${task.tableId}, partition=${task.partitionDesc}, version=${task.version}")
        return
      }
      if (!isRetryableCallbackFailure(result)) {
        println(s"[ERROR] ${dateFormat.format(new Date())} compaction failure callback non-retryable failed, http=${result.httpCode}, code=${result.code}, bizCode=${result.bizCode}, msg=${result.message}")
        return
      }
      println(s"[WARN] ${dateFormat.format(new Date())} compaction failure callback retry attempt=$attempt/$errRetryMax, http=${result.httpCode}, code=${result.code}, msg=${result.message}")
      attempt += 1
      if (attempt <= errRetryMax) {
        Thread.sleep(errRetryIntervalMs)
      }
    }
  }

  private def reportTaskSuccess(task: CompactionTask): ApiResult = {
    val url =
      s"$pollBaseUrl/internal/tasks/compaction/success?tableId=${urlEncode(task.tableId)}&partitionDesc=${urlEncode(task.partitionDesc)}&claimToken=${urlEncode(task.claimToken)}"
    executeHttp(url, "POST")
  }

  private def reportTaskFailure(task: CompactionTask, errorMessage: String): ApiResult = {
    val url =
      s"$pollBaseUrl/internal/tasks/compaction/failure?tableId=${urlEncode(task.tableId)}&partitionDesc=${urlEncode(task.partitionDesc)}&claimToken=${urlEncode(task.claimToken)}&errorMessage=${urlEncode(errorMessage)}"
    executeHttp(url, "POST")
  }

  private def isCallbackSuccess(result: ApiResult): Boolean = {
    result.httpCode == 200 && result.code == 0
  }

  private def isRetryableCallbackFailure(result: ApiResult): Boolean = {
    if (result.httpCode == -1 || result.httpCode >= 500) {
      true
    } else if (result.httpCode == 409 || result.httpCode == 404 || result.httpCode == 400) {
      false
    } else {
      result.bizCode match {
        case "CLAIM_EXPIRED" | "CLAIM_MISMATCH" | "VERSION_MISMATCH" | "INVALID_STATE" | "NOT_FOUND" | "CONFLICT" =>
          false
        case _ =>
          result.code != 0
      }
    }
  }

  private def parseClaimedTask(claimResponse: JsonObject): Option[CompactionTask] = {
    val claimed =
      claimResponse.has("claimed") && !claimResponse.get("claimed").isJsonNull && claimResponse.get("claimed").getAsBoolean
    if (!claimed || !claimResponse.has("task") || claimResponse.get("task").isJsonNull || !claimResponse.get("task").isJsonObject) {
      None
    } else {
      Some(parseTask(claimResponse.getAsJsonObject("task")))
    }
  }

  private def parseTask(taskJson: JsonObject): CompactionTask = {
    def getString(key: String, default: String = ""): String = {
      if (taskJson.has(key) && !taskJson.get(key).isJsonNull) taskJson.get(key).getAsString else default
    }

    def getBoolean(key: String, default: Boolean = false): Boolean = {
      if (taskJson.has(key) && !taskJson.get(key).isJsonNull) taskJson.get(key).getAsBoolean else default
    }

    def getInt(key: String, default: Int = 0): Int = {
      if (taskJson.has(key) && !taskJson.get(key).isJsonNull) taskJson.get(key).getAsInt else default
    }

    CompactionTask(
      tableId = getString("tableId"),
      tableName = getString("tableName"),
      tableNamespace = getString("tableNamespace"),
      isPartitionTable = getBoolean("isPartitionTable"),
      partitionDesc = getString("partitionDesc"),
      version = getInt("runVersion", getInt("currentVersion")),
      claimToken = getString("claimToken")
    )
  }

  private def executeHttp(url: String, method: String): ApiResult = {
    var conn: HttpURLConnection = null
    try {
      conn = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod(method)
      conn.setConnectTimeout(requestTimeoutMs)
      conn.setReadTimeout(requestTimeoutMs)
      conn.setRequestProperty("Accept", "application/json")

      val httpCode = conn.getResponseCode
      val stream = if (httpCode >= 200 && httpCode < 400) conn.getInputStream else conn.getErrorStream
      val responseBody = readStream(stream)
      parseApiResult(httpCode, responseBody)
    } catch {
      case e: Exception =>
        ApiResult(-1, -1, Option(e.getMessage).getOrElse("unknown error"), None, "NETWORK_ERROR")
    } finally {
      if (conn != null) {
        conn.disconnect()
      }
    }
  }

  private def parseApiResult(httpCode: Int, responseBody: String): ApiResult = {
    try {
      val root = jsonParser.parse(responseBody).getAsJsonObject
      val hasEnvelope = root.has("code") || root.has("message") || root.has("data")
      val code =
        if (root.has("code") && !root.get("code").isJsonNull) root.get("code").getAsInt
        else if (httpCode >= 200 && httpCode < 300) 0
        else httpCode
      val message =
        if (root.has("message") && !root.get("message").isJsonNull) root.get("message").getAsString
        else if (root.has("errorMessage") && !root.get("errorMessage").isJsonNull) root.get("errorMessage").getAsString
        else ""
      val dataOpt =
        if (root.has("data") && root.get("data").isJsonObject) Some(root.getAsJsonObject("data"))
        else if (!hasEnvelope) Some(root)
        else None
      val bizCode =
        if (dataOpt.exists(obj => obj.has("code") && !obj.get("code").isJsonNull)) dataOpt.get.get("code").getAsString
        else ""
      ApiResult(httpCode, code, message, dataOpt, bizCode)
    } catch {
      case _: Exception =>
        ApiResult(httpCode, httpCode, responseBody, None, "")
    }
  }

  private def urlEncode(value: String): String = {
    URLEncoder.encode(Option(value).getOrElse(""), StandardCharsets.UTF_8.name())
  }

  private def readStream(stream: java.io.InputStream): String = {
    if (stream == null) {
      ""
    } else {
      val source = Source.fromInputStream(new BufferedInputStream(stream), "UTF-8")
      try {
        source.mkString
      } finally {
        source.close()
      }
    }
  }

  private def partitionDescToCondition(partitionDesc: String): String = {
    partitionDesc.split(",").map(part => {
      val pair = part.split("=", 2)
      val key = pair(0)
      val value = if (pair.length > 1) pair(1) else ""
      s"$key='${value.replace("'", "''")}'"
    }).mkString(" and ")
  }

  private def isEmptyPartition(partitionDesc: String): Boolean = {
    partitionDesc == null || partitionDesc.trim.isEmpty || partitionDesc == MetaUtils.DEFAULT_RANGE_PARTITION_VALUE
  }
}
