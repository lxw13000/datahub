package com.tsd.sano.es.modules.config;

import lombok.Getter;
import lombok.Setter;

/**
 * T+1同步使用的资源协调参数。
 */
@Getter
@Setter
public class ImportCommonConfig {

    /**
     * drain等待Reader、队列、Bulk和持久任务状态全部到达安全边界的最长秒数。
     */
    private int drainTimeoutSeconds = 600;

    /**
     * 当前实例允许的在途ES Bulk请求总数。
     */
    private int globalBulkConcurrency = 3;

    /**
     * T+1最多可使用的Bulk并发数。
     */
    private int tPlusOneMaxConcurrency = 3;
}
