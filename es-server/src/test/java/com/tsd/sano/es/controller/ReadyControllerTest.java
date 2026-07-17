package com.tsd.sano.es.controller;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.importer.taskstore.SanoImportTaskService;
import com.tsd.sano.es.sync.config.EsServiceModeManager;
import com.tsd.sano.es.sync.config.TableSyncMode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * server-mode严格就绪检查测试。
 */
class ReadyControllerTest {

    /**
     * query-only实例只需确认ES访问正常，不依赖T+1调度器。
     */
    @Test
    void shouldReportQueryInstanceReadyAfterEsCheck() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.info()).thenReturn(mock(InfoResponse.class));

        ReadyController controller = controller(client, "query", new EsImportProperties(), List.of());
        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().ready()).isTrue();
        assertThat(response.getBody().queryReady()).isTrue();
        assertThat(response.getBody().syncReady()).isTrue();
    }

    /**
     * 版本A若误配置polling表必须返回503，防止部署脚本把数据真空实例判为就绪。
     */
    @Test
    @SuppressWarnings("unchecked")
    void shouldRejectPollingTableBeforePollingEngineExists() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        when(client.info()).thenReturn(mock(InfoResponse.class));
        EsImportProperties.TableConfig polling = tableConfig("coin", TableSyncMode.POLLING);
        polling.setBootstrapStartDate(java.time.LocalDate.of(2026, 7, 16));

        ReadyController controller = controller(
                client, "all", new EsImportProperties(), List.of(polling));
        var response = controller.ready();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().details()).contains("QUERY_ALIAS_READY: coin");
        assertThat(response.getBody().details()).contains("POLLING_ENGINE_NOT_IMPLEMENTED");
        verify(client).search(any(java.util.function.Function.class), eq(Object.class));
    }

    private ReadyController controller(ElasticsearchClient client,
                                       String serviceMode,
                                       EsImportProperties properties,
                                       List<EsImportProperties.TableConfig> tables) {
        properties.setTables(tables);
        SanoImportTaskService taskService = mock(SanoImportTaskService.class);
        return new ReadyController(
                client,
                properties,
                taskService,
                new EsServiceModeManager(serviceMode)
        );
    }

    private EsImportProperties.TableConfig tableConfig(String indexAlias, TableSyncMode syncMode) {
        EsImportProperties.TableConfig table = new EsImportProperties.TableConfig();
        table.setSyncMode(syncMode);
        table.setIndexAlias(indexAlias);
        table.setTableName(indexAlias);
        table.setMappingFile(indexAlias + ".json");
        return table;
    }
}
