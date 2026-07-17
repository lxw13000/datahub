package com.tsd.sano.es.sync.service;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.sync.config.TableSyncMode;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T+1与polling共享Bulk许可和内存预算测试。
 */
class GlobalSyncResourceManagerTest {

    /**
     * polling等待时，T+1归还的下一个全局额度必须优先满足polling保留并发。
     */
    @Test
    void shouldGiveReleasedCapacityToWaitingPollingRequest() throws Exception {
        EsImportProperties properties = properties(3, 2, 3, 1024L);
        GlobalEsWritePermitManager manager = new GlobalEsWritePermitManager(properties);
        GlobalEsWritePermitManager.Permit first = manager.acquire(TableSyncMode.T_PLUS_ONE);
        GlobalEsWritePermitManager.Permit second = manager.acquire(TableSyncMode.T_PLUS_ONE);
        GlobalEsWritePermitManager.Permit third = manager.acquire(TableSyncMode.T_PLUS_ONE);

        CountDownLatch pollingAcquired = new CountDownLatch(1);
        CountDownLatch releasePolling = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> polling = executor.submit(() -> {
                try (GlobalEsWritePermitManager.Permit ignored = manager.acquire(TableSyncMode.POLLING)) {
                    pollingAcquired.countDown();
                    await(releasePolling);
                }
            });

            waitUntil(() -> manager.snapshot().waitingPolling() == 1);
            first.close();

            assertThat(pollingAcquired.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(manager.snapshot().activePolling()).isEqualTo(1);

            releasePolling.countDown();
            polling.get(2, TimeUnit.SECONDS);
        } finally {
            releasePolling.countDown();
            first.close();
            second.close();
            third.close();
        }

        assertThat(manager.snapshot().activeTotal()).isZero();
    }

    /**
     * 内存预算不足时后续Reader必须等待，已有批次释放后才能继续。
     */
    @Test
    void shouldBlockMemoryReservationUntilBudgetIsReleased() throws Exception {
        EsImportProperties properties = properties(1, 0, 1, 100L);
        GlobalSyncMemoryLimiter limiter = new GlobalSyncMemoryLimiter(properties);
        GlobalSyncMemoryLimiter.Reservation first = limiter.reserve(80L);

        CountDownLatch secondAcquired = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> second = executor.submit(() -> {
                try (GlobalSyncMemoryLimiter.Reservation ignored = limiter.reserve(30L)) {
                    secondAcquired.countDown();
                    await(releaseSecond);
                }
            });

            assertThat(secondAcquired.await(150, TimeUnit.MILLISECONDS)).isFalse();
            first.close();
            assertThat(secondAcquired.await(2, TimeUnit.SECONDS)).isTrue();

            releaseSecond.countDown();
            second.get(2, TimeUnit.SECONDS);
        } finally {
            releaseSecond.countDown();
            first.close();
        }

        assertThat(limiter.snapshot().usedBytes()).isZero();
        assertThat(limiter.snapshot().reservationCount()).isZero();
    }

    /**
     * 查询完成后的实际体积校准必须同步归还多余预算。
     */
    @Test
    void shouldResizeAndReleaseMemoryReservation() {
        EsImportProperties properties = properties(1, 0, 1, 100L);
        GlobalSyncMemoryLimiter limiter = new GlobalSyncMemoryLimiter(properties);

        try (GlobalSyncMemoryLimiter.Reservation reservation = limiter.reserve(80L)) {
            reservation.resize(40L);
            assertThat(reservation.bytes()).isEqualTo(40L);
            assertThat(limiter.snapshot().usedBytes()).isEqualTo(40L);
        }

        assertThat(limiter.snapshot().usedBytes()).isZero();
    }

    private EsImportProperties properties(int globalConcurrency,
                                        int pollingReserved,
                                        int tPlusOneMax,
                                        long maxBytes) {
        EsImportProperties properties = new EsImportProperties();
        properties.getCommon().getWrite().setGlobalBulkConcurrency(globalConcurrency);
        properties.getCommon().getWrite().setPollingReservedConcurrency(pollingReserved);
        properties.getCommon().getWrite().setTPlusOneMaxConcurrency(tPlusOneMax);
        properties.getCommon().getWrite().setGlobalQueueMaxBytes(DataSize.ofBytes(maxBytes));
        return properties;
    }

    private void waitUntil(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!check.evaluate()) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Condition was not satisfied before timeout");
            }
            Thread.sleep(10L);
        }
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test worker interrupted", e);
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate();
    }
}
