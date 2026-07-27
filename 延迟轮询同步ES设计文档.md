# 延迟轮询同步 ES 设计文档

> **归档说明：本文记录已放弃的旧版本 B 架构，不再作为当前开发依据。**
> 当前实现以《延迟轮询同步ES简化版设计.md》为准；本文中的队列、并行 Bulk、
> Sequence、有序状态写入器、ES 租约、错误池和持久化对账任务均未采用。
>
> 状态：基础能力版本A已完成并上线；polling版本B待开发且保持关闭。修订日期：2026-07-22。

## 1. 目标与适用范围

本方案是在不接入 MQ 的前提下，将当前 T+1 全量导入升级为“低延迟持续同步”：`es-server` 启动并完成预加载后，持续按表轮询 MySQL 新增记录，以秒级到分钟级延迟写入 Elasticsearch。

适用前提：

1. 每张同步表都有稳定、单调递增且不会复用的数值 `id`。
2. 新记录的 `id` 大于已同步记录的 `id`。
3. 同步对象是只新增、不硬删除的流水记录；若以后存在原记录更新或晚到低 ID 写入，必须额外设计 `update_time` 或补偿扫描，单纯按 ID 递增无法覆盖。
4. 业务表已有 `dt,id` 或等价查询索引，避免轮询全表扫描。

该方案不提供 MQ 的天然消息保留、消费组与事务 Outbox 能力，但对当前最多 3 至 5 张流水表、可接受几秒延迟的业务，实施与运维复杂度明显更低。

### 1.1 当前实现基线与上线结论

截至本文本次修订，基础能力版本A已完成编码并上线：T+1连续批次安全断点、每表同步模式、共享写入资源、运行时服务角色、统一drain以及query-only部署脚本均已实现；polling引擎、checkpoint、错误池和对账仍未编码。因此：

1. 版本A已经完成真实发布环境验收，正式环境常驻`all`实例继续运行T+1同步，后续更新使用safe部署流程。
2. polling版本B及其验收完成前不得配置任何`sync-mode=polling`表。
3. 完成版本 A、B 及第14章验收后，T+1 表和 polling 表可以在同一实例优雅共存、统一 drain 和恢复；同一张表仍只允许一种自动同步模式。

## 2. 总体架构

```text
MySQL 同步表
    |
    | 每张表按 sync_date + last_read_id 查询下一页
    v
轮询读取器（每表一个逻辑任务）
    |
    | 有界批次队列，按表隔离
    v
每表独立 Bulk Workers
    |
    | 发送前申请公平全局 Bulk 许可证
    v
全局 Bulk 并发控制（初始 3）
    |
    | 成功后才推进 checkpoint
    v
Elasticsearch 每日物理索引
alias_yyyyMMdd

Polling同步状态索引 sano_sync_polling_checkpoint
公共错误索引 / 错误日志 sano_sync_error
异步对账任务索引 sano_sync_reconcile_task
    |
    v
Lark 告警与日期完成对账通知
```

核心原则：

1. **读取到数据不等于达到可恢复终态。** 持久化的 `last_committed_date + last_committed_id` 只能在对应批次全部成功，或失败项已可靠进入错误池后推进。
2. **每张表独立背压。** 一张表 ES 写慢或失败，不能填满全局内存并拖垮其他表。
3. **读取和写入可以重叠，checkpoint 必须有序。** 每表允许少量 in-flight 批次；后批即使先完成，也只能等前批完成后连续推进 checkpoint。
4. **任何失败都持久化状态。** JVM 重启后从最后已提交 checkpoint 重读，重复写依赖 ES `_id=id` 保持幂等。
5. **日期关闭只阻塞 checkpoint，不阻塞 Reader。** Reader 可以在内存中进入下一日期并继续投递批次，旧日期的关闭标记负责约束持久进度顺序。
6. **同一张表只有一个持久状态写入者。** 租约获取、续租、checkpoint 推进、暂停和释放都由该表的串行状态写入器执行，Bulk 回调只上报结果，不能并发更新 checkpoint 文档。
7. **允许跨批并行，但未提交窗口必须有界。** 早期 sequence 阻塞时，后续批次最多领先固定数量，防止完成结果、重试上下文和重放范围无限增长。

## 3. 启动与首次初始化

### 3.1 首次启动定位规则

每张 `sync-mode=polling` 的表必须配置首次启动日期，避免 checkpoint 不存在时从 MySQL 最早历史日期开始重放多年数据：

```yaml
sano:
  import:
    tables:
      - table-name: sano_wallet_coin_record
        index-alias: sano_wallet_coin_record
        sync-mode: polling
        bootstrap-start-date: 2026-07-16
```

启动定位遵循以下固定优先级：

1. 能通过实时 Get 取得有效 checkpoint 时，永远从 `last_committed_date + last_committed_id` 恢复，忽略 `bootstrap-start-date`，也不根据 ES 最大索引重新推断进度。
2. checkpoint 不存在时，读取该表的 `bootstrap-start-date`。未配置、格式错误或晚于今天时，持久状态设为 `NEEDS_BOOTSTRAP`，不启动 Reader，并发送提醒。
3. 使用与正式同步完全相同的日期和过滤条件，查询启动日期 `D` 的 `MIN(id)`。
4. 如果 `MIN(id)=M`，初始化恢复位置为 `last_committed_date=D`、`last_committed_id=max(M-1, 0)`，保证正式查询 `id > last_committed_id` 时包含最小 ID。当前方案要求业务 ID 为正数。
5. 如果 `D` 没有数据，初始化为 `last_committed_date=D`、`last_committed_id=0`：`D < 今天` 时进入标准日期关闭协议，`D = 今天` 时停留在当天等待新数据。
6. 初始化 checkpoint 成功后才把持久运行状态从 `NEEDS_BOOTSTRAP` 改为 `RUNNING`。该初始化对每张表只执行一次。

checkpoint 缺失时不再自动采用 ES 最大日期或最大 ID。即使目标索引已有数据，从配置日期重新读取也会使用相同 `_id=id` 幂等覆盖，不会产生重复文档；代价是可能重复写入，因此生产切换时应合理选择启动日期。

### 3.2 启动顺序

```text
Spring 应用启动
    -> ES、MySQL、Mapping、同步配置预加载完成
    -> 加载公共表目录并按sync-mode分组
    -> 仅为polling表读取checkpoint
    -> checkpoint不存在时按每表bootstrap-start-date初始化
    -> 仅调度持久运行状态为RUNNING的表
    -> 竞争并获取每表同步租约
    -> 启动每表独立队列与 Bulk Workers
    -> 初始化公平全局 Bulk 许可证
    -> 恢复PENDING及过期RUNNING对账任务，启动异步对账Worker
```

不要在 Bean 构造阶段启动无限循环。应在应用 Ready 后启动，并在关闭阶段先停止拉取、排空队列、保存状态后退出。同步租约用于防止误启动两个同步实例时重复运行同一张表。

### 3.3 状态分层及对应关系

状态必须分层保存，禁止使用同一个 `status` 同时表达业务暂停、进程生命周期和 drain 结果。

**第一层：持久业务、进度与一致性状态**

不同引擎沿用各自适合的持久模型，管理接口可以聚合展示，但不能把它们覆盖成一个通用 `status`：

- 表定义中的 `enabled + sync-mode` 是持久业务归属，决定该表由哪个引擎自动同步。
- polling 表的连续进度、租约和运行许可保存在 `sano_sync_polling_checkpoint`，字段如下。
- T+1 每次日期任务的执行进度保存在现有 `sano_import_task`，继续使用 `PENDING / RUNNING / SUCCESS / TIMEOUT_PARTIAL / FAILED`；它没有 polling 的表租约和 checkpoint，也不能用一次任务状态覆盖表模式。
- 两种引擎的日期一致性结果统一保存在 `sano_sync_reconcile_task`。polling checkpoint 可缓存最近一致性摘要，T+1 表则由状态接口读取最近对账任务；对账任务状态不改写同步任务状态。

| 字段 | 状态 | 含义 |
| --- | --- | --- |
| `sync_status` | `NEEDS_BOOTSTRAP` | checkpoint 尚未完成首次初始化，不调度 |
| `sync_status` | `RUNNING` | 允许实例获取租约并持续同步 |
| `sync_status` | `PAUSED` | 系统性 ES/索引故障、错误记录无法可靠落盘、租约安全条件不满足或人工暂停，不再读取和提交新 Bulk |
| `consistency_status` | `UNKNOWN` | 尚未完成过日期对账 |
| `consistency_status` | `HEALTHY` | 最近完成的日期对账完全一致 |
| `consistency_status` | `ACCEPTABLE` | 存在差异，但差异率未超过允许阈值 |
| `consistency_status` | `DEGRADED` | 差异率超过阈值或对账持续执行失败，需要告警和补偿 |

对账是旁路一致性检查，不控制 Reader 日期推进。`consistency_status` 为 `ACCEPTABLE` 或 `DEGRADED` 时，`sync_status` 默认仍保持 `RUNNING`；差异只触发记录、告警和异步补偿。单条 Mapping/业务数据错误在成功写入错误池后也保持 `RUNNING + DEGRADED`。只有系统性写入故障、错误记录无法可靠持久化、租约安全条件不满足或人工操作才把 `sync_status` 改为 `PAUSED`。

**第二层：当前实例的协调器和表运行状态**

- 协调器状态：`STARTING / RUNNING / DRAINING / DRAINED / FAILED / STOPPED`。
- 表运行状态：`WAITING_BOOTSTRAP / WAITING_LEASE / IDLE / READING / WRITING / PAUSED / STOPPED`。
- 日期流水线状态：`OPEN / READ_COMPLETE / CLOSING / CLOSED`。同一张表可以同时表现为“Reader 正在读取 `D+1`，而日期 `D` 仍处于 `CLOSING`”，两者不能合并为单一表状态。
- 这些状态只描述当前 JVM，不覆盖 checkpoint 中的持久业务状态。进程重启后根据 checkpoint 和租约重新计算。

**第三层：一次 drain 操作的结果**

- 全局 drain：`DRAINING / DRAINED / DRAINED_WITH_ERRORS / FAILED / CANCELLED`。
- 每表 drain：`PENDING / DRAINING / DRAINED / PAUSED_SAFE / FAILED`。
- 正常表 drain 完成时仍保持持久 `sync_status=RUNNING`，新实例获取租约后自动续跑。
- 表在 drain 中遇到系统性故障且重试耗尽时，持久状态改为 `PAUSED`，该次每表 drain 结果为 `PAUSED_SAFE`；单条数据错误成功进入错误池后仍可正常排空。只要其余资源均安全归零，全局结果为 `DRAINED_WITH_ERRORS`。

polling 表的主要对应关系如下；T+1 表在同一接口中返回原生任务状态、当前 JVM 执行状态和本次 drain 结果，不强行映射为 polling 的 `sync_status`：

| 持久业务状态 | 协调器状态 | 表运行状态 | drain结果 |
| --- | --- | --- | --- |
| `NEEDS_BOOTSTRAP` | `RUNNING` | `WAITING_BOOTSTRAP` | 无在途任务时视为 `DRAINED` |
| `RUNNING` | `RUNNING` | `WAITING_LEASE / IDLE / READING / WRITING`；可同时存在旧日期 `CLOSING` | 未启动 drain |
| `RUNNING` | `DRAINING` | `STOPPED` 前逐步排空 | `DRAINED` |
| `RUNNING + DEGRADED` | `RUNNING` | 继续正常读取和写入 | 对账告警与补偿独立执行 |
| `PAUSED` | 任意 | `PAUSED` | 排空后为 `PAUSED_SAFE`；不允许通过 drain 自动恢复 |

### 3.4 从 T+1 模式切换到延迟轮询

不要求代码自动处理旧 `sano_import_task`，切换由运维流程保证原 T+1 任务已经全部执行完成，并保证同一张表在任一时刻只有一种写入链路。

推荐切换顺序：

1. 等待目标表所有 T+1 任务完成，确认没有 `PENDING`、`RUNNING` 或 `TIMEOUT_PARTIAL` 任务。
2. 停止 T+1 调度，不再创建新任务；确认当前没有正在执行的全量 Bulk。
3. 为每张表设置 `bootstrap-start-date`。若业务保证不会补写旧日期，建议设置为“最后一个已完成 T+1 业务日期的下一天”，通常就是切换当天。
4. 启用延迟轮询。由于没有 checkpoint，Reader 从启动日期的最小 ID 开始，自动追赶切换窗口内积压在 MySQL 的数据。
5. 第一批追平后检查 checkpoint、MySQL/ES 数量及最大 ID，再确认切换完成。

如果不能完全保证最后一个 T+1 日期不会再补写，可把 `bootstrap-start-date` 设置为最后一个 T+1 业务日期，从该日最小 ID 幂等重放；该日期 Bulk 提交完成并持久化异步对账任务后即可进入下一天。

该流程可以做到**同步数据无丢失、无重复文档意义上的平滑切换**：停用 T+1 到启动轮询之间产生的数据仍保留在 MySQL，轮询启动后会追平；相同 ID 的重复写由 ES `_id=id` 幂等覆盖。但它不等于查询服务部署过程天然零中断，HTTP 查询是否全程可用仍由第 12 章的 query-only、drain 和容器切换流程保证。

### 3.5 每表同步模式

同步方式应由每张表独立选择，而不是整个服务只能统一使用 T+1 或延迟轮询。公共表定义只保存一份，并增加 `sync-mode`：

| `sync-mode` | 行为 |
| --- | --- |
| `t-plus-one` | 由现有 T+1 调度器按业务日期创建并执行 `sano_import_task` |
| `polling` | 由延迟轮询协调器创建 Reader、队列、Bulk Workers、租约和 checkpoint |

统一表目录调整为 `sano.import.tables`，完整保留 `enabled`、Alias、表名、Mapping、历史索引保留、ID、日期类型和 `where-sql` 等字段，只新增 `sync-mode` 与 `bootstrap-start-date`。T+1 和 polling 都从这里读取，不再创建第二份表配置。`es`、`import` 与 `notify` 是相互独立的服务级配置。以下是版本B启用后的目标混合配置示例，不代表版本A线上配置：

```yaml
sano:
  server-mode: all
  es:
    uris: 127.0.0.1:9201
  notify:
    enabled: true
    lark:
      enabled: true
      webhook-url: ${LARK_WEBHOOK_URL:}
  import:
    common:
      write:
        global-bulk-concurrency: 3
        polling-reserved-concurrency: 2
    t-plus-one:
      enabled: true
      cron: "0 0 3 * * ?"
    tables:
        - enabled: true
          index-alias: sano_wallet_coin_record
          table-name: sano_wallet_coin_record
          mapping-file: sano_wallet_coin_record.json
          sync-mode: polling
          bootstrap-start-date: 2026-07-16
          delete-history-index: true
          reserve-days: 60
          id-column: id
          dt-column: dt
          dt-column-type: DATE
          where-sql:

        - enabled: true
          index-alias: sano_wallet_diamond_record
          table-name: sano_wallet_diamond_record
          mapping-file: sano_wallet_diamond_record.json
          sync-mode: t-plus-one
          delete-history-index: true
          reserve-days: 60
          id-column: id
          dt-column: dt
          dt-column-type: DATE
          where-sql:

        - enabled: true
          index-alias: sano_wallet_lucky_diamond_record_10m
          table-name: sano_wallet_lucky_diamond_record_10m
          mapping-file: sano_wallet_lucky_diamond_record_10m.json
          sync-mode: polling
          bootstrap-start-date: 2026-07-16
          delete-history-index: true
          reserve-days: 60
          id-column: id
          dt-column: dt
          dt-column-type: DATE
          where-sql:

        - enabled: true
          index-alias: sano_game_record
          table-name: sano_game_record
          mapping-file: sano_game_record.json
          sync-mode: t-plus-one
          delete-history-index: true
          reserve-days: 60
          id-column: id
          dt-column: create_time
          dt-column-type: DATETIME
          where-sql:

  polling:
    enabled: true
```

配置边界：`import.common`只保存跨引擎共享的drain、ES写入许可和总内存预算；
`import.t-plus-one`保存T+1自己的Cron、读取批次、队列、Bulk、重试、失败阈值和索引优化参数；`notify`独立保存服务级通知开关和Lark渠道，供导入及后续其他模块复用；
polling在`polling`配置段维护自己的轮询间隔、读取批次、Worker、队列、Bulk和重试参数。后续MQ同样新增独立配置段，不能继承T+1参数。

版本A当前代码只有`EsImportProperties`中的`common`、`t-plus-one`和`tables`配置模型，所有线上表均显式为`sync-mode: t-plus-one`；尚未创建`polling`配置类或轮询协调器。Compose中的`SANO_ES_POLLING_ENABLED=false`是部署边界声明，不能据此认为版本A已经具备polling能力。

运行规则：

1. `enabled=false` 时两套引擎都忽略该表；`enabled=true` 时，T+1 调度器只为 `sync-mode=t-plus-one` 的表创建新任务，轮询协调器只为 `sync-mode=polling` 的表创建同步单元。
2. `import.t-plus-one.enabled` 和 `polling.enabled` 是对应引擎的服务器级总开关。表模式已启用但总开关关闭时，状态接口必须显示 `DISABLED_BY_GLOBAL_SWITCH`，不能伪装成正常同步。
3. `sync-mode` 未配置时默认使用 `t-plus-one`，保证现有 YAML 不增加新字段时仍保持原 T+1 行为。
4. `bootstrap-start-date` 只对轮询表生效；T+1 表忽略该配置。轮询表即使当前已有 checkpoint，也建议保留启动日期作为 checkpoint 被人工清理后的安全基线。
5. `mapping-file`、`delete-history-index`、`reserve-days`、`id-column`、`dt-column`、`dt-column-type`、`where-sql` 均由 T+1、轮询、索引管理和对账共同复用，不能因切换模式而丢失。
6. 第一版不支持运行中热切换 `sync-mode`；修改后需要按表 drain 并重启或重新加载同步单元。T+1 切换到 polling 使用 3.4 节流程。
7. polling 切回 T+1 时应在业务日期边界执行：先 drain 轮询，确保新的 T+1 起始日期没有被 polling 创建或写入。现有 T+1 `EsIndexManager` 遇到同名物理索引会拒绝创建，因此第一版不能在同一业务日期中途反向切换。
8. 该配置只决定后续自动任务归属，不负责清理历史 `sano_import_task`；首次切换前仍由运维确认旧任务已完成。

## 4. 并发模型

### 4.1 当前规模

当前最多 5 张轮询表时，可以为每张 `sync-mode=polling` 的表配置一个逻辑轮询任务；`t-plus-one` 表不创建常驻 Reader 或 Bulk Worker。

建议配置：

```yaml
sano:
  import:
    common:
      write:
        global-bulk-concurrency: 3
        polling-reserved-concurrency: 2
        t-plus-one-max-concurrency: 3
        global-queue-max-bytes: 128MB
  polling:
    enabled: true
    max-active-tables: 5
    reader-per-table: 1
    bulk-workers-per-table: 2
    queue-capacity-batches-per-table: 4
    max-uncommitted-sequences-per-table: 8
```

其中`sano.import.common.write`已在版本A实现并由T+1使用；`sano.polling`及其余参数属于版本B待实现配置。

说明：

- 每张启用表拥有一个独立 Reader；该 Reader 只读取本表并维护本表内存游标。
- `max-active-tables` 是服务器级保护上限。增加表时同步调整该值并评估 MySQL 轮询QPS、JVM内存和线程数，不改变既有表的运行逻辑。
- 每张表拥有独立 Reader、独立有界队列、独立 Bulk Workers、独立 checkpoint 和暂停状态。
- 每表最多两个 Bulk Worker，可并行处理该表不同且不重叠的 ID 批次。
- polling 与 T+1 的所有 Bulk Worker 真正发送 ES 请求前，都必须从共享写入协调器获取许可证；初始最多同时执行 3 个 ES Bulk。
- `polling-reserved-concurrency=2` 表示 polling 有请求等待时，T+1 后续最多保留 1 个在途许可证，防止夜间全量任务制造实时同步延迟；polling 空闲时 T+1 可以在 `t-plus-one-max-concurrency` 范围内借用空闲许可证。
- 许可证不抢占已经发送的请求；T+1 在途请求完成后，下一次申请按 polling 保留额度让行。
- `global-queue-max-bytes` 对两套引擎的内存批次增加总量保护，防止混合模式下 importer 队列与各表 polling 队列叠加导致内存失控。
- `max-uncommitted-sequences-per-table` 限制 `last_enqueued_sequence - last_committed_sequence`。达到上限后该表 Reader 停止查询，直到有序提交器释放窗口；该限制同时覆盖普通批次、重试批次和 `DATE_CLOSE`。

线程和队列按引擎、按表隔离，ES 写入额度和总内存预算跨引擎共享。新增表只增加该模式自己的同步单元；ES 扩容后调整共享写入参数即可。polling 保留额度保证 T+1 大批量导入期间，相对实时表仍有稳定写入能力。

内存预算必须遵循单一生命周期：Reader 在执行 SQL 前按目标页的保守估算预留额度，查询后按实际序列化大小校准；批次进入队列、等待许可证、执行 Bulk 或进入延迟重试期间都持续占用额度，只有该批次达到成功或“错误池已可靠落盘”的终态后才释放。预留失败时不得继续查询。T+1 也使用相同规则，避免两套引擎分别认为自己仍有可用内存。

### 4.2 每表独立有界队列

建议每张表持有独立的 `BlockingQueue<SyncBatch>`，而不是所有表共用一个无限队列：

```text
coin reader    -> coin queue    -> coin bulk workers    ┐
diamond reader -> diamond queue -> diamond bulk workers ├─ fair global permits -> ES
lucky reader   -> lucky queue   -> lucky bulk workers   ┤
game reader    -> game queue    -> game bulk workers    ┘
```

队列满时，读取器阻塞或延迟下一次轮询，不再执行新的 MySQL 查询。这就是背压：ES 写慢时自动减小数据库读取速度，避免 JVM 堆积大量 `List<Map<String, Object>>`。

每个 `SyncBatch` 至少保存：表名、同步日期、目标物理索引、批次序号、行数据、首末 ID、读取时间、重试次数。一个批次只包含同一业务日期的数据，因此 `target_index=alias_yyyyMMdd` 唯一明确。队列容量应按“批次数”或“估算字节数”限制，不按对象个数无限堆积。

## 5. 单表轮询流程

### 5.1 SQL 语义

每张表必须按“业务日期 + ID”稳定升序分页，尽量把查询限制在单日索引范围内。

`dt` 为 DATE 类型时：

```sql
SELECT <同步字段>
FROM sano_wallet_coin_record
WHERE dt = :sync_date
  AND id > :last_read_id
ORDER BY id ASC
LIMIT :page_size;
```

日期字段为 DATETIME/TIMESTAMP 时：

```sql
SELECT <同步字段>
FROM sano_game_record
WHERE create_time >= :day_start
  AND create_time < :next_day_start
  AND id > :last_read_id
ORDER BY id ASC
LIMIT :page_size;
```

`sync_date` 与 `last_read_id` 是内存读取游标；持久化恢复点仍是 `last_committed_date + last_committed_id`。查询条件必须继续使用 JDBC 占位符，表名和字段名使用配置白名单。

每次查询默认 `page_size=3000`，作为起点合理。实际大小由每行文档体积、MySQL 查询耗时、ES Bulk 耗时和 JVM 队列水位共同决定，不应固定为永远不变的值。

### 5.2 正常流程

```text
检查表状态是否 RUNNING 且持有同步租约
    -> 检查本表队列、内存额度与未提交sequence窗口
    -> 按 sync_date + last_read_id 查询最多 3000 条
    -> 有数据：封装带 target_index 和 sequence 的 SyncBatch
    -> 推进内存 last_read_id，不推进持久化 checkpoint
    -> 放入本表有界队列
    -> 本表独立 Bulk Worker 从本表队列获取 batch
    -> Worker 获取公平全局 Bulk 许可证
    -> 按 batch.target_index 写入 ES并释放许可证
    -> 标记该 sequence 的终态结果
    -> 仅按 sequence 连续推进 last_committed_date + last_committed_id
    -> 队列有空位时继续读取
```

若当前日期查询结果为 0：

1. `sync_date < 今天`：将日期 `D` 标记为 `READ_COMPLETE`，幂等准备 `D+1` 物理索引与 Alias，生成带 sequence 的 `DATE_CLOSE(D)`，随后立即把内存读取日期切换到 `D+1`、ID=`0`，继续 Reader；此时不能提前推进持久 checkpoint。
2. `sync_date = 今天`：不进入未来日期，保持当前日期和 ID，进入空闲退避等待。
3. 等待期间一旦读到数据：立即将轮询间隔恢复到基础 `5s`。
4. 程序停机数天后：从持久化日期继续逐日追赶，不直接跳到今天。

`DATE_CLOSE` 与普通批次使用同一个有序 sequence 链，但它只约束持久提交顺序，不是 Reader 停止信号。例如 D 的最后批次为 102、`DATE_CLOSE(D)` 为 103、D+1 首批为 104；即使 104 先写入成功，也只能标记成功，checkpoint 仍等待 102 和 103。等 D 的 Bulk 与重试完成后，提交器处理 103：先持久化异步对账任务，再把 checkpoint 推进到 D+1、ID=`0`，随后可连续提交已经成功的 104。对账执行结果不参与该顺序。

如果 D 出现单条不可重试数据错误，必须先把失败项可靠写入错误池，再把该项标记为“终态但有错误”，该 sequence 随后可以完成，表保持 `RUNNING + DEGRADED`。如果错误池无法写入，或发生索引不可用、持续全批失败等系统性故障，checkpoint 才停在 D 并将表置为 `PAUSED`；此前已写入 D+1 但尚未提交进度的文档会在恢复后以相同 `_id` 幂等覆盖。

### 5.3 轮询间隔是否自适应

需要自适应，但应保持简单、可预测，不能根据单次耗时频繁抖动。

推荐策略：

| 本次结果 / 状态 | 下一次读取动作 |
| --- | --- |
| 返回条数等于 `page_size` | 说明可能有积压；队列可用时立即读下一页，不额外等待 |
| 返回条数介于 `1` 与 `page_size - 1` | 接近追平；使用基础间隔，例如 `5s` |
| 历史日期无数据 | 插入 `DATE_CLOSE` 后立即用内存游标读取下一天；持久 checkpoint 按 sequence 稍后推进 |
| 今天连续无数据 | 间隔指数增长：`5s -> 10s -> 20s -> 30s -> 60s`，最大 `60s` |
| 重新读到数据 | 立即重置为基础间隔 `5s` |
| 队列高水位或 ES 慢写 | 不主动查询；等待队列回落，等价于延长到 `10s/20s` |
| 表状态 `PAUSED` | 停止轮询，等待人工恢复 |

不建议仅因“本批不足 3000 条”就无限延长间隔。数据可能以每秒数十条稳定增长，固定较长等待会直接增加同步延迟。是否减速应主要看“连续空轮询”和“队列/ES 压力”，而不是单次小批量。

## 6. ES Bulk、错误处理与停止策略

### 6.1 目标索引与有序 checkpoint

每表 Bulk Worker 从本表队列取数据，不访问其他表队列。每个 `SyncBatch` 在入队前已经确定 `target_index`，每个 Bulk operation 都显式指定该物理索引；禁止依赖全局可变 alias 或线程上下文推断写入目标。

例如：

```text
SyncBatch(table=coin, sync_date=2026-07-15,
          target_index=sano_wallet_coin_record_20260715,
          sequence=102)
```

同表两个 Bulk Worker 可以并行处理批次 101、102，因此完成顺序可能变化。若 102 先完成，只向本表提交器上报结果，checkpoint 仍停在 100；当 101 达到终态后，由唯一的有序提交器按 sequence 连续推进到 102。终态分为 `SUCCESS` 和 `TERMINAL_WITH_ERROR`：后者表示失败项已经可靠写入错误池，可以推进但必须把一致性状态标记为 `DEGRADED`。系统性故障、错误池写入失败或租约失效不属于可提交终态，必须阻塞窗口并暂停或等待恢复。

sequence 只在一次 `lease_token` 所代表的表运行世代内有效，逻辑键为 `(lease_token, sequence)`。获取新租约后从持久化的 `last_committed_sequence + 1` 重新编号；旧世代未提交的 Bulk 回调即使晚到，也会因 `lease_token` 不匹配被丢弃。每表只能有一个串行提交器，且 `last_enqueued_sequence - last_committed_sequence` 不得超过配置上限，默认 8。这样早期 sequence 长时间重试时，Reader 最多领先 8 个 sequence，不会无限积累完成元数据或跨日重放范围。

每个 Worker 发送请求前执行：

```text
从本表队列取得批次
    -> 获取 fair global bulk permit
    -> 发送 ES Bulk
    -> 向本表串行提交器上报批次结果
    -> 释放 global bulk permit
    -> 尝试按 sequence 连续推进本表 checkpoint
```

全局许可证建议使用公平模式，初始值为 3。表内 Worker 数决定单表潜在并发，全局许可证决定整个 ES 实际承受的并发，两者职责不同。

ES Bulk 的 HTTP 请求成功不代表每个文档都写成功。必须遍历每个 `BulkResponseItem`：

1. 所有文档成功：标记该批次完成；仅在前序批次也达到可提交终态时保存 `last_committed_date + last_committed_id`。
2. 仅有可重试失败：只重试失败文档，不推进 checkpoint。
3. 存在不可重试失败：按 6.3 的策略先持久化错误项，再由提交器决定该 sequence 是否达到 `TERMINAL_WITH_ERROR`，不能将整个原批次盲目重新放回队列。

不能把“整个已部分成功的 Bulk 批次”直接重新放回背压队列：成功文档会被重复发送，虽然 `_id` 幂等通常不会产生重复数据，但会无谓增加 ES 写压力，也会混淆失败记录。

### 6.2 可重试错误

以下场景通常可重试：ES `429`、连接超时、短暂网络异常、`502/503`、节点暂时不可用。

策略：

```text
第 1 次失败：等待 1 秒后重试失败项
第 2 次失败：等待 3 秒后重试失败项
第 3 次失败：等待 10 秒后重试失败项
仍失败且只影响少量确定记录：转入错误池，标记终态并继续
仍失败且表现为全批/连接/索引系统性故障：打开表级熔断并暂停，发送 Lark 告警
```

“等待”不能通过 Bulk Worker 内部 `sleep` 实现，否则两个表内 Worker 都可能被 D 的重试占住，D+1 虽已入队仍无法执行。失败项应写入本表独立的延迟重试队列：当前 ES 请求结束后立即释放全局 Bulk 许可证和 Worker，达到重试时间后再重新竞争许可证。失败 sequence 在有序提交器中保持未完成，但其他 Worker 可以继续处理更高 sequence，包括 D+1 批次；这些后续成功批次只暂存结果，不越过失败 sequence 推进 checkpoint。

因此，D 的一次重试不会让后续全部停住：D+1 可以继续读取和执行 Bulk，最多领先到未提交窗口上限。达到默认8个 sequence 后暂停新的 SQL 是有意的内存安全阀，而不是日期关闭屏障；D 的单条永久错误可靠进入错误池后会解除窗口，系统性 ES/索引故障才会持续阻塞。系统性故障下继续无限读取和提交后续数据本身也无法可靠成功，应由熔断、告警和恢复流程处理。

系统性故障触发暂停时必须做到：

1. 停止该表的新 SQL 查询。
2. 停止从该表队列继续取新批次。
3. 保留最后已提交 checkpoint，不越过当前失败批次。
4. 将失败批次的 ID 范围、失败原因、ES 状态、重试次数写入错误索引和错误日志。
5. 发送一次明确的 Lark 告警，不循环刷屏。

应用重启或人工恢复时，从最后已提交 checkpoint 重读失败批次。使用原始 `id` 作为 ES `_id` 后，重复写入安全。

### 6.3 不可重试的单条数据错误

例如严格 Mapping 拒绝未知字段、日期格式错误、文档过大、业务数据缺失 ID。这类错误重试三次通常不会成功。

默认策略为 **`ERROR_POOL_AND_CONTINUE`**：

```text
单条永久错误
    -> 写入 sano_sync_error
    -> 记录原始 ID、原始数据摘要、错误原因
    -> 错误文档写入成功后将该项标记为TERMINAL_WITH_ERROR
    -> 批次其余项全部终态后允许有序推进checkpoint
    -> 保持sync_status=RUNNING并置consistency_status=DEGRADED
    -> Lark提醒并交给异步对账/补偿修复
```

该策略与“对账允许千分之一差异且不能阻塞下一日”保持一致，避免单条脏数据造成整表后续数据真空。它不是静默跳过：错误索引写入是 sequence 达到终态的前置条件，错误记录使用确定性 `_id=table_name_syncDate_recordId` 幂等保存，并保留原始 ID、错误原因和补偿状态。后续对账仍会发现缺失并尝试定向修复。

以下情况不得继续推进，必须进入表级熔断/暂停：错误索引不可写或无法确认写入结果、目标索引不存在且无法恢复、持续全批失败、认证/权限错误、租约失效、状态写入器无法持久化 checkpoint。`PAUSE_TABLE` 可保留为单表显式配置，但不作为默认值。

### 6.4 T+1 安全断点与混合模式约束

现有 T+1 链路不能用“本次 Bulk 中成功文档的最大 ID”直接更新任务 `last_success_id`。例如 ID 101 失败而 102 成功时保存 102，`TIMEOUT_PARTIAL` 恢复后使用 `id > 102` 会永久跳过 101。实施混合模式和统一 drain 前，必须先把 T+1 改为批次有序断点：

1. 每个读取批次保存明确的 `first_id / last_id / batch_sequence`，只有该批次所有项成功或已可靠进入错误池，并且所有前序批次都达到终态时，才能把任务断点推进到该批次 `last_id`。
2. T+1 的 `TIMEOUT_PARTIAL` 只保存最后连续终态批次的 ID，不能保存最大成功 item ID；重启后从该安全点重读，依赖 `_id=id` 幂等覆盖。为兼容现有 `sano_import_task` 可以暂时保留字段名 `last_success_id`，但代码语义必须改成 `last_safe_checkpoint_id`，不能再按名称理解为任意成功 item 的最大值。
3. T+1 与 polling 共用全局 Bulk 许可证和内存预算；polling 有等待者时，T+1 借用的许可证在当前请求结束后让出，不能继续抢占 polling 保留额度。
4. T+1 任务 `SUCCESS` 后创建与 polling 相同结构的异步对账任务，日期推进、部署和告警因此使用统一的一致性口径。对账结果不反向修改已经成功的 T+1 任务。
5. 在只允许单同步实例的第一版中，必须由部署拓扑保证只有一个`all`实例启用同步；JVM内互斥不能视为跨实例互斥。若未来允许多个同步实例，`sano_import_task`的领取和状态更新也必须增加owner、租约及OCC。

### 6.5 慢写回压

当连续 Bulk 耗时超过阈值（例如 `3s`）或队列达到 70% 高水位：

1. 打印慢 Bulk 日志，并记录表名、批次大小、耗时和队列深度。
2. 读取器停止立即拉取下一页，等待队列低于 30% 水位。
3. 仅在连续慢写达到阈值时发送 Lark 汇总提醒，避免偶发慢请求产生告警噪声。
4. 不使用无限线程或无限内存队列“硬顶过去”。

这里不必手工给每张表从 `5s` 改成 `10s`。有界队列和高低水位控制会更准确地反映 ES 实际处理能力；间隔只是补充节流手段。

## 7. 同步 checkpoint 与错误持久化

不建议只保存在JVM内存或日志文件。版本B的内部持久化索引统一使用`sano_sync_`前缀，便于通过`sano_sync_*`集中查看：

```text
sano_sync_polling_checkpoint
sano_sync_error
sano_sync_reconcile_task
```

其中`sano_sync_polling_checkpoint`是Polling专属索引，因此名称保留`polling`；error和reconcile task由T+1与Polling共用，不增加单一引擎名称。checkpoint和error字段在本章定义，reconcile task在第8章定义；三者职责独立，不合并到同一个Mapping。

### 7.1 `sano_sync_polling_checkpoint` 建议字段

| 字段 | 含义 |
| --- | --- |
| `table_name` | 表名，作为文档 `_id` 的组成部分 |
| `index_alias` | 目标 ES Alias |
| `sync_status` | `NEEDS_BOOTSTRAP` / `RUNNING` / `PAUSED`，控制是否允许同步 |
| `consistency_status` | `UNKNOWN` / `HEALTHY` / `ACCEPTABLE` / `DEGRADED`，描述最近一致性结果 |
| `last_committed_date` | 最后可恢复的已提交业务日期 |
| `last_committed_id` | 最后连续达到可提交终态的 MySQL ID；可能包含已可靠进入错误池的记录 |
| `last_committed_time` | 最近一次 checkpoint 提交时间 |
| `last_read_date` | 当前内存读取日期的观测值，不能用于恢复 |
| `last_read_id` | 仅用于观测，不能用于恢复起点 |
| `last_committed_sequence` | 最后连续达到可提交终态并已落盘的批次序号 |
| `owner_instance_id` | 当前持有该表同步租约的实例 |
| `lease_token` | 每次成功获取租约时递增的隔离令牌，防止旧实例恢复后误更新进度 |
| `lease_renewed_at` | 最近一次成功续租时间 |
| `lease_until` | 租约到期时间，防止两个同步实例同时运行 |
| `last_reconciled_date` | 最近一次被允许更新表级一致性状态的业务日期，防止旧对账覆盖新结果 |
| `last_error_message` | 最近一次失败摘要 |
| `last_error_at` | 最近失败时间 |
| `updated_at` | 状态更新时间 |

读取游标与已提交 checkpoint 必须明确区分。真正恢复只能使用 `last_committed_date + last_committed_id`；`last_read_date + last_read_id` 只用于观察当前流水线读到了哪里，并且在跨日并行期间允许领先持久 checkpoint 一个或多个 sequence。

### 7.2 `sano_sync_error` 建议字段

| 字段 | 含义 |
| --- | --- |
| `table_name` | 来源表 |
| `sync_mode` / `task_id` | 来源引擎及可选 T+1 任务 ID，便于统一补偿审计 |
| `record_id` | 失败 MySQL ID |
| `batch_first_id` / `batch_last_id` | 失败批次范围 |
| `error_type` | `RETRY_EXHAUSTED` / `MAPPING` / `DATA_INVALID` / `INDEX` 等 |
| `error_message` | ES 或数据转换失败原因 |
| `retry_count` | 已重试次数 |
| `source_data` | 可脱敏保存的原始数据摘要 |
| `created_at` | 记录时间 |
| `resolved_at` / `resolved_by` | 人工修复追踪 |

错误索引本身也需要可观测和备份。错误文档中不得包含数据库密码、Token、身份证件等不应进入日志的信息。

### 7.3 Docker 强停与异常退出恢复

所有背压队列均在 JVM 内存中，`docker kill`、宿主机重启或 OOM 时队列可能来不及排空，因此恢复设计不能依赖内存数据。

| 中断时机 | 重启后的处理 |
| --- | --- |
| MySQL 已读取，尚未写 ES | 从已提交 checkpoint 重新查询，批次重新生成 |
| ES 已写成功，尚未保存 checkpoint | 从旧 checkpoint 重读并重复写入；`_id=id` 保证幂等 |
| checkpoint 已保存 | 从新日期和 ID 继续，不重读已提交批次 |
| 持有租约的实例突然消失 | 新实例等待 `lease_until` 过期后接管 |

防御措施：

1. 每条 ES 文档固定使用 MySQL `id` 作为 `_id`。
2. checkpoint 只在连续批次成功后持久化，不能在读取或入队时更新。
3. 每个同步实例使用唯一 `instance_id`，每表定期续租；未持有租约的实例不得查询该表。
4. Spring 优雅关闭尽力排空队列，但即使未执行关闭回调也能通过 checkpoint 重放恢复。
5. 启动时发现旧租约未过期则等待，不强抢；过期后使用 ES 乐观并发控制获取租约。

### 7.4 租约时长与续租机制

建议初始参数如下：

| 配置项 | 建议值 | 说明 |
| --- | --- | --- |
| `lease-duration` | 90秒 | 单次获取或续租后，租约有效期延长到当前时间之后90秒 |
| `lease-renew-interval` | 20秒 | 独立续租线程每20秒为当前实例持有的表续租一次 |
| `lease-danger-threshold` | 30秒 | 距到期不足30秒且仍续租失败时，停止产生新的同步数据 |
| `lease-acquire-retry-interval` | 10秒 | 未获得租约的表每10秒尝试获取一次，不执行MySQL查询 |
| `lease-request-timeout` | 5秒 | 单次租约更新请求的超时时间，避免续租线程长时间阻塞 |

续租由独立调度线程执行，不能依赖 Reader 轮询、Bulk 完成或是否读到数据。即使当天无数据并进入60秒退避，租约仍必须按20秒周期续租。90秒租约通常可提供4次左右的续租机会，可以容忍短暂网络抖动、ES响应变慢和JVM停顿，同时将异常实例的最长接管等待控制在约90秒。

租约生命周期如下：

1. 实例启动时生成本次进程唯一的 `instance_id`，建议使用 `${HOSTNAME}-${startup_uuid}`，进程重启后不得复用旧值。
2. 表进入 `RUNNING` 前，使用 ES 乐观并发控制获取租约；获取成功时递增 `lease_token`。
3. 表处于 `RUNNING` 或 `DRAINING` 时持续续租。进入部署排空阶段后，仍需持有租约，直到本表队列、重试任务和在途 Bulk 全部处理完成。
4. 表完成排空、正常停止或进入安全暂停状态后，先持久化 checkpoint 和最终状态，再主动释放租约。
5. 续租失败但剩余时间仍大于30秒时记录 WARN 并继续重试，不影响已经读取的数据处理。
6. 距租约到期不足30秒且仍未续租成功时，立即停止新的 MySQL 查询和新 Bulk 提交；已提交的 Bulk 可以完成，但其结果只能在租约仍有效时提交 checkpoint。
7. 租约已经到期后，旧实例不得继续查询、提交新 Bulk、切换索引或更新 checkpoint，必须等待重新获得租约。

租约获取、续租、释放和 checkpoint 更新都必须同时校验 `owner_instance_id + lease_token`，并使用 ES 的 `if_seq_no`、`if_primary_term` 乐观并发控制。这样即使旧实例因长时间 GC 或网络中断后恢复，也无法覆盖已经由新实例接管的表进度。这些写操作不能由续租线程和 Bulk 回调直接并发发送，而要进入 7.5 节的单表状态写入器。

租约读取必须使用 checkpoint 文档 `_id` 的实时 Get API，不能通过 Search API 判断租约状态。实时 Get 不依赖索引 refresh，可以读取尚未对搜索可见的最新版本。续租写入固定使用 `refresh=false`，不得每20秒主动刷新索引；正常情况下由状态写入器使用自己保存的最新 `_seq_no` 和 `_primary_term` 发起下一次写入，发生409冲突时再实时 Get 并判断是异常外部更新还是租约已经被其他实例接管。这样可避免正常 checkpoint 推进和续租互相制造409冲突。

租约依赖各同步宿主机时钟近似一致。生产环境必须启用 NTP/chrony，并配置 `max-clock-skew`（建议5秒）作为安全余量：获取租约时只有 `lease_until + max-clock-skew < now` 才认为旧租约已过期；本实例在 `lease_until - max-clock-skew` 后不得再提交状态。检测到本机时钟漂移超过阈值时停止新读取并告警，不能冒险强抢租约。

正常续租成功日志使用 DEBUG；首次续租失败使用 WARN；进入30秒危险区、租约过期或被其他实例接管时使用 ERROR 并发送 Lark 通知。按5张表、每20秒分别续租计算，平均仅0.25次ES请求/秒，对当前系统性能影响可以忽略。

### 7.5 单表状态写入器

checkpoint 索引中租约字段、进度字段和业务状态位于同一文档，若续租线程与 Bulk 完成回调分别使用 OCC 更新，正常运行也会频繁发生409，并可能造成续租或进度提交饥饿。因此每张 polling 表必须有且只有一个 `TableStateWriter`（可实现为单线程 actor/串行事件循环）：

1. 租约获取成功后由它持有最新 `_seq_no / _primary_term` 和 `lease_token`，后续续租、进度提交、暂停、人工恢复和释放租约均排入该写入器。
2. Reader、Bulk Worker、重试调度器和有序提交器只发送不可变事件，不直接更新 checkpoint 文档；事件携带 `lease_token`，旧世代事件直接丢弃。
3. 多个可合并事件可在一次 update 中同时保存，例如“续租 + checkpoint 推进 + consistency_status=DEGRADED”，减少写放大；但租约危险区内的续租事件具有最高优先级。
4. OCC 409 后实时 Get：若 owner/token 已变化，立即停止本表；若仍属于本实例，则以新版本重放尚未确认的串行事件。事件处理必须幂等。
5. 状态写入器失败、积压超过上限或无法在租约安全时间内确认写入时，停止 Reader 和新 Bulk，不能让内存进度继续无限领先。

## 8. 日期关闭、对账与不一致处理

### 8.1 对账触发时机

不再使用每日 `00:10` 固定调度。Reader 查询到历史业务日期 `D` 为空时产生 `DATE_CLOSE(D)` 并把日期 `D` 标记为 `CLOSING`。`CLOSING` 是日期 checkpoint 状态，不是表 Reader 状态；Reader 准备好 `D+1` 后立即继续读取新日期。

Reader 生成关闭标记只需满足：

1. 业务日期 `D` 早于今天，避免对仍在持续写入的当天数据提前对账。
2. `D` 的 MySQL 查询已经返回空，确认所有 D 数据均已生成批次并取得 sequence。
3. `D+1` 目标物理索引与 Alias 已幂等准备成功，避免切换内存游标后没有明确写入目标。

有序提交器真正提交 `DATE_CLOSE(D)` 时才要求：D 的全部前置 sequence 均达到可提交终态（`SUCCESS` 或错误池已可靠落盘的 `TERMINAL_WITH_ERROR`），持久化 checkpoint 已推进到 D 的最后终态 ID，并且当前实例仍持有有效租约。

日期切换流程如下：

```text
历史日期D查询返回空
    -> 幂等创建D+1物理索引并绑定查询Alias
    -> 注册DATE_CLOSE(D) sequence，D进入CLOSING
    -> Reader立即切换内存游标到D+1并继续投递批次

并行发生：
    -> D的Bulk Worker继续完成队列、重试和在途Bulk
    -> D+1批次可以提前执行Bulk，成功结果暂存在有序提交器

当D的前置sequence全部达到可提交终态：
    -> 有序提交器提交DATE_CLOSE(D)
    -> 幂等写入D的PENDING异步对账任务
    -> checkpoint推进到D+1、ID=0
    -> 连续提交已经达到可提交终态的D+1 sequence
    -> 全局对账Worker在旁路异步处理D，结果不影响D+1同步
```

对账任务必须先持久化，不能只提交到 JVM 内存线程池。建议使用独立索引 `sano_sync_reconcile_task`，由 T+1 与 polling 共用；文档 `_id` 固定为 `table_name_yyyyMMdd`，通过确定性 ID 防止重复创建。任务至少包含 `table_name`、`index_name`、`sync_date`、`source_sync_mode`、`status`、`execute_after`、`owner_instance_id`、`lease_token`、`lease_until`、MySQL/ES统计结果、差异数量、补偿结果、错误信息和更新时间。

对账由一个全局线程串行执行，避免多张千万级表同时执行 `COUNT` 和 ID 核对挤占 MySQL、ES。日期推进只要求任务文档已经创建成功，即使任务仍为 `PENDING`、`RUNNING`、`FAILED` 或发现差异，也不得反向暂停已经进入 `D+1` 的 Reader。服务重启后扫描 `PENDING` 及租约已过期的 `RUNNING` 任务继续执行，失败任务按独立重试策略恢复。

对账任务也必须使用 owner、租约隔离令牌和 OCC 领取/续租/完成，不能只依赖 JVM 单线程。旧实例恢复后提交结果时，若任务 token 已变化则丢弃结果。对 polling 表更新 checkpoint 一致性摘要时，结果必须作为事件交给当前租约 owner 的 `TableStateWriter`，并满足 `task.sync_date >= checkpoint.last_reconciled_date` 后原子写入新的 `last_reconciled_date`；对账 Worker 不得直接绕过状态写入器更新 checkpoint。当前没有 polling owner 时，任务自身先完成，摘要等下次表取得租约后补写。T+1 表不写 polling checkpoint，其一致性状态直接取最近对账任务。较旧日期的补跑结果只保存在任务自身，不得覆盖较新日期已经得出的表级一致性状态。

开始统计前仅对 `D` 的物理索引执行一次 refresh，确保最后一批 Bulk 对 `_count` 可见；不得对每个 Bulk 执行 refresh。若 `D` 的 MySQL 数量为0且物理索引不存在，直接记录对账成功，不为 `D` 补建空索引。

如果业务保证不会在日期关闭后补写旧日期，`execute_after` 可以等于任务创建时间。如果可能存在晚到数据，可配置 `reconcile-delay-minutes`，例如延迟30至120分钟执行；延迟只影响对账任务，不影响 Reader 进入新日期。一次对账完成后又发生的旧日期补写无法被本次结果发现，必须通过业务时效约束或人工重跑该日期对账解决。

该顺序允许读取日期领先于持久 checkpoint，同时保持可重放：

1. 对账任务使用确定性 ID，进程在任务创建后、checkpoint 更新前退出，重启后重复创建只会得到同一任务，不会丢失或重复生成对账工作。
2. `D+1` 索引创建和 Alias 绑定必须幂等；进程在索引创建或 D+1 Bulk 写入后退出，重启后从持久 checkpoint 重读，重复文档由 `_id=id` 幂等覆盖。
3. 只要 D 的前置 sequence 均达到可提交终态、对账任务已持久化且仍持有有效租约，就可以通过 `TableStateWriter` 使用 `owner_instance_id + lease_token + if_seq_no + if_primary_term` 把 checkpoint 更新为 `D+1,0`，与对账结果无关。
4. D+1 的 Bulk 可以先于 `DATE_CLOSE(D)` 完成，但不能越过该 sequence 提交 checkpoint；关闭 sequence 一旦提交，可以按顺序一次性追上所有已达到可提交终态的 D+1 批次。
5. 对账任务持久化或 checkpoint 更新失败时只阻塞有序提交器，不停止 Reader；最终仍会受到每表有界队列和全局字节上限背压。对账执行失败或差异超阈值由对账子系统独立告警和补偿。

对账条件必须与实际同步条件完全一致：相同表、相同 `dt` 或 DATETIME 左闭右开范围、相同过滤条件。否则统计结果没有意义。

### 8.2 第一层：快速统计对账

对账允许配置差异率阈值，初始建议为千分之一：

```yaml
sano:
  polling:
    reconcile:
      allowed-difference-rate: 0.001
```

阈值只决定一致性状态、告警级别和补偿优先级，不参与日期 checkpoint 推进。快速统计阶段可先使用 `abs(mysql_count - es_count) / max(mysql_count, 1)` 判断差异规模；进入精确 ID 核对后，以 `missing_mysql_id_count / max(mysql_count, 1)` 作为最终缺失率。

每张表至少比较：

| MySQL | Elasticsearch |
| --- | --- |
| `COUNT(*)` | `_count` 指定物理索引和日期范围 |
| `MIN(id)` / `MAX(id)` | `min` / `max` 聚合 `id` |
| 同步 checkpoint 状态 | `sync_status`、`consistency_status`、错误数、队列深度 |

结果处理：

1. `count`、最小 ID、最大 ID 都一致且表状态正常：任务标记为 `SUCCESS`，表的 `consistency_status=HEALTHY`。
2. 任一不一致：不要直接判定为“数据丢失”，进入第二层和第三层核对。
3. 精确差异率不超过 `allowed-difference-rate`：任务标记为 `ACCEPTABLE`，表的 `consistency_status=ACCEPTABLE`，保留差异明细并继续异步补偿。
4. 精确差异率超过阈值：任务标记为 `DEGRADED`，表的 `consistency_status=DEGRADED`，提高告警级别并执行补偿，但 `sync_status` 保持 `RUNNING`。
5. 对账任务自身执行失败：任务标记为 `FAILED` 并重试；重试耗尽后告警，但不修改日期 checkpoint，也不停止 Reader。

**只比较 count 不足以证明一致。** 因此 count 相同但 min/max 异常、错误池存在记录或抽样异常时，也应进入精确核对。

### 8.3 第二层：确认是否只是积压

日期关闭屏障正常工作时，该日期不应再有队列积压；仍需执行以下防御检查：

1. 记录该表当前 `sync_status`、`consistency_status` 和失败批次，作为对账结果上下文，但不据此取消对账。
2. 只检查业务日期 `D` 自己的批次注册表和 in-flight Bulk，不能把正在同步的 `D+1` 队列深度误判为 `D` 的积压。
3. 如果仍发现 `D` 存在正常积压，说明关闭观测信息尚未收敛，将对账任务退回 `PENDING`，等待有限窗口后重试；不影响 `D+1` Reader。
4. 如果重试后两端一致，标记为“延迟追平”，记录最大延迟，不执行全量补偿。

这样可避免在 ES 临时变慢、刚好有 3000 条在队列中时误触发昂贵补数。

### 8.4 第三层：精确找出差异 ID

若队列已排空仍不一致，按分页执行 MySQL 到 ES 的单向 ID 核对，不能将百万 ID 一次性放入内存：

1. MySQL 按 `dt,id` 读取目标业务日期的 ID 页，每页 1000 至 3000 个。
2. 对同一物理 ES 索引使用 `_mget` 或 `terms` 查询该页 ID。
3. 找出“存在于 MySQL 但不存在于 ES”的 ID，按 ID 回查完整 MySQL 行并重新进入 Bulk 写入。
4. 将缺失 ID、数量和修复结果写入对账记录与 Lark 通知。

本业务约定 MySQL 流水不会删除，因此不实现 ES 反向扫描和自动删除逻辑。正常链路中 ES 文档均来自 MySQL，修复目标只处理“MySQL 有、ES 缺失”。

### 8.5 修复策略

| 差异情况 | 默认处理 |
| --- | --- |
| MySQL 有、ES 无 | 按差异 ID 回查 MySQL 全字段，定向 Bulk 补写，然后复核 |
| 两端 count 一致但抽样/范围异常 | 分页验证 MySQL ID 是否全部存在于 ES |
| 同步表 `PAUSED` | 保持暂停，优先修复失败批次；不绕过 checkpoint 做大范围补写 |
| 差异数量很小 | 定向补写，保留补偿审计日志 |
| 差异数量很大或整天索引异常 | 保持新日期实时轮询，独立启动受控的目标历史日期重建/回填流程 |

每次补偿结束后必须再次执行快速统计对账。复核差异率不超过阈值时更新为 `consistency_status=ACCEPTABLE`；超过阈值时保持 `consistency_status=DEGRADED` 并发送包含表名、日期、MySQL 数、ES 数、缺失 ID 样例和最近错误的 Lark 通知。两种结果均不回退已经推进的日期 checkpoint，也不暂停新日期同步。

### 8.6 为什么不每天直接全量重导

每日全量重导当然可以修复“ES 少数据”问题，但代价高：会重复扫描 MySQL、重复 Bulk 写入、占用 ES merge/refresh 资源。

因此推荐顺序是：**快速统计 -> 等待积压排空 -> 精确 ID 差异 -> 定向补偿 -> 仅在大面积异常时重导当天**。

## 9. 索引创建、跨天与历史数据

轮询按单日时间范围读取，因此一个正常 `SyncBatch` 只属于一个业务日期和一个目标物理索引：

```text
sync_date=2026-07-15
    -> SQL 仅查询 2026-07-15 时间范围
    -> target_index=alias_20260715
    -> 该日期读空且早于今天后，游标推进到 2026-07-16
```

当某个目标日索引尚不存在：

1. 使用现有 Mapping/Settings 创建 `alias_yyyyMMdd`。
2. 并发创建返回“已存在”时视为正常成功。
3. 绑定该日期索引到查询 Alias。
4. 不删除其他历史索引的 Alias 绑定。

程序不会查询未来日期。到达今天且没有数据时保留当天游标并逐步退避；跨过零点后原“今天”变成历史日期，再次确认读空，准备下一日索引并插入 `DATE_CLOSE`，随后 Reader 立即进入新日期。旧日期 Bulk 在后台继续完成；只有有序 checkpoint 和对账任务创建等待旧日期 sequence，不阻塞新日期读取和 Bulk。若 MySQL 在游标已经推进且对账完成后补写旧日期数据，本次对账无法发现；该约束应与业务写入时效约定保持一致，必要时人工重跑指定日期对账。

### 9.1 历史索引保留与清理

`delete-history-index` 和 `reserve-days` 继续沿用公共表配置，由 T+1 与 polling 共用同一个索引保留服务，不能各自启动清理线程。清理以业务日期为准，每日低峰期串行执行，并遵循以下安全条件：

1. 只处理 `today - reserve-days` 之前的物理索引，绝不删除 checkpoint 当前日期、Reader 当前日期、任何在途批次的 `target_index` 或当天索引。
2. polling 日期必须已经提交 `DATE_CLOSE`，checkpoint 已越过该日期；T+1 日期必须已有终态任务，才进入候选集合。
3. 该日期存在 `PENDING / RUNNING` 对账或补偿任务时暂缓删除并告警；终态对账记录可以保留，但索引删除后不再允许自动重跑该日期补偿，需走受控历史重建。
4. 删除物理索引前先从 Alias 校验其确实属于目标表和目标日期；删除“索引不存在”视为幂等成功，权限或集群异常则保留并下次重试，不影响实时同步。
5. `delete-history-index=false` 时只观测和告警容量，不执行删除。切换 `sync-mode` 不重置保留周期，也不重复删除。

## 10. 资源、连接与性能建议

### 10.1 MySQL

1. 每张表轮询只有在执行 SQL 时占用连接，等待间隔不会长期占用 JDBC Connection。
2. 5 张表、5 个 reader，再加异步对账和管理接口，Hikari `maximum-pool-size` 建议至少为 `10`；以线上其他 SQL 使用情况为准。
3. 保持 `minimum-idle: 1`、`max-lifetime` 小于 MySQL/NAT 的连接回收时间，延续当前 Hikari 配置策略。
4. 每页只查询实际需要写入 ES 的字段；不要为了轮询读取额外大字段。

### 10.2 Elasticsearch

1. 以 `3000` 条、约 `8MB` 至 `16MB` 的 Bulk 作为起点，实际按文档体积和慢 Bulk 日志调整。
2. 写入期间保持正常 `refresh_interval=1s`；不要对每个 Bulk 使用强制 refresh。
3. 监控 `429`、写线程池拒绝、GC、merge、磁盘水位和 Bulk 耗时。
4. 一个单节点 ES 不宜让所有表无限并发大 Bulk；使用公平全局许可证限制实际 ES 并发，单表队列负责各自背压。

### 10.3 轮询频率

基础 `5s` 对 5 张表约每秒一次 SQL，且每次都有日期范围、`id > cursor`、`ORDER BY id LIMIT 3000` 索引条件，通常可接受。今天持续无数据时自动退避到 `60s`，可显著减少空查询。

若将来表数超过 20 张，不建议按表固定 5 秒轮询。应按业务优先级分组：高频表 5 秒，中频表 30 秒，低频表 5 分钟，并增加 `max-active-tables` 控制并发。

### 10.4 线上数据基线（2026-07-15）

| 表 | 当日数据量 | 当前全量耗时 | 平均写入速度 | Bulk 次数 | 平均每 Bulk |
| --- | ---: | ---: | ---: | ---: | ---: |
| `sano_wallet_coin_record` | 4,912,732 | 1,263.862s | 约 3,887 条/s | 1,638 | 约 2,999 条 |
| `sano_wallet_diamond_record` | 273,919 | 14.132s | 约 19,383 条/s | 92 | 约 2,977 条 |
| `sano_wallet_lucky_diamond_record_10m` | 17,757,092 | 688.114s | 约 25,805 条/s | 5,920 | 约 3,000 条 |
| `sano_game_record` | 1,865,576 | 44.062s | 约 42,340 条/s | 622 | 约 2,999 条 |

四张表合计约 `24,809,319` 条/天，平均约 `287` 条/秒。按 5 秒轮询，平均每轮约新增 `1,436` 条，分散在四张表：

```text
coin    约 284 条/5秒
diamond 约 16 条/5秒
lucky   约 1,028 条/5秒
game    约 108 条/5秒
```

因此 `page_size=3000` 足以覆盖当前平均流量。若某表返回满 3000 条，读取器不等待 5 秒，立即继续下一页，可用于追赶峰值或停机积压。即使总量增长 3 倍，lucky 表平均每 5 秒约 3,084 条，也只需连续读取两页即可追平。

现有全量导入速度是在关闭 refresh 等批量优化条件下测得，不能直接等同于实时写入能力；实时模式仍需保留 `refresh_interval=1s` 并承受查询负载。当前先配置每表 2 个 Worker、全局最多 3 个实际 Bulk；只有持续无 429、ES CPU/GC/查询延迟稳定后才提高全局许可证。

## 11. 管理接口与告警

建议提供内部 Token 鉴权接口：

| 接口能力 | 作用 |
| --- | --- |
| 查询所有表同步状态 | 查看 checkpoint、延迟、队列、错误和对账结果 |
| 暂停指定表 | 安全停止读取与写入，保留 checkpoint |
| 恢复指定表 | 从最后已提交 checkpoint 继续 |
| 触发指定表/日期对账 | 运维排查与补偿前验证 |
| 触发指定表/日期定向补偿 | 仅在有对账审计记录时执行 |
| 启动全局 drain | 通知全部独立 Reader 停止读取，等待所有表完整排空 |
| 查询 drain 状态 | 部署脚本确认每表流水线和全局 Bulk 许可证均已完成 |
| 取消 drain | 部署中止时恢复未失败表，失败表继续保持暂停 |

建议的部署接口语义：

```text
POST /internal/sync/drain
    -> 幂等地将当前实例切换为 DRAINING
    -> 向所有独立 Reader 发布停止读取信号
    -> 返回 operation_id

GET /internal/sync/drain/status
    -> RUNNING / DRAINING / DRAINED / DRAINED_WITH_ERRORS / FAILED
    -> 每表返回sync_mode和drain_result
    -> polling表返回reader_stopped、queue_size、active_bulk、retry_count、sequence和checkpoint
    -> t-plus-one表返回active_task、task_status、last_safe_checkpoint_id（现有字段last_success_id）、queue_size和active_bulk
    -> 返回两套引擎的活动请求总数，以及共享许可证状态

POST /internal/sync/drain/cancel
    -> 部署中止时将协调器恢复为 RUNNING
    -> 立即恢复未失败polling表
    -> 立即重新投递本次drain生成的TIMEOUT_PARTIAL任务，并恢复T+1调度
    -> 系统性失败表仍保持原状态
```

混合模式下 drain 属于整个同步实例，不能只排空 polling 而遗漏正在运行的 T+1 Bulk。使用以下顺序：

1. 协调器原子切换为 `DRAINING`：polling Reader 不再发起新 SQL；T+1 调度器不再创建或启动新任务。
2. 正在执行 SQL 的 polling Reader 等待当前查询返回，已查出的数据必须入队；正在执行的 T+1 任务在当前读取批次后停止继续分页，排空已入队 Bulk，并保存为可续跑的 `TIMEOUT_PARTIAL` 安全点。
3. polling 表继续完成队列、延迟重试和 sequence 提交；T+1 表继续完成当前任务已经入队的 Bulk，并持久化任务进度。
4. polling 表只有在 Reader 已停止、队列为空、活动 Bulk 为 0、等待重试为 0、`last_committed_sequence=last_enqueued_sequence` 时才是 `DRAINED`。
5. T+1 表没有活动任务时直接是 `DRAINED`；有活动任务时，只有任务进入 `SUCCESS / TIMEOUT_PARTIAL / FAILED` 且其 Reader、队列和 Bulk 请求全部归零后才达到安全边界。
6. polling 表遇到系统性故障且重试耗尽后进入持久 `PAUSED` 和 drain `PAUSED_SAFE`；单条错误已可靠进入错误池时继续排空。T+1 失败任务保持自身 `FAILED` 或可续跑状态，不影响其他表排空。
7. 所有表达到 `DRAINED` 或 `PAUSED_SAFE` 后，确认共享全局许可证全部归还，并确认 polling 与 T+1 的 ES 活动请求总数都为 0。
8. 最后持久化状态并释放 polling 租约：全部表正常时返回 `DRAINED`；存在安全暂停或失败表时返回 `DRAINED_WITH_ERRORS`。

不能只根据队列为空判断完成：批次可能已被 Worker 取出，正在等待许可证或正在请求 ES。也不能只根据全局许可证全部归还判断完成：某张表的队列或重试任务可能尚未处理。

Drain 不把正常表的业务状态改为 `PAUSED`；新同步实例启动后可获取租约并自动续跑。如果某表在 drain 过程中发生系统性故障并重试耗尽，该表独立进入 `PAUSED_SAFE`，其他表继续完成排空，全局返回 `DRAINED_WITH_ERRORS`。该状态已达到安全停机边界，可以继续部署；新版启动后正常表自动续跑，失败表仍保持暂停并单独告警。调用 `drain/cancel` 时不能只恢复 Cron：本次 drain 保存的 `TIMEOUT_PARTIAL` 必须立即重新进入执行队列，否则可能一直等到下一次定时扫描才恢复。

只有以下情况返回真正的 `FAILED`：Reader 或 Bulk 无法在超时内结束、checkpoint/失败状态无法持久化、全局许可证未全部归还、活动请求计数无法归零。此时不能停止主容器，应取消 drain 并中止部署。

Lark 通知至少包含：

1. 表因系统性故障重试耗尽而暂停：表名、ID 范围、失败原因、重试次数、最后已提交 ID。
2. 日期完成对账成功：表名、业务日期、MySQL 数、ES 数、最大同步延迟。
3. 日期完成对账不一致：表名、业务日期、差异方向、差异数量、样例 ID、是否已自动补偿、最终状态。
4. 持续慢 Bulk 或队列长期高水位：表名、最大耗时、当前积压和 ES 429 次数。
5. `DRAINED_WITH_ERRORS`：安全暂停表、失败 sequence、旧 checkpoint，以及部署继续但该表不会自动恢复。
6. Drain `FAILED`：未结束线程或请求、许可证状态、持久化异常，以及部署已中止。

通知服务必须独立捕获异常，通知失败不能阻断读取、Bulk、checkpoint 保存或对账。

## 12. 单服务模式下的无中断更新设计

### 12.1 服务运行模式

同一个镜像当前只支持两种运行模式，两种模式均开放查询：

| 模式 | 查询 API | `polling` 表 | `t-plus-one` 表 |
| --- | --- | --- | --- |
| `all` | 开启 | 按总开关运行 | 按总开关运行 |
| `query` | 开启 | 关闭 | 关闭 |

当前资源规模下，正常情况运行一个`all`实例。更新期间临时启动一个`query`实例保障查询，然后drain并更新主实例。当前没有独立同步节点需求，因此不预先实现`consumer`；后续确需查询、同步长期拆分时再扩展新模式和部署拓扑。

示例设计配置：

```yaml
sano:
  server-mode: all # all / query
  import:
    t-plus-one:
      enabled: true
  # 版本B待实现；版本A没有该配置模型。
  polling:
    enabled: true
```

运行模式的优先级高于各功能开关：

1. 两种模式注册相同Bean、调度基础设施和执行器，查询Controller始终开放。
2. `query`即使误配`polling.enabled=true`或`import.t-plus-one.enabled=true`，运行时同步门禁也禁止提交T+1/polling工作，不启动Reader、Worker或Bulk同步链路。
3. `all`模式在查询之外启用同步；T+1调度器只接收`t-plus-one`表，轮询协调器只接收`polling`表。
4. 临时查询容器固定使用`server-mode: query`，并显式关闭polling和T+1；运行时同步门禁与功能开关形成双重保护，但不改变Bean集合。

`server-mode`只描述当前实例是否额外启用同步，查询始终启用；每表`sync-mode`描述启用表使用T+1还是轮询，`enabled=false`表示停用。实例模式与表模式正交，未来接入MQ时可扩展新的表模式，但同一张表在同一时期仍只能有一个自动同步模式。

### 12.2 两个容器的职责

| 容器 | 模式 | 宿主机端口 | 生命周期 |
| --- | --- | --- | --- |
| `sano-es-server` | `all` | `0.0.0.0:8002` | 常驻，提供查询和同步 |
| `sano-es-server-query` | `query` | `0.0.0.0:8003` | 更新前启动，主实例恢复后停止 |

临时查询容器优先使用当前线上稳定镜像标签启动。这样即使新版本启动失败，查询仍由已验证版本继续提供。该稳定镜像必须已经实现 `server-mode=query`；首次从旧版升级时不能假设旧镜像具备此能力，必须先完成 12.4.1 的基础能力发布。

### 12.3 Nginx 配置思路

正式环境内部入口`服务器内网IP:8102`与外部`es-server.fofunlive.net:80`共用查询upstream：

```nginx
upstream es_server_query_backend {
    server 127.0.0.1:8002 max_fails=1 fail_timeout=5s;
    server 127.0.0.1:8003 backup max_fails=1 fail_timeout=5s;
}
```

测试环境提供两个Nginx入口：外部继续访问`es-server-test.fofunlive.net:80`，内部其他服务由原来的
`服务器内网IP:9003`统一调整为`服务器内网IP:9103`。两个入口代理到同一个all/query upstream：

```nginx
upstream es_server_test_query_backend {
    server 127.0.0.1:9003 max_fails=1 fail_timeout=5s;
    server 127.0.0.1:9004 backup max_fails=1 fail_timeout=5s;
}

# 外部入口，域名和端口不变。
server {
    listen 80;
    server_name es-server-test.fofunlive.net;
    location / {
        proxy_pass http://es_server_test_query_backend;
        # 其余proxy参数沿用现有配置。
    }
}

# 内部入口，其他服务统一改用内网IP:9103。
server {
    listen 9103;
    server_name _;
    location / {
        proxy_pass http://es_server_test_query_backend;
        # 其余proxy参数与外部入口一致。
    }
}
```

`9003/9004`固定作为Nginx后端端口，分别对应常驻all实例和临时query实例；`9103`只作为内部Nginx入口。
完整的外部域名和内部端口server配置见`es-server/nginx/es-server-test.conf.example`。

部署脚本对`9003/9004`回环端口的访问仅用于精确识别主实例和临时实例的`health/ready/drain`；接管和最终查询冒烟
同时请求外部域名和`127.0.0.1:9103`。其中回环地址用于本机部署检查，其他内部业务仍通过`服务器内网IP:9103`调用。

`8003` 设置为 `backup`：正常时请求只进入主实例；主实例更新不可用时，Nginx 将查询切到临时 query-only 实例。版本A当前Nginx配置对整个服务使用统一的`location /`，正式配置以`es-server/nginx/es-server.conf.example`为准：

```nginx
location / {
    proxy_pass http://es_server_query_backend;
    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    proxy_connect_timeout 60s;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;
    proxy_next_upstream error timeout http_502 http_503 http_504;
    proxy_next_upstream_tries 2;
}
```

部署脚本不通过Nginx调用同步管理接口，而是直接请求主实例`http://127.0.0.1:8002/internal/sync/...`，避免请求落到query-only容器。当前Nginx配置没有为`/internal/**`增加`allow/deny`或单独location，管理接口仍由程序内置Token校验；业务和部署脚本不得把Nginx负载入口当作主实例管理地址。

### 12.4 更新脚本流程

以下流程只适用于“当前线上稳定镜像已经支持 query-only、统一 drain 和安全 T+1 断点”的版本：

```text
1. 获取当前线上镜像标签，启动 sano-es-server-query（query 模式）
2. 等待 query 容器 /health、/ready 和真实 ES 查询冒烟通过
3. 确认Nginx已预配置8003 backup；仅在显式提供检查和reload命令时由脚本重新加载
4. POST 主实例 /internal/sync/drain
5. 轮询 drain/status，直到 DRAINED 或 DRAINED_WITH_ERRORS
6. 若超时或 FAILED：调用 drain/cancel，立即中止更新，不停止主实例
7. 停止并替换 sano-es-server 主实例
8. 等待新版 /health、/ready、查询冒烟通过
9. 版本A确认同步协调器恢复为`RUNNING`；版本B再检查租约和各表运行状态
10. 版本B启用polling后再确认新数据checkpoint正常推进；版本A不执行该检查
11. 停止临时 query 容器，最终检查主实例查询
```

重要边界：

1. 临时 query 容器未真实可用时，不允许 drain 主实例。
2. Drain 未达到 `DRAINED` 或 `DRAINED_WITH_ERRORS` 时，不允许停止或删除主容器。
3. `DRAINED_WITH_ERRORS` 可以部署，因为所有Reader、Worker和ES请求已经安全停止；新版启动后失败表保持暂停，不影响其他表。
4. Drain `FAILED` 或超时时必须执行 cancel，使未失败表恢复读取；失败表按自身状态独立处理。
5. 新主实例失败时回滚旧镜像；临时 query 容器继续提供查询。轮询停止期间 MySQL 数据只会积压，新主实例恢复后按日期和 ID 追平。
6. 脚本不删除仍被任一容器引用的镜像。
7. 部署接口必须使用现有内部 Token，并只允许本机或运维网络访问。
8. 部署脚本在 drain 成功后必须注册退出陷阱：主实例尚未被替换时任一步失败，调用 `drain/cancel`；主实例已停止或替换后失败，立即启动旧镜像回滚。只有新版健康、查询冒烟和同步恢复全部通过后才解除陷阱。
9. drain 阶段仍需为“排空已有队列”授予全局 Bulk 许可证；禁止把协调器置为 `DRAINING` 后同时关闭许可证分配，否则两套引擎会互相等待而永远无法归零。

#### 12.4.1 首次升级采用两阶段发布

版本A上线前的旧镜像不支持`server-mode=query`和统一`/internal/sync/drain`，因此首次升级不能按上述流程宣称无中断。该历史升级已经完成，后续发布不得再次使用legacy绕过版本A预检：

**基础能力版本 A（已上线，polling保持关闭）**

1. 已实现并验证`server-mode=all/query`、统一drain/status/cancel、T+1连续批次安全断点、T+1`TIMEOUT_PARTIAL`立即恢复、共享写入许可证与内存预算。
2. 所有线上表均显式保持`sync-mode: t-plus-one`；Compose保留`SANO_ES_POLLING_ENABLED=false`边界声明，代码中尚无polling引擎。
3. 第一次部署A已通过legacy完成受控升级；该模式只保留给无旧容器的首次安装或历史旧版本升级场景。
4. 已完成query-only接管、统一drain、cancel、旧镜像回滚和T+1断点恢复的测试与发布验收。

**业务能力版本 B（按表启用 polling）**

1. 以已经验证的 A 作为线上稳定镜像启动临时 query 容器。
2. 使用 12.4 的统一 drain 流程升级 B；先只启用一张 polling 表，其余表继续 T+1。
3. 验证 checkpoint、错误池、对账、资源隔离和切换回滚后，再逐表修改 `sync-mode`。

从 B 开始，T+1 与 polling 可以在同一新版实例中同时生效，统一 drain 会同时覆盖两套引擎；但同一张表在同一时刻仍只能属于一种自动同步模式。

### 12.5 健康与就绪

`/health` 只表示 JVM 存活；更新脚本应使用更严格的 `/ready`：

- `query`模式：ES可访问，并对一张已启用表的真实业务Alias执行`size=0`轻量查询；没有启用表时只检查ES连接。
- 版本A的`all`模式：在查询ready之外，存在已启用T+1表且T+1总开关开启时要求`sano_import_task`索引可访问；总开关关闭时返回`T_PLUS_ONE_DISABLED_BY_GLOBAL_SWITCH`但不伪装成运行中。
- 版本A若误配置任何polling表，`/ready`必须返回503并包含`POLLING_ENGINE_NOT_IMPLEMENTED`，阻止错误配置被发布。
- 版本B实现后，再扩展`/ready`检查checkpoint索引、租约协调器和polling总开关状态。

同步表暂时`PAUSED`或T+1任务失败不应使整个查询服务unhealthy。版本A通过`/internal/sync/drain/status`展示协调器、T+1运行态、持久任务快照和drain结果；版本B再新增表级状态接口，并由Lark明确通知异常表。

## 13. 推荐代码边界

延迟轮询不是现有按天全量导入的子模块，应使用独立顶层包，避免生命周期、任务状态、索引切换逻辑互相影响：

```text
com.tsd.sano.es
├── sync                     # importer/polling 共用的模式和资源协调
│   ├── config
│   │   ├── TableSyncMode.java
│   │   ├── EsServiceMode.java
│   │   └── EsServiceModeManager.java
│   ├── service
│   │   ├── GlobalEsWritePermitManager.java
│   │   ├── GlobalSyncMemoryLimiter.java
│   │   ├── SyncDrainCoordinator.java
│   │   └── ReconcileTaskRepository.java  # 版本B待实现
├── controller
│   ├── ReadyController.java
│   └── SyncDrainController.java
├── importer                 # 现有T+1/历史补数及当前Lark导入通知
│   └── notify
│       ├── ImportNotifyService.java
│       └── LarkImportNotifier.java
├── polling                  # 新增低延迟轮询同步
│   ├── config
│   │   └── EsPollingProperties.java  # 仅保存轮询引擎全局参数
│   ├── model
│   │   ├── SyncBatch.java
│   │   ├── SyncCursor.java
│   │   ├── SyncCheckpoint.java
│   │   └── SyncStatus.java
│   ├── service
│   │   ├── PollingSyncCoordinator.java
│   │   ├── PollingTableWorker.java
│   │   ├── PollingBulkWriter.java
│   │   ├── OrderedCheckpointTracker.java
│   │   ├── TableStateWriter.java
│   │   ├── SyncCheckpointService.java
│   │   └── DailyReconciliationService.java
└── search                   # 查询逻辑
```

其中`polling`目录和`ReconcileTaskRepository`在版本A中尚不存在；其余列出的模式、资源、drain、ready及Lark通知类均以当前源码为准。版本B可以复用通知接口，但不应为了目录对称提前搬移已经稳定运行的T+1通知实现。

实现时避免将无限循环、SQL、ES Bulk、checkpoint、Lark 通知全部堆在一个类。边界建议：

1. 继续由现有 `EsImportProperties.TableConfig` 绑定 `sano.import.tables`；配置加载时直接完成默认值规范化、校验，并生成T+1与polling两个只读列表，不再复制为另一套表定义模型。
2. `PollingTableWorker` 负责一张表的查询节流和批次投递。
3. `PollingBulkWriter` 只负责按 `batch.target_index` 生成 ES Bulk、错误分类与回调。
4. `GlobalEsWritePermitManager` 位于公共同步层，为 polling 保留实时额度并限制 T+1 借用并发；不保存任何表数据或 checkpoint。
5. `GlobalSyncMemoryLimiter` 统一计算 importer 与 polling 已排队、在途和等待重试的数据估算字节数。
6. `OrderedCheckpointTracker` 按表维护有界 sequence 窗口，只向状态写入器提交连续终态的日期和 ID。
7. `TableStateWriter` 是单表 checkpoint 文档的唯一写入者，串行处理租约、进度、暂停、恢复和释放；`SyncCheckpointService` 提供实时 Get、OCC update 和索引访问能力，不自行并发改状态。
8. `ReconcileTaskRepository` 位于公共同步层，由 T+1 和 polling 共同创建带租约/OCC 的日期对账任务；`DailyReconciliationService` 负责统计、MySQL 到 ES 的 ID 差异和补偿编排。
9. 现有 `EsBulkImporter` 保留任务上下文，但必须先修复“最大成功 item ID”断点问题，并在发送 ES 请求前接入公共写入许可证和内存预算；轮询链路不直接依赖其任务上下文、线程池或 Alias 切换流程。
10. 通知接口可以共用，轮询状态模型、队列、checkpoint 和调度器不得放入 `importer`。

## 14. 实施顺序与验收

### 14.1 实施顺序

1. [x] 修复现有T+1的连续批次安全断点并接入共享写入许可证/内存预算；用`SUCCESS`、`TIMEOUT_PARTIAL`、单条失败和强停恢复验证不跳ID。统一异步对账仍属于版本B，不计入本项已完成范围。
2. [x] 实现`server-mode`、统一drain/status/cancel和部署脚本回滚陷阱，发布基础能力版本A；所有线上表保持T+1，polling关闭。
3. [x] 在`sano.import.tables`的`TableConfig`中增加`sync-mode`和`bootstrap-start-date`，默认模式为`t-plus-one`；配置加载时直接校验并生成T+1、polling两个只读分类集合。
4. [ ] 新建checkpoint/error索引、串行`TableStateWriter`与轮询配置模型，实现polling单表启动、租约、暂停和恢复。
5. [ ] 实现单表按`sync_date + id`逐日轮询、每表独立队列/Bulk Workers、有界sequence窗口，并正确处理读取日期领先持久checkpoint。
6. [ ] 实现可重试/不可重试错误分类：单条错误可靠进入错误池后继续，系统性故障熔断暂停；补齐Lark通知。
7. [ ] 实现带owner/lease/OCC的异步快速统计对账、条件更新表级一致性状态、定向ID差异检查和补偿。
8. [ ] 在测试环境同时启用至少一张`polling`表和一张`t-plus-one`表连续运行数天，确认调度、资源、Alias、对账和drain完全隔离，再发布版本B并逐表启用polling。
9. [ ] 生产环境可保留现有T+1导入作为人工紧急回填工具；使用前先暂停对应表轮询并排空在途请求，完成回填后再从可信checkpoint恢复，不能并行写同一张表。

### 14.2 验收场景

1. 应用重启：从 `last_committed_date + last_committed_id` 继续，ES 不产生重复文档。
2. ES 短暂不可用：该表暂停或重试，恢复后完整追平。
3. 单条 Mapping 错误：错误索引和 Lark 有明确 ID；错误记录确认落盘后 checkpoint 有序越过该项，表保持 `RUNNING + DEGRADED`，后续数据无真空；错误池不可写时表才暂停。
4. ES 慢写：本表队列升高、读取自动减速，其他表仍可正常同步。
5. 日期推进：历史日期读空后插入 `DATE_CLOSE` 并立即用内存游标读取下一天；D+1 Bulk 可以提前成功，但持久 checkpoint 必须等 D 的 sequence 完成并落下对账任务后有序推进；今天读空时不进入未来日期。
6. MySQL 与 ES count 不一致：能找出具体缺失 ID，定向补偿后复核。
7. 大量积压：返回满页时持续追赶，队列有界，JVM 不出现无控制内存增长。
8. 强制停止 Docker：重启后重新读取未提交批次，checkpoint 不越过尚未成功且未可靠进入错误池的数据。
9. 更新部署：query-only 先接管查询，drain 完成后更新主实例，全程查询可用。
10. 混合模式：`polling` 表持续低延迟推进，同时 `t-plus-one` 表只在 Cron 到点创建任务；双方不会为对方表创建自动任务。
11. 乱序 Bulk：sequence 101 长时间重试而 102 至 108 先完成时，checkpoint 不越过 101，Reader 在未提交窗口达到8后停止查询，内存不继续增长；101 终态后连续提交。
12. 租约与 checkpoint 并发：持续续租同时高频完成 Bulk，不出现正常竞争导致的409风暴；旧 lease token 的迟到回调不能修改新实例状态。
13. T+1 部分失败：低 ID 失败、高 ID 成功后触发 drain/强停，`TIMEOUT_PARTIAL` 只能保存连续安全 ID，恢复后低 ID 不被跳过。
14. 对账抢占与乱序：旧实例对账结果晚到或旧日期补跑完成，不能覆盖新 owner 或较新日期的 `consistency_status`。
15. 升级路径：polling关闭的基础版本A及query-only/drain/cancel/回滚演练已经完成；版本B必须以线上A为稳定镜像按safe流程升级。

## 15. 最终建议

对于当前系统，推荐先实现以下最小可靠版本：

```text
沿用sano.import.tables，为每个enabled表配置t-plus-one / polling唯一同步模式
    + t-plus-one表复用现有定时任务链路，不创建常驻轮询资源
    + polling表拥有独立Reader、队列、Bulk Workers、checkpoint和暂停状态
    + page_size=3000
    + 历史日期读空后插入DATE_CLOSE并立即读取下一天，关闭sequence只约束checkpoint而不阻塞Reader
    + 每表批次携带唯一 target_index，表间不共享数据队列
    + 每表最多2个Bulk Worker，全局公平Bulk许可证初始为3
    + 同表Bulk可并行，未提交sequence窗口默认最多8，单表状态写入器串行推进checkpoint和租约
    + 单条永久错误可靠进入错误池后继续并标记DEGRADED，系统性故障才熔断暂停
    + T+1使用连续批次安全断点，TIMEOUT_PARTIAL不采用最大成功item ID
    + 每个历史日期关闭时先持久化异步对账任务再推进日期，对账差异按默认千分之一容差分级并异步补偿，不阻塞新日期同步
    + T+1与polling共用带租约/OCC的异步对账任务，并防止旧日期结果覆盖新状态
    + 硬停机依靠持久化 checkpoint 和 ES _id 幂等恢复
    + polling关闭的基础能力版本A已上线；后续更新前启动query-only，版本B统一drain两套引擎后替换主实例
```

不建议第一版把全局 Bulk 许可证设置过大、静默丢弃永久错误、每天全量重导或使用复杂的动态速率算法。单条坏数据可以在错误池可靠留痕后继续，但不能无审计跳过。先确保“按天不跳游标、表间隔离、状态串行持久化、可暂停、可恢复、目标索引明确、能发现并补齐缺失数据”，再根据 ES 监控逐步提高全局并发。
