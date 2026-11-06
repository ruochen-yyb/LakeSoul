# MySQL CDC server-id 冲突问题分析

- 时间：2026-07-23 10:36
- 问题类型：mysql-cdc
- 涉及入口：`org.apache.flink.lakesoul.entry.JdbcCDC`

## 1. 错误现象

Flink 作业在 binlog 增量读取阶段报错，作业失败：

```
io.debezium.DebeziumException: A slave with the same server_uuid/server_id as this slave
has connected to the master ... Error code: 1236; SQLSTATE: HY000.
The 'server-id' in the mysql cdc connector should be globally unique, but conflicts happen now.
```

关键上下文：
- 冲突发生时 Last offset：`file=binlog.037029, pos=39731028, gtids=...:1-213735606, server_id=2`
- MySQL 错误码 1236 是主库主动断开复制连接的报错。

## 2. 根因分析

MySQL 复制协议要求每个 slave（binlog 客户端）的 `server-id` 在**整个主库范围内全局唯一**。
Flink CDC / Debezium 以 slave 身份连接 MySQL 拉取 binlog，报 1236 说明当前作业使用的
server-id 已被另一个活跃连接占用。

按错误提示，冲突来源有三种可能：

1. **同一作业内部冲突**：同一 job 中存在多张 mysql cdc 表，或多个并行 subtask 分到了相同的 server-id。
2. **跨作业冲突**：另一个 Flink 作业（或其他 LakeSoul JdbcCDC 作业）连接同一个 MySQL 主库，使用了相同的 server-id。
3. **外部工具冲突**：Canal、Debezium、DTS 等其他同步工具占用了该 server-id。

### 已确认信息（2026-07-23 补充）

**当前运行任务的 cdc yaml 中没有配置 `server-id`。**

此时 Flink CDC 3.5.0（pom 中 `cdc.version=3.5.0`）走默认行为：

- 默认 server-id 范围为 **`5400-6400`**；
- 增量框架下 binlog split 读取器使用的实际 server-id = **范围起始值 + subtaskId**，
  即并行度为 1 时固定使用 **5400**，并行度为 n 时依次使用 5400..5400+n-1；
- 该取值是**确定性的**，不随机、不与作业名关联。

因此，只要满足以下任一条件就必然 1236：

1. **另一个 CDC 作业**（同样未配置 server-id，或范围覆盖 5400..）连同一个 MySQL 主库，
   且 subtaskId 重叠 —— 两个默认配置的作业并行度相同时**必然冲突**；
2. **本作业重启/失败重试时，旧连接的 binlog dump 线程在 MySQL 侧尚未断开**，
   新连接以相同 server-id（5400+subtaskId）重连 —— 这是未配置 server-id 时最常见的触发场景；
3. 外部同步工具（Canal/DTS 等）占用了 5400 附近的 id。

结合"未配置 server-id"这一事实，**最可能是场景 1 或场景 2**。

## 3. 代码链路梳理

入口与配置读取链路：

```
JdbcCDC.mysqlCdc()
  └─ MySqlSource.builder() ...
  └─ JdbcSourceBuilderTool.mySqlSourceBuilder(cdcYamlPath, sourceBuilder)
        └─ 读取 yaml 中的 server-id → sourceBuilder.serverId(...)
  └─ sourceBuilder.build() → MySqlSource
```

关键代码位置：

- `lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcCDC.java:222-245`
  构建 MySqlSource，仅当提供了 cdc yaml 时才设置 server-id；否则**不显式设置**（使用 CDC 默认值）。
- `lakesoul-flink/src/main/java/org/apache/flink/lakesoul/entry/JdbcSourceBuilderTool.java:137-139` 与 `:178-181`
  两处重复判断 `SERVER_ID`，直接把 yaml 中的 `server-id` 原样传给 builder，**没有任何冲突检测或自动分配逻辑**。

风险点：

1. `JdbcSourceBuilderTool` 中 server-id 只是透传，未校验：
   - 是否为合法的 id 范围（如 `5400-6400`）；
   - 范围宽度是否 >= source 并行度（Flink CDC 增量框架下每个并行读取器会占用一个 id）。
2. `JdbcCDC` 在未提供 yaml 时不设置 server-id，完全依赖连接器默认值（5400-6400 区间随机），
   多作业并发时仍可能碰撞（概率较低但存在）。
3. 代码中 `SERVER_ID` 判断重复出现两次（137 行与 178 行），属冗余但非本次问题根因。

## 4. 排查建议（按优先级）

1. **确认当前使用的 server-id**：检查 JdbcCDC 提交时使用的 cdc yaml 中 `server-id` 配置值。
2. **查主库上的活跃复制连接**：
   ```sql
   SHOW PROCESSLIST;            -- 查看 Binlog Dump 线程
   SELECT * FROM performance_schema.replication_connection_configuration; -- 从库视角
   ```
   或在主库执行 `SHOW SLAVE HOSTS;` 查看已注册的 slave server-id。
3. **排查冲突来源**：
   - 是否有其他 Flink 作业 / Canal / DTS 连同一 MySQL 主库；
   - 同一作业是否有多个 mysql cdc source；
   - source 并行度是否与 server-id 范围匹配。
4. **检查作业是否反复重启**：旧作业未完全退出（如 checkpoint 恢复中、TaskManager 残留）
   时新作业以相同 server-id 重连，也会触发 1236。

## 5. 修复方案（待讨论）

### 方案 A：仅调整配置（不改代码，推荐先落地）
- 在 cdc yaml 中**显式配置 server-id**，为每个作业分配互不重叠的范围，例如：
  - 作业 1：`server-id: 5400-5409`
  - 作业 2：`server-id: 5410-5419`
- server-id 范围宽度 >= source 并行度。
- 注意：显式配置后**仍无法避免场景 2**（本作业重启时旧连接未释放），
  需配合方案 C 的重启间隔，或等待 MySQL 侧旧 dump 线程超时断开。

#### 方案 A 落地步骤（Dinky 提交 jar 任务场景）

`JdbcCDC` 通过程序参数 `--config_yaml_path` 指定 cdc yaml（key 定义见
`LakeSoulDDLSinkOptions.CONNFIG_YAML_PATH`，即 `config_yaml_path`），
yaml 通过 Flink `FileSystem` 读取，支持本地路径 / HDFS / S3。

1. 编写 cdc yaml（示例 `mysql-cdc.yaml`）：

   ```yaml
   server-id: 5410-5419        # 与其他作业不重叠的范围，宽度 >= source 并行度
   scan.startup.mode: initial  # 可选，按需
   heartbeat.interval: 30      # 可选，秒
   ```

2. 将 yaml 放到作业运行时可访问的位置（三选一）：
   - **HDFS/S3（推荐）**：`hdfs:///path/to/mysql-cdc.yaml`，JM/TM 都能读到，无需关心文件分发；
   - 集群所有节点相同的本地路径，如 `/opt/flink/conf/mysql-cdc.yaml`；
   - 若 Dinky 有资源中心（rs），可上传到资源中心后在提交参数中引用实际路径。

3. Dinky 任务配置（jar 任务 / Application 模式）：
   - 主类：`org.apache.flink.lakesoul.entry.JdbcCDC`
   - **程序参数（Program Args）中追加**：

     ```
     --config_yaml_path hdfs:///path/to/mysql-cdc.yaml
     ```

     （其他原有参数如 `--source_db_type mysql --source_db_db_name ...` 保持不变）

4. 保存并重新发布/重启任务。

验证：任务启动日志中不再出现 1236；或在 MySQL 主库执行
`SHOW PROCESSLIST;` 确认 Binlog Dump 线程对应的 server-id 落在配置范围内。

### 方案 B：代码增强（需改动项目文件）
- 在 `JdbcSourceBuilderTool.mySqlSourceBuilder` 中对 server-id 做校验：单值时若并行度 > 1 给出明确报错提示；
- 未配置 server-id 时，基于作业名/时间戳自动推导一个唯一 id 段，降低多作业碰撞概率；
- 清理 137/178 行重复的 server-id 判断。

### 方案 C：运维兜底
- 提供排查脚本/文档，说明如何定位 server-id 占用方；
- 作业重启策略增加重启间隔，避免旧连接未释放时立即重连。

## 6. 不确定性说明

- 无法确认冲突对端是谁（其他作业还是本作业旧连接），需在 MySQL 主库侧 `SHOW PROCESSLIST` 排查确认。
- 已确认未配置 server-id，实际连接用的 id 为默认值 `5400 + subtaskId`。
- 日志中 `server_id=2` 是 offset 中记录的 MySQL 主库 server_id，与冲突的 slave server-id 无关。
- 当前未知该作业的 source 并行度配置。

## 7. 下一步

请确认希望采用哪个方向：
1. 仅提供配置层面的修复建议（不改代码）；
2. 按方案 B 增强 `JdbcSourceBuilderTool` 的 server-id 处理逻辑（需改代码并编译验证）；
3. 先协助排查 MySQL 主库侧的占用来源。
