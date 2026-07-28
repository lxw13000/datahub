package com.tsd.sano.es.modules.coordination.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.ImportCommonConfig;
import com.tsd.sano.es.modules.config.TableSyncMode;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * T+1与polling共用的公平ES Bulk并发控制器。
 *
 * <p>polling没有等待者时T+1可以借用空闲并发；polling开始等待后，新的T+1请求
 * 只能使用非保留额度。已经发送的Bulk不会被抢占，完成后通过Permit归还额度。</p>
 */
@Component
public class GlobalEsWritePermitManager {

    /**
     * 公平锁，保证两类同步请求按等待顺序重新竞争许可。
     */
    private final ReentrantLock lock = new ReentrantLock(true);

    /**
     * Bulk完成归还许可后唤醒等待线程。
     */
    private final Condition permitAvailable = lock.newCondition();

    /**
     * 所有同步引擎合计Bulk并发上限。
     */
    private final int globalConcurrency;

    /**
     * polling存在等待者时为其保留的并发数。
     */
    private final int pollingReservedConcurrency;

    /**
     * polling无等待者时T+1最多可使用的并发数。
     */
    private final int tPlusOneMaxConcurrency;

    /**
     * 当前两类同步合计占用的许可数。
     */
    private int activeTotal;

    /**
     * 当前polling占用的许可数。
     */
    private int activePolling;

    /**
     * 当前T+1占用的许可数。
     */
    private int activeTPlusOne;

    /**
     * 当前等待许可的polling请求数。
     */
    private int waitingPolling;

    /**
     * 当前等待许可的T+1请求数。
     */
    private int waitingTPlusOne;

    /**
     * 校验并加载共享Bulk并发参数。
     */
    public GlobalEsWritePermitManager(EsImportProperties properties) {
        ImportCommonConfig common = properties.getCommon();
        this.globalConcurrency = Math.max(1, common.getGlobalBulkConcurrency());
        this.pollingReservedConcurrency = Math.max(0,
                Math.min(common.getPollingReservedConcurrency(), globalConcurrency));
        this.tPlusOneMaxConcurrency = Math.max(1,
                Math.min(common.getTPlusOneMaxConcurrency(), globalConcurrency));
    }

    /**
     * 等待并获取指定引擎的Bulk许可。
     *
     * <p>许可不足时持续等待，不会因等待本身中止业务。只有等待线程被外部中断时才
     * 恢复中断标记并抛出异常，终止当前T+1任务或当前Polling表，不影响其他同步任务。
     * 调用方必须使用try-with-resources关闭返回值。</p>
     */
    public Permit acquire(TableSyncMode syncMode) {
        boolean polling = syncMode == TableSyncMode.POLLING;
        try {
            lock.lockInterruptibly();
            if (polling) {
                waitingPolling++;
            } else {
                waitingTPlusOne++;
            }
            try {
                while (!canAcquire(syncMode)) {
                    permitAvailable.await();
                }
                activeTotal++;
                if (polling) {
                    activePolling++;
                } else {
                    activeTPlusOne++;
                }
                return new Permit(this, syncMode);
            } finally {
                if (polling) {
                    waitingPolling--;
                } else {
                    waitingTPlusOne--;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("ES sync interrupted while waiting global bulk permit", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 返回当前资源快照，供管理接口和drain判断使用。
     */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(activeTotal, activePolling, activeTPlusOne, waitingPolling, waitingTPlusOne);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按全局上限和polling保留额度判断当前请求能否取得许可。
     */
    private boolean canAcquire(TableSyncMode syncMode) {
        if (activeTotal >= globalConcurrency) {
            return false;
        }
        if (syncMode == TableSyncMode.POLLING) {
            return true;
        }

        int allowedTPlusOne = waitingPolling > 0
                ? Math.max(0, globalConcurrency - pollingReservedConcurrency)
                : tPlusOneMaxConcurrency;
        return activeTPlusOne < allowedTPlusOne;
    }

    /**
     * 归还指定同步引擎占用的许可并唤醒等待者。
     */
    private void release(TableSyncMode syncMode) {
        lock.lock();
        try {
            if (activeTotal <= 0) {
                throw new IllegalStateException("Global ES bulk permit released more than acquired");
            }
            activeTotal--;
            if (syncMode == TableSyncMode.POLLING) {
                activePolling--;
            } else {
                activeTPlusOne--;
            }
            permitAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 单次Bulk请求持有的共享许可。
     */
    public static final class Permit implements AutoCloseable {

        private final GlobalEsWritePermitManager owner;
        private final TableSyncMode syncMode;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Permit(GlobalEsWritePermitManager owner, TableSyncMode syncMode) {
            this.owner = owner;
            this.syncMode = syncMode;
        }

        /**
         * 幂等归还许可，避免异常清理路径重复释放。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(syncMode);
            }
        }
    }

    /**
     * 共享Bulk许可的只读运行快照。
     */
    public record Snapshot(int activeTotal,
                           int activePolling,
                           int activeTPlusOne,
                           int waitingPolling,
                           int waitingTPlusOne) {
    }
}
