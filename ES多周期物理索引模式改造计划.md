# ES多周期物理索引模式改造计划

> 文档状态：方案已确认，四模式通用代码已完成首轮实现及本地隔离验证，等待真实环境联调和升级验证。
> 当前基线：`es-server` 已完整移除基于 MySQL 查询的 Polling，只保留已上线的 T+1；本计划实施时不得破坏 T+1 和优雅升级能力。
> 2026-10-15确认：仅当索引模式为DAY、删除开关为true且保留天数大于0时自动删除历史索引；其余情况均永久保留，由运维、技术人员手动删除。
> 2026-10-15确认：SINGLE人工接口执行全量删除重建，重复提交已完成任务可发起新一轮重建；每日自动任务仅按配置的日期字段同步新增、修改，不自动初始化或重建。暂停续跑复用本轮新索引，不重复删除。
> 2026-10-15确认：SINGLE源数据物理删除不做自动同步或补偿，允许ES暂时保留源端已删除的旧文档，由运维人员定期调用人工全量删除重建接口处理；程序不增加定期重建任务。
> 2026-10-15确认：所有模式统一移除where-sql配置、属性传递及自定义SQL覆盖分支；保留程序按配置生成的日期条件和主键游标分页条件，不新增extra-where-sql等替代入口。
> 2026-10-15确认：本次实现DAY、MONTH、YEAR、SINGLE四种模式的通用同步管理能力；具体数据源接入和业务查询接口后续按需求单独开发，不将直播表、用户表接入纳入本次实施范围。
> 2026-10-15确认：同表index-mode切换由运维主动控制；程序不增加模式变更检测、切换门禁、边界校验或自动迁移流程。
> 2026-10-15确认：回滚时的版本、配置和任务兼容性由运维、技术人员人为控制，不增加程序侧回滚门禁；不将旧版本回滚概括为停止所有每日任务或只能查询。
> 2026-10-15再次确认：保留DAY、MONTH、YEAR、SINGLE四种模式，不缩减为两种或三种。第14节为实施步骤，第18节记录实际完成及待验证范围。

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
8. 当前Reader和MySQL对账支持where-sql覆盖日期条件；仓库现有环境及示例均未配置实际内容，本次计划统一移除该配置与相关执行分支。

其中第7项不需要改变。即使多天写入同一个月、年或固定索引，每张表每天仍然只能有一条T+1任务：

```text
taskId    = tableName_yyyyMMdd
indexName = 根据index-mode计算出的目标物理索引
```

`SINGLE` 首次全量和以后人工发起的全量重建复用一条固定任务记录，不占用业务日期字段：

```text
taskId     = tableName_BOOTSTRAP_fixedIndexName
importDate = null
```

例如用户固定索引 `sano_user_v1` 对应：

```text
sano_user_BOOTSTRAP_sano_user_v1
```

`BOOTSTRAP` 只用于构成固定任务文档ID，不写入 `import_date`，也不是新的任务类型字段。任务执行规则仍然以当前表配置为准，只有 `index-mode=SINGLE` 的表允许创建和执行 `importDate=null` 的固定全量任务；再次人工重建时重置同一条记录，不新增一条历史初始化任务。

## 3. 改造目标

### 3.1 必须实现

- 本次范围为四种模式的通用同步管理，包括索引同步、人工Bootstrap全量重建、每日增量、补数、续跑和对账，以及现有优雅升级兼容；
- 单表配置支持 `DAY`、`MONTH`、`YEAR`、`SINGLE` 四种物理索引模式；
- 现有表未配置时默认 `DAY`，升级后金币、钻石等已有同步行为完全不变；
- T+1任务继续按天创建、执行、暂停、恢复、补数和记录终态；
- 月、年、固定索引创建时立即绑定Alias，允许多个每日任务向同一个物理索引幂等写入；
- ES文档ID继续使用配置的 `id-column`，相同记录重跑时覆盖，不产生重复文档；
- SINGLE全量删除重建仅通过人工接口提交；每日自动任务仅根据`dt-column`、`dt-column-type`同步对应业务日期的新增、修改，日期字段名不在程序中写死；
- 所有模式移除where-sql及相关配置驱动的任意SQL过滤；日期条件和id-column游标分页由程序统一生成；
- 对账能够区分日期分桶索引和固定当前状态索引；
- 历史自动清理仅适用于同时满足删除条件的DAY索引，每次只处理一个确定名称，不扫描Alias下全部索引；其他情况永久保留，由运维、技术人员手动删除；
- 查询统一保留业务Alias，外部调用不因物理索引模式变化而修改；
- 优雅停机、任务断点、通知和日志业务保持现有行为。

### 3.2 本次不做

- 不接入新的具体数据源，不新增`sano_room`、`sano_user`的实际同步配置、Mapping或MySQL表结构改造；这些工作在后续接入对应表时单独确认和开发；
- 不新增直播、用户等业务查询接口；仅保证现有业务查询与Alias兼容；
- 不引入ILM、Data Stream、Redis、MQ或新的调度框架；
- 不重新引入基于MySQL查询的Polling；
- 不在本次多周期改造中实现MQ、Kafka、Outbox或Binlog CDC实时同步；
- 不实现SINGLE源端物理删除的自动同步、差异补偿或定期自动全量重建；由运维定期调用人工重建接口清除ES残留数据；
- 不新增extra-where-sql或其他任意SQL片段配置作为替代；后续确有特殊筛选需求时再单独沟通；
- 不为月、年索引实现“每日数据原子可见”的临时索引加Reindex方案；
- 不自动判断何时从月索引升级为年索引或拆分分片；
- 不自动完成 `SINGLE` 索引的Mapping版本迁移；
- 不控制同表index-mode切换，不增加模式变更检测、旧任务清空门禁或完整月、年边界校验，也不自动迁移已有索引和数据；切换由运维主动安排；
- 不增加回滚版本检测、回滚专用同步门禁或自动配置降级；版本、配置和任务兼容性由运维、技术人员确认处理，不改变现有drain和优雅部署流程；
- 不改变业务查询使用的Alias名称。

## 4. 配置设计

### 4.1 新增索引模式

使用纯配置枚举：

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

`SINGLE` 使用必填配置：

```yaml
fixed-index-name: sano_user_v1
```

不直接使用 `index-alias` 作为物理索引名，因为ES不能同时存在同名索引和Alias。所有查询仍然访问 `sano_user` Alias。

### 4.3 历史保留配置

继续保留现有 `delete-history-index` 和 `reserve-days`，但这两个配置仅对DAY模式的历史自动清理生效。自动删除必须同时满足以下三个条件：

```text
index-mode = DAY
且 delete-history-index = true
且 reserve-days > 0
```

例如，按日索引保留60天：

```yaml
index-mode: DAY
delete-history-index: true
reserve-days: 60
```

删除开关未配置时默认false；`reserve-days`改为可空配置，不再提供默认30天：

```java
private boolean deleteHistoryIndex = false;
private Integer reserveDays;
```

统一规则：

| 模式和配置 | 行为 |
|---|---|
| `DAY`，`delete-history-index=true`，`reserve-days > 0` | 自动删除一个按保留天数计算的到期日索引 |
| `DAY`，未配置删除开关或开关为false | 永久保留，由运维、技术人员手动删除 |
| `DAY`，未配置保留天数或天数小于等于0 | 永久保留，由运维、技术人员手动删除 |
| `MONTH/YEAR/SINGLE`，无论删除开关和天数如何配置 | 永久保留，由运维、技术人员手动删除 |

任何一个自动删除条件不满足，都不进入自动清理流程。保留天数缺失或小于等于0不因此启动报错；非DAY模式即使配置了删除开关和正数天数，也直接跳过自动删除，不增加额外配置报错。

### 4.4 配置示例

以下新增数据源仅用于说明四种模式的配置语义，不代表本次接入计划。直播、用户等表的实际同步配置、Mapping和MySQL索引方案，后续按具体需求确认；本次现有同步表仍保持DAY模式。

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
  reconcile: false
```

直播场次使用月索引：

```yaml
- enabled: true
  index-mode: MONTH
  table-name: sano_room
  index-alias: sano_room
  mapping-file: sano_room.json
  # MONTH模式不执行自动清理，永久保存，由运维、技术人员手动删除。
  delete-history-index: false
  id-column: id
  # 示例暂用close_time；接入直播表时再确认MySQL索引改造及最终日期字段配置。
  dt-column: close_time
  dt-column-type: DATETIME
  reconcile: true
```

少量历史记录使用年索引：

```yaml
- enabled: true
  index-mode: YEAR
  table-name: sano_small_event
  index-alias: sano_small_event
  mapping-file: sano_small_event.json
  # YEAR模式不执行自动清理，永久保存，由运维、技术人员手动删除。
  delete-history-index: false
  id-column: id
  dt-column: create_time
  dt-column-type: DATETIME
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
  # SINGLE模式不执行自动清理，固定索引由运维、技术人员手动维护。
  delete-history-index: false
  id-column: id
  # 这里只是用户表的配置示例；程序使用dt-column配置值，不写死update_time。
  dt-column: update_time
  dt-column-type: DATETIME
  reconcile: true
```

上述用户表示例选择`update_time`作为日期字段；其他表可以配置不同字段。所选`dt-column`必须能反映新增和修改，`dt-column-type`决定按DATE等值或DATETIME左闭右开范围查询。如果只配置不随修改变化的`create_time`，就只能发现新增，不能覆盖已有记录的后续修改。

## 5. 四种模式的业务语义

### 5.1 DAY

保持当前线上逻辑：

```text
创建D日任务
→ 创建 alias_yyyyMMdd
→ 查询MySQL的D日数据
→ Bulk写入
→ 完成后刷新并绑定Alias
→ 仅在删除开关为true且保留天数>0时，最佳努力删除一个准确到期的日索引
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
→ 不执行自动历史清理，月索引永久保留，由运维、技术人员手动删除
→ 在月索引中按D日条件异步对账
```

同一个月的不同每日任务拥有不同 `taskId`，但共享同一个 `indexName`。

### 5.3 YEAR

行为与 `MONTH` 相同，只是一个自然年内的每日任务共享 `alias_yyyy`；年索引同样永久保留，不执行自动清理，由运维、技术人员手动删除。

适合每日数据较少、年累计规模仍可控的历史记录，不建议用于金币、钻石等大流水。

### 5.4 SINGLE

`SINGLE` 表示当前状态索引，不是无限增长历史流水的替代方案：

```text
人工提交全量重建任务
→ 实际执行时删除已有fixed-index-name及其中全部数据（不存在则跳过删除）
→ 重新创建fixed-index-name并立即绑定业务Alias
→ Bootstrap从id起点0全表读取，直接写入已经可查询的固定索引
→ 全量成功后，后续每日按配置的dt-column、dt-column-type查询新增或修改数据
→ 使用业务ID向同一个固定索引Upsert
→ 不执行周期历史索引清理
→ 对账按全表现状执行
```

适合用户、礼物、国家配置等只需要保存每个业务主键最新状态的数据。

每日增量只处理新增和修改；MySQL物理删除的记录可能继续保留在ES中，接受两端暂时不一致。运维人员定期人工全量删除重建后清除这些旧文档，程序不自动发现或补偿物理删除。

## 6. SINGLE首次初始化与日常增量

### 6.1 人工全量删除重建

如果固定索引尚不存在，而定时T+1只查询昨天的数据，会创建一个缺少全部历史用户的半成品索引。因此必须先由人工接口发起全量Bootstrap。该接口也用于以后人工修复，每次新的全量重建都删除当前配置指定的固定索引，再从MySQL全表重新导入；自动任务不承担初始化或删除重建职责。

已确认调用链：

```text
人工提交指定SINGLE表全量重建
→ 校验SINGLE表、all实例和当前未处于drain
→ 检查同表任务执行状态，避免与正在执行的任务或重复提交并发
→ 创建或重置固定Bootstrap任务为PENDING，清零本轮断点和统计
→ 进入现有T+1串行执行队列（HTTP请求不直接删除索引）
→ 任务实际开始执行时删除fixed-index-name及其中全部数据（不存在则跳过删除）
→ 重新创建fixed-index-name并在创建请求中直接绑定Alias
→ MySQL按配置的id-column从0开始全表分页，不追加日期过滤
→ 复用现有Reader、队列、Bulk、内存限制和失败阈值
→ 全量成功后恢复索引设置并刷新
→ 后续才允许每日增量任务写入
```

首次全量和后续人工重建任务仍然落入`sano_import_task`，复用同一固定任务ID，不增加`task_type`，也不改变`import_date`的日期语义：

```text
task.taskId     = tableName + "_BOOTSTRAP_" + fixedIndexName
task.importDate = null
```

执行任务时先读取当前 `SyncTableConfig`：

- `index-mode=SINGLE`且任务ID等于当前配置计算出的固定Bootstrap任务ID、`importDate=null`：执行人工全量重建或本轮全量任务的断点续跑；
- `index-mode=SINGLE`且`importDate=yyyyMMdd`：仅在当前Bootstrap任务SUCCESS后，按配置的`dt-column`、`dt-column-type`执行每日增量；
- `DAY/MONTH/YEAR`：`importDate`必须是合法 `yyyyMMdd`；
- 非SINGLE表或任务ID不匹配时遇到 `importDate=null`，必须将该任务标记失败，不能误执行全表导入。

固定任务的`status`表示当前一轮人工全量任务的生命周期：不存在表示尚未提交，`PENDING/RUNNING/TIMEOUT_PARTIAL/FAILED`都表示本轮尚未成功，只有`SUCCESS`才允许创建和执行该表每日增量任务。全量未成功或重建期间，定时器跳过该表，不自动提交全量，也不通过增量路径补建丢失的固定索引；其他表继续执行。此前已创建的该表每日待任务也不能绕过这一执行门禁。

**重复提交和续跑规则**：

- Bootstrap已经SUCCESS后再次人工调用，允许重置原任务并发起新一轮删除重建，不只返回“已完成”；失败后也可由人工重新发起完整重建。
- 同表已有正在执行或尚待执行的重建任务时，不重复启动另一条重建链路。
- 新一轮重建清零断点、重新统计，从0开始；由于索引整体删除，MySQL中已不存在的旧文档也会随旧索引清除。
- 因deadline或drain暂停后的续跑，复用本轮已经新建的索引和安全断点，不再次删除；实现时必须区分“新一轮删除重建”和“已有任务恢复”，并保证删除、创建、断点恢复顺序正确。
- 重建失败按现有方式记录ERROR、发送通知，不影响其他表继续执行。部分写入的新索引和Alias保留，供排查或本轮恢复；新的人工重建请求仍按删除重建处理。
- 删除旧索引后到新索引创建前，查询可能短暂报索引不存在；新索引绑定Alias后，数据随导入逐步可见，不保留旧索引兜底，接受重建期间不完整查询结果。

任务索引Mapping无需新增字段，也不需要执行PUT Mapping升级。SINGLE人工重建中的显式删除不属于§4.3、§9的自动历史清理，不能被“非DAY不自动删除”的规则误拦截。

删除重建与续跑的状态边界见第14节P4：新Bootstrap准备完成前保持PENDING，确认RUNNING持久化后才开始读取、写入；已有TIMEOUT_PARTIAL即使断点为0也不重新删除。源表为空仍需创建并绑定一个空的固定索引，不能直接复用每日无数据分支而跳过初始化。

### 6.2 后续每日增量

后续每日自动任务仍以业务日期作为任务ID，仅负责新增、修改的覆盖写入；查询字段取自`dt-column`，查询方式取自`dt-column-type`，不能在代码中写死`update_time`。

例如，用户表配置`dt-column: update_time`、`dt-column-type: DATETIME`时：

```text
taskId    = sano_user_20260826
indexName = sano_user_v1
查询条件   = update_time >= D 00:00:00 AND update_time < D+1 00:00:00
文档ID     = 配置的id-column（此例为用户id）
```

同一用户以后再次变化时覆盖原文档，不增加重复用户文档。

通用日期条件为：DATE按`配置日期字段 = D`查询；DATETIME按`配置日期字段 >= D 00:00:00 AND 配置日期字段 < D+1 00:00:00`查询。自动任务不扫描全表、不删除或重建固定索引；Bootstrap未成功或当前固定索引丢失时，跳过或报告该表异常，交由人工处理，不自动触发全量。

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

上述Mapping版本迁移由运维主动安排，并人工确认旧版本SINGLE任务及断点的处理；程序不为该迁移增加自动切换控制。

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

当前状态数据不适合按历史增量日期字段持续对账。以配置为`update_time`的用户表为例，一个用户后续再次更新后，其ES文档的该字段会变化，历史日期统计也会随之变化；字段名不在对账实现中写死。

因此 `SINGLE` 对账应自动解释为全表现状对账：

```text
MySQL全表（不加日期或自定义业务过滤）：COUNT / MIN(id) / MAX(id)
ES固定索引：COUNT / MIN(id) / MAX(id)
```

对几十万用户执行低频全量统计可以接受。是否每天自动执行仍由该表 `reconcile` 配置决定；也可以关闭自动对账，只保留人工调用。

源端物理删除可能使ES数量大于MySQL；对账仅报告统计结果、发送通知，不自动删除ES文档、不修改同步任务终态，也不触发全量重建。按已确认运维策略，由运维人员定期调用人工全量删除重建接口处理；不增加逐条删除核验或自动补偿。

### 8.4 统一移除where-sql，程序生成查询条件

所有DAY/MONTH/YEAR/SINGLE模式统一移除where-sql配置及相关能力，不保留DAY自定义SQL兼容分支，也不新增extra-where-sql等替代入口。现有仓库各环境及示例中的where-sql均为空，移除后保留当前自动日期查询语义。

通用查询规则：

| 场景 | 程序生成的条件 |
|---|---|
| DAY/MONTH/YEAR每日任务、SINGLE每日增量 | 根据dt-column、dt-column-type和任务日期生成DATE等值或DATETIME左闭右开日期条件 |
| 上述任务分页读取 | 在日期条件后追加id-column > lastId，按id-column升序、LIMIT批次大小；值使用参数绑定 |
| SINGLE人工全量重建/续跑 | 不加日期条件，只按id-column > lastId、主键升序和LIMIT批次大小进行全表游标分页；新重建从0开始，续跑使用安全断点 |
| 每日任务MySQL总量统计 | 使用相同日期条件，不附加分页游标或LIMIT |
| DAY对账 | MySQL按相同日期条件统计，ES按该日物理索引整体统计 |
| MONTH/YEAR对账 | MySQL和ES都按相同业务日期范围统计，不能统计整个共享索引 |
| SINGLE全表现状对账 | MySQL全表和ES固定索引整体统计，不附加日期过滤、分页游标或自定义业务条件 |

移除范围包括YML字段及旧注释、SyncTableConfig和TPlusOneImportConfig中的whereSql属性及规范化、任务参数传递、Reader自定义WHERE覆盖分支和对账自定义条件分支。程序内部仍需构造WHERE和绑定参数，移除的是外部配置覆盖查询条件的能力，不删除正常日期及游标分页逻辑。

物理索引模式只决定写入目标，不改变每日任务的MySQL日期范围。后续单表确有特殊业务筛选时再针对实际需求设计，本次不提前预留任意SQL入口。

## 9. 历史索引清理

历史自动清理只适用于DAY模式，继续沿用“计算一个确定名称、最佳努力删除”的原则，不扫描Alias下的全部索引。

进入清理流程前先同时检查：

```text
indexMode == DAY
&& deleteHistoryIndex == true
&& reserveDays != null
&& reserveDays > 0
```

任一条件不满足就直接返回，不查询历史索引是否存在，不调用ES删除接口。对应索引永久保留，由运维、技术人员手动删除；不计算月、年或固定索引的自动到期规则。

条件全部满足时，根据当前完成任务的业务日期计算一个到期日索引：

```text
expiredDate = completedDate - reserveDays天
expiredIndexName = indexAlias + "_" + yyyyMMdd(expiredDate)
```

例如完成日期为2026-08-27、DAY模式、删除开关为true、`reserve-days=60`时，最早保留日期为2026-06-29，只尝试删除2026-06-28的日索引。

清理执行规则：

- 每次只检查一个确定名称，索引不存在时直接跳过；
- 存在性检查和删除均属于最佳努力副作用，异常只记录日志，不改变已经完成的T+1任务终态；
- 不新增全Alias扫描或独立每日清理服务；删除失败遗留的日索引由人工维护，不保证后续日期任务自动补删；
- MONTH/YEAR/SINGLE即使配置`delete-history-index=true`且`reserve-days > 0`，也不得进入上述自动清理路径。

## 10. 查询侧处理

业务查询的稳定入口始终是Alias，物理索引模式不得暴露给外部调用方。

当前金币、钻石查询为了减少参与搜索的索引数量，会通过 `EsSearchUtil.getIndices()` 构造每日物理索引名。改造原则为：

- 现有金币、钻石继续按 `DAY` 生成每日索引，不改变查询结果；
- 后续按需开发月、年、固定索引的业务查询时，优先直接查询Alias以保证正确性；本次不新增这些业务查询接口；
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

`resolve`支持四种模式；`resolveExpired`只为同时满足§9三个删除条件的DAY配置计算到期索引，其他情况不产生自动删除目标，不实现月、年、固定索引的到期算法。

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
| `modules/config/DateColumnType.java` | `DATE/DATETIME`日期查询方式枚举，Reader和对账共用 |
| `modules/index/EsIndexNameResolver.java` | 集中计算目标和到期物理索引名 |

本次不新增具体业务表Mapping。`esmapping/sano_room.json`、`esmapping/sano_user.json`等留到后续数据源接入时，根据最终字段、精度和类型要求单独创建。

SINGLE Bootstrap复用现有T+1执行链路，不新增独立Reader或Bulk Writer。人工全量删除重建入口复用固定任务ID，自动入口仅执行配置日期字段对应的每日增量。实现边界见第17节，实际验证范围见第18节。

### 13.2 修改

| 文件/模块 | 主要改动 |
|---|---|
| `SyncTableConfig` | 增加 `indexMode`、`fixedIndexName`；删除开关默认false、`reserveDays`改为可空；移除whereSql属性及规范化，移除保留天数小于等于0即报错的校验，不因非DAY配置删除参数而报错 |
| `TPlusOneImportConfig` | 携带任务执行和清理所需的索引模式配置，移除whereSql属性 |
| `SanoImportTask` | 保持 `importDate` 只表示 `yyyyMMdd`；固定Bootstrap任务该字段为空 |
| `SanoImportTaskService` | 支持固定Bootstrap任务ID的新增、查询、续跑和人工重建重置，兼容空日期排序及待执行过滤，重置时清零本轮统计及断点，不修改任务索引Mapping |
| `TPlusOneImportTask` | 创建任务时通过解析服务生成物理索引名；SINGLE每日任务创建和执行均检查Bootstrap已成功，不自动提交全量；移除whereSql参数传递及相关注释 |
| `TPlusOneImportService` | 删除内部固定按日命名的兜底，复用月、年、固定索引；区分人工全量新一轮删除重建与暂停续跑，合法Bootstrap不补昨天日期，空全量仍完成固定索引准备 |
| `TPlusOneJdbcReader` | 移除自定义WHERE覆盖分支，保留配置日期条件和主键游标分页；支持人工Bootstrap按id-column全表分页，不写死update_time |
| `TPlusOneIndexService` | 仅DAY且删除开关为true、保留天数>0时清理一个到期日索引，其余情况直接跳过 |
| `ReconcileStatisticsService` | 移除whereSql条件分支，按模式选择程序生成的日期统计或全表现状统计 |
| `EsImportController` | 人工对账使用统一索引解析规则，并增加SINGLE人工全量删除重建任务入口；HTTP仅提交，不直接删除索引 |
| `TPlusOneNotifyService`及drain兼容点 | 正确展示和恢复空日期Bootstrap；不改变通知通道、排空判据或既有部署协议 |
| `EsSearchUtil` | 现有每日方法保持兼容，并禁止新模式误用每日名称 |
| 四份环境YML和示例YML | 显式补充 `index-mode`，移除where-sql字段及相关旧注释，保留并更新日期字段和查询方式的关键注释 |
| 完整设计文档 | 编码完成并验证后再更新，不在计划阶段提前宣称已实现 |

### 13.3 不修改或只做兼容验证

- `TPlusOneJdbcReader` 的正常每日查询与数据库连接管理；
- `TPlusOneBulkWriter` 的Bulk构建、重试和文档ID；
- `ImportContext`、内存限制、队列和有序安全断点；
- `SyncDrainCoordinator` 的排空判据；
- Notify通道实现；
- 外部查询接口路径和业务Alias。

## 14. 开发步骤

### 14.1 实施原则与交付顺序

按P0至P7逐步实施，每一步完成编译及针对性验证后再进入下一步。中间步骤只作为开发检查点，不把尚未完成Bootstrap或对账适配的半成品部署到正式环境。

```text
P0 基线核对
→ P1 配置、命名和移除where-sql
→ P2 DAY/MONTH/YEAR导入与清理
→ P3 SINGLE任务提交、门禁和队列扫描
→ P4 SINGLE重建、全量读取和恢复
→ P5 四种模式对账
→ P6 drain、部署和日志兼容
→ P7 联合验证、文档和清理
```

复用现有调度器、任务索引、Reader、Bulk Writer、内存限制、许可和drain协议。不为四种模式分别建立执行器或流水线，不增加task_type，不引入Polling、租约、分布式调度或自动迁移控制。仅为多处共用的索引名称规则增加集中解析组件；模式差异留在对应业务方法中清晰处理，避免新增大量只有一个调用点的包装类和短方法。

### P0：核对当前基线与影响面

**工作内容**：

1. 核对工作区现有修改，保护用户已修改内容；记录当前T+1正常、无数据、失败、TIMEOUT_PARTIAL、drain及cancel恢复的调用链。
2. 搜索所有按日拼接索引名、解析importDate、计算历史保留期以及传递whereSql的位置，形成实际修改清单。
3. 核对现有任务索引Mapping、JSON空值处理、待任务排序和任务去重行为，避免Bootstrap落成`tableName_null`或被当成昨天的每日任务。
4. 对当前版本先做编译基线检查；已有缺陷若与本次无关，不顺带修改，不重新引入已明确由人工处理的D01屏障设计。

**完成判据**：确定改造只涉及模式配置、索引生命周期、任务识别、读取范围和对账；现有Bulk重试、队列、有序安全断点及连接管理不进行无关重写。

### P1：配置、统一命名和移除where-sql

**主要文件**：`SyncTableConfig`、`TPlusOneImportConfig`、`IndexMode`、`EsIndexNameResolver`、配置参数传递及环境YML。

1. 新增带清晰注释的DAY/MONTH/YEAR/SINGLE枚举，默认DAY；SINGLE使用显式fixed-index-name。
2. 将索引模式和固定名称传递至单次导入配置。统一解析日、月、年和固定物理索引名，任务、导入与对账不再各自硬编码日后缀。
3. 保留启动时启用表的必要配置校验；SINGLE校验固定名称必填且不与自身Alias同名，不增加模式切换、完整月年边界或旧版本回滚检测。
4. delete-history-index默认false，reserve-days改为可空；仅DAY、开关为true、天数>0时允许自动清理，其他情况不报错、不删除。
5. 一次性移除配置类、执行参数、Reader及对账中的where-sql覆盖能力，以及所有环境、示例YML中的字段和旧注释，保留程序生成的日期条件和参数绑定。
6. 现有启用表全部保持DAY；保留当前显式删除开关、天数、对账开关、批次及资源参数。不得调整数据库密码、ES地址、Token或新增实际业务表。
7. 保留YML关键注释和IDE配置元数据支持，避免属性移除后仍遗留文档或绑定提示。

**验证**：旧配置未写index-mode仍为DAY；四种名称正确；日期查询条件移除where-sql前后相同；空、0、负数保留天数不触发删除或启动失败。

### P2：DAY/MONTH/YEAR索引生命周期及补数

**主要文件**：`TPlusOneImportTask`、`TPlusOneImportService`、`TPlusOneIndexService`。

1. 定时、全表日期补数、单表日期补数入口统一按模式计算indexName；每日taskId继续为tableName_yyyyMMdd，任务已持久化的目标索引用于本次执行和续跑，不在恢复时静默改写目标。
2. DAY保持新索引创建时不绑定Alias、成功后绑定的行为；MONTH/YEAR不存在时创建并绑定Alias，存在时复用，不删除共享索引。
3. 同月、同年多天任务仍由现有dispatcher串行执行；按业务ID覆盖写入。补一天数据不得清空月、年索引，不影响其他日期数据。
4. 保持现有导入前优化、成功/暂停/失败后的设置恢复和刷新顺序；共享索引允许部分写入在刷新后可见，不承诺每日数据原子上线。
5. 保留COUNT为0时不创建新的日期分桶空索引的现有行为，以及已有索引续跑到末尾的正确收尾。
6. 自动历史清理先判断三个条件；命中时只处理一个到期日索引，存在性检查和删除都纳入最佳努力异常处理，不影响任务终态。
7. 保持现有补数接口的任务去重语义：已经存在的任务不会因为重复提交自动重置SUCCESS或FAILED。SINGLE人工重建的重置能力是明确的独立操作，不扩散为所有任务的强制重跑能力。

**验证**：DAY兼容；MONTH跨月、YEAR跨年命名正确；同周期多日写入、补数和续跑不丢其他日期；非DAY模式完全不进入历史删除路径。

### P3：SINGLE人工提交、每日任务门禁和扫描

**主要文件**：`SanoImportTask`、`SanoImportTaskService`、`TPlusOneImportTask`、`EsImportController`。

1. 增加人工提交指定SINGLE表全量删除重建的管理入口，建议使用POST并明确破坏性语义；HTTP只创建或重置任务，不直接删除索引，不接收任意物理索引名或SQL。
2. 固定任务ID为tableName_BOOTSTRAP_fixedIndexName，import_date为空，不增加task_type或额外任务文档。普通每日任务ID及日期含义不变。
3. 人工新一轮重建重置同一任务为PENDING，清零断点及本轮统计；已SUCCESS或FAILED可人工重建。重复待执行、执行中或暂停待续跑的同一重建，不再并发重置。
4. 复用当前单all实例的任务启动门禁，确保人工重置不能与同表任务开始执行发生竞态；仍允许其他表正常运行，不引入跨实例租约。
5. 定时创建、人工日期补数和实际执行三处都遵守SINGLE门禁：当前固定Bootstrap未SUCCESS时跳过该表每日任务，不自动提交全量、不改写为FAILED；其他表继续执行。
6. 调整待任务查询及排序，使import_date为空的Bootstrap能够被扫描和执行；待执行Bootstrap优先于被其阻塞的每日任务。
7. 对已有但暂不可执行的SINGLE每日任务，扫描时排除或按本轮可执行条件过滤，不能只在for循环continue后立刻重新拉取同一批，否则可能空转或长期遮挡其他表任务。
8. 禁用表、非SINGLE表、固定任务ID不匹配或非法空日期不允许进入全表导入；这是任务识别校验，不是模式切换管理。

**验证**：固定任务创建/重复提交/重置；Bootstrap未成功时每日入口跳过；超过task-fetch-limit的被阻塞任务不饿死其他表；后台提交失败后已持久化任务仍可后续扫描执行。

### P4：SINGLE删除重建、全量读取和续跑

**主要文件**：`TPlusOneImportTask`、`TPlusOneImportService`、`TPlusOneJdbcReader`、`TPlusOneIndexService`。

1. SINGLE全量必须同时满足表模式、固定任务ID及空日期规则；只凭importDate为空不能触发全量。执行配置不再把合法Bootstrap的空日期补成昨天。
2. 新一轮Bootstrap先注册现有活动任务门禁，在后台删除固定索引（不存在则跳过），创建新索引并立即绑定Alias，再开始本轮全量写入。普通每日增量无权删除、重建或补建固定索引。
3. 使用现有任务状态建立准备边界，不新增持久字段：新Bootstrap在索引删除、重建准备完成前保持PENDING；准备完成并确认RUNNING持久化成功后，才允许启动源数据读取和Bulk写入。仅调整Bootstrap的RUNNING落库时机，普通每日任务保持现有顺序。
4. 准备阶段宕机留下PENDING时，可在下次重新删除重建，因为此前尚未允许写入；RUNNING残留按现有机制恢复为TIMEOUT_PARTIAL后复用本轮新索引，不重新删除。RUNNING更新响应不确定时不启动写入，也不能用另一个终态盲目覆盖。
5. TIMEOUT_PARTIAL续跑即使lastSuccessId=0，也必须复用本轮新索引，不能仅凭ID为0判断是否重建；若已准备的Bootstrap续跑时索引丢失，报告失败并交由人工重新发起，不冒充正常续跑。
6. 准备前被drain拒绝的任务仍保留PENDING；已进入准备阶段时不在准备尚未完成的情况下保存一个可被误认成已准备的TIMEOUT_PARTIAL。准备完成后接收停止信号，按现有流程保存断点；准备失败则记录失败，不伪装为已完成准备。
7. 全量COUNT不附加日期条件；分页仅使用id-column > lastId、升序和LIMIT。每日增量仍按配置日期字段过滤；保留JdbcTemplate查询后归还连接、查询结束后再等待队列/内存的现有资源管理方式。
8. Bootstrap源表为空也必须完成删除、创建和Alias绑定，最终SUCCESS，不能沿用普通每日任务“COUNT为0就不创建索引”的分支。空全量属于成功构建一个空的固定索引。
9. 复用现有Bulk、内存预算、安全断点、失败阈值及终态持久化规则；SINGLE增量发现固定索引丢失时只报告该表失败，不自动全量，不阻断其他表。
10. 全量重建不是MySQL一致性快照，也不改变现有安全断点含义；源数据持续变化时可能产生统计差异，仍由对账通知和人工复核处理。日期补数读取的是源端现状，不提供历史版本还原。

**验证**：首次全量、再次删除重建、空全量、lastId=0暂停、非0暂停、准备阶段宕机、RUNNING残留、目标索引丢失、查询/写入失败，以及“续跑绝不重新删除”的边界。

### P5：四种模式对账及人工接口

**主要文件**：`ReconcileStatisticsService`、`EsImportController`及对账结果模型/通知展示。

1. DAY保持MySQL按D日、ES按日物理索引整体统计。
2. MONTH/YEAR统一解析目标索引，MySQL和ES都按相同配置日期字段限制D日；ES总量、最小ID和最大ID查询必须全部带同一日期过滤，不能只给其中一条请求加条件。
3. SINGLE无论由全量成功、每日增量成功或人工调用触发，均执行MySQL全表与固定索引的现状对账，不使用历史增量日期过滤。
4. 人工对账入口对DAY/MONTH/YEAR仍要求日期；SINGLE不强制要求日期，通知明确标注全表现状，避免把传入日期误解释为统计范围。
5. 保留单表reconcile开关、现有异步执行器和COUNT/MIN/MAX范围，不增加持久对账任务或数据级修复。MySQL为0时继续按当前约定通知MYSQL_EMPTY并跳过ES统计，不能宣称两端已匹配；MySQL大于0时先刷新目标索引再统计。
6. 对账执行、异步提交或通知失败不改变同步任务已保存的SUCCESS，不自动删除源端已消失记录，也不触发全量重建。

**验证**：共享月年索引中同时存在多日数据时，每日对账不混入其他日期；SINGLE修改日期变化后仍按全表现状统计；开关关闭、MySQL为空、ES错误及通知失败均符合现有隔离规则。

### P6：优雅升级、就绪和日志兼容

**主要文件**：T+1编排/通知、`SyncDrainCoordinator`兼容点；就绪、Compose、部署脚本和日志配置以核对为主。

1. 保持一个all实例执行同步、临时query实例只接管查询；Bean注册、实例角色、T+1总开关及drain启动门禁不改变。
2. 确保Bootstrap准备、全量写入和每日增量均被活动任务追踪；drain不能因尚未创建Reader/Bulk就提前判定安全排空。
3. 保持停止新读取、已读取批次收敛、连续安全断点保存、恢复索引设置、任务状态持久化的顺序；cancel精确恢复路径支持空importDate的Bootstrap，不将其解析成普通日期。
4. 通知、日志和drain状态展示区分Bootstrap与每日任务；保留ES-TPlusOne前缀、原日志分流和统一汇总文件，不另建一套周期模式日志体系。
5. /ready继续按现有查询Alias及任务索引要求工作，不因某张SINGLE表Bootstrap未SUCCESS而新增全局就绪门禁。若后续新环境仅有尚未创建Alias的SINGLE表，部署文档说明先人工启动、重建并确认就绪，不为了部署通过而伪造ready。
6. 两份Compose、正式/测试部署脚本优先保持原流程；若源码适配不要求脚本变化，不为模式扩展增加额外开关或回滚阻断。
7. 模式切换和回滚目标版本兼容性由运维、技术人员处理，只补充注意事项，不新增自动迁移、回滚门禁或配置降级。

**验证**：四种模式执行中safe升级；Bootstrap准备前后及Bulk期间drain；drain取消续跑；query不启动同步；DAY既有部署冒烟正常。实际服务器升级由用户执行，不在开发过程中自行操作生产服务。

### P7：联合验证、交付文档与清理

1. 完整编译、打包；逐项检查配置绑定及必要校验、任务JSON序列化、旧任务文档读取和空日期扫描。
2. 在隔离的开发/测试数据上验证四种通用模式，不接入真实直播或用户数据，不删除、重建生产索引。
3. 按第15节逐项回归现有DAY表、月年共享索引、SINGLE全量/增量/续跑、历史清理和异步对账。
4. 验证MySQL异常、ES异常、线程中断后连接、线程、许可和内存额度释放；不得为了本次模式扩展新增连接常驻占用或额外查询线程池。
5. 记录实际完成和未完成的验证；没有运行真实MySQL/ES或safe部署时，明确标注待用户环境验证，不以编译通过代替稳定性和性能结论。
6. 编码完成后更新完整设计文档、部署文档和本计划状态；写清新增管理接口的请求方式、参数、破坏性行为、第一次Bootstrap、补数、对账及优雅升级命令。
7. 清理本次临时测试用例、临时验证文件及仅供测试使用的业务入口；不保留测试专用构造器、开关或额外包装，亦不删除用户无关文件。
8. 最后检查注释、废弃字段/引用、日志路由和改动范围，现有环境业务表仍全部为DAY，未重新引入Polling或具体新表接入内容。

**交付方式**：按阶段汇报实际改动、验证结果和剩余事项；最终交付完整四模式能力及操作文档。首次正式发布不切换现有表模式，新数据源另行确认、测试和上线。

### 后续按需：具体数据源接入（不属于本次范围）

通用能力完成后，具体表的接入、测试和上线另行按需求安排，不作为本次验收条件：

- 直播表已确认按`close_time`业务日期同步、采用MONTH模式。实际接入前，再结合表结构、数据量和SQL执行计划确定MySQL字段及索引改造；允许修改表结构，但`close_dt`及`(close_dt, id)`仍只是候选方案。届时确认同步字段、diamond/diamond_lucky精度及ES类型，创建Mapping，并验证跨日关播、同月多日写入和单日对账。
- 用户表拟采用SINGLE模式。实际接入前，再确认最新表结构、同步字段、配置日期字段及Mapping，然后分别验证人工全量重建和每日增量。
- 各表业务查询接口在明确查询需求后单独设计和开发，不随通用同步管理能力一并增加。

## 15. 验收清单

### 15.1 兼容性

- 未配置 `index-mode` 的现有表仍生成 `alias_yyyyMMdd`；
- 金币和钻石T+1任务、补数、对账和查询结果不变；
- 所有模式的配置类、执行参数和YML均不再包含where-sql能力，不保留自定义SQL覆盖分支或extra-where-sql入口；
- DATE/DATETIME日期条件、id-column > lastId、主键升序和LIMIT分页仍按程序生成，现有日期同步结果保持一致；
- 旧的 `sano_import_task` 每日任务ID仍可读取和续跑；
- query实例仍只提供查询，不启动同步任务。
- 现有DAY表显式配置删除开关为true、保留天数>0时，继续按原规则清理；
- DAY未配置删除开关时默认false；保留天数缺失、为0或负数时不自动删除，也不因此启动失败；
- DAY仅在类型、开关和正数天数三个条件同时满足时自动清理，存在性检查或删除失败不影响任务成功终态。

### 15.2 MONTH/YEAR

- 同月或同年不同日期任务拥有不同taskId但写入相同indexName；
- 重复执行同一天不会增加重复文档；
- 月、年索引的对账只统计目标业务日期；
- 跨月、跨年时创建正确的新物理索引；
- 月、年索引永久保留，由运维、技术人员手动删除；
- 即使删除开关为true且保留天数>0，月、年模式仍不查询到期索引、不调用自动删除接口。

### 15.3 SINGLE

- 固定索引不存在时，普通每日任务不能绕过首次全量Bootstrap直接上线；
- 人工接口仅提交全量任务，后台实际开始新一轮执行时才删除旧固定索引并重新创建；
- 已成功的Bootstrap可再次人工重置，清零本轮断点和统计后从0删除重建；同表任务执行期间不会并发启动第二条重建链路；
- 创建固定索引时立即绑定Alias，Bootstrap过程中已成功写入的数据可以查询；
- 全量暂停后复用本轮新索引和安全断点继续执行，不再次删除；失败只影响该表，不阻断其他表；
- 首次全量成功后Alias可查询本轮全表导入的数据；
- 自动任务只按配置日期字段和类型读取每日新增、修改，不硬编码update_time，不自动全量、删除或重建索引；
- Bootstrap未成功或人工重建期间，阻止该表每日任务创建和执行，其他表正常执行；
- 后续新增和修改记录都按配置的相同业务ID覆盖到固定索引；
- 重复增量任务不产生重复文档；
- MySQL物理删除后，增量任务不自动删除ES对应文档；对账只报告结果，不触发删除补偿或重建，运维定期人工全量重建后清除旧文档；
- 固定索引不自动删除；即使配置删除开关为true和正数保留天数，也仅忽略自动删除参数，不因此启动报错；
- 全表现状对账不按历史增量日期字段过滤，不受后续修改导致的日期归属变化影响。

### 15.4 稳定性

- JdbcTemplate查询结束后连接正常归还连接池；
- 共享月、年、固定索引任务保持串行，不并发修改同一个索引设置；
- drain到达后不启动新任务，已读取批次仍按现有规则收敛；
- T+1安全断点和任务终态没有混用；
- 失败日志和通知包含taskId、tableName、indexName、可空importDate和indexMode。

## 16. 已确认的业务决策

1. **范围**：第一版只扩展 T+1，使其支持 `DAY/MONTH/YEAR/SINGLE`；不重新引入数据库Polling；
2. **历史配置**：仅DAY、`delete-history-index=true`、`reserve-days > 0`同时满足时自动删除；删除开关默认false，保留天数可空；其余情况均永久保留，由运维、技术人员手动删除，不因此启动报错；
3. **SINGLE物理名称**：显式配置 `fixed-index-name`，例如 `sano_user_v1`；
4. **Alias绑定和可见性**：只有 `DAY` 在同步成功后绑定Alias；`MONTH/YEAR/SINGLE` 创建物理索引时立即绑定Alias，接受失败时部分数据可查询；
5. **SINGLE人工全量**：不增加`task_type`；使用`tableName_BOOTSTRAP_fixedIndexName`固定任务ID、`import_date=null`；人工接口可在成功后再次发起删除重建，实际执行时删除固定索引并从0全表导入；暂停续跑不再次删除；
6. **SINGLE对账**：采用全表现状对账，是否执行继续由单表 `reconcile` 配置决定；
7. **后续直播接入日期**：`sano_room` 按 `close_time` 同步，仅在关播后进入ES；该表实际接入不属于本次范围；
8. **后续直播保留期**：直播采用MONTH模式，程序永久保留全部月索引，运维、技术人员按业务需要手动删除；
9. **每日增量字段**：使用各表配置的`dt-column`、`dt-column-type`，不硬编码update_time；用户表已确认存在可靠且有索引的update_time，可作为该表配置值；
10. **本次实现范围**：仅完成DAY、MONTH、YEAR、SINGLE四种模式的通用同步管理能力；具体数据源接入和业务查询接口后续按需求单独开发，替代此前“本次同时接入直播表和用户表”的安排；
11. **SINGLE自动任务边界**：仅执行配置业务日期的新增、修改覆盖写入，不自动初始化、删除重建或补建固定索引；全量未成功或重建期间跳过该表每日增量，其他表正常执行；
12. **SINGLE源端物理删除**：不做自动删除同步或数据补偿，接受ES暂时残留旧文档；由运维人员定期人工全量删除重建，程序不新增定期重建任务，对账不触发自动修复；
13. **直播表MySQL改造**：允许修改sano_room表结构；具体字段、索引方案及DDL在实际接入该表时进一步沟通确认，不将close_dt或(close_dt, id)提前定为必选方案；
14. **Mapping确认时机**：各表同步字段范围、数值精度和ES类型在实际接入该表时深入沟通确认；直播表金额类型、是否同步全部字段，以及用户表结构和字段清单均不在通用能力阶段提前定案；
15. **查询条件配置**：所有模式移除where-sql及属性传递、Reader/对账自定义条件分支，不新增替代SQL配置；保留程序按日期配置和主键生成的查询条件，SINGLE人工全量只按游标分页、不按日期过滤；
16. **同表模式切换**：由运维主动控制配置调整、旧任务处理、索引及Alias维护和数据迁移；程序不增加切换检测、门禁、月年边界校验或自动迁移。SINGLE本身的人工Bootstrap与每日增量规则保持不变；
17. **旧版本回滚**：由运维、技术人员人为确认回滚目标的模式支持范围，并处理配置和任务兼容性；不增加程序侧回滚控制。仅支持DAY的旧版本仍可处理兼容的DAY表，不能未经安排直接继续处理新模式表；已支持四种模式的版本之间回滚，不因模式本身统一禁止同步或对账。

## 17. 实现边界确认结果

以下事项会直接影响任务状态、数据库查询或部署升级。第1、2、5、6、7、8项已于2026-10-15确认；第3项已确认允许修改表结构；第3、4项具体方案均留到后续对应表接入阶段深入确认，不属于本次实现范围，也不阻塞通用多周期能力的设计。后续已获明确编码授权并按以下边界实施：

1. **Bootstrap触发和重复提交（已确认）**：人工接口创建或重置固定Bootstrap任务；每次新的全量重建在后台实际执行时删除旧固定索引、重建绑定Alias并从0全表导入，成功后的重复调用允许发起新一轮重建；同表防止并发重复执行，暂停续跑不重复删除。自动任务仅根据配置日期字段同步每日新增、修改，不自动初始化或重建；Bootstrap未成功时不创建、不执行该表每日增量，其他表正常执行。不增加task_type或额外任务文档。
2. **SINGLE源数据删除语义（已确认）**：按配置日期字段Upsert仅处理新增和修改，源端物理删除导致的ES残留和暂时不一致不做同步兜底；由运维人员定期调用人工全量删除重建接口处理，不新增自动删除同步、差异补偿或定期自动重建。对账只报告统计结果和通知，不触发自动修复。
3. **直播表MySQL同步索引（允许改表，具体方案延后确认）**：已确认允许修改sano_room表结构。实际实现该表同步时，再结合数据量、现有索引、每日按close_time业务日期读取的SQL及执行计划，深入沟通字段和索引改造。由close_time派生DATE列close_dt并增加(close_dt, id)联合索引仅为候选方案，尚未确定采用；本阶段不执行DDL，不预先增加配置或字段。该项不阻塞通用多周期能力，直播表接入前必须完成具体方案确认。
4. **Mapping字段范围与数值精度（已确认延后到各表接入阶段）**：实际同步sano_room或用户表时，再结合最新MySQL表结构、业务查询需求和精度要求，深入确定同步字段清单及ES Mapping。sano_room是否同步全部字段、decimal(64,3)的diamond/diamond_lucky使用何种ES类型，以及sano_user.json所需表结构和字段范围，本阶段均不提前定案，也不预先创建Mapping；各表接入前完成确认即可，不阻塞通用多周期能力设计。
5. **移除where-sql（已确认）**：DAY/MONTH/YEAR/SINGLE全部移除该配置及相关属性、参数传递和自定义WHERE覆盖逻辑，不保留DAY兼容分支、不增加extra-where-sql。正常每日同步保留程序按dt-column、dt-column-type及任务日期生成的日期条件，分页继续使用id-column > lastId、主键排序和LIMIT；SINGLE人工全量不按日期过滤，对账按§8.4统一生成条件。
6. **本次实现范围（已确认）**：完成DAY、MONTH、YEAR、SINGLE四种模式的通用同步管理能力，涵盖索引同步、人工Bootstrap全量重建、每日增量、补数、续跑和对账，并保持现有优雅升级能力。具体数据源的配置、Mapping、MySQL改造和业务查询接口，后续按需求单独开发；本次不直接接入`sano_room`或`sano_user`。
7. **同表index-mode切换（已确认由运维控制）**：程序不控制模式切换，不增加变更检测、旧任务清空门禁、完整月或年边界校验，也不自动迁移索引或数据。由运维主动安排配置修改、旧任务及断点处理、索引和Alias维护，避免重复覆盖或漏同步；不把“必须从完整月或年开始”作为程序规则。切换到SINGLE后仍遵守其通用执行语义：人工Bootstrap成功后才执行每日增量，但不额外增加“检测到切换就强制重建”的逻辑。
8. **上线后的旧版本回滚（已确认由运维、技术人员控制）**：四种模式均保留每日任务机制，新版本按模式确定写入目标和对账范围。回滚目标若为改造前仅支持DAY的旧版本，其新建任务仍固定使用日索引名，对账仍按日索引定位并统计整个索引，不能直接正确处理MONTH/YEAR/SINGLE；兼容的DAY表仍可正常同步、补数和对账，并非所有任务都必须停用或回滚后只能查询。由运维、技术人员主动确认目标版本能力，安排新模式表的配置、已有任务及索引处理。若回滚目标本身已支持四种模式，则不存在上述仅支持DAY的特定限制；其他版本差异仍由人工复核。程序不增加回滚检测、额外门禁或自动配置降级，保留现有drain、停止和恢复流程；最终部署文档仅说明兼容性注意事项和人工处理责任。

本次发布通用能力时，现有同步表全部保持`DAY`，验证现有T+1和优雅升级不受影响。后续各数据源接入时，再分别确定实际配置、测试和上线安排，不将直播表或用户表接入绑定为本次发布步骤。

第3、4项已明确延后的表级方案，在各自接入前确认，不作为通用能力的前置阻塞；本次未增加具体数据源或业务查询接口。

## 18. 实际实施与验证记录（2026-10-15）

已完成四模式通用配置、统一命名、日期分桶创建及复用、DAY条件清理、SINGLE固定任务及人工重建、全量读取/每日增量门禁、暂停恢复和模式化对账。复用原有T+1链路，新增IndexMode、DateColumnType两个纯配置枚举及EsIndexNameResolver公共命名类，没有拆分四种执行类、增加task_type或修改任务索引Mapping。

已完成的本地检查：

- 改动前离线编译基线通过；改动后离线编译及Maven打包通过。
- 隔离验证程序使用本地HTTP桩替代ES、JdbcTemplate桩替代MySQL，运行真实Reader、队列、Bulk、ImportService和任务编排，完成192项断言。
- 覆盖四模式命名、跨月跨年、五份YML绑定和非目标配置值不变、DAY行为、月年索引复用、SINGLE首次及重复重建、空全量、零断点续跑、索引丢失、准备阶段drain和cancel恢复、RUNNING响应丢失、查询异常及资源归还。
- 覆盖不同保留天数和模式组合、历史存在性检查失败不改变业务结果、阻塞的SINGLE每日任务不遮挡其他表，以及实际序列化的待任务过滤、整文档重置和四模式对账请求。
- 现有表保持DAY，原启停、对账、删除、资源、数据库及ES配置值未改变；未修改Compose、部署脚本和日志分流规则，仅补充使用文档及兼容说明。
- 后续将日期查询方式提取为DateColumnType枚举，配置和执行模型均使用枚举，Reader及对账采用穷举switch；YML不变。打包及31项针对性断言通过，覆盖五份YML绑定、默认值、DATE/DATETIME读取条件和Bootstrap全量条件；此次检查未访问真实数据库或ES，临时验证代码已清理。

本地HTTP桩不会执行真实ES查询，JDBC桩不会访问真实连接池；上述断言不能替代实际Mapping解析、MySQL执行计划、连接池和GC观察或服务器safe升级。真实MySQL/ES端到端、异步执行器运行、负载及部署验证仍待测试环境完成，不宣称已上线或已完成性能验收。临时验证程序及产物在验证后清理，不保留测试专用业务代码。

操作接口和完整命令见《es-server完整部署文档》第18节，当前实现说明已同步更新至《延迟轮询同步ES完整设计文档》。
