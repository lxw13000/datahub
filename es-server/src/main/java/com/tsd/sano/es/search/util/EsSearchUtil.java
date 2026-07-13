package com.tsd.sano.es.search.util;

import co.elastic.clients.elasticsearch._types.FieldSort;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.DateHistogramBucket;
import co.elastic.clients.elasticsearch._types.aggregations.LongTermsBucket;
import co.elastic.clients.elasticsearch._types.aggregations.StringTermsBucket;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Highlight;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.elasticsearch.core.search.HighlighterType;
import com.tsd.sano.es.core.exception.ServiceException;
import com.tsd.sano.es.search.EsConstant;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Es基础工具类
 *
 * @author lxw
 * @version V1.0
 * @date 2021/12/14
 **/
@Slf4j
public class EsSearchUtil {


    /**
     * 查询接口时间格式，和接口入参startTime/endTime保持一致。
     */
    private static final DateTimeFormatter QUERY_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 按天物理索引后缀格式，例如sano_wallet_coin_record_20260701。
     */
    private static final DateTimeFormatter INDEX_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;


    /**
     * 根据查询时间范围设置ES查询索引。
     *
     * <p>如果起止时间完整且格式为yyyy-MM-dd HH:mm:ss，则按天生成物理索引名，减少无关历史索引参与查询；
     * 如果时间缺失或格式异常，则回退到alias，保证查询仍可执行。</p>
     *
     * @param indexAlias 业务alias，也是物理索引名前缀
     * @param startTime  开始时间，格式yyyy-MM-dd HH:mm:ss
     * @param endTime    结束时间，格式yyyy-MM-dd HH:mm:ss
     */
    public static List<String> getIndices(String indexAlias, String startTime, String endTime) {
        if (StringUtils.isBlank(startTime) || StringUtils.isBlank(endTime)) {
            // 时间范围不完整时无法准确推导物理索引，使用alias兜底。
            return List.of(indexAlias);
        }

        try {
            LocalDate startDate = LocalDateTime.parse(startTime, QUERY_TIME_FORMATTER).toLocalDate();
            LocalDate endDate = LocalDateTime.parse(endTime, QUERY_TIME_FORMATTER).toLocalDate();
            if (endDate.isBefore(startDate)) {
                // 调用方时间倒挂时不在索引选择层抛错，回退alias交给原有时间条件处理。
                return List.of(indexAlias);
            }

            List<String> indices = new ArrayList<>();
            for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
                indices.add(indexAlias + "_" + INDEX_DATE_FORMATTER.format(date));
            }
            return indices;
        } catch (DateTimeParseException e) {
            // 时间格式不符合接口约定时回退alias，避免索引优化逻辑影响原查询兼容性。
            log.warn("===> ES-Search use alias because time format invalid. alias={}, startTime={}, endTime={}",
                    indexAlias, startTime, endTime);
            return List.of(indexAlias);
        }
    }

    /**
     * 查询一个字段是否等于匹配(term)多个值中的任意一个
     *
     * @param field  field, 字段分词，则需要加上.keyword再传入，示例”title.keyword“
     * @param values values
     * @return co.elastic.clients.elasticsearch._types.query_dsl.Query
     * @author lxw
     * @date 2024/11/11 14:03
     **/
    public static <T> Query getTermsOr(String field, List<T> values) {
        List<FieldValue> values2 = new ArrayList<>();
        for (T str : values) {
            values2.add(FieldValue.of(str));
        }
        return TermsQuery.of(t -> t
                .field(field)
                .terms(new TermsQueryField.Builder()
                        .value(values2).build())
        )._toQuery();
    }

    /**
     * 查询一个字段是否等于匹配(term)单个值
     *
     * @param field field, 字段分词，则需要加上.keyword再传入，示例”title.keyword“
     * @param value value
     * @return co.elastic.clients.elasticsearch._types.query_dsl.Query
     * @author lxw
     * @date 2024/11/11 14:03
     **/
    public static Query getTerm(String field, Object value) {
        return TermQuery.of(t -> t
                .field(field)
                .value(FieldValue.of(value))
        )._toQuery();

    }

    /**
     * 多个字段模糊匹配同一个值
     *
     * @param key        模糊匹配值
     * @param fields     需要查询的字段
     * @param searchType 查询类型,一般使用 best_fields
     *                   <p>
     *                   best_fields 适合在多个字段中找到最佳匹配字段（默认）<br/>
     *                   most_fields 适合字段内容有重复信息的情况，会对得分进行累加<br/>
     *                   cross_fields 将多个字段视为整体，适合相互补充的字段<br/>
     *                   phrase 精确短语匹配。<br/>
     *                   phrase_prefix  用于前缀匹配，适合自动补全 <br/>
     *                   <p/>
     * @return co.elastic.clients.elasticsearch._types.query_dsl.Query
     * @author lxw
     * @date 2021/12/14 14:30
     */
    public static Query multiMatchQuery(List<String> fields, String key, TextQueryType searchType) {

        return MultiMatchQuery.of(mm -> mm
                        .fields(fields)
                        .query(key)
                        .type(searchType == null ? TextQueryType.BestFields : searchType)
                        .minimumShouldMatch("1"))
                ._toQuery();
    }

    /**
     * 查询一个字段是否短语匹配多个值中的任意一个
     *
     * @param field  字段
     * @param values 值集合
     * @return co.elastic.clients.elasticsearch._types.query_dsl.Query
     * @author lxw
     * @date 2024/11/11 14:10
     **/
    public static Query getMatchPhraseOr(String field, List<String> values) {

        BoolQuery.Builder bool3 = new BoolQuery.Builder();
        int should = 0;
        for (String key : values) {
            if (StringUtils.isNotBlank(key)) {
                bool3.should(MatchPhraseQuery.of(m -> m.field(field).query(key))._toQuery());
                should++;
            }
        }
        if (should > 0) {
            bool3.minimumShouldMatch("1");
            return bool3.build()._toQuery();
        }
        return null;
    }

    /**
     * 查询一个字段分词匹配多个值中的任意一个
     *
     * @param field  字段
     * @param values 值集合
     * @return co.elastic.clients.elasticsearch._types.query_dsl.Query
     * @author lxw
     * @date 2024/11/11 14:10
     **/
    public static Query getMatchOr(String field, List<String> values) {

        BoolQuery.Builder bool3 = new BoolQuery.Builder();
        int should = 0;
        for (String key : values) {
            if (StringUtils.isNotBlank(key)) {
                bool3.should(MatchQuery.of(m -> m.field(field).query(key))._toQuery());
                should++;
            }
        }
        if (should > 0) {
            bool3.minimumShouldMatch("1");
            return bool3.build()._toQuery();
        }
        return null;
    }

    /**
     * 多个字段短语匹配单一值,任意一个字段满足即可
     *
     * @param fields fields
     * @param key    key
     * @return co.elastic.clients.elasticsearch._types.query_dsl.Query
     * @author lxw
     * @date 2024/11/11 14:03
     **/
    public static Query getMatchPhraseOr(List<String> fields, String key) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        int should = 0;
        for (String field : fields) {
            bool.should(MatchPhraseQuery.of(m -> m.field(field).query(key))._toQuery());
            should++;
        }
        if (should > 0) {
            bool.minimumShouldMatch("1");
            return bool.build()._toQuery();
        }
        return null;
    }


    /**
     * 日期类型 闭区间
     *
     * @param builder builder
     * @param field   field
     * @param format  format TimeFormatEnum
     * @param begin   begin
     * @param end     end
     * @author lxw
     * @date 2023/7/13 10:53
     **/
    public static void setDateEQ(BoolQuery.Builder builder, String field, String format, String begin, String end) {
        if (begin == null && end == null) {
            return;
        }

        if (begin != null && end == null) {
            builder.must(RangeQuery.of(r -> r
                            .date(d -> d
                                    .field(field)
                                    .gte(begin)
                                    .format(format)
                            )
                    )._toQuery()
            );
        } else if (begin == null) {
            builder.must(RangeQuery.of(r -> r
                            .date(d -> d
                                    .field(field)
                                    .lte(end)
                                    .format(format)
                            )
                    )._toQuery()
            );
        } else {
            builder.must(RangeQuery.of(r -> r
                            .date(d -> d
                                    .field(field)
                                    .gte(begin)
                                    .lte(end)
                                    .format(format)
                            )
                    )._toQuery()
            );
        }
    }


    /**
     * 设置限定字段
     *
     * @param searchBuilder sourceBuilder
     * @param includes      需要返回的字段
     * @param excludes      需要排除字段
     * @author lxw
     * @date 2024/11/8 14:33
     **/
    public static void setExcludesFields(SearchRequest.Builder searchBuilder, List<String> includes, List<String> excludes) {
        if (CollectionUtils.isEmpty(excludes)) {
            excludes = List.of("full_text");
        }
        List<String> finalExcludes = excludes;
        if (!CollectionUtils.isEmpty(includes)) {
            searchBuilder.source(c -> c.filter(f -> f.includes(includes).excludes(finalExcludes)));
        } else {
            searchBuilder.source(c -> c.filter(f -> f.excludes(finalExcludes)));
        }

    }


    /**
     * fix: Result window is too large, from + size must be less than or equal to: [10000] but was [10010]. See the scroll api for a more efficient way to request large data sets.
     * This limit can be set by changing the [index.max_result_window] index level setting.
     * 设置分页 （传入正常的前端 pageIndex 从1开始）
     *
     * @param searchBuilder 查询对象
     * @param startPage     起始页,从1开始
     * @param pageSize      每页大小
     * @author lxw
     * @date 2021/5/11 16:28
     */
    public static void setPage(SearchRequest.Builder searchBuilder, Integer startPage, Integer pageSize) {
        if (startPage > 0) {
            startPage = startPage - 1;
        } else {
            startPage = 0;
        }
        //设置分页参数
        int startIndex = startPage * pageSize;

        if (startIndex > 10000 || (startIndex + pageSize) > 10000) {
            throw new ServiceException("查询数据不允许超过1万条");
        }

        searchBuilder.from(startIndex).size(pageSize);
    }

    /**
     * 设置返回真实条数据
     *
     * @param searchBuilder 查询对象
     * @author lxw
     * @date 2025/2/24 10:47
     **/
    public static void trackTotalHits(SearchRequest.Builder searchBuilder) {

        searchBuilder.trackTotalHits(t -> t.enabled(true));
    }

    /**
     * 设置排序
     *
     * @param searchBuilder 查询对象
     * @param field         排序字段
     * @param asc           正序SortOrder.Asc，反序SortOrder.Desc
     * @author lxw
     * @date 2023/2/16 9:57
     **/
    public static void setOrder(SearchRequest.Builder searchBuilder, String field, SortOrder asc) {
        if (isNotBlank(field)) {
            searchBuilder.sort(s -> s.field(f -> f.field(field).order(asc)));
        }
    }

    /**
     * 设置排序
     *
     * @param searchBuilder 查询对象
     * @param field         排序字段
     * @param way           正序0--Direction.ASC，反序1--Direction.DESC
     * @author lxw
     * @date 2023/2/16 9:57
     **/
    public static void setOrder(SearchRequest.Builder searchBuilder, String field, Integer way) {
        if (isNotBlank(field)) {
            searchBuilder.sort(s -> s.field(getOrder(field, way)));
        }
    }

    /**
     * 获取排序
     *
     * @param field 字段
     * @param way   排序方式，正序0--Direction.ASC，反序1--Direction.DESC
     * @return co.elastic.clients.elasticsearch._types.FieldSort
     * @author lxw
     * @date 2024/11/8 14:05
     **/
    public static FieldSort getOrder(String field, Integer way) {
        SortOrder asc;
        if (way == 1) {
            asc = SortOrder.Desc;
        } else {
            asc = SortOrder.Asc;
        }
        return FieldSort.of(s -> s.field(field).order(asc));
    }

    /**
     * 设置高亮
     *
     * @param builder        查询对象
     * @param highlightField 高亮字段
     * @author lxw
     * @date 2021/5/11 16:31
     */
    public static void setHighlightField(SearchRequest.Builder builder, String highlightField) {
        if (isNotBlank(highlightField)) {
            //高亮
            Highlight.Builder highlightBuilder = new Highlight.Builder();
            highlightBuilder.fields(highlightField, new HighlightField.Builder().build())
                    .preTags("<span style='color:red'>")
                    .postTags("</span>")
                    .requireFieldMatch(false);
            builder.highlight(highlightBuilder.build());
        }
    }

    /**
     * 设置高亮
     *
     * @param builder         查询对象
     * @param highlightFields 高亮字段
     * @author lxw
     * @date 2021/5/11 16:31
     */
    public static void setHighlightField(SearchRequest.Builder builder, List<String> highlightFields) {
        if (!CollectionUtils.isEmpty(highlightFields)) {
            //高亮
            Highlight.Builder highlightBuilder = new Highlight.Builder();
            highlightBuilder.preTags("<span style='color:red'>")
                    .postTags("</span>")
                    //主要用于处理多字段高亮的情况。它的作用是指示在多字段高亮时，是否要求每个字段的高亮部分必须都匹配查询的字段。默认值： true
                    //如果为 true，在多字段查询时，每个字段必须匹配查询条件才能返回高亮。
                    //如果为 false，只要一个字段匹配查询条件，就返回该字段的高亮部分。
                    .requireFieldMatch(true)
                    .type(HighlighterType.Unified)
            //下面这两项,如果你要高亮如文字内容等有很多字的字段,必须配置,不然会导致高亮不全,文章内容缺失等
            //最大高亮分片数
//                    .fragmentSize(800000)
            //从第一个分片获取高亮片段
//                    .numberOfFragments(0)
            ;
            for (String field : highlightFields) {
                highlightBuilder.fields(field, new HighlightField.Builder().build());
            }
            builder.highlight(highlightBuilder.build());
        }
    }


    /**
     * 日期直方图取值
     *
     * @param datePrint datePrint
     * @author lxw
     * @date 2023/7/13 11:28
     **/
    public static List<Map<String, Object>> dateHistogramBucket(List<DateHistogramBucket> datePrint) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (DateHistogramBucket bucket : datePrint) {
            Map<String, Object> map = new HashMap<>();
            String key = bucket.keyAsString();
            map.put("key", key);
            map.put("label", key);
            map.put("count", bucket.docCount());
            list.add(map);
        }
        return list;
    }

    /**
     * 直接聚合取值使用的agg
     *
     * @param datePrint datePrint
     * @return java.util.List<java.util.Map < java.lang.String, java.lang.Object>>
     * @author lxw
     * @date 2023/7/13 11:33
     **/
    public static List<Map<String, Object>> stringTermsBucket(List<StringTermsBucket> datePrint) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StringTermsBucket bucket : datePrint) {
            Map<String, Object> map = new HashMap<>();
            String key = bucket.key().stringValue();
            map.put("key", key);
            map.put("label", key);
            map.put("count", bucket.docCount());
            list.add(map);
        }
        return list;
    }

    /**
     * 层级聚类
     *
     * @param datePrint      datePrint
     * @param aggLevelPrefix 父级前缀
     * @return java.util.List<java.util.Map < java.lang.String, java.lang.Object>>
     * @author lxw
     * @date 2023/7/13 11:33
     **/
    public static List<Map<String, Object>> stringTermsBucket(List<StringTermsBucket> datePrint, String aggLevelPrefix) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StringTermsBucket bucket : datePrint) {
            Map<String, Object> map = new HashMap<>();
            String key = bucket.key().stringValue();
            if (StringUtils.isNotBlank(key) && key.startsWith(aggLevelPrefix)) {
                map.put("key", key);
                map.put("label", key);
                map.put("count", bucket.docCount());
                list.add(map);
            }
        }
        return list;
    }

    /**
     * 直接聚合取值使用的agg
     *
     * @param longRareTermsBuckets datePrint
     * @return java.util.List<java.util.Map < java.lang.String, java.lang.Object>>
     * @author lxw
     * @date 2023/7/13 11:33
     **/
    public static List<Map<String, Object>> longTermsBucket(List<LongTermsBucket> longRareTermsBuckets) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (LongTermsBucket bucket : longRareTermsBuckets) {
            Map<String, Object> map = new HashMap<>();
            long key = bucket.key();
            map.put("key", String.valueOf(key));
            map.put("label", String.valueOf(key));
            map.put("count", bucket.docCount());
            list.add(map);
        }
        return list;
    }

    /**
     * 输入词匹配
     *
     * @param builder     builder
     * @param text        输入词
     * @param textPrecise 输入框的值匹配模式：0、模糊，1、精确
     * @author lxw
     * @date 2024/7/29 15:44
     **/
    public static void setText(BoolQuery.Builder builder, String text, Integer textPrecise) {
        // 检索词
        if (StringUtils.isNotBlank(text)) {
            builder.should(MatchPhraseQuery.of(pre -> pre.field("title").query(text).boost(9f))._toQuery());
            builder.should(MatchPhraseQuery.of(pre -> pre.field("title_zh").query(text).boost(9f))._toQuery());
            // 输入框的值匹配模式：0、模糊，1、精确
            if (textPrecise == 0) {
                builder.should(MatchQuery.of(pre -> pre.field("title").query(text).boost(2f))._toQuery());
                builder.should(MatchQuery.of(pre -> pre.field("title_zh").query(text).boost(2f))._toQuery());
            }
        }
    }

    /**
     * bool拼接
     *
     * @param builder3    BoolQuery
     * @param should      should数量
     * @param operateType 逻辑类型 AND、OR、NOT
     * @param query       查询条件
     * @return int should数量
     * @author lxw
     * @date 2024/11/11 13:54
     **/
    private static int getShould(BoolQuery.Builder builder3, int should, String operateType, Query query) {
        if (StringUtils.equalsIgnoreCase(operateType, EsConstant.AND)) {
            builder3.must(query);
        } else if (StringUtils.equalsIgnoreCase(operateType, EsConstant.OR)) {
            builder3.should(query);
            should++;
        } else if (StringUtils.equalsIgnoreCase(operateType, EsConstant.NOT)) {
            builder3.mustNot(query);
        } else {
            builder3.must(query);
        }
        return should;
    }


    private static boolean isBlank(CharSequence cs) {
        int strLen = length(cs);
        if (strLen != 0) {
            for (int i = 0; i < strLen; ++i) {
                if (!Character.isWhitespace(cs.charAt(i))) {
                    return false;
                }
            }

        }
        return true;
    }

    private static int length(CharSequence cs) {
        return cs == null ? 0 : cs.length();
    }

    private static boolean isNotBlank(CharSequence cs) {
        return !isBlank(cs);
    }


}
