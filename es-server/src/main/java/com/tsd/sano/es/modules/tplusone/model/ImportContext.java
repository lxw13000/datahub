package com.tsd.sano.es.modules.tplusone.model;

import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.tplusone.pipeline.TPlusOneMemoryLimiter;
import lombok.Getter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ES导入上下文。
 *
 * <p>生命周期：创建Index -> Reader -> Queue -> Bulk -> Alias，全流程共享。</p>
 *
 * @author lxw
 */
@Getter
public class ImportContext {

    /**
     * 导入配置。
     */
    private final TPlusOneImportConfig config;

    /**
     * 全局统计。
     */
    private final ImportStatistics statistics;

    /**
     * 系统导入配置。
     */
    private final EsImportProperties properties;

    /**
     * Reader到Bulk之间的数据队列。
     */
    private final BlockingQueue<ImportBatch> queue;

    /**
     * Reader已分配的最大批次序号，单Reader场景下按读取顺序递增。
     */
    private final AtomicLong lastEnqueuedSequence = new AtomicLong();

    /**
     * 每个数据批次持有的T+1内存额度，批次终态或任务退出时释放。
     */
    private final Map<Long, TPlusOneMemoryLimiter.Reservation> memoryReservations =
            new ConcurrentHashMap<>();

    /**
     * 已完成但尚未满足连续提交条件的批次结果。
     *
     * <p>该Map只允许在completeBatch的同步块内访问，避免多个Bulk Worker乱序回调时
     * 同时推进任务安全断点。</p>
     */
    private final Map<Long, BatchCompletion> pendingCompletions = new HashMap<>();

    /**
     * 下一个允许提交到任务安全断点的批次序号。
     */
    private long nextCommitSequence = 1L;

    /**
     * 首个包含未持久化失败项的批次序号，0表示尚未阻塞。
     */
    private long checkpointBlockedSequence;

    /**
     * 导入中止标记。
     *
     * <p>Bulk线程异常时通过该标记通知Reader停止入队，避免Reader永久阻塞。</p>
     */
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    /**
     * 导入中止原因，便于上层输出明确错误。
     */
    private final AtomicReference<Throwable> abortReason = new AtomicReference<>();

    /**
     * 本次导入截止时间戳，0表示不启用deadline。
     */
    private final long deadlineMillis;

    /**
     * Reader查询边界锁，使“开始下一次SQL”和“发出drain”具有明确先后顺序。
     */
    private final Object readerBoundaryMonitor = new Object();

    /**
     * 已送达本上下文的drain操作ID；一旦设置，本次Reader不会再开始新的查询批次。
     */
    private String requestedDrainOperationId;

    /**
     * 是否已有一个查询批次越过边界并正在读取或入队。
     */
    private boolean readBatchInProgress;

    /**
     * Reader是否已经退出生产循环；队列和Bulk仍可能正在排空。
     */
    private final AtomicBoolean readerStopped = new AtomicBoolean(false);

    /**
     * 创建一次导入上下文，并初始化Reader到Bulk的队列。
     */
    public ImportContext(TPlusOneImportConfig config,
                         ImportStatistics statistics,
                         EsImportProperties properties) {
        this(config, statistics, properties, 0L);
    }

    /**
     * 创建一次带deadline的导入上下文。
     */
    public ImportContext(TPlusOneImportConfig config,
                         ImportStatistics statistics,
                         EsImportProperties properties,
                         long deadlineMillis) {
        this.config = config;
        this.statistics = statistics;
        this.properties = properties;
        this.deadlineMillis = deadlineMillis;
        this.queue = new LinkedBlockingQueue<>(properties.getTPlusOne().getQueueCapacity());
    }

    /**
     * 标记本次导入需要中止，只保留第一个异常原因。
     */
    public void abort(Throwable error) {
        aborted.set(true);
        abortReason.compareAndSet(null, error);
    }

    /**
     * 判断导入是否已被标记中止。
     */
    public boolean isAborted() {
        return aborted.get();
    }

    /**
     * 获取导入中止原因。
     */
    public Throwable getAbortReason() {
        return abortReason.get();
    }

    /**
     * 判断本次导入是否已经到达deadline。
     */
    public boolean isDeadlineReached() {
        return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
    }

    /**
     * 标记本次导入因deadline暂停。
     */
    public void markTimeoutPartial() {
        statistics.setTimeoutPartial(true);
        statistics.setStopReason(ImportStopReason.DEADLINE);
        statistics.setStopOperationId(null);
    }

    /**
     * 在准备发起SQL前原子检查drain信号。
     *
     * <p>返回非空operation ID表示drain先于新SQL获得边界；如果SQL已经开始，
     * drain只能等待该页返回并完整入队，下一轮才会在这里停止。</p>
     */
    public String currentDrainOperationId() {
        synchronized (readerBoundaryMonitor) {
            return requestedDrainOperationId;
        }
    }

    /**
     * 将drain信号送达当前Reader。已经越过边界的批次可以完成，后续批次会被拒绝。
     */
    public void requestDrainStop(String operationId) {
        if (operationId == null || operationId.isBlank()) {
            return;
        }
        synchronized (readerBoundaryMonitor) {
            if (requestedDrainOperationId == null) {
                requestedDrainOperationId = operationId;
            }
        }
    }

    /**
     * 尝试开始下一查询批次。
     *
     * @return 允许开始时为空；drain已经先到达时返回对应operation ID
     */
    public String tryBeginReadBatch() {
        synchronized (readerBoundaryMonitor) {
            if (requestedDrainOperationId != null) {
                return requestedDrainOperationId;
            }
            if (readBatchInProgress) {
                throw new IllegalStateException("T+1 Reader cannot start concurrent read batches");
            }
            readBatchInProgress = true;
            return null;
        }
    }

    /**
     * 结束已经越过边界的查询批次；返回页必须在调用前完整入队。
     */
    public void endReadBatch() {
        synchronized (readerBoundaryMonitor) {
            readBatchInProgress = false;
        }
    }

    /**
     * 标记本次任务因部署排空暂停。
     */
    public void markDrainPartial(String operationId) {
        statistics.setTimeoutPartial(true);
        statistics.setStopReason(ImportStopReason.DRAIN);
        statistics.setStopOperationId(operationId);
    }

    /**
     * 标记Reader已经停止产生新批次。
     */
    public void markReaderStopped() {
        readerStopped.set(true);
    }

    /**
     * 返回最后连续达到安全终态的批次序号。
     */
    public synchronized long getLastCommittedSequence() {
        return nextCommitSequence - 1L;
    }

    /**
     * 按Reader读取顺序创建数据批次。
     *
     * <p>批次在成功入队前已经取得sequence；若入队过程异常，本次导入会整体中止，
     * 因此不会在同一上下文中留下可继续执行的sequence空洞。</p>
     */
    public ImportBatch createBatch(List<Map<String, Object>> rows, long lastId) {
        return createBatch(rows, lastId, null);
    }

    /**
     * 创建携带T+1内存额度的数据批次。
     *
     * <p>Reservation在批次完成前由上下文接管；调用方创建成功后不得自行关闭。</p>
     */
    public ImportBatch createBatch(List<Map<String, Object>> rows,
                                   long lastId,
                                   TPlusOneMemoryLimiter.Reservation reservation) {
        long sequence = lastEnqueuedSequence.incrementAndGet();
        ImportBatch batch = ImportBatch.data(sequence, lastId, rows);
        if (reservation != null) {
            memoryReservations.put(sequence, reservation);
        }
        return batch;
    }

    /**
     * 接收Bulk Worker的批次完成结果，并仅按sequence连续推进安全断点。
     *
     * <p>checkpointSafe=false表示批次中存在尚未可靠持久化的item失败。该批次以及
     * 后续批次即使已经写入ES，也不能进入TIMEOUT_PARTIAL恢复断点；重启后会从最后
     * 连续安全ID重读，依赖固定ES文档ID实现幂等覆盖。</p>
     *
     * @param batch          已完成的Reader批次
     * @param checkpointSafe true表示批次内全部文档均已成功写入ES
     */
    public synchronized void completeBatch(ImportBatch batch, boolean checkpointSafe) {
        if (batch == null || batch.isEndSignal()) {
            return;
        }
        TPlusOneMemoryLimiter.Reservation reservation = memoryReservations.remove(batch.sequence());
        if (reservation != null) {
            // 无论批次成功还是阻塞checkpoint，行数据已不再排队或执行，可以归还内存额度。
            reservation.close();
        }
        if (checkpointBlockedSequence > 0L || batch.sequence() < nextCommitSequence) {
            // 已经遇到更早的不安全批次，或收到重复迟到回调，不再改变安全断点。
            return;
        }

        pendingCompletions.putIfAbsent(
                batch.sequence(),
                new BatchCompletion(batch.lastId(), checkpointSafe)
        );

        while (true) {
            BatchCompletion completion = pendingCompletions.remove(nextCommitSequence);
            if (completion == null) {
                return;
            }
            if (!completion.checkpointSafe()) {
                // 不安全批次会永久阻塞本次任务的后续断点；清理高序号结果，避免元数据继续增长。
                checkpointBlockedSequence = nextCommitSequence;
                statistics.setCheckpointBlockedSequence(checkpointBlockedSequence);
                pendingCompletions.clear();
                return;
            }

            statistics.setLastSuccessId(completion.lastId());
            nextCommitSequence++;
        }
    }

    /**
     * 任务异常退出时释放队列中尚未达到终态的全部内存额度。
     */
    public void releaseAllMemoryReservations() {
        memoryReservations.forEach((sequence, reservation) -> reservation.close());
        memoryReservations.clear();
    }

    /**
     * Bulk批次完成结果，仅保存推进安全断点所需的最小信息。
     */
    private record BatchCompletion(long lastId, boolean checkpointSafe) {
    }
}
