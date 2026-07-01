package com.tsd.sano.es.importer.model;


import com.tsd.sano.es.core.config.EsImportProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 导入上下文
 * <p>
 * 生命周期：
 * <p>
 * 创建Index ——> Reader ——> Queue ——> Bulk ——> Alias
 * <p>
 * 全流程共享
 *
 * @author lxw
 */
public class ImportContext {

    /**
     * 导入配置
     */
    private EsImportConfig config;

    /**
     * 全局统计
     */
    private ImportStatistics statistics;

    /**
     * 系统配置
     */
    private EsImportProperties properties;

    /**
     * Reader -> Bulk
     */
    private BlockingQueue<List<Map<String, Object>>> queue;

    /**
     * 导入中止标记。
     *
     * <p>Bulk线程异常时通过该标记通知Reader停止入队，避免Reader永久阻塞。</p>
     */
    private final AtomicBoolean aborted = new AtomicBoolean(false);

    /**
     * 导入中止原因，便于上层输出明确错误。
     */
    private final AtomicReference<Throwable> abortReason = new AtomicReference<>();

    /**
     * 本次导入截止时间戳，0表示不启用deadline。
     */
    private final long deadlineMillis;

    /**
     * 创建一次导入上下文，并初始化Reader到Bulk的队列。
     */
    public ImportContext(EsImportConfig config,
                         ImportStatistics statistics,
                         EsImportProperties properties) {
        this(config, statistics, properties, 0L);
    }

    /**
     * 创建一次带deadline的导入上下文。
     */
    public ImportContext(EsImportConfig config,
                         ImportStatistics statistics,
                         EsImportProperties properties,
                         long deadlineMillis) {

        this.config = config;
        this.statistics = statistics;
        this.properties = properties;
        this.deadlineMillis = deadlineMillis;

        this.queue = new LinkedBlockingQueue<>(
                properties.getQueueCapacity()
        );
    }

    public EsImportConfig getConfig() {
        return config;
    }

    public ImportStatistics getStatistics() {
        return statistics;
    }

    public EsImportProperties getProperties() {
        return properties;
    }

    public BlockingQueue<List<Map<String, Object>>> getQueue() {
        return queue;
    }

    /**
     * 标记本次导入需要中止，只保留第一个异常原因。
     */
    public void abort(Throwable error) {
        aborted.set(true);
        abortReason.compareAndSet(null, error);
    }

    public boolean isAborted() {
        return aborted.get();
    }

    public Throwable getAbortReason() {
        return abortReason.get();
    }

    public long getDeadlineMillis() {
        return deadlineMillis;
    }

    /**
     * 判断本次导入是否已到达deadline。
     */
    public boolean isDeadlineReached() {
        return deadlineMillis > 0L && System.currentTimeMillis() >= deadlineMillis;
    }

    /**
     * 标记本次导入因deadline暂停。
     */
    public void markTimeoutPartial() {
        statistics.setTimeoutPartial(true);
    }
}
