# 延迟轮询同步 ES 开发任务

> 基准文档：`延迟轮询同步ES设计文档.md`  
> 当前阶段：版本 A 开发，polling 保持关闭。  
> 更新日期：2026-07-16。

## 1. 开发约束

1. 先完成版本 A 的 T+1 安全断点、表模式兼容和部署基础能力，再开发并启用 polling。
2. 同一张表同一时期只能属于 `t-plus-one` 或 `polling` 一种自动同步模式。
3. 类、公共方法、关键并发状态和持久字段必须有准确注释；普通直观语句不重复解释。
4. 实现类保持流程集中，只有存在独立职责、复用价值或并发边界时才提取类或方法。
5. 配置类和纯 Model 可使用 Lombok；并发状态机、资源生命周期类优先显式编写关键方法。
6. 每个任务完成后执行定向测试；阶段完成后执行完整测试、故障恢复测试和配置兼容验证。

## 2. 版本 A：保持 T+1 行为，补齐安全基础

### A1. T+1 连续批次安全断点

状态：已完成。

- [x] Reader 批次增加单调 `sequence` 和批次 `lastId`。
- [x] Bulk Worker 以Reader批次为单位上报完成结果。
- [x] 取消按成功 item 最大 ID 推进 `last_success_id`。
- [x] 只按连续安全批次推进任务断点。
- [x] item失败尚未进入持久错误池时阻塞安全断点，后续批次不得越过。
- [x] 增加乱序完成、首批失败、中间批次失败测试。
- [x] 增加Bulk响应级测试，覆盖部分item失败和缺少文档ID。
- [x] 执行完整测试并复查通知、日志、任务恢复语义。

验收重点：低 ID 失败、高 ID 成功后触发 `TIMEOUT_PARTIAL` 或强停，恢复查询不得跳过低 ID。

### A2. 公共表定义与每表同步模式

状态：已完成。

- [x] 在现有 `sano.es.import.tables` 增加 `sync-mode`、`bootstrap-start-date`。
- [x] `sync-mode` 缺省为 `t-plus-one`，验证旧 YAML 行为不变。
- [x] 启动时转换为不可变 `SyncTableDefinition`，集中校验表名、Alias、Mapping和日期字段。
- [x] T+1 自动任务只处理 `t-plus-one` 表；当前手工历史回填入口也要求T+1归属，避免与未来polling并写。
- [x] 校验同一 Alias 不得出现重复启用配置。

### A3. 共享 ES 写入许可与内存预算

状态：已完成T+1接入；polling将在版本B复用同一资源层。

- [x] 实现公平 `GlobalEsWritePermitManager`。
- [x] 实现 `GlobalSyncMemoryLimiter`，明确预留、校准和终态释放时机。
- [x] T+1 Bulk 接入共享许可证和内存预算。
- [x] 许可证不依赖运行状态，drain只停止新读取，已有队列仍可申请许可证完成排空。

### A4. service-mode 与统一 drain

状态：已完成。

- [x] 支持`all / query`；两种模式均开放查询，query只关闭同步能力。
- [x] 三种模式注册相同Bean；运行时门禁使query模式不提交T+1/polling工作、不启动同步链路。
- [x] 实现 `/internal/sync/drain`、`status`、`cancel`。
- [x] T+1 在当前读取批次后停止，排空已入队Bulk并保存安全 `TIMEOUT_PARTIAL`。
- [x] cancel 立即重新投递本次 drain 产生的 `TIMEOUT_PARTIAL`，不能等待下一次 Cron。
- [x] 部署脚本增加 query-only 接管、版本A能力预检、严格就绪与真实查询冒烟。
- [x] drain失败或替换前中断执行cancel；替换后失败按不可变旧镜像标签回滚。
- [x] 增加部署锁、INT/TERM退出保护、Nginx接管确认及离线流程测试。

### A5. 版本 A 发布验收

状态：待发布环境验收。

- [ ] 所有表保持 `t-plus-one`，`polling.enabled=false`。
- [ ] 对比升级前后任务生成、Alias、索引清理和通知行为。
- [ ] 演练成功、部分失败、请求失败、超时、强停、cancel和回滚。
- [ ] 验证只有一个`all`实例启用同步，临时`query`实例不启动任何同步工作。

## 3. 版本 B：按表启用 polling

### B1. 内部索引与持久模型

- [ ] 创建 `sano_polling_sync_checkpoint`、`sano_sync_error`、`sano_sync_reconcile_task` Mapping。
- [ ] checkpoint 每张 polling 表固定一个文档 ID。
- [ ] 错误文档使用确定性 ID，重复重试只更新同一条记录。
- [ ] 明确未解决错误和已解决错误的保留策略。

### B2. 租约与单表状态写入器

- [ ] 实现实时 Get、OCC、owner、`lease_token` 和租约安全余量。
- [ ] 实现每表唯一 `TableStateWriter`，串行处理租约、进度、暂停和释放。
- [ ] Bulk、Reader、对账只能提交事件，不能直接更新 checkpoint。
- [ ] 旧 token 的迟到事件和 Bulk 回调必须丢弃。
- [ ] 增加续租与高频 checkpoint 并发测试，禁止正常409冲突风暴。

### B3. polling Reader 与日期流水线

- [ ] checkpoint 缺失时严格使用每表 `bootstrap-start-date`。
- [ ] 按 `sync_date + id` 读取，DATE和DATETIME分别构造查询条件。
- [ ] 每表独立Reader、有界队列和背压。
- [ ] 历史日期读空后创建 `DATE_CLOSE`，Reader立即进入下一日期。
- [ ] 今天读空时退避但不进入未来日期。

### B4. polling Bulk、重试与有序提交

- [ ] 每批固定 `target_index` 和 `(lease_token, sequence)`。
- [ ] 每表最多2个Bulk Worker，实际请求受共享许可证限制。
- [ ] 未提交sequence窗口默认最多8。
- [ ] 可重试item进入延迟重试队列，等待期间释放Worker和许可证。
- [ ] 单条永久错误先写 `sano_sync_error`，确认成功后标记 `TERMINAL_WITH_ERROR`。
- [ ] 系统性故障或错误池不可写时熔断暂停。

### B5. 统一异步对账与补偿

- [ ] T+1成功和polling日期关闭均创建确定性日期对账任务。
- [ ] 对账任务使用owner、租约token和OCC领取、续租、完成。
- [ ] 默认允许差异率 `0.001`，对账结果不阻塞下一日期。
- [ ] 分页执行MySQL到ES的ID核对和定向补偿。
- [ ] `last_reconciled_date` 防止旧日期结果覆盖新状态。

### B6. 索引保留、管理与可观测性

- [ ] T+1与polling共用历史索引保留服务。
- [ ] 活跃日期、未关闭日期和存在运行中补偿的日期不得删除。
- [ ] 状态接口分别展示持久业务状态、运行时状态、对账状态和drain结果。
- [ ] 增加队列、未提交窗口、租约、重试、错误池、对账和共享许可证指标。

## 4. 最终验收

- [ ] 至少一张 polling 表和一张 T+1 表混合运行数天。
- [ ] 验证双方不会为对方表创建任务或Reader。
- [ ] 模拟Bulk乱序、429、网络中断、item错误、错误池故障、租约过期和旧实例恢复。
- [ ] 验证日期D重试期间D+1可在窗口内继续执行，checkpoint不越序。
- [ ] 验证query-only接管和统一drain期间查询持续可用。
- [ ] 验证新版本失败后旧镜像可恢复同步，MySQL积压最终追平。
