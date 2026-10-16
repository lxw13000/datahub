package com.tsd.sano.es.modules.config;

/**
 * 业务物理索引的组织方式；除人工Bootstrap外，同步任务仍按业务日期执行。
 */
public enum IndexMode {

    /** 每日一个物理索引，新索引在导入成功后绑定Alias。 */
    DAY,

    /** 同一自然月共享物理索引，创建时绑定Alias，不自动删除历史索引。 */
    MONTH,

    /** 同一自然年共享物理索引，创建时绑定Alias，不自动删除历史索引。 */
    YEAR,

    /** 所有日期共用显式配置的固定索引，人工全量初始化后按日覆盖新增、修改。 */
    SINGLE
}
