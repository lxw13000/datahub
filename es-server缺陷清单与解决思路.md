# es-server 缺陷清单与解决思路

> 复核日期：2026-10-14。
> 基线：当前工作区源码、四套环境配置、正式/测试部署脚本、Compose、Nginx 示例；包含新增钻石冻结记录表。
> 当前同步能力只有 T+1。Polling 已移除，不再沿用旧文档中的 Polling 缺陷及并发容量公式。
> 本次更新文档，不修改业务源码、YAML 或部署脚本。

## 1. 复核结论与适用边界

正常路径具备：单实例串行任务分发、Reader/Bulk 流水线、有序安全断点、稳定文档 ID 幂等覆盖、队列背压、Bulk 许可和 T+1 估算内存预算。query 角色在同步入口拒绝执行；正常 safe 升级先启动旧镜像 query，再排空旧主，严格验证新主后清理 query。

仍存在异常路径的实现问题，重点是“回滚时新主的安全停止”“重试与统计口径”。D01的任务终态持久化异常边界经业务确认，作为已接受的低频风险保留，不修改实现。不能把编译成功、/health 存活或协调器 RUNNING 等同于完整同步恢复。

静态检查未发现同步 Reader 手动借用连接后遗漏归还的路径：MySQL 通过 JdbcTemplate 执行，SQL 正常返回或抛错后由 Spring 释放资源。但慢 SQL/网络阻塞期间会持续占用连接；这属于长时间占用，不能据此宣称连接池一定不会耗尽。未进行生产故障注入或长期运行压测。

下文分类：

- P1：安全排空/恢复协议缺口，优先修复。
- P2：任务结果、可恢复性或异常处理问题。
- P3：部署校验与运维问题。
- “已接受风险”：业务已确认由现有日志、通知和人工复核处理，不列入必须修复项。
- “运行约束”：已知设计边界，不作为当前已发生的故障。

## 2. 旧清单逐项结算

| 旧编号 | 原问题 | 当前结论 |
|---|---|---|
| 1 | Polling pauseTable 持锁忙等、blockedTables 悬挂 | Polling 已删除，不再适用 |
| 2 | ES/MySQL IO 等待时间过长、drain 边界不足 | 仍存在，见 D06；新增持锁远程 IO 问题见 D07 |
| 3 | Polling Bulk 耗尽后跳批及失败统计 | Polling 已删除，不再适用 |
| 4 | 5 个 MySQL 连接与多张 Polling 表竞争 | 原公式失效；T+1、异步对账、查询接口仍共享连接池，见运行约束 |
| 5 | 对账线程池拒绝任务导致通知丢失 | 线程池仍有拒绝边界，但当前 T+1 终态通知不经过该执行器；原“Polling 失败通知挤占”结论撤回 |
| 6 | 部署目录锁没有 PID 存活检测 | 仍存在，见 D11 |
| 7 | query 使用 unless-stopped、失败回滚后可能残留 | 保留为运维取舍；正常成功路径会停止并删除 query |
| 8 | Polling INITIALIZATION_FAILED 后需重启 | Polling 已删除，不再适用 |
| 9 | T+1 未启动即恢复、RUNNING 回收需过期 | 仍存在，补充正常升级不立即续跑和停机漏日期，见运行约束 |
| 10 | Polling 空轮询退避最长 300 秒 | Polling 已删除，不再适用 |

此前用户要求修复的三项也重新核对：

- 内存扩容等待：reserve/resize 现在每秒检查 Bulk 中止标记，超预算单批直接报错；原“Bulk 已失败但 Reader 永久等内存”路径已修复。底层 IO 不返回时仍可能长时间等待，属于 D06。
- 导入前优化部分成功：optimized 标记已提前到 beforeImport 之前，异常路径会尝试恢复；原“标记未设置而完全不恢复”问题已修复。恢复失败、恢复返回未确认仍需看 D09。
- 安全断点已到日末：startId > 0 且本次 read=0 时跳过“本次全部失败”判断，恢复参数并绑定 Alias；原误判问题已修复。

## 3. 当前问题总览

| 编号 | 等级 | 当前问题 |
|---|---|---|
| D01 | 已接受低频风险，不修改 | 任务终态持久化异常通过现有日志、通知和人工复核处理；保留后续drain未追溯该异常的边界 |
| D02 | P1 | 升级失败回滚直接强删新主，新主可能已经开始同步 |
| D03 | P2 | 历史删除的 exists 检查位于异常隔离范围外，反向导致任务失败 |
| D04 | P2 | Bulk 请求重试只捕获 IOException，部分 ES 请求级错误直接中止 |
| D05 | 已确认累计处理口径 | success_count/failed_count包含续跑重放；已明确注释和通知，不实现唯一文档去重统计 |
| D06 | P2 | IO、已读取队列排空与重试没有共同截止边界 |
| D07 | P2 | 新增任务时持有协调器锁执行 ES 远程请求，状态和 drain 接口可阻塞 |
| D08 | P2 | cancel 精确续跑先移除任务 ID，查询异常时恢复请求会丢失 |
| D09 | P2 | Settings/Refresh 的部分失败没有完整校验 |
| D10 | P2 | 对账未验证 ES 查询完整性，部分结果可能被当作有效统计 |
| D11 | P3 | 部署锁残留后无法自动识别和恢复 |
| D12 | P3 | 回滚 /health 请求无时间限制，且恢复验证只检查存活 |
| D13 | P3 | Bulk 失败 ID 专项日志也被前 10 条截断 |

以下源码行号以本次复核时为准，后续代码修改后应以方法名定位。

## 4. D01：任务持久化异常（已接受低频风险，不修改实现）

**依据**：[SyncDrainCoordinator.finishTPlusOneTask](es-server/src/main/java/com/tsd/sano/es/modules/coordination/service/SyncDrainCoordinator.java:201)、[evaluateDrainLocked](es-server/src/main/java/com/tsd/sano/es/modules/coordination/service/SyncDrainCoordinator.java:296)、[TPlusOneImportTask.executeTask](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/task/TPlusOneImportTask.java:477)。

任务终态保存失败时，finishTPlusOneTask 仅在已有 operation 且结果为 IN_PROGRESS 时标记 drain FAILED。没有正在执行 drain 时，持久化失败只进入 lastPersistentTask 摘要，随后活动任务被注销。下一次 startDrain 创建新 operation 后，仅检查调度器、活动任务、许可和 Reservation 是否为空，不检查先前未解决的持久化问题。

触发链：

```text
TIMEOUT_PARTIAL/SUCCESS/FAILED 更新失败或响应不确定
→ 当前没有进行中的 drain
→ 注销活动任务
→ 后续任务可能覆盖 lastPersistentTask 摘要
→ 发起新 drain
→ 无活动线程、资源归还
→ 返回 DRAINED
```

此时 ES 文档可能仍为 RUNNING，或已提交但客户端未确认；不能证明本次安全断点已可靠保存。下一次启动可能延迟恢复或从旧断点重放，不等于必然丢数据。该技术边界仍存在，但经业务确认，不为此增加额外状态管理和恢复机制。

**业务决策（2026-10-14确认）**：当前单all、T+1串行运行，用户反馈已运行几个月，未发现该类错误。按低频风险接受，保留现有异常处理和人工复核方式；单任务异常不得阻止其他任务继续执行。

**保留现有处理**：

- 导入执行异常时，尽力将原任务文档更新为FAILED并记录原因，打印ERROR日志、发送Lark通知，然后继续其他任务。
- SUCCESS/TIMEOUT_PARTIAL更新结果不确定时，现有代码不再以FAILED覆盖可能已经提交的终态；记录错误并通知人工复核，继续其他任务。
- 如果连FAILED也保存失败，任务可能残留RUNNING；通过错误日志和通知检查实际任务状态、物理索引及数据完整性，必要时人工补指定日期的数据。
- 不向sano_import_task新增异常文档或特别状态；不增加内存待确认集合、额外执行门禁、后台恢复线程或针对D01的新drain判据。

人工补数时仍需遵守当前接口限制：已有FAILED/SUCCESS任务不能直接重复提交重跑，需先按人工流程处理原任务，见§17.4。本项只确认接受风险，不意味着已经增加受控重跑接口。

**结算**：D01不修改实现，不列入修复优先级。原“保存待确认任务、阻断分发、确认后才允许drain”的建议撤回，不作为开发任务。

## 5. D02：回滚没有先排空可能正在同步的新主

**依据**：[正式回滚函数](es-server/deploy-es-server.sh:534)、[测试回滚函数](es-server/deploy-es-server-test.sh:532)、[T+1 定时入口](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/task/TPlusOneImportTask.java:108)。

正常升级对旧主执行 drain。新主启动后立即是 all，允许定时器和人工接口提交同步。若新主观察阶段恰遇 02:30、人工请求，或验证/清理 query 阶段失败，退出保护回滚时会直接 docker rm -f 新主，没有对其执行 drain。

后果：新主的 Reader/Bulk 可被强制中断，任务残留 RUNNING、内存进度丢失；旧镜像即使恢复也未必立即续跑。固定文档 ID 能减少重放重复，但不能代替安全停机。

**解决思路**：

- 新主已经运行且具备 drain 协议时，回滚前尝试按同一协议排空新主。
- 无法安全排空时维持 query 查询接管，明确报告需人工处理，不能自动假定强删安全。
- 真正未启动或崩溃的新主可按异常恢复流程处理，区别于“活着且正在执行同步”的实例。
- 新主停稳后再恢复旧 all，继续满足同一环境只有一个 all 的约定。

## 6. D03：历史清理失败会污染同步任务终态

**依据**：[TPlusOneIndexService.deleteHistoryIndex](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/service/TPlusOneIndexService.java:130)、[导入完成路径](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/pipeline/TPlusOneImportService.java:207)。

deleteIndex 位于 try/catch 中，但此前的 indexManager.exists(expiredIndex) 位于 try 外。ES 存在性查询抛错时会穿透导入主流程。

触发链：

```text
当前日数据写完
→ 恢复参数并刷新
→ Alias 已绑定
→ 历史索引存在性检查异常
→ importData 抛错
→ 任务保存 FAILED
```

无数据任务也调用该方法，存在同样问题。于是“已可查询、导入已完成”与 FAILED 状态矛盾，且 FAILED 不会自动续跑。

**解决思路**：把远程存在性检查和删除纳入同一个最佳努力异常隔离范围。保留日志，不能让历史清理改变同步成功/无数据结果。无需增加每日扫描服务。

## 7. D04：Bulk 重试异常覆盖不足

**依据**：[TPlusOneBulkWriter.sendWithRetry](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/pipeline/TPlusOneBulkWriter.java:253)。

当前仅 catch IOException。Java Client 对服务端拒绝或无法处理请求还可能抛 ElasticsearchException，直接进入 Worker 异常路径，retry-times 不会生效。例如请求级临时拒绝/服务不可用与永久参数错误没有分类。

Bulk HTTP 请求正常返回、内部 item 为429等暂时失败时，当前 handleResponse 也只计失败，不进行 item 重试。这是另一条路径，不应与请求级异常混为一谈；超过容差时任务失败，容差内允许部分数据缺失属于现有策略。

**解决思路**：

- 对 ElasticsearchException 根据状态和原因区分可重试的临时错误与不可重试的权限、Mapping/参数错误。
- 保持已经构建的 BulkRequest 复用和固定文档 ID。
- 明确是否增加 item 级临时错误重试；若继续保留现有策略，文档写明 retry-times 仅针对可重试请求级错误。
- 验证 IOException、请求级临时错误、永久错误、item 临时失败四种情况。

异常类型依据：[Elastic Java Client 官方异常说明](https://www.elastic.co/docs/reference/elasticsearch/clients/java/api-conventions/exception-conventions)。

## 8. D05：续跑重放计数（已确认采用累计处理口径）

**依据**：[ImportContext.completeBatch](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/model/ImportContext.java:276)、[TIMEOUT_PARTIAL 统计累加](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/task/TPlusOneImportTask.java:446)、[SUCCESS 统计累加](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/task/TPlusOneImportTask.java:461)。

早期批次有 item 失败时安全断点被阻塞，后续批次仍可写成功。若此后 deadline/drain 产生 TIMEOUT_PARTIAL，任务保存全部成功/失败操作数，但只保存失败批次之前的安全 ID。续跑重新读取未进入安全断点的后续记录，并再次累加计数。

示例：当天100条，首次成功99、失败1，安全断点仍为0，随后被排空暂停。第二次重读100条全部成功；ES实际100条，任务可能记录 success_count=199、failed_count=1。之前已修复的错误仍出现在最终失败累计数中。

**影响**：任务文档和通知数量不再代表最终唯一文档数量。这里是重复统计，不是 ES 生成重复文档；同 ID 覆盖仍幂等。

**2026-10-16业务结论及调整**：保留当前计数方式，明确为已保存的累计处理成功/失败数量，包含续跑重放，不作为最终唯一文档数。后续重放成功不扣减历史失败。该边界不再列为需要新增去重机制的缺陷。

- 已补充任务实体、单轮统计及累加位置注释，区分本轮计数、累计处理数和独立安全断点。
- 通知改为“累计处理成功/失败”，提示真实完整性以对账为准；失败通知的本次计数与已保存累计数分开展示。
- 移除暂停通知通过源总量减累计处理数计算的“剩余”数量，避免重放造成错误提示。
- 不修改字段名、Mapping、计数及失败容差逻辑，不新增去重集合或ES计数请求。HTTP请求重试本身不是每尝试一次就计数。

## 9. D06：drain 有等待上限，但底层操作及排队数据没有共同时间边界

**依据**：[ES 客户端超时配置](es-server/src/main/java/com/tsd/sano/es/core/config/ElasticClientConfig.java:104)、[Reader SQL 与内存等待](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/pipeline/TPlusOneJdbcReader.java:170)、[许可等待](es-server/src/main/java/com/tsd/sano/es/modules/coordination/service/GlobalEsWritePermitManager.java:76)。

当前正式/测试配置：

| 项目 | 正式 | 测试 |
|---|---:|---:|
| ES connect timeout | 500秒 | 500秒 |
| ES socket timeout | 600秒 | 600秒 |
| 服务端 drain timeout | 600秒 | 600秒 |
| 部署脚本 DRAIN_TIMEOUT | 600秒 | 600秒 |
| 每轮 T+1 运行窗口 | 480分钟 | 5分钟 |
| Reader 批次/队列容量 | 15000 / 16 | 5000 / 10 |

JdbcTemplate 未配置查询超时，JDBC URL 未配置 connectTimeout/socketTimeout。Hikari connection-timeout=30000 控制借连接等待，不能中止已取得连接后正在执行的 SQL。Connector/J 的连接/套接字超时属性默认0表示无超时，见[官方网络属性说明](https://dev.mysql.com/doc/connectors/en/connector-j-connp-props-networking.html)。

drain 只停止新查询，已经读取的数据继续写完，这是正确业务要求。因此，即便单次请求 timeout 小于 drain timeout，多个排队批次和重试累计仍可能超过排空窗口。当前内存/许可等待还依赖前面的 Bulk 请求最终返回。

**影响和边界**：

- drain 到期会报告 FAILED；正常升级脚本不会因此继续停止旧主，这层保护仍存在。
- 后台 SQL/Bulk 不会随着 drain FAILED 自动结束；取消 drain 后仍可能等待 IO。
- 当前没有配置的查询时间上限，不等于数据库/网络永远不会终止，但应用无法保证确定时限。

**解决思路**：

- 根据实际大表查询和 Bulk 时长确定合理的建连、读取和SQL边界；SQL耗时先实测，不能直接统一收紧到固定30秒。
- 明确用户是否接受超时后任务失败/重放。此前“SQL 等待即可”的要求属于需再次确认的取舍。
- drain 窗口需覆盖已有队列、许可等待、重试和终态持久化，不只比较一个 HTTP timeout。
- 脚本等待时间留出网络、轮询及终态确认余量。
- 不建议简单拒绝 drain 期间所有 Bulk 许可：这会阻止已读数据排空，改变已确认业务。
- 未得到业务确认前，仅记录风险，不在本次修改超时配置。

## 10. D07：协调器锁内执行任务索引远程写入

**依据**：[callIfAcceptingNewWork](es-server/src/main/java/com/tsd/sano/es/modules/coordination/service/SyncDrainCoordinator.java:153)、[任务 create + WaitFor](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/service/SanoImportTaskService.java:111)。

callIfAcceptingNewWork 是 synchronized，调用 action.get() 时没有释放锁；action 实际执行 ES client.index，包含 refresh=wait_for 和网络等待。

于是一次任务创建卡住会阻塞 startDrain、status、cancelDrain、任务完成回调。部署 HTTP 请求最长30秒，而此处可能等待更久，导致部署只能中止并执行退出保护。这是当前 T+1 的持锁 IO 问题，与已移除的 Polling pauseTable 不是同一个缺陷。

**解决思路**：锁内原子登记“任务创建正在进行”，锁外发起 ES 请求，finally 锁内归还计数；drain 禁止后续创建，并等待已登记创建结束。必须把在途任务创建纳入 drain 判据，不能仅把 synchronized 删除。

## 11. D08：cancel 自动续跑查询异常会丢失精确恢复任务 ID

**依据**：[resumeAfterDrainCancel](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/task/TPlusOneImportTask.java:535)。

remainingTaskIds.removeFirst() 先移除任务 ID，随后 getTask 读取 ES。若 getTask 抛错，当前 ID 已不在 remainingTaskIds，finally 只归还剩余 ID，当前任务不会再次进入本次 cancel 的精确恢复集合。

持久任务没有删除，下一次普通队列扫描仍有机会执行；受损的是 cancel 后立即恢复的承诺。若 ES 持续失败且还有多个待恢复任务，finally 的再次提交也可能造成短时间连续失败提交。

**解决思路**：

- 先查看队首，确认任务读取和执行结果后才移除。
- 查询失败保留当前任务 ID 并记录失败，不在异常 finally 中立即无限重新提交；通过有间隔的有限重试或下次明确恢复调用继续处理。
- 保持 dispatcher 令牌在所有提交失败路径都正确归还。
- 验证首个任务读取失败、执行被新 drain 拒绝和多任务部分完成。

## 12. D09：索引参数恢复与刷新缺少完整确认

**依据**：[Settings 更新](es-server/src/main/java/com/tsd/sano/es/modules/index/EsIndexManager.java:126)、[Refresh](es-server/src/main/java/com/tsd/sano/es/modules/index/EsIndexManager.java:167)、[afterImport](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/service/TPlusOneIndexService.java:84)。

create/bind/delete 会检查 acknowledged，而 updateRefreshInterval/updateReplicaCount 只打印 acknowledged。refresh 只打印 successful shards，没有检查 failed shards。服务端返回部分失败时，上层仍可能继续绑定 Alias、保存 SUCCESS。

afterImport 的多个操作顺序执行；第一个抛错则后续恢复/refresh 不执行。异常路径虽会再尝试 afterImport，但仍没有隔离单项恢复失败。

**解决思路**：

- 明确 Settings 未确认是否需读取实际设置核实；无法确认时不能打印已可靠恢复。
- 刷新出现分片失败时按不完整处理，记录具体失败原因。
- 清理阶段分别尽力恢复 refresh/replicas，汇总错误，避免第一项失败阻止其他恢复。
- 当前1分片、0副本场景降低了部分失败的触发面，不代表返回值可永久忽略。
- 恢复为固定1s/0副本符合当前单机部署，暂不把“未还原原始自定义值”列为必须修复项。

## 13. D10：对账把未完整 ES 查询当成有效统计

**依据**：[ES minimum/maximum 查询](es-server/src/main/java/com/tsd/sano/es/modules/reconcile/service/ReconcileStatisticsService.java:164)。

两次查询未检查 timedOut、failed shards，total 为 null 时还用当前命中条数代替总量。请求返回但结果不完整时，可能发出错误的 MISMATCH；极端情况下部分结果恰好相符，也可能报告 MATCH。

**解决思路**：统计使用前验证查询未超时、无失败分片、total 存在且为精确总数；不可用时统一报告 FAILED，不能用 size=1 命中数量补总数。保留两次排序取精确 long ID 的方式，不改成容易损失大ID精度的 double min/max 聚合。

MySQL count=0 直接发送 MYSQL_EMPTY、不查询ES，是已确认需求，继续保留。这种结果仅说明源端为空，不证明ES也为空。

## 14. D11：部署锁未识别陈旧持有者

**依据**：[正式加锁](es-server/deploy-es-server.sh:152)、[测试加锁](es-server/deploy-es-server-test.sh:150)。

只用 mkdir 和 pid 文件，kill -9 或宿主异常后不会执行 EXIT 清理，目录残留会永久拒绝后续部署。

**解决思路**：优先考虑宿主 flock 进程级锁；若保留目录锁，检查持有者进程和部署身份，避免仅凭可复用PID删除活跃锁。识别陈旧锁后原子重新争抢，不能无条件删锁。正常重复部署已正确拒绝并发。

## 15. D12：回滚恢复验证缺少请求时间边界和严格就绪

**依据**：[正式回滚健康检查](es-server/deploy-es-server.sh:555)、[测试回滚健康检查](es-server/deploy-es-server-test.sh:553)。

回滚循环内 curl -fsS /health 没有 connect-timeout/max-time，外层 START_TIMEOUT 只有在 curl 返回后才生效。端口接受连接却不返回响应时，回滚退出保护本身可能长时间不退出。

此外，只检查 /health 就打印“旧镜像主实例已恢复”，没有验证 /ready、角色和真实Nginx入口。query 被保留是正确兜底，但不能证明旧主查询/同步已恢复。

**解决思路**：

- 为回滚每个HTTP请求增加独立时间边界。
- 旧镜像具备新协议时检查 /ready、ALL角色和协调器状态，再确认Nginx入口。
- legacy 旧镜像仅支持 /health 时明确报告“进程已启动，业务待人工确认”。
- 回滚确认前保留 query。
- ensure_stable_container 当前只比较 RestartCount，未在观察结束重新确认 running 和 /ready；建议补充结束检查，避免稳定观察期间停机仍沿用旧就绪结果。

## 16. D13：失败 ID 专项日志不完整

**依据**：[Bulk item 错误遍历](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/pipeline/TPlusOneBulkWriter.java:305)。

同一个循环同时打印专项失败 ID 和普通 WARN；达到 MAX_ERROR_LOG_COUNT=10 后 break。一个 Bulk 若有50个失败，计数是50，但专项文件只留下前10个 ID，无法根据该文件获取完整修复清单。

**解决思路**：遍历全部失败 item，专项失败ID按实际需要完整输出；仅限制普通 WARN 的明细数量。若专项也有意限制，明确标记已截断，并提供完整失败数据的替代路径。这里只涉及日志，不改变容差和断点逻辑。

## 17. 运行约束与设计取舍

### 17.1 正常升级不会立即恢复此前 TIMEOUT_PARTIAL

[调度入口](es-server/src/main/java/com/tsd/sano/es/modules/tplusone/task/TPlusOneImportTask.java:108)没有 ApplicationReadyEvent 启动扫描。新主 /ready 和 coordinatorState=RUNNING 只是能力/门禁就绪，没有自动执行任务。

正常 safe 升级将旧主正在执行的任务持久化为 TIMEOUT_PARTIAL 后，新主可能等到下一次02:30或人工调用才续跑。cancel 后的精确重投只在取消旧主 drain 的路径发生，不跨进程。

若要求升级后立即续跑，需要新增“仅扫描已有待执行任务”的启动入口；不要通过新建今天任务来实现恢复，也不要以协调器RUNNING日志表示业务已经恢复。

### 17.2 RUNNING 残留回收与停机漏日期

repairExpiredRunningTasks 仅在调度/人工扫描时运行，并要求 updatedAt 早于当前时间减 max-run-minutes。正式480分钟，因此新近宕机残留任务即使人工扫描也可能不会立即回收；字段 updatedAt 缺失的记录也会被跳过。

程序停机错过某天02:30时，该天对应“昨天”的任务未创建；下一次调度只创建新“昨天”，不会自动补齐缺失日期。需要人工日期段补任务。

若希望自动恢复/补日期，另行确认最小恢复入口和任务日期范围。在当前单all约定下可设计启动确认恢复；若未来多all运行，不能仅凭“时间过期”判断其他实例已失活。

### 17.3 同一环境只允许一个 all

当前 dispatcher、运行索引集合和 drain 门禁都在单JVM内。任务 create 去重只避免重复建文档，不是分布式任务领取锁；两个 all 可以同时查询到同一 PENDING 并执行。当前全局Bulk许可、128MB估算预算也只是单实例范围。

这是当前已确认部署前提，不建议恢复之前被移除的租约。扩张多个all前必须重新设计任务领取与资源协调。

### 17.4 FAILED/SUCCESS 任务不支持直接重建重跑

SanoImportTaskService.addTask 使用 create 去重，队列仅读取 PENDING/TIMEOUT_PARTIAL。人工导入同表同日期不会覆盖 FAILED/SUCCESS。当前接口能补“尚未生成任务的日期”，不能通用地修复所有已失败/已成功但不完整的日期。

人工测试今天的数据时，完成后当天后来新增的记录不会自动补入。T+1读取不是数据库全日一致快照；可变源数据、迟到数据和ID非唯一会影响完整性，需要按已结束日期/可靠ID前提使用。

最小后续方向是受控“重置/重跑已有任务”入口，明确已有索引复用、Alias可见性、计数重置和并发门禁，避免要求运维直接手改ES状态。已有Alias的索引复用写入可能暂时可查询部分更新，不能套用“新索引绑定前不可见”的保证。

### 17.5 历史删除允许遗漏，不自动补扫描

每个任务仅尝试删除 importDate-reserveDays 的一个物理索引。某天任务未执行或清理失败，该过期索引不会被以后别的日期任务自动重新命中。历史补数还可能创建早于当前保留期的索引，而本次清理按历史 importDate 计算。

保留此轻量策略，积累的过期索引通过人工维护处理。不要擅自恢复用户已否定的全Alias每日扫描。

### 17.6 异步对账、通知和连接池

对账线程池core=1、max=5、queue=20，饱和可能拒绝。自动对账提交失败已有WARN且不会覆盖成功终态；人工请求被拒返回异常。对账允许丢失属于已确认需求，不需要持久队列或自动重试。

当前终态通知在T+1线程同步发送，Lark请求设置10秒超时，异常被NotifyService隔离。不会反向导致任务失败，但仍可延长调度器退出/drain等待。不要再沿用“通知都经过对账执行器”的旧描述。

MySQL连接池5个：T+1通常仅1个Reader查询；对账常态1个，队列满才向最多5线程扩展；部分分析API还会读MySQL。不能按5张配置表认为同时有5个Reader，也不能保证高并发对账/分析时绝不争用连接。

### 17.7 对账、容差和数据规模

少量item失败仍允许T+1绑定Alias，SUCCESS不等于failed_count=0。count/min/max对账不能发现“总数与端点一致但中间内容错误”，也不核验tokens等字段，符合当前统计对账范围。

冻结表使用create_time范围、id游标和(create_time,id)索引，日内按id排序不一定能完全利用该索引排序。用户已确认先运行观察，本次不引入复合游标或修改表结构。金币表仍显式FORCE INDEX(idx_polling_dt_id)，删除该MySQL索引会使读取失败；其名称包含polling不代表仍有Polling能力。

128MB预算按首条JSON体积加10%估算，不是实际JVM堆硬上限；Map对象、Bulk序列化缓冲、HTTP/查询和其他Bean均需额外内存。固定稳定字段流水较适合该估算，宽表需要观察真实堆与GC。

### 17.8 就绪、Nginx和首次部署

/ready只抽查第一张启用表Alias和任务索引存在性，不验证所有业务Alias、任务积压和源端连通性。新增冻结表没有Alias不会阻止现有金币Alias通过/ready，因此升级成功后仍需做该新表的单独导入/查询验证。

空ES首次安装仍需按部署文档先Compose启动、初始化任务索引并完成业务索引导入；不能把缺Alias导致503直接当成启动崩溃。

Nginx backup是被动失败切换，并非探测严格/ready后才让新主接流量。端口已监听但应用尚未严格就绪时，新主可能被路由到；需要更严查询不中断保证时另行设计上线门禁。当前示例主备端口、正式/测试容器名隔离正确。

query回滚后保留、unless-stopped自愈是运维选择；成功升级路径会清理query。不要为了消除残留而移除接管期间自愈能力。

### 17.9 当前配置与日志说明

test/dev 的通知总开关和Lark开关默认true，但邻近注释仍称“默认关闭”，注释与配置不一致；调试会发送消息。若修正应先确定保留true还是改为false，本次不改变开关。

当前T+1 Reader仍有逐批INFO日志，Bulk前5次及每20次打印进度；对账/通知在各模块产生日志。专项日志存在不代表所有副作用都会进入import文件，全量日志保留为跨模块排查入口。

Logback异步Appender配置neverBlock=true，队列满时允许丢日志，包括错误日志；discardingThreshold=0不等于保证绝不丢失。因此失败ID专项文件也不能当作可靠持久修复队列。该配置是日志不阻塞业务的取舍，若需完整修复依据应另行确认可靠存储方式。

## 18. 修复顺序与验证要求

1. 先处理D02：把回滚时新主的退出也纳入安全排空协议。D01已确认接受风险，不修改实现，不列入修复任务。
2. 再处理D03、D04、D08：历史清理隔离、临时ES错误重试、cancel恢复ID不丢失。
3. D05已确认采用累计处理口径并调整注释及通知，不新增去重统计；继续评估D09、D10、D13：设置/刷新确认、对账完整性和失败ID日志。
4. D06、D07一并评估：去掉锁内远程IO，同时按实际队列和SQL耗时确认排空时间边界。
5. 最后补齐部署锁、回滚验证、启动续跑和受控重跑入口；涉及新业务行为的项目需先确认。

建议修复验证重点：历史exists失败；请求级临时ES错误；早期item失败后drain并重放；cancel读取任务失败；新主运行任务时升级失败回滚。每项验证应同时观察ES任务文档、物理索引数量、Alias、drain结果及资源归还，不能只看日志中的success或HTTP 200。D01的终态写入异常保留为人工复核事项，不增加对应实现改造。

本次结论依据静态源码路径复核；没有对生产/测试服务器执行写入、停止容器或故障注入。编译验证只能证明当前工程可构建，不能代替上述异常路径验证。

本次验证结果：mvn -DskipTests package通过；两份部署脚本bash -n通过；文档28处源码引用的文件与行号存在；git diff --check通过。工程当前没有测试源码，本次未新增测试文件。
