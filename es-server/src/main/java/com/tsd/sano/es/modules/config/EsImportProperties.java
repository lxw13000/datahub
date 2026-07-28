package com.tsd.sano.es.modules.config;

import com.tsd.sano.es.core.exception.ServiceException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ES导入参数配置。
 *
 * <p>所有字段均支持通过application.yml配置，配置前缀为 {@code sano.import}。</p>
 *
 * @author lxw
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "sano.import")
public class EsImportProperties {

    /**
     * T+1、Polling及未来同步引擎共用的资源协调参数。
     */
    @NestedConfigurationProperty
    private ImportCommonConfig common = new ImportCommonConfig();

    /**
     * 现有按业务日期建索引的T+1导入参数。
     */
    @NestedConfigurationProperty
    private TPlusOneConfig tPlusOne = new TPlusOneConfig();

    /**
     * 按业务日期和递增ID持续同步的Polling参数。
     */
    @NestedConfigurationProperty
    private PollingConfig polling = new PollingConfig();

    /**
     * 定时任务需要导入的表配置。
     */
    private List<SyncTableConfig> tables = List.of();

    /**
     * 配置加载时生成的T+1启用表，只读且运行期间不再重复筛选。
     */
    @Setter(AccessLevel.NONE)
    private List<SyncTableConfig> tPlusOneTables = List.of();

    /**
     * 配置加载时生成的Polling启用表，只读且运行期间不再重复筛选。
     */
    @Setter(AccessLevel.NONE)
    private List<SyncTableConfig> pollingTables = List.of();

    /**
     * 接收表配置时一次性完成默认值规范化、校验和模式分组。
     *
     * <p>Spring Boot在启动绑定 {@code sano.import.tables} 时调用该方法，后续同步任务直接读取
     * {@link #getTPlusOneTables()} 或 {@link #getPollingTables()}，不再按模式重复遍历配置。</p>
     */
    public void setTables(List<SyncTableConfig> tables) {
        if (tables == null) {
            tables = List.of();
        }
        List<SyncTableConfig> tPlusOneConfigs = new ArrayList<>();
        List<SyncTableConfig> pollingConfigs = new ArrayList<>();
        Set<String> enabledTableNames = new HashSet<>();

        for (SyncTableConfig tableConfig : tables) {
            if (tableConfig == null) {
                throw new IllegalStateException("ES sync table config cannot be null");
            }
            if (!tableConfig.isEnabled()) {
                continue;
            }

            // 仅启用表需要完成默认值规范化和运行所需的完整配置校验。
            tableConfig.normalizeAndValidate();
            // 启用表名必须唯一，避免不同模式的表名冲突。
            if (!enabledTableNames.add(tableConfig.getTableName())) {
                throw new IllegalStateException("Duplicate enabled ES sync table-name: "
                        + tableConfig.getTableName());
            }

            // 无default的穷举switch保证新增同步模式时必须同步补充分组规则。
            switch (tableConfig.getSyncMode()) {
                case T_PLUS_ONE -> tPlusOneConfigs.add(tableConfig);
                case POLLING -> pollingConfigs.add(tableConfig);
            }
        }

        this.tables = tables;
        this.tPlusOneTables = List.copyOf(tPlusOneConfigs);
        this.pollingTables = List.copyOf(pollingConfigs);
    }

    /**
     * 获取指定源表名的已启用T+1表；配置不存在或模式不匹配时阻止任务继续执行。
     */
    public SyncTableConfig requireTPlusOneTable(String tableName) {
        return tPlusOneTables.stream()
                .filter(table -> StringUtils.equals(table.getTableName(), tableName))
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        "ES sync table is disabled or mode mismatch, tableName=" + tableName
                                + ", expectedMode=" + TableSyncMode.T_PLUS_ONE));
    }

}
