package com.tsd.sano.es.sync.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.EsImportConfig;
import com.tsd.sano.es.importer.pipeline.model.ImportContext;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.pipeline.model.ImportStopReason;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTask;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTaskStatus;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 当前同步实例的统一部署排空协调器。
 *
 * <p>该类只保存当前JVM的协调器状态和drain结果，不覆盖 T+1 任务或未来 polling
 * checkpoint中的持久业务状态。任务启动、drain切换和cancel恢复都在同一监视器下
 * 线性化，避免已经报告DRAINED后又启动新任务。</p>
 */
@Component
public class SyncDrainCoordinator {

    private final GlobalEsWritePermitManager permitManager;
    private final GlobalSyncMemoryLimiter memoryLimiter;
    private final EsImportProperties importProperties;
    private final EsServiceModeManager serviceModeManager;
    private final long drainTimeoutMillis;

    /** 协调器运行态；DRAINING在cancel前始终拒绝新同步工作。 */
    private CoordinatorState coordinatorState = CoordinatorState.RUNNING;

    /** 当前或最近一次drain操作。 */
    private DrainOperation operation;

    /** T+1调度器是否仍在创建、扫描或执行任务。 */
    private boolean tPlusOneDispatcherActive;

    /** 当前唯一的T+1活动任务。现有调度器按任务串行执行。 */
    private TPlusOneTaskRuntime activeTPlusOneTask;

    /** 最近一次持久任务摘要，用于活动任务结束后的status观察。 */
    private PersistentTaskSnapshot lastPersistentTask;

    /** cancel后等待精确重投的、本次drain产生的任务ID。 */
    private final Set<String> pendingResumeTaskIds = new LinkedHashSet<>();

    /**
     * 注入排空判定所需的共享资源快照、同步配置和服务模式门禁。
     */
    public SyncDrainCoordinator(GlobalEsWritePermitManager permitManager,
                                 GlobalSyncMemoryLimiter memoryLimiter,
                                 EsImportProperties importProperties,
                                 EsServiceModeManager serviceModeManager) {
        this.permitManager = permitManager;
        this.memoryLimiter = memoryLimiter;
        this.importProperties = importProperties;
        this.serviceModeManager = serviceModeManager;
        this.drainTimeoutMillis = Math.max(1,
                importProperties.getCommon().getDrainTimeoutSeconds()) * 1000L;
    }

    /**
     * 幂等启动全局drain。返回后不会再有新的T+1调度器或任务越过启动门禁。
     */
    public synchronized DrainStatusSnapshot startDrain() {
        serviceModeManager.requireSyncEnabled();
        if (coordinatorState == CoordinatorState.DRAINING) {
            evaluateDrainLocked();
            return snapshotLocked();
        }
        if (activeTPlusOneTask != null
                && operation != null
                && operation.result == DrainResult.CANCELLED) {
            throw new ServiceException("Cancelled sync drain is still reaching a safe T+1 task boundary");
        }

        coordinatorState = CoordinatorState.DRAINING;
        operation = new DrainOperation(UUID.randomUUID().toString(), System.currentTimeMillis());
        if (activeTPlusOneTask != null && activeTPlusOneTask.context != null) {
            activeTPlusOneTask.context.requestDrainStop(operation.operationId);
        }
        evaluateDrainLocked();
        return snapshotLocked();
    }

    /**
     * 取消当前drain并恢复接收新工作。operationId为空时取消当前操作；传值时必须匹配。
     */
    public synchronized DrainStatusSnapshot cancelDrain(String operationId) {
        serviceModeManager.requireSyncEnabled();
        if (operation == null) {
            throw new ServiceException("No sync drain operation can be cancelled");
        }
        if (operationId != null && !operationId.isBlank() && !operation.operationId.equals(operationId.trim())) {
            throw new ServiceException("Sync drain operation id does not match current operation");
        }
        if (operation.result == DrainResult.CANCELLED) {
            return snapshotLocked();
        }

        coordinatorState = CoordinatorState.RUNNING;
        operation.result = DrainResult.CANCELLED;
        operation.cancelRequested = true;
        operation.completedAtMillis = System.currentTimeMillis();
        pendingResumeTaskIds.addAll(operation.interruptedTaskIds);
        return snapshotLocked();
    }

    /**
     * 返回当前状态，并在读取前重新计算排空完成或超时结果。
     */
    public synchronized DrainStatusSnapshot status() {
        evaluateDrainLocked();
        return snapshotLocked();
    }

    /**
     * 原子注册一个普通T+1调度器。drain先发生时返回false。
     */
    public synchronized boolean tryStartTPlusOneDispatcher() {
        if (!serviceModeManager.isSyncEnabled()
                || coordinatorState != CoordinatorState.RUNNING
                || tPlusOneDispatcherActive) {
            return false;
        }
        tPlusOneDispatcherActive = true;
        return true;
    }

    /**
     * 调度器退出后重新评估drain；任务终态持久化必须先于该调用。
     */
    public synchronized void onTPlusOneDispatcherStopped() {
        tPlusOneDispatcherActive = false;
        evaluateDrainLocked();
    }

    /**
     * 在drain门禁内执行新增任务记录等短操作，确保drain返回后不会再创建新工作。
     */
    public synchronized <T> T callIfAcceptingNewWork(Supplier<T> action, T rejectedValue) {
        if (!serviceModeManager.isSyncEnabled() || coordinatorState != CoordinatorState.RUNNING) {
            return rejectedValue;
        }
        return action.get();
    }

    /**
     * 在任务落RUNNING前注册活动任务；与startDrain共享同一原子边界。
     */
    public synchronized boolean tryBeginTPlusOneTask(SanoImportTask task) {
        if (!serviceModeManager.isSyncEnabled()
                || coordinatorState != CoordinatorState.RUNNING
                || !tPlusOneDispatcherActive) {
            return false;
        }
        if (activeTPlusOneTask != null) {
            throw new IllegalStateException("Only one T+1 task can run in the current dispatcher");
        }
        activeTPlusOneTask = new TPlusOneTaskRuntime(task);
        return true;
    }

    /**
     * 任务状态成功持久化为RUNNING后刷新管理接口摘要。
     */
    public synchronized void onTPlusOneTaskRunning(SanoImportTask task) {
        if (matchesActiveTask(task.getTaskId())) {
            activeTPlusOneTask.taskStatus = SanoImportTaskStatus.RUNNING.name();
        }
    }

    /**
     * 将Reader/Bulk上下文挂到当前任务；drain已先发生时立即把停止信号送达上下文。
     */
    public synchronized void attachTPlusOneContext(EsImportConfig config, ImportContext context) {
        if (activeTPlusOneTask == null || !activeTPlusOneTask.indexName.equals(config.getIndexName())) {
            throw new IllegalStateException("T+1 import context does not match the active task");
        }
        activeTPlusOneTask.context = context;
        if (coordinatorState == CoordinatorState.DRAINING && operation != null) {
            context.requestDrainStop(operation.operationId);
        }
    }

    /**
     * 活动任务达到持久终态后注销运行令牌，并记录本次drain是否需要cancel重投。
     */
    public synchronized void finishTPlusOneTask(SanoImportTask task,
                                                SanoImportTaskStatus terminalStatus,
                                                ImportStatistics statistics,
                                                boolean persistenceSafe,
                                                String error) {
        if (!matchesActiveTask(task.getTaskId())) {
            return;
        }

        long safeCheckpointId = statistics == null ? task.getLastSuccessId() : statistics.getLastSuccessId();
        String status = terminalStatus == null ? task.getStatus() : terminalStatus.name();
        lastPersistentTask = new PersistentTaskSnapshot(
                task.getTaskId(), task.getIndexAlias(), task.getTableName(), task.getImportDate(),
                status, safeCheckpointId, persistenceSafe, error
        );

        if (operation != null) {
            if (!persistenceSafe && operation.result == DrainResult.IN_PROGRESS) {
                operation.result = DrainResult.FAILED;
                operation.error = "T+1 terminal task state could not be persisted: " + task.getTaskId();
                operation.completedAtMillis = System.currentTimeMillis();
            } else if (terminalStatus == SanoImportTaskStatus.FAILED) {
                operation.hadBusinessErrors = true;
            }

            if (statistics != null
                    && statistics.getStopReason() == ImportStopReason.DRAIN
                    && operation.operationId.equals(statistics.getStopOperationId())
                    && terminalStatus == SanoImportTaskStatus.TIMEOUT_PARTIAL
                    && persistenceSafe) {
                operation.interruptedTaskIds.add(task.getTaskId());
                if (operation.cancelRequested || operation.result == DrainResult.CANCELLED) {
                    pendingResumeTaskIds.add(task.getTaskId());
                }
            }
        }

        activeTPlusOneTask = null;
        evaluateDrainLocked();
    }

    /**
     * cancel后原子领取需要立即重投的任务，同时占用dispatcher令牌。
     */
    public synchronized List<String> tryClaimCancelledDrainResumeTasks() {
        if (!serviceModeManager.isSyncEnabled()
                || coordinatorState != CoordinatorState.RUNNING
                || tPlusOneDispatcherActive
                || pendingResumeTaskIds.isEmpty()) {
            return List.of();
        }
        tPlusOneDispatcherActive = true;
        List<String> taskIds = new ArrayList<>(pendingResumeTaskIds);
        pendingResumeTaskIds.clear();
        return taskIds;
    }

    /**
     * 重投线程提交失败或被新drain阻止时，归还尚未执行的任务ID。
     */
    public synchronized void returnCancelledDrainResumeTasks(List<String> taskIds) {
        if (taskIds != null) {
            pendingResumeTaskIds.addAll(taskIds);
        }
        tPlusOneDispatcherActive = false;
        evaluateDrainLocked();
    }

    /**
     * 当前是否允许调度器继续扫描和启动任务。
     */
    public synchronized boolean isAcceptingNewWork() {
        return serviceModeManager.isSyncEnabled() && coordinatorState == CoordinatorState.RUNNING;
    }

    /**
     * 判断任务ID是否属于当前注册的T+1活动任务。
     */
    private boolean matchesActiveTask(String taskId) {
        return activeTPlusOneTask != null && activeTPlusOneTask.taskId.equals(taskId);
    }

    /**
     * 完成判据同时检查调度器、活动任务、真实Bulk许可和全局内存预留。
     */
    private void evaluateDrainLocked() {
        if (coordinatorState != CoordinatorState.DRAINING
                || operation == null
                || operation.result != DrainResult.IN_PROGRESS) {
            return;
        }

        GlobalEsWritePermitManager.Snapshot permits = permitManager.snapshot();
        GlobalSyncMemoryLimiter.Snapshot memory = memoryLimiter.snapshot();
        boolean pipelineStopped = !tPlusOneDispatcherActive && activeTPlusOneTask == null;
        boolean resourcesReturned = permits.activeTotal() == 0
                && permits.waitingPolling() == 0
                && permits.waitingTPlusOne() == 0
                && memory.reservationCount() == 0;
        if (pipelineStopped && resourcesReturned) {
            operation.result = operation.hadBusinessErrors
                    ? DrainResult.DRAINED_WITH_ERRORS
                    : DrainResult.DRAINED;
            operation.completedAtMillis = System.currentTimeMillis();
            return;
        }

        if (System.currentTimeMillis() - operation.requestedAtMillis >= drainTimeoutMillis) {
            operation.result = DrainResult.FAILED;
            operation.error = "Sync drain timed out before all readers, tasks and resources reached a safe boundary";
            operation.completedAtMillis = System.currentTimeMillis();
        }
    }

    /**
     * 在协调器锁内组装当前排空、流水线和共享资源快照。
     */
    private DrainStatusSnapshot snapshotLocked() {
        GlobalEsWritePermitManager.Snapshot permits = permitManager.snapshot();
        GlobalSyncMemoryLimiter.Snapshot memory = memoryLimiter.snapshot();
        TPlusOneRuntimeSnapshot runtime = buildTPlusOneSnapshot(permits);
        String serviceMode = serviceModeManager.currentMode().name();
        return new DrainStatusSnapshot(
                serviceMode,
                coordinatorState,
                operation == null ? DrainResult.NOT_STARTED : operation.result,
                operation == null ? null : operation.operationId,
                operation == null ? null : Instant.ofEpochMilli(operation.requestedAtMillis),
                operation == null || operation.completedAtMillis == 0L
                        ? null : Instant.ofEpochMilli(operation.completedAtMillis),
                operation == null ? null : operation.error,
                runtime,
                new ResourceSnapshot(permits, memory)
        );
    }

    /**
     * 组合T+1持久任务摘要与当前JVM流水线运行状态。
     */
    private TPlusOneRuntimeSnapshot buildTPlusOneSnapshot(GlobalEsWritePermitManager.Snapshot permits) {
        TPlusOneTaskRuntime active = activeTPlusOneTask;
        ImportContext context = active == null ? null : active.context;
        PersistentTaskSnapshot persistentTask = active == null
                ? lastPersistentTask
                : new PersistentTaskSnapshot(active.taskId, active.indexAlias, active.tableName,
                active.importDate, active.taskStatus,
                context == null ? active.lastSafeCheckpointId : context.getStatistics().getLastSuccessId(),
                false, null);

        return new TPlusOneRuntimeSnapshot(
                serviceModeManager.isSyncEnabled() && importProperties.getTPlusOne().isEnabled(),
                tPlusOneDispatcherActive,
                active != null,
                persistentTask,
                context != null && context.getReaderStopped().get(),
                context == null ? 0 : context.getQueue().size(),
                permits.activeTPlusOne(),
                permits.waitingTPlusOne(),
                context == null ? 0L : context.getLastEnqueuedSequence().get(),
                context == null ? 0L : context.getLastCommittedSequence(),
                context == null ? 0L : context.getStatistics().getLastId(),
                context == null ? 0L : context.getStatistics().getLastSuccessId()
        );
    }

    /**
     * 协调器是否继续接受新同步工作的内存运行态。
     */
    public enum CoordinatorState {
        /** 正常接受同步调度和任务。 */
        RUNNING,

        /** 拒绝新工作并等待现有工作到达安全边界。 */
        DRAINING
    }

    /**
     * 最近一次drain操作结果；与协调器运行态和持久业务状态相互独立。
     */
    public enum DrainResult {
        /** 当前进程尚未发起过drain。 */
        NOT_STARTED,

        /** 已发起drain，仍在等待流水线或资源排空。 */
        IN_PROGRESS,

        /** 已安全排空，且已有任务没有业务失败。 */
        DRAINED,

        /** 已安全排空，但已有任务包含业务失败。 */
        DRAINED_WITH_ERRORS,

        /** 未在时限内安全排空或任务终态无法可靠持久化。 */
        FAILED,

        /** 操作被显式取消，协调器已恢复接收新工作。 */
        CANCELLED
    }

    /**
     * 管理接口返回的完整排空状态。
     */
    public record DrainStatusSnapshot(String serviceMode,
                                      CoordinatorState coordinatorState,
                                      DrainResult drainResult,
                                      String operationId,
                                      Instant requestedAt,
                                      Instant completedAt,
                                      String error,
                                      TPlusOneRuntimeSnapshot tPlusOne,
                                      ResourceSnapshot resources) {
    }

    /**
     * T+1调度器、活动任务、Reader、队列和安全断点的运行快照。
     */
    public record TPlusOneRuntimeSnapshot(boolean enabled,
                                          boolean dispatcherActive,
                                          boolean activeTask,
                                          PersistentTaskSnapshot persistentTask,
                                          boolean readerStopped,
                                          int queueSize,
                                          int activeBulk,
                                          int waitingBulkPermit,
                                          long lastEnqueuedSequence,
                                          long lastCommittedSequence,
                                          long lastReadId,
                                          long lastSafeCheckpointId) {
    }

    /**
     * T+1持久任务状态摘要，不使用协调器状态替代任务终态。
     */
    public record PersistentTaskSnapshot(String taskId,
                                         String indexAlias,
                                         String tableName,
                                         String importDate,
                                         String taskStatus,
                                         long lastSafeCheckpointId,
                                         boolean persistenceSafe,
                                         String error) {
    }

    /**
     * drain完成判定使用的全局Bulk许可和内存预算快照。
     */
    public record ResourceSnapshot(GlobalEsWritePermitManager.Snapshot bulkPermits,
                                   GlobalSyncMemoryLimiter.Snapshot memory) {
    }

    /**
     * 当前或最近一次drain的可变操作状态，仅在协调器锁内访问。
     */
    private static final class DrainOperation {
        /** 唯一操作ID，用于阻止旧部署脚本取消新一轮drain。 */
        private final String operationId;

        /** 发起时间，用于超时判断。 */
        private final long requestedAtMillis;

        /** 本次drain打断且可在cancel后精确续跑的任务ID。 */
        private final Set<String> interruptedTaskIds = new LinkedHashSet<>();

        /** 当前drain结果。 */
        private DrainResult result = DrainResult.IN_PROGRESS;

        /** 操作结束时间，进行中时为0。 */
        private long completedAtMillis;

        /** 排空期间是否出现任务级业务失败。 */
        private boolean hadBusinessErrors;

        /** 是否已收到取消请求。 */
        private boolean cancelRequested;

        /** drain失败原因。 */
        private String error;

        private DrainOperation(String operationId, long requestedAtMillis) {
            this.operationId = operationId;
            this.requestedAtMillis = requestedAtMillis;
        }
    }

    /**
     * 当前T+1活动任务在本JVM中的运行信息，与ES任务文档分开保存。
     */
    private static final class TPlusOneTaskRuntime {
        private final String taskId;
        private final String indexAlias;
        private final String indexName;
        private final String tableName;
        private final String importDate;

        /** 上下文尚未挂载时使用的任务持久安全断点。 */
        private final long lastSafeCheckpointId;

        /** 当前观察到的持久任务状态。 */
        private String taskStatus;

        /** Reader/Bulk启动后挂载的单次导入上下文。 */
        private ImportContext context;

        private TPlusOneTaskRuntime(SanoImportTask task) {
            this.taskId = task.getTaskId();
            this.indexAlias = task.getIndexAlias();
            this.indexName = task.getIndexName();
            this.tableName = task.getTableName();
            this.importDate = task.getImportDate();
            this.lastSafeCheckpointId = task.getLastSuccessId();
            this.taskStatus = task.getStatus();
        }
    }
}
