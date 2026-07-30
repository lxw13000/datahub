package com.tsd.sano.es.modules.tplusone.pipeline;

import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.EsImportProperties;
import com.tsd.sano.es.modules.tplusone.service.TPlusOneIndexService;
import com.tsd.sano.es.modules.tplusone.model.TPlusOneImportConfig;
import com.tsd.sano.es.modules.tplusone.model.ImportContext;
import com.tsd.sano.es.modules.tplusone.model.ImportStatistics;
import com.tsd.sano.es.modules.config.EsServiceModeManager;
import com.tsd.sano.es.modules.coordination.service.SyncDrainCoordinator;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.*;

/**
 * ES导入总入口。
 *
 * <p>负责组织一次完整导入流程：归一化配置、防止同一索引重复运行、统计源数据、
 * 创建或复用索引、启动Reader/Bulk流水线、恢复索引参数、切换alias并清理历史索引。</p>
 */
@Service
public class TPlusOneImportService {

    private static final Logger log = LoggerFactory.getLogger(TPlusOneImportService.class);

    /**
     * 真实索引日期后缀格式，生成 alias_yyyyMMdd。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    /**
     * 运行中的导入任务Key集合。
     *
     * <p>用于防止同一alias/date重复触发，避免并发创建同一个真实索引。</p>
     */
    private static final Set<String> RUNNING_IMPORT_KEYS = ConcurrentHashMap.newKeySet();

    /** T+1导入参数。 */
    private final EsImportProperties properties;

    /** 真实索引的创建、优化、alias切换及历史清理组件。 */
    private final TPlusOneIndexService indexService;

    /** MySQL计数及分页读取组件。 */
    private final TPlusOneJdbcReader jdbcReader;

    /** ES Bulk队列消费组件。 */
    private final TPlusOneBulkWriter bulkWriter;

    /** 部署排空与当前T+1上下文协调组件。 */
    private final SyncDrainCoordinator drainCoordinator;

    /** 当前实例是否允许启动同步链路的运行模式门禁。 */
    private final EsServiceModeManager serviceModeManager;

    /**
     * 注入导入流程需要的各个协作组件。
     */
    public TPlusOneImportService(EsImportProperties properties,
                                 TPlusOneIndexService indexService,
                                 TPlusOneJdbcReader jdbcReader,
                                 TPlusOneBulkWriter bulkWriter,
                                 SyncDrainCoordinator drainCoordinator,
                                 EsServiceModeManager serviceModeManager) {
        this.properties = properties;
        this.indexService = indexService;
        this.jdbcReader = jdbcReader;
        this.bulkWriter = bulkWriter;
        this.drainCoordinator = drainCoordinator;
        this.serviceModeManager = serviceModeManager;
    }

    /**
     * 按完整配置执行一次导入，可用于限时任务和断点续跑。
     *
     * @param config         导入配置
     * @param deadlineMillis 本次导入截止时间戳，0表示不启用deadline
     */
    public ImportStatistics importData(TPlusOneImportConfig config, long deadlineMillis) {
        // 防御直接调用导入服务绕过调度器或Web门禁；query模式不允许创建Reader/Bulk链路。
        serviceModeManager.requireSyncEnabled();
        // 先补全默认配置，确保后续组件拿到完整的表名、索引名和日期。
        normalizeConfig(config);

        // 以真实索引名作为运行锁，防止同一alias/date被重复触发。
        String importKey = requireText(config.getIndexName(), "indexName");
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
            statistics.setLastId(Math.max(0L, config.getStartId()));
            statistics.setLastSuccessId(Math.max(0L, config.getStartId()));

            // ImportContext贯穿Reader、Bulk、Index三个阶段，共享统计和中止信号。
            ImportContext context = new ImportContext(config, statistics, properties, deadlineMillis);
            drainCoordinator.attachTPlusOneContext(config, context);

            // Java 21中ExecutorService支持try-with-resources，确保线程池生命周期跟随本次导入结束。
            try (ExecutorService bulkExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("es-import-bulk-dispatcher");
                return thread;
            })) {

                boolean optimized = false;

                try {
                    log.info("===> ES-TPlusOne start. alias={}, index={}, table={}, date={}, startId={}",
                            config.getIndexAlias(),
                            config.getIndexName(),
                            config.getTableName(),
                            config.getImportDate(),
                            config.getStartId());

                    // 先统计总量，避免无数据时创建空索引并挂alias。
                    long total = jdbcReader.count(context);
                    if (statistics.isTimeoutPartial()) {
                        // drain在COUNT之前或执行期间到达时，不再创建索引或启动Reader/Bulk流水线。
                        log.warn("===> ES-TPlusOne stopped after count for drain. alias={}, table={}, date={}, operationId={}",
                                config.getIndexAlias(), config.getTableName(), config.getImportDate(),
                                statistics.getStopOperationId());
                        return statistics;
                    }
                    if (total <= 0L) {
                        // 指定日期无数据属于正常业务结果，不创建空索引、不切换alias，任务状态按SUCCESS处理。
                        log.warn("===> ES-TPlusOne no data, skip import. alias={}, table={}, date={}",
                                config.getIndexAlias(), config.getTableName(), config.getImportDate());
                        // 即使当天无数据，也按表级保留策略尝试清理一个刚过期的历史索引。
                        indexService.deleteHistoryIndex(context);
                        return statistics;
                    }

                    if (indexService.exists(config.getIndexName())) {
                        log.info("===> ES-TPlusOne reuse existing index. index={}, startId={}",
                                config.getIndexName(), config.getStartId());
                    } else {
                        if (config.getStartId() > 0L) {
                            throw new ServiceException("ES import checkpoint index not exists, index="
                                    + config.getIndexName() + ", startId=" + config.getStartId());
                        }
                        // 新索引不提前绑定Alias，避免半成品数据进入查询范围。
                        indexService.createIndex(context);
                        log.info("===> ES-TPlusOne index created. index={}", config.getIndexName());
                    }

                    // 大批量写入前关闭refresh等参数，降低ES写入开销。
                    indexService.beforeImport(context);
                    optimized = true;

                    // Bulk消费者先启动，再由Reader生产数据，形成读写流水线。
                    Future<?> bulkFuture = bulkExecutor.submit(() -> bulkWriter.importFromQueue(context));
                    try {
                        jdbcReader.readToQueue(context);
                    } catch (Exception e) {
                        // Reader异常不是正常生产结束；中断Bulk调度器，避免多worker因结束信号不足永久等待。
                        context.abort(e);
                        bulkFuture.cancel(true);
                        bulkExecutor.shutdownNow();
                        throw e;
                    }
                    // 等待Bulk线程全部消费完成，确保写入统计完整；统一转换异步执行异常。
                    try {
                        bulkFuture.get();
                    } catch (Exception e) {
                        throw new ServiceException("ES import bulk worker failed, error=" + e.getMessage(), e);
                    }

                    if (statistics.isTimeoutPartial()) {
                        // 超时暂停只恢复索引参数并刷新，不切换alias，避免线上读到半成品索引。
                        indexService.afterImport(context);
                        log.warn("===> ES-TPlusOne timeout partial. alias={}, index={}, total={}, read={}, success={}, failed={}, lastReadId={}, safeCheckpointId={}, blockedSequence={}, costMs={}",
                                config.getIndexAlias(),
                                config.getIndexName(),
                                statistics.getTotal().get(),
                                statistics.getRead().get(),
                                statistics.getSuccess().get(),
                                statistics.getFailed().get(),
                                statistics.getLastId(),
                                statistics.getLastSuccessId(),
                                statistics.getCheckpointBlockedSequence(),
                                System.currentTimeMillis() - statistics.getStartTime());
                        return statistics;
                    }

                    checkImportResult(statistics);

                    // 成功写入后恢复索引参数并刷新，随后再绑定业务alias。
                    indexService.afterImport(context);
                    indexService.bindAlias(context);
                    indexService.deleteHistoryIndex(context);

                    log.info("===> ES-TPlusOne success. alias={}, index={}, total={}, success={}, costMs={}",
                            config.getIndexAlias(),
                            config.getIndexName(),
                            statistics.getTotal().get(),
                            statistics.getSuccess().get(),
                            System.currentTimeMillis() - statistics.getStartTime());
                    return statistics;
                } catch (Exception e) {
                    // 异常时主动停止调度线程池，try-with-resources会再次兜底关闭。
                    bulkExecutor.shutdownNow();
                    if (optimized) {
                        // 导入失败也尽量恢复索引参数，避免refresh长期关闭。
                        try {
                            indexService.afterImport(context);
                        } catch (Exception restoreError) {
                            log.warn("===> ES-TPlusOne restore index settings failed. index={}, error={}",
                                    context.getConfig().getIndexName(), restoreError.getMessage());
                        }
                    }
                    // 一次导入只输出一个失败终态，任务持久化和通知由上层TPlusOneImportTask继续处理。
                    log.error("===> ES-TPlusOne failed. alias={}, index={}, table={}, date={}, "
                                    + "total={}, read={}, success={}, failed={}, lastReadId={}, "
                                    + "safeCheckpointId={}, costMs={}, error={}",
                            config.getIndexAlias(),
                            config.getIndexName(),
                            config.getTableName(),
                            config.getImportDate(),
                            statistics.getTotal().get(),
                            statistics.getRead().get(),
                            statistics.getSuccess().get(),
                            statistics.getFailed().get(),
                            statistics.getLastId(),
                            statistics.getLastSuccessId(),
                            System.currentTimeMillis() - statistics.getStartTime(),
                            e.getMessage(),
                            e);
                    throw e instanceof ServiceException serviceException
                            ? serviceException
                            : new ServiceException("ES import failed, error=" + e.getMessage(), e);
                } finally {
                    // 正常路径额度已由Bulk批次释放；异常路径在这里兜底释放队列和在途批次额度。
                    context.releaseAllMemoryReservations();
                    statistics.setEndTime(System.currentTimeMillis());
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
    private void normalizeConfig(TPlusOneImportConfig config) {
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

        if (failed > properties.getTPlusOne().getMaxFailedDocuments()
                || failureRate > properties.getTPlusOne().getMaxFailureRate()) {
            // 失败超过阈值时中断上线，避免业务查询读到明显不完整的数据。
            throw new ServiceException("ES import failed documents exceed threshold, total=" + total
                    + ", success=" + success
                    + ", failed=" + failed
                    + ", failureRate=" + String.format("%.6f", failureRate));
        }

        if (failed > 0L) {
            log.warn("===> ES-TPlusOne finished with partial failed documents. total={}, success={}, failed={}, failureRate={}",
                    total, success, failed, String.format("%.6f", failureRate));
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
