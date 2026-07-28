# 延迟轮询同步 ES 简化版设计

> 本文是当前 Polling 版本 B 的有效设计，以线上版本 A 和当前 `es-server` 源码为基线。
>
> 原《延迟轮询同步ES设计文档.md》描述的是已放弃的队列、并行 Bulk、Sequence 和 ES 租约方案，
> 不再作为当前开发依据。

## 1. 设计结论

Polling 按表建立独立 Worker。一张表内部严格串行：

```text
读取一批 MySQL
    -> 同步写入一个 ES Bulk
    -> 成功，或整批重试耗尽并告警
    -> 推进内存 ID
    -> 开始下一轮
```

不同表可以并行；同一进程中一张表最多只有一个 Worker、一个 SQL 和一个 Bulk。

当前不接入 Redis，也不再使用 ES Checkpoint 实现租约、过期、续期或 fencing token。现阶段部署
只允许一个 `all` 实例运行同步；升级期间可以并存一个 `query` 实例，但 `query` 不启动 T+1 和
Polling。未来确需运行多个 `all` 实例时，再使用 Redis 按 `tableName` 保存临时占用，并单独设计
领取、续期和故障接管。

## 2. 范围与取舍

当前实现包含：

1. T+1 与 Polling 按表选择同步模式。
2. Polling 单表串行读取和写入。
3. 每张表一条持久 Checkpoint。
4. 日期关闭延迟和跨日推进。
5. 独立的异步统计对账。
6. Polling 差异对应的人工 T+1 修复任务。
7. 历史索引异步、最佳努力删除。
8. all/query 运行角色、统一 drain、安全部署与回滚。

当前实现不包含：

- 单表 Reader 队列和多个 Bulk Worker。
- Sequence、有序提交窗口和每批 Checkpoint。
- 持久错误池。
- ES 租约、owner、token、过期、续期和时钟漂移保护。
- 持久化对账任务、对账领取和自动重试状态机。
- 自动定位缺失 ID 或自动补偿。
- 多个 `all` 实例同时运行。

## 3. 表配置与命名

`tableName` 是 MySQL 源表名，也是表配置、Checkpoint 文档和管理接口的唯一标识。

`indexAlias` 只用于聚合该表按日期创建的物理索引，未配置时由配置加载逻辑赋值为
`tableName`。

业务物理索引名称固定为：

```text
indexAlias_yyyyMMdd
```

内部索引不使用该规则。当前 Polling 仅需要：

```text
sano_sync_polling_checkpoint
```

同一张表只能配置一种自动同步模式：

```yaml
sano:
  import:
    common:
      drain-timeout-seconds: 600
      global-bulk-concurrency: 3
      polling-reserved-concurrency: 2
      t-plus-one-max-concurrency: 3

    t-plus-one:
      enabled: true
      queue-max-bytes: 128MB
      # 其余配置保持现有T+1参数

    polling:
      enabled: true
      max-active-tables: 5
      poll-interval: 5s
      date-close-delay: 10m
      read-batch-size: 3000
      bulk-retry-times: 2
      bulk-retry-interval: 1s

    tables:
      - enabled: true
        table-name: sano_wallet_coin_record
        index-alias: sano_wallet_coin_record
        mapping-file: sano_wallet_coin_record.json
        sync-mode: polling
        bootstrap-start-date: 2026-07-27
        reconcile: true
        delete-history-index: true
        reserve-days: 30
        id-column: id
        dt-column: dt
        dt-column-type: DATE
        where-sql:
```

## 4. Checkpoint

每张 Polling 表固定只有一条 Checkpoint，ES 文档 `_id` 使用 `tableName`。

字段如下：

| 字段 | 含义 |
| --- | --- |
| `table_name` | MySQL 源表名和文档唯一标识 |
| `index_alias` | 业务查询 Alias |
| `status` | `RUNNING` 或 `PAUSED` |
| `sync_date` | 下次恢复时继续读取的业务日期 |
| `last_id` | 下次恢复时继续使用的查询游标 |
| `last_error` | 最近一次导致暂停的错误摘要 |
| `last_started_at` | 最近一次 Worker 启动或人工恢复时间 |
| `last_stopped_at` | 最近一次优雅停止或错误暂停时间 |
| `updated_at` | 文档最近更新时间 |

Checkpoint 不在每次 Bulk 后更新。正常批次只推进 Worker 内存中的 `syncDate/lastId`，避免持续
写 ES 内部索引。只有以下生命周期边界创建或更新：

1. 首次启动且文档不存在时，按 `bootstrap-start-date, ID=0` 创建。
2. Worker 启动时确认持久状态仍为 `RUNNING`，并记录启动时间。
3. 日期关闭时原子推进到 `D+1, ID=0`。
4. 系统性错误时保存当前内存进度并置为 `PAUSED`。
5. 人工暂停或恢复时更新持久业务状态。
6. 部署 drain 或应用关闭时保存当前内存进度，状态继续保持 `RUNNING`。

异常宕机无法保存最新内存游标。重启后会从上一次生命周期 Checkpoint 重读，ES 文档 ID 使用
MySQL 主键，因此重复写入是覆盖，不会产生重复文档。

版本 B 尚未进入测试环境。若开发期间曾创建过旧结构的 Checkpoint 索引，应在测试前删除并通过
人工初始化接口按当前 Mapping 重新创建，避免把废弃字段误认为当前协议。

## 5. 启动与 Worker 编排

Spring 应用就绪后，`PollingCoordinator` 执行：

1. 判断当前是否为允许同步的 `all` 模式。
2. 判断 Polling 总开关和 Polling 表集合。
3. 检查 Checkpoint 内部索引是否已经人工初始化。
4. 为缺少 Checkpoint 的表创建唯一文档；单表查询或初始化失败时保留该表等待后续扫描重试。
5. 启动一个轻量扫描器和固定大小的表 Worker 执行器。
6. 对持久状态为 `RUNNING`、本机尚无 Worker 且存在并发槽位的表启动 Worker。
7. `PAUSED` 表不自动启动，等待人工恢复。

`activeWorkers` 以 `tableName` 为键，在单个 JVM 中防止同一表重复启动。它不是跨实例锁。

## 6. 单表同步主循环

### 6.1 MySQL 查询

每次查询使用：

```sql
SELECT *
FROM tableName
WHERE 日期条件
  AND idColumn > ?
ORDER BY idColumn ASC
LIMIT ?
```

`dt-column-type` 支持：

- `DATE`：`dtColumn = syncDate`
- `DATETIME`：`dtColumn >= D 00:00:00 AND dtColumn < D+1 00:00:00`

若配置了 `where-sql`，它作为完整业务日期条件使用。

程序业务日期和 MySQL 日期字段约定都按 UTC+8 本地时间理解，日期关闭使用
`LocalDate/LocalDateTime`，不做运行时系统时区换算。生命周期审计时间继续使用 `Instant`。

MySQL 查询同步等待，不设置 Polling 自定义查询超时和重试；数据库真实返回错误时由 Worker
进入系统性错误处理。

### 6.2 ES Bulk

一个 MySQL 读取批次对应一个完整 Bulk：

1. ES 文档 `_id` 使用 `id-column`。
2. 首次写入失败后，按配置重试整个 Bulk，默认再重试 2 次。
3. 请求异常、响应包含失败 item、响应数量不匹配，都视为整批失败。
4. 重试使用相同索引、文档 ID 和文档内容，重复执行保持幂等。
5. Bulk 期间收到 drain，也先完成本批全部重试，再处理停止请求。

重试耗尽后：

1. 记录表、日期、物理索引、首尾 ID 和错误原因。
2. 异步提交 Lark 通知。
3. 返回 Worker 并推进本批最大 ID，继续下一批。
4. 不暂停整张表。

这会允许 ES 暂时存在差异，最终由日期对账发现，并由运维通过人工 T+1 全量任务修复。

### 6.3 系统性错误

以下错误会停止当前表：

- MySQL 查询失败。
- 当日或下一日索引无法创建。
- Checkpoint 日期或状态异常。
- Worker 中未被整批 Bulk 规则吸收的其他运行错误。

Worker 保存当前内存日期和 ID，将 Checkpoint 置为 `PAUSED`，再提交停止通知。Checkpoint
暂时不可写时保持停止状态并间隔重试，不能继续读写后丢失可恢复边界。

## 7. 日期关闭与跨天闭环

`date-close-delay=10m` 表示日期 D 最早在 D+1 的 `00:10` 关闭。因此 D+1 数据通常也从
`00:10` 左右开始读取，这是为了给 D 的晚到记录留下明确窗口。

关闭 D 必须同时满足：

1. 当前本地日期已经晚于 D。
2. 本次 SQL 开始时间不早于 `D+1 00:00 + date-close-delay`。
3. 本次按 D 和当前 `lastId` 查询仍为空。

满足后按以下顺序执行：

```text
确认D日超过关闭延迟且关闭时间之后再次查询仍为空
    -> 异步、最佳努力删除到期历史索引
    -> 异步提交D日统计对账
        -> 查询MySQL总量、最小ID和最大ID
        -> MySQL总量为0时直接发送MYSQL_EMPTY通知，不查询ES
        -> MySQL总量大于0时主动刷新D日物理索引
        -> 查询ES总量、最小ID和最大ID
        -> 发送对账通知
    -> 创建并绑定D+1物理索引，固定最多尝试3次，两次重试前各等待5秒
    -> 创建成功后原子更新Checkpoint为D+1, ID=0
    -> Worker内存切换到D+1, ID=0
    -> 继续读取D+1
```

确认 D 已完成后立即提交对账和历史索引删除，不等待 D+1 索引或 Checkpoint。两者允许重复调用；
提交或执行失败只能记录日志、发送通知，不能回退日期、暂停当前表或阻断后续同步。

D+1 索引创建成功是推进 Checkpoint 的可靠前置条件。创建重试耗尽时保留
`D + 当前lastId + PAUSED`，人工恢复后从 D 的当前游标重新确认关闭条件。

## 8. 对账

对账是独立通用能力，不属于 Polling 的状态机，也不读写 Polling Checkpoint。

每张表通过 `reconcile` 决定是否实际执行。同步流程和人工接口都可以调用统一入口；入口发现
该表关闭对账时直接返回。

对账先查询指定日期的 MySQL：

- MySQL `COUNT(1)`
- MySQL `MIN(id)`
- MySQL `MAX(id)`

- MySQL 总量为 0 时直接发送 `MYSQL_EMPTY` 通知，不刷新或查询 ES。
- MySQL 总量大于 0 时，先刷新该日物理索引，再查询 ES 文档总数、最小 ID 和最大 ID。
- 结果一致、存在差异、MySQL无数据或执行失败，均发送 Lark 消息。

对账不保存任务、不自动重试、不逐条比对、不自动补数据，允许重复调用，也允许异步任务因
进程退出而丢失；运维可通过接口重新调用。

发现差异后，运维调用 Polling 修复接口创建指定表、指定日期的 T+1 全量任务。T+1 使用稳定
文档 ID 覆盖写入，完成缺失数据修复。

## 9. 历史索引删除

历史索引不使用全局每日扫描服务。

- T+1 在每个日期任务完成后调用 `EsIndexManager` 的既有删除逻辑。
- Polling 确认日期完成后、创建下一日索引前异步调用同一删除逻辑。
- 只按当前表配置的 `delete-history-index/reserve-days` 计算一个到期物理索引。
- 删除失败只记录日志，不影响同步主循环。

## 10. 单实例边界

当前支持的部署拓扑：

```text
一个常驻 all：查询 + T+1 + Polling
升级期间一个临时 query：仅查询
```

当前禁止：

- 两台服务器各运行一个 `all`。
- 同一服务器启动两个不同容器名的 `all`。
- 绕过部署脚本，在旧 `all` 未停止时启动新 `all`。

`query` 可以与 `all` 同时运行，因为它的同步能力在运行模式上被关闭。

如果未来需要多 `all`，应新增 Redis 临时占用：

```text
key: sano:es:polling:owner:{tableName}
value: instanceId
TTL: 明确的短期占用时间
```

届时必须重新设计原子领取、续期、故障接管和旧实例隔离；该临时协调状态不应再写回 ES
Checkpoint，也不能仅靠 JVM 内存互斥。

## 11. 优雅停止、升级与恢复

Polling drain：

1. 协调器停止扫描和启动新 Worker。
2. 向所有本机 Worker 设置停止标记。
3. `IDLE` Worker 立即保存内存进度。
4. 正在执行 SQL 的 Worker 等 SQL 返回；若已读到数据，仍完成该批 Bulk。
5. 正在执行 Bulk 的 Worker完成整个重试过程。
6. 每个 Worker 保存最终 `syncDate/lastId`，持久状态保持 `RUNNING`，然后退出。
7. 所有 Worker 保存成功且共享资源归还后，统一 drain 才能完成。

safe 升级顺序：

```text
启动临时 query
    -> Nginx 查询流量切到 query
    -> old all 执行统一 drain
    -> 确认 Polling Worker 全部保存 Checkpoint
    -> 停止并删除 old all
    -> 启动 new all
    -> new all 从 Checkpoint 恢复 Worker
    -> 严格 /ready 通过
    -> Nginx 查询流量切回 all
    -> 停止临时 query
```

部署脚本必须保证旧 `all` 停止后才启动新 `all`，不能通过并行启动两个 `all` 缩短切换时间。

部署取消时，旧 Worker 先退出并保存进度，然后重新启动协调器。新版本失败回滚时，先停止新
`all`，再恢复旧 `all`，旧版本从相同 Checkpoint 继续。

## 12. 管理与就绪接口

| 接口 | 用途 |
| --- | --- |
| `GET /import/createSyncInternalIndices` | 人工创建 Polling Checkpoint 内部索引 |
| `GET /import/reconcile?tableName=...&date=yyyyMMdd` | 人工异步发起统计对账 |
| `GET /import/pollingRepairTask?tableName=...&date=yyyyMMdd` | 创建 Polling 日期的 T+1 全量修复任务 |
| `GET /internal/sync/status` | 查看持久业务进度、当前 JVM Worker 和 drain 状态 |
| `POST /internal/sync/polling/{tableName}/pause` | 先停止 Worker并保存进度，再持久暂停 |
| `POST /internal/sync/polling/{tableName}/resume` | 恢复持久状态并允许本机重新启动 Worker |
| `POST /internal/sync/drain` | 发起统一排空 |
| `GET /internal/sync/drain/status` | 查询排空结果 |
| `POST /internal/sync/drain/cancel` | 取消排空并恢复旧实例 |
| `GET /ready` | 校验当前角色需要的查询和同步能力 |

`/ready` 对 `all` 的 Polling 检查包括：

1. Checkpoint 索引存在。
2. Polling 协调器为 `RUNNING`。
3. 每张 `RUNNING` 表有本机 Worker，或因 `max-active-tables` 正常等待槽位。
4. `PAUSED` 是单表业务状态，需要状态接口和告警处理，但不使整个查询服务失活。

## 13. 验收条件

1. `src/main`、YAML、Compose 和部署脚本中不存在 ES 租约字段、续期线程或时钟漂移逻辑。
2. 一张表只有一条 Checkpoint，正常 Bulk 不频繁更新它。
3. DATE 和 DATETIME 两种日期条件查询正确。
4. Bulk 失败时完整重试，耗尽后告警并继续，不暂停表。
5. drain 在已读取批次写完后保存最新内存游标。
6. 日期关闭延迟生效，下一日索引、Checkpoint、对账和删除顺序正确。
7. 对账差异或失败不阻断下一日。
8. 人工暂停、恢复、对账和 T+1 修复接口可用。
9. query 模式不启动任何同步 Worker。
10. safe 部署过程任意时刻最多只有一个 `all`。
11. 构建和真实 Spring 启动通过。
12. 验证完成后仓库中不保留 `src/test`、测试依赖或测试专用生产代码。
