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

        properties.getTables().stream()
                .filter(EsImportProperties.TableConfig::isEnabled)
                .forEach(table -> importOneTable(table, importDate));

        log.info("===> ES-Import scheduled task finished. date={}", importDate);
    }

    private void importOneTable(EsImportProperties.TableConfig table, LocalDate importDate) {
        EsImportConfig config = new EsImportConfig();
        config.setIndexAlias(table.getIndexAlias());
        config.setTableName(StringUtils.defaultIfBlank(table.getTableName(), table.getIndexAlias()));
        config.setMappingFile(table.getMappingFile());
        config.setWhereSql(table.getWhereSql());
        config.setIdColumn(table.getIdColumn());
        config.setDtColumn(table.getDtColumn());
        config.setImportDate(importDate);

        importService.importData(config);
    }
}
