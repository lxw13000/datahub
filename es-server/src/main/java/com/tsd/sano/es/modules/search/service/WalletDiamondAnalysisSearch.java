package com.tsd.sano.es.modules.search.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.FieldDateMath;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.util.NamedValue;
import com.tsd.sano.es.controller.analysis.dto.RoomWalletAnalysisDTO;
import com.tsd.sano.es.controller.analysis.dto.UserDiamondAnalysisDTO;
import com.tsd.sano.es.controller.analysis.vo.DiamondIncomePropTopVO;
import com.tsd.sano.es.controller.analysis.vo.DiamondIncomePropVO;
import com.tsd.sano.es.controller.analysis.vo.DiamondIncomeSourceTopVO;
import com.tsd.sano.es.controller.analysis.vo.DiamondIncomeSourceVO;
import com.tsd.sano.es.controller.analysis.vo.RoomDiamondDailyIncomeStatVO;
import com.tsd.sano.es.controller.analysis.vo.UserDiamondDailyStatVO;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.modules.search.EBusinessType;
import com.tsd.sano.es.modules.search.constant.EsIndexAlias;
import com.tsd.sano.es.modules.search.util.EsSearchUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 用户钻石收入分析查询服务。
 *
 * <p>所有统计均查询钻石流水索引，tokens只做原始值汇总，不在本服务中进行单位换算。</p>
 *
 * @author lxw
 */
@Service
public class WalletDiamondAnalysisSearch {

    private static final Logger log = LoggerFactory.getLogger(WalletDiamondAnalysisSearch.class);

    /**
     * 接口业务日期格式。
     */
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    /**
     * Top排行榜返回数量。
     */
    private static final int TOP_SIZE = 10;

    /**
     * 增大各分片候选桶数量，降低跨多个按天索引统计Top10时遗漏全局候选项的概率。
     */
    private static final int TOP_SHARD_SIZE = 200;

    /**
     * Elasticsearch Java客户端。
     */
    private final ElasticsearchClient client;

    public WalletDiamondAnalysisSearch(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 按天统计指定用户的普通礼物、幸运礼物和游戏钻石收入。
     *
     * <p>日期范围内没有流水的日期由ES日期直方图返回空桶，所有收入值为0。</p>
     *
     * @param dto 用户及业务日期范围
     * @return 按业务日期升序排列的每日收入统计
     */
    public List<UserDiamondDailyStatVO> dailyStat(UserDiamondAnalysisDTO dto) {
        validateDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        // 只保留钻石收入对应的业务类型，不额外使用status重复限定流水方向。
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.RECEIVE_GIFT.getCode(),
                EBusinessType.RECEIVE_LUCKY_GIFT.getCode(),
                EBusinessType.ANCHOR_GAME_INCOME.getCode()
        )));

        SearchRequest request = SearchRequest.of(search -> search
                .index(indices)
                .ignoreUnavailable(true)
                .size(0)
                .query(boolBuilder.build()._toQuery())
                .aggregations("daily", daily -> daily
                        .dateHistogram(histogram -> histogram
                                .field("dt")
                                .calendarInterval(CalendarInterval.Day)
                                .format("yyyy-MM-dd")
                                // 返回空日期桶，并使用接口日期范围补齐首尾没有收入的日期。
                                .minDocCount(0)
                                .extendedBounds(bounds -> bounds
                                        .min(FieldDateMath.of(value -> value.expr(dto.getStartDate())))
                                        .max(FieldDateMath.of(value -> value.expr(dto.getEndDate())))))
                        .aggregations("normal_gift_income", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.RECEIVE_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("lucky_gift_income", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.RECEIVE_LUCKY_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("game_income", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.ANCHOR_GAME_INCOME.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search user diamond daily income DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("用户钻石每日收入统计查询超时，未返回完整结果");
            }

            List<DateHistogramBucket> buckets = response.aggregations()
                    .get("daily").dateHistogram().buckets().array();
            List<UserDiamondDailyStatVO> result = new ArrayList<>(buckets.size());
            for (DateHistogramBucket bucket : buckets) {
                UserDiamondDailyStatVO stat = new UserDiamondDailyStatVO();
                stat.setDt(bucket.keyAsString());
                Map<String, Aggregate> aggregations = bucket.aggregations();
                stat.setNormalGiftIncomeTokens(filteredTokens(aggregations, "normal_gift_income"));
                stat.setLuckyGiftIncomeTokens(filteredTokens(aggregations, "lucky_gift_income"));
                stat.setGameIncomeTokens(filteredTokens(aggregations, "game_income"));
                result.add(stat);
            }

            log.info("===> ES-Search user diamond daily income. indices={}, userId={}, startDate={}, endDate={}, "
                            + "dayCount={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(), result.size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES user diamond daily income failed, indices={}, userId={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询用户钻石每日收入统计失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 按天统计指定房间集合的普通礼物、幸运礼物和游戏钻石收入。
     *
     * <p>多个房间的数据合并统计；日期范围内没有流水的日期由ES日期直方图返回空桶，
     * 所有收入值为0。</p>
     *
     * @param dto 房间集合及业务日期范围
     * @return 按业务日期升序排列的每日钻石收入统计
     */
    public List<RoomDiamondDailyIncomeStatVO> roomDailyIncomeStat(RoomWalletAnalysisDTO dto) {
        validateRoomDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTermsOr("room_id", dto.getRoomIds()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        // 只保留三个钻石收入业务类型，不额外使用status重复限定流水方向。
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.RECEIVE_GIFT.getCode(),
                EBusinessType.RECEIVE_LUCKY_GIFT.getCode(),
                EBusinessType.ANCHOR_GAME_INCOME.getCode()
        )));

        SearchRequest request = SearchRequest.of(search -> search
                .index(indices)
                .ignoreUnavailable(true)
                .size(0)
                .query(boolBuilder.build()._toQuery())
                .aggregations("daily", daily -> daily
                        .dateHistogram(histogram -> histogram
                                .field("dt")
                                .calendarInterval(CalendarInterval.Day)
                                .format("yyyy-MM-dd")
                                // 返回空日期桶，并使用接口日期范围补齐首尾没有收入的日期。
                                .minDocCount(0)
                                .extendedBounds(bounds -> bounds
                                        .min(FieldDateMath.of(value -> value.expr(dto.getStartDate())))
                                        .max(FieldDateMath.of(value -> value.expr(dto.getEndDate())))))
                        .aggregations("normal_gift_income", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.RECEIVE_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("lucky_gift_income", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.RECEIVE_LUCKY_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("game_income", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.ANCHOR_GAME_INCOME.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search room diamond daily income DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("房间钻石每日收入统计查询超时，未返回完整结果");
            }

            List<DateHistogramBucket> buckets = response.aggregations()
                    .get("daily").dateHistogram().buckets().array();
            List<RoomDiamondDailyIncomeStatVO> result = new ArrayList<>(buckets.size());
            for (DateHistogramBucket bucket : buckets) {
                RoomDiamondDailyIncomeStatVO stat = new RoomDiamondDailyIncomeStatVO();
                stat.setDt(bucket.keyAsString());
                Map<String, Aggregate> aggregations = bucket.aggregations();
                stat.setNormalGiftIncomeTokens(filteredTokens(aggregations, "normal_gift_income"));
                stat.setLuckyGiftIncomeTokens(filteredTokens(aggregations, "lucky_gift_income"));
                stat.setGameIncomeTokens(filteredTokens(aggregations, "game_income"));
                result.add(stat);
            }

            log.info("===> ES-Search room diamond daily income. indices={}, roomIds={}, startDate={}, endDate={}, "
                            + "dayCount={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getRoomIds(), dto.getStartDate(), dto.getEndDate(), result.size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES room diamond daily income failed, indices={}, roomIds={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getRoomIds(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询房间钻石每日收入统计失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 按累计收入tokens统计指定用户的普通礼物、幸运礼物和游戏收入来源用户Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类钻石收入对应的赠送者Top10
     */
    public DiamondIncomeSourceTopVO incomeSourceTop10(UserDiamondAnalysisDTO dto) {
        validateDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.RECEIVE_GIFT.getCode(),
                EBusinessType.RECEIVE_LUCKY_GIFT.getCode(),
                EBusinessType.ANCHOR_GAME_INCOME.getCode()
        )));

        SearchRequest request = SearchRequest.of(search -> search
                .index(indices)
                .ignoreUnavailable(true)
                .size(0)
                .query(boolBuilder.build()._toQuery())
                .aggregations("normal_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm(
                                "business_type", EBusinessType.RECEIVE_GIFT.getCode()))
                        .aggregations("sources", sources -> sources
                                .terms(terms -> terms.field("ta_user_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("lucky_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm(
                                "business_type", EBusinessType.RECEIVE_LUCKY_GIFT.getCode()))
                        .aggregations("sources", sources -> sources
                                .terms(terms -> terms.field("ta_user_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("game", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm(
                                "business_type", EBusinessType.ANCHOR_GAME_INCOME.getCode()))
                        .aggregations("sources", sources -> sources
                                .terms(terms -> terms.field("ta_user_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search user diamond income source Top10 DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("用户钻石收入来源用户Top10查询超时，未返回完整结果");
            }

            DiamondIncomeSourceTopVO result = new DiamondIncomeSourceTopVO();
            result.setNormalGift(sourceBuckets(response, "normal_gift"));
            result.setLuckyGift(sourceBuckets(response, "lucky_gift"));
            result.setGame(sourceBuckets(response, "game"));
            log.info("===> ES-Search user diamond income source Top10. indices={}, userId={}, startDate={}, "
                            + "endDate={}, normalGiftSize={}, luckyGiftSize={}, gameSize={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    result.getNormalGift().size(), result.getLuckyGift().size(), result.getGame().size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES user diamond income source Top10 failed, indices={}, userId={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询用户钻石收入来源用户Top10失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 按累计收入tokens统计指定用户的普通礼物、幸运礼物和游戏具体收入类型Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类钻石收入对应的prop_id Top10
     */
    public DiamondIncomePropTopVO incomePropTop10(UserDiamondAnalysisDTO dto) {
        validateDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.RECEIVE_GIFT.getCode(),
                EBusinessType.RECEIVE_LUCKY_GIFT.getCode(),
                EBusinessType.ANCHOR_GAME_INCOME.getCode()
        )));

        SearchRequest request = SearchRequest.of(search -> search
                .index(indices)
                .ignoreUnavailable(true)
                .size(0)
                .query(boolBuilder.build()._toQuery())
                .aggregations("normal_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm(
                                "business_type", EBusinessType.RECEIVE_GIFT.getCode()))
                        .aggregations("props", props -> props
                                .terms(terms -> terms.field("prop_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("lucky_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm(
                                "business_type", EBusinessType.RECEIVE_LUCKY_GIFT.getCode()))
                        .aggregations("props", props -> props
                                .terms(terms -> terms.field("prop_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("game", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm(
                                "business_type", EBusinessType.ANCHOR_GAME_INCOME.getCode()))
                        .aggregations("props", props -> props
                                .terms(terms -> terms.field("prop_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search user diamond income prop Top10 DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("用户钻石具体收入Top10查询超时，未返回完整结果");
            }

            DiamondIncomePropTopVO result = new DiamondIncomePropTopVO();
            result.setNormalGift(propBuckets(response, "normal_gift"));
            result.setLuckyGift(propBuckets(response, "lucky_gift"));
            result.setGame(propBuckets(response, "game"));
            log.info("===> ES-Search user diamond income prop Top10. indices={}, userId={}, startDate={}, "
                            + "endDate={}, normalGiftSize={}, luckyGiftSize={}, gameSize={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    result.getNormalGift().size(), result.getLuckyGift().size(), result.getGame().size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES user diamond income prop Top10 failed, indices={}, userId={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询用户钻石具体收入Top10失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 校验必填参数及业务日期范围。
     *
     * @param dto 查询参数
     */
    private static void validateDateRange(UserDiamondAnalysisDTO dto) {
        if (dto == null || dto.getUserId() == null) {
            throw new ServiceException("用户ID不能为空");
        }
        if (StringUtils.isBlank(dto.getStartDate()) || StringUtils.isBlank(dto.getEndDate())) {
            throw new ServiceException("统计开始日期和结束日期不能为空");
        }

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(dto.getStartDate(), DATE_FORMATTER);
            endDate = LocalDate.parse(dto.getEndDate(), DATE_FORMATTER);
        } catch (DateTimeParseException error) {
            throw new ServiceException("统计日期格式错误，请使用yyyy-MM-dd，例如：2026-08-23", error);
        }
        if (endDate.isBefore(startDate)) {
            throw new ServiceException("统计结束日期不能早于开始日期");
        }
    }

    /**
     * 校验房间集合及业务日期范围。
     *
     * @param dto 查询参数
     */
    private static void validateRoomDateRange(RoomWalletAnalysisDTO dto) {
        if (dto == null || dto.getRoomIds() == null || dto.getRoomIds().isEmpty()) {
            throw new ServiceException("房间ID集合不能为空");
        }
        if (dto.getRoomIds().stream().anyMatch(roomId -> roomId == null)) {
            throw new ServiceException("房间ID集合不能包含空值");
        }
        if (StringUtils.isBlank(dto.getStartDate()) || StringUtils.isBlank(dto.getEndDate())) {
            throw new ServiceException("统计开始日期和结束日期不能为空");
        }

        LocalDate startDate;
        LocalDate endDate;
        try {
            startDate = LocalDate.parse(dto.getStartDate(), DATE_FORMATTER);
            endDate = LocalDate.parse(dto.getEndDate(), DATE_FORMATTER);
        } catch (DateTimeParseException error) {
            throw new ServiceException("统计日期格式错误，请使用yyyy-MM-dd，例如：2026-08-23", error);
        }
        if (endDate.isBefore(startDate)) {
            throw new ServiceException("统计结束日期不能早于开始日期");
        }
    }

    /**
     * 根据业务日期生成需要查询的钻石按天物理索引。
     */
    private static List<String> getIndices(UserDiamondAnalysisDTO dto) {
        return EsSearchUtil.getIndices(
                EsIndexAlias.SANO_WALLET_DIAMOND_RECORD,
                dto.getStartDate() + " 00:00:00",
                dto.getEndDate() + " 23:59:59"
        );
    }

    /**
     * 根据业务日期生成需要查询的钻石按天物理索引。
     */
    private static List<String> getIndices(RoomWalletAnalysisDTO dto) {
        return EsSearchUtil.getIndices(
                EsIndexAlias.SANO_WALLET_DIAMOND_RECORD,
                dto.getStartDate() + " 00:00:00",
                dto.getEndDate() + " 23:59:59"
        );
    }

    /**
     * 读取过滤聚合下的tokens汇总值。
     */
    private static long filteredTokens(Map<String, Aggregate> aggregations, String aggregationName) {
        return Math.round(aggregations.get(aggregationName)
                .filter().aggregations().get("tokens").sum().value());
    }

    /**
     * 将指定业务类型下的ta_user_id聚合桶转换为钻石收入来源排行。
     */
    private static List<DiamondIncomeSourceVO> sourceBuckets(
            SearchResponse<Void> response, String aggregationName) {
        List<LongTermsBucket> buckets = response.aggregations().get(aggregationName)
                .filter().aggregations().get("sources").lterms().buckets().array();
        List<DiamondIncomeSourceVO> result = new ArrayList<>(buckets.size());
        for (LongTermsBucket bucket : buckets) {
            DiamondIncomeSourceVO item = new DiamondIncomeSourceVO();
            item.setSourceUserId(Math.toIntExact(bucket.key()));
            item.setTokens(Math.round(bucket.aggregations().get("tokens").sum().value()));
            result.add(item);
        }
        return result;
    }

    /**
     * 将指定业务类型下的prop_id聚合桶转换为具体钻石收入排行。
     */
    private static List<DiamondIncomePropVO> propBuckets(SearchResponse<Void> response, String aggregationName) {
        List<LongTermsBucket> buckets = response.aggregations().get(aggregationName)
                .filter().aggregations().get("props").lterms().buckets().array();
        List<DiamondIncomePropVO> result = new ArrayList<>(buckets.size());
        for (LongTermsBucket bucket : buckets) {
            DiamondIncomePropVO item = new DiamondIncomePropVO();
            item.setPropId(Math.toIntExact(bucket.key()));
            item.setTokens(Math.round(bucket.aggregations().get("tokens").sum().value()));
            result.add(item);
        }
        return result;
    }
}
