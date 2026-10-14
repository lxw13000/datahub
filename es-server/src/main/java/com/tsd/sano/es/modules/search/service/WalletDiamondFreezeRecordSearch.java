package com.tsd.sano.es.modules.search.service;

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
import com.tsd.sano.es.controller.diamondfreeze.dto.SearchDiamondFreezeRecordDTO;
import com.tsd.sano.es.controller.diamondfreeze.vo.DiamondFreezeRecordVO;
import com.tsd.sano.es.core.util.TimeUtils;
import com.tsd.sano.es.modules.search.constant.EsIndexAlias;
import com.tsd.sano.es.modules.search.util.EsSearchUtil;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 钻石冻结记录ES查询服务。
 *
 * <p>查询按时间范围缩小到每日物理索引，并以create_time、id倒序提供稳定分页。</p>
 */
@Service
public class WalletDiamondFreezeRecordSearch {

    private static final Logger log = LoggerFactory.getLogger(WalletDiamondFreezeRecordSearch.class);

    /** Elasticsearch客户端。 */
    private final ElasticsearchClient client;

    public WalletDiamondFreezeRecordSearch(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * 查询指定条件下的钻石冻结记录总数。
     *
     * @param dto 查询条件
     * @return 符合条件的记录总数；ES查询失败时返回0
     */
    public long count(SearchDiamondFreezeRecordDTO dto) {
        List<String> indices = EsSearchUtil.getIndices(
                EsIndexAlias.SANO_WALLET_DIAMOND_FREEZE_RECORD,
                dto.getStartTime(),
                dto.getEndTime()
        );
        long startedAt = System.currentTimeMillis();
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        setBoolQuery(boolBuilder, dto);
        try {
            CountResponse response = client.count(CountRequest.of(request -> request
                    .index(indices)
                    .query(boolBuilder.build()._toQuery())
                    // 日期范围内没有对应物理索引时按0条处理，不影响其他日期查询。
                    .ignoreUnavailable(true)));
            log.info("===> ES-Search count diamond freeze records. indices={}, userId={}, consumeUserId={}, "
                            + "anchorId={}, businessType={}, freezeType={}, startTime={}, endTime={}, "
                            + "count={}, countCostMs={}",
                    indices, dto.getUserId(), dto.getConsumeUserId(), dto.getAnchorId(),
                    dto.getBusinessType(), dto.getFreezeType(), dto.getStartTime(), dto.getEndTime(),
                    response.count(), System.currentTimeMillis() - startedAt);
            return response.count();
        } catch (IOException | ElasticsearchException error) {
            log.error("ES count diamond freeze records failed, indices={}, userId={}, consumeUserId={}, "
                            + "anchorId={}, businessType={}, freezeType={}, startTime={}, endTime={}, "
                            + "countCostMs={}, error={}",
                    indices, dto.getUserId(), dto.getConsumeUserId(), dto.getAnchorId(),
                    dto.getBusinessType(), dto.getFreezeType(), dto.getStartTime(), dto.getEndTime(),
                    System.currentTimeMillis() - startedAt, error.getMessage(), error);
            return 0L;
        }
    }

    /**
     * 分页查询钻石冻结记录。
     *
     * @param dto 查询和分页条件
     * @return 钻石冻结记录；ES查询失败时返回空集合
     */
    public List<DiamondFreezeRecordVO> list(SearchDiamondFreezeRecordDTO dto) {
        long startedAt = System.currentTimeMillis();
        try {
            SearchRequest request = buildSearchRequest(dto).build();
            // 完整DSL仅在调试级别输出，避免生产环境高频查询产生大量日志。
            log.debug("===> ES-Search diamond freeze records DSL. request={}", request);
            SearchResponse<DiamondFreezeRecordVO> response = client.search(request, DiamondFreezeRecordVO.class);

            List<DiamondFreezeRecordVO> records = new ArrayList<>();
            for (Hit<DiamondFreezeRecordVO> hit : response.hits().hits()) {
                if (hit.source() != null) {
                    records.add(hit.source());
                }
            }
            log.info("===> ES-Search diamond freeze records. indices={}, userId={}, consumeUserId={}, "
                            + "anchorId={}, businessType={}, freezeType={}, startTime={}, endTime={}, "
                            + "pageSize={}, lastCreateTime={}, lastId={}, size={}, esTookMs={}, "
                            + "timedOut={}, searchCostMs={}",
                    request.index(), dto.getUserId(), dto.getConsumeUserId(), dto.getAnchorId(),
                    dto.getBusinessType(), dto.getFreezeType(), dto.getStartTime(), dto.getEndTime(),
                    dto.getPageSize(), dto.getLastCreateTime(), dto.getLastId(), records.size(),
                    response.took(), response.timedOut(), System.currentTimeMillis() - startedAt);
            return records;
        } catch (IOException | ElasticsearchException error) {
            log.error("ES search diamond freeze records failed, userId={}, consumeUserId={}, anchorId={}, "
                            + "businessType={}, freezeType={}, startTime={}, endTime={}, pageSize={}, "
                            + "lastCreateTime={}, lastId={}, searchCostMs={}, error={}",
                    dto.getUserId(), dto.getConsumeUserId(), dto.getAnchorId(), dto.getBusinessType(),
                    dto.getFreezeType(), dto.getStartTime(), dto.getEndTime(), dto.getPageSize(),
                    dto.getLastCreateTime(), dto.getLastId(), System.currentTimeMillis() - startedAt,
                    error.getMessage(), error);
            return List.of();
        }
    }

    /**
     * 构建冻结记录列表查询请求。
     */
    private SearchRequest.Builder buildSearchRequest(SearchDiamondFreezeRecordDTO dto) {
        SearchRequest.Builder searchBuilder = new SearchRequest.Builder()
                .index(EsSearchUtil.getIndices(
                        EsIndexAlias.SANO_WALLET_DIAMOND_FREEZE_RECORD,
                        dto.getStartTime(),
                        dto.getEndTime()
                ))
                .ignoreUnavailable(true);
        BoolQuery.Builder boolBuilder = new BoolQuery.Builder();
        setBoolQuery(boolBuilder, dto);
        searchBuilder.query(boolBuilder.build()._toQuery());

        // create_time和id组合排序既符合流水展示顺序，也为时间相同的记录提供稳定次序。
        EsSearchUtil.setOrder(searchBuilder, "create_time", SortOrder.Desc);
        EsSearchUtil.setOrder(searchBuilder, "id", SortOrder.Desc);
        if (dto.getSearchType() == 0) {
            searchBuilder.size(dto.getPageSize());
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
     * 将非空查询条件转换为ES精确过滤和时间范围条件。
     */
    private static void setBoolQuery(BoolQuery.Builder boolBuilder, SearchDiamondFreezeRecordDTO dto) {
        if (dto.getId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("id", dto.getId()));
        }
        if (dto.getCommonId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("common_id", dto.getCommonId()));
        }
        if (dto.getUserId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("user_id", dto.getUserId()));
        }
        if (dto.getConsumeUserId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("consume_user_id", dto.getConsumeUserId()));
        }
        if (dto.getAnchorId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("anchor_id", dto.getAnchorId()));
        }
        if (dto.getBusinessType() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("business_type", dto.getBusinessType()));
        }
        if (dto.getFreezeType() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("freeze_type", dto.getFreezeType()));
        }
        if (dto.getPropId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("prop_id", dto.getPropId()));
        }
        if (dto.getRoomId() != null) {
            boolBuilder.must(EsSearchUtil.getTerm("room_id", dto.getRoomId()));
        }
        EsSearchUtil.setDateEQ(
                boolBuilder,
                "create_time",
                TimeUtils.BASIC,
                dto.getStartTime(),
                dto.getEndTime()
        );
    }
}
