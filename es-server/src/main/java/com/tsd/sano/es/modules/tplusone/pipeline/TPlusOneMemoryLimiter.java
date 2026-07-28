package com.tsd.sano.es.modules.tplusone.pipeline;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * T+1流水线的批次内存预算控制器。
 *
 * <p>一个Reservation从读取前预留开始，经过排队和Bulk执行，直到批次终态后关闭。
 * 预算不足时Reader等待，从源头限制T+1队列和在途批次合计内存，而不是只限制队列元素数量。</p>
 */
@Component
public class TPlusOneMemoryLimiter {

    /**
     * 内存不足时检查调用方停止条件的间隔，避免Bulk异常后Reader永久等待。
     */
    private static final long STOP_CHECK_INTERVAL_SECONDS = 1L;

    /** 公平锁，串行维护当前实例的T+1内存预留状态。 */
    private final ReentrantLock lock = new ReentrantLock(true);

    /** 批次释放或缩小预留后唤醒等待Reader。 */
    private final Condition memoryAvailable = lock.newCondition();

    /** T+1排队和在途批次共享的内存预算上限。 */
    private final long maxBytes;

    /** 当前全部有效Reservation占用的估算字节数。 */
    private long usedBytes;

    /** 当前尚未关闭的Reservation数量。 */
    private int reservationCount;

    /**
     * 加载T+1流水线使用的内存预算上限。
     */
    public TPlusOneMemoryLimiter(EsImportProperties properties) {
        this.maxBytes = Math.max(1L,
                properties.getTPlusOne().getQueueMaxBytes().toBytes());
    }

    /**
     * 等待并预留指定字节数。
     */
    public Reservation reserve(long requestedBytes) {
        return reserve(requestedBytes, () -> false);
    }

    /**
     * 等待并预留指定字节数，等待期间定期检查调用方是否已经停止。
     *
     * <p>Reader传入Bulk中止状态后，即使现有批次不再释放额度，也能退出等待并进入
     * 任务清理流程，避免内存额度与Reader之间形成循环等待。</p>
     */
    public Reservation reserve(long requestedBytes, BooleanSupplier stopRequested) {
        long bytes = normalizeRequestedBytes(requestedBytes);
        try {
            lock.lockInterruptibly();
            while (usedBytes + bytes > maxBytes) {
                if (stopRequested.getAsBoolean()) {
                    throw new ServiceException("ES T+1 sync stopped while waiting memory budget");
                }
                memoryAvailable.await(STOP_CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
            }
            if (stopRequested.getAsBoolean()) {
                throw new ServiceException("ES T+1 sync stopped before reserving memory budget");
            }
            usedBytes += bytes;
            reservationCount++;
            return new Reservation(this, bytes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("ES T+1 sync interrupted while waiting memory budget", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 返回当前内存预算快照。
     */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(maxBytes, usedBytes, reservationCount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 规范化单次预留大小，并拒绝永远无法满足的超预算请求。
     */
    private long normalizeRequestedBytes(long requestedBytes) {
        long bytes = Math.max(1L, requestedBytes);
        if (bytes > maxBytes) {
            throw new ServiceException("Single ES T+1 sync batch exceeds memory budget, requestedBytes="
                    + bytes + ", maxBytes=" + maxBytes);
        }
        return bytes;
    }

    /**
     * 按查询后的实际估算调整已有预留，扩大时继续遵守T+1内存预算。
     */
    private void resize(Reservation reservation, long requestedBytes) {
        long newBytes = normalizeRequestedBytes(requestedBytes);
        try {
            lock.lockInterruptibly();
            if (reservation.closed.get()) {
                throw new IllegalStateException("Cannot resize closed ES sync memory reservation");
            }

            long additionalBytes = newBytes - reservation.bytes;
            while (additionalBytes > 0L && usedBytes + additionalBytes > maxBytes) {
                memoryAvailable.await();
                if (reservation.closed.get()) {
                    throw new IllegalStateException("Cannot resize closed ES sync memory reservation");
                }
            }
            usedBytes += additionalBytes;
            reservation.bytes = newBytes;
            if (additionalBytes < 0L) {
                memoryAvailable.signalAll();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("ES T+1 sync interrupted while resizing memory reservation", e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 释放批次内存预留并唤醒等待Reader。
     */
    private void release(Reservation reservation) {
        lock.lock();
        try {
            usedBytes -= reservation.bytes;
            reservationCount--;
            memoryAvailable.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 单个已读取批次持有的内存额度。
     */
    public static final class Reservation implements AutoCloseable {

        private final TPlusOneMemoryLimiter owner;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private long bytes;

        private Reservation(TPlusOneMemoryLimiter owner, long bytes) {
            this.owner = owner;
            this.bytes = bytes;
        }

        /**
         * 查询返回后按实际批次估算调整预留额度。
         */
        public void resize(long requestedBytes) {
            owner.resize(this, requestedBytes);
        }

        /**
         * 返回当前预留字节数。
         */
        public long bytes() {
            return bytes;
        }

        /**
         * 幂等释放当前批次占用的内存预算。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(this);
            }
        }
    }

    /**
     * T+1内存预算只读快照。
     */
    public record Snapshot(long maxBytes, long usedBytes, int reservationCount) {
    }
}
