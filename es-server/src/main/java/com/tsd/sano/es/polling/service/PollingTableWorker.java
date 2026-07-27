package com.tsd.sano.es.polling.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.notify.ImportNotifyService;
import com.tsd.sano.es.importer.pipeline.EsIndexManager;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.polling.model.SyncCheckpoint;
import com.tsd.sano.es.reconcile.service.ReconcileStatisticsService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

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
     * 当前表配置
     */
    private final EsImportProperties.TableConfig tableConfig;

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
     * checkpoint持久化服务
     */
    private final SyncCheckpointService checkpointService;

    /**
     * 每日物理索引管理器
     */
    private final EsIndexManager indexManager;

    /**
     * 日期推进成功后的独立异步统计对账入口
     */
    private final ReconcileStatisticsService reconcileStatisticsService;

    /**
     * Polling停止通知入口
     */
    private final ImportNotifyService notifyService;

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
            EsImportProperties.TableConfig tableConfig,
            SyncCheckpoint checkpoint,
            EsImportProperties properties,
            PollingJdbcReader jdbcReader,
            PollingBulkWriter bulkWriter,
            SyncCheckpointService checkpointService,
            EsIndexManager indexManager,
            ReconcileStatisticsService reconcileStatisticsService,
            ImportNotifyService notifyService
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
        this.checkpointService = checkpointService;
        this.indexManager = indexManager;
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
        log.info("===> ES-Polling worker started. table={}, date={}, lastId={}",
                tableName, syncDate, lastId);

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
                    stage = Stage.BULK_WRITING;
                    String indexName = indexAlias + "_" + INDEX_DATE_FORMATTER.format(syncDate);
                    long esStartedAt = System.currentTimeMillis();
                    // BulkWriter内部完成整批重试；成功或重试耗尽告警后都会返回并推进查询游标
                    boolean bulkSuccessful = bulkWriter.writeBatch(tableConfig, syncDate, indexName, batch);
                    long esCostMs = System.currentTimeMillis() - esStartedAt;
                    lastId = batch.lastId();
                    lastActivityAt = Instant.now();
                    log.info("===> ES-Polling cycle completed. table={}, date={}, size={}, "
                                    + "previousLastId={}, nextLastId={}, mysqlCostMs={}, esCostMs={}, "
                                    + "totalCostMs={}, result={}",
                            tableName, syncDate, batch.rows().size(),
                            previousLastId, lastId, mysqlCostMs, esCostMs,
                            System.currentTimeMillis() - cycleStartedAt,
                            bulkSuccessful ? "SUCCESS" : "BULK_FAILED_CONTINUED");
                    if (stopRequested) {
                        // drain在Bulk期间到达时等待完整重试结束，再保存已推进的查询游标。
                        stopGracefully();
                        return;
                    }
                    continue;
                }

                log.info("===> ES-Polling cycle completed. table={}, date={}, size=0, "
                                + "previousLastId={}, nextLastId={}, mysqlCostMs={}, esCostMs=0, "
                                + "totalCostMs={}, result=EMPTY",
                        tableName, syncDate, previousLastId, lastId,
                        mysqlCostMs, System.currentTimeMillis() - cycleStartedAt);

                if (stopRequested) {
                    // SQL已经返回空批次，无需再进入日期判断，直接保存当前内存游标
                    stopGracefully();
                    return;
                }

                LocalDateTime now = LocalDateTime.now();
                LocalDate today = now.toLocalDate();
                if (syncDate.isAfter(today)) {
                    throw new ServiceException("ES polling syncDate cannot be after current date, tableName="
                            + tableName + ", syncDate=" + syncDate + ", currentDate=" + today);
                }

                Duration closeDelay = properties.getPolling().getDateCloseDelay();
                LocalDateTime closeTime = syncDate.plusDays(1)
                        .atStartOfDay()
                        .plus(closeDelay);
                if (syncDate.equals(today) || queryStartedAt.isBefore(closeTime)) {
                    // 当前日期持续轮询；旧日期在关闭时间前也必须保留，以接收允许范围内的晚到数据
                    stage = Stage.IDLE;
                    waitForNextPoll();
                    continue;
                }

                // 只有在关闭时间之后开始的SQL仍返回空，才能完整提交日期切换
                stage = Stage.DATE_SWITCHING;
                LocalDate nextDate = syncDate.plusDays(1);
                // 创建下一天的物理索引，避免在checkpoint持久化后才发现索引不存在
                int maxAttempts = Math.max(0, properties.getPolling().getBulkRetryTimes()) + 1;
                for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                    try {
                        indexManager.preparePollingIndex(tableConfig, nextDate);
                        break;
                    } catch (RuntimeException error) {
                        if (attempt >= maxAttempts) {
                            throw error;
                        }
                        log.warn("===> ES-Polling next date index preparation failed, retry whole operation. "
                                        + "table={}, nextDate={}, attempt={}/{}, error={}",
                                tableName, nextDate, attempt, maxAttempts, error.getMessage());
                        try {
                            Thread.sleep(Math.max(1L,
                                    properties.getPolling().getBulkRetryInterval().toMillis()));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            stopRequested = true;
                            stopGracefully();
                            return;
                        }
                    }
                }
                Optional<SyncCheckpoint> advanced = checkpointService.advanceDate(
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
                lastActivityAt = Instant.now();
                log.info("===> ES-Polling date advanced. table={}, date={}, lastId={}",
                        tableName, syncDate, lastId);
                LocalDate closedDate = nextDate.minusDays(1);
                // checkpoint已经推进后才提交两个独立异步任务；提交失败不能回退日期或暂停当前表
                try {
                    indexManager.deleteHistoryIndex(tableConfig, closedDate);
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
            }
        } catch (RuntimeException error) {
            log.error("===> ES-Polling worker cycle failed. table={}, date={}, lastId={}, stage={}, error={}",
                    tableName, syncDate, lastId, stage, error.getMessage(), error);
            pauseOnError(error);
        } finally {
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
                checkpointSaved = checkpointService.stopGracefully(
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
                log.error("===> ES-Polling graceful stop persistence failed, retry later. "
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
                boolean paused = checkpointService.pauseOnError(
                        tableConfig.getTableName(),
                        syncDate,
                        lastId,
                        lastError,
                        Instant.now()
                );
                if (paused) {
                    checkpointSaved = true;
                    String indexName = tableConfig.getIndexAlias() + "_" + INDEX_DATE_FORMATTER.format(syncDate);
                    notifyService.notifyPollingStopped(tableConfig.getTableName(), indexName, syncDate, lastId, error);
                } else {
                    // 持久状态已变化时不能再覆盖checkpoint
                    lastError = "Polling error occurred but pause was rejected because checkpoint status changed: "
                            + lastError;
                    stage = Stage.STOPPED;
                }
                return;
            } catch (RuntimeException persistenceError) {
                log.error("===> ES-Polling pause persistence failed, retry later. "
                                + "table={}, date={}, lastId={}, error={}",
                        tableConfig.getTableName(), syncDate, lastId,
                        persistenceError.getMessage(), persistenceError);
                waitForCheckpointPersistence();
            }
        }
    }

    /**
     * 等待下一轮当前日期查询；drain中断只用于提前结束等待
     */
    private void waitForNextPoll() {
        Duration interval = properties.getPolling().getPollInterval();
        try {
            Thread.sleep(Math.max(1L, interval.toMillis()));
        } catch (InterruptedException error) {
            // 执行器关闭产生的中断也按优雅停止处理，不把正常停机误判为业务错误
            Thread.interrupted();
            stopRequested = true;
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
     * 单表Worker当前执行阶段
     */
    public enum Stage {
        IDLE,
        QUERYING,
        BULK_WRITING,
        DATE_SWITCHING,
        PAUSED,
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
