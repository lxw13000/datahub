package com.tsd.sano.es.modules.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeAggregationSource;
import co.elastic.clients.elasticsearch._types.aggregations.CompositeBucket;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tsd.sano.es.controller.diamond.dto.DiamondIncomeDistributionDTO;
import com.tsd.sano.es.controller.diamond.dto.SatDiamond7DayDTO;
import com.tsd.sano.es.controller.diamond.dto.SearchDiamondRecordDTO;
import com.tsd.sano.es.controller.diamond.dto.WithdrawDiamondAnalysisDTO;
import com.tsd.sano.es.controller.diamond.vo.DiamondIncomeRangeVO;
import com.tsd.sano.es.controller.diamond.vo.DiamondRecordVO;
import com.tsd.sano.es.controller.diamond.vo.WithdrawDiamondAnalysisVO;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.core.util.TimeUtils;
import com.tsd.sano.es.modules.search.constant.EsIndexAlias;
import com.tsd.sano.es.modules.search.util.EsSearchUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 钻石记录检索
 *
 * @author lxw
 * @version V1.0
 * @date 2026/7/2 21:14
 */
@Service
public class WalletDiamondRecordSearch {

    private static final Logger log = LoggerFactory.getLogger(WalletDiamondRecordSearch.class);

    /**
     * 注册时间接口使用的时间格式。
     */
    private static final DateTimeFormatter QUERY_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 单次ES查询包含的最大用户数，限制聚合桶和请求体大小。
     */
    private static final int USER_BATCH_SIZE = 1_000;

    /**
     * Composite聚合每页最多返回的用户、状态和业务类型组合数。
     */
    private static final int COMPOSITE_PAGE_SIZE = 1_000;

    /**
     * 钻石加余额记录状态。
     */
    private static final int INCOME_STATUS = 1;

    /**
     * 钻石减余额记录状态。
     */
    private static final int EXPENSE_STATUS = -1;

    /**
     * 不纳入收入统计的直播时长奖励业务类型。
     */
    private static final int LIVE_DURATION_REWARD_BUSINESS_TYPE = 74;

    /**
     * 注册用户查询SQL。
     *
     * <p>查询必须只返回一列并命名为user_id；后续可在这里补充用户状态、渠道等业务条件。</p>
     */
    private static final String REGISTERED_USER_SQL = """
            SELECT id AS user_id
            FROM sano_user
            WHERE create_time >= ?
              AND create_time < ?
            """;

    /**
     * 指定时间范围内发生过提现的用户查询SQL。
     */
    private static final String WITHDRAW_USER_SQL = """
            SELECT DISTINCT user_id
            FROM sano_wallet_withdraw
            WHERE create_time >= ?
              AND create_time <= ?
            ORDER BY user_id
            """;

    /**
     * Elasticsearch Java 客户端
     */
    private final ElasticsearchClient client;

    /**
     * 查询注册用户集合使用的MySQL访问组件。
     */
    private final JdbcTemplate jdbcTemplate;

    public WalletDiamondRecordSearch(ElasticsearchClient client, JdbcTemplate jdbcTemplate) {
        this.client = client;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询指定条件下的钻石流水总数
     *
     * @param dto 查询参数
     * @return 符合条件的流水总数
     */
    public long count(SearchDiamondRecordDTO dto) {
        List<String> indices = EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_DIAMOND_RECORD, dto.getStartTime(), dto.getEndTime());
        long countStartMillis = System.currentTimeMillis();
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        setBoolQuery(boolBuilder, dto);
        try {
            CountResponse count = client.count(CountRequest.of(request -> request
                    .index(indices)
                    .query(boolBuilder.build()._toQuery())
                    // 某天无数据时可能没有物理索引，忽略不存在索引可以避免整个查询失败
                    .ignoreUnavailable(true)));
            log.info("===> ES-Search count diamond records. indices={}, userId={}, businessType={}, startTime={}, endTime={}, count={}, countCostMs={}",
                    indices, dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(),
                    count.count(), System.currentTimeMillis() - countStartMillis);
            return count.count();
        } catch (IOException | ElasticsearchException e) {
            log.error("ES count diamond records failed, indices={}, userId={}, businessType={}, startTime={}, endTime={}, countCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(),
                    System.currentTimeMillis() - countStartMillis, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 查询钻石流水列表
     *
     * @param dto 查询参数
     * @return 钻石流水记录列表
     */
    public List<DiamondRecordVO> list(SearchDiamondRecordDTO dto) {
        long searchStartMillis = System.currentTimeMillis();
        try {
            SearchRequest request = buildSearchRequest(dto).build();
            // DSL 仅在调试级别输出，避免生产环境的高频查询产生大量日志
            log.debug("===> ES-Search diamond records DSL. request={}", request);
            SearchResponse<DiamondRecordVO> response = client.search(request, DiamondRecordVO.class);

            List<DiamondRecordVO> records = new ArrayList<>();
            for (Hit<DiamondRecordVO> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    records.add(hit.source());
                }
            }
            log.info("===> ES-Search diamond records. indices={}, userId={}, businessType={}, startTime={}, endTime={}, pageSize={}, lastCreateTime={}, lastId={}, size={}, esTookMs={}, timedOut={}, searchCostMs={}",
                    request.index(), dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(), dto.getPageSize(),
                    dto.getLastCreateTime(), dto.getLastId(), records.size(), response.took(), response.timedOut(),
                    System.currentTimeMillis() - searchStartMillis);
            return records;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search diamond records failed, userId={}, businessType={}, startTime={}, endTime={}, pageSize={}, lastCreateTime={}, lastId={}, searchCostMs={}, error={}",
                    dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(), dto.getPageSize(),
                    dto.getLastCreateTime(), dto.getLastId(), System.currentTimeMillis() - searchStartMillis, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 指定用户近七天主播直播收益
     *
     * @param dto 参数
     * @return java.lang.Long 收益数值
     * @author lxw
     * @date 2026/7/14 20:12
     **/
    public Long sevenDaysLiveIncome(SatDiamond7DayDTO dto) {

        List<String> indices = EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_DIAMOND_RECORD, dto.getStartTime(), dto.getEndTime());
        long searchStartMillis = System.currentTimeMillis();
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        // 用户ID
        boolBuilder.must(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        // 时间字段使用 create_time，查询工具会按传入格式生成 ES date range
        EsSearchUtil.setDateEQ(boolBuilder, "create_time", TimeUtils.BASIC, dto.getStartTime(), dto.getEndTime());
        List<Integer> bus;
        if (dto.isSta74()) {
            bus = List.of(2, 12, 23, LIVE_DURATION_REWARD_BUSINESS_TYPE);
        } else {
            // 直播收入业务类型：2，收到豪华礼物，12.收到幸运礼物，23.房主游戏分账
            bus = List.of(2, 12, 23);
        }
        boolBuilder.must(EsSearchUtil.getTermsOr("business_type", bus));
        try {
            SearchRequest request = SearchRequest.of(search -> search
                    .index(indices)
                    .query(boolBuilder.build()._toQuery())
                    .size(0)
                    // 某天无数据时可能没有物理索引，忽略不存在索引可以避免整个查询失败
                    .ignoreUnavailable(true)
                    .aggregations("live_income", aggregation -> aggregation.sum(sum -> sum.field("tokens"))));
            SearchResponse<Void> response = client.search(request, Void.class);
            long liveIncome = Math.round(response.aggregations().get("live_income").sum().value());
            log.info("===> ES-Search sevenDaysLiveIncome. indices={}, userId={}, startTime={}, endTime={}, liveIncome={}, esTookMs={}, timedOut={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartTime(), dto.getEndTime(),
                    liveIncome, response.took(), response.timedOut(), System.currentTimeMillis() - searchStartMillis);
            return liveIncome;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES sevenDaysLiveIncome failed, indices={}, userId={}, startTime={}, endTime={}, searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartTime(), dto.getEndTime(),
                    System.currentTimeMillis() - searchStartMillis, e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 查询注册时间范围内用户的钻石收入，并按收入总额统计人数。
     *
     * <p>MySQL负责筛选用户集合；ES按用户汇总收入状态的钻石记录，并排除直播时长奖励。
     * ES没有收入记录的用户按0处理，与MySQL LEFT JOIN后的IFNULL语义一致。</p>
     *
     * @param dto 用户注册时间范围
     * @return 固定顺序的钻石收入区间人数
     */
    public List<DiamondIncomeRangeVO> incomeDistribution(DiamondIncomeDistributionDTO dto) {
        if (dto == null || StringUtils.isBlank(dto.getStartTime())
                || StringUtils.isBlank(dto.getEndTime())) {
            throw new ServiceException("用户注册开始时间和结束时间不能为空");
        }

        LocalDateTime startTime;
        LocalDateTime endTime;
        try {
            startTime = LocalDateTime.parse(dto.getStartTime(), QUERY_TIME_FORMATTER);
            endTime = LocalDateTime.parse(dto.getEndTime(), QUERY_TIME_FORMATTER);
        } catch (DateTimeParseException error) {
            throw new ServiceException("用户注册时间格式错误，请使用yyyy-MM-dd HH:mm:ss", error);
        }
        if (!endTime.isAfter(startTime)) {
            throw new ServiceException("用户注册结束时间必须大于开始时间");
        }

        long startedAt = System.currentTimeMillis();
        List<Long> queriedUserIds;
        try {
            queriedUserIds = jdbcTemplate.query(
                    REGISTERED_USER_SQL,
                    (resultSet, rowNum) -> resultSet.getLong("user_id"),
                    startTime,
                    endTime
            );
        } catch (DataAccessException error) {
            throw new ServiceException("查询注册用户失败，error=" + error.getMessage(), error);
        }

        // 用户SQL后续可能增加关联查询；这里去重并过滤无效ID，避免重复统计人数。

        List<Long> userIds = new ArrayList<>(queriedUserIds);
        Map<Long, Long> incomeByUser = new LinkedHashMap<>(Math.max(16, userIds.size()));
        for (Long userId : userIds) {
            // 预先补0，确保没有任何收入记录的注册用户也计入0-100区间。
            incomeByUser.put(userId, 0L);
        }

        long esStartedAt = System.currentTimeMillis();
        try {
            for (int fromIndex = 0; fromIndex < userIds.size(); fromIndex += USER_BATCH_SIZE) {
                List<Long> batchUserIds = userIds.subList(
                        fromIndex,
                        Math.min(fromIndex + USER_BATCH_SIZE, userIds.size())
                );
                List<Integer> bus;
                if (dto.isSta74()) {
                    bus = List.of(2, 12, 23, LIVE_DURATION_REWARD_BUSINESS_TYPE);
                } else {
                    bus = List.of(2, 12, 23);
                }
                BoolQuery query = new BoolQuery.Builder()
                        .filter(EsSearchUtil.getTermsOr("user_id", batchUserIds))
                        .filter(EsSearchUtil.getTermsOr("business_type", bus))
                        .build();

                SearchResponse<Void> response = client.search(search -> search
                                .index(EsIndexAlias.SANO_WALLET_DIAMOND_RECORD)
                                .ignoreUnavailable(true)
                                .size(0)
                                .query(query._toQuery())
                                .aggregations("users", users -> users
                                        .terms(terms -> terms
                                                .field("user_id")
                                                .size(batchUserIds.size()))
                                        .aggregations("income_tokens", income -> income
                                                .sum(sum -> sum.field("tokens")))),
                        Void.class
                );

                List<LongTermsBucket> buckets =
                        response.aggregations().get("users").lterms().buckets().array();
                for (LongTermsBucket bucket : buckets) {
                    long income = Math.round(
                            bucket.aggregations().get("income_tokens").sum().value()
                    );
                    incomeByUser.put(bucket.key(), income);
                }
            }
        } catch (IOException | ElasticsearchException error) {
            log.error("ES diamond income distribution failed, userCount={}, startTime={}, endTime={}, "
                            + "esCostMs={}, error={}",
                    incomeByUser.size(), dto.getStartTime(), dto.getEndTime(),
                    System.currentTimeMillis() - esStartedAt, error.getMessage(), error);
            throw new ServiceException("查询钻石收入分布失败，error=" + error.getMessage(), error);
        }

        long[] rangeCounts = new long[8];
        for (long income : incomeByUser.values()) {
            if (income <= 100_000L) {
                rangeCounts[0]++;
            } else if (income <= 200_000L) {
                rangeCounts[1]++;
            } else if (income <= 500_000L) {
                rangeCounts[2]++;
            } else if (income <= 1_000_000L) {
                rangeCounts[3]++;
            } else if (income <= 2_000_000L) {
                rangeCounts[4]++;
            } else if (income <= 5_000_000L) {
                rangeCounts[5]++;
            } else if (income <= 10_000_000L) {
                rangeCounts[6]++;
            } else {
                rangeCounts[7]++;
            }
        }

        List<DiamondIncomeRangeVO> result = List.of(
                new DiamondIncomeRangeVO("0-100", rangeCounts[0]),
                new DiamondIncomeRangeVO("101-200", rangeCounts[1]),
                new DiamondIncomeRangeVO("201-500", rangeCounts[2]),
                new DiamondIncomeRangeVO("501-1000", rangeCounts[3]),
                new DiamondIncomeRangeVO("1001-2000", rangeCounts[4]),
                new DiamondIncomeRangeVO("2001-5000", rangeCounts[5]),
                new DiamondIncomeRangeVO("5001-10000", rangeCounts[6]),
                new DiamondIncomeRangeVO("10000+", rangeCounts[7])
        );
        long completedAt = System.currentTimeMillis();
        log.info("===> ES-Search diamond income distribution. startTime={}, endTime={}, userCount={}, "
                        + "batchCount={}, mysqlCostMs={}, esCostMs={}, totalCostMs={}, ranges={}",
                dto.getStartTime(), dto.getEndTime(), incomeByUser.size(),
                (incomeByUser.size() + USER_BATCH_SIZE - 1) / USER_BATCH_SIZE,
                esStartedAt - startedAt, completedAt - esStartedAt,
                completedAt - startedAt, result);
        return result;
    }

    /**
     * 查询指定时间内的提现用户，并统计这些用户在独立时间范围内的钻石来源和用途。
     *
     * <p>提现用户由MySQL筛选；钻石流水按用户分批查询ES，并使用Composite聚合完整遍历
     * user_id、status和business_type组合，避免普通Terms聚合超过桶限制后截断结果。</p>
     *
     * @param dto 提现用户筛选时间和钻石统计时间
     * @return 用户、余额变化方向和业务类型组成的扁平汇总明细
     */
    public List<WithdrawDiamondAnalysisVO> withdrawAnalysis(WithdrawDiamondAnalysisDTO dto) {
        if (dto == null
                || StringUtils.isBlank(dto.getWithdrawStartTime())
                || StringUtils.isBlank(dto.getWithdrawEndTime())
                || StringUtils.isBlank(dto.getStatisticsStartTime())
                || StringUtils.isBlank(dto.getStatisticsEndTime())) {
            throw new ServiceException("提现开始时间、提现结束时间、统计开始时间和统计结束时间不能为空");
        }

        LocalDateTime withdrawStartTime;
        LocalDateTime withdrawEndTime;
        LocalDateTime statisticsStartTime;
        LocalDateTime statisticsEndTime;
        try {
            withdrawStartTime = LocalDateTime.parse(dto.getWithdrawStartTime(), QUERY_TIME_FORMATTER);
            withdrawEndTime = LocalDateTime.parse(dto.getWithdrawEndTime(), QUERY_TIME_FORMATTER);
            statisticsStartTime = LocalDateTime.parse(dto.getStatisticsStartTime(), QUERY_TIME_FORMATTER);
            statisticsEndTime = LocalDateTime.parse(dto.getStatisticsEndTime(), QUERY_TIME_FORMATTER);
        } catch (DateTimeParseException error) {
            throw new ServiceException("提现时间和统计时间格式错误，请使用yyyy-MM-dd HH:mm:ss", error);
        }
        if (withdrawEndTime.isBefore(withdrawStartTime)) {
            throw new ServiceException("提现结束时间不能早于提现开始时间");
        }
        if (statisticsEndTime.isBefore(statisticsStartTime)) {
            throw new ServiceException("统计结束时间不能早于统计开始时间");
        }

        long startedAt = System.currentTimeMillis();
        List<Long> userIds;
        try {
            userIds = jdbcTemplate.query(
                    WITHDRAW_USER_SQL,
                    (resultSet, rowNum) -> resultSet.getLong("user_id"),
                    withdrawStartTime,
                    withdrawEndTime
            );
        } catch (DataAccessException error) {
            throw new ServiceException("查询提现用户失败，error=" + error.getMessage(), error);
        }
        long esStartedAt = System.currentTimeMillis();
        if (userIds.isEmpty()) {
            log.info("===> ES-Search withdraw diamond analysis. withdrawStartTime={}, withdrawEndTime={}, "
                            + "statisticsStartTime={}, statisticsEndTime={}, excludeBusinessTypes={}, "
                            + "userCount=0, userBatchCount=0, aggregationBucketCount=0, mysqlCostMs={}, "
                            + "esCostMs=0, totalCostMs={}",
                    dto.getWithdrawStartTime(), dto.getWithdrawEndTime(),
                    dto.getStatisticsStartTime(), dto.getStatisticsEndTime(),
                    dto.getExcludeBusinessTypes(),
                    esStartedAt - startedAt, esStartedAt - startedAt);
            return List.of();
        }

        List<String> indices = EsSearchUtil.getIndices(
                EsIndexAlias.SANO_WALLET_DIAMOND_RECORD,
                dto.getStatisticsStartTime(),
                dto.getStatisticsEndTime()
        );
        List<Map<String, CompositeAggregationSource>> compositeSources = List.of(
                Map.of("userId", CompositeAggregationSource.of(
                        source -> source.terms(terms -> terms.field("user_id")))),
                Map.of("status", CompositeAggregationSource.of(
                        source -> source.terms(terms -> terms.field("status")))),
                Map.of("businessType", CompositeAggregationSource.of(
                        source -> source.terms(terms -> terms.field("business_type"))))
        );
        List<WithdrawDiamondAnalysisVO> result = new ArrayList<>();

        try {
            for (int fromIndex = 0; fromIndex < userIds.size(); fromIndex += USER_BATCH_SIZE) {
                List<Long> batchUserIds = userIds.subList(
                        fromIndex,
                        Math.min(fromIndex + USER_BATCH_SIZE, userIds.size())
                );
                Map<String, FieldValue> afterKey = null;
                do {
                    Map<String, FieldValue> requestAfterKey = afterKey;
                    BoolQuery.Builder queryBuilder = new BoolQuery.Builder()
                            .filter(EsSearchUtil.getTermsOr("user_id", batchUserIds))
                            .filter(EsSearchUtil.getTermsOr(
                                    "status",
                                    List.of(INCOME_STATUS, EXPENSE_STATUS)
                            ));
                    // 排除指定的业务类型，避免统计钻石流水时包含不需要的记录。
                    if (dto.getExcludeBusinessTypes() != null && !dto.getExcludeBusinessTypes().isEmpty()) {
                        queryBuilder.mustNot(EsSearchUtil.getTermsOr(
                                "business_type",
                                dto.getExcludeBusinessTypes()
                        ));
                    }
                    EsSearchUtil.setDateEQ(
                            queryBuilder,
                            "create_time",
                            TimeUtils.BASIC,
                            dto.getStatisticsStartTime(),
                            dto.getStatisticsEndTime()
                    );

                    SearchResponse<Void> response = client.search(search -> search
                                    .index(indices)
                                    .ignoreUnavailable(true)
                                    .size(0)
                                    .query(queryBuilder.build()._toQuery())
                                    .aggregations("user_status_business", aggregation -> aggregation
                                            .composite(composite -> {
                                                composite
                                                        .size(COMPOSITE_PAGE_SIZE)
                                                        .sources(compositeSources);
                                                if (requestAfterKey != null) {
                                                    composite.after(requestAfterKey);
                                                }
                                                return composite;
                                            })
                                            .aggregations("total_tokens", total -> total
                                                    .sum(sum -> sum.field("tokens")))),
                            Void.class
                    );
                    if (response.timedOut() || response.shards().failed().intValue() > 0) {
                        throw new ServiceException("ES提现用户钻石统计未完整执行，timedOut="
                                + response.timedOut() + ", failedShards=" + response.shards().failed());
                    }

                    var composite = response.aggregations()
                            .get("user_status_business")
                            .composite();
                    List<CompositeBucket> buckets = composite.buckets().array();
                    for (CompositeBucket bucket : buckets) {
                        long userId = bucket.key().get("userId").longValue();
                        int status = (int) bucket.key().get("status").longValue();
                        int businessType = (int) bucket.key().get("businessType").longValue();
                        long tokens = Math.round(
                                bucket.aggregations().get("total_tokens").sum().value()
                        );
                        result.add(new WithdrawDiamondAnalysisVO(
                                userId,
                                status,
                                businessType,
                                tokens
                        ));
                    }
                    afterKey = composite.afterKey();
                } while (afterKey != null && !afterKey.isEmpty());
            }
        } catch (IOException | ElasticsearchException error) {
            log.error("ES withdraw diamond analysis failed, indices={}, withdrawStartTime={}, "
                            + "withdrawEndTime={}, statisticsStartTime={}, statisticsEndTime={}, "
                            + "excludeBusinessTypes={}, userCount={}, completedBucketCount={}, "
                            + "esCostMs={}, error={}",
                    indices, dto.getWithdrawStartTime(), dto.getWithdrawEndTime(),
                    dto.getStatisticsStartTime(), dto.getStatisticsEndTime(),
                    dto.getExcludeBusinessTypes(), userIds.size(),
                    result.size(), System.currentTimeMillis() - esStartedAt,
                    error.getMessage(), error);
            throw new ServiceException("查询提现用户钻石来源和用途失败，error=" + error.getMessage(), error);
        }

        // 输出顺序固定为用户ID、加余额优先、业务类型，便于直接导出表格和人工核对。
        result.sort((left, right) -> {
            int userOrder = Long.compare(left.userId(), right.userId());
            if (userOrder != 0) {
                return userOrder;
            }
            int statusOrder = Integer.compare(right.status(), left.status());
            return statusOrder != 0
                    ? statusOrder
                    : Integer.compare(left.businessType(), right.businessType());
        });

        long completedAt = System.currentTimeMillis();
        log.info("===> ES-Search withdraw diamond analysis. withdrawStartTime={}, withdrawEndTime={}, "
                        + "statisticsStartTime={}, statisticsEndTime={}, excludeBusinessTypes={}, "
                        + "userCount={}, userBatchCount={}, aggregationBucketCount={}, mysqlCostMs={}, "
                        + "esCostMs={}, totalCostMs={}",
                dto.getWithdrawStartTime(), dto.getWithdrawEndTime(),
                dto.getStatisticsStartTime(), dto.getStatisticsEndTime(),
                dto.getExcludeBusinessTypes(), userIds.size(),
                (userIds.size() + USER_BATCH_SIZE - 1) / USER_BATCH_SIZE,
                result.size(), esStartedAt - startedAt, completedAt - esStartedAt,
                completedAt - startedAt);
        return result;
    }

    /**
     * 构建钻石流水 ES 查询请求
     *
     * @param dto 查询参数
     * @return 构建中的 ES 查询请求
     */
    private SearchRequest.Builder buildSearchRequest(SearchDiamondRecordDTO dto) {
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_DIAMOND_RECORD, dto.getStartTime(), dto.getEndTime()));
        // 某天无数据时可能没有物理索引，忽略不存在索引可以避免整个查询失败
        searchBuilder.ignoreUnavailable(true);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        setBoolQuery(boolBuilder, dto);
        searchBuilder.query(boolBuilder.build()._toQuery());

        // 深度分页和普通分页均以创建时间、ID 倒序，保证翻页顺序稳定
        EsSearchUtil.setOrder(searchBuilder, "create_time", SortOrder.Desc);
        EsSearchUtil.setOrder(searchBuilder, "id", SortOrder.Desc);
        if (dto.getSearchType() == 0) {
            searchBuilder.size(dto.getPageSize());
            if (StringUtils.isNotBlank(dto.getLastCreateTime()) && dto.getLastId() != null) {
                searchBuilder.searchAfter(List.of(
                        FieldValue.of(dto.getLastCreateTime()),
                        FieldValue.of(dto.getLastId()))
                );
            }
        } else {
            EsSearchUtil.setPage(searchBuilder, dto.getPageIndex(), dto.getPageSize());
        }
        return searchBuilder;
    }

    /**
     * 根据钻石流水查询参数设置 ES 过滤条件
     *
     * @param boolBuilder Bool 查询构建器
     * @param dto         查询参数
     */
    private static void setBoolQuery(BoolQuery.Builder boolBuilder, SearchDiamondRecordDTO dto) {
        if (dto.getUserId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        }
        if (dto.getTaUserId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("ta_user_id", dto.getTaUserId()));
        }
        if (dto.getPropId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("prop_id", dto.getPropId()));
        }
        if (dto.getRoomId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("room_id", dto.getRoomId()));
        }
        if (dto.getBusinessType() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("business_type", dto.getBusinessType()));
        }
        // 时间字段使用 create_time，查询工具会按传入格式生成 ES date range
        EsSearchUtil.setDateEQ(boolBuilder, "create_time", TimeUtils.BASIC, dto.getStartTime(), dto.getEndTime());
    }

}
