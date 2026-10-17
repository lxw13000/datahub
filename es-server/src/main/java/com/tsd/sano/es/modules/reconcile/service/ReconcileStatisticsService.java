package com.tsd.sano.es.modules.reconcile.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.config.SyncTableConfig;
import com.tsd.sano.es.modules.config.IndexMode;
import com.tsd.sano.es.modules.index.EsIndexManager;
import com.tsd.sano.es.modules.index.EsIndexNameResolver;
import com.tsd.sano.es.modules.notify.model.NotifyMessage;
import com.tsd.sano.es.modules.notify.service.NotifyService;
import com.tsd.sano.es.modules.reconcile.model.ReconcileResult;
import com.tsd.sano.es.modules.reconcile.model.HistoryReconcileResult;
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
 * 独立统计对账服务：日常任务按日或SINGLE全表现状对账，人工历史按整个请求日期范围对账。
 *
 * <p>通过独立Spring执行器异步对账：日常比较总量、最小ID及最大ID，历史范围只比较总量；
 * MySQL非空时先主动刷新目标物理索引，避免尚未刷新文档造成假差异。
 * 该服务不维护导入任务状态，也不执行重试。</p>
 */
@Service
public class ReconcileStatisticsService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileStatisticsService.class);

    /**
     * 对账消息标题前缀。
     */
    private static final String SUBJECT_PREFIX = "[SANO-ES]";

    /**
     * MySQL统计查询入口，连接生命周期由Spring JDBC管理。
     */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Elasticsearch统计查询客户端。
     */
    private final ElasticsearchClient client;

    /**
     * 对账前刷新已关闭日期物理索引的通用ES索引操作。
     */
    private final EsIndexManager indexManager;

    /**
     * 对账模块复用的公共通知通道服务。
     */
    private final NotifyService notifyService;

    /**
     * 注入MySQL、ES索引操作和通知能力。
     */
    public ReconcileStatisticsService(
            JdbcTemplate jdbcTemplate,
            ElasticsearchClient client,
            EsIndexManager indexManager,
            NotifyService notifyService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.client = client;
        this.indexManager = indexManager;
        this.notifyService = notifyService;
    }

    /**
     * 按单表配置异步执行一次统计对账，并对匹配、不匹配和执行失败统一发送通知。
     *
     * @param tableConfig   目标表配置
     * @param reconcileDate 日期分桶的业务日期；SINGLE忽略此值并按全表现状统计
     */
    @Async("esReconcileExecutor")
    public void reconcile(SyncTableConfig tableConfig, LocalDate reconcileDate) {
        if (!tableConfig.isReconcile()) {
            log.info("===> ES-Reconcile skipped by table config. table={}, date={}",
                    tableConfig.getTableName(), reconcileDate);
            return;
        }

        String tableName = tableConfig.getTableName();
        IndexMode mode = tableConfig.getIndexMode();
        LocalDate statisticsDate = mode == IndexMode.SINGLE ? null : reconcileDate;
        String indexName = EsIndexNameResolver.resolve(mode, tableConfig.getIndexAlias(),
                tableConfig.getFixedIndexName(), statisticsDate);
        ReconcileResult.Statistics mysqlStatistics = null;
        ReconcileResult.Statistics esStatistics = null;

        try {
            List<Object> parameters = new ArrayList<>();
            String businessCondition;
            // DAY和SINGLE统计整个物理索引；共享月、年索引的三项ES统计必须复用同一日期过滤。
            Query esCondition = Query.of(query -> query.matchAll(all -> all));
            if (mode == IndexMode.SINGLE) {
                businessCondition = "1 = 1";
            } else {
                businessCondition = switch (tableConfig.getDtColumnType()) {
                    case INTEGER_DATE -> {
                        int day = Integer.parseInt(DateTimeFormatter.BASIC_ISO_DATE.format(statisticsDate));
                        parameters.add(day);
                        if (mode == IndexMode.MONTH || mode == IndexMode.YEAR) {
                            // 共享物理索引必须按整数业务日期过滤，不能将yyyy-MM-dd字符串用于整数列。
                            esCondition = Query.of(query -> query.term(term -> term.field(tableConfig.getDtColumn())
                                    .value(day)));
                        }
                        yield tableConfig.getDtColumn() + " = ?";
                    }
                    case DATE -> {
                        parameters.add(java.sql.Date.valueOf(statisticsDate));
                        if (mode == IndexMode.MONTH || mode == IndexMode.YEAR) {
                            esCondition = Query.of(query -> query.term(term -> term.field(tableConfig.getDtColumn())
                                    .value(statisticsDate.toString())));
                        }
                        yield tableConfig.getDtColumn() + " = ?";
                    }
                    case DATETIME -> {
                        parameters.add(Timestamp.valueOf(statisticsDate.atStartOfDay()));
                        parameters.add(Timestamp.valueOf(statisticsDate.plusDays(1).atStartOfDay()));
                        if (mode == IndexMode.MONTH || mode == IndexMode.YEAR) {
                            esCondition = Query.of(query -> query.range(range -> range.date(date -> date
                                    .field(tableConfig.getDtColumn())
                                    .gte(statisticsDate.atStartOfDay().toString())
                                    .lt(statisticsDate.plusDays(1).atStartOfDay().toString())
                                    .format("strict_date_optional_time"))));
                        }
                        yield tableConfig.getDtColumn() + " >= ? AND " + tableConfig.getDtColumn() + " < ?";
                    }
                };
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
                        return new ReconcileResult.Statistics(resultSet.getLong("total_count"), minId, maxId);
                    },
                    parameters.toArray()
            );
            log.info("===> ES-Reconcile mysql statistics completed. table={}, date={}, statistics={}, costMs={}",
                    tableName, statisticsDate, mysqlStatistics,
                    System.currentTimeMillis() - mysqlStartedAt);
            if (mysqlStatistics.count() == 0L) {
                // 源端为空按既有约定通知MYSQL_EMPTY，不查询ES；这不等同于确认两端匹配。
                ReconcileResult result = ReconcileResult.mysqlEmpty(
                        tableName, indexName, statisticsDate, mysqlStatistics);
                log.info("===> ES-Reconcile elasticsearch statistics skipped because mysql is empty. "
                                + "table={}, index={}, date={}",
                        tableName, indexName, statisticsDate);
                notifyResult(result);
                return;
            }

            // 主动刷新目标索引；月年统计仍只看D日，SINGLE则看全表现状。
            indexManager.refresh(indexName);
            long esStartedAt = System.currentTimeMillis();
            SearchRequest minimumRequest = new SearchRequest.Builder()
                    .index(indexName)
                    .query(esCondition)
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
                        .query(esCondition)
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
                    indexName, statisticsDate, esStatistics,
                    System.currentTimeMillis() - esStartedAt);

            ReconcileResult result = ReconcileResult.completed(
                    tableName, indexName, statisticsDate, mysqlStatistics, esStatistics);
            notifyResult(result);
        } catch (RuntimeException | IOException error) {
            String message = StringUtils.defaultIfBlank(
                    error.getMessage(), error.getClass().getSimpleName());
            ReconcileResult result = ReconcileResult.failed(
                    tableName, indexName, statisticsDate, mysqlStatistics, esStatistics, message);
            log.error("===> ES-Reconcile execution failed. table={}, index={}, date={}, error={}",
                    tableName, indexName, statisticsDate, message, error);
            notifyResult(result);
        }
    }

    /**
     * 人工历史范围全部导入成功后，独立异步执行一次日期范围总量对账。
     *
     * <p>仅用于MONTH/YEAR；不是循环调用每日对账。MySQL和所有目标物理索引使用相同
     * 左闭右开的日期条件，防止首尾月/年中范围外数据参与统计。只发送一份历史对账通知，
     * 不依赖单列或复合文档ID，不创建持久对账任务，失败也不修改已经完成的历史导入结果。</p>
     *
     * @param tableConfig 目标表配置，reconcile=false时直接跳过
     * @param startDate 开始业务日期，包含
     * @param endDate 结束业务日期，包含
     * @param operationId 对应人工历史导入操作ID
     */
    @Async("esReconcileExecutor")
    public void reconcileHistory(SyncTableConfig tableConfig, LocalDate startDate, LocalDate endDate,
                                 String operationId) {
        if (!tableConfig.isReconcile()) {
            log.info("===> ES-Reconcile history skipped by table config. operationId={}, table={}, startDate={}, endDate={}",
                    operationId, tableConfig.getTableName(), startDate, endDate);
            return;
        }
        String tableName = tableConfig.getTableName();
        List<String> indexNames = new ArrayList<>();
        Long mysqlCount = null;
        Long esCount = null;
        ReconcileResult.Status status = ReconcileResult.Status.FAILED;
        String errorMessage = null;
        long startedAt = System.currentTimeMillis();
        try {
            IndexMode mode = tableConfig.getIndexMode();
            if (mode != IndexMode.MONTH && mode != IndexMode.YEAR) {
                throw new ServiceException("Historical reconciliation only supports MONTH/YEAR, table=" + tableName);
            }
            // 直接按物理周期列举索引，不按天提交查询，不依赖可能包含范围外索引的业务Alias。
            LocalDate period = mode == IndexMode.MONTH ? startDate.withDayOfMonth(1) : startDate.withDayOfYear(1);
            while (!period.isAfter(endDate)) {
                indexNames.add(EsIndexNameResolver.resolve(mode, tableConfig.getIndexAlias(), null, period));
                period = mode == IndexMode.MONTH ? period.plusMonths(1) : period.plusYears(1);
            }
            LocalDate endExclusive = endDate.plusDays(1);
            List<Object> parameters = new ArrayList<>();
            Query esCondition = switch (tableConfig.getDtColumnType()) {
                case INTEGER_DATE -> {
                    int firstDay = Integer.parseInt(DateTimeFormatter.BASIC_ISO_DATE.format(startDate));
                    int lastDayExclusive = Integer.parseInt(DateTimeFormatter.BASIC_ISO_DATE.format(endExclusive));
                    parameters.add(firstDay);
                    parameters.add(lastDayExclusive);
                    // yyyyMMdd整数在double中可精确表示；该条件仅用于限定统计日期范围。
                    yield Query.of(query -> query.range(range -> range.number(number -> number
                            .field(tableConfig.getDtColumn()).gte((double) firstDay).lt((double) lastDayExclusive))));
                }
                case DATE -> {
                    parameters.add(java.sql.Date.valueOf(startDate));
                    parameters.add(java.sql.Date.valueOf(endExclusive));
                    yield Query.of(query -> query.range(range -> range.date(date -> date
                            .field(tableConfig.getDtColumn()).gte(startDate.toString()).lt(endExclusive.toString())
                            .format("strict_date_optional_time"))));
                }
                case DATETIME -> {
                    parameters.add(Timestamp.valueOf(startDate.atStartOfDay()));
                    parameters.add(Timestamp.valueOf(endExclusive.atStartOfDay()));
                    yield Query.of(query -> query.range(range -> range.date(date -> date
                            .field(tableConfig.getDtColumn()).gte(startDate.atStartOfDay().toString())
                            .lt(endExclusive.atStartOfDay().toString()).format("strict_date_optional_time"))));
                }
            };
            String mysqlSql = "SELECT COUNT(1) FROM " + tableName
                    + " WHERE " + tableConfig.getDtColumn() + " >= ? AND " + tableConfig.getDtColumn() + " < ?";
            mysqlCount = jdbcTemplate.queryForObject(mysqlSql, Long.class, parameters.toArray());
            if (mysqlCount == 0L) {
                // 沿用既有空源语义：通知MySQL无数据，不查询ES，也不宣称已匹配。
                status = ReconcileResult.Status.MYSQL_EMPTY;
                return;
            }

            // 空数据月份可能从未创建索引，允许缺失；其源端若确有数据，最终总数会反映差异。
            // 一次刷新全部范围索引；刷新分片失败不能继续宣称统计已经可见。
            var refresh = client.indices().refresh(request -> request.index(indexNames)
                    .ignoreUnavailable(true).allowNoIndices(true));
            if (refresh.shards() != null && refresh.shards().failed().longValue() > 0L) {
                throw new ServiceException("ES history reconcile refresh has failed shards, table=" + tableName);
            }
            // 历史只核对总数，无需按ID排序或读取文档，兼容复合文档ID及FULL_DAY读取表。
            CountRequest countRequest = new CountRequest.Builder().index(indexNames)
                    .ignoreUnavailable(true).allowNoIndices(true).query(esCondition)
                    .build();
            var countResponse = client.count(countRequest);
            // Count可能返回部分分片的计数；分片失败时不能将不完整总数视为对账结果。
            if (countResponse.shards().failed().longValue() > 0L) {
                throw new ServiceException("ES history reconcile count has failed shards, table=" + tableName);
            }
            esCount = countResponse.count();
            status = mysqlCount.equals(esCount)
                    ? ReconcileResult.Status.MATCHED : ReconcileResult.Status.MIS_MATCHED;
        } catch (RuntimeException | IOException error) {
            errorMessage = StringUtils.defaultIfBlank(error.getMessage(), error.getClass().getSimpleName());
            log.error("===> ES-Reconcile history failed. operationId={}, table={}, startDate={}, endDate={}, error={}",
                    operationId, tableName, startDate, endDate, errorMessage, error);
        } finally {
            log.info("===> ES-Reconcile history completed. operationId={}, table={}, startDate={}, endDate={}, "
                            + "result={}, mysqlCount={}, elasticsearchCount={}, costMs={}",
                    operationId, tableName, startDate, endDate, status, mysqlCount, esCount,
                    System.currentTimeMillis() - startedAt);
            notifyHistoryResult(new HistoryReconcileResult(operationId, tableName, startDate, endDate,
                    indexNames, status, mysqlCount, esCount, errorMessage));
        }
    }

    /**
     * 历史范围使用独立中文通知及事件标识，不与单日或SINGLE对账通知混用。
     */
    private void notifyHistoryResult(HistoryReconcileResult result) {
        try {
            String statusText = switch (result.status()) {
                case MATCHED -> "匹配成功";
                case MIS_MATCHED -> "数据不一致";
                case MYSQL_EMPTY -> "MySQL无数据，未对账";
                case FAILED -> "对账执行失败";
            };
            String title = SUBJECT_PREFIX + " ES历史数据对账 " + statusText + " " + result.tableName();
            StringBuilder content = new StringBuilder(640).append(title).append('\n')
                    .append("操作ID：").append(result.operationId()).append('\n')
                    .append("表名：").append(result.tableName()).append('\n')
                    .append("统计范围：").append(result.startDate()).append(" ~ ").append(result.endDate())
                    .append("（含起止日期）").append('\n')
                    .append("范围索引：").append(String.join(", ", result.indexNames())).append('\n')
                    .append("结果：").append(statusText).append('\n')
                    .append("MySQL总数：").append(result.mysqlCount() == null ? "未获取" : result.mysqlCount()).append('\n')
                    .append("Elasticsearch总数：").append(result.elasticsearchCount() == null
                            ? "未获取" : result.elasticsearchCount());
            if (StringUtils.isNotBlank(result.error())) {
                content.append('\n').append("错误：").append(StringUtils.left(result.error(), 800));
            }
            notifyService.send(new NotifyMessage("HISTORY_RECONCILE_" + result.status().name(),
                    result.operationId(), title, content.toString()));
        } catch (Exception error) {
            log.error("===> ES-Reconcile history notify failed. operationId={}, error={}",
                    result.operationId(), error.getMessage(), error);
        }
    }

    /**
     * 组织并发送当前模块的统计对账结果。
     *
     * <p>每次结果只发送一份中文通知；内部状态及事件标识不变，通知异常不改变对账或同步状态。</p>
     */
    private void notifyResult(ReconcileResult result) {
        try {
            // 中文仅用于消息展示，不修改枚举值或下游识别使用的RECONCILE_*事件标识。
            String statusText = switch (result.status()) {
                case MATCHED -> "匹配成功";
                case MIS_MATCHED -> "数据不一致";
                case MYSQL_EMPTY -> "MySQL无数据，未对账";
                case FAILED -> "对账执行失败";
            };
            String title = SUBJECT_PREFIX + " ES数据对账 " + statusText + " " + result.indexName();
            StringBuilder content = new StringBuilder(480)
                    .append(title).append('\n')
                    .append("表名：").append(result.tableName()).append('\n')
                    .append("索引：").append(result.indexName()).append('\n')
                    .append("统计范围：").append(result.reconcileDate() == null
                            ? "全表现状（SINGLE）" : result.reconcileDate()).append('\n')
                    .append("结果：").append(statusText).append('\n')
                    .append("MySQL：").append(formatStatistics(result.mysql())).append('\n')
                    .append("Elasticsearch：").append(formatStatistics(result.elasticsearch()));
            if (StringUtils.isNotBlank(result.error())) {
                content.append('\n').append("错误：").append(StringUtils.left(result.error(), 800));
            }
            notifyService.send(new NotifyMessage(
                    "RECONCILE_" + result.status().name(),
                    result.indexName(),
                    title,
                    content.toString()
            ));
        } catch (Exception notifyError) {
            log.error("===> ES-Reconcile notify failed before send. table={}, date={}, error={}",
                    result == null ? null : result.tableName(),
                    result == null ? null : result.reconcileDate(),
                    notifyError.getMessage(),
                    notifyError);
        }
    }

    /**
     * 中文展示单侧统计；统计未取得显示“未获取”，空数据的ID显示“无”。
     */
    private String formatStatistics(ReconcileResult.Statistics statistics) {
        if (statistics == null) {
            return "未获取";
        }
        return "计数=" + statistics.count()
                + "，最小ID=" + (statistics.minId() == null ? "无" : statistics.minId())
                + "，最大ID=" + (statistics.maxId() == null ? "无" : statistics.maxId());
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
