package com.tsd.sano.es.importer.taskstore;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.tsd.sano.es.importer.taskstore.model.SanoImportTask;
import com.tsd.sano.es.importer.util.MappingLoader;
import org.apache.http.StatusLine;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ES导入任务创建幂等性测试。
 */
class SanoImportTaskServiceTest {

    /**
     * 低层客户端以ResponseException返回409时，应视为任务已存在而不是创建失败。
     */
    @Test
    void shouldReturnFalseWhenLowLevelClientReportsConflict() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        ResponseException conflict = mock(ResponseException.class);
        Response response = mock(Response.class);
        StatusLine statusLine = mock(StatusLine.class);
        when(conflict.getResponse()).thenReturn(response);
        when(response.getStatusLine()).thenReturn(statusLine);
        when(statusLine.getStatusCode()).thenReturn(409);
        when(client.index(org.mockito.ArgumentMatchers.<IndexRequest<SanoImportTask>>any()))
                .thenThrow(conflict);

        SanoImportTask task = new SanoImportTask();
        task.setTableName("sano_wallet_coin_record");
        task.setIndexAlias("sano_wallet_coin_record");
        task.setIndexName("sano_wallet_coin_record_20260716");
        task.setImportDate("20260716");

        boolean created = new SanoImportTaskService(client, mock(MappingLoader.class)).addTask(task);

        assertThat(created).isFalse();
        assertThat(task.getTaskId()).isEqualTo("sano_wallet_coin_record_20260716");
    }
}
