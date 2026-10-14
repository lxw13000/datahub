# es-server 缺陷清单与解决思路

> 审计日期：2026-08-05。范围：`es-server` 同步引擎（T+1 / Polling）、共享资源、部署脚本与 Compose/Nginx。
> 本文只做分析与方案建议，不含任何代码改动。
> 结论前置：**未发现 MySQL 连接泄露或资源未关闭**（全部走 `JdbcTemplate`，查询结束即归还连接）。下列均为并发/超时交互、已接受的设计取舍与运维锐边。
>
> 修订说明（第 2 版）：经复核，撤回原 #1 的“方案C 超时返回”与 #5 的“CallerRunsPolicy”两条错误建议；将 #2 从“ES Bulk 单点超时”扩展为“ES 建连 / ES 读 / MySQL 查询 三处悬挂 + 成套超时治理”；#1 定级由“锁竞争”上调为“悬挂态实现缺陷”；#3 归类为“已接受的数据完整性风险”。

## 严重度总览

| # | 严重度 | 主题 | 核心位置 |
|---|---|---|---|
| 1 | 🟠 中 | `pauseTable` 持锁忙等 + 失败路径不回滚 `blockedTables`，可留下悬挂态 | `PollingCoordinator.java:451` |
| 2 | 🟠 中 | ES 建连(500s)/ES 读(600s)/MySQL 查询(无超时) 三处悬挂，drain 边界脆弱 | `application-*.yml` + `GlobalEsWritePermitManager.java:91` + `deploy-es-server.sh:23` |
| 3 | 🟠 已接受风险 | Polling bulk 重试耗尽后跳过整批（数据缺口，业务策略） | `PollingBulkWriter.java:147` / `PollingTableWorker.java:191` |
| 4 | 🟡 低 | MySQL 连接池(5)扩展性上限（保守瞬时上限，非持续占用） | `application-*.yml` hikari |
| 5 | 🟡 低 | `esReconcileExecutor` 默认 AbortPolicy，失败通知可能被拒丢失 | `AsyncConfig.java:46` |
| 6 | 🟡 低 | 部署锁无 PID 存活检测，`kill -9` 后需人工清理 | `deploy-es-server.sh:154` |
| 7 | 🔵 取舍 | query 容器 `restart: unless-stopped` 接管中途重启的残留 | `docker-compose.yml:55` |
| 8 | 🔵 信息 | `INITIALIZATION_FAILED` 无自动重试/人工接口，运维仍需重启 | `PollingCoordinator.java:183` |
| 9 | 🔵 信息 | T+1 无“启动即恢复”，且 RUNNING 任务须超 `max-run-minutes` 才被回收 | `TPlusOneImportTask.java:397` |
| 10 | 🔵 信息 | 当日追平后空轮询退避至 300s，新数据可见延迟最高约 5 分钟 | `PollingTableWorker.java:39` |

---

## 1. 🟠 `pauseTable` 持锁忙等 + 失败路径留下悬挂态

**位置**：`PollingCoordinator.java:451-472`。

**两个叠加问题**：
1. **持锁忙等**：`pauseTable` 是 `synchronized`，内部 `while(...) Thread.sleep(20)` 最长等 `drain-timeout`=600s，期间持有协调器监视器，阻塞 `requestDrain()`/`drainStatus()`/`resumeTable()`/`stop()`；又因 `SyncDrainCoordinator.status()/startDrain()` 会调用 `pollingCoordinator.drainStatus()`，可导致 `/internal/sync/status`、`/internal/sync/drain/status` 与并发部署 drain 被阻塞最长 10 分钟。
2. **失败路径不回滚 `blockedTables`（真实实现缺陷，与 600s 无关）**：`:452` 先 `blockedTables.add(tableName)`，但超时或 `checkpointSaved==false` 的 `return false` 路径**从不移除该表、也不补写 PAUSED**。结果留下 `checkpoint=RUNNING + blockedTables 含该表 + 本机无 Worker` 的**悬挂态**：协调器扫描时因 `blockedTables` 跳过该表，checkpoint 又是 RUNNING，只有人工 `resume`（它才 `blockedTables.remove`）能解开。该缺陷在当前版本即存在。

**影响**：单表人工暂停失败时该表在本实例静默停摆，且暂停操作会短时冻结状态/部署接口。

**解决思路（撤回原方案C）**：
- 把“等待 Worker 退出”移出 `synchronized` 临界区：锁内只做 `blockedTables.add + requestStop`，**释放锁**后用 `CountDownLatch`/`Future`（由 Worker 退出时 `countDown`）在锁外 `await(timeout)`，再取锁做 `pauseManually` 持久化。
- **失败/超时必须回滚**：任一失败分支都要 `blockedTables.remove(tableName)`，避免悬挂态。
- **处理并发 resume 竞态**：`resume` 会 `remove(blockedTables)+启动扫描`；暂停与恢复对 `blockedTables` 和 checkpoint 状态的读改写需在同一原子边界内，或以 checkpoint 的 CAS（脚本 `status==RUNNING` 判定）为唯一真相，避免 remove 丢失或重复启动。

---

## 2. 🟠 三处悬挂源 + 成套超时治理，drain 边界脆弱

**背景**：drain 的收敛依赖“Worker/Reader 到达安全边界”，而边界前的阻塞操作若能挂很久，drain 就会耗到超时判 `FAILED`。当前有**三处**可长时间悬挂，且 `GlobalEsWritePermitManager.acquire()`（`:91-126`）的等待**不响应 stop/drain，只响应线程中断**，会放大占用。

**位置与超时现状**：
- **ES 读超时** `sano.es.socket-timeout: 600000`（10 min）——单次 ES 往返（含 Bulk）最长挂 10 分钟，期间占着全局 Bulk 许可。
- **ES 建连超时** `sano.es.connection-timeout: 500000`（500s，`application-prod.yml:58`）——ES 主机不可达时单次建连可挂 500s。该值本身不合理（健康建连毫秒级）。
- **MySQL 查询无超时** `application-prod.yml:15`——`JdbcTemplate` 未设 `setQueryTimeout`，JDBC URL 也无 `socketTimeout`/`connectTimeout`。慢查询/锁等待会让 Worker 卡在 `QUERYING` 阶段**无限期**，drain 只能等到 `drain-timeout` 判 FAILED。**MySQL 侧风险不比 ES 小。**
- **后端 drain 超时** `common.drain-timeout-seconds: 600`；**脚本 drain 超时** `deploy-es-server.sh:23` `DRAIN_TIMEOUT=600`。三者相等，边界无余量。

**注意**：ES Bulk API 自带的 `timeout` 是**服务端软超时，不等于 HTTP 硬超时**；真正的硬边界仍是 RestClient 的 socket/connect timeout（或客户端 Future 硬超时）。因此“给 Bulk 加请求级 timeout”不足以兜底。

**解决思路（成套，非只调一个值）**：
- **ES connection-timeout**：降到个位数秒（如 3–5s）。建连不该等 500s。
- **ES socket-timeout**：按最重的**单次** ES 往返 p99 定；本系统 Bulk “慢”阈值仅 3s、无 scroll/PIT、最重读是 composite 分页（每页独立且秒级），可降到 120–180s。降值只影响“单请求本会跑 120–600s”的场景，健康时零影响，异常时更快释放许可。
- **MySQL**：给同步/对账查询设 `queryTimeout`（如 30–60s），并在 JDBC URL 增加 `connectTimeout`/`socketTimeout`，杜绝无限期挂起。
- **超时分层**：`脚本 DRAIN_TIMEOUT > 后端 drain-timeout > 各底层 IO 超时`，例如底层 IO ≤120s、drain-timeout=300s、脚本 DRAIN_TIMEOUT=420s。
- **可选增强**：让 `acquire()` 在 drain 期间可中断/带超时，卡住的 bulk 不再拖垮 drain 窗口（此法可保留读侧较宽超时，读一字不改）。

---

## 3. 🟠 Polling bulk 重试耗尽后跳过整批（已接受的数据完整性风险）

**位置**：`PollingBulkWriter.java:147-160`（重试耗尽 `return false`）；`PollingTableWorker.java:191`（无论成败都 `lastId = batch.lastId()` 推进游标）。

**性质**：这是文档 §6.3 明示的**业务策略**，应归类为“**已接受的数据完整性风险**”，非普通实现 bug。整批（默认 3000 行）写失败且重试耗尽后，游标越过该区间、数据被跳过。

**现有兜底（已具备）**：ERROR 日志、异步 Lark 通知、`PollingLogSummary` 失败汇总 WARN、跨天统计对账（count/min/max）、人工 T+1 全量修复。同日内崩溃会从持久 checkpoint 重读、有机会重试。

**唯一实打实缺口**：**状态接口未累计失败批次**——运维无法从 `/internal/sync/status` 直接看到某表累计跳过了多少批/多少行。

**解决思路**：
- 在 Worker/`PollingLogSummary` 累计 `bulkFailedBatches`/失败行数，并在 `/internal/sync/status` 暴露，使跳批可观测、可告警。
- （可选）跳批后落一条待修复记录（复用 `sano_import_task`），触发自动 T+1 区间修复，替代纯人工。
- （可选）增设“连续 N 批失败则暂停整表”阈值，避免 ES 长时间异常时静默丢大量数据。

---

## 4. 🟡 MySQL 连接池扩展性上限（保守瞬时上限）

**位置**：`application-{dev,test,prod}.yml` `hikari.maximum-pool-size: 5`。

**说明**：并发连接需求 ≈ 活动 Polling 表数（各 1，仅查询期间借用）+ 1（T+1 reader）+ 并发 reconcile。这是**保守瞬时上限，非持续占用**：Polling 只在 SQL 执行期间持连接；`esReconcileExecutor` core1/max5/queue20 的语义是**队列满(20)才扩容到 5**，故对账常态近似单线程串行，连接争用远低于上限公式。**当前部署安全**（测试 1 张 polling 表；正式 Polling 关闭）。

**触发条件**：Polling 表增长到 ~5 且叠加 T+1 + 对账积压时，瞬时需求可能超 5 → `connection-timeout(30s)` → 表暂停/对账失败。文档 §12.2 已提示。

**解决思路**：Polling 表数上调前同步上调 `maximum-pool-size`（经验值 ≥ `max-active-tables + 1 + 对账并发`）；监控 Hikari `pending`/`timeout` 指标。

---

## 5. 🟡 `esReconcileExecutor` 默认 AbortPolicy

**位置**：`AsyncConfig.java:46-57`（core1/max5/queue20，未设拒绝策略 → 默认 AbortPolicy）。承载对账 + 历史删除 + bulk 失败通知。

**影响**：在途超过 25 个任务时抛 `TaskRejectedException`。自动提交点均已 `try/catch` 仅告警（`PollingTableWorker.java:267-280`、`PollingBulkWriter.java:141`）；人工 `/import/reconcile` 被拒时直接返回接口错误——**均非静默丢失**。真正的弱点是：数据已出问题时，**失败通知本身可能被拒丢失**，削弱 #3 的可观测兜底。

**解决思路（撤回原 CallerRunsPolicy）**：
- **不要改 CallerRunsPolicy**：reconcile 由 `PollingTableWorker:275` 提交，CallerRuns 会把数秒级对账**同步跑在 Polling Worker 线程**里，堵住主同步循环，违背“对账/删除/通知不得影响主流程”。
- 允许丢失的对账/历史删除：**保留有界队列 + 拒绝日志**即可。
- 若失败通知需更可靠：给**通知单独一个轻量 executor**（小 core、独立队列），与对账/删除隔离，避免被挤占。

---

## 6. 🟡 部署锁无 PID 存活检测

**位置**：`deploy-es-server.sh:154-160`（仅 `mkdir` 锁目录 + 写 pid，不校验 pid 存活）。属低风险运维锐边。

**影响**：上次部署被 `kill -9` 后锁目录残留，后续部署持续 `fail "已有部署正在执行"`，需按文档 §16.6 手工清理。

**解决思路**：加锁时读 `${DEPLOY_LOCK_DIR}/pid`，`kill -0 <pid>` 判活；进程不存在则视为陈旧锁自动清理后重试一次，仍存活才拒绝。

---

## 7. 🔵 query 容器 `restart: unless-stopped`（取舍，非缺陷）

**位置**：`docker-compose.yml:55`。

**说明**：接管期间 query 容器**就是**唯一在服务的实例（主实例正被替换），`unless-stopped` 能在宿主重启后自愈查询能力。改 `restart: "no"` 反而更糟——接管中途重启会导致**无查询实例**。

**解决思路（保留自愈）**：维持 `unless-stopped`，改由**下一次部署检测并清理残留 query 容器**（正常流程 `stop_query_handoff` 已清理，仅补“接管中途宿主重启”这一少见路径）。

---

## 8–10. 🔵 信息级

- **#8 `INITIALIZATION_FAILED`**（`PollingCoordinator.java:183-189`）：非代码级绝对终态（`start()` 可再次从该状态进入），但当前只有 `ApplicationReadyEvent` 会调用 `start()`，无重试调度、无人工重启接口，**运维流程上仍需重启容器**。如需免重启，可加低频后台任务在该状态下周期性重探索引并自恢复，或提供人工 `start` 接口。
- **#9 T+1 无启动即恢复 + 回收门槛**：遗留 RUNNING 任务只有 `updatedAt < now - max-run-minutes` 才被 `repairExpiredRunningTasks`（`:397`）改为 TIMEOUT_PARTIAL。正式 `max-run-minutes=480`，因此**宕机后即使立即人工触发，也不会接管一个尚未过期的 RUNNING 任务**，恢复延迟可长达 8 小时。如需更快恢复，可加 `ApplicationReadyEvent` 触发“仅续跑既有 TIMEOUT_PARTIAL/过期 RUNNING、不新建当日任务”的扫描，或缩短过期判定。幂等文档 ID 保证正确性。
- **#10 当日 freshness ~5 分钟**（`PollingTableWorker.java:39` 退避 5/10/30/60/300s）：当日追平后空轮询退避至 300s，新数据可见延迟最高约 5 分钟。若需更低延迟，降低退避上限（如封顶 30–60s），代价是空闲时 MySQL 轮询更频繁。

---

## 优先处理建议

1. **#2 成套超时治理（收益最高）**：ES connection-timeout 降到秒级、ES socket-timeout 降到 120–180s、MySQL 加 `queryTimeout` + URL `socketTimeout/connectTimeout`、超时分层（IO < drain-timeout < 脚本 DRAIN_TIMEOUT）。若担心读侧，用“可中断 `acquire()`”方案解耦，读不动。
2. **#1 锁外等待 + 回滚 blockedTables + resume 竞态**：消除悬挂态与状态/部署接口冻结。
3. **#3 状态接口暴露累计失败批次 + #5 通知独立轻量 executor**：把“跳批 → 高可见告警”的最后防线做实。
4. #4、#6 属运维/扩展项，按 Polling 表规模与发布频率择机处理；#7、#8、#9、#10 为取舍/信息项，按需优化。
