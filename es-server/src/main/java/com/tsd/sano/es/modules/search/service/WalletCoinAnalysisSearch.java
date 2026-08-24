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
import com.tsd.sano.es.controller.analysis.dto.UserCoinAnalysisDTO;
import com.tsd.sano.es.controller.analysis.vo.CoinConsumePropTopVO;
import com.tsd.sano.es.controller.analysis.vo.CoinConsumePropVO;
import com.tsd.sano.es.controller.analysis.vo.CoinConsumeTargetTopVO;
import com.tsd.sano.es.controller.analysis.vo.CoinConsumeTargetVO;
import com.tsd.sano.es.controller.analysis.vo.RoomCoinDailyConsumeStatVO;
import com.tsd.sano.es.controller.analysis.vo.UserCoinDailyStatVO;
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
 * 用户金币消费和返奖分析查询服务。
 *
 * <p>所有统计均查询金币流水索引，tokens只做原始值汇总，不在本服务中进行单位换算。</p>
 *
 * @author lxw
 */
@Service
public class WalletCoinAnalysisSearch {

    private static final Logger log = LoggerFactory.getLogger(WalletCoinAnalysisSearch.class);

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

    public WalletCoinAnalysisSearch(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 按天统计指定用户的金币消费、返奖及Jackpot中奖数据。
     *
     * <p>日期范围内没有流水的日期仍会返回，所有统计值为0。</p>
     *
     * @param dto 用户及业务日期范围
     * @return 按业务日期升序排列的每日统计
     */
    public List<UserCoinDailyStatVO> dailyStat(UserCoinAnalysisDTO dto) {
        validateDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        // 先过滤本接口涉及的业务类型，避免无关金币流水进入日期聚合。
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.SEND_GIFT.getCode(),
                EBusinessType.SEND_LUCKY_GIFT.getCode(),
                EBusinessType.GAME_CONSUME.getCode(),
                EBusinessType.BUY_PROP.getCode(),
                EBusinessType.BUY_VIP.getCode(),
                EBusinessType.LUCKY_COIN.getCode(),
                EBusinessType.GAME_INCOME.getCode(),
                EBusinessType.JACKPOT_LUCKY.getCode(),
                EBusinessType.JACKPOT_GAME.getCode()
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
                                // 返回空日期桶，并使用接口日期范围补齐首尾没有流水的日期。
                                .minDocCount(0)
                                .extendedBounds(bounds -> bounds
                                        .min(FieldDateMath.of(value -> value.expr(dto.getStartDate())))
                                        .max(FieldDateMath.of(value -> value.expr(dto.getEndDate())))))
                        .aggregations("normal_gift_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.SEND_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("lucky_gift_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.SEND_LUCKY_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("game_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.GAME_CONSUME.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("mall_prop_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.BUY_PROP.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("vip_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.BUY_VIP.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("lucky_gift_reward", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.LUCKY_COIN.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("game_reward", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.GAME_INCOME.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("lucky_gift_jackpot", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.JACKPOT_LUCKY.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("game_jackpot", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm("business_type", EBusinessType.JACKPOT_GAME.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search user coin daily stat DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("用户金币每日统计查询超时，未返回完整结果");
            }

            List<DateHistogramBucket> buckets = response.aggregations()
                    .get("daily").dateHistogram().buckets().array();
            List<UserCoinDailyStatVO> result = new ArrayList<>(buckets.size());
            for (DateHistogramBucket bucket : buckets) {
                UserCoinDailyStatVO stat = new UserCoinDailyStatVO();
                stat.setDt(bucket.keyAsString());
                Map<String, Aggregate> aggregations = bucket.aggregations();
                stat.setNormalGiftConsumeTokens(filteredTokens(aggregations, "normal_gift_consume"));
                stat.setLuckyGiftConsumeTokens(filteredTokens(aggregations, "lucky_gift_consume"));
                stat.setGameConsumeTokens(filteredTokens(aggregations, "game_consume"));
                stat.setMallPropConsumeTokens(filteredTokens(aggregations, "mall_prop_consume"));
                stat.setVipConsumeTokens(filteredTokens(aggregations, "vip_consume"));
                stat.setLuckyGiftRewardTokens(filteredTokens(aggregations, "lucky_gift_reward"));
                stat.setGameRewardTokens(filteredTokens(aggregations, "game_reward"));
                stat.setLuckyGiftJackpotTokens(filteredTokens(aggregations, "lucky_gift_jackpot"));
                stat.setGameJackpotTokens(filteredTokens(aggregations, "game_jackpot"));
                result.add(stat);
            }

            log.info("===> ES-Search user coin daily stat. indices={}, userId={}, startDate={}, endDate={}, "
                            + "dayCount={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(), result.size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES user coin daily stat failed, indices={}, userId={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询用户金币每日统计失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 按天统计指定房间集合的普通礼物、幸运礼物和游戏金币消费。
     *
     * <p>多个房间的数据合并统计；日期范围内没有流水的日期由ES日期直方图返回空桶，
     * 所有消费值为0。</p>
     *
     * @param dto 房间集合及业务日期范围
     * @return 按业务日期升序排列的每日金币消费统计
     */
    public List<RoomCoinDailyConsumeStatVO> roomDailyConsumeStat(RoomWalletAnalysisDTO dto) {
        validateRoomDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTermsOr("room_id", dto.getRoomIds()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        // 只保留三个金币消费业务类型，不额外使用status重复限定流水方向。
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.SEND_GIFT.getCode(),
                EBusinessType.SEND_LUCKY_GIFT.getCode(),
                EBusinessType.GAME_CONSUME.getCode()
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
                                // 返回空日期桶，并使用接口日期范围补齐首尾没有消费的日期。
                                .minDocCount(0)
                                .extendedBounds(bounds -> bounds
                                        .min(FieldDateMath.of(value -> value.expr(dto.getStartDate())))
                                        .max(FieldDateMath.of(value -> value.expr(dto.getEndDate())))))
                        .aggregations("normal_gift_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.SEND_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("lucky_gift_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.SEND_LUCKY_GIFT.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))
                        .aggregations("game_consume", aggregation -> aggregation
                                .filter(EsSearchUtil.getTerm(
                                        "business_type", EBusinessType.GAME_CONSUME.getCode()))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search room coin daily consume DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("房间金币每日消费统计查询超时，未返回完整结果");
            }

            List<DateHistogramBucket> buckets = response.aggregations()
                    .get("daily").dateHistogram().buckets().array();
            List<RoomCoinDailyConsumeStatVO> result = new ArrayList<>(buckets.size());
            for (DateHistogramBucket bucket : buckets) {
                RoomCoinDailyConsumeStatVO stat = new RoomCoinDailyConsumeStatVO();
                stat.setDt(bucket.keyAsString());
                Map<String, Aggregate> aggregations = bucket.aggregations();
                stat.setNormalGiftConsumeTokens(filteredTokens(aggregations, "normal_gift_consume"));
                stat.setLuckyGiftConsumeTokens(filteredTokens(aggregations, "lucky_gift_consume"));
                stat.setGameConsumeTokens(filteredTokens(aggregations, "game_consume"));
                result.add(stat);
            }

            log.info("===> ES-Search room coin daily consume. indices={}, roomIds={}, startDate={}, endDate={}, "
                            + "dayCount={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getRoomIds(), dto.getStartDate(), dto.getEndDate(), result.size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES room coin daily consume failed, indices={}, roomIds={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getRoomIds(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询房间金币每日消费统计失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 按累计消费tokens统计指定用户的普通礼物、幸运礼物和游戏消费去处Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类消费对应的接收者Top10
     */
    public CoinConsumeTargetTopVO consumeTargetTop10(UserCoinAnalysisDTO dto) {
        validateDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.SEND_GIFT.getCode(),
                EBusinessType.SEND_LUCKY_GIFT.getCode(),
                EBusinessType.GAME_CONSUME.getCode()
        )));

        SearchRequest request = SearchRequest.of(search -> search
                .index(indices)
                .ignoreUnavailable(true)
                .size(0)
                .query(boolBuilder.build()._toQuery())
                .aggregations("normal_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm("business_type", EBusinessType.SEND_GIFT.getCode()))
                        .aggregations("targets", targets -> targets
                                .terms(terms -> terms.field("ta_user_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("lucky_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm("business_type", EBusinessType.SEND_LUCKY_GIFT.getCode()))
                        .aggregations("targets", targets -> targets
                                .terms(terms -> terms.field("ta_user_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("game", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm("business_type", EBusinessType.GAME_CONSUME.getCode()))
                        .aggregations("targets", targets -> targets
                                .terms(terms -> terms.field("ta_user_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search user coin consume target Top10 DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("用户金币消费去处Top10查询超时，未返回完整结果");
            }

            CoinConsumeTargetTopVO result = new CoinConsumeTargetTopVO();
            result.setNormalGift(targetBuckets(response, "normal_gift"));
            result.setLuckyGift(targetBuckets(response, "lucky_gift"));
            result.setGame(targetBuckets(response, "game"));
            log.info("===> ES-Search user coin consume target Top10. indices={}, userId={}, startDate={}, "
                            + "endDate={}, normalGiftSize={}, luckyGiftSize={}, gameSize={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    result.getNormalGift().size(), result.getLuckyGift().size(), result.getGame().size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES user coin consume target Top10 failed, indices={}, userId={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询用户金币消费去处Top10失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 按累计消费tokens统计指定用户的普通礼物、幸运礼物和游戏具体消费类型Top10。
     *
     * @param dto 用户及业务日期范围
     * @return 三类消费对应的prop_id Top10
     */
    public CoinConsumePropTopVO consumePropTop10(UserCoinAnalysisDTO dto) {
        validateDateRange(dto);
        List<String> indices = getIndices(dto);
        long searchStartedAt = System.currentTimeMillis();

        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        boolBuilder.filter(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        EsSearchUtil.setDateEQ(boolBuilder, "dt", "yyyy-MM-dd", dto.getStartDate(), dto.getEndDate());
        boolBuilder.filter(EsSearchUtil.getTermsOr("business_type", List.of(
                EBusinessType.SEND_GIFT.getCode(),
                EBusinessType.SEND_LUCKY_GIFT.getCode(),
                EBusinessType.GAME_CONSUME.getCode()
        )));

        SearchRequest request = SearchRequest.of(search -> search
                .index(indices)
                .ignoreUnavailable(true)
                .size(0)
                .query(boolBuilder.build()._toQuery())
                .aggregations("normal_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm("business_type", EBusinessType.SEND_GIFT.getCode()))
                        .aggregations("props", props -> props
                                .terms(terms -> terms.field("prop_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("lucky_gift", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm("business_type", EBusinessType.SEND_LUCKY_GIFT.getCode()))
                        .aggregations("props", props -> props
                                .terms(terms -> terms.field("prop_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens")))))
                .aggregations("game", aggregation -> aggregation
                        .filter(EsSearchUtil.getTerm("business_type", EBusinessType.GAME_CONSUME.getCode()))
                        .aggregations("props", props -> props
                                .terms(terms -> terms.field("prop_id")
                                        .size(TOP_SIZE)
                                        .shardSize(TOP_SHARD_SIZE)
                                        .order(NamedValue.of("tokens", SortOrder.Desc)))
                                .aggregations("tokens", tokens -> tokens.sum(sum -> sum.field("tokens"))))));

        try {
            log.debug("===> ES-Search user coin consume prop Top10 DSL. request={}", request);
            SearchResponse<Void> response = client.search(request, Void.class);
            if (response.timedOut()) {
                throw new ServiceException("用户金币具体消费Top10查询超时，未返回完整结果");
            }

            CoinConsumePropTopVO result = new CoinConsumePropTopVO();
            result.setNormalGift(propBuckets(response, "normal_gift"));
            result.setLuckyGift(propBuckets(response, "lucky_gift"));
            result.setGame(propBuckets(response, "game"));
            log.info("===> ES-Search user coin consume prop Top10. indices={}, userId={}, startDate={}, "
                            + "endDate={}, normalGiftSize={}, luckyGiftSize={}, gameSize={}, esTookMs={}, searchCostMs={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    result.getNormalGift().size(), result.getLuckyGift().size(), result.getGame().size(),
                    response.took(), System.currentTimeMillis() - searchStartedAt);
            return result;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES user coin consume prop Top10 failed, indices={}, userId={}, startDate={}, endDate={}, "
                            + "searchCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getStartDate(), dto.getEndDate(),
                    System.currentTimeMillis() - searchStartedAt, error.getMessage(), error);
            throw new ServiceException("查询用户金币具体消费Top10失败，error=" + error.getMessage(), error);
        }
    }

    /**
     * 校验必填参数并解析业务日期范围。
     *
     * @param dto 查询参数
     */
    private static void validateDateRange(UserCoinAnalysisDTO dto) {
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
     * 根据业务日期生成需要查询的按天物理索引。
     */
    private static List<String> getIndices(UserCoinAnalysisDTO dto) {
        return EsSearchUtil.getIndices(
                EsIndexAlias.SANO_WALLET_COIN_RECORD,
                dto.getStartDate() + " 00:00:00",
                dto.getEndDate() + " 23:59:59"
        );
    }

    /**
     * 根据业务日期生成需要查询的金币按天物理索引。
     */
    private static List<String> getIndices(RoomWalletAnalysisDTO dto) {
        return EsSearchUtil.getIndices(
                EsIndexAlias.SANO_WALLET_COIN_RECORD,
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
     * 将指定业务类型下的ta_user_id聚合桶转换为消费去处排行。
     */
    private static List<CoinConsumeTargetVO> targetBuckets(SearchResponse<Void> response, String aggregationName) {
        List<LongTermsBucket> buckets = response.aggregations().get(aggregationName)
                .filter().aggregations().get("targets").lterms().buckets().array();
        List<CoinConsumeTargetVO> result = new ArrayList<>(buckets.size());
        for (LongTermsBucket bucket : buckets) {
            CoinConsumeTargetVO item = new CoinConsumeTargetVO();
            item.setTargetUserId(Math.toIntExact(bucket.key()));
            item.setTokens(Math.round(bucket.aggregations().get("tokens").sum().value()));
            result.add(item);
        }
        return result;
    }

    /**
     * 将指定业务类型下的prop_id聚合桶转换为具体消费排行。
     */
    private static List<CoinConsumePropVO> propBuckets(SearchResponse<Void> response, String aggregationName) {
        List<LongTermsBucket> buckets = response.aggregations().get(aggregationName)
                .filter().aggregations().get("props").lterms().buckets().array();
        List<CoinConsumePropVO> result = new ArrayList<>(buckets.size());
        for (LongTermsBucket bucket : buckets) {
            CoinConsumePropVO item = new CoinConsumePropVO();
            item.setPropId(Math.toIntExact(bucket.key()));
            item.setTokens(Math.round(bucket.aggregations().get("tokens").sum().value()));
            result.add(item);
        }
        return result;
    }
}
