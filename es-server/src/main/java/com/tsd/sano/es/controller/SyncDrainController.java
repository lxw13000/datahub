package com.tsd.sano.es.controller;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.task.EsImportTask;
import com.tsd.sano.es.polling.service.PollingSyncCoordinator;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import com.tsd.sano.es.sync.service.SyncDrainCoordinator;
import com.tsd.sano.es.sync.service.SyncStatusService;
import org.springframework.web.bind.annotation.*;

/**
 * 部署流程使用的统一同步排空接口
 *
 * <p>接口返回值分别展示协调器运行态、当前drain结果、T+1持久任务摘要、流水线运行态
 * 和共享资源快照polling接入后由同一协调器扩展，不会复用T+1任务状态冒充checkpoint</p>
 */
@RestController
@RequestMapping("/internal/sync")
public class SyncDrainController {

    private final SyncDrainCoordinator drainCoordinator;
    private final EsImportTask importTask;
    private final SyncStatusService statusService;
    private final EsImportProperties importProperties;
    private final EsServiceModeManager serviceModeManager;
    private final PollingSyncCoordinator pollingCoordinator;

    /**
     * 注入统一排空协调器和T+1取消后恢复入口
     */
    public SyncDrainController(SyncDrainCoordinator drainCoordinator,
                               EsImportTask importTask,
                               SyncStatusService statusService,
                               EsImportProperties importProperties,
                               EsServiceModeManager serviceModeManager,
                               PollingSyncCoordinator pollingCoordinator) {
        this.drainCoordinator = drainCoordinator;
        this.importTask = importTask;
        this.statusService = statusService;
        this.importProperties = importProperties;
        this.serviceModeManager = serviceModeManager;
        this.pollingCoordinator = pollingCoordinator;
    }

    /**
     * 幂等启动排空，停止新Reader和新T+1任务，但允许已有Bulk继续取得许可并完成
     */
    @PostMapping("/drain")
    public ResultVO<SyncDrainCoordinator.DrainStatusSnapshot> drain() {
        return ResultVO.success(drainCoordinator.startDrain());
    }

    /**
     * 查询本次drain结果以及当前T+1流水线、Bulk许可和内存预算状态
     */
    @GetMapping("/drain/status")
    public ResultVO<SyncDrainCoordinator.DrainStatusSnapshot> status() {
        return ResultVO.success(drainCoordinator.status());
    }

    /**
     * 分层查询持久业务进度、Worker内存态、T+1和drain结果
     */
    @GetMapping("/status")
    public ResultVO<SyncStatusService.Snapshot> syncStatus() {
        return ResultVO.success(statusService.status());
    }

    /**
     * 人工暂停一张Polling表；本实例Worker先保存查询游标，随后持久化PAUSED
     */
    @PostMapping("/polling/{tableName}/pause")
    public ResultVO<String> pausePollingTable(@PathVariable String tableName) {
        serviceModeManager.requireSyncEnabled();
        boolean configured = importProperties.getPollingTables().stream()
                .anyMatch(table -> table.getTableName().equals(tableName));
        if (!configured) {
            throw new ServiceException(
                    "ES sync table is disabled or mode mismatch, tableName=" + tableName
                            + ", expectedMode=POLLING");
        }
        boolean paused = pollingCoordinator.pauseTable(tableName);
        return ResultVO.resultMsg(paused, "暂停Polling表");
    }

    /**
     * 人工恢复一张PAUSED表；协调器在后续扫描中重新启动Worker
     */
    @PostMapping("/polling/{tableName}/resume")
    public ResultVO<String> resumePollingTable(@PathVariable String tableName) {
        serviceModeManager.requireSyncEnabled();
        boolean configured = importProperties.getPollingTables().stream()
                .anyMatch(table -> table.getTableName().equals(tableName));
        if (!configured) {
            throw new ServiceException(
                    "ES sync table is disabled or mode mismatch, tableName=" + tableName
                            + ", expectedMode=POLLING");
        }
        boolean resumed = pollingCoordinator.resumeTable(tableName);
        return ResultVO.resultMsg(resumed, "恢复Polling表");
    }

    /**
     * 取消排空并立即重投本次drain产生的TIMEOUT_PARTIAL任务
     *
     * @param operationId 可选的操作ID；提供时用于防止旧部署脚本取消新一轮drain
     */
    @PostMapping("/drain/cancel")
    public ResultVO<SyncDrainCoordinator.DrainStatusSnapshot> cancel(
            @RequestParam(required = false) String operationId) {
        SyncDrainCoordinator.DrainStatusSnapshot snapshot = drainCoordinator.cancelDrain(operationId);
        importTask.resumeAfterDrainCancel();
        return ResultVO.success(snapshot);
    }
}
