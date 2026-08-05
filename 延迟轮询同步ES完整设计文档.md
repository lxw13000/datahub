# 延迟轮询同步 ES 完整设计文档

## 1. 文档范围

> 与源码核对日期：2026-08-05。

本文档以当前 `es-server` 源码、`application-dev.yml`、`application-develop.yml`、`application-test.yml`、`application-prod.yml`、Docker Compose、Nginx 和部署脚本为准，只描述已经实现的功能。

当前系统支持：

- 同一份表目录按表选择 `t-plus-one` 或 `polling`。
- T+1 按业务日期执行全量导入。
- Polling 按业务日期和递增 ID 持续同步。
- 每张 Polling 表一条持久化 checkpoint。
- Polling 跨天关闭、下一日索引创建和异步统计对账。
- 对账发现差异后，通过人工接口提交 T+1 全量覆盖修复。
- T+1 与 Polling 共用 ES Bulk 并发控制。
- `all`、`query` 两种运行模式。
- T+1 与 Polling 统一 drain、状态查询及安全升级。
- 飞书/Lark 同步结果和异常通知。

当前 Polling 不使用 Redis、分布式租约、持久化错误明细、持久化对账任务、Reader 队列、多 Bulk Worker 或逐 Bulk checkpoint。

## 2. 核心对象和命名

### 2.1 表名、Alias 和物理索引

三个名称职责不同：

| 名称 | 含义 | 示例 |
| --- | --- | --- |
| `table-name` | MySQL 源表名，也是表配置和 Polling checkpoint 的唯一标识 | `sano_wallet_coin_record` |
| `index-alias` | ES 业务查询 Alias；为空时由配置加载器赋值为 `table-name` | `sano_wallet_coin_record` |
| `index-name` | 单日物理索引，规则为 `indexAlias_yyyyMMdd` | `sano_wallet_coin_record_20260727` |

业务查询使用 Alias，不直接依赖具体日期索引。Alias 可以同时指向保留期内的多个物理索引。

### 2.2 内部索引

当前只有两个同步内部索引：

| 索引 | 文档 ID | 用途 |
| --- | --- | --- |
| `sano_import_task` | `tableName_yyyyMMdd` | T+1 任务及 Polling 历史修复任务 |
| `sano_sync_polling_checkpoint` | `tableName` | Polling 单表持久业务状态和恢复进度 |

两个内部索引都不会由普通业务读写或应用启动隐式创建，必须通过初始化接口人工创建。

### 2.3 服务模式

配置项为：

```yaml
sano:
  server-mode: ${SANO_SERVER_MODE:all}
```

支持两种模式：

| 模式 | 查询 | T+1 | Polling | 同步管理接口 |
| --- | --- | --- | --- | --- |
| `all` | 开放 | 按总开关启用 | 按总开关启用 | 开放 |
| `query` | 开放 | 禁止执行 | 禁止执行 | 同步操作被运行时门禁拒绝 |

所有 Bean 在两种模式下都会注册。`query` 不是缺少执行器或同步组件，而是在调度入口、人工入口和底层导入服务处通过 `EsServiceModeManager` 禁止同步能力。

当前 `dev`、`develop` 配置默认使用 `query`；测试和正式常驻容器使用 `all`，安全升级期间的临时容器固定使用 `query`。

### 2.4 代码模块

`com.tsd.sano.es` 下的 `core`、`controller`、`modules` 相互平行：

| 模块 | 职责 |
| --- | --- |
| `core` | Spring、ES 客户端、异常、鉴权和通用返回等基础设施 |
| `controller` | 查询、导入、就绪和 drain 等 HTTP 入口 |
| `modules.config` | 同步表目录、T+1、Polling、服务模式及共享写入参数 |
| `modules.index` | Mapping 加载和通用 ES 索引远程原子操作 |
| `modules.notify` | 通用通知消息、Lark 通道、总开关和通道分发 |
| `modules.tplusone` | T+1 模型、Reader/Bulk 管线、任务存储、索引生命周期和业务通知 |
| `modules.polling` | Polling checkpoint、日期索引、Reader/Bulk、Worker、协调器和业务通知 |
| `modules.reconcile` | 独立统计对账及对账结果通知内容 |
| `modules.coordination` | 两种同步模式共享的写入许可、drain 和状态汇总 |
| `modules.search` | 查询常量、查询服务和查询工具 |

`EsIndexManager` 不感知同步模式和业务日期，只提供通用 ES 远程操作。`TPlusOneIndexService` 和 `PollingIndexService` 分别拥有各自完整的索引调用顺序和异常策略；`PollingIndexService` 同时管理 Polling 日期索引与 checkpoint。

`NotifyService` 不判断 T+1、Polling 或对账业务，只负责启用检查、通道遍历和异常隔离。具体模块自行决定发送时机并组织标题与正文。

## 3. 配置模型

同步配置前缀为 `sano.import`，结构为：

```yaml
sano:
  import:
    common:
    t-plus-one:
    polling:
    tables:
```

### 3.1 公共配置

`common` 只保存两种同步引擎共同使用的 drain 和 ES 写入资源参数：

| 配置 | 当前默认值 | 作用 |
| --- | ---: | --- |
| `drain-timeout-seconds` | `600` | 应用关闭、人工暂停或部署 drain 的等待上限 |
| `global-bulk-concurrency` | Java及测试默认 `3`；正式默认 `5` | T+1 与 Polling 合计在途 ES Bulk 上限 |
| `polling-reserved-concurrency` | `2` | Polling 有等待请求时为其保留的额度 |
| `t-plus-one-max-concurrency` | `3` | Polling 无等待请求时 T+1 可使用的最大额度 |

### 3.2 T+1 配置

T+1 配置包括：

- 总开关和 Cron。
- 单轮最大运行时间、待任务拉取数。
- MySQL Reader 批大小。
- Bulk Worker 数量和 BlockingQueue 容量。
- `queue-max-bytes`，只约束T+1排队、在途和重试批次的估算内存。
- Bulk 文档数、目标大小、重试次数和间隔。
- 允许失败文档数、失败率。
- Refresh 和副本调整开关。

测试环境 Cron 为每天 `02:30`，正式环境为每天 `01:30`。测试环境单轮最大运行 5 分钟，正式环境为 480 分钟。

### 3.3 Polling 配置

| 配置 | 当前默认值 | 作用 |
| --- | ---: | --- |
| `enabled` | Java 默认 `false`；测试主实例覆盖为 `true`，正式主实例保持 `false` | 是否启动 Polling |
| `max-active-tables` | `5` | 单实例最多并行运行的 Polling 表数 |
| `poll-interval` | `5s` | 协调器扫描间隔、历史日期关闭前轮询间隔及 checkpoint 持久化重试间隔 |
| `date-close-delay` | `10m` | 次日零点后继续接收旧日期晚到数据的时间 |
| `read-batch-size` | `3000` | 单次 MySQL 查询最大行数 |
| `bulk-retry-times` | `2` | ES 整批失败后的重试次数，不含首次 |
| `bulk-retry-interval` | `1s` | Polling 整批重试等待时间 |

测试环境 Docker Compose 中常驻 `all` 实例将 `SANO_ES_POLLING_ENABLED` 默认设为 `true`；正式环境常驻 `all` 实例默认设为 `false`。两套环境的临时 `query` 实例均固定为 `false`。

### 3.4 单表配置

```yaml
- enabled: true
  table-name: sano_wallet_coin_record
  index-alias: sano_wallet_coin_record
  mapping-file: sano_wallet_coin_record.json
  sync-mode: polling
  bootstrap-start-date: 2026-07-27
  reconcile: true
  delete-history-index: true
  reserve-days: 60
  id-column: id
  dt-column: dt
  dt-column-type: DATE
  where-sql:
```

配置加载时一次完成规范化、验证和模式分组：

- `enabled=false` 的表不参与同步。
- 启用表以 `table-name` 判断重复。
- `index-alias` 为空时使用 `table-name`。
- `sync-mode` 为空时默认 `T_PLUS_ONE`。
- `dt-column-type` 只支持 `DATE` 和 `DATETIME`。
- Polling 表必须设置 `bootstrap-start-date`。
- `delete-history-index=true` 时，`reserve-days` 必须大于 0。
- 加载完成后分别形成只读的 T+1 表集合和 Polling 表集合。

当前测试配置中：

- `sano_wallet_coin_record` 为 `polling`，首次日期为 `2026-07-27`。
- `sano_wallet_diamond_record`、`sano_wallet_lucky_diamond_record_10m`、`sano_game_record` 为 `t-plus-one`。
- 所有表保留 30 天。

当前正式配置关闭 Polling 总开关，四张启用表均为 `t-plus-one`，历史索引保留 60 天。金币表仍保留 `bootstrap-start-date=2026-07-29`，但 T+1 模式不会读取该字段；以后重新切换为 Polling 前必须结合实际 checkpoint 和目标日期重新确认。

测试和正式配置中的四张启用表当前均设置 `reconcile=true`；T+1 成功后都会异步提交统计对账，测试环境金币表在 Polling 日期关闭时提交对账。

## 4. 应用启动流程

### 4.1 Spring 启动

应用启动后：

1. 加载环境配置并规范化表目录。
2. 注册查询、T+1、Polling、对账、通知、状态和 drain 相关 Bean。
3. 启用 Spring 定时调度和异步执行器。
4. Web 容器开始提供 `/health`。
5. `ApplicationReadyEvent` 触发 `PollingCoordinator.start()`。

### 4.2 Polling 启动

Polling 协调器按以下顺序启动：

1. 如果当前为 `query` 模式，将协调器置为 `DISABLED`。
2. 如果 Polling 总开关关闭或没有 Polling 表，将协调器置为 `DISABLED`。
3. 检查 `sano_sync_polling_checkpoint` 是否存在。
4. 内部索引缺失时置为 `INITIALIZATION_FAILED`，不自动建索引。
5. 对每张 Polling 表初始化唯一 checkpoint；已存在时校验 Alias 并复用，不覆盖进度。
6. 单表 checkpoint 查询或初始化失败时，仅将该表保留为待初始化状态，后续扫描独立重试；其他表继续启动。
7. 创建一个协调器调度线程。
8. 创建大小为 `max-active-tables` 的 Worker 线程池。
9. 按 `poll-interval` 扫描尚未运行的表；原子获取最新 checkpoint 并判断是否为 `RUNNING`。
10. 单表 checkpoint 启动异常只跳过当前表并等待后续扫描重试，不中断其他表。
11. 为每张允许运行的表准备当前日期物理索引和 Alias。
12. 启动该表唯一的 `PollingTableWorker`。

当前设计只允许一个常驻 `all` 实例。安全升级期间额外启动的实例固定为 `query`，不会创建 Polling Worker。

### 4.3 T+1 触发

T+1 不由 `ApplicationReadyEvent` 启动。Spring 启动时只注册定时器、任务服务和导入管线，真正执行发生在 Cron 到点或人工接口提交之后。

T+1 有三种触发方式：

- Cron 自动执行昨天的数据。
- 人工提交全部 T+1 表的日期范围。
- 人工提交指定 T+1 表的日期范围。

Polling 历史修复也复用 T+1 任务索引和导入管线，但只允许修复 checkpoint 当前日期之前、且物理索引已经存在的日期。

## 5. T+1 同步流程

### 5.1 自动入口和任务扫描

T+1 自动同步的完整入口为：

```text
Spring定时器到达配置的cron时间
→ 调用TPlusOneImportTask.importYesterday()
→ 判断实例类型
   → all：允许继续
   → query：直接返回，不启动同步
→ 判断T+1总开关
   → sano.import.t-plus-one.enabled=false：直接返回
→ 尝试获取当前JVM唯一T+1 Dispatcher执行权
   → 已有定时任务、人工任务或Polling历史修复正在执行：本轮跳过
   → drain正在进行：本轮跳过
→ 计算导入日期为昨天
→ 计算本轮最大运行截止时间deadline
→ 修复遗留RUNNING任务
   → 查询超过max-run-minutes仍为RUNNING的任务
   → 更新为TIMEOUT_PARTIAL
   → 保留lastSuccessId，等待断点续跑
→ 遍历T+1配置表集合
→ 为每张表创建唯一PENDING任务
   → taskId = tableName_yyyyMMdd
   → indexName = indexAlias_yyyyMMdd
   → 使用ES create语义防止重复创建
   → 单表创建失败只记录错误，其他表继续
→ 循环查询sano_import_task待执行任务
   → 查询PENDING、TIMEOUT_PARTIAL
   → 按import_date升序
   → 同一天按created_at升序
→ 判断本轮deadline和drain
   → 已到deadline：不再启动下一条任务
   → drain已开始：停止扫描
→ 逐条串行执行任务
```

T+1 没有应用启动后的立即恢复扫描。应用异常重启后，持久任务需要等待下一次 Cron 或人工入口再次触发 Dispatcher。

### 5.2 单条任务准备

单条任务开始时按以下顺序处理：

```text
原子注册当前唯一活动T+1任务
→ 解析任务日期和表配置
→ 判断任务属于：
   → 普通T+1表
   → Polling已关闭历史日期修复
→ TIMEOUT_PARTIAL任务读取lastSuccessId作为startId
→ 组装TPlusOneImportConfig
   → tableName
   → indexAlias
   → indexName
   → importDate
   → mappingFile
   → idColumn
   → dtColumn、dtColumnType
   → whereSql
   → 历史保留配置
→ 先将持久任务更新为RUNNING
→ 创建ImportContext
→ 将Reader/Bulk上下文注册给drain协调器
→ 统计MySQL目标日期总数
```

如果 MySQL 总数为 0：

```text
不创建空物理索引
→ 不绑定Alias
→ 按保留策略尝试删除一个到期历史索引
→ 任务持久化为SUCCESS
→ 发送成功通知
→ 异步对账
   → MySQL仍为空时发送MYSQL_EMPTY通知
```

如果 MySQL 存在数据：

```text
检查目标物理索引是否存在
→ 不存在：
   → startId=0：创建物理索引，但暂不绑定Alias
   → startId>0：断点索引缺失，任务失败
→ 已存在：
   → 复用现有索引
   → TIMEOUT_PARTIAL从lastSuccessId之后继续
→ 按配置临时设置refresh_interval=-1
→ 按配置临时设置number_of_replicas=0
→ 启动Bulk Dispatcher和多个Bulk Worker
```

新建索引在完整导入成功前不绑定 Alias，防止业务查询读到半成品索引。Polling 历史修复复用已经存在并绑定 Alias 的历史物理索引，只执行全量 upsert。

### 5.3 Reader 调用链

```text
Reader准备读取下一批
→ 检查Bulk侧是否已经失败
→ 检查drain信号
→ 检查本轮deadline
→ 从TPlusOneMemoryLimiter预留估算内存
→ MySQL按业务日期和lastId查询
   → DATE：dtColumn = importDate
   → DATETIME：当天左闭右开
   → whereSql非空：直接使用完整where条件
→ SQL分页：
   SELECT *
   WHERE 日期条件
     AND idColumn > lastId
   ORDER BY idColumn ASC
   LIMIT readBatchSize
→ 使用本页最后一条ID更新Reader读取游标
→ 为该Reader批次分配递增sequence
→ 将批次放入有界BlockingQueue
→ 队列满时等待Bulk消费，形成背压
→ 查询为空或不足一页时，为每个Bulk Worker发送结束标记
```

Reader 查询返回的批次先按实际数据估算值调整内存 Reservation，再进入队列；正常完成、异常清理和 Bulk 完成路径都会释放对应 Reservation。

### 5.4 Bulk 调用链

```text
多个Bulk Worker并行消费Reader队列
→ 每个Reader批次继续按以下条件拆分子Bulk：
   → bulk-actions
   → bulk-size-mb估算上限
→ 获取T+1、Polling共用的全局ES写入许可
→ 发送ES Bulk
→ 请求级IOException：
   → 整个子Bulk重试
   → 重试耗尽后任务整体失败
→ Bulk item级失败：
   → 记录失败数量
   → 失败ID写入import-error专项日志
   → 继续处理当前Reader批次和后续批次
   → 当前Reader批次标记为checkpoint不安全
→ Bulk完成后释放全局许可
→ Reader批次完成后释放内存Reservation
```

请求级异常和 item 级失败的处理不同：请求级异常重试耗尽会中止整条任务；item 级失败允许继续，但会进入最终失败数量、失败率校验，并阻止安全断点越过该 Reader 批次。

### 5.5 多Worker有序安全断点

多个 Bulk Worker 可能乱序完成。例如 sequence 2、3 先于 sequence 1 完成时，`ImportContext` 暂存完成结果，只有 sequence 1 完成后才按读取顺序连续推进：

```text
sequence=1成功 → lastSuccessId推进到1.lastId
sequence=2成功 → lastSuccessId推进到2.lastId
sequence=3成功 → lastSuccessId推进到3.lastId
```

如果 sequence 2 存在 item 失败：

```text
sequence=1成功 → lastSuccessId推进到1.lastId
sequence=2不安全 → 安全断点停止
sequence=3即使成功 → 不再推进lastSuccessId
```

任务暂停后从 sequence 1 的最后 ID 重新读取，依靠固定 ES 文档 ID 幂等覆盖。该安全断点只在任务变为 `TIMEOUT_PARTIAL` 时持久化到 `sano_import_task`，不会在每个 Bulk 完成时更新任务文档。

### 5.6 正常完成和Alias绑定

```text
Reader完成全部数据读取
→ 等待所有Bulk Worker结束
→ 检查失败数量和失败率
   → 全部失败：任务失败，不绑定Alias
   → 超过max-failed-documents：任务失败
   → 超过max-failure-rate：任务失败
   → 少量失败且在阈值内：允许继续
→ 恢复refresh_interval
→ 恢复副本数
→ 主动Refresh物理索引
→ 绑定业务Alias
→ 删除一个到期历史索引
→ 持久任务更新为SUCCESS
→ 发送成功通知
→ 异步对账
→ 继续扫描下一条PENDING或TIMEOUT_PARTIAL任务
```

历史索引删除按当前导入日期和保留天数只计算一个确定的过期物理索引，不扫描 Alias 下的所有索引；删除失败不改变已经完成的任务状态。

### 5.7 deadline、drain和续跑

Reader 达到 `max-run-minutes` 或收到部署 drain 信号时：

```text
不再发起下一批MySQL查询
→ 已经读取和入队的数据继续完成Bulk
→ 等待全部Bulk Worker结束
→ 恢复索引refresh和副本
→ 主动Refresh已写入数据
→ 不绑定半成品索引的Alias
→ 保存最后连续成功的lastSuccessId
→ 持久任务更新为TIMEOUT_PARTIAL
→ 发送暂停通知
```

普通 deadline 产生的 `TIMEOUT_PARTIAL` 等待下一次 Cron 或人工任务扫描后继续。下一轮复用原物理索引，从 `lastSuccessId` 之后读取。

部署 drain 还会记录本次操作打断的 task ID：

```text
TIMEOUT_PARTIAL持久化成功
→ Dispatcher退出
→ drain确认任务持久状态、队列、Bulk许可和内存Reservation均已安全收敛
→ drain返回DRAINED或DRAINED_WITH_ERRORS
```

取消 drain 后只精确领取本次 drain 产生的 `TIMEOUT_PARTIAL` 任务并立即续跑，不扫描和误启动其他历史暂停任务。

### 5.8 异常处理

```text
COUNT、MySQL读取、索引创建、ES请求、Bulk线程或任务状态发生异常
→ 中止Reader和Bulk流水线
→ 尽力恢复索引refresh和副本
→ 释放全部内存Reservation
→ 持久任务更新为FAILED
→ 发送失败通知
→ 注销当前活动任务
→ 当前Dispatcher继续执行后续任务
```

全部文档失败，或者失败数量、失败率超过阈值时，同样按失败处理，不绑定 Alias。任务终态写入结果不确定时，统一 drain 不会把该任务视为安全完成。

### 5.9 人工入口

三类人工调用均复用同一个持久任务队列和串行 Dispatcher：

| 接口 | 作用 |
| --- | --- |
| `/import/importAppointDay` | 为全部 T+1 表提交指定单日任务 |
| `/import/importDateRange` | 为全部 T+1 表提交指定日期范围任务 |
| `/import/importTableDateRange` | 为指定 T+1 表提交日期范围任务 |
| `/import/repairPollingDate` | 为 Polling 已关闭历史日期提交 T+1 全量 upsert 修复 |

Polling 历史修复必须满足：

- 目标表当前配置为 `polling`。
- 修复日期早于 Polling checkpoint 当前日期。
- 目标历史物理索引已经存在。
- 同表同日任务不处于 `PENDING`、`RUNNING` 或 `TIMEOUT_PARTIAL`。

Polling 修复允许把已经结束的同 ID 任务重置为 `PENDING`。全量 upsert 可以补齐缺失或覆盖已有文档，但不会删除 ES 中源端已经不存在的多余文档；修复成功后会再次异步对账。

## 6. Polling 单表同步流程

当前实现从应用启动到跨天循环的完整流程为：

```text
ApplicationReadyEvent
→ 判断实例类型（all/query）
→ 判断Polling总开关
→ 读取Polling配置表集合
→ 检查checkpoint内部索引
→ 逐表查询或初始化唯一SyncCheckpoint
   → 单表失败只保留该表等待重试
   → 其他表继续启动
→ 协调器根据表集合循环启动单表Worker
→ 原子获取该表最新SyncCheckpoint
→ 判断持久状态是否为RUNNING
→ 准备当前日期物理索引
→ MySQL按syncDate、lastId批次查询
→ ES整批Bulk写入并重试
   → 成功后推进Worker内存lastId
   → 重试耗尽后通知并推进内存lastId，继续下一批
→ 当天查询为空时按固定间隔逐级退避
→ 确认D日超过关闭延迟且关闭时间之后再次查询仍为空
→ 异步删除到期历史索引
→ 异步对账D日
   → 查询MySQL总量、最小ID和最大ID
   → MySQL总量为0时直接发送MYSQL_EMPTY通知，不查询ES
   → MySQL总量大于0时主动刷新D日物理索引
   → 查询ES总量、最小ID和最大ID
   → 发送匹配、不匹配或失败通知
→ 创建D+1索引，固定最多尝试3次，两次重试前各等待5秒
→ 创建成功后原子推进checkpoint到D+1、lastId=0
→ Worker切换到D+1并继续循环同步
```

### 6.1 串行模型

每张 Polling 表只有一个 Worker。Worker 在同一个线程中循环执行：

1. 查询一批 MySQL。
2. 同步写入一个 ES Bulk。
3. Bulk 返回后更新内存 `lastId`。
4. 立即进入下一轮查询；当天空批次按固定退避等待，历史日期在关闭时间前按 `poll-interval` 等待。

不同表可以并行，最大并发表数由 `max-active-tables` 控制。

Polling 没有 Reader 队列和多 Bulk Worker，因此不会出现同表 Bulk 乱序，也不需要 sequence 提交器。

### 6.2 MySQL 查询

默认查询形式：

```sql
SELECT *
FROM table_name
WHERE business_date_condition
  AND id_column > ?
ORDER BY id_column ASC
LIMIT ?
```

日期条件：

- `DATE`：`dt_column = ?`
- `DATETIME`：`dt_column >= 当天00:00:00 AND dt_column < 次日00:00:00`
- `where-sql` 非空：将它作为完整业务过滤条件，再追加 `id > ?`

Polling 使用 `JdbcTemplate.queryForList` 同步等待 MySQL 返回，不设置独立查询超时和查询重试。数据库返回异常时由 Worker 按系统性错误处理。

当前日期连续查询为空时使用代码内固定的 `5、10、30、60、300` 秒退避，达到 300 秒后保持该间隔；重新读到数据后从 5 秒重新开始。该等待可被 drain 停止信号提前唤醒。

业务日期使用 `LocalDate` 和 `LocalDateTime`，按程序与 MySQL 共同约定的 UTC+8 本地时间计算，不做时区转换。checkpoint 时间戳和运行状态时间使用 `Instant`。

### 6.3 ES Bulk

一个 MySQL 查询批次构造成一个 ES Bulk：

- 目标索引为当前日期物理索引。
- ES 文档 ID 使用 `id-column`。
- 文档内容为当前 MySQL 行。
- 不主动 Refresh。
- 整个重试过程复用同一 Bulk 请求。
- 请求异常、响应含失败 item、响应 item 数量不一致都视为整批失败。
- 当前配置为首次请求加 2 次完整重试。

成功时返回 `true`。重试耗尽时：

1. 记录表名、日期、索引、首尾 ID、批大小、尝试次数和失败原因。
2. 异步发送 Lark 失败通知。
3. 返回 `false`。
4. Worker 仍将内存 `lastId` 推进到该查询批次的末尾。
5. 继续读取下一批，不暂停整张表。

这是当前实现的明确业务规则。失败区间不会由 Polling 自动回读；日期关闭后的统计对账用于发现差异，运维再通过人工 T+1 覆盖修复接口补齐。

### 6.4 循环日志与低频汇总

每轮同步仍生成一条 `DEBUG` 日志：

```text
ES-Polling cycle completed. table=..., date=..., size=...,
previousLastId=..., nextLastId=..., mysqlCostMs=..., esCostMs=...,
totalCostMs=..., result=...
```

`result` 可能为：

- `SUCCESS`
- `BULK_FAILED_CONTINUED`
- `EMPTY`

正常 MySQL 读取和正常 Bulk 不再分别打印重复的完成日志。开发和测试环境开启 Polling
`DEBUG`，可以查看逐轮详情；生产环境保持 `INFO`，不输出这些高频日志。

每个 Worker 同时持有一个 `PollingLogSummary`，按固定五分钟窗口累计循环数、空查询数、
MySQL 行数、ES 成功/失败行数、游标范围、平均/最大耗时和成功吞吐，并输出一条 `INFO`
汇总日志：

```text
ES-Polling summary. reason=INTERVAL, table=..., date=...,
windowSeconds=..., cycles=..., successBatches=..., emptyCycles=...,
mysqlRows=..., esSuccessRows=..., esFailedRows=..., bulkFailedBatches=...,
startLastId=..., endLastId=..., mysqlAvgMs=..., mysqlMaxMs=...,
esAvgMs=..., esMaxMs=..., totalAvgMs=..., successRowsPerSecond=...
```

跨日期、日期关闭和 Worker 停止时会立即输出尚未结束的窗口。窗口中存在 ES 失败行时使用
`WARN`，否则使用 `INFO`。该汇总器只负责旁路日志统计，内部异常会丢弃并重置窗口，不参与
checkpoint、日期推进、Bulk 重试、Worker 状态、drain 或对账判断。

协调器和 Worker 启停、日期推进、checkpoint 初始化与恢复等低频生命周期事件保留
`INFO`。仍会继续执行的重试记录为 `WARN`，重试耗尽、Worker暂停和不可恢复失败记录为
`ERROR`。

## 7. Polling checkpoint

### 7.1 数据模型

每张表固定一条文档，文档 ID 为 `tableName`：

| 字段 | 含义 |
| --- | --- |
| `table_name` | 源表唯一标识 |
| `index_alias` | 业务 Alias |
| `status` | `RUNNING` 或 `PAUSED` |
| `sync_date` | 当前同步业务日期 |
| `last_id` | 最近一次持久化的恢复游标 |
| `last_error` | 最近一次持久错误 |
| `last_started_at` | 最近启动时间 |
| `last_stopped_at` | 最近停止时间 |
| `updated_at` | 最近更新时间 |

### 7.2 初始化

checkpoint 不存在时：

- `sync_date = bootstrap-start-date`
- `last_id = 0`
- `status = RUNNING`

当前实现不会查询 MySQL 最小 ID；首次查询使用 `id > 0`。

### 7.3 持久化时机

checkpoint 不在每次 Bulk 后更新。持久化发生在：

- 首次初始化。
- Worker 启动时更新启动时间。
- 日期从 D 原子推进到 D+1，并将 `last_id` 重置为 0。
- 系统性错误时保存当前内存进度并置为 `PAUSED`。
- 人工暂停和恢复。
- drain 或应用关闭时保存当前内存进度，业务状态保持 `RUNNING`。

因此 Worker 运行中，状态接口中的持久 `last_id` 可能落后于 Worker 内存 `lastId`。异常宕机后会从最近一次持久 checkpoint 重放，ES 文档 ID 固定，重复写入表现为覆盖。

## 8. 日期关闭和跨天闭环

设当前 Worker 正在读取日期 D。

日期 D 关闭必须同时满足：

1. 当前本地日期已经大于 D。
2. 查询开始时间不早于 `D+1 00:00 + date-close-delay`。
3. 该次查询仍然返回空批次。

配置 10 分钟关闭延迟时，D+1 的数据最早在 D+1 的 `00:10` 后才开始同步。D 在延迟期内仍持续轮询，用于接收旧日期晚到数据。

关闭顺序严格为：

```text
确认D日超过关闭延迟且关闭时间之后再次查询仍为空
→ 异步删除到期历史索引
→ 异步对账D日
   → 查询MySQL总量、最小ID和最大ID
   → MySQL总量为0时直接通知，不刷新或查询ES
   → MySQL总量大于0时主动刷新D日物理索引
   → 查询ES总量、最小ID和最大ID
   → 发送对账通知
→ 幂等创建D+1物理索引并绑定Alias
   → 固定最多尝试3次
   → 两次重试前各等待5秒
→ 原子校验checkpoint仍为RUNNING且仍处于D
→ 将checkpoint推进为D+1、last_id=0
→ Worker内存日期和游标切换为D+1、0
→ 主循环开始读取D+1
```

历史删除和对账在确认 D 已完成后立即异步提交，不等待 D+1 索引创建或 checkpoint 推进。二者允许重复提交，提交失败或执行失败也不能回退日期、暂停当前表或阻断 D+1。

D+1 索引创建重试耗尽时，不推进 checkpoint。Worker 将 `D + 当前lastId + PAUSED` 持久化并发送停止通知；人工恢复后仍从 D 的该游标重新确认空批次和日期关闭条件。

历史 bootstrap 日期早于当前日期时，Worker 会逐日读取；某日为空且关闭时间早已过去时，立即进入下一日。

## 9. 对账与人工修复

### 9.1 统计对账

对账是独立服务，不属于 Polling 状态机。T+1 成功和 Polling 日期关闭后都会异步调用；单表 `reconcile=false` 时调用会被跳过。

对账先查询 MySQL：

- MySQL 总量、最小 ID、最大 ID。

- MySQL 总量为 0 时，直接发送 `MYSQL_EMPTY` 通知，不刷新或查询 ES。
- MySQL 总量大于 0 时，先主动刷新该日物理索引，再查询 ES 总量、最小 ID和最大 ID。
- 匹配、不匹配、MySQL无数据和执行失败都会调用 Lark 通知。

对账不做逐文档内容比对，不创建持久任务，不自动重试，也不自动修复。日期关闭重试可能重复提交同一日对账，因此允许重复执行和重复通知。

人工接口也可以提交指定表、指定日期的统计对账。

### 9.2 Polling 历史修复

运维确认某个已关闭日期存在差异后，可调用 Polling 历史修复接口：

- 目标表必须是当前启用的 Polling 表。
- 修复日期必须早于 checkpoint 的 `sync_date`。
- 对应物理索引必须已经存在。
- 任务写入 `sano_import_task`。
- 导入复用 T+1 管线，从 ID 0 全量读取并 upsert 到已有物理索引。
- 修复完成后再次异步提交统计对账。

同表同日任务使用相同文档 ID。`PENDING`、`RUNNING`、`TIMEOUT_PARTIAL` 状态不能重复提交；已结束任务可以原子重置为 `PENDING` 后再次执行。

全量 upsert 只能补写或覆盖文档，不会删除 ES 中源端已经不存在的多余文档。如果修复后仍不一致，需要运维人工重建该日期物理索引。

## 10. 历史索引保留

T+1 和 Polling 都不扫描 Alias 下所有索引。

每次只计算一个待删除索引：

```text
expiredIndex = indexAlias + "_" + (completedDate - reserveDays).format(yyyyMMdd)
```

- T+1 成功或无数据完成时直接最佳努力删除。
- Polling 确认日期完成后、创建下一日索引前，通过 `esReconcileExecutor` 异步最佳努力删除。
- 索引不存在直接跳过。
- 删除失败只记录日志，不改变同步成功结果。

## 11. 错误、暂停和恢复

### 11.1 Polling 继续执行的错误

Polling Bulk 重试耗尽属于可对账、可人工修复的数据差异：

- 记录 ERROR。
- 发送异步通知。
- 推进内存游标。
- 继续下一批。

### 11.2 Polling 暂停的错误

以下主循环系统性异常会停止当前表：

- MySQL 查询异常。
- 当前或下一日物理索引准备失败且重试耗尽。
- 日期状态异常。
- checkpoint 原子推进异常。
- 其他未被 Bulk 失败规则吸收的运行时异常。

Worker 会循环尝试把当前日期、内存 `lastId` 和错误写入 checkpoint，成功后置为 `PAUSED` 并发送停止通知。checkpoint 暂时不可写时不会直接退出，而是按 `poll-interval` 等待后重试持久化。

人工恢复接口只把 `PAUSED` 改回 `RUNNING` 并清除错误。协调器随后重新启动该表 Worker。

### 11.3 状态分层

状态接口明确区分：

- 持久业务状态：checkpoint 中的日期、ID、`RUNNING/PAUSED` 和错误。
- 协调器状态：当前 JVM 的 `NOT_STARTED/STARTING/RUNNING/STOPPING/STOPPED/DISABLED/INITIALIZATION_FAILED`。
- Worker 内存状态：当前日期、内存 ID、执行阶段、停止标记和活动时间。
- drain 结果：本轮升级排空的 operation ID、结果和资源快照。

## 12. 共享资源

### 12.1 ES Bulk 并发

`GlobalEsWritePermitManager` 同时约束 T+1 和 Polling：

- 所有真实 Bulk 请求受全局上限控制。
- Polling 没有等待者时，T+1 可以使用配置允许的全部额度。
- Polling 开始等待后，新的 T+1 请求只能使用非保留额度。
- 已经发送的 Bulk 不会被抢占。
- 响应检查和重试等待不占用许可。

### 12.2 MySQL 连接

Polling 每个活动表同一时刻最多执行一条同步查询。T+1 Reader 和异步对账也使用同一个 `JdbcTemplate` 数据源。

当前三个环境 Hikari 配置均为：

```yaml
maximum-pool-size: 5
minimum-idle: 1
connection-timeout: 30000
idle-timeout: 300000
max-lifetime: 1200000
keepalive-time: 300000
```

`max-active-tables` 和同时可能运行的 T+1、对账数量必须结合连接池容量配置。Polling 查询在方法返回后由 Spring JDBC 正常释放连接，不长期持有连接或 ResultSet。

### 12.3 异步执行器

当前有两个 Spring 执行器：

- `esImportExecutor`：T+1 人工任务和任务分发，核心 5、最大 20、队列 100，关闭时等待任务完成。
- `esReconcileExecutor`：统计对账、Polling 历史删除、失败批次通知，核心 1、最大 5、队列 20，关闭时不等待队列执行完成。

对账、历史删除和失败通知都是允许重复、也允许在关机时丢失的最佳努力副作用，不参与 checkpoint 推进条件。

## 13. drain 和安全停止

### 13.1 drain 目标

统一 drain 用于部署前停止旧 `all` 实例接受新的同步工作，并等待已经开始的工作到达可恢复边界。

### 13.2 Polling drain

1. 协调器停止启动新 Worker。
2. 向现有 Worker 设置 `stopRequested`。
3. Worker 正在查询时等待 SQL 返回。
4. Worker 已经读到数据时，仍执行完整 Bulk 及其重试。
5. Worker 保存最新内存日期和 `lastId`。
6. checkpoint 业务状态保持 `RUNNING`，供新实例继续。

### 13.3 T+1 drain

1. 不再创建和启动新任务。
2. 当前 Reader 停止读取新批次。
3. 已经排队和在途的 Bulk 继续完成。
4. 将安全断点保存到任务。
5. 当前任务进入 `TIMEOUT_PARTIAL` 或明确失败终态。

drain 结果可以为 `DRAINED`、`DRAINED_WITH_ERRORS`、`FAILED` 或 `IN_PROGRESS`。部署脚本只在确认安全排空后停止旧主实例。

取消 drain 时：

- Polling Worker 先完成当前退出，再由协调器重启。
- 本次 drain 产生的 T+1 `TIMEOUT_PARTIAL` 任务会被精确重新提交。

## 14. 就绪与状态接口

### 14.1 `/health`

公开接口，固定返回 `OK`，只表示 Spring Web 已启动。

### 14.2 `/ready`

需要 `token`，检查：

- ES 客户端可访问。
- 至少一个启用表的业务 Alias 可以执行 `size=0` 查询。
- `all` 模式且 T+1 启用时，`sano_import_task` 存在。
- `all` 模式且 Polling 启用时，checkpoint 索引存在。
- Polling 协调器处于 `RUNNING`。
- 每张 `RUNNING` 表有 Worker，或因达到 `max-active-tables` 正常等待槽位。

单表 `PAUSED` 会写入就绪详情，但不会让整个查询服务变成未就绪。

### 14.3 管理接口

除 `/health` 外，接口均需要请求头或 URL 参数 `token`。

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| GET | `/ready` | 严格就绪检查 |
| GET | `/import/createImportTaskIndex` | 人工创建 `sano_import_task` |
| GET | `/import/createSyncInternalIndices` | 人工创建 Polling checkpoint 索引 |
| GET | `/import/importAppointDay?date=yyyyMMdd` | 全部 T+1 表指定单日导入 |
| GET | `/import/importDateRange?startDate=yyyyMMdd&endDate=yyyyMMdd` | 全部 T+1 表日期范围导入 |
| GET | `/import/importTableDateRange?tableName=...&startDate=yyyyMMdd&endDate=yyyyMMdd` | 指定 T+1 表日期范围导入 |
| GET | `/import/reconcile?tableName=...&date=yyyyMMdd` | 人工异步统计对账 |
| GET | `/import/repairPollingDate?tableName=...&date=yyyyMMdd` | 提交 Polling 历史日期全量覆盖修复 |
| GET | `/import/pollingRepairTask?tableName=...&date=yyyyMMdd` | 查询 Polling 修复任务 |
| POST | `/internal/sync/drain` | 启动统一 drain |
| GET | `/internal/sync/drain/status` | 查询 drain 状态 |
| POST | `/internal/sync/drain/cancel?operationId=...` | 取消指定 drain |
| GET | `/internal/sync/status` | 查询持久状态、协调器、Worker 和 drain |
| POST | `/internal/sync/polling/{tableName}/pause` | 人工暂停 Polling 表 |
| POST | `/internal/sync/polling/{tableName}/resume` | 人工恢复 Polling 表 |

## 15. 日志与通知

日志按容器/JVM当前日期保存到 `${LOG_DIR}/yyyyMMdd`。Appender 不设置固定活动文件，日期变化后由
`fileNamePattern` 自动切换目录；活动文件包含从 `0` 开始的分片序号，滚动完成的分片压缩为
`.log.gz`。当前文件类型包括：

- `${APP_NAME}.<序号>.log`
- `${APP_NAME}-error.<序号>.log`
- `${APP_NAME}-import.<序号>.log`
- `${APP_NAME}-import-error.<序号>.log`
- `${APP_NAME}-polling.<序号>.log`
- `${APP_NAME}-polling-error.<序号>.log`
- `${APP_NAME}-search-api.<序号>.log`

`modules.tplusone`、`modules.polling` 和 `modules.search` 的日志分别写入对应专项文件，并继续传递到应用总日志。T+1 显式失败 ID 同时进入导入错误文件；Polling ERROR 同时进入 Polling 错误文件和应用错误日志。

通知配置位于 `sano.notify`：

```yaml
sano:
  notify:
    enabled: true
    lark:
      enabled: true
      webhook-url: ...
      secret: ...
```

通知组件只提供总开关和 Lark 通道能力。是否发送由实际业务调用决定。

## 16. 当前运行约束

- 当前只允许一个常驻 `all` 实例；升级期间临时实例必须是 `query`。
- Polling 源表的 `id-column` 必须是可转换为 `long` 的递增数值。
- MySQL 应为日期条件和 ID 游标查询提供合适索引。
- `where-sql` 来自受信任部署配置，代码会直接拼接为业务过滤条件。
- `bootstrap-start-date` 只在 checkpoint 第一次创建时生效；已有 checkpoint 不会被配置覆盖。
- 修改表的 `index-alias` 时，已有 checkpoint 的 Alias 必须与新配置一致，否则启动失败。
- Polling Bulk 重试耗尽会继续推进，这是当前业务策略，不是自动重试队列。
- 对账和历史删除不参与下一日推进，执行失败不会造成同步真空或卡死。
- 所有业务调用统一使用 Alias；日期物理索引仅供同步、对账、修复和运维检查。
