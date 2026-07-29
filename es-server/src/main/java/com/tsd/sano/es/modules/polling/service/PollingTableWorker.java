package com.tsd.sano.es.modules.polling.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.polling.model.SyncCheckpoint;
import com.tsd.sano.es.modules.reconcile.service.ReconcileStatisticsService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 单张Polling表的串行同步Worker
 *
 * <p>同一线程依次执行MySQL查询和ES整批写入，已处理批次只推进内存查询游标
 * checkpoint只在跨天、错误暂停或优雅停止时保存，不维护队列和并行Bulk状态</p>
 */
public class PollingTableWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(PollingTableWorker.class);

    /**
     * Polling每日物理索引命名格式
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 当前日期连续空查询后的固定退避秒数；达到300秒后保持该间隔
     */
    private static final long[] EMPTY_POLL_BACKOFF_SECONDS = {5L, 10L, 30L, 60L, 300L};

    /**
     * 当前表配置
     */
    private final SyncTableConfig tableConfig;

    /**
     * Polling循环和批量读取参数
     */
    private final EsImportProperties properties;

    /**
     * 单批MySQL读取器
     */
    private final PollingJdbcReader jdbcReader;

    /**
     * 同步整批ES写入器
     */
    private final PollingBulkWriter bulkWriter;

    /**
     * Polling日期索引和checkpoint持久化服务。
     */
    private final PollingIndexService pollingIndexService;

    /**
     * 日期推进成功后的独立异步统计对账入口
     */
    private final ReconcileStatisticsService reconcileStatisticsService;

    /**
     * Polling停止通知入口
     */
    private final PollingNotifyService notifyService;

    /**
     * Worker内存中的当前业务日期
     */
    private volatile LocalDate syncDate;

    /**
     * Worker内存中的当前主键游标
     */
    private volatile long lastId;

    /**
     * drain或协调器发出的停止标记
     */
    private volatile boolean stopRequested;

    /**
     * 用于提前结束空查询退避，避免最长300秒等待阻塞drain
     */
    private final CountDownLatch stopSignal = new CountDownLatch(1);

    /**
     * 当前串行执行阶段，仅用于状态和drain边界判断
     */
    private volatile Stage stage = Stage.IDLE;

    /**
     * 最近一次错误原因
     */
    private volatile String lastError;

    /**
     * 最近一次阶段或游标变化时间
     */
    private volatile Instant lastActivityAt = Instant.now();

    /**
     * 错误暂停或正常停止时是否成功保存了最终checkpoint
     */
    private volatile boolean checkpointSaved;

    /**
     * 使用状态为RUNNING的checkpoint创建单表Worker
     */
    public PollingTableWorker(
            SyncTableConfig tableConfig,
            SyncCheckpoint checkpoint,
            EsImportProperties properties,
            PollingJdbcReader jdbcReader,
            PollingBulkWriter bulkWriter,
            PollingIndexService pollingIndexService,
            ReconcileStatisticsService reconcileStatisticsService,
            PollingNotifyService notifyService
    ) {
        // 表配置已经在EsImportProperties加载时校验，这里只检查checkpoint与当前Worker是否一致
        if (!StringUtils.equals(tableConfig.getTableName(), checkpoint.getTableName())
                || !StringUtils.equals(tableConfig.getIndexAlias(), checkpoint.getIndexAlias())
                || checkpoint.getStatus() != SyncCheckpoint.Status.RUNNING
                || checkpoint.getSyncDate() == null) {
            throw new ServiceException("ES polling worker requires a valid RUNNING checkpoint, tableName="
                    + tableConfig.getTableName());
        }

        this.tableConfig = tableConfig;
        this.syncDate = checkpoint.getSyncDate();
        this.lastId = checkpoint.getLastId();
        this.properties = properties;
        this.jdbcReader = jdbcReader;
        this.bulkWriter = bulkWriter;
        this.pollingIndexService = pollingIndexService;
        this.reconcileStatisticsService = reconcileStatisticsService;
        this.notifyService = notifyService;
    }

    /**
     * 串行运行当前表，直到drain或系统性错误使Worker退出
     */
    @Override
    public void run() {
        String tableName = tableConfig.getTableName();
        String indexAlias = tableConfig.getIndexAlias();
        PollingLogSummary logSummary = new PollingLogSummary(tableName, syncDate, lastId);
        log.info("===> ES-Polling worker started. table={}, date={}, lastId={}",
                tableName, syncDate, lastId);

        int emptyPollBackoffIndex = 0;
        try {
            while (true) {
                stage = Stage.IDLE;
                lastActivityAt = Instant.now();
                if (stopRequested) {
                    stopGracefully();
                    return;
                }

                long cycleStartedAt = System.currentTimeMillis();
                long previousLastId = lastId;
                // QUERYING设置在线程真正进入JDBC前，drain在此后到达时等待本次SQL返回
                stage = Stage.QUERYING;
                // Polling业务日期与MySQL DATETIME统一按部署约定的UTC+8本地时间计算，不做时区转换
                LocalDateTime queryStartedAt = LocalDateTime.now();
                long mysqlStartedAt = System.currentTimeMillis();
                PollingJdbcReader.ReadBatch batch = jdbcReader.readBatch(tableConfig, syncDate, lastId,
                        properties.getPolling().getReadBatchSize());
                long mysqlCostMs = System.currentTimeMillis() - mysqlStartedAt;
                lastActivityAt = Instant.now();

                if (!batch.isEmpty()) {
                    // 一旦读到数据，下一次空查询重新从5秒开始退避。
                    emptyPollBackoffIndex = 0;
                    stage = Stage.BULK_WRITING;
                    String indexName = indexAlias + "_" + INDEX_DATE_FORMATTER.format(syncDate);
                    long esStartedAt = System.currentTimeMillis();
                    // BulkWriter内部完成整批重试；成功或重试耗尽告警后都会返回并推进查询游标
                    boolean bulkSuccessful = bulkWriter.writeBatch(tableConfig, syncDate, indexName, batch);
                    long esCostMs = System.currentTimeMillis() - esStartedAt;
                    lastId = batch.lastId();
                    lastActivityAt = Instant.now();
                    log.debug("===> ES-Polling cycle completed. table={}, date={}, size={}, "
                                    + "previousLastId={}, nextLastId={}, mysqlCostMs={}, esCostMs={}, "
                                    + "totalCostMs={}, result={}",
                            tableName, syncDate, batch.rows().size(),
                            previousLastId, lastId, mysqlCostMs, esCostMs,
                            System.currentTimeMillis() - cycleStartedAt,
                            bulkSuccessful ? "SUCCESS" : "BULK_FAILED_CONTINUED");
                    // 调用汇总日志打印
                    logSummary.recordCycle(syncDate, batch.rows().size(),
                            previousLastId, lastId, mysqlCostMs, esCostMs,
                            System.currentTimeMillis() - cycleStartedAt,
                            bulkSuccessful
                    );
                    if (stopRequested) {
                        // drain在Bulk期间到达时等待完整重试结束，再保存已推进的查询游标。
                        stopGracefully();
                        return;
                    }
                    continue;
                }

                log.debug("===> ES-Polling cycle completed. table={}, date={}, size=0, "
                                + "previousLastId={}, nextLastId={}, mysqlCostMs={}, esCostMs=0, "
                                + "totalCostMs={}, result=EMPTY",
                        tableName, syncDate, previousLastId, lastId,
                        mysqlCostMs, System.currentTimeMillis() - cycleStartedAt);
                // 调用汇总日志打印
                logSummary.recordCycle(syncDate, 0, previousLastId, lastId,
                        mysqlCostMs, 0L, System.currentTimeMillis() - cycleStartedAt,
                        true
                );

                if (stopRequested) {
                    // drain在本轮SQL期间到达；停止前不再提交日期关闭，保存D日当前游标， 由恢复后的下一次查询重新确认关闭延迟和空批次条件。
                    stopGracefully();
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                LocalDate today = now.toLocalDate();
                if (syncDate.isAfter(today)) {
                    throw new ServiceException("ES polling syncDate cannot be after current date, tableName="
                            + tableName + ", syncDate=" + syncDate + ", currentDate=" + today);
                }

                if (syncDate.equals(today)) {
                    // 当天连续空查询按5、10、30、60、300秒逐级退避，降低无数据时的MySQL轮询压力。
                    long backoffSeconds = EMPTY_POLL_BACKOFF_SECONDS[emptyPollBackoffIndex];
                    if (emptyPollBackoffIndex < EMPTY_POLL_BACKOFF_SECONDS.length - 1) {
                        emptyPollBackoffIndex++;
                    }
                    stage = Stage.IDLE;
                    waitForNextPoll(Duration.ofSeconds(backoffSeconds));
                    continue;
                }

                Duration closeDelay = properties.getPolling().getDateCloseDelay();
                LocalDateTime closeTime = syncDate.plusDays(1)
                        .atStartOfDay()
                        .plus(closeDelay);
                if (queryStartedAt.isBefore(closeTime)) {
                    // 旧日期在关闭时间前仍按基础间隔查询，以接收日期关闭延迟范围内的晚到数据
                    stage = Stage.IDLE;
                    waitForNextPoll(properties.getPolling().getPollInterval());
                    continue;
                }

                // 到达这里表示D日已经超过关闭延迟，并且在关闭时间之后再次发起的MySQL查询仍然为空。
                // 此时才能确认D日完成，提交该日异步收尾任务，并开始D+1索引创建和checkpoint推进。
                stage = Stage.DATE_SWITCHING;
                LocalDate closedDate = syncDate;
                // D日汇总日志属于旁路能力；输出成功或失败都不影响后续异步任务和日期推进。
                logSummary.flush("DATE_CLOSE");
                // D日已经完成，先提交两个独立异步任务；重复提交和提交失败都不影响D+1跨天。
                try {
                    pollingIndexService.deleteHistoryIndex(tableConfig, closedDate);
                } catch (RuntimeException error) {
                    log.warn("===> ES-Polling history index deletion submit failed. "
                                    + "table={}, closedDate={}, error={}",
                            tableName, closedDate, error.getMessage(), error);
                }
                try {
                    reconcileStatisticsService.reconcile(tableConfig, closedDate);
                } catch (RuntimeException error) {
                    // 对账执行异常由异步方法自行处理；这里仅隔离代理或任务提交边界异常
                    log.warn("===> ES-Polling reconcile submit failed. table={}, closedDate={}, error={}",
                            tableName, closedDate, error.getMessage(), error);
                }

                LocalDate nextDate = syncDate.plusDays(1);
                // D+1物理索引固定最多尝试3次，两次重试前各等待5秒；全部失败后由外层暂停当前表。
                for (int attempt = 1; true; attempt++) {
                    try {
                        pollingIndexService.prepareIndex(tableConfig, nextDate);
                        break;
                    } catch (RuntimeException error) {
                        if (attempt >= 3) {
                            throw error;
                        }
                        log.warn("===> ES-Polling next date index preparation failed, retry later. "
                                        + "table={}, nextDate={}, attempt={}/3, error={}",
                                tableName, nextDate, attempt, error.getMessage());
                        try {
                            Thread.sleep(5_000L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            stopRequested = true;
                            stopGracefully();
                            return;
                        }
                    }
                }
                Optional<SyncCheckpoint> advanced = pollingIndexService.advanceDate(
                        tableName,
                        syncDate,
                        nextDate,
                        Instant.now()
                );
                if (advanced.isEmpty()) {
                    // 人工状态或日期已经变化时不再覆盖checkpoint；已创建的空索引可继续幂等使用
                    lastError = "Polling checkpoint status or sync date changed while advancing date";
                    stage = Stage.STOPPED;
                    return;
                }
                SyncCheckpoint nextCheckpoint = advanced.get();
                syncDate = nextCheckpoint.getSyncDate();
                lastId = nextCheckpoint.getLastId();
                emptyPollBackoffIndex = 0;
                lastActivityAt = Instant.now();
                log.info("===> ES-Polling date advanced. table={}, date={}, lastId={}",
                        tableName, syncDate, lastId);
            }
        } catch (RuntimeException error) {
            log.error("===> ES-Polling worker cycle failed. table={}, date={}, lastId={}, stage={}, error={}",
                    tableName, syncDate, lastId, stage, error.getMessage(), error);
            pauseOnError(error);
        } finally {
            // 输出不足五分钟的剩余窗口；汇总器内部隔离全部日志异常，不参与停止结果判断。
            logSummary.flush("WORKER_STOP");
            if (stage != Stage.PAUSED && stage != Stage.STOPPED) {
                stage = Stage.STOPPED;
            }
            lastActivityAt = Instant.now();
            log.info("===> ES-Polling worker stopped. table={}, stage={}, date={}, lastId={}, "
                            + "checkpointSaved={}, error={}",
                    tableName, stage, syncDate, lastId, checkpointSaved, lastError);
        }
    }

    /**
     * 请求Worker在当前SQL、Bulk或日期提交到达安全边界后停止
     */
    public void requestStop() {
        stopRequested = true;
        stopSignal.countDown();
    }

    /**
     * 返回当前Worker只读运行快照
     */
    public Snapshot snapshot() {
        return new Snapshot(
                tableConfig.getTableName(),
                syncDate,
                lastId,
                stage,
                stopRequested,
                checkpointSaved,
                lastError,
                lastActivityAt
        );
    }

    /**
     * 保存最新内存查询进度；临时ES错误时保持停止状态并继续尝试持久化
     */
    private void stopGracefully() {
        stage = Stage.STOPPED;
        while (true) {
            try {
                checkpointSaved = pollingIndexService.stopGracefully(
                        tableConfig.getTableName(),
                        syncDate,
                        lastId,
                        Instant.now()
                );
                if (!checkpointSaved) {
                    lastError = "Polling graceful stop was rejected because checkpoint status changed";
                }
                return;
            } catch (RuntimeException error) {
                lastError = "Polling graceful checkpoint save failed: " + error.getMessage();
                log.warn("===> ES-Polling graceful stop persistence failed, retry later. "
                                + "table={}, date={}, lastId={}, error={}",
                        tableConfig.getTableName(), syncDate, lastId, error.getMessage(), error);
                waitForCheckpointPersistence();
            }
        }
    }

    /**
     * 保存当前内存查询进度、持久暂停当前表并在成功后发送一次通知
     */
    private void pauseOnError(RuntimeException error) {
        stage = Stage.PAUSED;
        lastError = StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
        while (true) {
            try {
                boolean paused = pollingIndexService.pauseOnError(
                        tableConfig.getTableName(),
                        syncDate,
                        lastId,
                        lastError,
                        Instant.now()
                );
                if (paused) {
                    checkpointSaved = true;
                    String indexName = tableConfig.getIndexAlias() + "_" + INDEX_DATE_FORMATTER.format(syncDate);
                    notifyService.notifyStopped(tableConfig.getTableName(), indexName, syncDate, lastId, error);
                } else {
                    // 持久状态已变化时不能再覆盖checkpoint
                    lastError = "Polling error occurred but pause was rejected because checkpoint status changed: "
                            + lastError;
                    stage = Stage.STOPPED;
                }
                return;
            } catch (RuntimeException persistenceError) {
                log.warn("===> ES-Polling pause persistence failed, retry later. "
                                + "table={}, date={}, lastId={}, error={}",
                        tableConfig.getTableName(), syncDate, lastId,
                        persistenceError.getMessage(), persistenceError);
                waitForCheckpointPersistence();
            }
        }
    }

    /**
     * 等待下一轮查询；drain停止信号可提前结束等待
     */
    private void waitForNextPoll(Duration interval) {
        try {
            stopSignal.await(Math.max(1L, interval.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            // 执行器关闭产生的中断也按优雅停止处理，不把正常停机误判为业务错误
            Thread.interrupted();
            stopRequested = true;
            stopSignal.countDown();
        }
    }

    /**
     * checkpoint暂时不可写时使用固定短等待，避免紧密重试压垮ES
     */
    private void waitForCheckpointPersistence() {
        Duration interval = properties.getPolling().getPollInterval();
        try {
            Thread.sleep(Math.max(1L, interval.toMillis()));
        } catch (InterruptedException ignored) {
            // 持久业务状态未确定前不能因线程中断直接退出；清除标记后继续保存
            Thread.interrupted();
        }
    }

    /**
     * 单表Worker当前在本JVM中的执行阶段。
     *
     * <p>该状态只用于运行状态展示和drain边界判断，不代表checkpoint已经持久化。
     * PAUSED、STOPPED是否完成最终结算，还需结合checkpointSaved判断。</p>
     */
    public enum Stage {

        /**
         * 当前没有执行MySQL或ES操作，可能正在等待下一轮轮询、空查询退避，或准备响应停止请求。
         */
        IDLE,

        /**
         * 正在执行本轮MySQL查询；此时收到停止请求，需要等待当前SQL返回。
         */
        QUERYING,

        /**
         * 已取得MySQL数据，正在执行整批ES Bulk及其内部重试； 此时收到停止请求，需要等待该批处理完成。
         */
        BULK_WRITING,

        /**
         * 正在关闭当前业务日期，包括创建下一日索引、推进checkpoint，以及提交历史索引删除和异步对账任务。
         */
        DATE_SWITCHING,

        /**
         * Worker因系统性错误停止业务处理，正在保存或已经保存PAUSED状态。
         *
         * <p>该阶段不保证checkpoint已经持久化成功，应结合checkpointSaved判断。</p>
         */
        PAUSED,

        /**
         * Worker已停止发起新的查询和写入，正在保存或已经保存最终checkpoint。
         *
         * <p>该阶段不等于drain已经安全完成；还需等待Worker退出并确认 checkpointSaved为true。</p>
         */
        STOPPED
    }

    /**
     * 状态接口和drain协调器使用的只读Worker快照
     */
    public record Snapshot(
            String tableName,
            LocalDate syncDate,
            long lastId,
            Stage stage,
            boolean stopRequested,
            boolean checkpointSaved,
            String lastError,
            Instant lastActivityAt
    ) {
    }
}
