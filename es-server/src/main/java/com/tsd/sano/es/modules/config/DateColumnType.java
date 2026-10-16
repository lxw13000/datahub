package com.tsd.sano.es.modules.config;

/**
 * 同步日期字段的查询方式，由Reader与对账共用，不决定物理索引的周期。
 */
public enum DateColumnType {

    /** DATE字段按业务日期等值查询。 */
    DATE,

    /** DATETIME或TIMESTAMP字段按当天开始至次日开始的左闭右开范围查询。 */
    DATETIME,

    /** yyyyMMdd格式的整数日期字段，按业务日期转换后的整数等值查询。 */
    INTEGER_DATE
}
