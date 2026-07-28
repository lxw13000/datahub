package com.tsd.sano.es.modules.tplusone.pipeline;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.config.TableSyncMode;
import com.tsd.sano.es.modules.coordination.service.GlobalEsWritePermitManager;
import com.tsd.sano.es.modules.tplusone.model.ImportBatch;
import com.tsd.sano.es.modules.tplusone.model.ImportContext;
import com.tsd.sano.es.modules.tplusone.model.TPlusOneImportConfig;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ES Bulk导入器。
 *
 * <p>负责从导入队列消费数据库数据，拆分为Bulk请求写入ES，并维护成功、
 * 失败、批次数等统计信息。</p>
 */
@Service
public class TPlusOneBulkWriter {

    private static final Logger log = LoggerFactory.getLogger(TPlusOneBulkWriter.class);

    /**
     * 导入失败ID专项日志，独立输出到 es-server-import-error.log。
     */
    private static final Logger importErrorLog = LoggerFactory.getLogger("com.tsd.sano.es.modules.tplusone.error");

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

    /**
     * 慢Bulk请求阈值(ms)，超过该值打印warn用于压测观察。
     */
    private static final long SLOW_BULK_WARN_MS = 3000L;

    /**
     * Bulk进度日志打印间隔，避免每批都打印导致日志过多。
     */
    private static final long BULK_PROGRESS_LOG_INTERVAL = 20L;

    /**
     * 文档大小估算安全系数。
     *
     * <p>当前表结构字段稳定，按首条估算即可；增加10%余量，避免少数字段较长导致Bulk偏大。</p>
     */
    private static final double DOC_SIZE_SAFE_FACTOR = 1.1D;

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;
    private final GlobalEsWritePermitManager writePermitManager;

    /**
     * 注入ES客户端和JSON工具，JSON工具用于估算单条文档体积。
     */
    public TPlusOneBulkWriter(ElasticsearchClient client,
                              ObjectMapper objectMapper,
                              GlobalEsWritePermitManager writePermitManager) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.writePermitManager = writePermitManager;
    }

    /**
     * 启动多个Bulk工作线程，持续消费Reader写入的队列。
     */
    public void importFromQueue(ImportContext context) {
        EsImportProperties properties = requireProperties(context);
        int workerCount = properties.getTPlusOne().getWorkerCount();

        // Java 21中ExecutorService支持try-with-resources，导入结束后自动关闭工作线程池。
        try (ExecutorService executor = Executors.newFixedThreadPool(workerCount, new BulkThreadFactory())) {
            CompletionService<Void> completions = new ExecutorCompletionService<>(executor);

            for (int i = 0; i < workerCount; i++) {
                // 每个工作线程独立消费队列，提升Bulk写入吞吐。
                completions.submit(() -> {
                    consumeQueue(context);
                    return null;
                });
            }

            // 不再接收新任务，等待已提交的Bulk工作线程结束。
            executor.shutdown();
            waitWorkersDone(context, executor, completions, workerCount);
        }
    }

    /**
     * 单个工作线程循环消费队列，结束信号表示Reader已完成投递。
     */
    private void consumeQueue(ImportContext context) {
        try {
            while (true) {
                ImportBatch batch = takeBatch(context);
                if (batch.isEndSignal()) {
                    // 每个Worker收到一个独立结束信号后正常退出。
                    return;
                }

                boolean checkpointSafe = importRows(context, batch.rows());
                context.completeBatch(batch, checkpointSafe);
                if (!checkpointSafe) {
                    // item级失败仍按现有容差策略继续导入，但不能越过本批次保存续跑断点。
                    log.warn("===> ES-Import checkpoint blocked by unsafe batch. sequence={}, lastId={}, size={}",
                            batch.sequence(), batch.lastId(), batch.rows().size());
                }
            }
        } catch (RuntimeException e) {
            // 任意Bulk线程失败都要通知Reader停止生产，避免队列写入侧卡死。
            context.abort(e);
            throw e;
        }
    }

    /**
     * 将Reader读取的一批数据按bulkActions和bulkSizeMb继续拆分。
     *
     * @return true表示该Reader批次内所有文档均成功，可用于推进安全断点
     */
    private boolean importRows(ImportContext context, List<Map<String, Object>> rows) {
        EsImportProperties properties = requireProperties(context);
        int bulkActions = Math.max(1, properties.getTPlusOne().getBulkActions());
        int maxBulkBytes = Math.max(1, properties.getTPlusOne().getBulkSizeMb()) * MB;
        int actionLimit = estimateActionLimit(rows, bulkActions, maxBulkBytes);

        // chunk保存本次待发送的Bulk子批次，按配置数量和估算体积计算后的数量阈值切分。
        List<Map<String, Object>> chunk = new ArrayList<>(Math.min(rows.size(), actionLimit));
        boolean checkpointSafe = true;

        for (Map<String, Object> row : rows) {
            if (chunk.size() >= actionLimit) {
                // 达到阈值立即发送，避免单次请求过大影响ES稳定性。
                if (!sendWithRetry(context, chunk)) {
                    checkpointSafe = false;
                }
                chunk = new ArrayList<>(Math.min(rows.size(), actionLimit));
            }

            chunk.add(row);
        }

        if (!chunk.isEmpty()) {
            // 发送最后不足阈值的一批数据。
            if (!sendWithRetry(context, chunk)) {
                checkpointSafe = false;
            }
        }
        return checkpointSafe;
    }

    /**
     * Bulk请求失败时重试整批数据。
     *
     * <p>这里处理的是网络、ES服务不可用等请求级异常。请求级异常会重试，
     * 多次失败后中断流程；单条文档错误在Bulk响应中统计，不直接中断任务，
     * 但返回false阻止安全断点越过该批次。</p>
     */
    private boolean sendWithRetry(ImportContext context, List<Map<String, Object>> rows) {
        EsImportProperties properties = requireProperties(context);
        int maxAttempt = Math.max(0, properties.getTPlusOne().getRetryTimes()) + 1;

        for (int attempt = 1; attempt <= maxAttempt; attempt++) {
            try {
                long startTime = System.currentTimeMillis();
                BulkResponse response;
                // 许可证只覆盖真实ES请求时间，响应统计和重试等待均不占用全局并发额度。
                try (GlobalEsWritePermitManager.Permit ignored =
                             writePermitManager.acquire(TableSyncMode.T_PLUS_ONE)) {
                    response = sendBulk(context, rows);
                }
                long costMs = System.currentTimeMillis() - startTime;
                return handleResponse(context, response, rows.size(), costMs);
            } catch (IOException e) {
                if (attempt >= maxAttempt) {
                    // 请求级失败说明本批数据没有可靠写入，继续执行会得到不完整索引。
                    context.getStatistics().getFailed().addAndGet(rows.size());
                    logRequestFailedIds(context, rows, e.getMessage());
                    throw new ServiceException("ES bulk import failed after retry, size=" + rows.size()
                            + ", error=" + e.getMessage(), e);
                }

                log.warn("===> ES-Import bulk request failed, retry later. attempt={}/{}, size={}, error={}",
                        attempt, maxAttempt, rows.size(), e.getMessage());
                // 简单固定间隔重试，避免ES短暂抖动直接导致整次导入失败。
                sleepQuietly(properties.getTPlusOne().getRetryInterval());
            }
        }
        return false;
    }

    /**
     * 构建并发送Bulk请求。
     *
     * <p>单条数据缺少文档ID时只记录失败并跳过，不影响同批次其他数据写入。</p>
     */
    private BulkResponse sendBulk(ImportContext context, List<Map<String, Object>> rows) throws IOException {
        TPlusOneImportConfig config = requireConfig(context);
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
                logImportFailedId(context, null, "blank_document_id", "document id is blank, idColumn=" + idColumn);
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
     * <p>ES item级失败只影响对应文档，统计后继续处理后续批次；返回值用于
     * 判断当前Reader批次是否允许进入连续安全断点。</p>
     */
    private boolean handleResponse(ImportContext context, BulkResponse response, int requestSize, long costMs) {
        if (response == null) {
            // 空响应表示本批没有可发送文档，统计已在构建阶段处理。
            return false;
        }

        long failed = response.items().stream()
                .filter(item -> item.error() != null)
                .count();
        long success = response.items().size() - failed;

        context.getStatistics().getSuccess().addAndGet(success);
        context.getStatistics().getFailed().addAndGet(failed);
        long bulkCount = context.getStatistics().getBulkCount().incrementAndGet();

        if (response.errors()) {
            // 只打印有限条错误明细，完整失败数量进入统计对象。
            logBulkErrors(context, response);
        }

        if (costMs >= SLOW_BULK_WARN_MS) {
            log.warn("===> ES-Import slow bulk. requestSize={}, success={}, failed={}, costMs={}",
                    requestSize, success, failed, costMs);
        }

        if (bulkCount <= 5 || bulkCount % BULK_PROGRESS_LOG_INTERVAL == 0 || failed > 0) {
            log.info("===> ES-Import bulk progress. bulkCount={}, requestSize={}, success={}, failed={}, costMs={}, totalSuccess={}, totalFailed={}",
                    bulkCount,
                    requestSize,
                    success,
                    failed,
                    costMs,
                    context.getStatistics().getSuccess().get(),
                    context.getStatistics().getFailed().get());
        }

        // 响应条数少于请求条数说明存在缺少文档ID而被跳过的记录，同样不能推进安全断点。
        return failed == 0L && response.items().size() == requestSize;
    }

    /**
     * 打印Bulk item级失败明细。
     */
    private void logBulkErrors(ImportContext context, BulkResponse response) {
        int count = 0;
        for (BulkResponseItem item : response.items()) {
            if (item.error() == null) {
                continue;
            }

            logImportFailedId(context, item.id(), String.valueOf(item.status()), item.error().reason());

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
     * 记录请求级失败涉及的全部文档ID。
     */
    private void logRequestFailedIds(ImportContext context, List<Map<String, Object>> rows, String reason) {
        TPlusOneImportConfig config = requireConfig(context);
        String idColumn = requireText(config.getIdColumn(), "idColumn");

        for (Map<String, Object> row : rows) {
            String documentId = extractDocumentId(row, idColumn);
            logImportFailedId(context, documentId, "bulk_request_failed", reason);
        }
    }

    /**
     * 输出导入失败ID专项日志。
     *
     * <p>格式突出表名和ID，便于直接回查数据源。</p>
     */
    private void logImportFailedId(ImportContext context, String documentId, String status, String reason) {
        TPlusOneImportConfig config = requireConfig(context);
        importErrorLog.error("===> ES-Import failed document. table={} id:{} index={} alias={} status={} reason={}",
                config.getTableName(),
                StringUtils.defaultIfBlank(documentId, "UNKNOWN"),
                config.getIndexName(),
                config.getIndexAlias(),
                status,
                reason);
    }

    /**
     * 从共享队列中获取一批待读取数据。
     */
    private ImportBatch takeBatch(ImportContext context) {
        try {
            return context.getQueue().take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("ES bulk importer interrupted while waiting queue", e);
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
     * 根据首条文档估算本批次允许的最大文档数。
     *
     * <p>当前同步表字段结构稳定，逐条估算会造成额外JSON序列化开销；按首条估算并加安全系数，
     * 能在稳定性和性能之间取得更合适的平衡。</p>
     */
    private int estimateActionLimit(List<Map<String, Object>> rows, int bulkActions, int maxBulkBytes) {
        if (rows.isEmpty()) {
            return bulkActions;
        }

        // 只抽样首条记录，避免在Bulk发送前重复遍历和序列化整批数据。
        int estimatedDocBytes = Math.max(1, (int) Math.ceil(estimateDocBytes(rows.getFirst()) * DOC_SIZE_SAFE_FACTOR));
        int sizeLimitActions = Math.max(1, maxBulkBytes / estimatedDocBytes);
        return Math.max(1, Math.min(bulkActions, sizeLimitActions));
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
    private void waitWorkersDone(ImportContext context,
                                 ExecutorService executor,
                                 CompletionService<Void> completions,
                                 int workerCount) {
        try {
            for (int i = 0; i < workerCount; i++) {
                // 按实际完成顺序观察结果；任一worker失败后立即中断其余worker，避免顺序get永久等待。
                completions.take().get();
            }
            if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                log.warn("===> ES-Import bulk worker pool still terminating");
            }
        } catch (Exception e) {
            context.abort(e);
            executor.shutdownNow();
            throw new ServiceException("ES bulk importer worker failed, error=" + e.getMessage(), e);
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
            throw new ServiceException("ES bulk retry interrupted", e);
        }
    }

    /**
     * 校验导入上下文并返回业务配置。
     */
    private TPlusOneImportConfig requireConfig(ImportContext context) {
        if (context == null || context.getConfig() == null) {
            throw new ServiceException("ES import context config cannot be null");
        }
        return context.getConfig();
    }

    /**
     * 校验并返回导入全局参数。
     */
    private EsImportProperties requireProperties(ImportContext context) {
        if (context == null || context.getProperties() == null) {
            throw new ServiceException("ES import properties cannot be null");
        }
        return context.getProperties();
    }

    /**
     * 校验必填字符串参数。
     */
    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new ServiceException("ES import " + fieldName + " cannot be blank");
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
