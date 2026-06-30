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
     * 失败明细最多打印条数，避免大量错误刷屏。
     */
    private static final int MAX_ERROR_LOG_COUNT = 10;

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;

    /**
     * 注入ES客户端和JSON工具，JSON工具用于估算单条文档体积。
     */
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
                // 每个工作线程独立消费队列，提升Bulk写入吞吐。
                futures.add(executor.submit(() -> consumeQueue(context)));
            }

            // 不再接收新任务，等待已提交的Bulk工作线程结束。
            executor.shutdown();
            waitWorkersDone(executor, futures);
        }
    }

    /**
     * 单个工作线程循环消费队列，空批次表示Reader已结束。
     */
    private void consumeQueue(ImportContext context) {
        try {
            while (true) {
                List<Map<String, Object>> rows = takeBatch(context);
                if (rows.isEmpty()) {
                    // Reader投递空集合表示没有更多数据，当前工作线程正常退出。
                    return;
                }
                importRows(context, rows);
            }
        } catch (RuntimeException e) {
            // 任意Bulk线程失败都要通知Reader停止生产，避免队列写入侧卡死。
            context.abort(e);
            throw e;
        }
    }

    /**
     * 将Reader读取的一批数据按bulkActions和bulkSizeMb继续拆分。
     */
    private void importRows(ImportContext context, List<Map<String, Object>> rows) {
        EsImportProperties properties = requireProperties(context);
        int bulkActions = Math.max(1, properties.getBulkActions());
        int maxBulkBytes = Math.max(1, properties.getBulkSizeMb()) * MB;

        // chunk保存本次待发送的Bulk子批次，按数量和字节大小双阈值切分。
        List<Map<String, Object>> chunk = new ArrayList<>(Math.min(rows.size(), bulkActions));
        int chunkBytes = 0;

        for (Map<String, Object> row : rows) {
            // 估算文档体积，用于避免单个Bulk请求过大。
            int rowBytes = estimateDocBytes(row);
            boolean reachActionLimit = chunk.size() >= bulkActions;
            boolean reachSizeLimit = !chunk.isEmpty() && chunkBytes + rowBytes > maxBulkBytes;

            if (reachActionLimit || reachSizeLimit) {
                // 达到阈值立即发送，避免单次请求过大影响ES稳定性。
                sendWithRetry(context, chunk);
                chunk = new ArrayList<>(Math.min(rows.size(), bulkActions));
                chunkBytes = 0;
            }

            chunk.add(row);
            chunkBytes += rowBytes;
        }

        if (!chunk.isEmpty()) {
            // 发送最后不足阈值的一批数据。
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
                // 简单固定间隔重试，避免ES短暂抖动直接导致整次导入失败。
                sleepQuietly(properties.getRetryInterval());
            }
        }
    }

    /**
     * 构建并发送Bulk请求。
     *
     * <p>单条数据缺少文档ID时只记录失败并跳过，不影响同批次其他数据写入。</p>
     */
    private BulkResponse sendBulk(ImportContext context, List<Map<String, Object>> rows) throws IOException {
        EsImportConfig config = requireConfig(context);
        String indexName = requireText(config.getIndexName(), "indexName");
        String idColumn = requireText(config.getIdColumn(), "idColumn");

        BulkRequest.Builder builder = new BulkRequest.Builder()
                .refresh(Refresh.False);
        int validCount = 0;

        for (Map<String, Object> row : rows) {
            // 文档ID必须稳定，保证重跑同一批数据时ES写入幂等。
            String documentId = extractDocumentId(row, idColumn);
            if (StringUtils.isBlank(documentId)) {
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
            // 整个子批次都无有效文档时跳过请求，避免发送空Bulk。
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
            // 空响应表示本批没有可发送文档，统计已在构建阶段处理。
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
            // 只打印有限条错误明细，完整失败数量进入统计对象。
            logBulkErrors(response);
        }

        log.info("===> ES-Import bulk finished. success={}, failed={}, totalSuccess={}, totalFailed={}",
                success,
                failed,
                context.getStatistics().getSuccess().get(),
                context.getStatistics().getFailed().get());
    }

    /**
     * 打印Bulk item级失败明细。
     */
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
                // 失败过多时截断日志，防止单次导入刷爆日志文件。
                break;
            }
        }
    }

    /**
     * 从共享队列中获取一批待读取数据。
     */
    private List<Map<String, Object>> takeBatch(ImportContext context) {
        try {
            return context.getQueue().take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("ES bulk importer interrupted while waiting queue", e);
        }
    }

    /**
     * 估算单条文档序列化后的字节数。
     */
    private int estimateDocBytes(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsBytes(row).length;
        } catch (Exception e) {
            // 估算失败不影响导入，使用保守默认值继续切分。
            return DEFAULT_DOC_BYTES;
        }
    }

    /**
     * 获取ES文档ID。
     *
     * <p>优先使用业务主键。若主键缺失则跳过该条数据，避免随机ID造成重跑重复文档。</p>
     */
    private String extractDocumentId(Map<String, Object> row, String idColumn) {
        Object value = row.get(idColumn);
        if (value != null && StringUtils.isNotBlank(value.toString())) {
            return value.toString();
        }

        // 缺少稳定业务ID时不能随机生成，否则重跑会产生重复文档。
        return null;
    }

    /**
     * 等待所有Bulk工作线程结束。
     */
    private void waitWorkersDone(ExecutorService executor, List<Future<?>> futures) {
        try {
            for (Future<?> future : futures) {
                // future.get会向上抛出工作线程异常，保证主流程感知失败。
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

    /**
     * 重试间隔等待。
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("ES bulk retry interrupted", e);
        }
    }

    /**
     * 校验导入上下文并返回业务配置。
     */
    private EsImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new BusinessException("ES import context config cannot be null");
        }
        return context.getConfig();
    }

    /**
     * 校验并返回导入全局参数。
     */
    private EsImportProperties requireProperties(ImportContext context) {
        if (context == null || context.getProperties() == null) {
            throw new BusinessException("ES import properties cannot be null");
        }
        return context.getProperties();
    }

    /**
     * 校验必填字符串参数。
     */
    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }

    /**
     * Bulk工作线程工厂，用于设置可识别的线程名。
     */
    private static class BulkThreadFactory implements ThreadFactory {

        /**
         * 线程序号，便于日志中定位具体工作线程。
         */
        private final AtomicInteger index = new AtomicInteger(1);

        /**
         * 创建Bulk工作线程。
         */
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName("es-bulk-importer-" + index.getAndIncrement());
            return thread;
        }
    }
}
