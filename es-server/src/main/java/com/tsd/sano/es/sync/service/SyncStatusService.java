package com.tsd.sano.es.sync.service;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.polling.model.SyncCheckpoint;
import com.tsd.sano.es.polling.service.PollingSyncCoordinator;
import com.tsd.sano.es.polling.service.SyncCheckpointService;
import com.tsd.sano.es.polling.service.PollingTableWorker;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 汇总T+1、Polling和drain状态的只读服务。
 *
 * <p>Polling状态明确分为持久业务进度和当前JVM Worker运行态，避免用内存游标
 * 冒充checkpoint，也不把协调器生命周期当作单表业务状态。</p>
 */
@Service
public class SyncStatusService {

    private final EsImportProperties properties;
    private final EsServiceModeManager serviceModeManager;
    private final SyncCheckpointService checkpointService;
    private final PollingSyncCoordinator pollingCoordinator;
    private final SyncDrainCoordinator drainCoordinator;

    /**
     * 注入配置、checkpoint、Polling协调器和统一drain状态来源。
     */
    public SyncStatusService(
            EsImportProperties properties,
            EsServiceModeManager serviceModeManager,
            SyncCheckpointService checkpointService,
            PollingSyncCoordinator pollingCoordinator,
            SyncDrainCoordinator drainCoordinator
    ) {
        this.properties = properties;
        this.serviceModeManager = serviceModeManager;
        this.checkpointService = checkpointService;
        this.pollingCoordinator = pollingCoordinator;
        this.drainCoordinator = drainCoordinator;
    }

    /**
     * 查询当前实例和所有已配置Polling表的分层状态。
     */
    public Snapshot status() {
        PollingSyncCoordinator.Snapshot coordinator = pollingCoordinator.snapshot();
        List<PollingTableStatus> tables = new ArrayList<>();
        for (EsImportProperties.TableConfig table : properties.getPollingTables()) {
            SyncCheckpoint checkpoint = null;
            String checkpointError = null;
            try {
                checkpoint = checkpointService.find(table.getTableName()).orElse(null);
                if (checkpoint == null) {
                    checkpointError = "Polling checkpoint does not exist";
                }
            } catch (RuntimeException error) {
                checkpointError = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
            }
            PollingTableWorker.Snapshot worker = coordinator.workers().get(table.getTableName());

            PersistentBusinessState persistent = checkpoint == null ? null : new PersistentBusinessState(
                    checkpoint.getSyncDate(),
                    checkpoint.getLastId(),
                    checkpoint.getStatus(),
                    checkpoint.getLastError(),
                    checkpoint.getLastStartedAt(),
                    checkpoint.getLastStoppedAt(),
                    checkpoint.getUpdatedAt()
            );
            WorkerRuntimeState runtime = worker == null ? null : new WorkerRuntimeState(
                    worker.syncDate(),
                    worker.lastId(),
                    worker.stage(),
                    worker.stopRequested(),
                    worker.checkpointSaved(),
                    worker.lastError(),
                    worker.lastActivityAt()
            );
            tables.add(new PollingTableStatus(
                    table.getTableName(),
                    table.getIndexAlias(),
                    persistent,
                    runtime,
                    checkpointError
            ));
        }

        return new Snapshot(
                serviceModeManager.currentMode().name(),
                new PollingCoordinatorState(
                        coordinator.state(),
                        coordinator.running(),
                        coordinator.drainRequested(),
                        coordinator.lastError()
                ),
                List.copyOf(tables),
                drainCoordinator.status()
        );
    }

    /**
     * 状态接口顶层响应。
     */
    public record Snapshot(
            String serviceMode,
            PollingCoordinatorState pollingCoordinator,
            List<PollingTableStatus> pollingTables,
            SyncDrainCoordinator.DrainStatusSnapshot drain
    ) {
    }

    /**
     * 当前JVM Polling协调器生命周期，不代表任何单表业务状态。
     */
    public record PollingCoordinatorState(
            PollingSyncCoordinator.State state,
            boolean running,
            boolean drainRequested,
            String lastError
    ) {
    }

    /**
     * 单张Polling表的持久进度和本机运行状态。
     */
    public record PollingTableStatus(
            String tableName,
            String indexAlias,
            PersistentBusinessState persistent,
            WorkerRuntimeState worker,
            String error
    ) {
    }

    /**
     * ES checkpoint中的持久业务状态和可恢复进度。
     */
    public record PersistentBusinessState(
            LocalDate syncDate,
            long lastId,
            SyncCheckpoint.Status status,
            String lastError,
            Instant lastStartedAt,
            Instant lastStoppedAt,
            Instant updatedAt
    ) {
    }

    /**
     * 当前JVM Worker内存运行态；不存在时表示本实例没有执行该表。
     */
    public record WorkerRuntimeState(
            LocalDate syncDate,
            long lastId,
            PollingTableWorker.Stage stage,
            boolean stopRequested,
            boolean checkpointSaved,
            String lastError,
            Instant lastActivityAt
    ) {
    }
}
