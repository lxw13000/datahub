package com.tsd.sano.es.importer.task;

import com.tsd.sano.es.importer.notify.ImportNotifyService;
import com.tsd.sano.es.importer.pipeline.EsImportService;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.taskstore.SanoImportTaskService;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTask;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTaskStatus;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import com.tsd.sano.es.sync.service.GlobalEsWritePermitManager;
import com.tsd.sano.es.sync.service.GlobalSyncMemoryLimiter;
import com.tsd.sano.es.sync.service.SyncDrainCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * T+1调度器的drain门禁和cancel精确恢复测试。
 */
class EsImportTaskDrainTest {

    private EsImportProperties importProperties;
    private EsImportService importService;
    private SanoImportTaskService taskService;
    private ImportNotifyService notifyService;
    private SyncDrainCoordinator coordinator;
    private EsServiceModeManager serviceModeManager;

    @BeforeEach
    void setUp() {
        importProperties = new EsImportProperties();
        importProperties.getTPlusOne().setEnabled(true);
        importProperties.getTPlusOne().setWorkerCount(1);
        importProperties.getTPlusOne().setQueueCapacity(10);
        importProperties.getCommon().getWrite().setGlobalQueueMaxBytes(DataSize.ofMegabytes(8));
        importProperties.setTables(List.of(tableConfig()));
        serviceModeManager = new EsServiceModeManager("all");
        coordinator = new SyncDrainCoordinator(
                new GlobalEsWritePermitManager(importProperties),
                new GlobalSyncMemoryLimiter(importProperties),
                importProperties,
                serviceModeManager
        );

        importService = mock(EsImportService.class);
        taskService = mock(SanoImportTaskService.class);
        notifyService = mock(ImportNotifyService.class);
    }

    /**
     * drain生效后Cron不得查询、创建或启动任何T+1任务。
     */
    @Test
    void shouldNotStartScheduledWorkDuringDrain() {
        coordinator.startDrain();

        importTask().importYesterday();

        verify(taskService, never()).listPendingTasks(anyInt());
        verify(taskService, never()).addTask(any());
    }

    /**
     * query模式保留定时任务Bean，但Cron触发时必须在入口直接返回。
     */
    @Test
    void shouldKeepScheduledTaskBeanButSkipWorkInQueryMode() {
        serviceModeManager = new EsServiceModeManager("query");
        coordinator = mock(SyncDrainCoordinator.class);

        importTask().importYesterday();

        verifyNoInteractions(coordinator, taskService, importService);
    }

    /**
     * cancel只读取并重投本operation产生的TIMEOUT_PARTIAL，不扫描历史待执行任务。
     */
    @Test
    void shouldImmediatelyResumeOnlyTaskInterruptedByCancelledDrain() {
        SanoImportTask task = task();
        coordinator.tryStartTPlusOneDispatcher();
        coordinator.tryBeginTPlusOneTask(task);
        SyncDrainCoordinator.DrainStatusSnapshot drain = coordinator.startDrain();

        ImportStatistics interrupted = new ImportStatistics();
        interrupted.setLastSuccessId(120L);
        interrupted.setStopReason(com.tsd.sano.es.importer.pipeline.model.ImportStopReason.DRAIN);
        interrupted.setStopOperationId(drain.operationId());
        task.setStatus(SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
        task.setLastSuccessId(120L);
        coordinator.finishTPlusOneTask(
                task, SanoImportTaskStatus.TIMEOUT_PARTIAL, interrupted, true, null);
        coordinator.onTPlusOneDispatcherStopped();
        coordinator.cancelDrain(drain.operationId());

        when(taskService.getTask(task.getTaskId())).thenReturn(Optional.of(task));
        ImportStatistics completed = new ImportStatistics();
        completed.setLastSuccessId(200L);
        when(importService.importData(any(), anyLong(), anyBoolean())).thenReturn(completed);

        importTask().resumeAfterDrainCancel();

        verify(taskService).getTask(task.getTaskId());
        verify(taskService, never()).listPendingTasks(anyInt());
        verify(importService).importData(any(), anyLong(), org.mockito.ArgumentMatchers.eq(true));
        verify(taskService, atLeastOnce()).updateTask(task);
    }

    private EsImportTask importTask() {
        Executor directExecutor = Runnable::run;
        return new EsImportTask(
                importProperties,
                importService,
                taskService,
                notifyService,
                directExecutor,
                coordinator,
                serviceModeManager
        );
    }

    private SanoImportTask task() {
        SanoImportTask task = new SanoImportTask();
        task.setTaskId("coin_20260715");
        task.setIndexAlias("coin");
        task.setIndexName("coin_20260715");
        task.setTableName("coin");
        task.setImportDate("20260715");
        task.setStatus(SanoImportTaskStatus.PENDING.name());
        return task;
    }

    private EsImportProperties.TableConfig tableConfig() {
        EsImportProperties.TableConfig table = new EsImportProperties.TableConfig();
        table.setIndexAlias("coin");
        table.setTableName("coin");
        table.setMappingFile("coin.json");
        return table;
    }
}
