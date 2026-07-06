package com.tsd.sano.es.importer.pipeline.model;

import com.tsd.sano.es.importer.pipeline.config.EsImportProperties;
import lombok.Getter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ES导入上下文。
 *
 * <p>生命周期：创建Index -> Reader -> Queue -> Bulk -> Alias，全流程共享。</p>
 *
 * @author lxw
 */
@Getter
public class ImportContext {

    /**
     * 导入配置。
     */
    private final EsImportConfig config;

    /**
     * 全局统计。
     */
    private final ImportStatistics statistics;

    /**
     * 系统导入配置。
     */
    private final EsImportProperties properties;

    /**
     * Reader到Bulk之间的数据队列。
     */
    private final BlockingQueue<List<Map<String, Object>>> queue;

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
        this.queue = new LinkedBlockingQueue<>(properties.getQueueCapacity());
    }

    /**
     * 标记本次导入需要中止，只保留第一个异常原因。
     */
    public void abort(Throwable error) {
        aborted.set(true);
        abortReason.compareAndSet(null, error);
    }

    /**
     * 判断导入是否已被标记中止。
     */
    public boolean isAborted() {
        return aborted.get();
    }

    /**
     * 获取导入中止原因。
     */
    public Throwable getAbortReason() {
        return abortReason.get();
    }

    /**
     * 判断本次导入是否已经到达deadline。
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
