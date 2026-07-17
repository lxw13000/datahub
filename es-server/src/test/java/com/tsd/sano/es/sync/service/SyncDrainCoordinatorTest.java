package com.tsd.sano.es.sync.service;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.EsImportConfig;
import com.tsd.sano.es.importer.pipeline.model.ImportContext;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTask;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTaskStatus;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import com.tsd.sano.es.sync.config.TableSyncMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 统一同步排空协调器状态机测试。
 */
class SyncDrainCoordinatorTest {

    private EsImportProperties importProperties;
    private SyncDrainCoordinator coordinator;

    @BeforeEach
    void setUp() {
        importProperties = new EsImportProperties();
        importProperties.getTPlusOne().setEnabled(true);
        importProperties.getTPlusOne().setWorkerCount(1);
        importProperties.getTPlusOne().setQueueCapacity(10);

        importProperties.getCommon().setDrainTimeoutSeconds(60);
        importProperties.getCommon().getWrite().setGlobalBulkConcurrency(3);
        importProperties.getCommon().getWrite().setPollingReservedConcurrency(2);
        importProperties.getCommon().getWrite().setTPlusOneMaxConcurrency(3);
        importProperties.getCommon().getWrite().setGlobalQueueMaxBytes(DataSize.ofMegabytes(8));

        coordinator = new SyncDrainCoordinator(
                new GlobalEsWritePermitManager(importProperties),
                new GlobalSyncMemoryLimiter(importProperties),
                importProperties,
                new EsServiceModeManager("all")
        );
    }

    /**
     * 空闲实例应立即排空，重复调用必须返回同一个operation ID且继续拒绝新调度器。
     */
    @Test
    void shouldDrainIdleInstanceIdempotently() {
        SyncDrainCoordinator.DrainStatusSnapshot first = coordinator.startDrain();
        SyncDrainCoordinator.DrainStatusSnapshot second = coordinator.startDrain();

        assertThat(first.drainResult()).isEqualTo(SyncDrainCoordinator.DrainResult.DRAINED);
        assertThat(second.operationId()).isEqualTo(first.operationId());
        assertThat(second.coordinatorState()).isEqualTo(SyncDrainCoordinator.CoordinatorState.DRAINING);
        assertThat(coordinator.tryStartTPlusOneDispatcher()).isFalse();
    }

    /**
     * 活动任务只有在终态持久化、dispatcher退出且资源归还后才达到DRAINED。
     */
    @Test
    void shouldWaitForPersistedTaskBoundaryAndResumeOnlyAffectedTask() {
        SanoImportTask task = task("coin_20260715");
        assertThat(coordinator.tryStartTPlusOneDispatcher()).isTrue();
        assertThat(coordinator.tryBeginTPlusOneTask(task)).isTrue();

        SyncDrainCoordinator.DrainStatusSnapshot draining = coordinator.startDrain();
        assertThat(draining.drainResult()).isEqualTo(SyncDrainCoordinator.DrainResult.IN_PROGRESS);

        ImportStatistics statistics = new ImportStatistics();
        statistics.setLastSuccessId(120L);
        ImportContext context = context(statistics);
        coordinator.attachTPlusOneContext(config(), context);
        assertThat(context.currentDrainOperationId()).isEqualTo(draining.operationId());
        context.markDrainPartial(draining.operationId());

        task.setStatus(SanoImportTaskStatus.TIMEOUT_PARTIAL.name());
        task.setLastSuccessId(120L);
        coordinator.finishTPlusOneTask(
                task, SanoImportTaskStatus.TIMEOUT_PARTIAL, statistics, true, null);
        assertThat(coordinator.status().drainResult()).isEqualTo(SyncDrainCoordinator.DrainResult.IN_PROGRESS);

        coordinator.onTPlusOneDispatcherStopped();
        assertThat(coordinator.status().drainResult()).isEqualTo(SyncDrainCoordinator.DrainResult.DRAINED);

        coordinator.cancelDrain(draining.operationId());
        assertThat(coordinator.tryClaimCancelledDrainResumeTasks()).containsExactly(task.getTaskId());
        assertThat(coordinator.tryClaimCancelledDrainResumeTasks()).isEmpty();
    }

    /**
     * 已安全持久化的业务失败允许部署继续，但必须明确返回DRAINED_WITH_ERRORS。
     */
    @Test
    void shouldSeparateSafeBusinessFailureFromDrainFailure() {
        SanoImportTask task = task("coin_20260715");
        coordinator.tryStartTPlusOneDispatcher();
        coordinator.tryBeginTPlusOneTask(task);
        coordinator.startDrain();

        task.setStatus(SanoImportTaskStatus.FAILED.name());
        coordinator.finishTPlusOneTask(
                task, SanoImportTaskStatus.FAILED, new ImportStatistics(), true, "mapping failure");
        coordinator.onTPlusOneDispatcherStopped();

        assertThat(coordinator.status().drainResult())
                .isEqualTo(SyncDrainCoordinator.DrainResult.DRAINED_WITH_ERRORS);
    }

    /**
     * 终态无法持久化时即使线程已经退出，也不能报告安全排空。
     */
    @Test
    void shouldFailDrainWhenTerminalTaskStateIsNotPersistent() {
        SanoImportTask task = task("coin_20260715");
        coordinator.tryStartTPlusOneDispatcher();
        coordinator.tryBeginTPlusOneTask(task);
        coordinator.startDrain();

        coordinator.finishTPlusOneTask(
                task, SanoImportTaskStatus.FAILED, new ImportStatistics(), false, "write timeout");
        coordinator.onTPlusOneDispatcherStopped();

        assertThat(coordinator.status().drainResult()).isEqualTo(SyncDrainCoordinator.DrainResult.FAILED);
    }

    /**
     * 队列内存和在途Bulk任一未归还时都不能仅凭“无活动任务”提前报告DRAINED。
     */
    @Test
    void shouldWaitForBothBulkPermitAndMemoryReservation() {
        GlobalEsWritePermitManager permitManager = new GlobalEsWritePermitManager(importProperties);
        GlobalSyncMemoryLimiter memoryLimiter = new GlobalSyncMemoryLimiter(importProperties);
        coordinator = new SyncDrainCoordinator(
                permitManager, memoryLimiter, importProperties,
                new EsServiceModeManager("all"));

        GlobalEsWritePermitManager.Permit permit = permitManager.acquire(TableSyncMode.T_PLUS_ONE);
        GlobalSyncMemoryLimiter.Reservation reservation = memoryLimiter.reserve(1024L);
        assertThat(coordinator.startDrain().drainResult())
                .isEqualTo(SyncDrainCoordinator.DrainResult.IN_PROGRESS);

        permit.close();
        assertThat(coordinator.status().drainResult())
                .isEqualTo(SyncDrainCoordinator.DrainResult.IN_PROGRESS);

        reservation.close();
        assertThat(coordinator.status().drainResult())
                .isEqualTo(SyncDrainCoordinator.DrainResult.DRAINED);
    }

    /**
     * query模式保留协调器Bean，但必须从协调器底层拒绝新同步工作和drain操作。
     */
    @Test
    void shouldKeepCoordinatorButRejectSyncWorkInQueryMode() {
        coordinator = new SyncDrainCoordinator(
                new GlobalEsWritePermitManager(importProperties),
                new GlobalSyncMemoryLimiter(importProperties),
                importProperties,
                new EsServiceModeManager("query"));

        assertThat(coordinator.tryStartTPlusOneDispatcher()).isFalse();
        assertThat(coordinator.isAcceptingNewWork()).isFalse();
        assertThat(coordinator.status().serviceMode()).isEqualTo("QUERY");
        assertThat(coordinator.status().tPlusOne().enabled()).isFalse();
        assertThatThrownBy(coordinator::startDrain)
                .hasMessageContaining("serviceMode=QUERY");
    }

    private ImportContext context(ImportStatistics statistics) {
        return new ImportContext(config(), statistics, importProperties);
    }

    private EsImportConfig config() {
        EsImportConfig config = new EsImportConfig();
        config.setIndexAlias("coin");
        config.setIndexName("coin_20260715");
        config.setTableName("coin");
        return config;
    }

    private SanoImportTask task(String taskId) {
        SanoImportTask task = new SanoImportTask();
        task.setTaskId(taskId);
        task.setIndexAlias("coin");
        task.setIndexName("coin_20260715");
        task.setTableName("coin");
        task.setImportDate("20260715");
        task.setStatus(SanoImportTaskStatus.PENDING.name());
        return task;
    }
}
