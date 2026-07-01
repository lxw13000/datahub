package com.tsd.sano.es.importer.service;

import com.tsd.sano.es.core.config.EsImportProperties;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.model.ImportContext;
import com.tsd.sano.es.importer.model.ImportStatistics;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

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

    /**
     * 发送结束标记的队列等待时间，避免异常场景下永久阻塞。
     */
    private static final long END_SIGNAL_TIMEOUT_SECONDS = 1L;

    /**
     * 运行中的导入任务Key集合。
     *
     * <p>用于防止同一alias/date重复触发，避免并发创建同一个真实索引。</p>
     */
    private static final Set<String> RUNNING_IMPORT_KEYS = ConcurrentHashMap.newKeySet();

    private final EsImportProperties properties;
    private final EsIndexManager indexManager;
    private final JdbcDataReader jdbcDataReader;
    private final EsBulkImporter bulkImporter;
    private final ImportMonitor importMonitor;

    /**
     * 注入导入流程需要的各个协作组件。
     */
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
     * 按指定日期导入数据，主要用于开发和测试回放某一天的数据。
     *
     * @param indexAlias  业务alias，默认也作为表名
     * @param mappingFile resources/esmapping目录下的mapping文件名
     * @param yyyyMMdd    导入日期，格式为yyyyMMdd
     * @return 导入统计
     */
    public ImportStatistics importAppointDay(String indexAlias, String mappingFile, String yyyyMMdd) {
        EsImportConfig config = new EsImportConfig();
        config.setIndexAlias(indexAlias);
        config.setTableName(indexAlias);
        config.setMappingFile(mappingFile);
        config.setImportDate(parseImportDate(yyyyMMdd));
        return importData(config);
    }

    /**
     * 按完整配置执行一次导入。
     */
    public ImportStatistics importData(EsImportConfig config) {
        // 先补全默认配置，确保后续组件拿到完整的表名、索引名和日期。
        normalizeConfig(config);

        // 以真实索引名作为运行锁，防止同一alias/date被重复触发。
        String importKey = buildImportKey(config);
        if (!RUNNING_IMPORT_KEYS.add(importKey)) {
            // add返回false表示已有相同导入正在执行，直接拒绝本次重复请求。
            throw new ServiceException("ES import task is already running, alias=" + config.getIndexAlias()
                    + ", index=" + config.getIndexName()
                    + ", table=" + config.getTableName()
                    + ", date=" + config.getImportDate());
        }

        try {
            ImportStatistics statistics = new ImportStatistics();
            statistics.setStartTime(System.currentTimeMillis());

            // ImportContext贯穿Reader、Bulk、Index三个阶段，共享统计和中止信号。
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

                    // 先统计总量，避免无数据时创建空索引并挂alias。
                    long total = jdbcDataReader.count(context);
                    if (total <= 0L) {
                        throw new ServiceException("ES import no data, table=" + config.getTableName()
                                + ", date=" + config.getImportDate());
                    }

                    // 创建真实索引，不提前绑定alias，避免半成品索引被查询。
                    indexCreated = indexManager.createIndex(context);
                    if (!indexCreated) {
                        throw new ServiceException("ES import create index not acknowledged, index=" + config.getIndexName());
                    }

                    // 大批量写入前关闭refresh等参数，降低ES写入开销。
                    indexManager.beforeImport(context);
                    optimized = true;

                    // Bulk消费者先启动，再由Reader生产数据，形成读写流水线。
                    Future<?> bulkFuture = bulkExecutor.submit(() -> bulkImporter.importFromQueue(context));
                    try {
                        jdbcDataReader.readToQueue(context);
                    } catch (Exception e) {
                        // Reader失败时主动投递结束标记，让Bulk线程尽快退出。
                        offerEndSignals(context);
                        throw e;
                    }
                    // 等待Bulk线程全部消费完成，确保写入统计完整。
                    waitBulkFinished(bulkFuture);

                    checkImportResult(statistics);

                    // 成功写入后恢复索引参数并刷新，随后再绑定业务alias。
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
                        // 导入失败也尽量恢复索引参数，避免refresh长期关闭。
                        restoreIndexQuietly(context);
                    }
                    throw e instanceof ServiceException ServiceException
                            ? ServiceException
                            : new ServiceException("ES import failed, error=" + e.getMessage(), e);
                } finally {
                    statistics.setEndTime(System.currentTimeMillis());
                    monitorFinish(context);
                }
            }
        } finally {
            // 无论成功、失败还是异常中断，都释放运行中标记，避免后续任务被永久阻塞。
            RUNNING_IMPORT_KEYS.remove(importKey);
        }
    }

    /**
     * 补全默认配置，并生成真实索引名。
     */
    private void normalizeConfig(EsImportConfig config) {
        if (config == null) {
            throw new ServiceException("ES import config cannot be null");
        }

        String alias = requireText(config.getIndexAlias(), "indexAlias");
        if (StringUtils.isBlank(config.getTableName())) {
            // 未显式配置表名时，默认使用业务alias作为表名。
            config.setTableName(alias);
        }
        requireText(config.getMappingFile(), "mappingFile");

        if (config.getImportDate() == null) {
            // 默认按T+1导入昨天的数据。
            config.setImportDate(LocalDate.now().minusDays(1));
        }

        if (StringUtils.isBlank(config.getIndexName())) {
            // 真实索引按日期分区，业务alias可同时指向保留期内多个日期索引。
            config.setIndexName(alias + "_" + INDEX_DATE_FORMATTER.format(config.getImportDate()));
        }
    }

    /**
     * 解析yyyyMMdd格式导入日期。
     */
    private LocalDate parseImportDate(String yyyyMMdd) {
        String dateText = requireText(yyyyMMdd, "yyyyMMdd");
        try {
            return LocalDate.parse(dateText, INDEX_DATE_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new ServiceException("ES import yyyyMMdd format invalid, value=" + yyyyMMdd, e);
        }
    }

    /**
     * 构建导入运行锁Key。
     */
    private String buildImportKey(EsImportConfig config) {
        return requireText(config.getIndexName(), "indexName");
    }

    /**
     * 读取失败时主动发送结束标记，避免Bulk线程永久阻塞。
     */
    private void offerEndSignals(ImportContext context) {
        for (int i = 0; i < context.getProperties().getWorkerCount(); i++) {
            try {
                boolean offered = context.getQueue().offer(
                        List.<Map<String, Object>>of(),
                        END_SIGNAL_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                );
                if (!offered || context.isAborted()) {
                    // 队列满或Bulk已失败时停止投递，避免异常流程继续阻塞。
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ServiceException("ES import interrupted while sending end signal", e);
            }
        }
    }

    /**
     * 等待Bulk调度任务完成。
     */
    private void waitBulkFinished(Future<?> bulkFuture) {
        try {
            bulkFuture.get();
        } catch (Exception e) {
            throw new ServiceException("ES import bulk worker failed, error=" + e.getMessage(), e);
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
        double failureRate = total <= 0L ? 0D : (double) failed / total;

        if (total > 0L && success <= 0L) {
            // 全部失败说明索引不可用，绝不能绑定alias。
            throw new ServiceException("ES import all documents failed, total=" + total + ", failed=" + failed);
        }

        if (failed > properties.getMaxFailedDocuments() || failureRate > properties.getMaxFailureRate()) {
            // 失败超过阈值时中断上线，避免业务查询读到明显不完整的数据。
            throw new ServiceException("ES import failed documents exceed threshold, total=" + total
                    + ", success=" + success
                    + ", failed=" + failed
                    + ", failureRate=" + String.format("%.6f", failureRate));
        }

        if (failed > 0L) {
            log.warn("===> ES-Import finished with partial failed documents. total={}, success={}, failed={}, failureRate={}",
                    total, success, failed, String.format("%.6f", failureRate));
        }
    }

    /**
     * 异常场景下尽量恢复索引设置。
     */
    private void restoreIndexQuietly(ImportContext context) {
        try {
            indexManager.afterImport(context);
        } catch (Exception restoreError) {
            log.warn("===> ES-Import restore index settings failed. index={}, error={}",
                    context.getConfig().getIndexName(), restoreError.getMessage());
        }
    }

    /**
     * 触发导入开始监控。
     */
    private void monitorStart(ImportContext context) {
        if (properties.isEnableMonitor()) {
            importMonitor.onStart(context);
        }
    }

    /**
     * 触发导入完成监控。
     */
    private void monitorFinish(ImportContext context) {
        if (properties.isEnableMonitor()) {
            importMonitor.onFinish(context);
        }
    }

    /**
     * 触发导入失败监控。
     */
    private void monitorError(ImportContext context, Exception error) {
        if (properties.isEnableMonitor()) {
            importMonitor.onError(context, error);
        }
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
}
