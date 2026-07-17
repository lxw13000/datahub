package com.tsd.sano.es.importer.pipeline.model;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.sync.service.GlobalSyncMemoryLimiter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T+1导入有序安全断点测试。
 *
 * <p>重点验证Bulk乱序完成和低序号失败时，TIMEOUT_PARTIAL使用的断点不会
 * 越过尚未安全完成的批次。</p>
 */
class ImportContextTest {

    /**
     * 高序号批次先完成时，必须等待低序号批次后才能连续推进断点。
     */
    @Test
    void shouldCommitCheckpointInSequenceOrder() {
        ImportContext context = newContext(100L);
        ImportBatch first = context.createBatch(rows(101L, 110L), 110L);
        ImportBatch second = context.createBatch(rows(111L, 120L), 120L);

        context.completeBatch(second, true);
        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(100L);

        context.completeBatch(first, true);
        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(120L);
        assertThat(context.getStatistics().getCheckpointBlockedSequence()).isZero();
    }

    /**
     * 最早批次包含item失败时，后续成功批次不能越过该批次更新安全断点。
     */
    @Test
    void shouldNotCommitPastUnsafeFirstBatch() {
        ImportContext context = newContext(100L);
        ImportBatch first = context.createBatch(rows(101L, 110L), 110L);
        ImportBatch second = context.createBatch(rows(111L, 120L), 120L);

        context.completeBatch(second, true);
        context.completeBatch(first, false);

        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(100L);
        assertThat(context.getStatistics().getCheckpointBlockedSequence()).isEqualTo(1L);
    }

    /**
     * 前置成功批次可以提交，但遇到中间不安全批次后必须停止继续推进。
     */
    @Test
    void shouldKeepLastContiguousSafeBatchBeforeFailure() {
        ImportContext context = newContext(100L);
        ImportBatch first = context.createBatch(rows(101L, 110L), 110L);
        ImportBatch second = context.createBatch(rows(111L, 120L), 120L);
        ImportBatch third = context.createBatch(rows(121L, 130L), 130L);

        context.completeBatch(third, true);
        context.completeBatch(second, false);
        context.completeBatch(first, true);

        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(110L);
        assertThat(context.getStatistics().getCheckpointBlockedSequence()).isEqualTo(2L);

        // 阻塞建立后的迟到成功回调不得改变安全断点。
        context.completeBatch(third, true);
        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(110L);
    }

    /**
     * 批次终态和任务异常清理都必须归还全局内存额度。
     */
    @Test
    void shouldReleaseBatchMemoryOnCompletionAndCleanup() {
        GlobalSyncMemoryLimiter limiter = new GlobalSyncMemoryLimiter(new EsImportProperties());
        ImportContext context = newContext(100L);

        GlobalSyncMemoryLimiter.Reservation completedReservation = limiter.reserve(1024L);
        ImportBatch completed = context.createBatch(rows(101L, 110L), 110L, completedReservation);
        context.completeBatch(completed, true);
        assertThat(limiter.snapshot().usedBytes()).isZero();

        GlobalSyncMemoryLimiter.Reservation abandonedReservation = limiter.reserve(2048L);
        context.createBatch(rows(111L, 120L), 120L, abandonedReservation);
        context.releaseAllMemoryReservations();
        assertThat(limiter.snapshot().usedBytes()).isZero();
        assertThat(limiter.snapshot().reservationCount()).isZero();
    }

    private ImportContext newContext(long startId) {
        EsImportConfig config = new EsImportConfig();
        EsImportProperties properties = new EsImportProperties();
        properties.getTPlusOne().setQueueCapacity(4);

        ImportStatistics statistics = new ImportStatistics();
        statistics.setLastId(startId);
        statistics.setLastSuccessId(startId);
        return new ImportContext(config, statistics, properties);
    }

    private List<Map<String, Object>> rows(long firstId, long lastId) {
        return List.of(Map.of("id", firstId), Map.of("id", lastId));
    }
}
