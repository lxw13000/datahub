package com.tsd.sano.es.modules.coordination.service;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.ImportCommonConfig;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 当前实例T+1任务共用的公平ES Bulk并发控制器。
 *
 * <p>所有T+1 Bulk Worker在发送请求前取得许可，完成后通过Permit归还额度，
 * 防止单次导入占用过多ES写入并发。</p>
 */
@Component
public class GlobalEsWritePermitManager {

    /**
     * 公平锁，保证T+1写入请求按等待顺序重新竞争许可。
     */
    private final ReentrantLock lock = new ReentrantLock(true);

    /**
     * Bulk完成归还许可后唤醒等待线程。
     */
    private final Condition permitAvailable = lock.newCondition();

    /**
     * 当前实例Bulk并发上限。
     */
    private final int globalConcurrency;

    /**
     * T+1最多可使用的并发数。
     */
    private final int tPlusOneMaxConcurrency;

    /**
     * 当前T+1占用的许可总数。
     */
    private int activeTotal;

    /**
     * 当前T+1占用的许可数。
     */
    private int activeTPlusOne;

    /**
     * 当前等待许可的T+1请求数。
     */
    private int waitingTPlusOne;

    /**
     * 校验并加载T+1 Bulk并发参数。
     */
    public GlobalEsWritePermitManager(EsImportProperties properties) {
        ImportCommonConfig common = properties.getCommon();
        this.globalConcurrency = Math.max(1, common.getGlobalBulkConcurrency());
        this.tPlusOneMaxConcurrency = Math.max(1,
                Math.min(common.getTPlusOneMaxConcurrency(), globalConcurrency));
    }

    /**
     * 等待并获取T+1 Bulk许可。
     *
     * <p>许可不足时持续等待，不会因等待本身中止业务。只有等待线程被外部中断时才
     * 恢复中断标记并抛出异常，终止当前T+1任务。
     * 调用方必须使用try-with-resources关闭返回值。</p>
     */
    public Permit acquire() {
        try {
            lock.lockInterruptibly();
            waitingTPlusOne++;
            try {
                while (!canAcquire()) {
                    permitAvailable.await();
                }
                activeTotal++;
                activeTPlusOne++;
                return new Permit(this);
            } finally {
                waitingTPlusOne--;
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
            return new Snapshot(activeTotal, activeTPlusOne, waitingTPlusOne);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按实例总上限和T+1上限判断当前请求能否取得许可。
     */
    private boolean canAcquire() {
        return activeTotal < globalConcurrency && activeTPlusOne < tPlusOneMaxConcurrency;
    }

    /**
     * 归还T+1占用的许可并唤醒等待者。
     */
    private void release() {
        lock.lock();
        try {
            if (activeTotal <= 0) {
                throw new IllegalStateException("Global ES bulk permit released more than acquired");
            }
            activeTotal--;
            activeTPlusOne--;
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
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Permit(GlobalEsWritePermitManager owner) {
            this.owner = owner;
        }

        /**
         * 幂等归还许可，避免异常清理路径重复释放。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }

    /**
     * 共享Bulk许可的只读运行快照。
     */
    public record Snapshot(int activeTotal,
                           int activeTPlusOne,
                           int waitingTPlusOne) {
    }
}
