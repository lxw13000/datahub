package com.tsd.sano.es.importer.service;

import com.tsd.sano.es.importer.model.ImportContext;
import com.tsd.sano.es.importer.model.ImportStatistics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 导入监控日志组件。
 *
 * <p>当前以轻量日志为主，不依赖外部监控系统；后续需要接Prometheus、
 * Redis或管理后台时，可在这里统一扩展。</p>
 */
@Service
public class ImportMonitor {

    private static final Logger log = LoggerFactory.getLogger(ImportMonitor.class);

    /**
     * 记录导入开始信息。
     */
    public void onStart(ImportContext context) {
        log.info("===> ES-Import monitor start. alias={}, index={}, table={}, date={}",
                context.getConfig().getIndexAlias(),
                context.getConfig().getIndexName(),
                context.getConfig().getTableName(),
                context.getConfig().getImportDate());
    }

    /**
     * 记录导入完成摘要。
     */
    public void onFinish(ImportContext context) {
        ImportStatistics statistics = context.getStatistics();
        long costMs = Math.max(0, statistics.getEndTime() - statistics.getStartTime());
        log.info("===> ES-Import monitor finish. alias={}, total={}, read={}, success={}, failed={}, bulkCount={}, costMs={}",
                context.getConfig().getIndexAlias(),
                statistics.getTotal().get(),
                statistics.getRead().get(),
                statistics.getSuccess().get(),
                statistics.getFailed().get(),
                statistics.getBulkCount().get(),
                costMs);
    }

    /**
     * 记录导入失败摘要。
     */
    public void onError(ImportContext context, Exception error) {
        ImportStatistics statistics = context.getStatistics();
        log.error("===> ES-Import monitor failed. alias={}, total={}, read={}, success={}, failed={}, error={}",
                context.getConfig().getIndexAlias(),
                statistics.getTotal().get(),
                statistics.getRead().get(),
                statistics.getSuccess().get(),
                statistics.getFailed().get(),
                error.getMessage());
    }
}
