package com.tsd.sano.es.modules.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.util.unit.DataSize;

/**
 * T+1按日建索引导入参数。
 *
 * <p>Polling和未来同步引擎不得复用这些吞吐及失败策略。</p>
 */
@Getter
@Setter
public class TPlusOneConfig {

    /**
     * 是否启用T+1定时及手工导入能力。
     */
    private boolean enabled = false;

    /**
     * T+1定时导入cron表达式，默认每天02:30执行。
     */
    private String cron = "0 30 2 * * ?";

    /**
     * 每轮调度最大运行分钟数，超过后不再启动下一条任务。
     */
    private int maxRunMinutes = 480;

    /**
     * 每轮调度最多拉取的待执行任务数。
     */
    private int taskFetchLimit = 100;

    /**
     * 每批读取MySQL数据量。
     */
    private int readBatchSize = 3000;

    /**
     * Bulk写入线程数。
     */
    private int workerCount = 8;

    /**
     * Reader到Bulk之间的队列容量。
     */
    private int queueCapacity = 50;

    /**
     * T+1排队、在途和重试批次的合计内存预算。
     */
    private DataSize queueMaxBytes = DataSize.ofMegabytes(128);

    /**
     * 单次Bulk最大文档数。
     */
    private int bulkActions = 2000;

    /**
     * 单次Bulk最大请求体大小，单位MB。
     */
    private int bulkSizeMb = 10;

    /**
     * Bulk写入最大重试次数。
     */
    private int retryTimes = 3;

    /**
     * Bulk写入重试等待时间，单位毫秒。
     */
    private long retryInterval = 1000;

    /**
     * 允许的最大失败文档数，超过后不绑定Alias。
     */
    private long maxFailedDocuments = 1000;

    /**
     * 允许的最大失败率，0.001表示0.1%。
     */
    private double maxFailureRate = 0.001D;

    /**
     * T+1建索引导入期间是否关闭ES refresh。
     */
    private boolean disableRefresh = true;

    /**
     * T+1建索引导入期间是否关闭ES副本。
     */
    private boolean disableReplica = true;


}
