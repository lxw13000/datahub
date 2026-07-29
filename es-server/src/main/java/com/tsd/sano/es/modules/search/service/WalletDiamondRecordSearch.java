package com.tsd.sano.es.modules.search.service;

import ch.qos.logback.core.model.INamedModel;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
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
import com.tsd.sano.es.controller.diamond.vo.DiamondIncomeRangeVO;
import com.tsd.sano.es.controller.diamond.vo.DiamondRecordVO;
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
     * 钻石收入记录状态。
     */
    private static final int INCOME_STATUS = 1;

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
        // 直播收入业务类型：2，收到豪华礼物，12.收到幸运礼物，23.房主游戏分账
        boolBuilder.must(EsSearchUtil.getTermsOr("business_type", List.of(2, 12, 23)));
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
                List<Integer> bus = new ArrayList<>();
                if(dto.isSta74()){
                    bus= List.of(2, 12, 23, LIVE_DURATION_REWARD_BUSINESS_TYPE);
                }else {
                    bus= List.of(2, 12, 23);
                }
                BoolQuery query = new BoolQuery.Builder()
                        .filter(EsSearchUtil.getTermsOr("user_id", batchUserIds))
                        .filter(EsSearchUtil.getTermsOr("business_type",bus ))
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
