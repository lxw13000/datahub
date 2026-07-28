package com.tsd.sano.es.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.tplusone.service.SanoImportTaskService;
import com.tsd.sano.es.modules.polling.model.SyncCheckpoint;
import com.tsd.sano.es.modules.polling.service.PollingCoordinator;
import com.tsd.sano.es.modules.polling.service.PollingIndexService;
import com.tsd.sano.es.modules.polling.service.PollingTableWorker;
import com.tsd.sano.es.modules.config.EsServiceMode;
import com.tsd.sano.es.modules.config.EsServiceModeManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 面向容器编排和部署脚本的严格就绪检查。
 *
 * <p>/health只说明Web进程存活；/ready还会按server-mode验证ES访问能力、
 * T+1任务索引，以及Polling内部索引、协调器和本机Worker。单表业务暂停不会让
 * 整个查询实例失活，但会由同步状态接口展示。</p>
 */
@RestController
public class ReadyController {

    /** ES连接和业务Alias查询检查入口。 */
    private final ElasticsearchClient elasticsearchClient;

    /** 当前启用的T+1、Polling能力和表目录配置。 */
    private final EsImportProperties importProperties;

    /** T+1持久任务索引检查入口。 */
    private final SanoImportTaskService importTaskService;

    /** 当前实例all/query运行模式。 */
    private final EsServiceModeManager serviceModeManager;

    /** Polling checkpoint索引及持久状态检查入口。 */
    private final PollingIndexService pollingIndexService;

    /** Polling协调器和Worker运行状态检查入口。 */
    private final PollingCoordinator pollingCoordinator;

    /**
     * 注入查询链路、T+1任务索引及服务模式检查所需组件。
     */
    public ReadyController(ElasticsearchClient elasticsearchClient,
                           EsImportProperties importProperties,
                           SanoImportTaskService importTaskService,
                           EsServiceModeManager serviceModeManager,
                           PollingIndexService pollingIndexService,
                           PollingCoordinator pollingCoordinator) {
        this.elasticsearchClient = elasticsearchClient;
        this.importProperties = importProperties;
        this.importTaskService = importTaskService;
        this.serviceModeManager = serviceModeManager;
        this.pollingIndexService = pollingIndexService;
        this.pollingCoordinator = pollingCoordinator;
    }

    /**
     * 返回与当前实例职责一致的就绪结果；未就绪时使用HTTP 503阻止部署继续。
     */
    @GetMapping("/ready")
    public ResponseEntity<ReadyStatus> ready() {
        EsServiceMode serviceMode = serviceModeManager.currentMode();
        List<String> details = new ArrayList<>();
        boolean queryReady;
        boolean syncReady = !serviceMode.isSyncEnabled();

        // all和query模式都承担查询职责，因此两种模式都必须通过相同的真实Alias查询检查。
        try {
            elasticsearchClient.info();
            List<SyncTableConfig> tPlusOneTables = importProperties.getTPlusOneTables();
            List<SyncTableConfig> pollingTables = importProperties.getPollingTables();
            Optional<SyncTableConfig> smokeTable = !tPlusOneTables.isEmpty()
                    ? Optional.of(tPlusOneTables.getFirst())
                    : pollingTables.stream().findFirst();
            if (smokeTable.isPresent()) {
                String indexAlias = smokeTable.get().getIndexAlias();
                // 仅连接ES并不能证明业务Alias可查询；size=0可验证Alias、权限和查询链路，且不拉取文档。
                elasticsearchClient.search(builder -> builder.index(indexAlias).size(0), Object.class);
                details.add("QUERY_ALIAS_READY: " + indexAlias);
            } else {
                details.add("QUERY_ES_READY_NO_ENABLED_TABLE");
            }
            queryReady = true;
        } catch (Exception e) {
            queryReady = false;
            details.add("QUERY_ES_UNAVAILABLE: " + safeMessage(e));
        }

        if (serviceMode.isSyncEnabled()) {
            syncReady = true;
            boolean hasTPlusOneTables = !importProperties.getTPlusOneTables().isEmpty();
            boolean hasPollingTables = !importProperties.getPollingTables().isEmpty();

            if (hasTPlusOneTables && importProperties.getTPlusOne().isEnabled()) {
                try {
                    boolean taskIndexReady = importTaskService.exists();
                    syncReady = taskIndexReady;
                    details.add(taskIndexReady ? "T_PLUS_ONE_TASK_INDEX_READY" : "T_PLUS_ONE_TASK_INDEX_MISSING");
                } catch (Exception e) {
                    syncReady = false;
                    details.add("T_PLUS_ONE_UNAVAILABLE: " + safeMessage(e));
                }
            } else if (hasTPlusOneTables) {
                // 总开关关闭属于显式停用，不伪装为正在同步，也不阻止query-only接管。
                details.add("T_PLUS_ONE_DISABLED_BY_GLOBAL_SWITCH");
            }

            if (hasPollingTables && importProperties.getPolling().isEnabled()) {
                syncReady = checkPollingReadiness(details) && syncReady;
            } else if (hasPollingTables) {
                details.add("POLLING_DISABLED_BY_GLOBAL_SWITCH");
            }
        }

        boolean ready = queryReady && syncReady;
        ReadyStatus status = new ReadyStatus(
                ready, serviceMode, queryReady, syncReady, List.copyOf(details), Instant.now());
        return ResponseEntity.status(ready ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(status);
    }

    /**
     * 检查Polling内部索引、协调器以及每张RUNNING表是否具备本机执行能力。
     */
    private boolean checkPollingReadiness(List<String> details) {
        try {
            if (!pollingIndexService.checkpointIndexExists()) {
                details.add("POLLING_CHECKPOINT_INDEX_MISSING");
                return false;
            }

            PollingCoordinator.Snapshot coordinator = pollingCoordinator.snapshot();
            if (coordinator.state() != PollingCoordinator.State.RUNNING) {
                details.add("POLLING_COORDINATOR_NOT_READY: state=" + coordinator.state()
                        + ", error=" + coordinator.lastError());
                return false;
            }

            boolean ready = true;
            for (SyncTableConfig table : importProperties.getPollingTables()) {
                Optional<SyncCheckpoint> checkpointOptional = pollingIndexService.find(table.getTableName());
                if (checkpointOptional.isEmpty()) {
                    ready = false;
                    details.add("POLLING_CHECKPOINT_MISSING: " + table.getTableName());
                    continue;
                }

                SyncCheckpoint checkpoint = checkpointOptional.get();
                if (checkpoint.getStatus() == SyncCheckpoint.Status.PAUSED) {
                    // PAUSED是单表业务状态，需要运维处理，但不能拖垮整个查询服务的ready。
                    details.add("POLLING_TABLE_PAUSED: " + table.getTableName()
                            + ", error=" + checkpoint.getLastError());
                    continue;
                }
                if (checkpoint.getStatus() != SyncCheckpoint.Status.RUNNING) {
                    ready = false;
                    details.add("POLLING_TABLE_STATUS_INVALID: " + table.getTableName()
                            + ", status=" + checkpoint.getStatus());
                    continue;
                }

                PollingTableWorker.Snapshot worker = coordinator.workers().get(table.getTableName());
                if (worker != null) {
                    details.add("POLLING_TABLE_WORKER_RUNNING: " + table.getTableName());
                } else if (coordinator.workers().size()
                        >= importProperties.getPolling().getMaxActiveTables()) {
                    // 达到配置的表级并发上限时，剩余RUNNING表等待空闲槽位属于正常调度状态。
                    details.add("POLLING_TABLE_WAITING_FOR_SLOT: " + table.getTableName());
                } else {
                    ready = false;
                    details.add("POLLING_TABLE_WORKER_MISSING: " + table.getTableName());
                }
            }
            return ready;
        } catch (Exception error) {
            details.add("POLLING_UNAVAILABLE: " + safeMessage(error));
            return false;
        }
    }

    /**
     * 提取适合返回到就绪详情中的异常摘要。
     */
    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    /**
     * 就绪状态明确区分查询职责和同步职责，便于部署脚本定位失败阶段。
     */
    public record ReadyStatus(boolean ready,
                              EsServiceMode serviceMode,
                              boolean queryReady,
                              boolean syncReady,
                              List<String> details,
                              Instant checkedAt) {
    }
}
