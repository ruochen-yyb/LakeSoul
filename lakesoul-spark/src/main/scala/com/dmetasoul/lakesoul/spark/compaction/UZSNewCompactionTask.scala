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

import java.io.{BufferedInputStream, DataOutputStream}
import java.net.{HttpURLConnection, URL, URLEncoder}
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import scala.io.Source

object UZSNewCompactionTask {

  val dateFormat: SimpleDateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss")
  val CLAIM_TYPE_SPARK = "SPARK"
  val POLL_BASE_URL_PARAMETER = "poll.base.url"
  val CLAIMED_BY_PARAMETER = "claimed.by"
  val NO_TASK_INTERVAL_MS_PARAMETER = "no.task.interval.ms"
  val EXECUTE_ERROR_BACKOFF_MS_PARAMETER = "execute.error.backoff.ms"
  val REQUEST_TIMEOUT_MS_PARAMETER = "request.timeout.ms"
  val DONE_RETRY_MAX_PARAMETER = "done.retry.max"
  val DONE_RETRY_INTERVAL_MS_PARAMETER = "done.retry.interval.ms"
  val CALLBACK_FAILURE_BACKOFF_MS_PARAMETER = "callback.failure.backoff.ms"
  val ERR_RETRY_MAX_PARAMETER = "err.retry.max"
  val ERR_RETRY_INTERVAL_MS_PARAMETER = "err.retry.interval.ms"

  var pollBaseUrl = "http://127.0.0.1:8080"
  var claimedBy = "spark-worker"
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
                            version: Int)

  case class ApiResult(httpCode: Int,
                       code: Int,
                       message: String,
                       data: Option[JsonObject],
                       bizCode: String)

  def main(args: Array[String]): Unit = {
    val parameter = ParametersTool.fromArgs(args)
    pollBaseUrl = parameter.get(POLL_BASE_URL_PARAMETER, pollBaseUrl).stripSuffix("/")
    claimedBy = parameter.get(CLAIMED_BY_PARAMETER, claimedBy)
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
    println(s"========== ${dateFormat.format(new Date())} start UZS polling worker: baseUrl=$pollBaseUrl claimedBy=$claimedBy ==========")
    while (true) {
      try {
        pollOneTask() match {
          case None =>
            Thread.sleep(noTaskIntervalMs)
          case Some(task) =>
            if (executeCompactionTask(task)) {
              val doneOk = reportDoneWithRetry(task)
              if (!doneOk) {
                println(s"[WARN] ${dateFormat.format(new Date())} setTaskDone failed after retries, wait ${callbackFailureBackoffMs}ms")
                Thread.sleep(callbackFailureBackoffMs)
              }
            } else {
              reportErrWithRetry(task)
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
    val encodedClaimedBy = URLEncoder.encode(claimedBy, StandardCharsets.UTF_8.name())
    val url = s"$pollBaseUrl/getCompactionTask?claimedBy=$encodedClaimedBy"
    val result = executeHttp(url, "GET", None)

    if (result.code != 0) {
      println(s"[WARN] ${dateFormat.format(new Date())} getCompactionTask failed http=${result.httpCode} code=${result.code} msg=${result.message}")
      None
    } else {
      result.data.map(parseTask)
    }
  }

  private def executeCompactionTask(task: CompactionTask): Boolean = {
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
      true
    } catch {
      case e: Exception =>
        println(s"[ERROR] ${dateFormat.format(new Date())} task execute failed: $taskInfo, err=${e.getMessage}")
        false
    }
  }

  private def reportDoneWithRetry(task: CompactionTask): Boolean = {
    var attempt = 1
    while (attempt <= doneRetryMax) {
      val result = reportTask(task, "setTaskDone")
      if (isCallbackSuccess(result)) {
        return true
      }
      if (!isRetryableCallbackFailure(result)) {
        println(s"[ERROR] ${dateFormat.format(new Date())} setTaskDone non-retryable failed, http=${result.httpCode}, code=${result.code}, bizCode=${result.bizCode}, msg=${result.message}")
        return false
      }
      println(s"[WARN] ${dateFormat.format(new Date())} setTaskDone retry attempt=$attempt/$doneRetryMax, http=${result.httpCode}, code=${result.code}, msg=${result.message}")
      attempt += 1
      if (attempt <= doneRetryMax) {
        Thread.sleep(doneRetryIntervalMs)
      }
    }
    false
  }

  private def reportErrWithRetry(task: CompactionTask): Unit = {
    var attempt = 1
    while (attempt <= errRetryMax) {
      val result = reportTask(task, "setTaskErr")
      if (isCallbackSuccess(result)) {
        println(s"[INFO] ${dateFormat.format(new Date())} setTaskErr success tableId=${task.tableId}, partition=${task.partitionDesc}, version=${task.version}")
        return
      }
      if (!isRetryableCallbackFailure(result)) {
        println(s"[ERROR] ${dateFormat.format(new Date())} setTaskErr non-retryable failed, http=${result.httpCode}, code=${result.code}, bizCode=${result.bizCode}, msg=${result.message}")
        return
      }
      println(s"[WARN] ${dateFormat.format(new Date())} setTaskErr retry attempt=$attempt/$errRetryMax, http=${result.httpCode}, code=${result.code}, msg=${result.message}")
      attempt += 1
      if (attempt <= errRetryMax) {
        Thread.sleep(errRetryIntervalMs)
      }
    }
  }

  private def reportTask(task: CompactionTask, endpoint: String): ApiResult = {
    val payload = new JsonObject()
    payload.addProperty("claimType", CLAIM_TYPE_SPARK)
    payload.addProperty("tableId", task.tableId)
    payload.addProperty("partitionDesc", task.partitionDesc)
    payload.addProperty("version", task.version)
    val url = s"$pollBaseUrl/$endpoint"
    executeHttp(url, "POST", Some(payload.toString))
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
      version = getInt("version")
    )
  }

  private def executeHttp(url: String, method: String, body: Option[String]): ApiResult = {
    var conn: HttpURLConnection = null
    try {
      conn = new URL(url).openConnection().asInstanceOf[HttpURLConnection]
      conn.setRequestMethod(method)
      conn.setConnectTimeout(requestTimeoutMs)
      conn.setReadTimeout(requestTimeoutMs)
      conn.setRequestProperty("Accept", "application/json")

      body.foreach(payload => {
        conn.setDoOutput(true)
        conn.setRequestProperty("Content-Type", "application/json")
        val output = new DataOutputStream(conn.getOutputStream)
        output.write(payload.getBytes(StandardCharsets.UTF_8))
        output.flush()
        output.close()
      })

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
      val code = if (root.has("code") && !root.get("code").isJsonNull) root.get("code").getAsInt else httpCode
      val message = if (root.has("message") && !root.get("message").isJsonNull) root.get("message").getAsString else ""
      val dataOpt =
        if (root.has("data") && root.get("data").isJsonObject) Some(root.getAsJsonObject("data"))
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
