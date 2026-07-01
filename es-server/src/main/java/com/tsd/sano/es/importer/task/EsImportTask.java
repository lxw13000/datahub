package com.tsd.sano.es.importer.task;

import com.tsd.sano.es.core.config.EsImportProperties;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.model.ImportStatistics;
import com.tsd.sano.es.importer.service.EsImportService;
import com.tsd.sano.es.search.SanoImportTaskService;
import com.tsd.sano.es.search.model.SanoImportTask;
import com.tsd.sano.es.search.model.SanoImportTaskStatus;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * ES定时导入任务。
 *
 * <p>每天先生成PENDING任务，再按任务索引顺序串行执行待处理任务。</p>
 */
@Component
@ConditionalOnProperty(prefix = "sano.es.import", name = "task-enabled", havingValue = "true")
public class EsImportTask {

    private static final Logger log = LoggerFactory.getLogger(EsImportTask.class);

    /**
     * 导入日期格式，和任务索引中的import_date字段保持一致。
     */
    private static final DateTimeFormatter IMPORT_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final EsImportProperties properties;
    private final EsImportService importService;
    private final SanoImportTaskService importTaskService;

    /**
     * 注入导入配置、导入服务和任务索引服务。
     */
    public EsImportTask(EsImportProperties properties,
                        EsImportService importService,
                        SanoImportTaskService importTaskService) {
        this.properties = properties;
        this.importService = importService;
        this.importTaskService = importTaskService;
    }

    /**
     * 服务启动后修复上次异常退出留下的RUNNING任务。
     */
    @PostConstruct
    public void repairRunningTasksOnStart() {
        repairExpiredRunningTasks();
    }

    /**
     * 每天按配置生成T+1导入任务，并串行执行所有待处理任务。
     */
    @Scheduled(cron = "${sano.es.import.cron:0 30 2 * * ?}")
    public void importYesterday() {
        LocalDate importDate = LocalDate.now().minusDays(1);
        long deadlineMillis = System.currentTimeMillis() + maxRunMillis();
        log.info("===> ES-Import scheduled task start. date={}, maxRunHours={}",
                importDate, properties.getMaxRunHours());

        repairExpiredRunningTasks();
        createPendingTasks(importDate);
        runPendingTasks(deadlineMillis);

        log.info("===> ES-Import scheduled task finished. date={}", importDate);
    }

    /**
     * 将超过运行窗口仍处于RUNNING的任务恢复为TIMEOUT_PARTIAL。
     */
    private void repairExpiredRunningTasks() {
        LocalDateTime expireBefore = LocalDateTime.now().minusHours(Math.max(1, properties.getMaxRunHours()));
        List<SanoImportTask> tasks = importTaskService.listRunningTasks(properties.getTaskFetchLimit());

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
    }

    /**
     * 为当天配置的所有启用表创建PENDING任务。
     */
    private void createPendingTasks(LocalDate importDate) {
        for (EsImportProperties.TableConfig table : properties.getTables()) {
            if (!table.isEnabled()) {
                continue;
            }

            try {
                SanoImportTask task = buildTask(table, importDate);
                importTaskService.addTask(task);
            } catch (Exception e) {
                // 单表任务创建失败不影响其他表落任务，便于后续人工排查和补偿。
                log.error("===> ES-Import create pending task failed. alias={}, table={}, date={}, error={}",
                        table.getIndexAlias(), table.getTableName(), importDate, e.getMessage(), e);
            }
        }
    }

    /**
     * 执行本轮拉取到的待处理任务。
     */
    private void runPendingTasks(long deadlineMillis) {
        while (true) {
            List<SanoImportTask> tasks = importTaskService.listPendingTasks(properties.getTaskFetchLimit());
            if (tasks.isEmpty()) {
                log.info("===> ES-Import no pending task.");
                return;
            }

            for (SanoImportTask task : tasks) {
                if (System.currentTimeMillis() >= deadlineMillis) {
                    // 本轮调度到达运行上限后不再启动新任务，已经完成落库的PENDING任务留待下一轮继续。
                    log.warn("===> ES-Import scheduled task reach max run time, stop starting new task. maxRunHours={}",
                            properties.getMaxRunHours());
                    return;
                }

                executeTask(task, deadlineMillis);
            }
        }
    }

    /**
     * 执行单条任务，并维护任务状态。
     */
    private void executeTask(SanoImportTask task, long deadlineMillis) {
        boolean resumeTask = StringUtils.equals(task.getStatus(), SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
        try {
            task.setStatus(SanoImportTaskStatus.RUNNING.name());
            task.setRunCount(task.getRunCount() + 1);
            task.setStartedAt(LocalDateTime.now());
            task.setFinishedAt(null);
            task.setLastError(null);
            importTaskService.updateTask(task);

            ImportStatistics statistics = importService.importData(toImportConfig(task, resumeTask), deadlineMillis, resumeTask);

            if (statistics.isTimeoutPartial()) {
                task.setStatus(SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
                task.setTotalCount(statistics.getTotal().get());
                task.setSuccessCount(task.getSuccessCount() + statistics.getSuccess().get());
                task.setFailedCount(task.getFailedCount() + statistics.getFailed().get());
                task.setLastSuccessId(statistics.getLastSuccessId());
                task.setFinishedAt(LocalDateTime.now());
                importTaskService.updateTask(task);
                return;
            }

            task.setStatus(SanoImportTaskStatus.SUCCESS.name());
            task.setTotalCount(statistics.getTotal().get());
            task.setSuccessCount(task.getSuccessCount() + statistics.getSuccess().get());
            task.setFailedCount(task.getFailedCount() + statistics.getFailed().get());
            task.setLastSuccessId(statistics.getLastSuccessId());
            task.setFinishedAt(LocalDateTime.now());
            importTaskService.updateTask(task);
        } catch (Exception e) {
            task.setStatus(SanoImportTaskStatus.FAILED.name());
            task.setLastError(StringUtils.left(e.getMessage(), 1000));
            task.setFinishedAt(LocalDateTime.now());
            importTaskService.updateTask(task);

            // 当前任务失败后继续执行后续任务，避免单表异常阻塞整个调度批次。
            log.error("===> ES-Import pending task failed. taskId={}, alias={}, table={}, date={}, error={}",
                    task.getTaskId(), task.getIndexAlias(), task.getTableName(), task.getImportDate(), e.getMessage(), e);
        }
    }

    /**
     * 根据表配置构建任务索引文档。
     */
    private SanoImportTask buildTask(EsImportProperties.TableConfig table, LocalDate importDate) {
        String indexAlias = table.getIndexAlias();
        String tableName = StringUtils.defaultIfBlank(table.getTableName(), indexAlias);
        String importDateText = IMPORT_DATE_FORMATTER.format(importDate);

        SanoImportTask task = new SanoImportTask();
        task.setTableName(tableName);
        task.setIndexAlias(indexAlias);
        task.setIndexName(indexAlias + "_" + importDateText);
        task.setImportDate(importDateText);
        task.setStatus(SanoImportTaskStatus.PENDING.name());
        task.setLastSuccessId(0L);
        return task;
    }

    /**
     * 将任务文档转换为导入流程配置。
     */
    private EsImportConfig toImportConfig(SanoImportTask task, boolean resumeTask) {
        EsImportConfig config = new EsImportConfig();
        config.setIndexAlias(task.getIndexAlias());
        config.setIndexName(task.getIndexName());
        config.setTableName(task.getTableName());
        config.setImportDate(LocalDate.parse(task.getImportDate(), IMPORT_DATE_FORMATTER));
        if (resumeTask) {
            // 续跑任务从已确认写入ES的最大ID后继续读取。
            config.setStartId(task.getLastSuccessId());
        }

        // 根据业务alias反查表配置，复用mapping、whereSql和主键字段等配置。
        properties.getTables().stream()
                .filter(table -> StringUtils.equals(table.getIndexAlias(), task.getIndexAlias()))
                .findFirst()
                .ifPresent(table -> {
                    config.setMappingFile(table.getMappingFile());
                    config.setWhereSql(table.getWhereSql());
                    config.setIdColumn(table.getIdColumn());
                    config.setDtColumn(table.getDtColumn());
                });
        return config;
    }

    /**
     * 计算本轮调度最大运行毫秒数。
     */
    private long maxRunMillis() {
        return Math.max(1, properties.getMaxRunHours()) * 60L * 60L * 1000L;
    }
}
