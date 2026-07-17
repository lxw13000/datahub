package com.tsd.sano.es.importer.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.EsImportConfig;
import com.tsd.sano.es.importer.pipeline.model.ImportBatch;
import com.tsd.sano.es.importer.pipeline.model.ImportContext;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.pipeline.model.ImportStopReason;
import com.tsd.sano.es.sync.service.GlobalSyncMemoryLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.unit.DataSize;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T+1 Reader在部署排空期间的SQL批次边界测试。
 */
class JdbcDataReaderDrainTest {

    /**
     * SQL执行中到达drain时，本页必须完整入队，但不得继续查询下一页。
     */
    @Test
    void shouldEnqueueCurrentSqlPageAndStopBeforeNextPage() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ImportContext context = context();
        List<Map<String, Object>> rows = List.of(Map.of("id", 101L), Map.of("id", 102L));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenAnswer(invocation -> {
            context.requestDrainStop("drain-1");
            return rows;
        });

        JdbcDataReader reader = new JdbcDataReader(
                jdbcTemplate, new ObjectMapper(), memoryLimiter());
        reader.readToQueue(context);

        verify(jdbcTemplate, times(1)).queryForList(anyString(), any(Object[].class));
        assertThat(context.getStatistics().getRead().get()).isEqualTo(2L);
        assertThat(context.getStatistics().getStopReason()).isEqualTo(ImportStopReason.DRAIN);
        assertThat(context.getStatistics().getStopOperationId()).isEqualTo("drain-1");
        assertThat(context.getReaderStopped()).isTrue();
        assertThat(context.getQueue()).hasSize(2);
        assertThat(context.getQueue().poll()).extracting(ImportBatch::lastId).isEqualTo(102L);
        assertThat(context.getQueue().poll()).extracting(ImportBatch::isEndSignal).isEqualTo(true);
    }

    /**
     * drain先于首个SQL到达时，Reader不得访问MySQL，只投递worker结束信号。
     */
    @Test
    void shouldNotStartSqlWhenDrainWinsReadBoundary() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ImportContext context = context();
        context.requestDrainStop("drain-2");

        new JdbcDataReader(jdbcTemplate, new ObjectMapper(), memoryLimiter()).readToQueue(context);

        verify(jdbcTemplate, times(0)).queryForList(anyString(), any(Object[].class));
        assertThat(context.getStatistics().getStopReason()).isEqualTo(ImportStopReason.DRAIN);
        assertThat(context.getQueue()).singleElement().extracting(ImportBatch::isEndSignal).isEqualTo(true);
    }

    /**
     * drain先于COUNT获得查询边界时，不得访问MySQL。
     */
    @Test
    void shouldNotStartCountWhenDrainWinsReadBoundary() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ImportContext context = context();
        context.requestDrainStop("drain-count-before");

        long total = new JdbcDataReader(jdbcTemplate, new ObjectMapper(), memoryLimiter()).count(context);

        assertThat(total).isZero();
        verify(jdbcTemplate, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
        assertThat(context.getStatistics().getStopReason()).isEqualTo(ImportStopReason.DRAIN);
        assertThat(context.getStatistics().getStopOperationId()).isEqualTo("drain-count-before");
    }

    /**
     * COUNT执行期间到达drain时允许当前SQL完成，但必须标记为排空暂停。
     */
    @Test
    void shouldMarkDrainAfterInFlightCountFinishes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ImportContext context = context();
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    context.requestDrainStop("drain-count-running");
                    return 12L;
                });

        long total = new JdbcDataReader(jdbcTemplate, new ObjectMapper(), memoryLimiter()).count(context);

        assertThat(total).isEqualTo(12L);
        assertThat(context.getStatistics().getTotal().get()).isEqualTo(12L);
        assertThat(context.getStatistics().getStopReason()).isEqualTo(ImportStopReason.DRAIN);
        assertThat(context.getStatistics().getStopOperationId()).isEqualTo("drain-count-running");
    }

    /**
     * Bulk中止后Reader必须退出内存额度等待，不能依赖已停止的Worker释放额度。
     */
    @Test
    void shouldStopWaitingMemoryWhenBulkAborts() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ImportContext context = context();
        EsImportProperties limiterProperties = new EsImportProperties();
        limiterProperties.getCommon().getWrite().setGlobalQueueMaxBytes(DataSize.ofBytes(2048L));
        GlobalSyncMemoryLimiter limiter = new GlobalSyncMemoryLimiter(limiterProperties);

        try (GlobalSyncMemoryLimiter.Reservation occupied = limiter.reserve(2048L);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> readerFuture = executor.submit(
                    () -> new JdbcDataReader(jdbcTemplate, new ObjectMapper(), limiter).readToQueue(context));

            Thread.sleep(100L);
            context.abort(new ServiceException("simulated bulk failure"));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> readerFuture.get(2, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ServiceException.class);
        }

        verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
        assertThat(context.getReaderStopped()).isTrue();
    }

    private ImportContext context() {
        EsImportProperties properties = new EsImportProperties();
        properties.getTPlusOne().setReadBatchSize(2);
        properties.getTPlusOne().setWorkerCount(1);
        properties.getTPlusOne().setQueueCapacity(10);

        EsImportConfig config = new EsImportConfig();
        config.setTableName("coin");
        config.setIndexAlias("coin");
        config.setIndexName("coin_20260715");
        config.setIdColumn("id");
        config.setWhereSql("dt = '2026-07-15'");
        config.setImportDate(LocalDate.of(2026, 7, 15));
        return new ImportContext(config, new ImportStatistics(), properties);
    }

    private GlobalSyncMemoryLimiter memoryLimiter() {
        EsImportProperties properties = new EsImportProperties();
        properties.getCommon().getWrite().setGlobalQueueMaxBytes(DataSize.ofMegabytes(8));
        return new GlobalSyncMemoryLimiter(properties);
    }
}
