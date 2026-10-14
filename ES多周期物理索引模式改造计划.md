# ES多周期物理索引模式改造计划

> 文档状态：未来改造方案，尚未开始编码。  
> 当前基线：`es-server` 已完整移除基于 MySQL 查询的 Polling，只保留已上线的 T+1；本计划实施时不得破坏 T+1 和优雅升级能力。

## 1. 改造背景

当前所有业务表统一使用按日物理索引：

```text
indexAlias_yyyyMMdd
```

这种规则适合金币、钻石等每日数据量很大的流水表，但不适合所有业务：

- `sano_wallet_coin_record`、`sano_wallet_diamond_record` 数据量大，继续按日建索引合理；
- `sano_room` 当前约一百多万条、每日新增约三万至四万条，按日建索引会产生大量很小的索引，按月更合适；
- 每日数据量更少但需要长期保留的记录，可以按年建索引；
- 用户等当前状态数据只有几十万，即使未来未达到千万级，也没有按日期拆分的必要，应长期写入一个物理索引。

本次改造的核心不是改变T+1仍然“每天同步一个业务日期”的任务模型，而是把以下两个概念分开：

```text
同步任务粒度：始终按业务日期执行
物理索引粒度：DAY / MONTH / YEAR / SINGLE
```

## 2. 当前源码约束

当前源码把业务日期和每日物理索引绑定在多个位置，不能只修改一个日期格式：

1. `TPlusOneImportTask` 创建任务时直接生成 `indexAlias_yyyyMMdd`；
2. `TPlusOneImportService` 在 `indexName` 为空时按日补全物理索引名；
3. `TPlusOneIndexService` 按 `reserveDays` 计算并删除一个每日历史索引；
4. `ReconcileStatisticsService` 按对账日期直接定位每日物理索引，并对整个索引统计；
5. `EsImportController` 的人工对账按日拼接物理索引名；
6. `EsSearchUtil.getIndices()` 根据查询时间范围生成每日物理索引列表；
7. `SanoImportTask` 的任务ID是 `tableName_yyyyMMdd`，但任务中同时持久化了每日 `indexName`。

其中第7项不需要改变。即使多天写入同一个月、年或固定索引，每张表每天仍然只能有一条T+1任务：

```text
taskId    = tableName_yyyyMMdd
indexName = 根据index-mode计算出的目标物理索引
```

`SINGLE` 首次全量使用一条固定任务记录保存初始化状态，不占用业务日期字段：

```text
taskId     = tableName_BOOTSTRAP_fixedIndexName
importDate = null
```

例如用户固定索引 `sano_user_v1` 对应：

```text
sano_user_BOOTSTRAP_sano_user_v1
```

`BOOTSTRAP` 只用于构成固定任务文档ID，不写入 `import_date`，也不是新的任务类型字段。任务执行规则仍然以当前表配置为准，只有 `index-mode=SINGLE` 的表允许创建和执行 `importDate=null` 的固定初始化任务。

## 3. 改造目标

### 3.1 必须实现

- 单表配置支持 `DAY`、`MONTH`、`YEAR`、`SINGLE` 四种物理索引模式；
- 现有表未配置时默认 `DAY`，升级后金币、钻石等已有同步行为完全不变；
- T+1任务继续按天创建、执行、暂停、恢复、补数和记录终态；
- 月、年、固定索引创建时立即绑定Alias，允许多个每日任务向同一个物理索引幂等写入；
- ES文档ID继续使用配置的 `id-column`，相同记录重跑时覆盖，不产生重复文档；
- 对账能够区分日期分桶索引和固定当前状态索引；
- 历史清理按照物理索引周期执行，不扫描Alias下全部索引；
- 查询统一保留业务Alias，外部调用不因物理索引模式变化而修改；
- 优雅停机、任务断点、通知和日志业务保持现有行为。

### 3.2 本次不做

- 不引入ILM、Data Stream、Redis、MQ或新的调度框架；
- 不重新引入基于MySQL查询的Polling；
- 不在本次多周期改造中实现MQ、Kafka、Outbox或Binlog CDC实时同步；
- 不为月、年索引实现“每日数据原子可见”的临时索引加Reindex方案；
- 不自动判断何时从月索引升级为年索引或拆分分片；
- 不自动完成 `SINGLE` 索引的Mapping版本迁移；
- 不改变业务查询使用的Alias名称。

## 4. 配置设计

### 4.1 新增索引模式

建议新增纯配置枚举：

```java
public enum IndexMode {
    DAY,
    MONTH,
    YEAR,
    SINGLE
}
```

在 `SyncTableConfig` 中增加：

```yaml
index-mode: DAY
```

未配置时默认 `DAY`，保证现有YML和线上行为兼容。

### 4.2 物理索引命名

| 模式 | 物理索引命名 | 示例 |
|---|---|---|
| `DAY` | `indexAlias_yyyyMMdd` | `sano_wallet_coin_record_20260826` |
| `MONTH` | `indexAlias_yyyyMM` | `sano_room_202608` |
| `YEAR` | `indexAlias_yyyy` | `sano_small_event_2026` |
| `SINGLE` | 使用显式固定物理索引名 | `sano_user_v1` |

`SINGLE` 建议增加必填配置：

```yaml
fixed-index-name: sano_user_v1
```

不直接使用 `index-alias` 作为物理索引名，因为ES不能同时存在同名索引和Alias。所有查询仍然访问 `sano_user` Alias。

### 4.3 历史保留配置

继续保留现有 `reserve-days`，因为它表达业务数据至少需要保存多少天，不应随着物理索引模式变化而改变含义。

```yaml
delete-history-index: true
reserve-days: 730
```

`reserve-days` 改为可空配置，不再提供默认30天：

```java
private Integer reserveDays;
```

统一规则：

| 配置 | 行为 |
|---|---|
| `delete-history-index=false` | 永久保存，不删除历史索引 |
| `delete-history-index=true`，未配置 `reserve-days` | 永久保存，不删除历史索引 |
| `delete-history-index=true`，`reserve-days > 0` | 删除整个周期都已超过保留天数的物理索引 |
| `reserve-days <= 0` | 配置错误，启动失败 |
| `SINGLE` | 必须关闭历史索引删除，固定当前状态索引永久保存 |

月、年索引只能整索引删除，因此实际保存时间会比 `reserve-days` 多不足一个月或一年。这是为了保证仍在保留范围内的文档不会随整个索引被提前删除。

### 4.4 配置示例

金币表保持每日索引：

```yaml
- enabled: true
  index-mode: DAY
  table-name: sano_wallet_coin_record
  index-alias: sano_wallet_coin_record
  mapping-file: sano_wallet_coin_record.json
  delete-history-index: true
  reserve-days: 60
  id-column: id
  dt-column: dt
  dt-column-type: DATE
  where-sql:
  reconcile: false
```

直播场次使用月索引：

```yaml
- enabled: true
  index-mode: MONTH
  table-name: sano_room
  index-alias: sano_room
  mapping-file: sano_room.json
  # 未配置保留天数，永久保存全部月索引。
  delete-history-index: false
  id-column: id
  dt-column: close_time
  dt-column-type: DATETIME
  where-sql:
  reconcile: true
```

少量历史记录使用年索引：

```yaml
- enabled: true
  index-mode: YEAR
  table-name: sano_small_event
  index-alias: sano_small_event
  mapping-file: sano_small_event.json
  delete-history-index: true
  reserve-days: 1825
  id-column: id
  dt-column: create_time
  dt-column-type: DATETIME
  where-sql:
  reconcile: true
```

用户当前状态使用固定索引：

```yaml
- enabled: true
  index-mode: SINGLE
  table-name: sano_user
  index-alias: sano_user
  fixed-index-name: sano_user_v1
  mapping-file: sano_user.json
  delete-history-index: false
  id-column: id
  dt-column: update_time
  dt-column-type: DATETIME
  where-sql:
  reconcile: true
```

上述用户表示例要求源表存在能够覆盖新增和修改的可靠 `update_time`。如果只有 `create_time`，只能同步新增用户，不能发现已有用户的资料变化。

## 5. 四种模式的业务语义

### 5.1 DAY

保持当前线上逻辑：

```text
创建D日任务
→ 创建 alias_yyyyMMdd
→ 查询MySQL的D日数据
→ Bulk写入
→ 完成后刷新并绑定Alias
→ 删除一个准确到期的日索引
→ 异步对账D日
```

新索引在任务成功前没有绑定Alias，因此当天半成品数据不会进入业务查询。

### 5.2 MONTH

```text
每天仍创建D日任务
→ 根据D计算 alias_yyyyMM
→ 月索引不存在时创建，并在创建请求中直接绑定Alias
→ 每天只查询MySQL的D日数据
→ 使用业务ID向当月索引Upsert
→ 完成后恢复索引设置并刷新
→ 按月计算一个准确到期索引并最佳努力删除
→ 在月索引中按D日条件异步对账
```

同一个月的不同每日任务拥有不同 `taskId`，但共享同一个 `indexName`。

### 5.3 YEAR

行为与 `MONTH` 相同，只是一个自然年内的每日任务共享 `alias_yyyy`。

适合每日数据较少、年累计规模仍可控的历史记录，不建议用于金币、钻石等大流水。

### 5.4 SINGLE

`SINGLE` 表示当前状态索引，不是无限增长历史流水的替代方案：

```text
创建fixed-index-name并立即绑定业务Alias
→ 首次全量Bootstrap直接写入已经可查询的固定索引
→ 后续每日按update_time查询新增或修改数据
→ 使用业务ID向同一个固定索引Upsert
→ 不执行周期历史索引清理
→ 对账按全表现状执行
```

适合用户、礼物、国家配置等只需要保存每个业务主键最新状态的数据。

## 6. SINGLE首次初始化与日常增量

### 6.1 不能直接从“昨天”开始

如果固定索引尚不存在，而定时T+1只查询昨天的数据，会创建一个缺少全部历史用户的半成品索引。因此必须增加明确的首次全量Bootstrap流程。

推荐调用链：

```text
人工提交指定SINGLE表全量初始化
→ 检查当前表没有活动T+1任务
→ 创建fixed-index-name并在创建请求中直接绑定Alias
→ MySQL按id从0开始全表分页
→ 复用现有Reader、队列、Bulk、内存限制和失败阈值
→ 全量成功后恢复索引设置并刷新
→ 后续才允许每日增量任务写入
```

首次全量任务仍然落入 `sano_import_task`，不增加 `task_type`，也不改变 `import_date` 的日期语义：

```text
task.taskId     = tableName + "_BOOTSTRAP_" + fixedIndexName
task.importDate = null
```

执行任务时先读取当前 `SyncTableConfig`：

- `index-mode=SINGLE`且任务ID等于当前配置计算出的固定Bootstrap任务ID、`importDate=null`：执行或续跑全表初始化；
- `index-mode=SINGLE`且`importDate=yyyyMMdd`：Bootstrap成功后按 `update_time` 执行每日增量；
- `DAY/MONTH/YEAR`：`importDate`必须是合法 `yyyyMMdd`；
- 非SINGLE表或任务ID不匹配时遇到 `importDate=null`，必须将该任务标记失败，不能误执行全表导入。

固定任务的 `status` 表示首次全量的生命周期：不存在表示尚未提交，`PENDING/RUNNING/TIMEOUT_PARTIAL/FAILED` 都表示尚未完成，只有 `SUCCESS` 才允许创建该表后续每日增量任务。Bootstrap失败或暂停后继续复用同一个固定索引和安全断点；Alias保持绑定，允许已成功写入的数据直接进入查询。任务索引Mapping无需新增字段，也不需要执行PUT Mapping升级。

### 6.2 后续每日增量

后续每日任务仍以日期作为任务ID，但按 `update_time` 查询当天新增和修改的行：

```text
taskId    = sano_user_20260826
indexName = sano_user_v1
查询条件   = update_time >= D 00:00:00 AND update_time < D+1 00:00:00
文档ID     = user.id
```

同一用户以后再次变化时覆盖原文档，不增加重复用户文档。

### 6.3 Mapping升级

本次不自动实现Mapping版本迁移，但固定索引必须保留Alias，以便以后人工执行：

```text
创建 sano_user_v2
→ 从MySQL重新全量构建
→ 校验成功
→ Alias从sano_user_v1原子切换到sano_user_v2
→ 配置fixed-index-name切换为sano_user_v2
→ 确认稳定后删除v1
```

部署切换前必须确保没有旧版本SINGLE任务处于 `PENDING/RUNNING/TIMEOUT_PARTIAL`。

## 7. 多日共享索引后的可见性变化

`MONTH`、`YEAR` 和 `SINGLE` 在创建物理索引时就直接绑定Alias。首次任务或后续每日任务如果执行到一半失败，已经成功写入的文档在refresh后可以被查询到。

当前可接受的恢复方式为：

```text
任务标记失败或TIMEOUT_PARTIAL
→ 记录安全lastSuccessId
→ 下次续跑或人工重跑
→ 使用相同文档ID覆盖补齐
```

本计划不增加“每日临时索引 → 成功后Reindex到月索引”的复杂流程。因此需要在编码前明确接受以下差异：

- `DAY`：新的一天仍保持同步成功后才绑定Alias；
- `MONTH/YEAR/SINGLE`：创建物理索引时立即绑定Alias，从首次写入开始就不保证整日或全量原子可见；
- 任务终态、通知、对账和人工补数负责发现并恢复不完整日期。

## 8. 对账改造

### 8.1 DAY

保持当前行为，物理索引只包含D日数据，可以直接统计整个索引：

```text
COUNT / MIN(id) / MAX(id)
```

### 8.2 MONTH和YEAR

目标物理索引包含多个业务日期。ES统计必须增加和MySQL一致的D日过滤：

```text
DATE字段：dt = D
DATETIME字段：dt >= D 00:00:00 AND dt < D+1 00:00:00
```

然后只对过滤结果计算：

```text
COUNT / MIN(id) / MAX(id)
```

### 8.3 SINGLE

当前状态数据不适合按历史 `update_time` 日期持续对账。一个用户后续再次更新后，其ES文档的 `update_time` 会变化，历史日期统计也会随之变化。

因此 `SINGLE` 对账应自动解释为全表现状对账：

```text
MySQL全表或固定业务条件：COUNT / MIN(id) / MAX(id)
ES固定索引：COUNT / MIN(id) / MAX(id)
```

对几十万用户执行低频全量统计可以接受。是否每天自动执行仍由该表 `reconcile` 配置决定；也可以关闭自动对账，只保留人工调用。

### 8.4 where-sql限制

当前 `where-sql` 被视为完整MySQL条件，配置后不会自动追加任务日期条件。对于多日共享索引，如果同一份固定条件每天重复执行，可能每天重复扫描同一批数据。

第一版建议保持简单并在配置加载时限制：

- `MONTH/YEAR` 要求 `where-sql` 为空，统一使用 `dt-column` 生成每日条件；
- `SINGLE` 每日增量也要求 `where-sql` 为空，统一按 `update_time` 查询；
- 需要固定业务过滤条件时，再单独设计 `extra-where-sql`，本次不同时扩展；
- `DAY` 继续兼容现有 `where-sql` 行为。

## 9. 历史索引清理

索引清理继续沿用当前“计算一个确定名称、最佳努力删除”的原则，不扫描Alias下的全部索引。

先计算当前任务需要保留的最早日期：

```text
retentionStartDate = completedDate - (reserveDays - 1)天
```

再按模式计算一个完整过期的确定索引：

```text
DAY   ：retentionStartDate的前一天对应的日索引
MONTH ：retentionStartDate所在月份的前一个月索引
YEAR  ：retentionStartDate所在年份的前一个年索引
SINGLE：不执行
```

例如完成日期为2026-08-27、`reserve-days=60`，最早保留日期为2026-06-29：

- `DAY` 可以删除2026-06-28日索引；
- `MONTH` 必须保留仍包含6月29日至6月30日的六月索引，只删除五月索引；
- `YEAR` 必须保留仍包含有效日期的2026年索引，只处理已经完整过期的上一年度索引。

对于月、年索引，同一周期内的每日任务可能重复检查同一个到期索引：

- 第一次删除成功后，后续检查直接发现索引不存在；
- 第一次删除失败时，下一日任务可以再次最佳努力删除；
- 每次只检查一个准确名称，不会查询Alias下的全部索引；
- 删除失败只记录日志，不改变已经完成的T+1任务终态。

## 10. 查询侧处理

业务查询的稳定入口始终是Alias，物理索引模式不得暴露给外部调用方。

当前金币、钻石查询为了减少参与搜索的索引数量，会通过 `EsSearchUtil.getIndices()` 构造每日物理索引名。改造原则为：

- 现有金币、钻石继续按 `DAY` 生成每日索引，不改变查询结果；
- 新增月、年、固定索引的业务查询，第一版直接查询Alias，优先保证正确性；
- 不允许按日格式推导月、年或固定物理索引，否则会产生索引不存在或漏查；
- 后续确认有性能需要时，再让查询工具根据 `IndexMode` 对月份或年份去重生成物理索引列表；
- `SINGLE` 永远查询Alias，不根据时间生成物理索引名。

该方案避免让查询模块强依赖同步表是否启用，也避免为了当前低数据量提前增加配置查找和路由复杂度。

## 11. 实时同步边界

当前源码只保留 T+1，不再存在 `sync-mode`、Polling 总开关、checkpoint、Worker、跨天推进或历史修复任务。因此多周期能力只需要服务 T+1：

```text
T+1：支持 DAY / MONTH / YEAR / SINGLE
```

未来如需实时同步，应单独设计 Outbox 或 Binlog CDC、MQ/Kafka、消费进度和幂等 ES 写入。实时链路是否复用本计划的 `index-mode`，必须结合消息分区、乱序、补数和索引切换重新评估，不能重新恢复数据库高频轮询。

## 12. 索引名称统一组件

建议在 `modules.index` 下增加一个职责明确的索引名称解析服务，例如：

```text
EsIndexNameResolver
```

该服务只负责根据表配置和业务日期得到物理索引名，不执行ES远程操作：

```text
resolve(tableConfig, businessDate)
resolveExpired(tableConfig, businessDate)
```

它不负责：

- 创建、删除、刷新索引；
- 绑定Alias；
- 判断任务是否成功；
- T+1调度；
- 对账和通知。

这不是过度提取。当前每日索引后缀已经分散在任务、导入、对账和查询等多个类中；引入多种模式后必须保证同一张表在所有入口得到完全相同的物理索引名称。

通用ES远程能力继续由现有 `EsIndexManager` 负责。

## 13. 预计源码改造清单

### 13.1 新增

| 文件 | 作用 |
|---|---|
| `modules/config/IndexMode.java` | `DAY/MONTH/YEAR/SINGLE`纯配置枚举 |
| `modules/index/EsIndexNameResolver.java` | 集中计算目标和到期物理索引名 |
| `esmapping/sano_room.json` | 直播场次Mapping，字段以最终确认清单为准 |
| `esmapping/sano_user.json` | 仅在本次同时接入用户表时新增 |

SINGLE Bootstrap复用现有T+1执行链路，不预设新的Reader或Bulk Writer。任务提交入口和重复提交规则需要确认第17节后再确定，避免提前增加职责重复的服务。

### 13.2 修改

| 文件/模块 | 主要改动 |
|---|---|
| `SyncTableConfig` | 增加 `indexMode`、`fixedIndexName`，将 `reserveDays` 改为可空并补充必要校验 |
| `TPlusOneImportConfig` | 携带任务执行和清理所需的索引模式配置 |
| `SanoImportTask` | 保持 `importDate` 只表示 `yyyyMMdd`；固定Bootstrap任务该字段为空 |
| `SanoImportTaskService` | 支持固定Bootstrap任务ID的新增、查询、续跑和受控重置，不修改任务索引Mapping |
| `TPlusOneImportTask` | 创建任务时通过解析服务生成物理索引名；执行前校验持久任务与当前配置 |
| `TPlusOneImportService` | 删除内部固定按日命名的兜底，复用月、年、固定索引 |
| `TPlusOneIndexService` | 按模式清理一个准确到期索引 |
| `ReconcileStatisticsService` | 按模式定位索引并选择日期对账或全表现状对账 |
| `EsImportController` | 人工对账使用统一索引解析规则，并增加受控的SINGLE首次全量任务入口 |
| `EsSearchUtil` | 现有每日方法保持兼容，并禁止新模式误用每日名称 |
| 四份环境YML和示例YML | 显式补充 `index-mode`，迁移历史保留配置和关键注释 |
| 完整设计文档 | 编码完成并验证后再更新，不在计划阶段提前宣称已实现 |

### 13.3 不修改或只做兼容验证

- `TPlusOneJdbcReader` 的正常每日查询与数据库连接管理；
- `TPlusOneBulkWriter` 的Bulk构建、重试和文档ID；
- `ImportContext`、内存限制、队列和有序安全断点；
- `SyncDrainCoordinator` 的排空判据；
- Notify通道实现；
- 外部查询接口路径和业务Alias。

## 14. 开发步骤

### P1：配置与索引名称规则

1. 新增 `IndexMode`；
2. 扩展 `SyncTableConfig` 并在启动阶段完整校验；
3. 实现统一索引名称解析；
4. 将现有环境表全部显式配置为 `DAY`；
5. 验证旧配置默认值仍为 `DAY`。

### P2：T+1日期分桶模式

1. 任务创建使用统一名称解析；
2. 导入服务复用月、年物理索引；
3. 保持任务ID、日期条件、断点和终态按天；
4. 历史清理保留 `reserve-days` 语义，并按物理索引周期计算完整过期索引；
5. 完成 `DAY/MONTH/YEAR` 的人工补数和续跑验证。

### P3：对账与查询正确性

1. `DAY`保持整索引统计；
2. `MONTH/YEAR`在ES请求中增加D日过滤；
3. `SINGLE`使用全表现状统计；
4. 人工对账入口使用统一索引名称；
5. 新模式查询只访问Alias，现有每日查询优化保持不变。

### P4：SINGLE首次全量和增量

1. 使用 `taskId=tableName_BOOTSTRAP_fixedIndexName`、`import_date=null` 的固定任务持久化首次全量状态；
2. 复用现有Reader、Bulk、内存和drain能力执行首次全量；
3. 创建固定索引时直接绑定Alias，验证Bootstrap期间数据可以逐步查询；
4. 开放后续按 `update_time` 的每日增量Upsert；
5. 验证重复执行、超时续跑、失败恢复和全表现状对账。

### P5：接入直播场次表

1. 以已经确认的 `close_time` 作为同步日期；
2. 创建 `sano_room.json` Mapping；
3. 开发环境以 `MONTH` 完成历史小范围导入；
4. 验证跨日直播在关播日期进入ES；
5. 验证同月多日任务写入同一索引；
6. 验证月索引内单日对账；
7. 验证跨月创建新索引、Alias聚合和到期删除。

### P6：回归和文档

1. 编译项目；
2. 开发环境验证全部T+1表；
3. 验证现有金币、钻石仍生成每日索引；
4. 验证drain、恢复和安全断点；
5. 验证日志仍进入T+1和统一日志文件；
6. 验证源码、YML、Compose、日志和部署文档没有重新引入Polling；
7. 更新完整设计文档和部署文档；
8. 不保留仅供临时测试使用的业务代码。

## 15. 验收清单

### 15.1 兼容性

- 未配置 `index-mode` 的现有表仍生成 `alias_yyyyMMdd`；
- 金币和钻石T+1任务、补数、对账和查询结果不变；
- 旧的 `sano_import_task` 每日任务ID仍可读取和续跑；
- query实例仍只提供查询，不启动同步任务。

### 15.2 MONTH/YEAR

- 同月或同年不同日期任务拥有不同taskId但写入相同indexName；
- 重复执行同一天不会增加重复文档；
- 月、年索引的对账只统计目标业务日期；
- 跨月、跨年时创建正确的新物理索引；
- 历史删除每次只处理一个确定名称的索引；
- 删除失败不影响任务成功终态。

### 15.3 SINGLE

- 固定索引不存在时，普通每日任务不能绕过首次全量Bootstrap直接上线；
- 创建固定索引时立即绑定Alias，Bootstrap过程中已成功写入的数据可以查询；
- 首次全量失败或暂停后保留Alias、物理索引和安全断点，并可继续执行；
- 首次全量成功后Alias可查询全部历史用户；
- 后续新增和修改用户都按相同ID覆盖到固定索引；
- 重复增量任务不产生重复用户；
- `delete-history-index=true` 的SINGLE配置在启动时被拒绝；
- 全表现状对账不受历史 `update_time` 变化影响。

### 15.4 稳定性

- JdbcTemplate查询结束后连接正常归还连接池；
- 共享月、年、固定索引任务保持串行，不并发修改同一个索引设置；
- drain到达后不启动新任务，已读取批次仍按现有规则收敛；
- T+1安全断点和任务终态没有混用；
- 失败日志和通知包含taskId、tableName、indexName、可空importDate和indexMode。

## 16. 已确认的业务决策

1. **范围**：第一版只扩展 T+1，使其支持 `DAY/MONTH/YEAR/SINGLE`；不重新引入数据库Polling；
2. **历史配置**：保留 `reserve-days`；未配置表示永久保存，月、年模式只删除整个周期都已过期的索引；
3. **SINGLE物理名称**：显式配置 `fixed-index-name`，例如 `sano_user_v1`；
4. **Alias绑定和可见性**：只有 `DAY` 在同步成功后绑定Alias；`MONTH/YEAR/SINGLE` 创建物理索引时立即绑定Alias，接受失败时部分数据可查询；
5. **SINGLE首次全量**：不增加 `task_type`；使用 `tableName_BOOTSTRAP_fixedIndexName` 固定任务ID，`import_date`保持为空并支持断点续跑；
6. **SINGLE对账**：采用全表现状对账，是否执行继续由单表 `reconcile` 配置决定；
7. **直播日期**：`sano_room` 按 `close_time` 同步，仅在关播后进入ES；
8. **直播保留期**：通过 `reserve-days` 自主设置，未配置时永久保存全部月索引；
9. **用户增量字段**：用户表存在可靠且有索引的 `update_time`；
10. **首期接入表**：通用能力完成后同时接入 `sano_room` 和用户表，但分开测试、分开上线。

## 17. 仍需确认的实现边界

以下事项会直接影响任务状态、数据库查询或部署升级，确认后计划才算完整：

1. **Bootstrap触发和重复提交**：建议通过受控人工接口创建 `taskId=tableName_BOOTSTRAP_fixedIndexName`、`import_date=null` 的固定任务；普通每日任务在该任务达到 `SUCCESS` 前只跳过、不创建。需要确认重复调用已经成功的Bootstrap时是直接返回已完成，还是允许受控重置后重新全量覆盖。
2. **SINGLE源数据删除语义**：按 `update_time` Upsert只能处理新增和修改，无法发现MySQL物理删除。需要确认用户数据不会物理删除，或者删除会表现为可同步的状态字段变化；否则必须另外设计删除同步或定期全量重建。
3. **直播表MySQL同步索引**：现有 `sano_room` 没有适合 `close_time + id` 每日游标查询的联合索引。建议增加由 `close_time` 生成的DATE列和 `(close_dt, id)` 联合索引，再配置 `dt-column: close_dt`、`dt-column-type: DATE`。需要确认是否允许修改MySQL表结构；如果不允许，需接受按 `close_time` 查询逐渐变慢的风险并重新评估SQL。
4. **Mapping字段范围与数值精度**：需要确认 `sano_room` 是否同步全部字段，以及 `decimal(64,3)` 的 `diamond/diamond_lucky` 在ES中使用 `double`、`scaled_float`还是字符串保存；同时还需要用户表结构和需要进入ES的字段清单，才能最终确定 `sano_user.json`。
5. **where-sql限制**：计划建议 `MONTH/YEAR/SINGLE` 第一版必须保持 `where-sql` 为空，只使用日期字段生成条件。需要确认是否接受；若直播或用户同步还需要固定业务过滤条件，应在本次增加独立的附加条件配置，而不能继续复用当前“覆盖整个日期条件”的 `where-sql`。
6. **本次接口范围**：需要确认首期只完成索引同步、Bootstrap、补数和对账能力，还是还要同时增加 `sano_room`、`sano_user` 的业务查询接口。当前计划按“只完成同步管理能力，业务查询另行按需求开发”理解。
7. **同表index-mode切换**：第一版不自动迁移已有物理索引。同一张表从 `DAY` 切换到 `MONTH/YEAR` 时，必须先完成全部旧任务，并从新的完整月或完整年边界开始，禁止新旧物理索引覆盖同一业务日期；切换到 `SINGLE` 必须重新全量Bootstrap。需要确认是否接受该运维约束。
8. **上线后的旧版本回滚**：通用代码第一次发布时所有现有表仍保持 `DAY`，可以按原方式回滚；一旦启用 `MONTH/YEAR/SINGLE`，旧版本不再具备这些表的任务创建和对账能力。回滚旧镜像前必须先停止这些新模式表的同步，回滚后只保留查询能力，不能让旧版本继续为这些表创建每日任务。需要确认将该规则写入最终部署文档。

建议发布顺序保持：先发布通用能力且现有表全部为 `DAY`，再单独测试并上线 `sano_room MONTH`，最后单独执行用户 `BOOTSTRAP` 固定任务并开启用户每日增量。

在第17节确认完成前，不实施多周期能力，也不修改现有T+1、对账或查询业务行为。
