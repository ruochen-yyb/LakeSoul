# uzs-lakesoul-flink-auto-transfer TODO

- [x] 明确实现边界：不做任务幂等/防重，不做优雅停机；只做领取、执行、done/err 回写。
- [x] 统一入口为 `UzsAutoTransfer`：串行轮询 `getTransferTask`，无任务短暂等待后继续。
- [x] 实现 SQL 模板安全渲染：白名单占位符、未知占位符拦截、禁止多语句/注释/DDL/危险关键字。
- [x] 实现任务执行流程：分区任务与全表任务参数校验、执行 SQL、失败分类处置。
- [x] 实现回写策略：成功 `setTaskDone`，失败 `setTaskErr`，并对 `setTaskDone` 失败做短退避重试。
- [x] 更新使用方式文档：新增运行参数和 `flink run` 示例。
- [x] 增加 Batch 执行与 LakeSoul Catalog 初始化（CREATE/USE CATALOG）。
- [x] 修复启动兼容性：移除 `executeSql("SET execution.runtime-mode = batch")`，避免 Flink 版本不支持 `SET` 导致启动失败。
- [x] 调整 SQL 组装：模板仅提供字段映射主体，不再要求模板显式提供 `WHERE` 与 `{{partition_desc}}`。
- [x] 实现分区条件自动拼装：将 `partition_desc` 按逗号与首个等号拆分，保持顺序拼接为 `AND` 条件。
- [x] 更新 README：补充分区/非分区任务的自动 `WHERE` 规则与 `partition_desc` 示例。
