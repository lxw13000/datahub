package com.tsd.sano.es.sync.config;

/**
 * 单表自动同步模式。
 *
 * <p>配置值使用 {@code t-plus-one} 或 {@code polling}。Spring Boot宽松绑定会将
 * {@code t-plus-one} 转换为 {@link #T_PLUS_ONE}；未配置时由表配置默认使用T+1。</p>
 */
public enum TableSyncMode {

    /**
     * 现有按业务日期执行的T+1导入模式。
     */
    T_PLUS_ONE,

    /**
     * 按日期和递增ID持续读取的延迟轮询模式。
     */
    POLLING
}
