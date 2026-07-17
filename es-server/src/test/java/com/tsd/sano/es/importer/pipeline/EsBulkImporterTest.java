package com.tsd.sano.es.importer.pipeline;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ErrorCause;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.pipeline.model.EsImportConfig;
import com.tsd.sano.es.importer.pipeline.model.ImportBatch;
import com.tsd.sano.es.importer.pipeline.model.ImportContext;
import com.tsd.sano.es.importer.pipeline.model.ImportStatistics;
import com.tsd.sano.es.sync.service.GlobalEsWritePermitManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T+1 Bulk批次安全断点测试。
 *
 * <p>验证item级失败或缺少文档ID时，Bulk可以保留现有容差统计行为，
 * 但不能把TIMEOUT_PARTIAL安全断点推进到失败记录之后。</p>
 */
class EsBulkImporterTest {

    /**
     * Bulk响应包含单条item失败时，整个Reader批次不能提交到安全断点。
     */
    @Test
    void shouldBlockCheckpointWhenBulkContainsItemFailure() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        BulkResponse response = mockBulkResponse(true, successItem("101"), failedItem("102"));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);

        ImportContext context = newContext();
        enqueueBatchAndEnd(context, List.of(Map.of("id", 101L), Map.of("id", 102L)), 102L);

        new EsBulkImporter(client, new ObjectMapper(), permitManager()).importFromQueue(context);

        assertThat(context.getStatistics().getSuccess().get()).isEqualTo(1L);
        assertThat(context.getStatistics().getFailed().get()).isEqualTo(1L);
        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(100L);
        assertThat(context.getStatistics().getCheckpointBlockedSequence()).isEqualTo(1L);
    }

    /**
     * 构建Bulk时跳过缺少文档ID的记录，也必须把当前Reader批次视为不安全。
     */
    @Test
    void shouldBlockCheckpointWhenRowHasNoDocumentId() throws IOException {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        BulkResponse response = mockBulkResponse(false, successItem("102"));
        when(client.bulk(any(BulkRequest.class))).thenReturn(response);

        ImportContext context = newContext();
        enqueueBatchAndEnd(context, List.of(Map.of("name", "missing-id"), Map.of("id", 102L)), 102L);

        new EsBulkImporter(client, new ObjectMapper(), permitManager()).importFromQueue(context);

        assertThat(context.getStatistics().getSuccess().get()).isEqualTo(1L);
        assertThat(context.getStatistics().getFailed().get()).isEqualTo(1L);
        assertThat(context.getStatistics().getLastSuccessId()).isEqualTo(100L);
        assertThat(context.getStatistics().getCheckpointBlockedSequence()).isEqualTo(1L);
    }

    private ImportContext newContext() {
        EsImportConfig config = new EsImportConfig();
        config.setIndexName("test_index_20260715");
        config.setIndexAlias("test_index");
        config.setTableName("test_table");
        config.setIdColumn("id");

        EsImportProperties properties = new EsImportProperties();
        properties.getTPlusOne().setWorkerCount(1);
        properties.getTPlusOne().setQueueCapacity(4);
        properties.getTPlusOne().setBulkActions(3000);
        properties.getTPlusOne().setBulkSizeMb(10);
        properties.getTPlusOne().setRetryTimes(0);

        ImportStatistics statistics = new ImportStatistics();
        statistics.setLastId(100L);
        statistics.setLastSuccessId(100L);
        return new ImportContext(config, statistics, properties);
    }

    private GlobalEsWritePermitManager permitManager() {
        return new GlobalEsWritePermitManager(new EsImportProperties());
    }

    private void enqueueBatchAndEnd(ImportContext context,
                                    List<Map<String, Object>> rows,
                                    long lastId) {
        ImportBatch batch = context.createBatch(rows, lastId);
        context.getQueue().add(batch);
        context.getQueue().add(ImportBatch.workerEndSignal());
    }

    private BulkResponse mockBulkResponse(boolean errors, BulkResponseItem... items) {
        BulkResponse response = mock(BulkResponse.class);
        when(response.errors()).thenReturn(errors);
        when(response.items()).thenReturn(List.of(items));
        return response;
    }

    private BulkResponseItem successItem(String id) {
        BulkResponseItem item = mock(BulkResponseItem.class);
        when(item.id()).thenReturn(id);
        return item;
    }

    private BulkResponseItem failedItem(String id) {
        ErrorCause error = mock(ErrorCause.class);
        when(error.reason()).thenReturn("mapping rejected");

        BulkResponseItem item = mock(BulkResponseItem.class);
        when(item.id()).thenReturn(id);
        when(item.error()).thenReturn(error);
        return item;
    }
}
