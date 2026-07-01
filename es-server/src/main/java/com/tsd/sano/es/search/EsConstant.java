package com.tsd.sano.es.search;

/**
 * ES常量
 *
 * @author lxw
 * @date 2021-06-09 10:59
 */
public interface EsConstant {

    /**
     * 查询数据
     */
    String OPT_LIST = "OPT_LIST";

    /**
     * 总数
     */
    String OPT_COUNT = "OPT_COUNT";

    /**
     * 数据和总数
     */
    String OPT_LIST_COUNT = "OPT_LIST_COUNT";

    /**
     * 聚类
     */
    String OPT_AGG = "OPT_AGG";

    /**
     * AND 并且
     */
    String AND = "AND";

    /**
     * OR  或者
     */
    String OR = "OR";

    /**
     * NOT  非
     */
    String NOT = "NOT";

    /**
     * EQ EQUAL等于 适用于不分词字段
     */
    String EQ = "EQ";

    /**
     * GT GREATER THAN大于，适用于数字、时间字段
     */
    String GT = "GT";

    /**
     * GTE  GREATER THAN AND EQUAL 大于等于，适用于数字、时间字段
     */
    String GTE = "GTE";

    /**
     * LT LESS THAN 小于，适用于数字、时间字段
     */
    String LT = "LT";


    /**
     * LTE  LESS THAN AND EQUAL 小于等于，适用于数字、时间字段
     */
    String LTE = "LTE";

    /**
     * 模糊，适合用于分词字段
     */
    String MATCH = "MATCH";

    /**
     * 短语，适合用于分词字段
     */
    String MATCH_PHRASE = "MATCH_PHRASE";

    /**
     * 短语前缀，适合用于分词字段
     */
    String MATCH_PHRASE_PREFIX = "MATCH_PHRASE_PREFIX";

    /**
     * 单词前缀匹配，适用于不分词字段
     */
    String PREFIX = "PREFIX";

    /**
     * http请求状态码200
     */
    int HTTP_200 = 200;

}
