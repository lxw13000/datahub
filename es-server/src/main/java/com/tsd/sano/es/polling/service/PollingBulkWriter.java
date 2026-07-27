package com.tsd.sano.es.polling.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import com.tsd.sano.es.importer.notify.ImportNotifyService;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.sync.config.TableSyncMode;
import com.tsd.sano.es.sync.service.GlobalEsWritePermitManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Polling同步整批ES写入器
 *
 * <p>同一个MySQL读取批次同步写入ES，任意请求异常或文档失败都重试完整Bulk
 * 重试耗尽后记录失败范围并提交异步通知，然后返回上层继续下一批；差异由日期对账
 * 和人工T+1全量修复闭环，不能因单批失败暂停整张表</p>
 */
@Service
public class PollingBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(PollingBulkWriter.class);

    /**
     * ES同步客户端
     */
    private final ElasticsearchClient client;

    /**
     * Polling整批重试参数
     */
    private final EsImportProperties properties;

    /**
     * T+1与Polling共用的ES请求并发控制器
     */
    private final GlobalEsWritePermitManager writePermitManager;

    /**
     * Bulk重试耗尽后的异步通知入口
     */
    private final ImportNotifyService notifyService;

    /**
     * 注入ES客户端、Polling参数、全局Bulk并发控制器和通知服务
     */
    public PollingBulkWriter(ElasticsearchClient client, EsImportProperties properties,
                             GlobalEsWritePermitManager writePermitManager,
                             ImportNotifyService notifyService) {
        this.client = client;
        this.properties = properties;
        this.writePermitManager = writePermitManager;
        this.notifyService = notifyService;
    }

    /**
     * 将一个MySQL读取批次完整写入指定日期物理索引
     *
     * @param tableConfig 单表同步配置
     * @param syncDate    当前业务日期
     * @param indexName   当前日期物理索引
     * @param readBatch   本次MySQL查询结果
     */
    public void writeBatch(EsImportProperties.TableConfig tableConfig, LocalDate syncDate,
                           String indexName, PollingJdbcReader.ReadBatch readBatch) {

        List<Map<String, Object>> rows = readBatch.rows();
        if (rows.isEmpty()) {
            return;
        }
        String tableName = tableConfig.getTableName();
        String idColumn = tableConfig.getIdColumn();
        BulkRequest.Builder requestBuilder = new BulkRequest.Builder().refresh(Refresh.False);
        long firstId = readBatch.firstId();
        long lastId = readBatch.lastId();
        for (Map<String, Object> row : rows) {
            requestBuilder.operations(operation -> operation.index(item -> item
                    .index(indexName.trim())
                    .id(row.get(idColumn).toString())
                    .document(row)
            ));
        }
        // 整个重试过程复用同一个请求，确保每次重发的索引、文档ID和文档内容完全一致
        BulkRequest request = requestBuilder.build();

        int maxAttempts = Math.max(0, properties.getPolling().getBulkRetryTimes()) + 1;
        long retryIntervalMillis = Math.max(0L,
                properties.getPolling().getBulkRetryInterval().toMillis());
        String failureReason;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                long startTime = System.currentTimeMillis();
                BulkResponse response;
                // 许可证只覆盖真实ES请求，响应判断和重试等待不占用全局并发额度
                try (GlobalEsWritePermitManager.Permit ignored =
                             writePermitManager.acquire(TableSyncMode.POLLING)) {
                    response = client.bulk(request);
                }
                long failedCount = response.items().stream()
                        .filter(item -> item.error() != null)
                        .count();
                if (!response.errors() && failedCount == 0L
                        && response.items().size() == rows.size()) {
                    log.info("===> ES-Polling bulk completed. table={}, date={}, index={}, size={}, "
                                    + "firstId={}, lastId={}, attempt={}, costMs={}",
                            tableName, syncDate, indexName, rows.size(),
                            firstId, lastId, attempt, System.currentTimeMillis() - startTime);
                    return;
                }
                String firstItemError = response.items().stream()
                        .filter(item -> item.error() != null)
                        .map(item -> "id=" + item.id() + ", reason=" + item.error().reason())
                        .findFirst()
                        .orElse("none");
                failureReason = "responseErrors=" + response.errors()
                        + ", failedItems=" + failedCount
                        + ", responseItems=" + response.items().size()
                        + ", expectedItems=" + rows.size()
                        + ", firstItemError={" + firstItemError + "}";
            } catch (IOException | ElasticsearchException error) {
                failureReason = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
            }

            if (attempt >= maxAttempts) {
                log.error("===> ES-Polling bulk retries exhausted, continue next batch. "
                                + "table={}, date={}, index={}, attempts={}, size={}, "
                                + "firstId={}, lastId={}, error={}",
                        tableName, syncDate, indexName, maxAttempts,
                        rows.size(), firstId, lastId, failureReason);
                try {
                    // 失败批次由对账和人工T+1修复闭环；通知提交失败也不能阻止Polling继续
                    notifyService.notifyPollingBulkFailed(tableName, indexName, syncDate,
                            firstId, lastId, maxAttempts, failureReason);
                } catch (RuntimeException notifyError) {
                    log.warn("===> ES-Polling bulk failure notification submit failed. "
                                    + "table={}, date={}, firstId={}, lastId={}, error={}",
                            tableName, syncDate, firstId, lastId,
                            notifyError.getMessage(), notifyError);
                }
                return;
            }
            log.error("===> ES-Polling bulk failed, retry whole batch. table={}, date={}, index={}, "
                            + "attempt={}/{}, size={}, firstId={}, lastId={}, error={}",
                    tableName, syncDate, indexName, attempt, maxAttempts,
                    rows.size(), firstId, lastId, failureReason);
            try {
                Thread.sleep(retryIntervalMillis);
            } catch (InterruptedException error) {
                // 已从MySQL读取的批次仍需完成重试；清除中断后继续，成功后Worker再处理停止标记
                Thread.interrupted();
            }
        }
    }
}
