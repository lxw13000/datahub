package com.tsd.sano.es.modules.coordination.service;

import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.config.EsServiceModeManager;
import com.tsd.sano.es.modules.polling.model.SyncCheckpoint;
import com.tsd.sano.es.modules.polling.service.PollingCoordinator;
import com.tsd.sano.es.modules.polling.service.PollingIndexService;
import com.tsd.sano.es.modules.polling.service.PollingTableWorker;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 汇总Polling分层状态和统一同步drain快照的只读服务。
 *
 * <p>Polling状态分别展示持久checkpoint和当前JVM Worker运行态；
 * drain快照中同时包含T+1与Polling的排空状态。</p>
 */
@Service
public class SyncStatusService {

    private final EsImportProperties properties;
    private final EsServiceModeManager serviceModeManager;
    private final PollingIndexService pollingIndexService;
    private final PollingCoordinator pollingCoordinator;
    private final SyncDrainCoordinator drainCoordinator;

    /**
     * 注入配置、checkpoint、Polling协调器和统一drain状态来源
     */
    public SyncStatusService(
            EsImportProperties properties,
            EsServiceModeManager serviceModeManager,
            PollingIndexService pollingIndexService,
            PollingCoordinator pollingCoordinator,
            SyncDrainCoordinator drainCoordinator
    ) {
        this.properties = properties;
        this.serviceModeManager = serviceModeManager;
        this.pollingIndexService = pollingIndexService;
        this.pollingCoordinator = pollingCoordinator;
        this.drainCoordinator = drainCoordinator;
    }

    /**
     * 查询当前实例和所有已配置Polling表的分层状态。
     *
     * <p>返回值附带 {@link SyncDrainCoordinator#status()} 的统一排空快照，
     * 通过其中的tPlusOne和polling字段展示两种同步模式的排空状态。</p>
     */
    public Snapshot status() {
        PollingCoordinator.Snapshot coordinator = pollingCoordinator.snapshot();
        List<PollingTableStatus> tables = new ArrayList<>();
        for (SyncTableConfig table : properties.getPollingTables()) {
            SyncCheckpoint checkpoint = null;
            String checkpointError = null;
            try {
                checkpoint = pollingIndexService.find(table.getTableName()).orElse(null);
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
                        coordinator.drainRequested(),
                        coordinator.lastError()
                ),
                List.copyOf(tables),
                // 统一drain快照同时包含T+1和Polling排空状态；本服务不再单独查询T+1任务。
                drainCoordinator.status()
        );
    }

    /**
     * 状态接口顶层响应
     */
    public record Snapshot(
            String serviceMode,
            PollingCoordinatorState pollingCoordinator,
            List<PollingTableStatus> pollingTables,
            /** T+1与Polling共用的排空状态及资源快照。 */
            SyncDrainCoordinator.DrainStatusSnapshot drain
    ) {
    }

    /**
     * 当前JVM Polling协调器生命周期，不代表任何单表业务状态
     */
    public record PollingCoordinatorState(
            PollingCoordinator.State state,
            boolean drainRequested,
            String lastError
    ) {
    }

    /**
     * 单张Polling表的持久进度和本机运行状态
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
     * ES checkpoint中的持久业务状态和可恢复进度
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
     * 当前JVM Worker内存运行态；不存在时表示本实例没有执行该表
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
