package com.tsd.sano.es.modules.config;

import lombok.Getter;
import lombok.Setter;

import java.time.Duration;

/**
 * Polling单表串行同步参数。
 *
 * <p>该配置只描述Polling主循环和整批重试，不包含对账线程及对账队列。</p>
 */
@Getter
@Setter
public class PollingConfig {

    /**
     * 是否启用Polling调度和单表Worker。
     */
    private boolean enabled;

    /**
     * 当前实例允许同时持有并运行的Polling表数量。
     */
    private int maxActiveTables = 5;

    /**
     * 当前日期查询为空后再次轮询MySQL的等待时间。
     */
    private Duration pollInterval = Duration.ofSeconds(5);

    /**
     * 次日零点后继续接收旧日期晚到数据的等待时间。
     */
    private Duration dateCloseDelay = Duration.ofMinutes(10);

    /**
     * 单次按日期和递增ID读取MySQL的最大记录数。
     */
    private int readBatchSize = 3000;

    /**
     * ES整批Bulk失败后的重试次数，不包含首次请求。
     */
    private int bulkRetryTimes = 2;

    /**
     * 每次ES整批Bulk重试前的等待时间。
     */
    private Duration bulkRetryInterval = Duration.ofSeconds(1);
}
