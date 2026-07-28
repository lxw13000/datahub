package com.tsd.sano.es.modules.tplusone.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.tplusone.pipeline.TPlusOneImportService;
import com.tsd.sano.es.modules.index.EsIndexManager;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.tplusone.model.TPlusOneImportConfig;
import com.tsd.sano.es.modules.tplusone.model.ImportStatistics;
import com.tsd.sano.es.modules.tplusone.model.SanoImportTask;
import com.tsd.sano.es.modules.tplusone.model.SanoImportTaskStatus;
import com.tsd.sano.es.modules.polling.model.SyncCheckpoint;
import com.tsd.sano.es.modules.polling.service.PollingIndexService;
import com.tsd.sano.es.modules.reconcile.service.ReconcileStatisticsService;
import com.tsd.sano.es.modules.config.EsServiceModeManager;
import com.tsd.sano.es.modules.coordination.service.SyncDrainCoordinator;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * ES定时导入任务
 *
 * <p>每天先生成PENDING任务，再按任务索引顺序串行执行待处理任务</p>
 */
@Component
public class TPlusOneImportTask {

    private static final Logger log = LoggerFactory.getLogger(TPlusOneImportTask.class);

    /**
     * 导入日期格式，和任务索引中的import_date字段保持一致
     */
    private static final DateTimeFormatter IMPORT_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 导入任务配置，包含调度时间、运行时长、批次大小和表配置
     */
    private final EsImportProperties properties;

    /**
     * 单表单天导入主流程服务
     */
    private final TPlusOneImportService importService;

    /**
     * ES任务索引读写服务
     */
    private final SanoImportTaskService importTaskService;

    /**
     * 导入任务结束后的通知服务
     */
    private final TPlusOneNotifyService notifyService;

    /**
     * 手动补数据使用的后台线程池，避免HTTP请求阻塞到导入完成
     */
    private final Executor esImportExecutor;

    /**
     * 统一同步排空协调器，同时承担T+1调度器和任务启动的原子门禁
     */
    private final SyncDrainCoordinator drainCoordinator;

    /**
     * 运行时服务模式门禁；Bean始终注册，但query模式不得启动任何同步工作
     */
    private final EsServiceModeManager serviceModeManager;

    /**
     * Polling持久恢复点服务，用于限制历史修复不能触碰当前同步日期
     */
    private final PollingIndexService pollingIndexService;

    /**
     * 物理索引管理组件，用于确认历史修复只覆盖已经存在的日索引
     */
    private final EsIndexManager indexManager;

    /**
     * 修复成功后的独立异步统计对账入口
     */
    private final ReconcileStatisticsService reconcileStatisticsService;

    /**
     * 注入T+1任务编排、异步执行、通知和运行时门禁所需组件
     */
    public TPlusOneImportTask(EsImportProperties properties,
                              TPlusOneImportService importService,
                              SanoImportTaskService importTaskService,
                              TPlusOneNotifyService notifyService,
                              @Qualifier("esImportExecutor") Executor esImportExecutor,
                              SyncDrainCoordinator drainCoordinator,
                              EsServiceModeManager serviceModeManager,
                              PollingIndexService pollingIndexService,
                              EsIndexManager indexManager,
                              ReconcileStatisticsService reconcileStatisticsService) {
        this.properties = properties;
        this.importService = importService;
        this.importTaskService = importTaskService;
        this.notifyService = notifyService;
        this.esImportExecutor = esImportExecutor;
        this.drainCoordinator = drainCoordinator;
        this.serviceModeManager = serviceModeManager;
        this.pollingIndexService = pollingIndexService;
        this.indexManager = indexManager;
        this.reconcileStatisticsService = reconcileStatisticsService;
    }

    /**
     * 每天按配置生成T+1导入任务，并串行执行所有待处理任务
     */
    @Scheduled(cron = "${sano.import.t-plus-one.cron:0 30 2 * * ?}")
    public void importYesterday() {
        if (!isEnabled()) {
            return;
        }
        if (!drainCoordinator.tryStartTPlusOneDispatcher()) {
            // 已有手动或定时导入正在执行，本轮定时任务跳过，待下一轮继续扫描任务索引
            log.warn("===> ES-Import scheduled task skipped because dispatcher is busy or sync drain is active.");
            return;
        }

        LocalDate importDate = LocalDate.now().minusDays(1);
        try {
            long maxRunMillis = Math.max(1, properties.getTPlusOne().getMaxRunMinutes()) * 60L * 1000L;
            long deadlineMillis = System.currentTimeMillis() + maxRunMillis;
            log.info("===> ES-Import scheduled task start. date={}, maxRunMinutes={}",
                    importDate, properties.getTPlusOne().getMaxRunMinutes());

            repairExpiredRunningTasks();
            createPendingTasks(importDate);
            runPendingTasks(deadlineMillis);

            log.info("===> ES-Import scheduled task finished. date={}", importDate);
        } finally {
            finishDispatcherAndResumeCancelledDrain();
        }
    }

    /**
     * 手动补指定日期段的数据，起止日期相同即补指定单天
     *
     * @param startDate 开始日期，包含
     * @param endDate   结束日期，包含
     * @return true表示已提交后台执行，false表示已有导入任务正在执行
     */
    public boolean importDateRange(LocalDate startDate, LocalDate endDate) {
        requireEnabled();
        if (!drainCoordinator.tryStartTPlusOneDispatcher()) {
            log.warn("===> ES-Import manual task submit skipped because dispatcher is busy or sync drain is active. startDate={}, endDate={}",
                    startDate, endDate);
            return false;
        }

        try {
            esImportExecutor.execute(() -> {
                try {
                    long maxRunMillis = Math.max(1, properties.getTPlusOne().getMaxRunMinutes()) * 60L * 1000L;
                    long deadlineMillis = System.currentTimeMillis() + maxRunMillis;
                    log.info("===> ES-Import manual task start. startDate={}, endDate={}, maxRunMinutes={}",
                            startDate, endDate, properties.getTPlusOne().getMaxRunMinutes());

                    repairExpiredRunningTasks();
                    for (LocalDate importDate = startDate; !importDate.isAfter(endDate); importDate = importDate.plusDays(1)) {
                        if (!drainCoordinator.isAcceptingNewWork()) {
                            break;
                        }
                        createPendingTasks(importDate);
                    }
                    runPendingTasks(deadlineMillis);

                    log.info("===> ES-Import manual task finished. startDate={}, endDate={}", startDate, endDate);
                } catch (Exception e) {
                    // 异步任务异常不会返回给HTTP调用方，必须在后台线程中明确记录
                    log.error("===> ES-Import manual task failed. startDate={}, endDate={}, error={}",
                            startDate, endDate, e.getMessage(), e);
                } finally {
                    finishDispatcherAndResumeCancelledDrain();
                }
            });
            return true;
        } catch (Exception e) {
            finishDispatcherAndResumeCancelledDrain();
            log.error("===> ES-Import manual task submit failed. startDate={}, endDate={}, error={}",
                    startDate, endDate, e.getMessage(), e);
            return false;
        }
    }

    /**
     * 手动补指定单表的日期段数据
     *
     * <p>调用方确认数据尚未同步后，直接按天创建PENDING任务；已有同ID任务由任务索引create语义自然去重</p>
     *
     * @param tableName MySQL源表名
     * @param startDate 开始日期，包含
     * @param endDate   结束日期，包含
     * @return 提交结果说明
     */
    public String importTableDateRange(String tableName, LocalDate startDate, LocalDate endDate) {
        requireEnabled();
        if (!drainCoordinator.isAcceptingNewWork()) {
            throw new ServiceException("Sync drain is active; new T+1 manual tasks are not accepted");
        }
        SyncTableConfig table = properties.requireTPlusOneTable(tableName);

        int createdCount = 0;
        int existingCount = 0;
        int failedCount = 0;
        boolean stoppedByDrain = false;
        for (LocalDate importDate = startDate; !importDate.isAfter(endDate); importDate = importDate.plusDays(1)) {
            String importDateText = IMPORT_DATE_FORMATTER.format(importDate);

            try {
                SanoImportTask task = new SanoImportTask();
                task.setTableName(tableName);
                task.setIndexAlias(table.getIndexAlias());
                task.setIndexName(table.getIndexAlias() + "_" + importDateText);
                task.setImportDate(importDateText);
                task.setStatus(SanoImportTaskStatus.PENDING.name());
                task.setLastSuccessId(0L);

                Boolean added = drainCoordinator.callIfAcceptingNewWork(
                        () -> importTaskService.addTask(task), null);
                if (added == null) {
                    stoppedByDrain = true;
                    log.warn("===> ES-Import stop creating manual tasks at drain boundary. alias={}, date={}",
                            table.getIndexAlias(), importDate);
                    break;
                }
                if (added) {
                    createdCount++;
                } else {
                    // 同一张表同一天已有任务时不更新原记录，由现有任务状态决定后续执行方式
                    existingCount++;
                }
            } catch (Exception e) {
                // 单天任务写入失败不阻断日期段内其他任务，便于一次补数尽可能推进
                failedCount++;
                log.error("===> ES-Import manual task create failed. alias={}, table={}, date={}, error={}",
                        table.getIndexAlias(), tableName, importDate, e.getMessage(), e);
            }
        }

        if (stoppedByDrain) {
            throw new ServiceException("Sync drain started while creating T+1 manual tasks; tasks already created remain pending. created="
                    + createdCount + ", existing=" + existingCount + ", failed=" + failedCount);
        }

        if (!drainCoordinator.tryStartTPlusOneDispatcher()) {
            // 任务已落库，当前同步完成后，下一次队列扫描会继续处理PENDING任务
            log.info("===> ES-Import manual table task queued. alias={}, table={}, startDate={}, endDate={}, created={}, existing={}, failed={}",
                    table.getIndexAlias(), tableName, startDate, endDate, createdCount, existingCount, failedCount);
            return "任务已入队，当前已有同步任务执行中，等待后续队列扫描created=" + createdCount
                    + ", existing=" + existingCount + ", failed=" + failedCount;
        }

        try {
            esImportExecutor.execute(() -> {
                try {
                    long maxRunMillis = Math.max(1, properties.getTPlusOne().getMaxRunMinutes()) * 60L * 1000L;
                    long deadlineMillis = System.currentTimeMillis() + maxRunMillis;
                    log.info("===> ES-Import manual table task start. alias={}, table={}, startDate={}, endDate={}, maxRunMinutes={}",
                            table.getIndexAlias(), tableName, startDate, endDate,
                            properties.getTPlusOne().getMaxRunMinutes());

                    repairExpiredRunningTasks();
                    // 复用待任务队列扫描，按现有顺序串行执行，不额外创建并发同步链路
                    runPendingTasks(deadlineMillis);

                    log.info("===> ES-Import manual table task finished. alias={}, table={}, startDate={}, endDate={}",
                            table.getIndexAlias(), tableName, startDate, endDate);
                } catch (Exception e) {
                    // 后台编排异常需要明确记录，但不能影响已经落库的任务后续被定时器续跑
                    log.error("===> ES-Import manual table task dispatcher failed. alias={}, table={}, startDate={}, endDate={}, error={}",
                            table.getIndexAlias(), tableName, startDate, endDate, e.getMessage(), e);
                } finally {
                    finishDispatcherAndResumeCancelledDrain();
                }
            });
            return "任务已提交后台队列扫描created=" + createdCount
                    + ", existing=" + existingCount + ", failed=" + failedCount;
        } catch (Exception e) {
            finishDispatcherAndResumeCancelledDrain();
            log.error("===> ES-Import manual table task submit failed. alias={}, table={}, error={}",
                    table.getIndexAlias(), tableName, e.getMessage(), e);
            return "任务已写入待执行队列，但后台扫描提交失败，等待下次定时任务处理created=" + createdCount
                    + ", existing=" + existingCount + ", failed=" + failedCount;
        }
    }

    /**
     * 为Polling表的已关闭历史日期提交一次T+1全量覆盖修复
     *
     * <p>修复复用现有任务索引和T+1导入管线，但只允许写入checkpoint当前日期之前已经存在的
     * 物理索引全量upsert不会删除ES中多余文档，修复后的对账仍不一致时需要运维重建索引</p>
     *
     * @param tableName  MySQL源表名
     * @param repairDate 已关闭的历史业务日期
     * @return 任务提交结果
     */
    public String repairPollingDate(String tableName, LocalDate repairDate) {
        requireEnabled();
        if (!drainCoordinator.isAcceptingNewWork()) {
            throw new ServiceException("Sync drain is active; new polling repair tasks are not accepted");
        }

        SyncTableConfig table = properties.getPollingTables().stream()
                .filter(config -> StringUtils.equals(config.getTableName(), tableName))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "ES sync table is disabled or mode mismatch, tableName=" + tableName
                                + ", expectedMode=POLLING"));
        SyncCheckpoint checkpoint = pollingIndexService.find(tableName)
                .orElseThrow(() -> new ServiceException(
                        "ES polling checkpoint does not exist, tableName=" + tableName));
        if (checkpoint.getSyncDate() == null || !repairDate.isBefore(checkpoint.getSyncDate())) {
            throw new ServiceException("Polling repair date must be earlier than checkpoint syncDate, tableName="
                    + tableName + ", repairDate=" + repairDate
                    + ", syncDate=" + checkpoint.getSyncDate());
        }

        String importDateText = IMPORT_DATE_FORMATTER.format(repairDate);
        String indexName = table.getIndexAlias() + "_" + importDateText;
        if (!indexManager.exists(indexName)) {
            throw new ServiceException("Polling repair physical index does not exist, index=" + indexName);
        }

        SanoImportTask task = new SanoImportTask();
        task.setTableName(tableName);
        task.setIndexAlias(table.getIndexAlias());
        task.setIndexName(indexName);
        task.setImportDate(importDateText);
        Boolean accepted = drainCoordinator.callIfAcceptingNewWork(
                () -> importTaskService.addOrResetPollingRepairTask(task), null);
        if (accepted == null) {
            throw new ServiceException("Sync drain started while submitting polling repair task");
        }
        if (!accepted) {
            throw new ServiceException("Polling repair task is still active "
                    + "(PENDING/RUNNING/TIMEOUT_PARTIAL), taskId=" + task.getTaskId());
        }

        if (!drainCoordinator.tryStartTPlusOneDispatcher()) {
            log.info("===> ES-Import polling repair task queued behind current dispatcher. taskId={}, index={}",
                    task.getTaskId(), indexName);
            return "Polling历史修复任务已入队，等待当前任务结束后执行taskId=" + task.getTaskId()
                    + "；注意：全量upsert不会删除ES多余文档，对账仍不一致时需人工重建索引";
        }

        try {
            esImportExecutor.execute(() -> {
                try {
                    long maxRunMillis = Math.max(1, properties.getTPlusOne().getMaxRunMinutes()) * 60L * 1000L;
                    long deadlineMillis = System.currentTimeMillis() + maxRunMillis;
                    log.info("===> ES-Import polling repair dispatcher start. taskId={}, index={}",
                            task.getTaskId(), indexName);
                    repairExpiredRunningTasks();
                    runPendingTasks(deadlineMillis);
                    log.info("===> ES-Import polling repair dispatcher finished. taskId={}, index={}",
                            task.getTaskId(), indexName);
                } catch (Exception error) {
                    // 任务已经持久化，后台扫描提交后的异常只记录，后续定时扫描仍可继续处理
                    log.error("===> ES-Import polling repair dispatcher failed. taskId={}, error={}",
                            task.getTaskId(), error.getMessage(), error);
                } finally {
                    finishDispatcherAndResumeCancelledDrain();
                }
            });
            return "Polling历史修复任务已提交taskId=" + task.getTaskId()
                    + "；注意：全量upsert不会删除ES多余文档，对账仍不一致时需人工重建索引";
        } catch (Exception error) {
            finishDispatcherAndResumeCancelledDrain();
            log.error("===> ES-Import polling repair dispatcher submit failed. taskId={}, error={}",
                    task.getTaskId(), error.getMessage(), error);
            return "Polling历史修复任务已入队，但后台扫描提交失败，等待下次任务扫描taskId="
                    + task.getTaskId();
        }
    }

    /**
     * 将超过运行窗口仍处于RUNNING的任务恢复为TIMEOUT_PARTIAL
     */
    private void repairExpiredRunningTasks() {
        try {
            LocalDateTime expireBefore = LocalDateTime.now().minusMinutes(
                    Math.max(1, properties.getTPlusOne().getMaxRunMinutes()));
            List<SanoImportTask> tasks = importTaskService.listRunningTasks(
                    properties.getTPlusOne().getTaskFetchLimit());

            for (SanoImportTask task : tasks) {
                if (task.getUpdatedAt() == null || !task.getUpdatedAt().isBefore(expireBefore)) {
                    continue;
                }

                LocalDateTime expiredUpdatedAt = task.getUpdatedAt();
                task.setStatus(SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
                task.setLastError("Recovered expired RUNNING task before scheduled import.");
                task.setFinishedAt(LocalDateTime.now());
                importTaskService.updateTask(task);
                log.warn("===> ES-Import repair expired running task. taskId={}, alias={}, table={}, date={}, updatedAt={}",
                        task.getTaskId(), task.getIndexAlias(), task.getTableName(), task.getImportDate(), expiredUpdatedAt);
            }
        } catch (Exception e) {
            // RUNNING残留修复失败不阻断本轮任务，后续创建和执行任务继续进行
            log.warn("===> ES-Import repair expired running task failed, continue scheduled import. error={}", e.getMessage());
        }
    }

    /**
     * 为当天配置的所有启用表创建PENDING任务
     */
    private void createPendingTasks(LocalDate importDate) {
        for (SyncTableConfig table : properties.getTPlusOneTables()) {
            if (!drainCoordinator.isAcceptingNewWork()) {
                log.info("===> ES-Import stop creating pending tasks because sync drain is active. date={}", importDate);
                return;
            }
            try {
                String indexAlias = table.getIndexAlias();
                String tableName = table.getTableName();
                String importDateText = IMPORT_DATE_FORMATTER.format(importDate);

                // 同一张表同一天只有一条任务记录，重复创建时由任务索引服务按_id去重
                SanoImportTask task = new SanoImportTask();
                task.setTableName(tableName);
                task.setIndexAlias(indexAlias);
                task.setIndexName(indexAlias + "_" + importDateText);
                task.setImportDate(importDateText);
                task.setStatus(SanoImportTaskStatus.PENDING.name());
                task.setLastSuccessId(0L);

                Boolean accepted = drainCoordinator.callIfAcceptingNewWork(() -> {
                    importTaskService.addTask(task);
                    return Boolean.TRUE;
                }, null);
                if (accepted == null) {
                    log.info("===> ES-Import pending task creation stopped at drain boundary. date={}", importDate);
                    return;
                }
            } catch (Exception e) {
                // 单表任务创建失败不影响其他表落任务，便于后续人工排查和补偿
                log.error("===> ES-Import create pending task failed. alias={}, table={}, date={}, error={}",
                        table.getIndexAlias(), table.getTableName(), importDate, e.getMessage(), e);
            }
        }
    }

    /**
     * 执行本轮拉取到的待处理任务
     */
    private void runPendingTasks(long deadlineMillis) {
        while (true) {
            if (!drainCoordinator.isAcceptingNewWork()) {
                log.info("===> ES-Import stop scanning pending tasks because sync drain is active.");
                return;
            }
            List<SanoImportTask> tasks = importTaskService.listPendingTasks(
                    properties.getTPlusOne().getTaskFetchLimit());
            if (tasks.isEmpty()) {
                log.info("===> ES-Import no pending task.");
                return;
            }

            for (SanoImportTask task : tasks) {
                if (!drainCoordinator.isAcceptingNewWork()) {
                    log.info("===> ES-Import stop starting pending tasks at drain boundary.");
                    return;
                }
                if (System.currentTimeMillis() >= deadlineMillis) {
                    // 本轮调度到达运行上限后不再启动新任务，已经完成落库的PENDING任务留待下一轮继续
                    log.warn("===> ES-Import scheduled task reach max run time, stop starting new task. maxRunMinutes={}",
                            properties.getTPlusOne().getMaxRunMinutes());
                    return;
                }

                if (!executeTask(task, deadlineMillis)) {
                    return;
                }
            }
        }
    }

    /**
     * 执行单条任务，并维护运行态和持久任务终态
     *
     * @return true表示任务已进入终态，可继续扫描；false表示被drain门禁拒绝
     */
    private boolean executeTask(SanoImportTask task, long deadlineMillis) {
        if (!drainCoordinator.tryBeginTPlusOneTask(task)) {
            return false;
        }

        boolean resumeTask = StringUtils.equals(task.getStatus(), SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
        boolean pollingRepair = false;
        ImportStatistics statistics = null;
        SanoImportTaskStatus terminalStatus = null;
        boolean persistenceSafe = false;
        boolean terminalUpdateAttempted = false;
        String terminalError = null;
        try {
            LocalDate importDate = LocalDate.parse(task.getImportDate(), IMPORT_DATE_FORMATTER);
            SyncTableConfig table = properties.getTPlusOneTables().stream()
                    .filter(config -> StringUtils.equals(config.getTableName(), task.getTableName()))
                    .findFirst()
                    .orElse(null);
            if (table == null) {
                // Polling历史修复没有新增任务类型字段，因此执行前必须依靠当前表模式和日期边界重新识别
                table = properties.getPollingTables().stream()
                        .filter(config -> StringUtils.equals(config.getTableName(), task.getTableName()))
                        .findFirst()
                        .orElseThrow(() -> new ServiceException(
                                "ES sync task table is disabled or mode mismatch, tableName="
                                        + task.getTableName()));
                SyncCheckpoint checkpoint = pollingIndexService.find(task.getTableName())
                        .orElseThrow(() -> new ServiceException(
                                "ES polling checkpoint does not exist, tableName=" + task.getTableName()));
                if (checkpoint.getSyncDate() == null || !importDate.isBefore(checkpoint.getSyncDate())) {
                    throw new ServiceException(
                            "Polling repair date must be earlier than checkpoint syncDate, tableName="
                                    + task.getTableName() + ", repairDate=" + importDate
                                    + ", syncDate=" + checkpoint.getSyncDate());
                }
                String expectedIndexName = table.getIndexAlias() + "_" + IMPORT_DATE_FORMATTER.format(importDate);
                if (!StringUtils.equals(task.getIndexAlias(), table.getIndexAlias())
                        || !StringUtils.equals(task.getIndexName(), expectedIndexName)) {
                    throw new ServiceException("Polling repair task index does not match current table config, taskId="
                            + task.getTaskId() + ", expectedIndex=" + expectedIndexName);
                }
                if (!indexManager.exists(expectedIndexName)) {
                    throw new ServiceException(
                            "Polling repair physical index does not exist, index=" + expectedIndexName);
                }
                pollingRepair = true;
            }

            TPlusOneImportConfig config = new TPlusOneImportConfig();
            config.setIndexAlias(task.getIndexAlias());
            config.setIndexName(task.getIndexName());
            config.setTableName(task.getTableName());
            config.setImportDate(importDate);
            if (resumeTask) {
                // 续跑任务从最后连续完成批次的安全断点后继续读取
                config.setStartId(task.getLastSuccessId());
            }

            // 根据源表名反查配置，复用mapping、whereSql、主键字段和历史索引保留策略
            config.setMappingFile(table.getMappingFile());
            config.setWhereSql(table.getWhereSql());
            config.setIdColumn(table.getIdColumn());
            config.setDtColumn(table.getDtColumn());
            config.setDtColumnType(table.getDtColumnType());
            config.setDeleteHistoryIndex(table.isDeleteHistoryIndex());
            config.setReserveDays(table.getReserveDays());

            // 先将任务置为RUNNING，再启动真实导入，避免进程中断后任务状态仍停留在PENDING
            task.setStatus(SanoImportTaskStatus.RUNNING.name());
            task.setRunCount(task.getRunCount() + 1);
            task.setStartedAt(LocalDateTime.now());
            task.setFinishedAt(null);
            task.setLastError(null);
            importTaskService.updateTask(task);
            drainCoordinator.onTPlusOneTaskRunning(task);

            // 索引存在时复用；索引缺失时只有安全断点为0才允许创建。
            statistics = importService.importData(config, deadlineMillis);

            if (statistics.isTimeoutPartial()) {
                // 到达运行上限时只保存连续批次安全断点，下一轮从该ID之后继续读取
                task.setStatus(SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
                task.setTotalCount(statistics.getTotal().get());
                task.setSuccessCount(task.getSuccessCount() + statistics.getSuccess().get());
                task.setFailedCount(task.getFailedCount() + statistics.getFailed().get());
                task.setLastSuccessId(statistics.getLastSuccessId());
                task.setFinishedAt(LocalDateTime.now());
                terminalStatus = SanoImportTaskStatus.TIMEOUT_PARTIAL;
                terminalUpdateAttempted = true;
                importTaskService.updateTask(task);
                persistenceSafe = true;
                notifyService.notifyTimeoutPartial(task, statistics);
                return true;
            }

            // 正常完成后记录累计结果并通知，alias切换已在导入服务内部完成
            task.setStatus(SanoImportTaskStatus.SUCCESS.name());
            task.setTotalCount(statistics.getTotal().get());
            task.setSuccessCount(task.getSuccessCount() + statistics.getSuccess().get());
            task.setFailedCount(task.getFailedCount() + statistics.getFailed().get());
            task.setLastSuccessId(statistics.getLastSuccessId());
            task.setFinishedAt(LocalDateTime.now());
            terminalStatus = SanoImportTaskStatus.SUCCESS;
            terminalUpdateAttempted = true;
            importTaskService.updateTask(task);
            persistenceSafe = true;
            notifyService.notifySuccess(task, statistics);
            try {
                // 普通T+1和Polling历史修复共用独立异步对账；提交失败不能覆盖已持久化的SUCCESS
                reconcileStatisticsService.reconcile(table, importDate);
            } catch (RuntimeException reconcileError) {
                log.warn("===> ES-Import reconcile submit failed after task success. "
                                + "taskId={}, pollingRepair={}, error={}",
                        task.getTaskId(), pollingRepair,
                        reconcileError.getMessage(), reconcileError);
            }
        } catch (Exception e) {
            terminalError = StringUtils.left(e.getMessage(), 1000);
            terminalStatus = SanoImportTaskStatus.FAILED;

            if (!terminalUpdateAttempted) {
                try {
                    task.setStatus(SanoImportTaskStatus.FAILED.name());
                    task.setLastError(terminalError);
                    task.setFinishedAt(LocalDateTime.now());
                    terminalUpdateAttempted = true;
                    importTaskService.updateTask(task);
                    persistenceSafe = true;
                } catch (Exception persistError) {
                    // 无法确认FAILED是否持久化时，drain必须报告FAILED，不能把该任务视为安全停机
                    persistenceSafe = false;
                    terminalError = StringUtils.left("Task state persistence failed: " + persistError.getMessage(), 1000);
                    log.error("===> ES-Import failed task state could not be persisted. taskId={}, error={}",
                            task.getTaskId(), persistError.getMessage(), persistError);
                }
            } else {
                // SUCCESS/TIMEOUT_PARTIAL更新结果不确定时不再用FAILED覆盖，避免把实际已提交的终态反向改写
                persistenceSafe = false;
                log.error("===> ES-Import terminal task state persistence is uncertain. taskId={}, attemptedStatus={}, error={}",
                        task.getTaskId(), task.getStatus(), e.getMessage(), e);
            }
            notifyService.notifyFailed(task, statistics, e);

            // 当前任务失败后继续执行后续任务，避免单表异常阻塞整个调度批次
            log.error("===> ES-Import pending task failed. taskId={}, alias={}, table={}, date={}, error={}",
                    task.getTaskId(), task.getIndexAlias(), task.getTableName(), task.getImportDate(), e.getMessage(), e);
        } finally {
            drainCoordinator.finishTPlusOneTask(
                    task, terminalStatus, statistics, persistenceSafe, terminalError);
        }
        return true;
    }

    /**
     * cancel后立即、且只重新执行本次drain产生的TIMEOUT_PARTIAL任务
     *
     * <p>如果旧dispatcher尚未退出，本方法暂时不领取任务；旧dispatcher的finally会再次调用，
     * 从而封住cancel与线程退出之间的竞态窗口</p>
     */
    public void resumeAfterDrainCancel() {
        if (!isEnabled()) {
            return;
        }
        List<String> taskIds = drainCoordinator.tryClaimCancelledDrainResumeTasks();
        if (taskIds.isEmpty()) {
            return;
        }

        try {
            esImportExecutor.execute(() -> {
                List<String> remainingTaskIds = new java.util.ArrayList<>(taskIds);
                try {
                    long maxRunMillis = Math.max(1, properties.getTPlusOne().getMaxRunMinutes()) * 60L * 1000L;
                    long deadlineMillis = System.currentTimeMillis() + maxRunMillis;
                    while (!remainingTaskIds.isEmpty() && drainCoordinator.isAcceptingNewWork()) {
                        String taskId = remainingTaskIds.removeFirst();
                        SanoImportTask task = importTaskService.getTask(taskId).orElse(null);
                        if (task == null || !StringUtils.equals(
                                task.getStatus(), SanoImportTaskStatus.TIMEOUT_PARTIAL.name())) {
                            continue;
                        }
                        if (!executeTask(task, deadlineMillis)) {
                            remainingTaskIds.addFirst(taskId);
                            break;
                        }
                    }
                } finally {
                    if (!remainingTaskIds.isEmpty()) {
                        drainCoordinator.returnCancelledDrainResumeTasks(remainingTaskIds);
                    } else {
                        drainCoordinator.onTPlusOneDispatcherStopped();
                    }
                    // 当前恢复任务又被新的drain打断并随后cancel时，继续尝试领取新一轮精确任务
                    resumeAfterDrainCancel();
                }
            });
        } catch (Exception e) {
            drainCoordinator.returnCancelledDrainResumeTasks(taskIds);
            log.error("===> ES-Import submit cancelled-drain resume tasks failed. taskIds={}, error={}",
                    taskIds, e.getMessage(), e);
        }
    }

    /**
     * 普通调度器释放运行令牌后，检查是否有cancel等待的精确恢复任务
     */
    private void finishDispatcherAndResumeCancelledDrain() {
        drainCoordinator.onTPlusOneDispatcherStopped();
        resumeAfterDrainCancel();
    }

    /**
     * 校验T+1能力同时满足实例角色和功能总开关，供手工入口复用
     */
    public void requireEnabled() {
        serviceModeManager.requireSyncEnabled();
        if (!properties.getTPlusOne().isEnabled()) {
            throw new ServiceException(503,
                    "T+1 import task is disabled by sano.import.t-plus-one.enabled");
        }
    }

    /**
     * T+1任务同时受实例服务模式和功能总开关控制
     */
    private boolean isEnabled() {
        return serviceModeManager.isSyncEnabled() && properties.getTPlusOne().isEnabled();
    }
}
