# 延迟轮询同步 ES 简化版开发计划

> 当前有效设计：`延迟轮询同步ES简化版设计.md`
>
> 线上版本 A 是回归基线。原《延迟轮询同步ES开发任务.md》和
> 《延迟轮询同步ES设计文档.md》记录的是已放弃的旧版本 B，不再作为当前实现依据。

## 1. 开发约束

1. 保持 T+1、查询接口、Alias 规则和 all/query 安全部署能力不变。
2. 一张表只能配置 `t-plus-one` 或 `polling` 一种自动同步模式。
3. 类、公共方法、关键状态和持久字段保留准确注释，直观语句不重复解释。
4. 单表流程保持集中，不重新引入队列、多个 Bulk Worker、Sequence 或过度拆分类。
5. Checkpoint 只保存业务恢复点，不承担实例协调。
6. 当前部署只允许一个 `all`；临时 `query` 不启用同步。
7. 验证可以使用临时文件或临时测试，但交付时删除全部测试源码、测试依赖和测试专用业务代码。

## 2. 当前代码结构

| 组件 | 职责 |
| --- | --- |
| `EsImportProperties` | common、T+1、Polling 和表目录配置 |
| `PollingJdbcReader` | 按日期和递增 ID 同步读取一批 MySQL |
| `PollingBulkWriter` | 完整 Bulk 写入、整批重试、耗尽后告警并返回 |
| `PollingTableWorker` | 单表串行主循环、日期关闭、错误暂停和停止保存 |
| `PollingSyncCoordinator` | 本机 Worker 编排、一表一个 Worker、统一 drain |
| `SyncCheckpoint` | 单表持久业务状态和恢复点 |
| `SyncCheckpointService` | Checkpoint内部索引创建、文档初始化和生命周期原子更新 |
| `ReconcileStatisticsService` | 独立异步统计对账和通知 |
| `SyncStatusService` | 分开展示持久进度、Worker 运行态和 drain |

## 3. 已完成任务

### P1. 配置模型

- [x] `sano.import` 下按 `common / t-plus-one / polling / tables` 排列。
- [x] Polling 配置只保留主循环和 Bulk 重试参数。
- [x] 删除 MySQL 查询超时和查询重试配置。
- [x] 删除固定 Reader 数、Bulk Worker 数配置。
- [x] 每表增加 `sync-mode`、`bootstrap-start-date` 和 `reconcile`。
- [x] `tableName` 作为唯一标识，`indexAlias` 为空时默认等于 `tableName`。
- [x] 开发环境 `sano_wallet_coin_record` 使用 Polling，起始日期为 2026-07-27。

### P2. Checkpoint

- [x] 每张表固定一条文档，文档 ID 使用 `tableName`。
- [x] 只保存日期、ID、RUNNING/PAUSED、错误和生命周期时间。
- [x] 首次启动按 `bootstrap-start-date, ID=0` 创建。
- [x] 正常 Bulk 只更新内存游标，不写 Checkpoint。
- [x] 跨日原子更新到 `D+1, ID=0`。
- [x] 错误暂停保存内存进度并置为 `PAUSED`。
- [x] 优雅停止保存内存进度，状态保持 `RUNNING`。
- [x] 人工暂停和恢复使用原子状态更新。
- [x] 删除 owner、token、到期和续期字段。
- [x] 删除租约请求、续期线程、fencing 和时钟漂移保护。

### P3. MySQL 串行读取

- [x] 使用 `tableName + 日期条件 + id > lastId + ORDER BY id + LIMIT`。
- [x] 支持 `DATE` 等值查询。
- [x] 支持 `DATETIME` 当天左闭右开范围。
- [x] 支持受信任的完整 `where-sql`。
- [x] 使用 `queryForList` 同步等待，不增加 Polling 查询超时和重试状态机。
- [x] 返回首尾 ID，供写入日志、通知和游标推进使用。

### P4. ES 整批写入

- [x] 一个读取批次对应一个完整 Bulk。
- [x] 请求异常、item 失败和响应数量异常统一整批重试。
- [x] 默认首次失败后再重试 2 次。
- [x] 重试期间复用相同索引、文档 ID 和内容。
- [x] 重试耗尽后记录范围并异步告警。
- [x] 重试耗尽不暂停表，Worker 继续推进该批最大 ID。
- [x] drain 到达后仍写完已经从 MySQL 读取的批次。

### P5. 单表 Worker

- [x] 同一线程串行执行 SQL 和 Bulk。
- [x] 当前日期空批次按 `poll-interval` 等待。
- [x] 使用 `LocalDate/LocalDateTime` 计算业务日期和关闭时间。
- [x] 系统性错误保存当前进度并暂停该表。
- [x] Checkpoint 暂时不可写时停止业务读写并继续尝试保存。
- [x] 暴露阶段、内存日期、内存 ID、停止标记和保存结果快照。

### P6. 日期关闭

- [x] 增加 `date-close-delay`，默认 10 分钟。
- [x] 关闭判定使用“当前日期已晚于 D + 关闭时间后的 SQL 仍为空”。
- [x] 先创建并绑定 D+1 物理索引。
- [x] 再原子推进 Checkpoint 到 `D+1, ID=0`。
- [x] 推进后异步调用 D 的对账和历史索引删除。
- [x] 对账或删除失败不回退日期、不暂停表。

### P7. 独立对账

- [x] 对账配置下沉到每张表，默认启用。
- [x] 使用 `@Async("esReconcileExecutor")` 调用一次执行一次。
- [x] 只比较 MySQL/ES 的 `count/min(id)/max(id)`。
- [x] 结果一致、存在差异或失败都提交 Lark 消息。
- [x] 不保存对账任务，不维护队列配置、领取、重试和持久状态。
- [x] 提供人工对账接口。
- [x] 提供指定 Polling 表和日期的 T+1 全量修复任务接口。

### P8. 历史索引

- [x] 未引入全局每日历史索引扫描服务。
- [x] T+1 每个任务完成后调用既有 `EsIndexManager` 删除逻辑。
- [x] Polling 真实跨天后异步调用相同逻辑。
- [x] 每次只计算一个按保留天数到期的物理索引。
- [x] 删除失败不阻断同步。

### P9. 本机协调、状态与 drain

- [x] `PollingSyncCoordinator` 使用 `activeWorkers<tableName,...>` 保证本 JVM 一表一个 Worker。
- [x] 删除实例 UUID、租约领取、续期和失效处理。
- [x] 人工暂停先阻止重启、等待 Worker 保存进度，再持久化 `PAUSED`。
- [x] 人工恢复后允许本机重新启动 Worker。
- [x] 统一 drain 停止新 Worker，等待当前 SQL/Bulk，保存所有最终 Checkpoint。
- [x] drain cancel 在旧 Worker 全部退出后重新启动 Polling。
- [x] 状态接口只区分持久业务状态、协调器状态、Worker 状态和 drain 结果。
- [x] `/ready` 校验 Checkpoint 索引、协调器和 RUNNING 表的 Worker/等待槽位状态。

### P10. 单 all 部署边界

- [x] all/query 使用相同镜像和 Bean，只由 `sano.server-mode` 决定是否启用同步。
- [x] query 模式不启动 T+1 和 Polling。
- [x] safe 部署先启动 query，再 drain 和停止旧 all，随后启动新 all。
- [x] 部署脚本在旧 all 删除后才启动新 all。
- [x] 回滚先停止失败的新 all，再恢复旧 all。
- [x] 当前文档明确禁止多个 all 同时运行。
- [x] 未来多 all 改用 Redis 临时占用，不向 Checkpoint 重新加入租约。

### P11. 内部索引和接口

- [x] Polling Checkpoint 索引不在启动时自动创建。
- [x] 提供与 T+1 任务索引一致的人工初始化接口。
- [x] Mapping 已删除全部租约字段。
- [x] 手工暂停、恢复、状态、对账和修复接口已接入。

## 4. 本轮移除租约的验证任务

- [x] 全局搜索生产源码、YAML、Compose 和脚本，确认无租约、owner、token、续期和时钟保护残留。
- [x] 校验 Checkpoint Mapping JSON。
- [x] 编译主源码。
- [x] 打包可执行 Jar。
- [x] 使用 query 模式启动 Spring，验证共用 Bean 注册不受影响。
- [x] 使用 all 模式且关闭同步开关启动 Spring，验证 all 构造和基础调用链。
- [x] 检查 safe 脚本中旧 all 停止与新 all 启动的先后顺序。
- [x] 确认 `src/test` 不存在，`pom.xml` 无测试依赖，生产源码无测试专用入口。

## 5. 测试环境验收

部署前：

1. 若 ES 中存在开发期旧结构 `sano_sync_polling_checkpoint`，删除后调用初始化接口重新创建。
2. 确认测试环境只有一个 `all`。
3. 确认 `sano_wallet_coin_record` 的 `bootstrap-start-date` 是预期日期。
4. 确认 `(dt,id)` 或等价联合索引存在。

顺序验证：

1. 首次启动创建单表 Checkpoint。
2. Worker 从配置日期和 ID 0 开始读取。
3. MySQL 空批次按 5 秒继续轮询。
4. ES 正常 Bulk 后继续下一批，Checkpoint 不随每批更新。
5. 模拟 Bulk 失败，确认完整重试 2 次、告警并继续。
6. 发起 drain，确认已读批次写完、最终内存游标保存、Worker 退出。
7. 取消 drain，确认从保存点恢复。
8. 人工暂停和恢复单表。
9. 验证日期关闭延迟、D+1 索引创建和 Checkpoint 推进。
10. 验证对账和历史删除失败不影响 D+1。
11. 人工调用对账接口。
12. 对差异日期创建 T+1 修复任务。
13. 执行 safe 部署，确认 query 接管期间没有第二个 all。
14. 模拟新版本失败，确认旧 all 从 Checkpoint 恢复。

## 6. 完成定义

版本 B 进入测试前必须满足：

1. 源码、配置、Mapping 和部署脚本不再包含 ES 租约实现。
2. Checkpoint 仅承担单表业务恢复。
3. 当前部署明确且实际保证只有一个 all。
4. T+1 与 Polling 可以按不同表同时运行。
5. Bulk 重试耗尽、跨日、对账、修复和历史删除语义符合当前设计。
6. drain、cancel、升级和回滚能从正确 Checkpoint 继续。
7. 构建和 Spring 启动验证通过。
8. 仓库不保留测试源码、测试依赖和测试专用业务代码。
