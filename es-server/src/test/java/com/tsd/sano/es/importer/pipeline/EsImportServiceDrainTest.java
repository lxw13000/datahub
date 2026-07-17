package com.tsd.sano.es.importer.pipeline;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.EsImportConfig;
import com.tsd.sano.es.importer.pipeline.model.ImportContext;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.importer.pipeline.model.ImportStopReason;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import com.tsd.sano.es.sync.service.SyncDrainCoordinator;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T+1导入总流程在COUNT阶段收到drain时的停止边界测试。
 */
class EsImportServiceDrainTest {

    /**
     * COUNT完成时已经收到drain，不得继续创建索引或启动Reader/Bulk流水线。
     */
    @Test
    void shouldStopPipelineWhenCountFinishesAtDrainBoundary() {
        EsImportProperties properties = new EsImportProperties();
        properties.getTPlusOne().setEnableMonitor(false);
        EsIndexManager indexManager = mock(EsIndexManager.class);
        JdbcDataReader dataReader = mock(JdbcDataReader.class);
        EsBulkImporter bulkImporter = mock(EsBulkImporter.class);
        SyncDrainCoordinator drainCoordinator = mock(SyncDrainCoordinator.class);

        when(dataReader.count(any(ImportContext.class))).thenAnswer(invocation -> {
            ImportContext context = invocation.getArgument(0);
            context.getStatistics().getTotal().set(12L);
            context.markDrainPartial("drain-count");
            return 12L;
        });

        EsImportService importService = new EsImportService(
                properties,
                indexManager,
                dataReader,
                bulkImporter,
                mock(ImportMonitor.class),
                drainCoordinator,
                mock(EsServiceModeManager.class)
        );
        EsImportConfig config = new EsImportConfig();
        config.setIndexAlias("coin");
        config.setTableName("coin");
        config.setMappingFile("coin.json");
        config.setImportDate(LocalDate.of(2026, 7, 15));

        ImportStatistics statistics = importService.importData(config, 0L, false);

        assertThat(statistics.isTimeoutPartial()).isTrue();
        assertThat(statistics.getStopReason()).isEqualTo(ImportStopReason.DRAIN);
        assertThat(statistics.getStopOperationId()).isEqualTo("drain-count");
        verify(indexManager, never()).createIndex(any(ImportContext.class));
        verify(bulkImporter, never()).importFromQueue(any(ImportContext.class));
    }
}
