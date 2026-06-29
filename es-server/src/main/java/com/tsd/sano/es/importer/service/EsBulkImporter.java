package com.tsd.sano.es.importer.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.core.config.EsImportProperties;
import com.tsd.sano.es.core.exception.BusinessException;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.model.ImportContext;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ES Bulk导入器。
 *
 * <p>负责从导入队列消费数据库数据，拆分为Bulk请求写入ES，并维护成功、
 * 失败、批次数等统计信息。</p>
 */
@Service
public class EsBulkImporter {

    private static final Logger log = LoggerFactory.getLogger(EsBulkImporter.class);

    /**
     * 字节换算单位，用于bulkSizeMb配置转字节。
     */
    private static final int MB = 1024 * 1024;

    /**
     * 单条文档大小估算失败时使用的保守默认值。
     */
    private static final int DEFAULT_DOC_BYTES = 1024;

    /**
     * 自动生成文档ID的前缀。
     *
     * <p>仅在业务ID缺失时兜底使用，正常数据必须优先使用数据库主键。</p>
     */
    private static final String GENERATED_ID_PREFIX = "generated_";

    /**
     * 失败明细最多打印条数，避免大量错误刷屏。
     */
    private static final int MAX_ERROR_LOG_COUNT = 10;

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    public EsBulkImporter(ElasticsearchClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动多个Bulk工作线程，持续消费Reader写入的队列。
     */
    public void importFromQueue(ImportContext context) {
        EsImportProperties properties = requireProperties(context);
        int workerCount = properties.getWorkerCount();

        // Java 21中ExecutorService支持try-with-resources，导入结束后自动关闭工作线程池。
        try (ExecutorService executor = Executors.newFixedThreadPool(workerCount, new BulkThreadFactory())) {
            List<Future<?>> futures = new ArrayList<>(workerCount);

            for (int i = 0; i < workerCount; i++) {
                futures.add(executor.submit(() -> consumeQueue(context)));
            }

            executor.shutdown();
            waitWorkersDone(executor, futures);
        }
    }

    /**
     * 单个工作线程循环消费队列，空批次表示Reader已结束。
     */
    private void consumeQueue(ImportContext context) {
        while (true) {
            List<Map<String, Object>> rows = takeBatch(context);
            if (rows.isEmpty()) {
                return;
            }
            importRows(context, rows);
        }
    }

    /**
     * 将Reader读取的一批数据按bulkActions和bulkSizeMb继续拆分。
     */
    private void importRows(ImportContext context, List<Map<String, Object>> rows) {
        EsImportProperties properties = requireProperties(context);
        int bulkActions = Math.max(1, properties.getBulkActions());
        int maxBulkBytes = Math.max(1, properties.getBulkSizeMb()) * MB;

        List<Map<String, Object>> chunk = new ArrayList<>(Math.min(rows.size(), bulkActions));
        int chunkBytes = 0;

        for (Map<String, Object> row : rows) {
            int rowBytes = estimateDocBytes(row);
            boolean reachActionLimit = chunk.size() >= bulkActions;
            boolean reachSizeLimit = !chunk.isEmpty() && chunkBytes + rowBytes > maxBulkBytes;

            if (reachActionLimit || reachSizeLimit) {
                sendWithRetry(context, chunk);
                chunk = new ArrayList<>(Math.min(rows.size(), bulkActions));
                chunkBytes = 0;
            }

            chunk.add(row);
            chunkBytes += rowBytes;
        }

        if (!chunk.isEmpty()) {
            sendWithRetry(context, chunk);
        }
    }

    /**
     * Bulk请求失败时重试整批数据。
     *
     * <p>这里处理的是网络、ES服务不可用等请求级异常。请求级异常会重试，
     * 多次失败后中断流程；单条文档错误在Bulk响应中统计，不直接中断任务。</p>
     */
    private void sendWithRetry(ImportContext context, List<Map<String, Object>> rows) {
        EsImportProperties properties = requireProperties(context);
        int maxAttempt = Math.max(0, properties.getRetryTimes()) + 1;

        for (int attempt = 1; attempt <= maxAttempt; attempt++) {
            try {
                BulkResponse response = sendBulk(context, rows);
                handleResponse(context, response);
                return;
            } catch (IOException e) {
                if (attempt >= maxAttempt) {
                    // 请求级失败说明本批数据没有可靠写入，继续执行会得到不完整索引。
                    context.getStatistics().getFailed().addAndGet(rows.size());
                    throw new BusinessException("ES bulk import failed after retry, size=" + rows.size()
                            + ", error=" + e.getMessage(), e);
                }

                log.warn("===> ES-Import bulk request failed, retry later. attempt={}/{}, size={}, error={}",
                        attempt, maxAttempt, rows.size(), e.getMessage());
                sleepQuietly(properties.getRetryInterval());
            }
        }
    }

    /**
     * 构建并发送Bulk请求。
     *
     * <p>单条数据无法生成文档ID时只记录失败并跳过，不影响同批次其他数据写入。</p>
     */
    private BulkResponse sendBulk(ImportContext context, List<Map<String, Object>> rows) throws IOException {
        EsImportConfig config = requireConfig(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        String idColumn = requireText(config.getIdColumn(), "idColumn");

        BulkRequest.Builder builder = new BulkRequest.Builder()
                .refresh(Refresh.False);
        int validCount = 0;

        for (Map<String, Object> row : rows) {
            String documentId = extractDocumentId(row, idColumn);
            if (StringUtils.isBlank(documentId)) {
                // 理论兜底分支：当前extractDocumentId会生成临时ID，保留该判断防御后续改动。
                context.getStatistics().getFailed().incrementAndGet();
                log.warn("===> ES-Import skip row because document id is blank. idColumn={}, row={}", idColumn, row);
                continue;
            }
            builder.operations(operation -> operation.index(index -> index
                    .index(indexName)
                    .id(documentId)
                    .document(row)
            ));
            validCount++;
        }

        if (validCount == 0) {
            log.warn("===> ES-Import skip bulk because no valid documents. rows={}", rows.size());
            return null;
        }

        return client.bulk(builder.build());
    }

    /**
     * 处理Bulk响应中的成功和失败明细。
     *
     * <p>ES item级失败只影响对应文档，统计后继续处理后续批次。</p>
     */
    private void handleResponse(ImportContext context, BulkResponse response) {
        if (response == null) {
            return;
        }

        long failed = response.items().stream()
                .filter(item -> item.error() != null)
                .count();
        long success = response.items().size() - failed;

        context.getStatistics().getSuccess().addAndGet(success);
        context.getStatistics().getFailed().addAndGet(failed);
        context.getStatistics().getBulkCount().incrementAndGet();

        if (response.errors()) {
            logBulkErrors(response);
        }

        log.info("===> ES-Import bulk finished. success={}, failed={}, totalSuccess={}, totalFailed={}",
                success,
                failed,
                context.getStatistics().getSuccess().get(),
                context.getStatistics().getFailed().get());
    }

    private void logBulkErrors(BulkResponse response) {
        int count = 0;
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }
            log.warn("===> ES-Import bulk item failed. id={}, status={}, reason={}",
                    item.id(), item.status(), item.error().reason());
            count++;
            if (count >= MAX_ERROR_LOG_COUNT) {
                break;
            }
        }
    }

    private List<Map<String, Object>> takeBatch(ImportContext context) {
        try {
            return context.getQueue().take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("ES bulk importer interrupted while waiting queue", e);
        }
    }

    private int estimateDocBytes(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsBytes(row).length;
        } catch (Exception e) {
            return DEFAULT_DOC_BYTES;
        }
    }

    /**
     * 获取ES文档ID。
     *
     * <p>优先使用业务主键。若主键缺失，生成临时ID兜底，避免单条脏数据中断整个批次。
     * 注意：临时ID无法保证重跑幂等，因此会记录warn日志，后续应排查源表数据。</p>
     */
    private String extractDocumentId(Map<String, Object> row, String idColumn) {
        Object value = row.get(idColumn);
        if (value != null && StringUtils.isNotBlank(value.toString())) {
            return value.toString();
        }

        String generatedId = GENERATED_ID_PREFIX + System.currentTimeMillis() + "_" + UUID.randomUUID();
        log.warn("===> ES-Import document id missing, generated fallback id. idColumn={}, generatedId={}, row={}",
                idColumn, generatedId, row);
        return generatedId;
    }

    private void waitWorkersDone(ExecutorService executor, List<Future<?>> futures) {
        try {
            for (Future<?> future : futures) {
                future.get();
            }
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                log.warn("===> ES-Import bulk worker pool still terminating");
            }
        } catch (Exception e) {
            executor.shutdownNow();
            throw new BusinessException("ES bulk importer worker failed, error=" + e.getMessage(), e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("ES bulk retry interrupted", e);
        }
    }

    private EsImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new BusinessException("ES import context config cannot be null");
        }
        return context.getConfig();
    }

    private EsImportProperties requireProperties(ImportContext context) {
        if (context == null || context.getProperties() == null) {
            throw new BusinessException("ES import properties cannot be null");
        }
        return context.getProperties();
    }

    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }

    private static class BulkThreadFactory implements ThreadFactory {

        private final AtomicInteger index = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("es-bulk-importer-" + index.getAndIncrement());
            return thread;
        }
    }
}
