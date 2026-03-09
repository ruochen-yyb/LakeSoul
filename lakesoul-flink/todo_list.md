# uzs-lakesoul-flink-auto-transfer TODO

- [x] 明确实现边界：不做任务幂等/防重，不做优雅停机；只做领取、执行、done/err 回写。
- [x] 统一入口为 `UzsAutoTransfer`：串行轮询 `getTransferTask`，无任务短暂等待后继续。
- [x] 实现 SQL 模板安全渲染：白名单占位符、未知占位符拦截、禁止多语句/注释/DDL/危险关键字。
- [x] 实现任务执行流程：分区任务与全表任务参数校验、执行 SQL、失败分类处置。
- [x] 实现回写策略：成功 `setTaskDone`，失败 `setTaskErr`，并对 `setTaskDone` 失败做短退避重试。
- [x] 更新使用方式文档：新增运行参数和 `flink run` 示例。
- [x] 增加 Batch 执行与 LakeSoul Catalog 初始化（CREATE/USE CATALOG）。
