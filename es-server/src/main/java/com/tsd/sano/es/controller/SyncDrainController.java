package com.tsd.sano.es.controller;

import com.tsd.sano.es.core.result.ResultVO;
import com.tsd.sano.es.importer.task.EsImportTask;
import com.tsd.sano.es.sync.service.SyncDrainCoordinator;
import org.springframework.web.bind.annotation.*;

/**
 * 部署流程使用的统一同步排空接口。
 *
 * <p>接口返回值分别展示协调器运行态、当前drain结果、T+1持久任务摘要、流水线运行态
 * 和共享资源快照。polling接入后由同一协调器扩展，不会复用T+1任务状态冒充checkpoint。</p>
 */
@RestController
@RequestMapping("/internal/sync")
public class SyncDrainController {

    private final SyncDrainCoordinator drainCoordinator;
    private final EsImportTask importTask;

    /**
     * 注入统一排空协调器和T+1取消后恢复入口。
     */
    public SyncDrainController(SyncDrainCoordinator drainCoordinator,
                               EsImportTask importTask) {
        this.drainCoordinator = drainCoordinator;
        this.importTask = importTask;
    }

    /**
     * 幂等启动排空，停止新Reader和新T+1任务，但允许已有Bulk继续取得许可并完成。
     */
    @PostMapping("/drain")
    public ResultVO<SyncDrainCoordinator.DrainStatusSnapshot> drain() {
        return ResultVO.success(drainCoordinator.startDrain());
    }

    /**
     * 查询本次drain结果以及当前T+1流水线、Bulk许可和内存预算状态。
     */
    @GetMapping("/drain/status")
    public ResultVO<SyncDrainCoordinator.DrainStatusSnapshot> status() {
        return ResultVO.success(drainCoordinator.status());
    }

    /**
     * 取消排空并立即重投本次drain产生的TIMEOUT_PARTIAL任务。
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
