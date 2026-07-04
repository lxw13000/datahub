package com.tsd.sano.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tsd.sano.es.core.util.TimeUtils;
import com.tsd.sano.es.controller.sta.vo.CoinRecordVO;
import com.tsd.sano.es.controller.sta.vo.WeekStatVO;
import com.tsd.sano.es.search.util.EsSearchUtil;
import org.apache.commons.lang3.StringUtils;
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

        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(EsIndexAlias.SANO_WALLET_COIN_RECORD);
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


    /**
     * 深分页查询用户金币流水，按create_time、id倒序返回。
     *
     * @param userId         用户ID
     * @param businessType   业务类型
     * @param startTime      开始时间，格式yyyy-MM-dd HH:mm:ss
     * @param endTime        结束时间，格式yyyy-MM-dd HH:mm:ss
     * @param pageSize       每页条数
     * @param lastCreateTime 上一页最后一条记录的create_time
     * @param lastId         上一页最后一条记录的id
     * @return 金币流水列表
     */
    public List<CoinRecordVO> searchCoinRecords(Long userId, Long businessType,
                                                String startTime, String endTime, Integer pageSize,
                                                String lastCreateTime, Long lastId) {
        long totalStartMillis = System.currentTimeMillis();
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder();
        searchBuilder.index(EsIndexAlias.SANO_WALLET_COIN_RECORD);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();

        // 固定查询用户ID，ES字段使用下划线命名。
        if (userId != null) {
            boolBuilder.must(EsSearchUtil.getTerm("user_id", userId));
        }
        // 可选查询业务类型，ES字段使用下划线命名。
        if (businessType != null) {
            boolBuilder.must(EsSearchUtil.getTerm("business_type", businessType));
        }
        // 时间段
        EsSearchUtil.setDateEQ(boolBuilder, "create_time", TimeUtils.BASIC, startTime, endTime);
        searchBuilder.query(boolBuilder.build()._toQuery());

        // search_after深分页必须和排序字段一一对应，顺序也必须保持一致。
        EsSearchUtil.setOrder(searchBuilder, "create_time", SortOrder.Desc);
        EsSearchUtil.setOrder(searchBuilder, "id", SortOrder.Desc);
        searchBuilder.size(pageSize);
        if (StringUtils.isNotBlank(lastCreateTime) && lastId != null) {
            searchBuilder.searchAfter(List.of(FieldValue.of(lastCreateTime), FieldValue.of(lastId)));
        }
        long buildCostMs = System.currentTimeMillis() - totalStartMillis;

        try {
            long searchStartMillis = System.currentTimeMillis();
            SearchResponse<CoinRecordVO> response = client.search(searchBuilder.build(), CoinRecordVO.class);
            long searchCostMs = System.currentTimeMillis() - searchStartMillis;

            long parseStartMillis = System.currentTimeMillis();
            List<CoinRecordVO> records = new ArrayList<>();
            for (Hit<CoinRecordVO> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    records.add(hit.source());
                }
            }
            long parseCostMs = System.currentTimeMillis() - parseStartMillis;
            long totalCostMs = System.currentTimeMillis() - totalStartMillis;

            log.info("===> ES-Search coin records. userId={}, businessType={}, startTime={}, endTime={}, pageSize={}, lastCreateTime={}, lastId={}, size={}, buildCostMs={}, searchCostMs={}, parseCostMs={}, totalCostMs={}",
                    userId, businessType, startTime, endTime, pageSize, lastCreateTime, lastId, records.size(),
                    buildCostMs, searchCostMs, parseCostMs, totalCostMs);
            return records;
        } catch (IOException | ElasticsearchException e) {
            log.error("ES search coin records failed, userId={}, businessType={}, startTime={}, endTime={}, pageSize={}, lastCreateTime={}, lastId={}, buildCostMs={}, totalCostMs={}, error={}",
                    userId, businessType, startTime, endTime, pageSize, lastCreateTime, lastId,
                    buildCostMs, System.currentTimeMillis() - totalStartMillis, e.getMessage(), e);
            return new ArrayList<>();
        }
    }


}
