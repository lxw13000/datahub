package com.tsd.sano.es.importer.service;

import com.tsd.sano.es.core.config.EsImportProperties;
import com.tsd.sano.es.core.exception.BusinessException;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.model.ImportContext;
import com.tsd.sano.es.importer.model.ImportStatistics;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * ES导入总入口。
 *
 * <p>负责组织一次完整导入流程：创建索引、启动Bulk消费者、读取数据库、
 * 恢复索引参数、切换alias、清理历史索引。</p>
 */
@Service
public class EsImportService {

    private static final Logger log = LoggerFactory.getLogger(EsImportService.class);

    /**
     * 真实索引日期后缀格式，生成 alias_yyyyMMdd。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final EsImportProperties properties;
    private final EsIndexManager indexManager;
    private final JdbcDataReader jdbcDataReader;
    private final EsBulkImporter bulkImporter;
    private final ImportMonitor importMonitor;

    public EsImportService(EsImportProperties properties,
                           EsIndexManager indexManager,
                           JdbcDataReader jdbcDataReader,
                           EsBulkImporter bulkImporter,
                           ImportMonitor importMonitor) {
        this.properties = properties;
        this.indexManager = indexManager;
        this.jdbcDataReader = jdbcDataReader;
        this.bulkImporter = bulkImporter;
        this.importMonitor = importMonitor;
    }

    /**
     * 按业务alias和mapping文件导入T+1数据。
     *
     * <p>默认表名等于alias，导入日期为昨天。
     */
    public ImportStatistics importYesterday(String indexAlias, String mappingFile) {
        EsImportConfig config = new EsImportConfig();
        config.setIndexAlias(indexAlias);
        config.setTableName(indexAlias);
        config.setMappingFile(mappingFile);
        config.setImportDate(LocalDate.now());
        return importData(config);
    }

    /**
     * 按完整配置执行一次导入。
     */
    public ImportStatistics importData(EsImportConfig config) {
        normalizeConfig(config);

        ImportStatistics statistics = new ImportStatistics();
        statistics.setStartTime(System.currentTimeMillis());

        ImportContext context = new ImportContext(config, statistics, properties);

        // Java 21中ExecutorService支持try-with-resources，确保线程池生命周期跟随本次导入结束。
        try (ExecutorService bulkExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("es-import-bulk-dispatcher");
            return thread;
        })) {

            boolean indexCreated = false;
            boolean optimized = false;

            try {
                log.info("===> ES-Import start. alias={}, index={}, table={}, date={}",
                        config.getIndexAlias(), config.getIndexName(), config.getTableName(), config.getImportDate());

                monitorStart(context);

                long total = jdbcDataReader.count(context);
                if (total <= 0L) {
                    throw new BusinessException("ES import no data, table=" + config.getTableName()
                            + ", date=" + config.getImportDate());
                }

                indexCreated = indexManager.createIndex(context);
                if (!indexCreated) {
                    throw new BusinessException("ES import create index not acknowledged, index=" + config.getIndexName());
                }

                indexManager.beforeImport(context);
                optimized = true;

                Future<?> bulkFuture = bulkExecutor.submit(() -> bulkImporter.importFromQueue(context));
                try {
                    jdbcDataReader.readToQueue(context);
                } catch (Exception e) {
                    offerEndSignals(context);
                    throw e;
                }
                waitBulkFinished(bulkFuture);

                checkImportResult(statistics);

                indexManager.afterImport(context);
                indexManager.switchAlias(context);
                indexManager.deleteHistoryIndices(context);

                log.info("===> ES-Import success. alias={}, index={}, total={}, success={}, costMs={}",
                        config.getIndexAlias(),
                        config.getIndexName(),
                        statistics.getTotal().get(),
                        statistics.getSuccess().get(),
                        System.currentTimeMillis() - statistics.getStartTime());
                return statistics;
            } catch (Exception e) {
                // 异常时主动停止调度线程池，try-with-resources会再次兜底关闭。
                bulkExecutor.shutdownNow();
                monitorError(context, e);
                if (optimized) {
                    restoreIndexQuietly(context);
                }
                throw e instanceof BusinessException businessException
                        ? businessException
                        : new BusinessException("ES import failed, error=" + e.getMessage(), e);
            } finally {
                statistics.setEndTime(System.currentTimeMillis());
                monitorFinish(context);
            }
        }
    }

    /**
     * 补全默认配置，并生成真实索引名。
     */
    private void normalizeConfig(EsImportConfig config) {
        if (config == null) {
            throw new BusinessException("ES import config cannot be null");
        }

        String alias = requireText(config.getIndexAlias(), "indexAlias");
        if (StringUtils.isBlank(config.getTableName())) {
            config.setTableName(alias);
        }
        requireText(config.getMappingFile(), "mappingFile");

        if (config.getImportDate() == null) {
            config.setImportDate(LocalDate.now().minusDays(1));
        }

        if (StringUtils.isBlank(config.getIndexName())) {
            config.setIndexName(alias + "_" + INDEX_DATE_FORMATTER.format(config.getImportDate()));
        }
    }

    /**
     * 读取失败时主动发送结束标记，避免Bulk线程永久阻塞。
     */
    private void offerEndSignals(ImportContext context) {
        for (int i = 0; i < context.getProperties().getWorkerCount(); i++) {
            try {
                context.getQueue().put(List.<Map<String, Object>>of());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("ES import interrupted while sending end signal", e);
            }
        }
    }

    private void waitBulkFinished(Future<?> bulkFuture) {
        try {
            bulkFuture.get();
        } catch (Exception e) {
            throw new BusinessException("ES import bulk worker failed, error=" + e.getMessage(), e);
        }
    }

    /**
     * 校验导入结果。
     *
     * <p>部分失败通常是单条脏数据或少量ES item错误，只记录告警并继续挂alias；
     * 全部失败说明索引不可用，必须中断，避免查询侧读到空索引。</p>
     */
    private void checkImportResult(ImportStatistics statistics) {
        long total = statistics.getTotal().get();
        long success = statistics.getSuccess().get();
        long failed = statistics.getFailed().get();

        if (total > 0L && success <= 0L) {
            throw new BusinessException("ES import all documents failed, total=" + total + ", failed=" + failed);
        }

        if (failed > 0L) {
            log.warn("===> ES-Import finished with partial failed documents. total={}, success={}, failed={}",
                    total, success, failed);
        }
    }

    private void restoreIndexQuietly(ImportContext context) {
        try {
            indexManager.afterImport(context);
        } catch (Exception restoreError) {
            log.warn("===> ES-Import restore index settings failed. index={}, error={}",
                    context.getConfig().getIndexName(), restoreError.getMessage());
        }
    }

    private void monitorStart(ImportContext context) {
        if (properties.isEnableMonitor()) {
            importMonitor.onStart(context);
        }
    }

    private void monitorFinish(ImportContext context) {
        if (properties.isEnableMonitor()) {
            importMonitor.onFinish(context);
        }
    }

    private void monitorError(ImportContext context, Exception error) {
        if (properties.isEnableMonitor()) {
            importMonitor.onError(context, error);
        }
    }

    private String requireText(String value, String fieldName) {
        if (StringUtils.isBlank(value)) {
            throw new BusinessException("ES import " + fieldName + " cannot be blank");
        }
        return value.trim();
    }
}
