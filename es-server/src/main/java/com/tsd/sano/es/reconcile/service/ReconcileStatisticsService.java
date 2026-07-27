package com.tsd.sano.es.reconcile.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.importer.notify.ImportNotifyService;
import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import com.tsd.sano.es.reconcile.model.ReconcileResult;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立的单表单日统计对账服务。
 *
 * <p>通过独立Spring执行器异步读取MySQL和ES的总量、最小ID及最大ID并比较。
 * 该服务不依赖Polling主循环或checkpoint，不维护任务状态，也不执行重试。</p>
 */
@Service
public class ReconcileStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStatisticsService.class);

    /**
     * 每日物理索引名称中的日期格式。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbcTemplate;
    private final ElasticsearchClient client;
    private final ImportNotifyService notifyService;

    /**
     * 注入MySQL、ES和通知能力。
     */
    public ReconcileStatisticsService(
            JdbcTemplate jdbcTemplate,
            ElasticsearchClient client,
            ImportNotifyService notifyService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.client = client;
        this.notifyService = notifyService;
    }

    /**
     * 按单表配置异步执行一次统计对账，并对匹配、不匹配和执行失败统一发送通知。
     *
     * @param tableConfig   目标表配置
     * @param reconcileDate 对账业务日期
     */
    @Async("esReconcileExecutor")
    public void reconcile(EsImportProperties.TableConfig tableConfig, LocalDate reconcileDate) {
        if (!tableConfig.isReconcile()) {
            log.info("===> ES-Reconcile skipped by table config. table={}, date={}",
                    tableConfig.getTableName(), reconcileDate);
            return;
        }

        String tableName = tableConfig.getTableName();
        String indexName = tableConfig.getIndexAlias() + "_" + INDEX_DATE_FORMATTER.format(reconcileDate);
        ReconcileResult.Statistics mysqlStatistics = null;
        ReconcileResult.Statistics esStatistics = null;

        try {
            List<Object> parameters = new ArrayList<>();
            String businessCondition;
            if (StringUtils.isNotBlank(tableConfig.getWhereSql())) {
                // whereSql来自受信任部署配置，并被视为该表完整的业务过滤条件。
                businessCondition = "(" + tableConfig.getWhereSql().trim() + ")";
            } else if ("DATE".equals(tableConfig.getDtColumnType())) {
                businessCondition = tableConfig.getDtColumn() + " = ?";
                parameters.add(java.sql.Date.valueOf(reconcileDate));
            } else if ("DATETIME".equals(tableConfig.getDtColumnType())) {
                businessCondition = tableConfig.getDtColumn() + " >= ? AND " + tableConfig.getDtColumn() + " < ?";
                parameters.add(Timestamp.valueOf(reconcileDate.atStartOfDay()));
                parameters.add(Timestamp.valueOf(reconcileDate.plusDays(1).atStartOfDay()));
            } else {
                throw new ServiceException("ES reconcile dtColumnType must be DATE or DATETIME, tableName="
                        + tableName + ", value=" + tableConfig.getDtColumnType());
            }

            String mysqlSql = "SELECT COUNT(1) AS total_count, MIN(" + tableConfig.getIdColumn()
                    + ") AS min_id, MAX(" + tableConfig.getIdColumn() + ") AS max_id"
                    + " FROM " + tableName + " WHERE " + businessCondition;
            long mysqlStartedAt = System.currentTimeMillis();
            mysqlStatistics = jdbcTemplate.queryForObject(
                    mysqlSql,
                    (resultSet, rowNum) -> {
                        Object minValue = resultSet.getObject("min_id");
                        Object maxValue = resultSet.getObject("max_id");
                        Long minId = minValue == null ? null : ((Number) minValue).longValue();
                        Long maxId = maxValue == null ? null : ((Number) maxValue).longValue();
                        return new ReconcileResult.Statistics( resultSet.getLong("total_count"), minId, maxId);
                    },
                    parameters.toArray()
            );
            log.info("===> ES-Reconcile mysql statistics completed. table={}, date={}, statistics={}, costMs={}",
                    tableName, reconcileDate, mysqlStatistics,
                    System.currentTimeMillis() - mysqlStartedAt);
            long esStartedAt = System.currentTimeMillis();
            SearchRequest minimumRequest = new SearchRequest.Builder()
                    .index(indexName)
                    .size(1)
                    .trackTotalHits(totalHits -> totalHits.enabled(true))
                    .source(source -> source.fetch(false))
                    .sort(sort -> sort.field(field -> field
                            .field(tableConfig.getIdColumn())
                            .order(SortOrder.Asc)))
                    .build();
            SearchResponse<Object> minimumResponse = client.search(minimumRequest, Object.class);
            long esCount = minimumResponse.hits().total() == null
                    ? minimumResponse.hits().hits().size()
                    : minimumResponse.hits().total().value();
            if (esCount == 0L) {
                esStatistics = new ReconcileResult.Statistics(0L, null, null);
            } else {
                Long minId = extractSortId(minimumResponse.hits().hits(), tableName, "minimum");
                SearchRequest maximumRequest = new SearchRequest.Builder()
                        .index(indexName)
                        .size(1)
                        .source(source -> source.fetch(false))
                        .sort(sort -> sort.field(field -> field
                                .field(tableConfig.getIdColumn())
                                .order(SortOrder.Desc)))
                        .build();
                SearchResponse<Object> maximumResponse = client.search(maximumRequest, Object.class);
                Long maxId = extractSortId(maximumResponse.hits().hits(), tableName, "maximum");
                esStatistics = new ReconcileResult.Statistics(esCount, minId, maxId);
            }
            log.info("===> ES-Reconcile elasticsearch statistics completed. index={}, date={}, "
                            + "statistics={}, costMs={}",
                    indexName, reconcileDate, esStatistics,
                    System.currentTimeMillis() - esStartedAt);

            ReconcileResult result = ReconcileResult.completed(
                    tableName, indexName, reconcileDate, mysqlStatistics, esStatistics);
            notifyService.notifyReconcileResult(result);
        } catch (RuntimeException | IOException error) {
            String message = StringUtils.defaultIfBlank(
                    error.getMessage(), error.getClass().getSimpleName());
            ReconcileResult result = ReconcileResult.failed(
                    tableName, indexName, reconcileDate, mysqlStatistics, esStatistics, message);
            log.error("===> ES-Reconcile execution failed. table={}, index={}, date={}, error={}",
                    tableName, indexName, reconcileDate, message, error);
            notifyService.notifyReconcileResult(result);
        }
    }

    /**
     * 从按ID排序的首条ES命中中读取精确long值，避免min/max聚合转double造成大ID精度损失。
     */
    private Long extractSortId(List<Hit<Object>> hits, String tableName, String statisticName) {
        if (hits.isEmpty() || hits.getFirst().sort().isEmpty()) {
            throw new ServiceException("ES reconcile " + statisticName
                    + " ID is missing, tableName=" + tableName);
        }
        FieldValue value = hits.getFirst().sort().getFirst();
        if (value.isLong()) {
            return value.longValue();
        }
        if (value.isString()) {
            try {
                return Long.parseLong(value.stringValue());
            } catch (NumberFormatException error) {
                throw new ServiceException("ES reconcile " + statisticName
                        + " ID must be numeric, tableName=" + tableName
                        + ", value=" + value.stringValue(), error);
            }
        }
        throw new ServiceException("ES reconcile " + statisticName
                + " ID must be an integer, tableName=" + tableName + ", value=" + value);
    }
}
