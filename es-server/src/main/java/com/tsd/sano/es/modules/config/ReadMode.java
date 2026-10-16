package com.tsd.sano.es.modules.config;

/**
 * 每日同步任务的MySQL读取方式，不改变ES物理索引周期和Bulk写入策略。
 */
public enum ReadMode {

    /** 默认按数值ID游标分页，暂停后从连续安全断点续读，适用于大数据量表。 */
    CURSOR,

    /** 整日一次读取，未完成时整日重放；适用于每日数据量较小且没有唯一数值游标的表。 */
    FULL_DAY
}
