# es-server T+1 同步完整设计文档

> 文件名沿用历史名称。当前源码已经移除基于 MySQL 轮询的 Polling 实现，本文只描述实际存在的 T+1 同步、查询服务和安全升级能力。

## 1. 当前范围

当前 `es-server` 提供以下能力：

- Elasticsearch 查询接口。
- 按业务日期执行的 T+1 MySQL 全量导入。
- 自动任务、人工单日、人工日期段和人工单表日期段导入。
- `sano_import_task` 持久任务状态和安全断点续跑。
- 单次任务 Reader、阻塞队列、多 Bulk Worker 和有序提交。
- T+1 写入内存预算与 ES Bulk 并发控制。
- 导入完成后的 Alias 绑定、历史索引清理、异步对账和通知。
- `all/query` 双实例职责模式。
- 部署 drain、取消 drain、任务恢复和 query 实例临时接管。

当前不包含：

- MySQL 延迟轮询同步。
- Polling checkpoint、Worker、日期关闭和跨天推进。
- Redis 租约或多同步实例竞争。
- MQ、Kafka、CDC 或其他实时同步消费者。
- `DAY/MONTH/YEAR/SINGLE` 多周期物理索引模式。

如需实时同步，应单独设计基于 Outbox 或 Binlog CDC、MQ/Kafka 和幂等 ES 写入的链路，不在 T+1 任务中继续叠加数据库轮询。

## 2. 核心命名

| 名称 | 含义 | 示例 |
| --- | --- | --- |
| `table-name` | MySQL 源表名，也是同步表唯一标识 | `sano_wallet_coin_record` |
| `index-alias` | 业务查询 Alias；为空时使用 `table-name` | `sano_wallet_coin_record` |
| `index-name` | 当前业务日期的物理索引 | `sano_wallet_coin_record_20260917` |
| `mapping-file` | `resources/esmapping` 下的索引定义文件 | `sano_wallet_coin_record.json` |
| `sano_import_task` | T+1 持久任务索引 | 文档 ID 为 `tableName_yyyyMMdd` |

当前只有 `sano_import_task` 是同步内部索引。历史环境中可能仍存在 `sano_sync_polling_checkpoint`，新版本不会读取、更新或自动删除它。

## 3. 服务模式

`sano.server-mode` 支持：

| 模式 | 查询接口 | T+1 调度、Reader 和 Bulk | 使用场景 |
| --- | --- | --- | --- |
| `all` | 开放 | 按 T+1 总开关决定 | 常驻主实例 |
| `query` | 开放 | 禁止执行 | 安全升级期间临时接管查询 |

两种模式注册相同 Bean，能力是否执行由 `EsServiceModeManager` 在运行入口判断。临时 query 实例不是另一套镜像，也不需要删除同步 Bean。

## 4. 代码模块

| 模块 | 职责 |
| --- | --- |
| `controller` | 查询、导入、就绪和 drain 接口 |
| `modules.config` | T+1、单表目录和服务模式配置 |
| `modules.index` | 通用 ES 索引远程操作 |
| `modules.notify` | Lark 通知通道能力 |
| `modules.reconcile` | 独立异步统计对账 |
| `modules.tplusone` | 任务、Reader、Bulk、索引编排、通知和导入主流程 |
| `modules.coordination` | T+1 Bulk 许可和安全排空 |
| `modules.search` | 业务查询实现 |

`EsIndexManager` 只提供索引存在性、创建、Alias 和设置等通用远程操作；T+1 的调用顺序与异常策略由 `TPlusOneIndexService` 和导入服务负责。

## 5. 配置模型

配置前缀为：

```yaml
sano:
  server-mode: all
  import:
    common:
    t-plus-one:
    tables:
```

### 5.1 公共配置

| 配置 | 作用 |
| --- | --- |
| `drain-timeout-seconds` | drain 等待 T+1 到达安全边界的最长时间 |
| `global-bulk-concurrency` | 当前实例在途 ES Bulk 总上限 |
| `t-plus-one-max-concurrency` | T+1 Bulk 并发上限 |

两个 Bulk 上限继续保留现有语义，实际 T+1 并发取两者中较小值。正式环境当前为 `min(5, 3)=3`，删除 Polling 后不会扩大并发。

### 5.2 T+1 配置

| 配置 | 作用 |
| --- | --- |
| `enabled` | T+1 定时和人工导入总开关 |
| `cron` | 每日任务 Cron |
| `max-run-minutes` | 一轮调度允许运行的最长分钟数 |
| `task-fetch-limit` | 每轮最多读取的待执行任务数 |
| `read-batch-size` | Reader 每批 MySQL 数据量 |
| `worker-count` | Bulk Worker 数量 |
| `queue-capacity` | Reader 与 Bulk 之间的批次队列容量 |
| `queue-max-bytes` | T+1 排队、在途和重试批次的内存预算 |
| `bulk-actions` | 单次 Bulk 最大文档数 |
| `bulk-size-mb` | 单次 Bulk 目标大小上限 |
| `retry-times` | Bulk 请求异常重试次数，不含首次 |
| `retry-interval` | Bulk 重试间隔，毫秒 |
| `max-failed-documents` | 允许的最大失败文档数 |
| `max-failure-rate` | 允许的最大失败率 |
| `disable-refresh` | 导入期间是否临时关闭自动 Refresh |
| `disable-replica` | 导入期间是否临时把副本调整为 0 |

### 5.3 单表配置

```yaml
tables:
  - enabled: true
    table-name: sano_wallet_coin_record
    index-alias: sano_wallet_coin_record
    mapping-file: sano_wallet_coin_record.json
    reconcile: true
    delete-history-index: true
    reserve-days: 60
    id-column: id
    dt-column: dt
    dt-column-type: DATE
    where-sql:
```

规则：

- 只校验和归集 `enabled=true` 的表。
- 启用表的 `table-name` 必须唯一。
- `index-alias` 为空时使用 `table-name`。
- `DATE` 使用日期等值条件。
- `DATETIME` 使用当天左闭右开的时间范围。
- `where-sql` 非空时使用明确配置的业务条件。
- `reconcile=false` 时，对账入口收到调用后直接跳过。
- `delete-history-index=true` 且导入完成后，按 `reserve-days` 清理目标历史索引。

## 6. T+1 任务入口

### 6.1 定时入口

```text
@Scheduled
→ 判断 server-mode=all
→ 判断 sano.import.t-plus-one.enabled=true
→ 原子获取 dispatcher 令牌
→ 修复超过运行窗口的 RUNNING 任务
→ 为昨天和每张启用表创建 PENDING 任务
→ 扫描 PENDING/TIMEOUT_PARTIAL 任务
→ 按任务顺序串行执行
```

任务使用 ES `create` 语义写入，已存在的 `tableName_yyyyMMdd` 不会被重复创建。

### 6.2 人工入口

| 接口 | 作用 |
| --- | --- |
| `/import/importAppointDay?date=yyyyMMdd` | 所有启用表的指定单日任务 |
| `/import/importDateRange?startDate=...&endDate=...` | 所有启用表的日期段任务 |
| `/import/importTableDateRange?tableName=...&startDate=...&endDate=...` | 指定表的日期段任务 |

人工入口先持久化任务，再尝试异步启动 dispatcher。dispatcher 已忙时，任务保留为 `PENDING`，由当前扫描或下一次定时扫描继续处理。

## 7. 持久任务状态

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已创建，等待执行 |
| `RUNNING` | 当前正在执行 |
| `TIMEOUT_PARTIAL` | 到达 deadline 或 drain 安全停止，可从断点续跑 |
| `SUCCESS` | 完整结束，Alias 已按业务规则绑定 |
| `FAILED` | 执行失败 |
| `CANCELLED` | 人工取消 |

任务开始前先持久化 `RUNNING`。任务结束时先保存最终状态，再从 drain 协调器注销运行令牌。终态持久化无法确认时，drain 必须返回 `FAILED`，不能谎报安全排空。

启动新一轮调度时，超过 `max-run-minutes` 仍为 `RUNNING` 的残留任务会恢复为 `TIMEOUT_PARTIAL`，随后按安全断点续跑。

## 8. 单表单日导入

```text
读取持久任务
→ 校验启用表配置
→ 生成 TPlusOneImportConfig
→ 任务持久化为 RUNNING
→ MySQL 统计总量
→ 无数据：任务正常完成，不创建空索引
→ 有数据：创建或复用未完成物理索引
→ 可选关闭 Refresh 和副本
→ 启动 Reader 与 Bulk Worker
→ 等待 Reader、队列和 Worker 收敛
→ 恢复索引设置
→ 判断失败文档数和失败率
→ 成功时绑定 Alias
→ 清理目标历史索引
→ 持久化 SUCCESS 或 TIMEOUT_PARTIAL/FAILED
→ 发送通知
→ SUCCESS 后最佳努力提交异步对账
```

新建日索引不会提前绑定 Alias，防止查询读到半成品。只有完整成功且失败阈值允许时才绑定 Alias。

## 9. Reader、队列和内存

Reader 使用 `id > lastId ORDER BY id LIMIT batchSize` 分页，并叠加日期条件。每批数据在入队前取得 `TPlusOneMemoryLimiter` 额度，Bulk 完成或异常清理后归还。

内存预算只约束 T+1。申请额度不足时等待，避免队列和重试批次无上限占用堆内存。drain 完成条件会检查所有额度均已归还。

金币表当前保留：

```sql
FORCE INDEX (idx_polling_dt_id)
```

这是已存在的 MySQL 物理索引名称，虽然名称包含 `polling`，但当前 T+1 金币表查询仍依赖它，不能因移除 Polling 代码而删除。

## 10. Bulk、有序提交和安全断点

多个 Bulk Worker 可以并发处理不同 sequence。`ImportContext` 只把从 1 开始连续完成的 sequence 提交为安全进度：

```text
sequence 101 成功，102 成功，103 未完成，104 成功
→ 安全断点只能推进到 102
→ 103 完成后才能继续提交 103、104
```

Bulk 请求在发送前获取 `GlobalEsWritePermitManager` 许可，并在 `try-with-resources` 中归还。请求级异常按 T+1 配置重试；响应中的单文档失败会记录失败 ID 和统计。

deadline 或 drain 到达时，Reader 不再读取新页，但已经读取并入队的数据必须完成 Bulk。最终保存的是连续成功批次对应的 `lastSuccessId`，下次从该 ID 之后继续，允许安全重放但不能跳过未完成批次。

## 11. 对账和通知

`ReconcileStatisticsService` 是独立异步能力，不参与任务成功条件。T+1 任务先持久化 `SUCCESS`，再最佳努力提交对账；提交或执行失败不会反向修改任务终态。

当前对账按业务日期验证 MySQL 与 ES 的：

- 总量；
- 最小 ID；
- 最大 ID。

对账前主动刷新目标日索引。结果通过公共 `NotifyService` 发送 Lark 消息。

## 12. 历史索引清理

历史清理由 `TPlusOneIndexService` 在单表单日任务结束时调用 `EsIndexManager` 完成，不存在额外的每日全表扫描服务。

- `delete-history-index=false`：不删除。
- `delete-history-index=true`：根据当前导入日期和 `reserve-days` 计算一个目标物理索引并最佳努力删除。
- 删除失败记录日志，不覆盖已经完成的导入结果。

## 13. drain 与优雅升级

### 13.1 启动 drain

```text
POST /internal/sync/drain
→ 协调器切换为 DRAINING
→ 拒绝新的定时、人工 dispatcher 和任务启动
→ 活动 Reader 收到 drain stopOperationId
→ Reader 在批次边界停止读取
→ 已读队列和在途 Bulk 继续完成
→ 保存 TIMEOUT_PARTIAL 和安全 lastSuccessId
→ dispatcher 退出
→ 等待 Bulk 许可和内存额度全部归还
→ 返回 DRAINED 或 DRAINED_WITH_ERRORS
```

### 13.2 取消 drain

`POST /internal/sync/drain/cancel` 必须匹配当前 `operationId`。取消后协调器恢复 `RUNNING`，并只重投本次 drain 产生的 `TIMEOUT_PARTIAL` 任务。

### 13.3 部署脚本依赖字段

`/internal/sync/drain/status` 必须持续返回：

- `serviceMode`
- `coordinatorState`
- `drainResult`
- `operationId`

部署脚本只在 `DRAINED` 或 `DRAINED_WITH_ERRORS` 后停止旧主实例。`FAILED`、超时、接口不可用或操作 ID 变化都会取消部署并保留或恢复旧实例。

## 14. 就绪检查

`/health` 只表示 Web 进程存活。

`/ready` 验证：

- all/query 都能访问 ES；
- 存在启用表时，第一张表的业务 Alias 可执行 `size=0` 查询；
- `all` 且 T+1 开启时，`sano_import_task` 已存在；
- `query` 不要求同步任务索引之外的执行状态。

当前 `/ready` 不检查 Polling checkpoint、Coordinator 或 Worker。

## 15. 同步管理接口

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/import/createImportTaskIndex` | 创建 `sano_import_task` |
| GET | `/import/importAppointDay` | 人工单日导入 |
| GET | `/import/importDateRange` | 人工日期段导入 |
| GET | `/import/importTableDateRange` | 人工单表日期段导入 |
| GET | `/import/reconcile` | 人工异步对账 |
| POST | `/internal/sync/drain` | 启动安全排空 |
| GET | `/internal/sync/drain/status` | 查询排空和 T+1 运行状态 |
| POST | `/internal/sync/drain/cancel` | 取消排空并恢复任务 |

Polling 初始化、暂停、恢复、历史修复和 checkpoint 查询接口已经移除。

## 16. 日志

当前日志文件包括：

- `${APP_NAME}.<序号>.log`
- `${APP_NAME}-error.<序号>.log`
- `${APP_NAME}-import.<序号>.log`
- `${APP_NAME}-import-error.<序号>.log`
- `${APP_NAME}-search-api.<序号>.log`

日志按 JVM/容器当前日期进入 `yyyyMMdd` 子目录。Polling 专项日志已经移除。

## 17. 运行约束

- 当前只允许一个常驻 `all` 实例；安全升级期间额外实例必须为 `query`。
- 多个 `all` 实例没有 Redis/数据库分布式锁保护，可能重复执行同一任务。
- 启用 T+1 前必须创建 `sano_import_task`。
- 严格 `/ready` 还要求至少一张启用表的 Alias 已存在；空 ES 首次部署需要先完成初始化。
- `id-column` 必须能够转换为递增的 `long`。
- MySQL 日期条件必须有可用索引；金币表继续使用 `idx_polling_dt_id`。
- 已有 `sano_sync_polling_checkpoint` 仅为历史遗留数据，可在确认无需回退旧 Polling 版本后人工删除。
