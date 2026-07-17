# 实时写入 ES 设计文档

## 1. 背景与目标

当前 `es-server` 通过 MySQL 按天读取、Bulk 导入 Elasticsearch，适合 T+1 和历史补数。后续业务需要改为：业务系统产生记录后发送 MQ 消息，`es-server` 消费消息并实时写入 Elasticsearch，同时继续对内提供查询 API。

本设计目标：

1. 业务数据从 MQ 到 ES 的写入具备可恢复、可重试、可追踪能力。
2. 部署两个 `es-server` 实例，任一实例滚动更新或故障时，查询服务持续可用，MQ 消息不丢失。
3. 消息重复、应用重启、网络抖动不会产生重复 ES 文档或覆盖较新的数据。
4. 单条脏数据不阻断整个消费分区；失败消息可人工排查和补偿。
5. 保留现有 MySQL 导入能力，仅作为历史初始化、指定日期补数和灾后重建工具。

本期不直接接入某一种 MQ。Kafka、RocketMQ、RabbitMQ 的客户端与提交语义不同；最终实现前，应以公司现有基础设施优先确定一种 MQ，不同时接入多套。

## 2. 总体架构

第一阶段采用两个完全相同的应用实例：每个实例都具备 MQ 消费和查询 API 能力。

```text
业务系统
    |
    | 事务完成后发布业务变更消息
    v
MQ Topic / Queue
    |
    | 同一 Consumer Group，按消息 Key 分区
    +-------------------------------+
    |                               |
    v                               v
es-server-1                     es-server-2
consumer + query                consumer + query
    |                               |
    +------------ Bulk -------------+
                    |
                    v
          Elasticsearch 每日物理索引
          sano_xxx_yyyyMMdd

内网业务 / 外部调用
          |
          v
       Nginx / 内网入口
          |
          +------ es-server-1
          +------ es-server-2
```

两个实例使用同一个 MQ Consumer Group：

- 同一分区在任意时刻只会被其中一个实例消费，避免两台机器同时处理同一条消息。
- 实例数量大于分区数时，多余实例会处于待命状态；这仍能提供故障接管和查询高可用，但不会提升消费吞吐。
- 要提升消费吞吐，应增加 Topic 分区数，并在 ES、MySQL、网络容量允许时再提高消费者并发。

## 3. 服务角色与部署策略

### 3.1 第一阶段：两个 `all` 实例

建议先使用同一镜像、同一配置结构部署两个实例：

| 实例 | MQ 消费 | 查询 API | 宿主机端口 |
| --- | --- | --- | --- |
| `sano-es-server-1` | 是 | 是 | `127.0.0.1:8002` |
| `sano-es-server-2` | 是 | 是 | `127.0.0.1:8003` |

实例内部端口均可保持 `8002`，通过 Docker 的宿主机端口映射区分。端口仅绑定 `127.0.0.1`，由 Nginx 对外和对内转发，业务系统不直接访问应用容器端口。

建议增加角色配置，但第一阶段两个实例都使用 `all`：

```yaml
sano:
  es:
    realtime:
      enabled: true
      role: all # all / consumer / query
      consumer-group: sano-es-realtime-v1
```

### 3.2 后续演进：读写角色拆分

当 MQ 消费、Bulk 写入或查询压力明显增大时，再拆为：

| 角色 | 实例数建议 | 职责 |
| --- | --- | --- |
| `consumer` | 2 个或更多 | 仅消费 MQ、写 ES、处理重试和 DLQ |
| `query` | 2 个或更多 | 仅提供查询 API，不加入 Consumer Group |

这时查询扩容不会改变消费者再均衡，消费者升级也不会影响查询容量。当前不建议一开始就部署四个容器，两个 `all` 实例更适合现有规模和运维复杂度。

## 4. MQ 消息契约

消息不能只发送一段无上下文的数据库 JSON。消费者需要知道写入哪个 ES 文档、哪个每日索引、是新增更新还是删除，以及消息的新旧顺序。

推荐统一事件结构：

```json
{
  "event_id": "7f9adfd0-24ef-4d32-a049-04a98d5af001",
  "event_type": "WALLET_COIN_RECORD_UPSERT",
  "operation": "UPSERT",
  "table_name": "sano_wallet_coin_record",
  "record_id": "463703037978877952",
  "business_date": "2026-07-15",
  "version": 1784104385000,
  "occurred_at": "2026-07-15T10:33:05+08:00",
  "data": {
    "id": 463703037978877952,
    "user_id": 52748786,
    "business_type": 11,
    "create_time": "2026-07-15 10:33:05"
  }
}
```

字段要求：

| 字段 | 要求 | 用途 |
| --- | --- | --- |
| `event_id` | 全局唯一 | 日志、DLQ、链路排查 |
| `event_type` | 枚举 | 路由到对应 ES 表配置与 Mapping |
| `operation` | `UPSERT` 或 `DELETE` | 生成 ES index/delete 操作 |
| `table_name` | 白名单内表名 | 选择索引别名、Mapping 和保留策略 |
| `record_id` | 非空、字符串传输 | 作为 ES `_id`，保证重复消息幂等 |
| `business_date` | `yyyy-MM-dd` | 决定物理索引 `alias_yyyyMMdd` |
| `version` | 同一记录单调递增 | 防止乱序旧消息覆盖新消息 |
| `data` | 与目标 Mapping 对齐 | ES `_source` 内容 |

MQ 的消息 Key 应使用 `table_name + ':' + record_id`。这样同一业务记录会路由到同一分区，保障同一记录的消费顺序；不追求所有记录的全局顺序。

### 4.1 业务系统发送可靠性

推荐业务系统采用 **Transactional Outbox**：业务数据与 Outbox 事件在同一个数据库事务中提交，再由可靠的发布器将 Outbox 投递到 MQ。

原因：若业务先更新数据库再直接发送 MQ，应用在两步之间崩溃会出现“数据库已变更但没有消息”；反过来先发消息则可能出现 ES 已更新但数据库事务回滚。Outbox 可以将该风险收敛为可重试的发布过程。

业务侧至少应保证：

1. 每条变更事件有唯一 `event_id`。
2. 同一 `record_id` 的 `version` 单调递增。
3. 事件内提供稳定的 `business_date`；不要由消费者以“当前日期”推算索引。
4. 删除事件必须提供 `table_name`、`record_id`、`business_date`。若业务日期可能被修改，还需提供旧日期，确保能删除旧物理索引中的文档。

## 5. 消费、Bulk 与确认边界

### 5.1 写入流程

```text
拉取 MQ 消息
    -> 校验事件基础字段和表白名单
    -> 按 alias_yyyyMMdd 路由
    -> 确保当天物理索引已存在
    -> 组装 ES Bulk 请求
    -> ES 返回每一条 BulkItem 结果
    -> 成功或已转入 DLQ 的消息才允许提交 MQ offset / ack
```

实时写入复用现有 Bulk 的核心原则：批量条数、估算体积上限、并发数、429/网络异常重试、局部耗时日志。但 MQ 场景必须将“Bulk 成功”与“MQ offset 提交”绑定，不能在发送 Bulk 前提前确认消息。

建议初始参数沿用当前生产验证过的批量配置，并通过指标调优：

```yaml
sano:
  es:
    realtime:
      bulk-size: 5000
      max-bulk-bytes: 16mb
      flush-interval-ms: 1000
      max-retry-count: 3
      retry-backoff-ms: 1000
```

其中 `flush-interval-ms` 的作用是低流量时不必等到攒满 5000 条才可见。实际类名和最终配置键在编码阶段确定；此处仅是设计草案。

### 5.2 幂等与乱序

MQ 通常按“至少一次”语义交付：消费者崩溃、提交 offset 失败、再均衡都可能使同一消息再次投递。因此必须假设消息会重复。

处理方式：

1. 所有 `UPSERT` 使用原始业务 `record_id` 作为 ES `_id`，重复写入只覆盖同一文档，不会新增第二条。
2. `version` 使用业务侧的单调递增版本值，写 ES 时使用外部版本控制，避免延迟到达的旧事件覆盖新事件。
3. 同一记录用固定 MQ Key 保持分区内顺序；外部版本控制仍是最后保护，因为重试、补偿和跨系统投递可能导致乱序。
4. `DELETE` 同样使用同一 `_id` 和正确的每日物理索引。重复删除返回“未找到”时可按幂等成功处理。

ES 的 Bulk API 会逐条返回成功或失败，不能只根据 HTTP 200 判断整批成功。Bulk 的 `errors=true` 时必须逐项处理。[Elasticsearch Bulk API](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/docs-bulk.html)

### 5.3 失败分类

| 失败类型 | 示例 | 处理方式 | 是否提交原 MQ 消息 |
| --- | --- | --- | --- |
| 可重试 | ES `429`、连接超时、`502/503`、节点短暂不可用 | 指数退避后重试；达到阈值后暂停该分区或转重试 Topic | 否 |
| 不可重试 | Mapping 严格模式拒绝字段、必填 `record_id` 缺失、非法日期 | 记录完整错误并发送 DLQ | 是，DLQ 成功后才提交 |
| 索引创建竞争 | 两实例同时创建当天索引 | `resource_already_exists` 视为成功 | 是 |
| 业务未知类型 | 未配置的 `event_type` / `table_name` | 发送 DLQ，告警 | 是，DLQ 成功后才提交 |

DLQ 消息至少包含原消息全文、`event_id`、Topic/Partition/Offset、失败时间、异常分类和 ES 错误原因。现有失败 ID 日志继续保留，但 DLQ 才是可补偿的可靠载体。

### 5.4 Backpressure

消费者不能无限制从 MQ 拉消息后堆在 JVM 内存。实现时应使用有界队列：

1. Bulk 队列接近上限时暂停或降低 MQ 拉取。
2. ES Bulk 出现连续慢写、429 或重试时暂停对应分区。
3. 队列恢复到低水位后再继续消费。
4. 记录 MQ lag、队列深度、Bulk 耗时和重试次数。

这会将压力回推到 MQ，而不是挤爆 `es-server` 堆内存或 ES 写线程。

## 6. 每日索引、Alias 与 Mapping

### 6.1 索引路由

实时事件使用 `business_date` 确定物理索引：

```text
sano_wallet_coin_record + 2026-07-15
    -> sano_wallet_coin_record_20260715
```

不能使用消费时间或服务器当前时间决定索引，否则跨天积压、历史补偿消息会被写错日期。

### 6.2 当日索引创建

首次收到某日数据时，消费端需要确保物理索引存在，并按现有 Mapping 创建：

1. 先检查索引是否存在。
2. 不存在时根据 `esmapping` 文件创建 Settings 和 Mapping。
3. 创建成功后绑定查询 Alias。
4. 两个实例并发创建时，只有一个成功；另一个收到“索引已存在”后继续写入，不作为任务失败。

不能对每条消息都执行索引检查。实现时应以本地日期缓存减少请求，并在缓存失效或 ES 返回索引不存在时再检查。

### 6.3 Mapping 演进

当前 Mapping 使用严格模式时，业务先发送新字段会被 ES 拒绝。因此字段升级顺序必须固定：

```text
先发布 ES Mapping / 索引模板
    -> 再发布 es-server 消费代码
    -> 最后发布业务生产者发送新字段
```

删除字段不要立即从 Mapping 移除；旧消息、历史索引和重放消息仍可能携带该字段。建议先停止生产、等待保留期后再评估清理。

### 6.4 查询可见性

Elasticsearch 是近实时系统：Bulk 写入成功后，文档通常在下一次 refresh 后才会被搜索到。不要在每个 Bulk 请求上使用 `refresh=true` 或 `refresh=wait_for`，这会显著降低写入吞吐。

默认保持 `refresh_interval=1s`，查询侧接受约秒级可见延迟；确有强一致查询需求时，单独设计读取回源或局部等待机制。[Elasticsearch near real-time search](https://www.elastic.co/guide/en/elasticsearch/reference/8.19/near-real-time.html)

## 7. 双实例、Nginx 与滚动更新

### 7.1 Nginx 职责

Nginx 将查询请求负载均衡到两个实例：

```text
内网：http://172.31.38.87:8080
外部：https://es-server.fofunlive.net
                    |
                    v
       upstream es_server_backend
       127.0.0.1:8002 + 127.0.0.1:8003
```

查询路径（如 `/walletCoin/**`、`/walletDiamond/**`）可以配置 `error`、`timeout`、`502`、`503`、`504` 的故障切换。导入、管理和手工补数接口不应由 Nginx 自动重试，以免客户端连接中断后触发重复执行。

API Token 校验仍在 `es-server` 内部执行，Nginx 无需保存 Token。

### 7.2 健康检查

建议区分：

| 接口 | 用途 | 判断内容 |
| --- | --- | --- |
| `/health` | Liveness | 应用 JVM 正常存活，Docker 可直接调用，无 Token |
| `/ready` | Readiness | Spring 已启动、ES 客户端可用、关键配置已加载 |

Nginx 只把 `/ready` 成功的实例加入查询流量。MQ 暂时积压不应让查询实例直接“不健康”，但需要通过独立指标和告警处理。

### 7.3 无中断发布步骤

以升级 `es-server-2` 为例：

1. `es-server-1` 保持运行，继续提供查询和消费。
2. 拉取新镜像并启动新版 `es-server-2`。
3. 等待新版 `/health`、`/ready` 通过，确认 Nginx 可转发查询。
4. 旧版 `es-server-2` 先停止继续拉取 MQ 消息，再等待已拉取消息写完 ES、Bulk 成功、offset/ack 提交完成，然后退出。
5. 检查 MQ consumer group 正常、ES Bulk 无持续错误、查询 API 正常。
6. 按同样方式升级 `es-server-1`。

MQ 在应用短暂切换时保留未确认消息；另一实例会接管分区或新版实例恢复后继续消费。真正的“无丢消息”依赖第 5 节的“Bulk 成功后才 ack”和幂等 `_id` 策略。

部署脚本不能再无条件删除镜像：两个实例可能仍依赖旧标签。应只更新目标实例，确认另一个实例稳定后再清理不被任何容器引用的旧镜像。

## 8. 优雅停机与再均衡

收到 Docker `SIGTERM` 时，消费者应按以下顺序退出：

```text
停止拉取新 MQ 消息
    -> 暂停已分配分区
    -> 等待内存队列和进行中的 Bulk 完成
    -> 提交已完成消息的 offset / ack
    -> 关闭 MQ Consumer、ES Client、线程池
    -> 进程退出
```

Docker 的 `stop_grace_period` 应大于“单个 Bulk 最大重试时间 + 队列排空时间”。建议初始设置为 `120s`，后续按实际 Bulk 耗时和队列长度调整。

如果超过优雅停机时间，Docker 强制结束进程，未提交的消息会被 MQ 再次投递；由于 ES `_id` 幂等，允许少量重复处理，但不应造成重复文档。

## 9. 从现有 T+1 导入切换到实时写入

实时写入上线前，不能简单停止导入、直接打开消费者，否则历史数据和切换窗口内数据可能缺失。

推荐切换步骤：

1. 业务侧先完成 Outbox / MQ 事件生产，确保每条事件带稳定 `record_id`、`business_date`、`version`。
2. 为每张表确认 Mapping、每日索引规则和实时事件类型。
3. 启动实时消费者，但保持查询流量不变；消费者从约定的初始 offset 开始消费。
4. 使用现有 MySQL 导入能力回填历史数据，回填写入同一物理索引时也携带对应的业务版本。
5. 依赖 ES 外部版本控制，保证实时较新事件不会被历史回填的旧版本覆盖。
6. 对比 MySQL 与 ES 的总数、抽样 ID、关键聚合结果。
7. 确认稳定后关闭该表的每日 T+1 定时导入；保留手工补数和灾后重建能力。

切换期间不能让“没有版本控制的回填”覆盖已经被实时消费写入的新数据。若业务无法提供可靠单调版本，必须先设计明确的 MySQL 快照水位与 MQ offset 切换点，再上线实时链路。

生产实时模式下，原有每日任务调度应关闭，避免同一日期数据同时被“创建新索引的全量导入”和“MQ 增量消费”写入：

```yaml
sano:
  es:
    import:
      task-enabled: false
```

配置名以项目最终实际属性为准。历史补数应使用单独的运维流程，并确保与实时消费者的版本控制兼容。

## 10. 可观测性、告警与运维

至少记录和监控以下指标：

| 分类 | 指标 / 日志 |
| --- | --- |
| MQ | Topic lag、每分区积压、消费速率、再均衡次数、未提交消息数 |
| Bulk | 批次条数、请求大小、成功/失败数、耗时、429 数、重试次数 |
| ES | 节点健康、写入线程池拒绝、索引 refresh/merge、GC、磁盘水位 |
| DLQ | 失败消息数、最早积压时间、失败类型、最近错误样例 |
| 查询 | API 请求量、耗时、ES `took`、超时数、命中索引数 |
| 应用 | JVM 堆、GC、队列深度、线程池活跃数、实例 readiness |

通知策略：

1. 单条不可重试消息进入 DLQ：记录错误日志；按时间窗口汇总通知，避免消息风暴。
2. 某分区连续重试、MQ lag 持续增长：立即通知。
3. ES Bulk 连续出现 429 或慢写：告警并自动降低消费速度。
4. 两个实例均不 ready：最高优先级通知。
5. 每日发送消费汇总：消息数、成功数、DLQ 数、最大 lag、最大 Bulk 耗时。

现有 Lark / 钉钉通知抽象可复用，但通知异常必须被完整隔离，不能影响 MQ ack、Bulk 重试或查询 API。

## 11. 推荐代码结构

在现有 `com.tsd.sano.es.importer` 之外新增独立的 `realtime` 领域，避免将“按天全量导入”和“持续消费 MQ”混在同一个任务类中：

```text
com.tsd.sano.es.realtime
├── config
│   └── EsRealtimeProperties.java
├── model
│   ├── EsChangeEvent.java
│   ├── EsChangeOperation.java
│   └── RealtimeWriteResult.java
├── consumer
│   └── EsChangeConsumer.java
├── service
│   ├── EsRealtimeWriteService.java
│   ├── EsRealtimeIndexManager.java
│   └── EsRealtimeBulkWriter.java
└── dlq
    └── EsChangeDlqPublisher.java
```

设计约束：

1. MQ 客户端只负责拉取、暂停、恢复和确认消息。
2. `EsRealtimeWriteService` 负责事件到索引、`_id`、版本和 Bulk 操作的转换。
3. 索引创建逻辑可以复用现有 `EsIndexManager` 的 Mapping/Settings 能力，但不能复用“全量导入完成后切 Alias”的任务流程。
4. DLQ 发布、通知与主消费链路解耦；但 DLQ 未成功写入前不能确认原始不可重试消息。
5. 不把 MQ offset、消费者对象或线程上下文放进 Controller，查询 API 与消费 API 完全分离。

## 12. 实施顺序

建议按以下迭代顺序实施，避免一次性改动过大：

1. 确定 MQ 类型、Topic、分区数、保留时长、DLQ Topic 和业务事件契约。
2. 实现事件模型、单实例消费者、按 `_id` Bulk 写入、可重试分类和手工测试 Topic。
3. 实现每日索引按需创建、严格 Mapping 校验、外部版本控制。
4. 接入 DLQ、消费/Bulk/MQ lag 日志和 Lark/钉钉告警。
5. 先在测试 ES 使用少量真实数据演练：重复、乱序、ES 暂停、应用强制重启、Mapping 错误、DLQ 重放。
6. 部署两个 `all` 实例和 Nginx upstream，演练单实例滚动更新、Consumer Group 再均衡和查询无中断。
7. 选择一张低风险表进行历史回填与实时切换，对账通过后逐表推广。
8. 数据量和查询压力明显增长后，再拆分 `consumer` 与 `query` 角色并独立扩容。

## 13. 验收标准

上线一张表前至少满足：

1. 重复投递同一消息多次，ES 文档数不增加，内容正确。
2. 先投递高版本、后投递低版本，ES 保留高版本内容。
3. ES 停止或网络异常后，消费者不提交未成功消息；ES 恢复后可以自动追平。
4. Mapping 错误消息进入 DLQ，后续正常消息持续消费。
5. 两个实例分别滚动重启时，查询 API 不出现整体不可用，MQ lag 可恢复。
6. 历史回填和实时事件并行时，对账结果正确，不发生旧数据覆盖新数据。
7. 日常监控可以识别 lag、DLQ、Bulk 重试、ES 慢写与实例不可用。

完成以上验收后，MQ 实时写入才能替代该表的日常 T+1 全量同步。
