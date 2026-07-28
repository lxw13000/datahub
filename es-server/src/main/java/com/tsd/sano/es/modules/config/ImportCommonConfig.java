package com.tsd.sano.es.modules.config;

import lombok.Getter;
import lombok.Setter;

/**
 * T+1、Polling及未来同步引擎共用的资源协调参数。
 */
@Getter
@Setter
public class ImportCommonConfig {

    /**
     * drain等待Reader、队列、Bulk和持久任务状态全部到达安全边界的最长秒数。
     */
    private int drainTimeoutSeconds = 600;

    /**
     * 所有同步引擎合计允许的在途ES Bulk请求数。
     */
    private int globalBulkConcurrency = 3;

    /**
     * Polling存在等待请求时为其保留的并发数。
     */
    private int pollingReservedConcurrency = 2;

    /**
     * Polling空闲时T+1最多可使用的并发数。
     */
    private int tPlusOneMaxConcurrency = 3;
}
