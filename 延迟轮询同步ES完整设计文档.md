# es-server T+1 多周期索引同步完整设计文档

> 文件名沿用历史名称。当前源码已经移除基于 MySQL 轮询的 Polling 实现，本文只描述实际存在的 T+1 同步、查询服务和安全升级能力。

## 1. 当前范围

当前 `es-server` 提供以下能力：

- Elasticsearch 查询接口。
- 按业务日期执行的 T+1 MySQL 全量导入。
- DAY、MONTH、YEAR、SINGLE四种物理索引组织方式；现有业务表仍全部配置为DAY。
- SINGLE人工全量删除重建、暂停续跑及按配置日期字段执行的每日新增、修改同步。
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
- 具体直播、用户等新数据源及其业务查询接口，本次未接入。

如需实时同步，应单独设计基于 Outbox 或 Binlog CDC、MQ/Kafka 和幂等 ES 写入的链路，不在 T+1 任务中继续叠加数据库轮询。

## 2. 核心命名

| 名称 | 含义 | 示例 |
| --- | --- | --- |
| `table-name` | MySQL 源表名，也是同步表唯一标识 | `sano_wallet_coin_record` |
| `index-alias` | 业务查询 Alias；为空时使用 `table-name` | `sano_wallet_coin_record` |
| `index-name` | 本次任务写入的物理索引，由模式决定 | `sano_wallet_coin_record_20260917` |
| `index-mode` | DAY/MONTH/YEAR/SINGLE，默认DAY | `MONTH`使用`alias_yyyyMM` |
| `fixed-index-name` | SINGLE必填，不能与Alias同名 | `sample_v1` |
| `mapping-file` | `resources/esmapping` 下的索引定义文件 | `sano_wallet_coin_record.json` |
| `sano_import_task` | T+1 持久任务索引 | 每日为`tableName_yyyyMMdd`；Bootstrap为`tableName_BOOTSTRAP_fixedIndexName` |

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
| `modules.index` | 通用 ES 索引远程操作及统一物理索引命名 |
| `modules.notify` | Lark 通知通道能力 |
| `modules.reconcile` | 独立异步统计对账 |
| `modules.tplusone` | 任务、Reader、Bulk、索引编排、通知和导入主流程 |
| `modules.coordination` | T+1 Bulk 许可和安全排空 |
| `modules.search` | 业务查询实现 |

`EsIndexManager` 只提供索引存在性、创建、Alias 和设置等通用远程操作；T+1 的调用顺序与异常策略由 `TPlusOneIndexService` 和导入服务负责。

四种模式复用同一个T+1调度器、Reader、队列、Bulk及安全断点，不分别建立执行类。`IndexMode`是枚举，`EsIndexNameResolver`仅计算名称。

`DateColumnType`与`IndexMode`同处modules.config，分别定义DATE等值和DATETIME左闭右开日期查询方式，Reader及对账共用。MySQL TIMESTAMP字段也配置DATETIME，无需单独增加枚举值；YML仍使用DATE/DATETIME。

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
    # 未配置默认DAY，保持现有按日导入行为。
    index-mode: DAY
    table-name: sano_wallet_coin_record
    index-alias: sano_wallet_coin_record
    mapping-file: sano_wallet_coin_record.json
    reconcile: true
    delete-history-index: true
    reserve-days: 60
    id-column: id
    dt-column: dt
    dt-column-type: DATE
```

规则：

- 只校验和归集 `enabled=true` 的表。
- 启用表的 `table-name` 必须唯一。
- `index-alias` 为空时使用 `table-name`。
- `DATE` 使用日期等值条件。
- `DATETIME` 使用当天左闭右开的时间范围。
- 已移除where-sql，不支持配置任意SQL片段；日期与游标条件均由程序生成并绑定参数。
- `reconcile=false` 时，对账入口收到调用后直接跳过。
- 仅DAY、`delete-history-index=true`、`reserve-days > 0`同时满足时自动清理一个到期日索引；开关默认false，天数可空，其余情况永久保留。

### 5.4 索引模式和Alias

| 模式 | 物理名称 | Alias绑定 | 自动历史删除 |
| --- | --- | --- | --- |
| DAY | `alias_yyyyMMdd` | 新索引导入成功后绑定 | 满足开关和正数保留天数才删除 |
| MONTH | `alias_yyyyMM` | 创建时绑定，后续每日任务复用 | 不删除 |
| YEAR | `alias_yyyy` | 创建时绑定，后续每日任务复用 | 不删除 |
| SINGLE | 显式fixed-index-name | 人工重建时创建并绑定 | 不删除 |

模式只改变写入目标，普通任务仍每天执行。月、年和固定索引允许部分写入在刷新后被查询到，不保证整日或全量原子可见。按日补数只覆盖对应业务ID，不删除共享索引，也不会因重复提交自动重置已有SUCCESS或FAILED任务。

## 6. T+1 任务入口

### 6.1 定时入口

```text
@Scheduled
→ 判断 server-mode=all
→ 判断 sano.import.t-plus-one.enabled=true
→ 原子获取 dispatcher 令牌
→ 修复超过运行窗口的 RUNNING 任务
→ 为昨天和每张启用表创建 PENDING 任务
   → SINGLE的固定Bootstrap未SUCCESS时跳过该表，不自动初始化
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
| `POST /import/importHistoryDateRange?tableName=...&startDate=...&endDate=...` | MONTH/YEAR低频人工历史导入；后台逐日执行，不读写任务索引 |
| `POST /import/rebuildSingleIndex?tableName=...` | SINGLE人工全量删除重建；实际删除在后台执行 |

全表日期入口在异步dispatcher中创建任务；单表日期和SINGLE重建入口先持久化任务，再尝试启动后台扫描。已有dispatcher时不并发启动第二条执行链，已入队任务由当前或后续扫描继续处理。Bootstrap未成功的SINGLE每日任务在扫描中排除，避免空转和遮挡其他表任务；其无日期Bootstrap本身仍可被优先扫描。

### 6.3 SINGLE人工全量与每日增量

```text
POST人工提交已启用SINGLE表
→ 校验all、T+1开关、drain和同表活动任务
→ 创建或重置固定Bootstrap文档为PENDING，import_date=null
→ 后台串行登记活动任务
→ 删除固定索引（存在时），创建新索引并绑定Alias
→ 确认RUNNING持久化成功
→ 全表COUNT、按配置id-column游标分页，不加日期条件
→ 复用Reader/队列/Bulk/安全断点
→ SUCCESS后才允许该表每日增量
```

已SUCCESS或FAILED可人工重置同一固定文档再执行全量删除重建；PENDING、RUNNING、TIMEOUT_PARTIAL不允许重复重置。索引准备前宕机留下PENDING可重新准备；RUNNING残留恢复为TIMEOUT_PARTIAL后只续跑，不再次删除，即使安全断点为0也一样。准备期间已登记活动任务，drain不能提前返回安全排空。

源表为空也会完成空固定索引创建及Alias绑定。每日增量仅按配置dt-column/dt-column-type查询新增、修改，固定索引丢失时报该表失败，不自动补建或全量。MySQL物理删除不做自动同步，由运维人工全量重建清理ES残留；全量导入不是数据库一致性快照。

### 6.4 MONTH/YEAR无持久任务的人工历史初始化

`POST /import/importHistoryDateRange`接收已启用表名及`yyyyMMdd`起止日期，范围两端均包含。仅支持MONTH/YEAR；DAY继续使用每日任务，SINGLE继续使用原全量删除重建入口。

该入口不读取、创建、修复或更新`sano_import_task`。后台直接逐日调用现有T+1导入流水线：按单表`read-mode`读取当天数据，按日期创建或复用月/年索引，复用Bulk、内存预算及异常处理。所有日期从当天起点开始，不删除已有共享索引。历史入口不再逐日提交对账，仅整个请求范围成功完成后，调用`ReconcileStatisticsService.reconcileHistory`异步执行一次范围对账，由单表`reconcile`决定是否执行。

人工历史导入与普通任务共用当前实例串行门禁，忙碌或drain期间直接拒绝，不宣称持久排队。该低频入口不套用普通调度的每轮运行分钟限制，建议避开每日自动调度窗口，防止长时间占用执行器导致本轮自动调度跳过。

单日运行只在drain协调器登记内存身份，不出现在持久任务摘要中，也不进入cancel自动续跑列表。drain后不再开始下一天；当前日期仍遵守原流水线的安全停止边界。失败或停止后结束剩余日期，仅输出一份人工历史导入汇总通知，说明请求范围、最后完成日期和中断日期，不对未完成的整个范围对账。

全范围成功时先发送一份历史导入汇总，再异步提交一次历史范围对账。历史对账只核对请求起止日期内的总数，不查询、比较或通知最小/最大ID，不依赖单列或复合文档ID。MySQL按请求范围执行COUNT；ES刷新范围覆盖的物理月/年索引后，使用相同日期过滤执行一次Count查询，不能计入首尾月/年中范围外的数据。源数据为空而未创建的索引允许缺失，源端有数据却缺少索引时会表现为数量不匹配；刷新或计数存在失败分片时按对账失败处理。历史对账采用独立`HISTORY_RECONCILE_*`事件，中文通知包含起止日期及双方总数，不影响日常单日及SINGLE对账。MySQL范围内无数据仍按MYSQL_EMPTY通知并跳过ES，不视为匹配；对账或通知失败不修改导入成功结果。

所有初始化进度仅存于当前进程，强制退出时可能没有最终通知或最新日志。不提供启动恢复或cancel自动恢复；运维从中断日期重新调用，不确定位置时可重复提交原范围，依靠稳定文档ID覆盖。范围同步不是数据库一致性快照，不处理源表物理删除。

## 7. 持久任务状态

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已创建，等待执行 |
| `RUNNING` | 当前正在执行 |
| `TIMEOUT_PARTIAL` | 到达 deadline 或 drain 安全停止，可从断点续跑 |
| `SUCCESS` | 完整结束，Alias 已按业务规则绑定 |
| `FAILED` | 执行失败 |
| `CANCELLED` | 人工取消 |

普通任务开始前先持久化RUNNING；新Bootstrap先完成索引准备，再确认RUNNING，随后才能读取、写入。任务结束时先保存最终状态，再从drain协调器注销运行令牌；当前drain期间终态持久化无法确认时返回FAILED。没有进行中drain时发生的持久化异常按日志、通知及人工复核处理，不新增跨drain屏障。

启动新一轮调度时，超过 `max-run-minutes` 仍为 `RUNNING` 的残留任务会恢复为 `TIMEOUT_PARTIAL`，随后按安全断点续跑。

任务统计采用累计处理口径，不对续跑重放做去重：`total_count`为最近一次保存的源端同步范围总量；`success_count`、`failed_count`为已保存的累计处理成功/失败数量，包含重放，历史失败在后续修复后不扣减。因此成功累计数允许大于源总量，失败累计数不代表最终缺失文档数；请求重试不是每尝试一次就重复计数。失败通知将本次内存统计与已保存累计数分开展示。

`last_success_id`仍是独立的连续安全断点，不由累计计数推算。通知明确标记累计处理口径，不再使用“源总量减累计成功/失败”推算剩余数量；真实完整性由独立对账确认。保留原字段名和Mapping，不增加唯一文档去重统计。

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
→ DAY成功时绑定Alias；其他模式已在创建时绑定
→ 仅满足删除条件的DAY尝试清理一个历史索引
→ 持久化 SUCCESS 或 TIMEOUT_PARTIAL/FAILED
→ 发送通知
→ SUCCESS 后最佳努力提交异步对账
```

新建日索引不会提前绑定 Alias，防止查询读到半成品。只有完整成功且失败阈值允许时才绑定 Alias。

## 9. Reader、队列和内存

Reader 使用 `id > lastId ORDER BY id LIMIT batchSize` 分页，并叠加日期条件。每批数据在入队前取得 `TPlusOneMemoryLimiter` 额度，Bulk 完成或异常清理后归还。

SINGLE Bootstrap是唯一不叠加日期条件的读取场景，必须先通过固定任务身份及模式校验；每日SINGLE任务仍按配置日期字段查询，不在源码中写死update_time。JdbcTemplate每次查询结束后归还连接，队列和内存等待不持有查询连接。

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

对账验证MySQL与ES的：

- 总量；
- 最小 ID；
- 最大 ID。

DAY的MySQL按D日、ES按日索引整体统计；MONTH/YEAR两端均限制D日，ES总量、最小ID和最大ID请求使用同一日期过滤；SINGLE两端均按全表现状统计，不限定历史增量日期。

MySQL总量为0时通知MYSQL_EMPTY并跳过ES，不表示两端匹配；MySQL非空时先刷新目标物理索引再统计ES。结果通过公共NotifyService发送Lark消息。人工SINGLE对账无需date，其余模式仍要求yyyyMMdd日期；是否实际执行统一由单表reconcile决定。

## 12. 历史索引清理

历史清理由 `TPlusOneIndexService` 在单表单日任务结束时调用 `EsIndexManager` 完成，不存在额外的每日全表扫描服务。

- 仅`index-mode=DAY`、`delete-history-index=true`、非空`reserve-days > 0`同时满足才计算一个到期日索引。
- 其他模式、开关未配置/为false、天数未配置/小于等于0均永久保留，由运维手动维护；不因此启动报错。
- 存在性检查和删除失败均只记录日志，不覆盖已完成导入结果，不扫描整个Alias。
- SINGLE人工全量删除重建是明确的人工操作，不受自动历史清理开关限制。

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
| POST | `/import/rebuildSingleIndex` | SINGLE人工全量删除重建，参数tableName |
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
- MONTH/YEAR的对账日期字段也必须进入ES Mapping；SINGLE若需发现修改，配置日期字段必须反映源记录的修改。
- 现有金币、钻石等查询仍使用DAY索引优化；后续新模式业务查询按需求开发，不得直接复用每日索引名推导。
- 模式切换、旧任务/断点处置、Alias及版本回滚兼容性由运维技术人员控制，程序不自动迁移或增加回滚门禁。
- 已有 `sano_sync_polling_checkpoint` 仅为历史遗留数据，可在确认无需回退旧 Polling 版本后人工删除。
