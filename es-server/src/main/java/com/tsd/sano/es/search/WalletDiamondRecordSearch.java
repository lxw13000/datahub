package com.tsd.sano.es.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.tsd.sano.es.controller.diamond.dto.SearchDiamondRecordDTO;
import com.tsd.sano.es.controller.diamond.vo.DiamondRecordVO;
import com.tsd.sano.es.core.util.TimeUtils;
import com.tsd.sano.es.search.util.EsSearchUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 钻石记录检索
 *
 * @author lxw
 * @version V1.0
 * @date 2026/7/2 21:14
 */
@Service
public class WalletDiamondRecordSearch {


    /**
     * 钻石 ES 查询日志
     */
    private static final Logger log = LoggerFactory.getLogger(WalletDiamondRecordSearch.class);

    /**
     * Elasticsearch Java 客户端
     */
    private final ElasticsearchClient client;

    public WalletDiamondRecordSearch(ElasticsearchClient client) {
        this.client = client;
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
