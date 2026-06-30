package com.tsd.sano.es.importer.task;

import com.tsd.sano.es.core.config.EsImportProperties;
import com.tsd.sano.es.importer.model.EsImportConfig;
import com.tsd.sano.es.importer.service.EsImportService;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * ES定时导入任务。
 *
 * <p>默认关闭，需配置 sano.es.import.task-enabled=true 后才会注册执行。</p>
 */
@Component
@ConditionalOnProperty(prefix = "sano.es.import", name = "task-enabled", havingValue = "true")
public class EsImportTask {

    private static final Logger log = LoggerFactory.getLogger(EsImportTask.class);

    private final EsImportProperties properties;
    private final EsImportService importService;

    /**
     * 注入导入配置和导入服务。
     */
    public EsImportTask(EsImportProperties properties, EsImportService importService) {
        this.properties = properties;
        this.importService = importService;
    }

    /**
     * 每天按配置导入T+1数据。
     */
    @Scheduled(cron = "${sano.es.import.cron:0 30 2 * * ?}")
    public void importYesterday() {
        LocalDate importDate = LocalDate.now().minusDays(1);
        log.info("===> ES-Import scheduled task start. date={}", importDate);

        // 多表串行导入，单表异常在importOneTable中隔离处理。
        properties.getTables().stream()
                .filter(EsImportProperties.TableConfig::isEnabled)
                .forEach(table -> importOneTable(table, importDate));

        log.info("===> ES-Import scheduled task finished. date={}", importDate);
    }

    /**
     * 导入单张配置表。
     */
    private void importOneTable(EsImportProperties.TableConfig table, LocalDate importDate) {
        try {
            EsImportConfig config = new EsImportConfig();
            config.setIndexAlias(table.getIndexAlias());
            // 表名未配置时默认复用业务alias。
            config.setTableName(StringUtils.defaultIfBlank(table.getTableName(), table.getIndexAlias()));
            config.setMappingFile(table.getMappingFile());
            config.setWhereSql(table.getWhereSql());
            config.setIdColumn(table.getIdColumn());
            config.setDtColumn(table.getDtColumn());
            config.setImportDate(importDate);

            importService.importData(config);
        } catch (Exception e) {
            // 单表失败不影响后续表导入，错误通过日志汇总。
            log.error("===> ES-Import scheduled table failed. alias={}, table={}, date={}, error={}",
                    table.getIndexAlias(), table.getTableName(), importDate, e.getMessage(), e);
        }
    }
}
