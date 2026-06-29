package com.tsd.sano.es.importer.model;


import com.tsd.sano.es.core.config.EsImportProperties;

import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

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

    public ImportContext(EsImportConfig config,
                         ImportStatistics statistics,
                         EsImportProperties properties) {

        this.config = config;
        this.statistics = statistics;
        this.properties = properties;

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
}