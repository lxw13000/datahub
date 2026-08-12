package com.tsd.sano.es.modules.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.util.NamedValue;
import com.tsd.sano.es.controller.coin.dto.SearchCoinRecordDTO;
import com.tsd.sano.es.controller.coin.vo.*;
import com.tsd.sano.es.core.util.TimeUtils;
import com.tsd.sano.es.modules.search.constant.EsIndexAlias;
import com.tsd.sano.es.modules.search.util.EsSearchUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WalletCoinRecordSearch
 *
 * @author lxw
 * @version V1.0
 * @date 2026/7/2 21:14
 */
@Service
public class WalletCoinRecordSearch {

    /**
     * 单位换算：一美元对应的金币数。
     */
    private static final BigDecimal TOKENS_PER_DOLLAR = BigDecimal.valueOf(10_000L);

    /**
     * 主播分层比例。
     */
    private static final BigDecimal HOST_RATIO = BigDecimal.valueOf(0.02D);

    /**
     * 用户去重聚合精度阈值，超过阈值后 ES 仍可能存在小幅近似误差。
     */
    private static final int USER_COUNT_PRECISION_THRESHOLD = 40_000;

    private static final Logger log = LoggerFactory.getLogger(WalletCoinRecordSearch.class);

    private final ElasticsearchClient client;

    public WalletCoinRecordSearch(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 统计指定房间在一段时间内的金币消费情况。
     *
     * @param roomIds   房间ID集合
     * @param startTime 开始时间，格式yyyy-MM-dd HH:mm:ss
     * @param endTime   结束时间，格式yyyy-MM-dd HH:mm:ss
     * @return 周期消费统计
     */
    public WeekStatVO staWeek(List<Integer> roomIds, String startTime, String endTime) {

        // 根据查询日期只选择对应的按天索引，避免无关历史索引参与聚合。
        List<String> indices = EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_COIN_RECORD, startTime, endTime);
        long searchStartMillis = System.currentTimeMillis();
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(indices);
        // 某天无数据时可能没有物理索引，忽略不存在索引可以避免整个查询失败。
        searchBuilder.ignoreUnavailable(true);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // 房间集合
        boolBuilder.must(EsSearchUtil.getTermsOr("room_id", roomIds));
        // 时间段
        EsSearchUtil.setDateEQ(boolBuilder, "create_time", TimeUtils.BASIC, startTime, endTime);
        // 消费
        boolBuilder.must(EsSearchUtil.getTerm("status", -1));

        searchBuilder.query(boolBuilder.build()._toQuery());
        // 聚合查询不需要返回数据，设置size=0。
        EsSearchUtil.setPage(searchBuilder, 0, 0);

        searchBuilder
                // 直播间消费人数：user_id去重。
                .aggregations("consume_user_count", a -> a.cardinality(c -> c.field("user_id")))
                // 直播间消费总数：tokens累计流水。
                .aggregations("consume_tokens", a -> a.sum(s -> s.field("tokens")))
                // 直播间幸运礼物消费数：business_type=11时tokens累计流水。
                .aggregations("normal_gift", a -> a
                        .filter(EsSearchUtil.getTerm("business_type", 1))
                        .aggregations("tokens", sub -> sub.sum(s -> s.field("tokens"))))
                // 直播间幸运礼物消费数：business_type=11时tokens累计流水。
                .aggregations("lucky_gift", a -> a
                        .filter(EsSearchUtil.getTerm("business_type", 11))
                        .aggregations("tokens", sub -> sub.sum(s -> s.field("tokens"))))
                // 直播间游戏消费数：business_type=21时tokens累计流水。
                .aggregations("game", a -> a
                        .filter(EsSearchUtil.getTerm("business_type", 21))
                        .aggregations("tokens", sub -> sub.sum(s -> s.field("tokens"))));

        try {
            SearchResponse<Void> response = client.search(searchBuilder.build(), Void.class);
            Map<String, Aggregate> aggregations = response.aggregations();

            WeekStatVO stat = new WeekStatVO();
            stat.setConsumeUserCount(Math.round(aggregations.get("consume_user_count").cardinality().value()));
            stat.setConsumeTokens(Math.round(aggregations.get("consume_tokens").sum().value()));
            stat.setNormalGiftTokens(Math.round(aggregations.get("normal_gift").filter().aggregations().get("tokens").sum().value()));
            stat.setLuckyGiftTokens(Math.round(aggregations.get("lucky_gift").filter().aggregations().get("tokens").sum().value()));
            stat.setGameTokens(Math.round(aggregations.get("game").filter().aggregations().get("tokens").sum().value()));
            log.info("===> ES-Search wallet coin week stat. indices={}, roomCount={}, startTime={}, endTime={}, esTookMs={}, timedOut={}, searchCostMs={}, stat={}",
                    indices, roomIds.size(), startTime, endTime, response.took(), response.timedOut(),
                    System.currentTimeMillis() - searchStartMillis, stat);
            return stat;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search wallet coin week stat failed, indices={}, roomCount={}, startTime={}, endTime={}, searchCostMs={}, error={}",
                    indices, roomIds.size(), startTime, endTime, System.currentTimeMillis() - searchStartMillis, e.getMessage(), e);
            return new WeekStatVO();
        }
    }

    /**
     * 按天统计礼物相关金币流水。
     *
     * <p>幸运礼物消费为 business_type=11，中奖返奖为 business_type=16，Jackpot 中奖为 business_type=17。</p>
     *
     * @param startDate 开始业务日期，格式 yyyy-MM-dd
     * @param endDate   结束业务日期，格式 yyyy-MM-dd
     * @return 每日统计结果
     */
    public List<CoinGiftDailyStatVO> coinGiftDailyStat(String startDate, String endDate) {
        String startTime = startDate + " 00:00:00";
        String endTime = endDate + " 23:59:59";
        List<String> indices = EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_COIN_RECORD, startTime, endTime);
        long searchStartMillis = System.currentTimeMillis();
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(indices).ignoreUnavailable(true).size(0);

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        // 使用 dt 字段过滤完整业务日期，避免 create_time 的时分秒影响日统计边界。
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", startDate, endDate);
        // 仅保留幸运礼物消费、中奖和 Jackpot 中奖三类记录，减少无关流水参与聚合。
        boolBuilder.must(EsSearchUtil.getTermsOr("business_type", List.of(11, 16, 17)));
        searchBuilder.query(boolBuilder.build()._toQuery());

        // 每个业务日期独立聚合消费、返奖和消费金额 Top3 的礼物。
        searchBuilder.aggregations("daily", a -> a
                .dateHistogram(d -> d.field("dt").calendarInterval(CalendarInterval.Day).format("yyyy-MM-dd"))
                .aggregations("consume", sub -> sub
                        // 业务类型已定义消费含义，只统计幸运礼物消费。
                        .filter(EsSearchUtil.getTerm("business_type", 11))
                        .aggregations("user_count", metric -> metric.cardinality(c -> c
                                .field("user_id").precisionThreshold(USER_COUNT_PRECISION_THRESHOLD)))
                        .aggregations("tokens", metric -> metric.sum(s -> s.field("tokens")))
                        .aggregations("amount", metric -> metric.sum(s -> s.field("amount")))
                        .aggregations("top_props", metric -> metric.terms(t -> t
                                        .field("prop_id")
                                        .size(3)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", value -> value.sum(s -> s.field("tokens")))
                                .aggregations("amount", value -> value.sum(s -> s.field("amount")))))
                .aggregations("reward", sub -> sub
                        // 返奖只统计幸运礼物中奖，不包含 Jackpot 中奖。
                        .filter(EsSearchUtil.getTerm("business_type", 16))
                        .aggregations("tokens", metric -> metric.sum(s -> s.field("tokens"))))
                .aggregations("jackpot", sub -> sub
                        // Jackpot 金额使用实际中奖流水，而非按消费金额比例估算。
                        .filter(EsSearchUtil.getTerm("business_type", 17))
                        .aggregations("tokens", metric -> metric.sum(s -> s.field("tokens")))));

        try {
            SearchResponse<Void> response = client.search(searchBuilder.build(), Void.class);
            List<CoinGiftDailyStatVO> stats = new ArrayList<>();
            List<DateHistogramBucket> buckets = response.aggregations().get("daily").dateHistogram().buckets().array();
            for (DateHistogramBucket bucket : buckets) {
                Map<String, Aggregate> aggregations = bucket.aggregations();
                Map<String, Aggregate> consume = aggregations.get("consume").filter().aggregations();
                Map<String, Aggregate> reward = aggregations.get("reward").filter().aggregations();
                Map<String, Aggregate> jackpot = aggregations.get("jackpot").filter().aggregations();
                long userCount = Math.round(consume.get("user_count").cardinality().value());
                long consumeTokens = Math.round(consume.get("tokens").sum().value());
                long consumeAmount = Math.round(consume.get("amount").sum().value());
                long rewardTokens = Math.round(reward.get("tokens").sum().value());
                long jackpotTokens = Math.round(jackpot.get("tokens").sum().value());
                // 全部金额先按原始 tokens 计算再保留小数，和 MySQL ROUND 口径保持一致。
                BigDecimal consumeTokensValue = BigDecimal.valueOf(consumeTokens);
                BigDecimal rewardTokensValue = BigDecimal.valueOf(rewardTokens);
                BigDecimal jackpotTokensValue = BigDecimal.valueOf(jackpotTokens);
                BigDecimal consumeDollar = consumeTokensValue.divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP);
                BigDecimal rewardDollar = rewardTokensValue.divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP);
                BigDecimal hostDollar = consumeTokensValue.multiply(HOST_RATIO)
                        .divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP);
                BigDecimal jackpotDollar = jackpotTokensValue.divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP);

                CoinGiftDailyStatVO stat = new CoinGiftDailyStatVO();
                stat.setDt(bucket.keyAsString());
                stat.setUserCount(userCount);
                stat.setConsumeAmount(consumeAmount);
                stat.setConsumeDollar(consumeDollar);
                stat.setRewardDollar(rewardDollar);
                stat.setHostDollar(hostDollar);
                stat.setJackpotDollar(jackpotDollar);
                stat.setNetDollar(consumeTokensValue.subtract(rewardTokensValue)
                        .subtract(consumeTokensValue.multiply(HOST_RATIO))
                        .subtract(jackpotTokensValue)
                        .divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP));
                stat.setAvgAmount(userCount == 0L ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
                        : BigDecimal.valueOf(consumeAmount).divide(BigDecimal.valueOf(userCount), 3, RoundingMode.HALF_UP));
                stat.setAvgDollar(userCount == 0L ? BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP)
                        : consumeTokensValue.divide(BigDecimal.valueOf(userCount).multiply(TOKENS_PER_DOLLAR), 3, RoundingMode.HALF_UP));

                long top3Tokens = 0L;
                long top3Amount = 0L;
                List<LongTermsBucket> topProps = consume.get("top_props").lterms().buckets().array();
                for (int i = 0; i < topProps.size(); i++) {
                    LongTermsBucket prop = topProps.get(i);
                    long tokens = Math.round(prop.aggregations().get("tokens").sum().value());
                    long amount = Math.round(prop.aggregations().get("amount").sum().value());
                    top3Tokens += tokens;
                    top3Amount += amount;
                    if (i == 0) {
                        stat.setTop1Id(prop.key());
                        stat.setTop1Dollar(BigDecimal.valueOf(tokens).divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP));
                        stat.setTop1Amount(amount);
                    } else if (i == 1) {
                        stat.setTop2Id(prop.key());
                        stat.setTop2Dollar(BigDecimal.valueOf(tokens).divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP));
                        stat.setTop2Amount(amount);
                    } else {
                        stat.setTop3Id(prop.key());
                        stat.setTop3Dollar(BigDecimal.valueOf(tokens).divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP));
                        stat.setTop3Amount(amount);
                    }
                }
                stat.setTop3TotalDollar(BigDecimal.valueOf(top3Tokens).divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP));
                stat.setTop3TotalAmount(top3Amount);
                stat.setTop3Ratio(consumeTokens == 0L ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.valueOf(top3Tokens).multiply(BigDecimal.valueOf(100L))
                        .divide(BigDecimal.valueOf(consumeTokens), 2, RoundingMode.HALF_UP));
                stats.add(stat);
            }
            log.info("===> ES-Search coin gift daily stat. indices={}, startDate={}, endDate={}, size={}, esTookMs={}, timedOut={}, searchCostMs={}",
                    indices, startDate, endDate, stats.size(), response.took(), response.timedOut(),
                    System.currentTimeMillis() - searchStartMillis);
            return stats;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search coin gift daily stat failed, indices={}, startDate={}, endDate={}, searchCostMs={}, error={}",
                    indices, startDate, endDate, System.currentTimeMillis() - searchStartMillis, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 按天统计每个幸运礼物的消费金额。
     *
     * @param startDate 开始业务日期，格式 yyyy-MM-dd
     * @param endDate   结束业务日期，格式 yyyy-MM-dd
     * @return 每天消费金额最高的 20 个幸运礼物
     */
    public List<CoinGiftDailyPropStatVO> coinGiftDailyPropStat(String startDate, String endDate) {
        String startTime = startDate + " 00:00:00";
        String endTime = endDate + " 23:59:59";
        List<String> indices = EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_COIN_RECORD, startTime, endTime);
        long searchStartMillis = System.currentTimeMillis();
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(indices).ignoreUnavailable(true).size(0);

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        // dt 用于按业务日期过滤，business_type=11 表示幸运礼物消费。
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", startDate, endDate);
        boolBuilder.must(EsSearchUtil.getTerm("business_type", 11));
        searchBuilder.query(boolBuilder.build()._toQuery());
        // 每个日期桶内按 tokens 降序取前 20 个礼物，避免高基数礼物聚合返回过大。
        searchBuilder.aggregations("daily", aggregation -> aggregation
                .dateHistogram(histogram -> histogram.field("dt").calendarInterval(CalendarInterval.Day).format("yyyy-MM-dd"))
                .aggregations("props", sub -> sub.terms(terms -> terms
                                .field("prop_id")
                                .size(20)
                                .order(NamedValue.of("tokens", SortOrder.Desc)))
                        .aggregations("tokens", value -> value.sum(sum -> sum.field("tokens")))));

        try {
            SearchResponse<Void> response = client.search(searchBuilder.build(), Void.class);
            List<CoinGiftDailyPropStatVO> stats = new ArrayList<>();
            List<DateHistogramBucket> buckets = response.aggregations().get("daily").dateHistogram().buckets().array();
            for (DateHistogramBucket bucket : buckets) {
                CoinGiftDailyPropStatVO stat = new CoinGiftDailyPropStatVO();
                stat.setDt(bucket.keyAsString());
                List<CoinGiftPropConsumeVO> props = new ArrayList<>();
                List<LongTermsBucket> propBuckets = bucket.aggregations().get("props").lterms().buckets().array();
                for (LongTermsBucket propBucket : propBuckets) {
                    CoinGiftPropConsumeVO prop = new CoinGiftPropConsumeVO();
                    prop.setPropId(propBucket.key());
                    long tokens = Math.round(propBucket.aggregations().get("tokens").sum().value());
                    prop.setConsumeDollar(BigDecimal.valueOf(tokens).divide(TOKENS_PER_DOLLAR, 3, RoundingMode.HALF_UP));
                    props.add(prop);
                }
                stat.setProps(props);
                stats.add(stat);
            }
            log.info("===> ES-Search coin gift daily prop stat. indices={}, startDate={}, endDate={}, daySize={}, esTookMs={}, timedOut={}, searchCostMs={}",
                    indices, startDate, endDate, stats.size(), response.took(), response.timedOut(),
                    System.currentTimeMillis() - searchStartMillis);
            return stats;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search coin gift daily prop stat failed, indices={}, startDate={}, endDate={}, searchCostMs={}, error={}",
                    indices, startDate, endDate, System.currentTimeMillis() - searchStartMillis, e.getMessage(), e);
            return new ArrayList<>();
        }
    }


    /**
     * 统计指定用户在指定时间段内的金币流水记录总数。
     *
     * @param dto 查询参数
     * @return 总数
     */
    public long count(SearchCoinRecordDTO dto) {

        List<String> indices = EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_COIN_RECORD, dto.getStartTime(), dto.getEndTime());
        long countStartMillis = System.currentTimeMillis();
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        // 设置查询条件
        setBoolQuery(boolBuilder, dto);
        try {
            CountRequest countRequest = CountRequest.of(c -> c
                    .index(indices)
                    .query(boolBuilder.build()._toQuery())
                    // 某天无数据时可能没有物理索引，忽略不存在索引可以避免整个查询失败。
                    .ignoreUnavailable(true)
            );
            CountResponse count = client.count(countRequest);
            log.info("===> ES-Search count coin records. indices={}, userId={}, businessType={}, startTime={}, endTime={}, count={}, countCostMs={}",
                    indices, dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(),
                    count.count(), System.currentTimeMillis() - countStartMillis);
            return count.count();
        } catch (IOException | ElasticsearchException e) {
            log.error("ES count coin records failed, indices={}, userId={}, businessType={}, startTime={}, endTime={}, countCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(),
                    System.currentTimeMillis() - countStartMillis, e.getMessage(), e);
            return 0L;
        }
    }


    /**
     * 执行ES查询并解析结果。
     *
     * @param dto 查询请求
     * @return 金币流水列表
     */
    public List<CoinRecordVO> list(SearchCoinRecordDTO dto) {
        long searchStartMillis = System.currentTimeMillis();
        try {
            SearchRequest build = buildSearchRequest(dto).build();
            // DSL 仅在调试级别输出，避免生产环境的高频查询产生大量日志。
            log.debug("===> ES-Search coin records DSL. request={}", build);
            SearchResponse<CoinRecordVO> response = client.search(build, CoinRecordVO.class);

            List<CoinRecordVO> records = new ArrayList<>();
            for (Hit<CoinRecordVO> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    records.add(hit.source());
                }
            }
            long searchCostMs = System.currentTimeMillis() - searchStartMillis;
            log.info("===> ES-Search coin records. indices={}, userId={}, businessType={}, startTime={}, endTime={}, pageSize={}, lastCreateTime={}, lastId={}, size={}, esTookMs={}, timedOut={}, searchCostMs={}",
                    build.index(),
                    dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(), dto.getPageSize(),
                    dto.getLastCreateTime(), dto.getLastId(), records.size(), response.took(), response.timedOut(), searchCostMs);
            return records;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search coin records failed, userId={}, businessType={}, startTime={}, endTime={}, pageSize={}, lastCreateTime={}, lastId={}, searchCostMs={}, error={}",
                    dto.getUserId(), dto.getBusinessType(), dto.getStartTime(), dto.getEndTime(), dto.getPageSize(),
                    dto.getLastCreateTime(), dto.getLastId(), System.currentTimeMillis() - searchStartMillis, e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 构建ES查询请求。
     *
     * @param dto 查询参数
     * @return 构建好的SearchRequest
     */
    private SearchRequest.Builder buildSearchRequest(SearchCoinRecordDTO dto) {
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(EsSearchUtil.getIndices(EsIndexAlias.SANO_WALLET_COIN_RECORD, dto.getStartTime(), dto.getEndTime()));
        // 某天无数据时可能没有物理索引，忽略不存在索引可以避免整个查询失败。
        searchBuilder.ignoreUnavailable(true);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        setBoolQuery(boolBuilder, dto);
        searchBuilder.query(boolBuilder.build()._toQuery());
        // 深度分页和普通分页均以创建时间、ID 倒序，保证翻页顺序稳定
        EsSearchUtil.setOrder(searchBuilder, "create_time", SortOrder.Desc);
        EsSearchUtil.setOrder(searchBuilder, "id", SortOrder.Desc);
        // 搜索类型，0:深度分页，1:普通分页（普通分页最多查询10000条数据，超过10000条数据请使用深度分页
        if (dto.getSearchType() == 0) {
            searchBuilder.size(dto.getPageSize());
            // search_after深分页必须和排序字段一一对应，顺序也必须保持一致。
            if (StringUtils.isNotBlank(dto.getLastCreateTime()) && dto.getLastId() != null) {
                searchBuilder.searchAfter(List.of(
                        FieldValue.of(dto.getLastCreateTime()),
                        FieldValue.of(dto.getLastId())
                ));
            }
        } else {
            EsSearchUtil.setPage(searchBuilder, dto.getPageIndex(), dto.getPageSize());
        }
        return searchBuilder;
    }

    /**
     * 设置BoolQuery查询条件。
     *
     * @param boolBuilder BoolQuery构建器
     * @param dto         查询参数
     */
    private static void setBoolQuery(BoolQuery.Builder boolBuilder, SearchCoinRecordDTO dto) {
        // 固定查询用户ID，ES字段使用下划线命名。
        if (dto.getUserId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        }
        // 可选查询对方用户ID，ES字段使用下划线命名。
        if (dto.getTaUserId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("ta_user_id", dto.getTaUserId()));
        }
        // 可选查询礼物ID，ES字段使用下划线命名。
        if (dto.getPropId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("prop_id", dto.getPropId()));
        }
        // 可选查询房间ID，ES字段使用下划线命名。
        if (dto.getRoomId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("room_id", dto.getRoomId()));
        }
        // 可选查询业务类型，ES字段使用下划线命名。
        if (dto.getBusinessType() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("business_type", dto.getBusinessType()));
        }
        // 时间段
        EsSearchUtil.setDateEQ(boolBuilder, "create_time", TimeUtils.BASIC, dto.getStartTime(), dto.getEndTime());

    }

}
