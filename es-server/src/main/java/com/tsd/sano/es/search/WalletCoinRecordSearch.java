package com.tsd.sano.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.tsd.sano.es.controller.sta.vo.WeekStatVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

        String index = EsIndexAlias.SANO_WALLET_COIN_RECORD;
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(index);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        List<FieldValue> values2 = new ArrayList<>();
        for (Integer str : roomIds) {
            values2.add(FieldValue.of(str));
        }
        // 房间集合
        boolBuilder.must(TermsQuery.of(t -> t
                .field("room_id")
                .terms(new TermsQueryField.Builder()
                        .value(values2).build())
        )._toQuery());
        // 时间段
        boolBuilder.must(RangeQuery.of(r -> r
                .date(d -> d.field("create_time")
                        .format("yyyy-MM-dd HH:mm:ss")
                        .gte(startTime)
                        .lte(endTime)
                )
        )._toQuery());
        // 消费
        boolBuilder.must(TermQuery.of(t -> t.field("status").value(-1))._toQuery());
        searchBuilder.query(boolBuilder.build()._toQuery()).from(0).size(0);

        searchBuilder
                // 直播间消费人数：user_id去重。
                .aggregations("consume_user_count", a -> a.cardinality(c -> c.field("user_id")))
                // 直播间消费总数：tokens累计流水。
                .aggregations("consume_tokens", a -> a.sum(s -> s.field("tokens")))
                // 直播间幸运礼物消费数：business_type=11时tokens累计流水。
                .aggregations("lucky_gift", a -> a
                        .filter(f -> f.term(t -> t.field("business_type").value(11)))
                        .aggregations("tokens", sub -> sub.sum(s -> s.field("tokens"))))
                // 直播间游戏消费数：business_type=21时tokens累计流水。
                .aggregations("game", a -> a
                        .filter(f -> f.term(t -> t.field("business_type").value(21)))
                        .aggregations("tokens", sub -> sub.sum(s -> s.field("tokens"))));

        try {
            SearchResponse<Void> response = client.search(searchBuilder.build(), Void.class);
            Map<String, Aggregate> aggregations = response.aggregations();

            WeekStatVO stat = new WeekStatVO();
            stat.setConsumeUserCount(Math.round(aggregations.get("consume_user_count").cardinality().value()));
            stat.setConsumeTokens(Math.round(aggregations.get("consume_tokens").sum().value()));
            stat.setLuckyGiftTokens(Math.round(aggregations.get("lucky_gift").filter().aggregations().get("tokens").sum().value()));
            stat.setGameTokens(Math.round(aggregations.get("game").filter().aggregations().get("tokens").sum().value()));
            log.info("===> ES-Search wallet coin week stat. roomCount={}, startTime={}, endTime={}, stat={}", roomIds.size(), startTime, endTime, stat);
            return stat;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search wallet coin week stat failed, roomCount={}, startTime={}, endTime={}, error={}",
                    roomIds.size(), startTime, endTime, e.getMessage(), e);
            return new WeekStatVO();
        }
    }


}
