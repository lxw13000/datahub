package com.tsd.sano.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.Aggregate;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tsd.sano.es.controller.sta.dto.SearchCoinRecordDTO;
import com.tsd.sano.es.controller.sta.vo.CoinRecordVO;
import com.tsd.sano.es.controller.sta.vo.WeekStatVO;
import com.tsd.sano.es.core.util.TimeUtils;
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
        // 搜索类型，0:深度分页，1:普通分页（普通分页最多查询10000条数据，超过10000条数据请使用深度分页
        if (dto.getSearchType() == 0) {
            // search_after深分页必须和排序字段一一对应，顺序也必须保持一致。
            EsSearchUtil.setOrder(searchBuilder, "create_time", SortOrder.Desc);
            EsSearchUtil.setOrder(searchBuilder, "id", SortOrder.Desc);
            searchBuilder.size(dto.getPageSize());
            if (StringUtils.isNotBlank(dto.getLastCreateTime()) && dto.getLastId() != null) {
                searchBuilder.searchAfter(List.of(
                        FieldValue.of(dto.getLastCreateTime()),
                        FieldValue.of(dto.getLastId())
                ));
            }
        } else {
            // 普通分页同样使用ID作为次级排序，保证同一创建时间的数据跨页顺序稳定。
            EsSearchUtil.setOrder(searchBuilder, "create_time", SortOrder.Desc);
            EsSearchUtil.setOrder(searchBuilder, "id", SortOrder.Desc);
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
